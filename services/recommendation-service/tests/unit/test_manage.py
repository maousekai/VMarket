import sys
import unittest
from unittest.mock import Mock, patch

from manage import main


class ManageTest(unittest.TestCase):
    def test_erasure_requires_explicit_valid_target_and_confirmation(self):
        for args in (["erase-user"], ["erase-user", "--user-id", "buyer"],
                     ["erase-user", "--user-id", "../", "--confirm-erasure"]):
            with patch.object(sys, "argv", ["manage.py", *args]), patch("manage.Store") as store:
                with self.assertRaises(SystemExit) as error:
                    main()
                self.assertEqual(error.exception.code, 2)
                store.assert_not_called()
        instance = Mock()
        with patch.object(sys, "argv", ["manage.py", "erase-user", "--user-id", "buyer", "--confirm-erasure"]), \
                patch("manage.Store", return_value=instance), patch("builtins.print"):
            main()
        instance.erase_user.assert_called_once_with("buyer")
        instance.close.assert_called_once()

    def test_cleanup_prunes_history_before_unreferenced_receipts(self):
        instance = Mock()
        with patch.object(sys, "argv", ["manage.py", "cleanup"]), \
                patch("manage.Store", return_value=instance), patch("builtins.print"):
            main()
        self.assertLess(instance.method_calls.index(unittest.mock.call.prune_history()),
                        instance.method_calls.index(unittest.mock.call.prune_receipts()))
