import unittest

from tests.benchmark_personalization import percentile, summarize


class BenchmarkTest(unittest.TestCase):
    def test_nearest_rank_percentiles_keep_failures_visible(self):
        self.assertEqual(percentile(list(range(1, 21)), 0.95), 19)
        self.assertEqual(percentile(list(range(1, 21)), 0.99), 20)
        rows = [{"status": 200, "ms": 10}, {"status": 503, "ms": 2000}, {"status": 0, "ms": 3000}]
        result = summarize(rows)
        self.assertEqual(result["successes"], 1)
        self.assertEqual(result["503_rate"], 1 / 3)
        self.assertEqual(result["transport_errors"], 1)
        self.assertEqual(result["all_p99_ms"], 3000)
        self.assertEqual(result["success_p99_ms"], 10)
        self.assertIsNone(summarize([{"status": 503, "ms": 2000}])["success_p99_ms"])
