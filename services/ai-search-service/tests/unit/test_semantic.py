import asyncio
import importlib.util
import json
import math
import threading
import time
import unittest
from dataclasses import replace
from unittest.mock import Mock, patch
from types import SimpleNamespace

import httpx

from settings import Settings
from schemas import SearchError
from search import SearchStore, index_definition, metadata_document
from test_search import snapshot


class SemanticChecks(unittest.TestCase):
    def test_runtime_exists(self):
        self.assertIsNotNone(importlib.util.find_spec('semantic'), 'Semantic runtime is required')

    def test_vectors_and_fingerprint(self):
        from semantic import fingerprint, validated_vector
        settings = Settings()
        self.assertEqual(validated_vector([3, 4], 2), [0.6, 0.8])
        for vector in ([0, 0], [True, 1], [math.inf, 1], [1], ['1', 1], [10**400, 1]):
            with self.assertRaises(ValueError): validated_vector(vector, 2)
        for change in ({'text_revision': 'a' * 40}, {'text_dims': 12}, {'text_provider': 'ollama'}, {'text_tokens': 128}):
            self.assertNotEqual(fingerprint(settings), fingerprint(replace(settings, **change)))
        with self.assertRaises(ValueError): replace(settings, text_provider='cloud', text_url='http://unsafe')

    def test_schema_projection_reuse_and_tombstone(self):
        from semantic import fingerprint
        fp = fingerprint(Settings())
        definition = index_definition('image', text_fingerprint=fp)
        self.assertEqual(definition['mappings']['_meta']['searchSchema'], 2)
        self.assertFalse(definition['mappings']['properties']['textVector']['index'])
        first = metadata_document(snapshot(), None, 'image', 1, fp)
        self.assertEqual(first['textState'], 'pending')
        first.update(textState='ready', textVector=[1] + [0] * 383, textAttempts=2)
        updated = metadata_document(snapshot(1, soldCount=9), first, 'image', 2, fp)
        self.assertEqual(updated['textVector'], first['textVector'])
        self.assertEqual(updated['textAttempts'], 2)
        changed = metadata_document(snapshot(2, name='Giày'), updated, 'image', 3, fp)
        self.assertNotIn('textVector', changed)
        hidden = metadata_document(snapshot(3, catalogVisible=False), first, 'image', 4, fp)
        self.assertEqual(hidden['textState'], 'none')
        self.assertNotIn('textVector', hidden)
        self.assertFalse(any(k.startswith('text') for k in metadata_document(snapshot(), None, 'image', 1)))

    def test_partial_search_rejected(self):
        store = SearchStore(Settings(), Mock())
        store.meta = Mock(return_value=('index', {}))
        for changes in ({'timed_out': True}, {'_shards': {'total': 1, 'successful': 0, 'failed': 1}},
                        {'hits': {'hits': [], 'total': {'value': 0, 'relation': 'gte'}}}):
            result = {'timed_out': False, '_shards': {'total': 1, 'successful': 1, 'failed': 0},
                      'hits': {'hits': [], 'total': {'value': 0, 'relation': 'eq'}}, **changes}
            store.client.options.return_value.search.return_value = result
            with self.assertRaises(SearchError):
                store.keyword({'keyword': 'ao', 'sort': 'RELEVANCE', 'page': 0, 'size': 20})

    def test_rebuild_rechecks_catalog_after_calibration(self):
        import manage
        settings = replace(Settings(), text_enabled=False)
        candidate, previous = Mock(), Mock()
        candidate.client.indices.exists.return_value = False
        candidate.client.count.return_value = {'count': 0}
        previous.meta.return_value = ('previous', {})
        calibrated = False
        def calibration(*args):
            nonlocal calibrated
            calibrated = True
            return {'validated': True, 'fingerprint': 'image', 'threshold': 0.8}
        def snapshots(_):
            return [snapshot(1)] if calibrated else []
        args = SimpleNamespace(maintenance=True, target='vmarket-products-v1-late-change', calibration='manifest.json')
        with patch('manage.SearchStore', side_effect=[candidate, previous]), patch('manage.ImageEncoder'), \
                patch('manage.topology'), patch('manage.snapshots', side_effect=snapshots), \
                patch('manage.calibrate', side_effect=calibration), patch('manage.Path.read_text', return_value='{}'):
            with self.assertRaises(ValueError): manage.reindex(settings, args)
        candidate.client.indices.update_aliases.assert_not_called()


