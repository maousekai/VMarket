"""Main-stack wiring checks; mount the repository when running from Docker."""
import os
import unittest
from pathlib import Path

import yaml

REPO = Path(os.environ.get('VMARKET_REPO_ROOT', Path(__file__).resolve().parents[2] / '../..')).resolve()
SEARCH_STACK = ('elasticsearch', 'ai-search-service', 'ai-search-text', 'ai-search-init')


@unittest.skipUnless((REPO / 'docker-compose.yml').exists(), 'Repository mount required for Compose checks')
class MainComposeChecks(unittest.TestCase):
    def test_search_is_opt_in_with_private_encoder_and_gateway_route(self):
        services = yaml.safe_load((REPO / 'docker-compose.yml').read_text(encoding='utf-8'))['services']
        # Heavy stack (~2.5GB) never starts with a plain `docker compose up -d`.
        for name in SEARCH_STACK:
            self.assertEqual(services[name].get('profiles'), ['search'], name)
        # Nothing outside the profile may depend on it, or a plain `up` would fail.
        for name, service in services.items():
            if name not in SEARCH_STACK:
                self.assertFalse(set(service.get('depends_on') or ()) & set(SEARCH_STACK), name)
        api, encoder = services['ai-search-service'], services['ai-search-text']
        self.assertEqual(api['image'], encoder['image'])
        self.assertNotIn('ports', encoder)
        self.assertIn('--factory', encoder['command'])
        self.assertEqual(api['environment']['ELASTICSEARCH_URL'], 'http://elasticsearch:9200')
        self.assertEqual(services['api-gateway']['environment']['AI_SEARCH_SERVICE_URL'], 'http://ai-search-service:8100')
        self.assertEqual(api['depends_on']['ai-search-init']['condition'], 'service_completed_successfully')
        self.assertTrue(api['healthcheck'])
        self.assertTrue(encoder['healthcheck'])
