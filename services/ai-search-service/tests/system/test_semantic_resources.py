"""Opt-in maximum image upload alongside real native text inference; same isolated stack."""
import asyncio
import io
import json
import os
import unittest
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import httpx
from elasticsearch import Elasticsearch
from PIL import Image

from images import ImageEncoder
from search import index_definition
from semantic import TextEmbeddings, fingerprint
from settings import Settings


class SemanticResourceSmoke(unittest.TestCase):
    def test_maximum_image_with_text(self):
        settings = Settings.load(env_file=Path('/nonexistent'))
        if not settings.alias.startswith('fr-srch06-test-'):
            raise ValueError('Resource check requires an isolated test alias')
        image_encoder = ImageEncoder(settings)
        image_encoder.load()
        definition = index_definition(image_encoder.fingerprint, settings, fingerprint(settings))
        definition['mappings']['_meta']['calibration'] = {'fingerprint': image_encoder.fingerprint, 'validated': True, 'threshold': -1}
        name = 'vmarket-products-v1-resource-' + uuid.uuid4().hex
        client = Elasticsearch(settings.es_url, request_timeout=5, max_retries=0)
        client.indices.create(index=name, body=definition)
        client.indices.put_alias(index=name, name=settings.alias, is_write_index=True)
        encoder = TextEmbeddings(settings)
        try:
            data = io.BytesIO()
            image = Image.new('RGBA', (5000, 5000), (20, 40, 80, 255))
            image.save(data, format='PNG')
            image.close()
            payload = data.getvalue()
            payload += b'\0' * (10_000_000 - len(payload))
            with ThreadPoolExecutor(max_workers=1) as executor:
                native = executor.submit(lambda: [encoder.embed_catalog('Sản phẩm dệt kim mềm mại') for _ in range(10)])
                response = httpx.post(os.environ['SEARCH_SYSTEM_URL'] + '/api/ai/search/image',
                    files={'file': ('maximum.png', payload, 'image/png')}, trust_env=False, timeout=10)
                self.assertEqual(response.status_code, 200, response.text)
                self.assertEqual(len(native.result()), 10)
            print('MAXIMUM_IMAGE_WITH_NATIVE_TEXT_PASSED: 25 MP RGBA, 10 MB upload, 10 real text inferences')
        finally:
            asyncio.run(encoder.close())
            client.indices.delete(index=name)
            client.close()