class ClientChecks(unittest.IsolatedAsyncioTestCase):
    async def test_http_disconnect_removes_waiter_without_releasing_running_slot(self):
        from text_encoder import create_encoder_app, InferenceQueue
        from semantic import fingerprint
        entered, release = threading.Event(), threading.Event()
        calls = []
        class Encoder:
            def load(self): pass
            def encode(self, text):
                calls.append(text)
                if text == 'query: block':
                    entered.set()
                    release.wait(1)
                return [1, 0]
        settings = replace(Settings(), internal_key='private', text_dims=2)
        encoder = Encoder()
        queue = InferenceQueue(encoder.encode)
        with patch('text_encoder.InferenceQueue', return_value=queue):
            app = create_encoder_app(settings, encoder)
        headers = {'X-Internal-Api-Key': 'private', 'X-Embedding-Role': 'query', 'X-Embedding-Fingerprint': fingerprint(settings),
                   'X-Embedding-Deadline-Ms': str(int((time.time() + 1) * 1000))}
        disconnected = asyncio.Event()
        body_sent = False
        async def receive():
            nonlocal body_sent
            if not body_sent:
                body_sent = True
                return {'type': 'http.request', 'body': json.dumps({'model': settings.text_model, 'input': ['query: abandoned']}).encode(), 'more_body': False}
            await disconnected.wait()
            return {'type': 'http.disconnect'}
        async def send(message): pass
        scope = {'type': 'http', 'asgi': {'version': '3.0'}, 'http_version': '1.1', 'method': 'POST',
                 'scheme': 'http', 'path': '/v1/embeddings', 'raw_path': b'/v1/embeddings', 'query_string': b'',
                 'root_path': '', 'headers': [(k.lower().encode(), v.encode()) for k, v in headers.items()],
                 'server': ('test', 80), 'client': ('test', 1)}
        async with app.router.lifespan_context(app):
            calls.clear()
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url='http://test') as client:
                first = asyncio.create_task(client.post('/v1/embeddings', headers=headers, json={'model': settings.text_model, 'input': ['query: block']}))
                second = None
                try:
                    await asyncio.to_thread(entered.wait, 0.2)
                    second = asyncio.create_task(app(scope, receive, send))
                    async with asyncio.timeout(0.2):
                        while not queue.queues['query']: await asyncio.sleep(0.001)
                    disconnected.set()
                    await asyncio.wait_for(second, 0.2)
                    self.assertEqual(calls, ['query: block'])
                    release.set()
                    self.assertEqual((await first).status_code, 200)
                    await asyncio.sleep(0.02)
                    self.assertEqual(calls, ['query: block'])
                finally:
                    release.set()
                    await asyncio.gather(first, *( [second] if second else []), return_exceptions=True)

    async def test_companion_auth_input_bounds_and_queue_bounds(self):
        from text_encoder import create_encoder_app, InferenceQueue
        from semantic import EmbeddingBusy, fingerprint
        class Encoder:
            def load(self): pass
            def encode(self, text): return [1, 0]
        settings = replace(Settings(), internal_key='private', text_dims=2)
        app = create_encoder_app(settings, Encoder())
        async with app.router.lifespan_context(app):
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url='http://test') as client:
                denied = await client.get('/internal/health')
                self.assertEqual((denied.status_code, denied.json()['error']['code']), (401, 'UNAUTHORIZED'))
                headers = {'X-Internal-Api-Key': 'private', 'X-Embedding-Role': 'query', 'X-Embedding-Fingerprint': fingerprint(settings),
                           'X-Embedding-Deadline-Ms': str(int((time.time() + 1) * 1000))}
                self.assertEqual((await client.post('/v1/embeddings', headers=headers, content=b'x' * 60001)).status_code, 413)
                invalid = await client.post('/v1/embeddings', headers=headers, json={'input': ['query: a', 'query: b']})
                self.assertEqual((invalid.status_code, invalid.json()['error']['code']), (400, 'INVALID_EMBEDDING_REQUEST'))
                response = await client.post('/v1/embeddings', headers=headers, json={'model': settings.text_model, 'input': ['query: a']})
                self.assertEqual(response.status_code, 200)
                self.assertEqual(response.json()['data'][0]['embedding'], [1, 0])
                headers['X-Embedding-Deadline-Ms'] = str(int((time.time() - 1) * 1000))
                expired = await client.post('/v1/embeddings', headers=headers, json={'model': settings.text_model, 'input': ['query: expired']})
                self.assertEqual(expired.status_code, 503)
        queue = InferenceQueue(lambda _: [1, 0])
        pending = asyncio.create_task(queue.submit('catalog', 'catalog', 1))
        await asyncio.sleep(0)
        try:
            with self.assertRaises(EmbeddingBusy): await queue.submit('extra', 'catalog', 1)
        finally:
            pending.cancel()
            await asyncio.gather(pending, return_exceptions=True)

    async def test_client_prefix_cache_validation_and_fallback(self):
        from semantic import TextEmbeddings, fingerprint
        settings = replace(Settings(), text_dims=2, text_enabled=True)
        calls = []
        async def handler(request):
            body = json.loads(request.content)
            calls.append(body)
            self.assertEqual(body['input'], ['query: đồ mặc khi trời lạnh'])
            self.assertEqual(request.headers['X-Internal-Api-Key'], settings.internal_key)
            return httpx.Response(200, json={'fingerprint': fingerprint(settings), 'data': [{'index': 0, 'embedding': [3, 4]}]})
        client = TextEmbeddings(settings, transport=httpx.MockTransport(handler))
        try:
            self.assertEqual(await client.query('đồ mặc khi trời lạnh'), [0.6, 0.8])
            self.assertEqual(await client.query('đồ mặc khi trời lạnh'), [0.6, 0.8])
            self.assertEqual(len(calls), 1)
            for data in ({'data': [{'index': 1, 'embedding': [1, 0]}]},
                         {'data': [{'index': 0, 'embedding': [0, 0]}]},
                         {'data': [{'index': 0, 'embedding': [10**400, 1]}]},
                         {'fingerprint': 'wrong', 'data': [{'index': 0, 'embedding': [1, 0]}]}):
                client.client = httpx.AsyncClient(transport=httpx.MockTransport(lambda _: httpx.Response(200, json={'fingerprint': fingerprint(settings), **data})))
                self.assertIsNone(await client.query(str(data)))
                await client.client.aclose()
        finally: await client.close()

    async def test_timeout_falls_back(self):
        from semantic import TextEmbeddings
        async def slow(_):
            await asyncio.sleep(1)
        client = TextEmbeddings(replace(Settings(), text_query_ms=10), transport=httpx.MockTransport(slow))
        try: self.assertIsNone(await client.query('slow'))
        finally: await client.close()

    async def test_queue_fairness_expiry_and_cancellation_keep_slot(self):
        from text_encoder import InferenceQueue
        entered, release = threading.Event(), threading.Event()
        calls = []
        def encode(text):
            calls.append(text)
            if text == 'first':
                entered.set()
                release.wait(2)
            return [1, 0]
        queue = InferenceQueue(encode)
        runner = asyncio.create_task(queue.run())
        first = asyncio.create_task(queue.submit('first', 'query', 1))
        try:
            await asyncio.to_thread(entered.wait, 1)
            first.cancel()
            query = asyncio.create_task(queue.submit('second', 'query', 1))
            catalog = asyncio.create_task(queue.submit('product', 'catalog', 1))
            expired = asyncio.create_task(queue.submit('expired', 'query', 0.01))
            await asyncio.sleep(0.03)
            self.assertEqual(calls, ['first'])
            release.set()
            await asyncio.gather(query, catalog)
            with self.assertRaises(TimeoutError): await expired
            self.assertEqual(calls, ['first', 'product', 'second'])
        finally:
            release.set()
            runner.cancel()
            await asyncio.gather(first, runner, return_exceptions=True)


