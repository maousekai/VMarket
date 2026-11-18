"""Opt-in real ES/Rabbit/native encoder/API/gateway check; Product snapshot HTTP stub.

Start only the named dependencies in README. Never starts Docker or downloads weights.
Uses a unique test index/alias/queue from environment; leaves no catalog writes behind.
"""
import asyncio
import io
import json
import os
import threading
import time
import unittest
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

import httpx
import pika
from elasticsearch import Elasticsearch, NotFoundError
from PIL import Image

from event_consumer import connection_parameters, declare_topology
from images import ImageEncoder
from search import SearchStore, index_definition
from semantic import TextEmbeddings, fingerprint
from settings import Settings
from text_maintenance import calibrate_text


def wait_for(check, seconds=45):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            value = check()
            if value: return value
        except (httpx.HTTPError, NotFoundError):
            pass
        time.sleep(0.1)
    raise AssertionError('System condition did not become ready')


class SemanticPipeline(unittest.TestCase):
    def test_events_real_model_gateway_and_fallback(self):
        settings = Settings.load(env_file=Path('/nonexistent'))
        if not settings.alias.startswith('fr-srch06-test-') or not settings.queue.startswith('fr-srch06-test-'):
            raise ValueError('System check requires isolated fr-srch06-test- alias and queue')
        index = 'vmarket-products-v1-system-' + uuid.uuid4().hex
        manifest = json.loads((Path(__file__).parents[1] / 'fixtures/semantic-quality.json').read_text(encoding='utf-8'))
        snapshots = {}
        for item in manifest['products']:
            snapshots[item['id']] = {**item, 'productVersion': 0, 'catalogVisible': True, 'deleted': False, 'deletedAt': None,
                'shopId': 'test-shop', 'categoryId': 'child', 'categoryPath': ['root', 'child'], 'imageUrls': [],
                'variantPrices': [100, 1000], 'minPrice': 100, 'maxPrice': 1000,
                'createdAt': '2026-11-09T00:00:00Z', 'updatedAt': '2026-11-09T00:00:00Z'}
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                if self.headers.get('X-Internal-Api-Key') != settings.internal_key:
                    self.send_error(401); return
                data = snapshots.get(self.path.rsplit('/', 1)[-1])
                if data is None:
                    self.send_error(404); return
                raw = json.dumps(data).encode()
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)
            def log_message(self, *_): pass
        # Loopback by default; set SEARCH_STUB_BIND=0.0.0.0 when Search runs in a container.
        bind = (os.environ.get('SEARCH_STUB_BIND', '127.0.0.1'), urlsplit(settings.product_url).port or 8084)
        server = ThreadingHTTPServer(bind, Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        client = Elasticsearch(settings.es_url, request_timeout=5, max_retries=0)
        image = ImageEncoder(settings)
        image.load()
        definition = index_definition(image.fingerprint, settings, fingerprint(settings))
        definition['mappings']['_meta'].update(calibration={'fingerprint': image.fingerprint, 'validated': True, 'threshold': -1},
            textCalibration={'fingerprint': fingerprint(settings), 'validated': True, 'threshold': -1})
        client.indices.create(index=index, body=definition)
        client.indices.put_alias(index=index, name=settings.alias, is_write_index=True)
        try:
            publisher_parameters = connection_parameters(settings)
            publisher_parameters.heartbeat = 600  # Operator quality checks can take minutes; runtime consumer keeps its normal heartbeat.
            with pika.BlockingConnection(publisher_parameters) as connection, \
                    httpx.Client(base_url=os.environ['SEARCH_SYSTEM_URL'], trust_env=False, timeout=5) as api:
                channel = connection.channel()
                declare_topology(channel, settings)
                channel.confirm_delivery()
                def publish(identity, event='ProductUpdated'):
                    channel.basic_publish(exchange=settings.exchange, routing_key=event, mandatory=True,
                        body=json.dumps({'eventId': str(uuid.uuid4()), 'eventType': event, 'timestamp': int(time.time() * 1000),
                                         'payload': {'productId': identity}}), properties=pika.BasicProperties(delivery_mode=2))
                for identity in snapshots: publish(identity, 'ProductCreated')
                for identity in snapshots:
                    wait_for(lambda: client.get(index=index, id=identity)['_source'].get('textState') == 'ready')
                client.indices.refresh(index=index)
                wait_for(lambda: api.get('/api/ai/search/health').json()['semantic'] == 'ready')
                started = time.monotonic()
                response = api.get('/api/ai/search', params={'keyword': 'chống rét'})
                self.assertEqual(response.status_code, 200, response.text)
                # Synthetic admission threshold tests integration; real quality is evaluated separately below.
                self.assertEqual(response.json()['totalElements'], len(snapshots))
                self.assertIn('warmth', {h['id'] for h in response.json()['results']})
                self.assertLess(time.monotonic() - started, 1)
                self.assertEqual(api.get('/api/ai/search', params={'keyword': 'chống rét', 'minPrice': 200, 'maxPrice': 900}).json()['totalElements'], 0)
                gateway = os.environ.get('SEARCH_GATEWAY_URL')
                if gateway:
                    self.assertEqual(api.get(gateway + '/api/ai/search', params={'keyword': 'chống rét'}).status_code, 200)
                    self.assertNotEqual(api.get(gateway + '/v1/embeddings').status_code, 200)
                # Exercise image upload through the existing API while the native text process is live.
                data = io.BytesIO()
                Image.new('RGB', (256, 256), 'red').save(data, format='PNG')
                self.assertEqual(api.post('/api/ai/search/image', files={'file': ('query.png', data.getvalue(), 'image/png')}).status_code, 200)
                snapshots['warmth']['productVersion'] = 1
                snapshots['warmth']['name'] = 'Áo len cập nhật'
                publish('warmth')
                wait_for(lambda: client.get(index=index, id='warmth')['_source'].get('productVersion') == 1
                         and client.get(index=index, id='warmth')['_source'].get('textState') == 'ready')
                store = SearchStore(settings, client)
                try:
                    report = asyncio.run(calibrate_text(store, settings, manifest))
                except ValueError as error:
                    report = {'validated': False, 'error': str(error), 'fingerprint': fingerprint(settings)}
                path = Path('/app/.local/text-system-quality.json')
                path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
                print('REAL_MODEL_QUALITY=' + json.dumps(report, ensure_ascii=False))
                if os.getenv('SEARCH_SEMANTIC_LOAD_SECONDS'):
                    async def load():
                        reports = []
                        async with httpx.AsyncClient(base_url=os.environ.get('SEARCH_GATEWAY_URL', os.environ['SEARCH_SYSTEM_URL']),
                                trust_env=False, timeout=2, limits=httpx.Limits(max_connections=200, max_keepalive_connections=200)) as load_api:
                            for mode in ('uncached', 'warm'):
                                await load_api.get('/api/ai/search', params={'keyword': 'Áo len'})
                                started = time.time()
                                end = time.monotonic() + int(os.environ['SEARCH_SEMANTIC_LOAD_SECONDS'])
                                rows, counter = [], 0
                                async def user():
                                    nonlocal counter
                                    while time.monotonic() < end:
                                        counter += 1
                                        number = counter
                                        query = 'áo' + ' ' * (number % 90 + 1) + 'len' + ' ' * (number // 90 % 90 + 1) + 'cổ' if mode == 'uncached' else 'Áo len'
                                        begin = time.monotonic()
                                        try:
                                            result = await load_api.get('/api/ai/search', params={'keyword': query})
                                            rows.append((result.status_code, time.monotonic() - begin))
                                        except httpx.HTTPError:
                                            rows.append((0, time.monotonic() - begin))
                                        await asyncio.sleep(1)
                                async def mutations():
                                    while time.monotonic() < end:
                                        snapshots['warmth']['productVersion'] += 1
                                        snapshots['warmth']['description'] += ' mềm'
                                        publish('warmth')
                                        await asyncio.sleep(2)
                                await asyncio.gather(*(user() for _ in range(200)), mutations())
                                durations = sorted(r[1] for r in rows)
                                reports.append({'mode': mode, 'users': 200, 'thinkSeconds': 1, 'mutationEverySeconds': 2,
                                    'catalogSize': len(snapshots), 'startedEpoch': started, 'endedEpoch': time.time(),
                                    'requests': len(rows), 'successes': sum(r[0] == 200 for r in rows),
                                    'p95Seconds': durations[int((len(durations) - 1) * 0.95)], 'maxSeconds': max(durations)})
                        return reports
                    load_report = asyncio.run(load())
                    Path('/app/.local/text-system-load.json').write_text(json.dumps(load_report, indent=2))
                    print('FOCUSED_LOAD=' + json.dumps(load_report))
                # Optional handoff for a local Postman run while isolated fixtures are alive.
                if os.getenv('SEARCH_POSTMAN_HANDOFF') == 'true':
                    marker = Path('/app/.local/postman-ready')
                    marker.write_text('ready')
                    def postman_finished():
                        connection.process_data_events(time_limit=0.01)
                        return not marker.exists()
                    wait_for(postman_finished, seconds=180)
                # Compatibility failure must preserve the original lexical query and public envelope.
                _, meta = store.meta()
                client.indices.put_mapping(index=index, _meta={**meta, 'textCalibration': {}})
                response = api.get('/api/ai/search', params={'keyword': 'Áo len'})
                self.assertEqual(response.status_code, 200)
                self.assertIn('warmth', {h['id'] for h in response.json()['results']})
                snapshots['warmth'].update(productVersion=snapshots['warmth']['productVersion'] + 1, catalogVisible=False)
                publish('warmth')
                wait_for(lambda: client.get(index=index, id='warmth')['_source']['productVersion'] == snapshots['warmth']['productVersion'])
                source = client.get(index=index, id='warmth')['_source']
                self.assertNotIn('textVector', source)
                self.assertEqual(source['textState'], 'none')
                for name in (settings.queue, settings.queue + '.retry', settings.queue + '.dead', settings.queue + '.quarantine'):
                    channel.queue_delete(queue=name)
                print('SYSTEM_SMOKE_PASSED: real embeddings, Product events/snapshots, hybrid API, image upload, gateway, fallback and hide')
        finally:
            server.shutdown()
            server.server_close()
            client.indices.delete(index=index)
            client.close()
