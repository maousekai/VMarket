"""Main-stack wiring checks; mount the repository when running from Docker."""
import os
import unittest
from pathlib import Path

import yaml

REPO = Path(os.environ.get('VMARKET_REPO_ROOT', Path(__file__).resolve().parents[2] / '../..')).resolve()


@unittest.skipUnless((REPO / 'docker-compose.yml').exists(), 'Repository mount required for Compose checks')
class MainComposeChecks(unittest.TestCase):
    def test_search_is_in_main_stack_with_private_encoder_and_gateway_route(self):
        services = yaml.safe_load((REPO / 'docker-compose.yml').read_text(encoding='utf-8'))['services']
        for name in ('elasticsearch', 'ai-search-service', 'ai-search-text', 'ai-search-init'):
            self.assertIn(name, services)
            self.assertNotIn('profiles', services[name])
        api, encoder = services['ai-search-service'], services['ai-search-text']
        self.assertEqual(api['image'], encoder['image'])
        self.assertNotIn('ports', encoder)
        self.assertEqual(api['environment']['ELASTICSEARCH_URL'], 'http://elasticsearch:9200')
        self.assertEqual(api['environment']['PRODUCT_SERVICE_URL'], 'http://product-service:8084')
        self.assertEqual(services['api-gateway']['environment']['AI_SEARCH_SERVICE_URL'], 'http://ai-search-service:8100')
        self.assertEqual(api['mem_limit'], '512m')
        self.assertEqual(encoder['mem_limit'], '1g')
        self.assertEqual(encoder['memswap_limit'], '1g')
        self.assertIn('elasticsearch', api['depends_on'])
        self.assertIn('rabbitmq', api['depends_on'])
        self.assertEqual(api['depends_on']['ai-search-init']['condition'], 'service_completed_successfully')
        self.assertTrue(api['healthcheck'])
        self.assertTrue(encoder['healthcheck'])
        self.assertFalse((REPO / 'docker-compose.search.yml').exists())