class TextCalibrationChecks(unittest.IsolatedAsyncioTestCase):
    async def test_recall_wins_within_false_acceptance_limit_and_held_out_gates(self):
        from text_maintenance import calibrate_text

        class Store:
            semantic_used = 0
            client = Mock(search=Mock(return_value={'hits': {'hits': [{'_score': 1.5}, {'_score': 1.9}]}}))
            def meta(self): return 'index', {}
            def keyword(self, params, vector=None, fingerprint=None, threshold=None):
                if vector is None:
                    return {'results': []}  # every intent query is a lexical miss
                self.semantic_used += 1
                kind, i = params['keyword'][1], int(params['keyword'][2:])
                # 0.5: 100% recall, 10% false acceptance; 0.9: 50% recall, 0% false acceptance.
                if kind == 'p':
                    found = threshold <= 0.5 or (threshold <= 0.9 and i < 5)
                    return {'results': [{'id': f'A{i}'}] if found else []}
                return {'results': [{'id': 'X'}] if threshold <= -1 or (threshold <= 0.5 and i == 0) else []}

        class Encoder:
            fingerprint = 'text-fp'
            def __init__(self, _): pass
            async def query(self, text): return [1.0]
            async def close(self): pass

        split = lambda prefix: ([{'keyword': f'{prefix}p{i}', 'acceptableProductIds': [f'A{i}']} for i in range(10)]
                                + [{'keyword': f'{prefix}n{i}', 'acceptableProductIds': []} for i in range(10)])
        with patch('text_maintenance.TextEmbeddings', Encoder):
            report = await calibrate_text(Store(), Settings(), {'calibration': split('c'), 'heldOut': split('h')})
        self.assertEqual(report['threshold'], 0.5)
        self.assertEqual((report['heldOut']['recallAt5'], report['heldOut']['falseAcceptance']), (1.0, 0.1))
        self.assertTrue(report['validated'])
