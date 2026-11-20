"""Run inside the main Search container; uses real Product APIs/outbox, never a stub.

Explicit opt-in reserves one existing unit and releases it in finally. No order is placed.
"""
import asyncio
import json
import os
import time
import unittest
import uuid

import httpx
from botocore.exceptions import ClientError

from contract import SNAPSHOTS_PATH
from images import s3_client
from manage import snapshots
from search import SearchStore
from semantic import TextEmbeddings
from settings import Settings


@unittest.skipUnless(os.getenv('SEARCH_MAIN_COMPOSE_MUTATION') == 'true', 'Explicit reserve/release opt-in required')
class MainComposeWorkflow(unittest.TestCase):
    def test_product_outbox_search_gateway_and_stock_restore(self):
        settings = Settings.load()
        self.assertEqual(settings.product_url, 'http://product-service:8084')
        self.assertEqual(settings.es_url, 'http://elasticsearch:9200')
        store = SearchStore(settings)
        catalog = list(snapshots(settings))
        selected = next(p for p in catalog if p.catalogVisible and not p.deleted and p.availableStock > 1)
        product_id = selected.id
        order_id = 'search-compose-check-' + uuid.uuid4().hex
        headers = {'X-Internal-Api-Key': settings.internal_key}
        gateway = 'http://api-gateway:8080'
        report = {'catalogSnapshots': len(catalog), 'productId': product_id, 'reservationId': order_id}

        def indexed(version, stock):
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline:
                source = store.client.get(index=settings.alias, id=product_id)['_source']
                if source['productVersion'] == version and source['availableStock'] == stock:
                    return source
                time.sleep(0.1)
            raise AssertionError('Product outbox update did not reach Search in 30 seconds')

        with httpx.Client(trust_env=False, timeout=5) as client:
            def snapshot():
                response = client.get(settings.product_url + SNAPSHOTS_PATH + '/' + product_id, headers=headers)
                response.raise_for_status()
                return response.json()

            def public_stock(expected):
                deadline = time.monotonic() + 10
                while time.monotonic() < deadline:
                    response = client.get(gateway + '/api/ai/search', params={'keyword': selected.name, 'size': 100})
                    self.assertEqual(response.status_code, 200)
                    matches = [p for p in response.json()['results'] if p['id'] == product_id]
                    if matches and matches[0]['availableStock'] == expected:
                        return
                    time.sleep(0.1)
                raise AssertionError('Gateway Search did not expose the current stock')

            before = snapshot()
            original = indexed(before['productVersion'], before['availableStock'])
            detail = client.get(settings.product_url + '/api/products/' + product_id)
            detail.raise_for_status()
            variant = next(v for v in detail.json()['product']['variants'] if v['availableStock'] > 0)
            public_stock(before['availableStock'])
            attempted = False
            after = None
            try:
                attempted = True
                started = time.monotonic()
                reserved = client.post(settings.product_url + '/api/products/inventory/reservations', headers=headers,
                    json={'orderId': order_id, 'items': [{'productId': product_id, 'variantId': variant['id'], 'quantity': 1}]})
                self.assertEqual(reserved.status_code, 200, reserved.text)
                after = snapshot()
                self.assertGreater(after['productVersion'], before['productVersion'])
                self.assertEqual(after['availableStock'], before['availableStock'] - 1)
                updated = indexed(after['productVersion'], after['availableStock'])
                report['reserveSyncMs'] = round((time.monotonic() - started) * 1000)
                self.assertEqual(updated['textHash'], original['textHash'])
                if 'textVector' in original:
                    self.assertEqual(updated['textVector'], original['textVector'])
                public_stock(after['availableStock'])
            finally:
                if attempted:
                    started = time.monotonic()
                    released = client.delete(settings.product_url + '/api/products/inventory/reservations/' + order_id, headers=headers)
                    self.assertIn(released.status_code, (200,) if after is not None else (200, 404),
                                  'Release reservation ' + order_id + ' if cleanup failed')
                    restored = snapshot()
                    self.assertEqual(restored['availableStock'], before['availableStock'])
                    indexed(restored['productVersion'], restored['availableStock'])
                    public_stock(restored['availableStock'])
                    report['releaseSyncMs'] = round((time.monotonic() - started) * 1000)
                    report['versions'] = [before['productVersion'], after['productVersion'] if after else None, restored['productVersion']]
                    report['stockRestored'] = True

            encoder = TextEmbeddings(settings)
            async def probe():
                try: return await encoder.query(selected.name)
                finally: await encoder.close()
            vector = asyncio.run(probe())
            self.assertIsNotNone(vector)
            self.assertEqual(len(vector), settings.text_dims)
            self.assertIn(client.get(gateway + '/api/products/internal/search-snapshots/' + product_id).status_code, (401, 403, 404))
            self.assertIn(client.post(gateway + '/v1/embeddings').status_code, (401, 403, 404))
            report['health'] = client.get(gateway + '/api/ai/search/health').json()
            self.assertEqual(report['health']['text'], 'ready')
            media = s3_client(settings, settings.minio_key, settings.minio_secret)
            try:
                with self.assertRaises(ClientError) as denied:
                    media.list_objects_v2(Bucket=settings.bucket)
                self.assertEqual(denied.exception.response['Error']['Code'], 'AccessDenied')
            finally:
                media.close()
        store.client.close()
        print('MAIN_COMPOSE_WORKFLOW=' + json.dumps(report))


if __name__ == '__main__': unittest.main(verbosity=2)
