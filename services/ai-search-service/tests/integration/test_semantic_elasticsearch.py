import time
import unittest
import uuid
from dataclasses import replace

import httpx

import test_elasticsearch as es_tests
from contract import RETRY_ATTEMPTS
from test_elasticsearch import product
from types import SimpleNamespace
from unittest.mock import patch
from search import SearchStore, index_definition
from semantic import EmbeddingBusy, fingerprint
from settings import Settings


class SemanticElasticsearchChecks(es_tests.ElasticsearchChecks):
    @classmethod
    def setUpClass(cls):
        super().setUpClass()
        cls.settings = replace(cls.settings, text_enabled=True)
        cls.store = SearchStore(cls.settings, cls.client)
        cls.fp = fingerprint(cls.settings)
        cls.client.indices.delete(index=cls.name)
        definition = index_definition('test-fp', cls.settings, cls.fp)
        definition['mappings']['_meta']['textCalibration'] = {'fingerprint': cls.fp, 'validated': True, 'threshold': 0.8}
        cls.client.indices.create(index=cls.name, body=definition)
        cls.client.indices.put_alias(index=cls.name, name=cls.alias, is_write_index=True)

    def ready(self, product_id, vector):
        self.client.update(index=self.name, id=product_id, doc={'textVector': vector, 'textState': 'ready'})

    def hybrid(self, **params):
        return self.store.keyword({'keyword': 'ao', 'sort': 'RELEVANCE', 'page': 0, 'size': 20, **params},
                                  vector=[1] + [0] * 383, fingerprint=self.fp)

    def test_semantic_union_filters_counts_sorts_and_pagination(self):
        self.apply(product('lexical', soldCount=20))
        self.apply(product('semantic', name='Trang phục mùa đông', description='Ấm áp', soldCount=10,
                           variantPrices=[500], minPrice=500, maxPrice=500))
        self.apply(product('both', soldCount=5, variantPrices=[200], minPrice=200, maxPrice=200))
        self.apply(product('unrelated', name='Điện thoại', description='Thiết bị'))
        self.apply(product('hidden', catalogVisible=False))
        for identity in ('semantic', 'both'):
            self.ready(identity, [1] + [0] * 383)
        self.ready('unrelated', [0, 1] + [0] * 382)
        self.client.indices.refresh(index=self.name)
        result = self.hybrid()
        self.assertEqual(result['totalElements'], 3)
        self.assertEqual(len({p['id'] for p in result['results']}), 3)
        self.assertEqual(result['results'][0]['id'], 'both')
        for sort, expected in [('PRICE_ASC', ['lexical', 'both', 'semantic']),
                               ('PRICE_DESC', ['semantic', 'both', 'lexical']),
                               ('BEST_SELLING', ['lexical', 'semantic', 'both']),
                               ('NEWEST', ['both', 'lexical', 'semantic'])]:
            self.assertEqual([p['id'] for p in self.hybrid(sort=sort)['results']], expected)
        self.assertEqual(self.hybrid(size=1, page=1)['totalPages'], 3)
        self.assertEqual([p['id'] for p in self.hybrid(minPrice=300, maxPrice=900)['results']], ['semantic'])
        self.assertEqual(self.hybrid(categoryId='other')['totalElements'], 0)
        self.assertEqual(self.hybrid(categoryId='root')['totalElements'], 3)
        self.apply(product('semantic', 1, deleted=True, catalogVisible=False, deletedAt='2026-10-02T00:00:00Z'))
        self.assertEqual(self.hybrid()['totalElements'], 2)
        self.assertNotIn('textVector', self.client.get(index=self.name, id='semantic')['_source'])

    def test_text_job_busy_retry_recovery_and_image_completion(self):
        self.apply(product('a', imageUrls=['http://localhost:9000/vmarket-media/products/a.jpg']))
        outer = self
        class TextEncoder:
            fingerprint = outer.fp
            def embed_catalog(self, text):
                outer.client.indices.refresh(index=outer.name)
                class ImageEncoder:
                    ready = True
                    fingerprint = 'test-fp'
                    def embed_catalog(self, url): return [1] + [0] * 575
                outer.store.process_image_job(ImageEncoder())
                return [1] + [0] * 383
        self.store.process_text_job(TextEncoder())
        source = self.client.get(index=self.name, id='a')['_source']
        self.assertEqual((source['textState'], source['imageState']), ('ready', 'ready'))
        self.apply(product('a', 1, name='Giày'))
        class Busy(TextEncoder):
            def embed_catalog(self, text): raise EmbeddingBusy()
        self.store.process_text_job(Busy())
        source = self.client.get(index=self.name, id='a')['_source']
        self.assertEqual(source['textAttempts'], 0)
        self.client.update(index=self.name, id='a', doc={'textNextAttempt': 0}, refresh=True)
        class Stale(TextEncoder):
            def embed_catalog(self, text):
                outer.apply(product('a', 2, catalogVisible=False))
                return [1] + [0] * 383
        SearchStore(self.settings, self.client).process_text_job(Stale())
        source = self.client.get(index=self.name, id='a')['_source']
        self.assertEqual(source['textState'], 'none')
        self.assertNotIn('textVector', source)

    def test_failed_text_attempts_survive_restart(self):
        self.apply(product('a'))
        outer = self
        class Broken:
            fingerprint = outer.fp
            def embed_catalog(self, text): raise ValueError('provider failed')
        for attempt in range(1, 4):
            SearchStore(self.settings, self.client).process_text_job(Broken())
            self.assertEqual(self.client.get(index=self.name, id='a')['_source']['textAttempts'], attempt)
            self.client.update(index=self.name, id='a', doc={'textNextAttempt': 0}, refresh=True)
        self.assertEqual(self.client.get(index=self.name, id='a')['_source']['textState'], 'failed')

    def test_encoder_connection_loss_stays_pending_until_recovery(self):
        self.apply(product('a'))
        outer = self
        class Down:
            fingerprint = outer.fp
            def embed_catalog(self, text): raise httpx.ConnectError('encoder restarting')
        for _ in range(RETRY_ATTEMPTS + 2):
            SearchStore(self.settings, self.client).process_text_job(Down())
            source = self.client.get(index=self.name, id='a')['_source']
            self.assertEqual((source['textState'], source['textAttempts']), ('pending', 0))
            self.client.update(index=self.name, id='a', doc={'textNextAttempt': 0}, refresh=True)
        class Up(Down):
            def embed_catalog(self, text): return [1] + [0] * 383
        SearchStore(self.settings, self.client).process_text_job(Up())
        self.assertEqual(self.client.get(index=self.name, id='a')['_source']['textState'], 'ready')

    def test_rollback_rebuild_returns_alias_to_schema_one_with_latest_versions(self):
        from manage import reindex
        _, meta = self.store.meta()
        self.client.indices.put_mapping(index=self.name, _meta={**meta, 'calibration': {
            'fingerprint': 'test-fp', 'validated': True, 'threshold': 0.8}})
        self.apply(product('kept', 3))
        target = 'vmarket-products-v1-test-rollback-' + uuid.uuid4().hex
        class Image:
            fingerprint = 'test-fp'
            def load(self): pass
        try:
            with patch('manage.ImageEncoder', return_value=Image()), patch('manage.topology'), \
                    patch('manage.snapshots', side_effect=lambda _: iter([product('kept', 3)])):
                reindex(replace(self.settings, text_enabled=False),
                        SimpleNamespace(maintenance=True, target=target, calibration=None, text_calibration=None))
            self.assertEqual(set(self.client.indices.get_alias(name=self.alias)), {target})
            mapping = self.client.indices.get_mapping(index=target)[target]['mappings']
            # The pre-FR-SRCH-06 reader accepts only searchSchema 1.
            self.assertEqual(mapping['_meta']['searchSchema'], 1)
            self.assertNotIn('textVector', mapping['properties'])
            self.assertEqual(self.client.get(index=target, id='kept')['_source']['productVersion'], 3)
        finally:
            self.client.indices.update_aliases(actions=[{'remove': {'index': target, 'alias': self.alias, 'must_exist': False}},
                {'add': {'index': self.name, 'alias': self.alias, 'is_write_index': True}}])
            self.client.indices.delete(index=target, ignore_unavailable=True)

    def test_metadata_change_during_inference_preserves_job_outcome(self):
        outer = self
        for busy in (False, True):
            self.apply(product('a'))
            class Encoder:
                fingerprint = outer.fp
                def embed_catalog(self, text):
                    outer.apply(product('a', 1, soldCount=1))
                    if busy: raise EmbeddingBusy()
                    return [1] + [0] * 383
            self.store.process_text_job(Encoder())
            source = self.client.get(index=self.name, id='a')['_source']
            with self.subTest(busy=busy):
                self.assertEqual(source['productVersion'], 1)
                self.assertEqual(source['textState'], 'pending' if busy else 'ready')
                self.assertEqual(source['textAttempts'], 0 if busy else 1)
            self.client.delete(index=self.name, id='a', refresh=True)

    def test_maintenance_rebuild_switches_alias_and_failure_keeps_old(self):
        from manage import reindex
        _, meta = self.store.meta()
        self.client.indices.put_mapping(index=self.name, _meta={**meta, 'calibration': {
            'fingerprint': 'test-fp', 'validated': True, 'threshold': 0.8}})
        target = 'vmarket-products-v1-test-rebuild-' + uuid.uuid4().hex
        broken = 'vmarket-products-v1-test-rebuild-' + uuid.uuid4().hex
        outer = self
        class Image:
            fingerprint = 'test-fp'
            def load(self): pass
        class Text:
            fingerprint = outer.fp
            def embed_catalog(self, text): return [1] + [0] * 383
            async def close(self): pass
        try:
            with patch('manage.ImageEncoder', return_value=Image()), patch('manage.TextEmbeddings', return_value=Text()), \
                    patch('manage.topology'), patch('manage.snapshots', return_value=[product('rebuilt')]):
                reindex(self.settings, SimpleNamespace(maintenance=True, target=target, calibration=None, text_calibration=None))
            self.assertEqual(set(self.client.indices.get_alias(name=self.alias)), {target})
            self.assertEqual(self.client.get(index=target, id='rebuilt')['_source']['textState'], 'ready')
            with patch('manage.ImageEncoder', return_value=Image()), patch('manage.TextEmbeddings', return_value=Text()), \
                    patch('manage.topology'), patch('manage.snapshots', side_effect=[[product('rebuilt')], [product('rebuilt', 1)]]):
                with self.assertRaises(ValueError):
                    reindex(self.settings, SimpleNamespace(maintenance=True, target=broken, calibration=None, text_calibration=None))
            self.assertEqual(set(self.client.indices.get_alias(name=self.alias)), {target})
        finally:
            self.client.indices.update_aliases(actions=[{'remove': {'index': target, 'alias': self.alias, 'must_exist': False}},
                {'add': {'index': self.name, 'alias': self.alias, 'is_write_index': True}}])
            self.client.indices.delete(index=target, ignore_unavailable=True)
            self.client.indices.delete(index=broken, ignore_unavailable=True)

    def test_legacy_strict_index_upgrades_only_through_rebuild(self):
        from manage import reindex
        from unittest.mock import AsyncMock
        legacy = 'vmarket-products-v1-legacy-' + uuid.uuid4().hex
        target = 'vmarket-products-v1-upgrade-' + uuid.uuid4().hex
        alias = 'upgrade-' + uuid.uuid4().hex
        settings = replace(self.settings, alias=alias)
        definition = index_definition('test-fp', settings)
        definition['mappings']['_meta']['calibration'] = {'fingerprint': 'test-fp', 'validated': True, 'threshold': 0.8}
        self.client.indices.create(index=legacy, body=definition)
        self.client.indices.put_alias(index=legacy, name=alias, is_write_index=True)
        store = SearchStore(settings, self.client)
        store.apply_snapshot(product('upgraded'), 1, 'test-fp')
        self.assertNotIn('textState', self.client.get(index=legacy, id='upgraded')['_source'])
        outer = self
        class Image:
            fingerprint = 'test-fp'
            def load(self): pass
        class Text:
            fingerprint = outer.fp
            def embed_catalog(self, text): return [1] + [0] * 383
            async def close(self): pass
        try:
            with patch('manage.ImageEncoder', return_value=Image()), patch('manage.TextEmbeddings', return_value=Text()), \
                    patch('manage.topology'), patch('manage.snapshots', return_value=[product('upgraded')]), \
                    patch('manage.Path.read_text', return_value='{}'), patch('manage.calibrate_text', new=AsyncMock(return_value={
                        'fingerprint': self.fp, 'validated': True, 'threshold': 0.8})):
                reindex(settings, SimpleNamespace(maintenance=True, target=target, calibration=None, text_calibration='manifest.json'))
            self.assertEqual(set(self.client.indices.get_alias(name=alias)), {target})
            self.assertEqual(store.meta()[1]['searchSchema'], 2)
            self.assertEqual(self.client.get(index=target, id='upgraded')['_source']['textState'], 'ready')
            self.assertNotIn('textVector', self.client.indices.get_mapping(index=legacy)[legacy]['mappings']['properties'])
        finally:
            self.client.indices.delete(index=legacy, ignore_unavailable=True)
            self.client.indices.delete(index=target, ignore_unavailable=True)


# Reuse the base fixtures only: the inherited lexical tests already run in test_elasticsearch.
for _name in unittest.TestLoader().getTestCaseNames(es_tests.ElasticsearchChecks):
    if _name not in SemanticElasticsearchChecks.__dict__:
        setattr(SemanticElasticsearchChecks, _name, None)
