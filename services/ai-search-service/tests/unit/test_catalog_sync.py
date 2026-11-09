import unittest
from unittest.mock import Mock, patch

from elasticsearch import NotFoundError

import manage
from settings import Settings
from test_search import snapshot


class CatalogSyncChecks(unittest.TestCase):
    def test_empty_store_bootstrap_has_no_synthetic_calibration(self):
        store = Mock()
        store.meta.side_effect = NotFoundError('missing', Mock(status=404), {})
        store.client.indices.exists.return_value = False
        with patch('manage.SearchStore', return_value=store), patch('manage.topology'), \
                patch('manage.ImageEncoder', return_value=Mock(fingerprint='image-fp')), \
                patch('manage.snapshots', return_value=[snapshot()]):
            self.assertTrue(hasattr(manage, 'sync_catalog'), 'Initial catalog synchronization is required')
            manage.sync_catalog(Settings())
        options = store.client.indices.create.call_args.kwargs
        self.assertNotIn('calibration', options['body']['mappings']['_meta'])
        self.assertNotIn('textCalibration', options['body']['mappings']['_meta'])
        self.assertIn(Settings().alias, options['body']['aliases'])
        store.apply_snapshot.assert_called_once()

    def test_existing_alias_and_model_metadata_are_preserved(self):
        store = Mock()
        store.meta.return_value = ('existing', {'encoderFingerprint': 'existing-image'})
        with patch('manage.SearchStore', return_value=store), patch('manage.topology'), \
                patch('manage.ImageEncoder') as encoder, patch('manage.snapshots', return_value=[snapshot()]):
            self.assertTrue(hasattr(manage, 'sync_catalog'), 'Existing catalogs need versioned catch-up')
            manage.sync_catalog(Settings())
        encoder.assert_not_called()
        store.client.indices.create.assert_not_called()
        self.assertEqual(store.apply_snapshot.call_args.args[2], 'existing-image')
