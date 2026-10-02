"""Real Elasticsearch 8.19 checks. Run explicitly; unit discovery never imports these."""
import os
import time
import unittest
import uuid
from types import SimpleNamespace
from unittest.mock import patch
from dataclasses import replace

from elasticsearch import Elasticsearch

from schemas import Snapshot
from search import SearchStore, index_definition
from settings import Settings


def product(product_id, version=0, **changes):
    data = {"id": product_id, "productVersion": version, "catalogVisible": True, "deleted": False, "deletedAt": None,
            "shopId": "shop", "name": "Áo thun đẹp", "description": "Cotton", "categoryId": "child", "categoryPath": ["root", "child"],
            "imageUrls": [], "variantPrices": [100, 1000], "minPrice": 100, "maxPrice": 1000,
            "createdAt": "2026-10-02T00:00:00Z", "updatedAt": "2026-10-02T00:00:00Z"}
    data.update(changes)
    return Snapshot.model_validate(data)


class ElasticsearchChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.name = "vmarket-products-v1-test-" + uuid.uuid4().hex
        cls.alias = "search-test-" + uuid.uuid4().hex
        cls.settings = replace(Settings(), alias=cls.alias)
        cls.client = Elasticsearch(cls.settings.es_url, request_timeout=5, max_retries=0)
        cls.client.info()  # Missing infrastructure is a failed integration run, never a silent pass.
        cls.client.indices.create(index=cls.name, body=index_definition("test-fp"))
        cls.client.indices.put_alias(index=cls.name, name=cls.alias, is_write_index=True)
        cls.store = SearchStore(cls.settings, cls.client)

    @classmethod
    def tearDownClass(cls):
        cls.client.indices.delete(index=cls.name)
        cls.client.close()

    def setUp(self):
        self.client.indices.refresh(index=self.name)
        self.client.delete_by_query(index=self.name, query={"match_all": {}}, refresh=True)

    def apply(self, snapshot):
        self.store.apply_snapshot(snapshot, int(time.time() * 1000), "test-fp")
        self.client.indices.refresh(index=self.name)

    def keyword(self, keyword, **params):
        return self.store.keyword({"keyword": keyword, "sort": "RELEVANCE", "page": 0, "size": 20, **params})["results"]

    def test_vietnamese_synonym_fuzzy_suggestions_and_price_gap(self):
        self.apply(product("a", soldCount=5))
        self.apply(product("b", name="Đồ điện", variantPrices=[500], minPrice=500, maxPrice=500))
        self.apply(product("hidden", catalogVisible=False))
        for keyword in ("ao thun", "áo phông", "ao thum"):
            self.assertEqual([p["id"] for p in self.keyword(keyword)], ["a"])
        self.assertEqual([p["id"] for p in self.keyword("do dien")], ["b"])
        self.assertEqual(self.keyword("ao", minPrice=200, maxPrice=900), [])
        self.assertEqual([p["id"] for p in self.keyword("ao", categoryId="root")], ["a"])
        self.assertEqual(self.store.suggestions({"keyword": "ao", "limit": 10})["results"], [{"id": "a", "name": "Áo thun đẹp"}])
        self.assertEqual(self.store.suggestions({"keyword": "a", "limit": 10})["results"], [])

    def test_versions_zero_duplicates_and_deletion(self):
        self.apply(product("a"))
        self.apply(product("a", 2, name="Giày sneaker"))
        self.apply(product("a", 1))
        self.apply(product("a", 2))
        self.assertEqual(self.keyword("ao thun"), [])
        self.assertEqual([p["id"] for p in self.keyword("sneaker")], ["a"])
        self.apply(product("a", 3, deleted=True, catalogVisible=False, deletedAt="2026-10-02T00:00:00Z"))
        self.apply(product("a", 2))
        self.assertEqual(self.keyword("sneaker"), [])
        self.assertEqual(self.client.get(index=self.name, id="a")["_source"]["productVersion"], 3)

    def test_price_sort_uses_minimum_for_both_directions_and_ranking(self):
        self.apply(product("a"))
        self.apply(product("b", variantPrices=[200, 300], minPrice=200, maxPrice=300, soldCount=100))
        self.assertEqual([p["id"] for p in self.keyword("ao", sort="PRICE_ASC")], ["a", "b"])
        self.assertEqual([p["id"] for p in self.keyword("ao", sort="PRICE_DESC")], ["b", "a"])
        self.assertEqual([p["id"] for p in self.keyword("ao", sort="BEST_SELLING")], ["b", "a"])
        self.assertEqual([p["id"] for p in self.keyword("ao")], ["b", "a"])

    def test_maintenance_rebuild_switches_alias_and_failure_keeps_old(self):
        from manage import reindex
        self.client.indices.put_mapping(index=self.name, _meta={"searchSchema": 1, "encoderFingerprint": "test-fp",
            "calibration": {"validated": True, "fingerprint": "test-fp", "threshold": 0.8}})
        target = "vmarket-products-v1-test-rebuild-" + uuid.uuid4().hex
        broken = "vmarket-products-v1-test-rebuild-" + uuid.uuid4().hex
        class Encoder:
            fingerprint = "test-fp"
            ready = True
            def load(self): pass
        try:
            with patch("manage.ImageEncoder", return_value=Encoder()), patch("manage.topology"), patch("manage.snapshots", return_value=[product("rebuilt")]):
                reindex(self.settings, SimpleNamespace(maintenance=True, target=target, calibration=None))
            self.assertEqual(set(self.client.indices.get_alias(name=self.alias)), {target})
            self.assertEqual([p["id"] for p in self.keyword("ao")], ["rebuilt"])
            with patch("manage.ImageEncoder", return_value=Encoder()), patch("manage.topology"), patch("manage.snapshots", side_effect=[[product("rebuilt")], [product("rebuilt", 1)]]):
                with self.assertRaises(ValueError): reindex(self.settings, SimpleNamespace(maintenance=True, target=broken, calibration=None))
            self.assertEqual(set(self.client.indices.get_alias(name=self.alias)), {target})
        finally:
            self.client.indices.update_aliases(actions=[{"remove": {"index": target, "alias": self.alias, "must_exist": False}},
                {"add": {"index": self.name, "alias": self.alias, "is_write_index": True}}])
            self.client.indices.delete(index=target, ignore_unavailable=True)
            self.client.indices.delete(index=broken, ignore_unavailable=True)

    def test_nested_knn_prefilters_best_image_and_distinct_products(self):
        self.apply(product("a"))
        self.apply(product("b", categoryPath=["other"], variantPrices=[500], minPrice=500, maxPrice=500))
        for product_id, vectors in (("a", [[1] + [0] * 575, [0, 1] + [0] * 574]), ("b", [[1] + [0] * 575])):
            hit = self.client.get(index=self.name, id=product_id)
            source = hit["_source"]
            source["images"] = [{"url": f"{product_id}-{i}.jpg", "fingerprint": "test-fp", "vector": v} for i, v in enumerate(vectors)]
            self.store.save_job(hit, source)
        self.client.indices.refresh(index=self.name)
        result = self.store.image([1] + [0] * 575, {"limit": 5, "categoryId": "root"}, "test-fp", threshold=0.5)
        self.assertEqual([p["id"] for p in result["results"]], ["a"])
        self.assertEqual(result["results"][0]["matchedImageUrl"], "a-0.jpg")
        self.assertAlmostEqual(result["results"][0]["similarity"], 1, places=5)
        self.assertEqual(self.store.image([1] + [0] * 575, {"limit": 5, "minPrice": 200, "maxPrice": 900, "categoryId": "root"}, "test-fp", threshold=0.5)["results"], [])

    def test_durable_job_restart_and_late_result_after_deletion(self):
        url = "http://localhost:9000/vmarket-media/products/a.jpg"
        self.apply(product("a", imageUrls=[url]))
        store = SearchStore(self.settings, self.client)  # Recreated process sees durable pending intent.
        outer = self
        class Encoder:
            ready = True
            fingerprint = "test-fp"
            def embed_catalog(self, url):
                outer.apply(product("a", 1, deleted=True, catalogVisible=False, deletedAt="2026-10-02T00:00:00Z"))
                return [1] + [0] * 575
        store.process_image_job(Encoder())
        source = self.client.get(index=self.name, id="a")["_source"]
        self.assertTrue(source["deleted"])
        self.assertEqual(source["images"], [])
        self.apply(product("b", imageUrls=[url]))
        hit = self.client.get(index=self.name, id="b")
        hit["_source"]["imageAttempts"] = 3
        store.save_job(hit, hit["_source"])
        self.client.indices.refresh(index=self.name)
        store.process_image_job(Encoder())
        self.assertEqual(self.client.get(index=self.name, id="b")["_source"]["imageState"], "failed")


if __name__ == "__main__": unittest.main()
