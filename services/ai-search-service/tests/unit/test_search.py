import asyncio
import io
import json
import threading
import tempfile
import unittest
from dataclasses import replace
from unittest.mock import Mock, patch
from pathlib import Path

import httpx
from fastapi.testclient import TestClient
from PIL import Image
from botocore.response import StreamingBody
from starlette.datastructures import QueryParams

from event_consumer import Workers, validate_event
from images import decoded_image, object_key
from llm import Expander, validated_terms
from main import create_app
from manage import evaluate
from schemas import SearchError, Snapshot, parse_query
from search import SearchStore, filters, image_body, image_threshold, index_definition, keyword_body, metadata_document, normalize
from diagnostics import log_failure
from settings import Settings


def snapshot(version=0, **changes):
    data = {"id": "p1", "productVersion": version, "catalogVisible": True, "deleted": False, "deletedAt": None,
            "shopId": "s1", "name": "Áo thun", "description": "Cotton", "categoryId": "child", "categoryPath": ["root", "child"],
            "imageUrls": ["http://localhost:9000/vmarket-media/products/a.jpg"], "variantPrices": [100, 1000],
            "minPrice": 100, "maxPrice": 1000, "createdAt": "2026-10-02T00:00:00Z", "updatedAt": "2026-10-02T00:00:00Z"}
    data.update(changes)
    return Snapshot.model_validate(data)


class SearchChecks(unittest.TestCase):
    def test_explicit_settings_precedence_and_fresh_loads(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / ".env"
            path.write_text("EVENT_QUEUE=from-file\nLLM_BASE_URL=https://provider.test/v1\n", encoding="utf-8")
            first = Settings.load(path, {"EVENT_QUEUE": "from-environment", "LLM_TIMEOUT_MS": "750", "LLM_MAX_TOKENS": "256"})
            self.assertEqual(first.queue, "from-environment")
            self.assertEqual((first.llm_timeout_ms, first.llm_max_tokens), (750, 256))
            path.write_text("EVENT_QUEUE=changed\n", encoding="utf-8")
            self.assertEqual(Settings.load(path, {}).queue, "changed")
            self.assertEqual(first.queue, "from-environment")
            self.assertEqual(Settings().llm_url, "")
            for value in ('"not a list"', '[1]', '["ok", null]'):
                with self.subTest(value=value), self.assertRaises(ValueError):
                    Settings.load(path, {"SEARCH_SYNONYMS": value})
            tuned = Settings.load(path, {"SEARCH_SYNONYMS": '["a, b"]', "SEARCH_NAME_BOOST": "4", "SEARCH_SALES_WEIGHT": "0.2"})
            self.assertEqual(index_definition(settings=tuned)["settings"]["analysis"]["filter"]["vi_synonyms"]["synonyms"], ["a, b"])
            body = keyword_body({"keyword": "a", "sort": "RELEVANCE", "page": 0, "size": 20}, settings=tuned)
            self.assertEqual(body["query"]["script_score"]["script"]["params"]["sales"], 0.2)

    def test_failure_logs_have_context_without_secrets(self):
        try:
            raise ValueError("private-api-key and private-query")
        except ValueError as error:
            with self.assertLogs(level="WARNING") as logs:
                log_failure("test_failure", error)
        output = " ".join(logs.output)
        self.assertIn("exception=ValueError", output)
        self.assertIn("function=test_failure_logs_have_context_without_secrets", output)
        self.assertNotIn("private-api-key", output)
        self.assertNotIn("private-query", output)

    def test_validation_and_variant_gap_query(self):
        for query in ("keyword=x&size=101", "keyword=x&minPrice=2&maxPrice=1", "keyword=x&keyword=y", "keyword=x&minPrice=NaN", "keyword=x&provider=z", "keyword=x&page=500"):
            with self.subTest(query=query), self.assertRaises(SearchError):
                parse_query(QueryParams(query), "keyword")
        params = parse_query(QueryParams("keyword=ao&minPrice=200&maxPrice=900"), "keyword")
        self.assertIn({"range": {"variantPrices": {"gte": 200, "lte": 900}}}, filters(params))
        self.assertNotIn("query_string", json.dumps(keyword_body(params, ["áo phông"])))
        self.assertEqual(normalize("ĐỒ ĐIỆN"), "do dien")

    def test_versions_duplicates_and_tombstones(self):
        document = metadata_document(snapshot(), None, "fp", 1)
        self.assertEqual(document["imageState"], "pending")
        document["imageAttempts"] = 2
        self.assertIsNone(metadata_document(snapshot(), document, "fp", 2))
        newer = metadata_document(snapshot(1), document, "fp", 2)
        newer["images"] = [{"url": newer["imageUrls"][0], "fingerprint": "fp", "vector": [1]}]
        self.assertEqual(metadata_document(snapshot(2), newer, "fp", 3)["imageState"], "ready")
        tombstone = metadata_document(snapshot(3, deleted=True, catalogVisible=False, deletedAt="2026-10-02T00:00:00Z"), newer, "fp", 4)
        self.assertEqual(tombstone["images"], [])
        self.assertNotIn("name", tombstone)
        self.assertIsNone(metadata_document(snapshot(2), tombstone, "fp", 5))

    def test_minio_origin_and_encoded_path_rejection(self):
        origin = "http://localhost:9000"
        self.assertEqual(object_key(origin + "/vmarket-media/products/abc.jpg", origin, "vmarket-media"), "products/abc.jpg")
        for url in ("https://evil/products/x.jpg", origin + "/vmarket-media/products/../x.jpg", origin + "/vmarket-media/products/%2e%2e.jpg", origin + "/vmarket-media/products/x.jpg?q=1", "http://user@localhost:9000/vmarket-media/products/x.jpg"):
            with self.subTest(url=url), self.assertRaises(ValueError):
                object_key(url, origin, "vmarket-media")

    def test_bytes_not_mime_and_animation_rejected(self):
        data = io.BytesIO()
        Image.new("RGB", (8, 8)).save(data, format="PNG")
        image = decoded_image(data.getvalue())
        self.assertEqual(image.mode, "RGB")
        image.close()
        with self.assertRaises(SearchError): decoded_image(b"not an image")
        animation = io.BytesIO()
        Image.new("RGB", (8, 8), "red").save(animation, format="PNG", save_all=True, append_images=[Image.new("RGB", (8, 8), "blue")])
        with self.assertRaises(SearchError): decoded_image(animation.getvalue())

    def test_s3_body_closed_after_bounded_download(self):
        from images import ImageEncoder
        stream = io.BytesIO(b"photo")
        body = StreamingBody(stream, 5)
        client = Mock()
        client.get_object.return_value = {"Body": body, "ContentLength": 5}
        encoder = ImageEncoder(replace(Settings(), minio_key="test", minio_secret="test"))
        encoder.embed_image = Mock(return_value=[1])
        with patch("boto3.client", return_value=client):
            self.assertEqual(encoder.embed_catalog("http://localhost:9000/vmarket-media/products/test.jpg"), [1])
        encoder.embed_image.assert_called_once_with(b"photo")
        self.assertTrue(stream.closed)
        client.close.assert_called_once()

    def test_projected_resize_limit_before_decode(self):
        for size in ((1, 10_000), (10_000, 1), (256, 4097)):
            data = io.BytesIO()
            with Image.new("RGB", size) as image:
                image.save(data, format="PNG")
            with self.subTest(size=size), patch.object(Image.Image, "load", side_effect=AssertionError("Decoded before dimension guard")):
                with self.assertRaises(SearchError) as error:
                    decoded_image(data.getvalue())
                self.assertEqual(error.exception.code, "INVALID_IMAGE")
        data = io.BytesIO()
        with Image.new("RGB", (256, 4096)) as image:
            image.save(data, format="PNG")
        with decoded_image(data.getvalue()) as image:
            self.assertEqual(image.size, (256, 4096))

    def test_event_envelope_and_delayed_delete_fetches_current(self):
        raw = json.dumps({"eventId": "d7836ec9-667d-44b9-9279-d21479e83aa5", "eventType": "ProductDeleted", "timestamp": 1, "payload": {"productId": "p1"}}).encode()
        self.assertEqual(validate_event(raw, "ProductDeleted"), ("p1", 1))
        with self.assertRaises(ValueError): validate_event(raw, "ProductUpdated")
        store = Mock()
        store.meta.return_value = ("index", {"encoderFingerprint": "fp"})
        store.fetch_snapshot.return_value = snapshot(5)
        workers = Workers(store, Mock())
        workers.handle(raw, "ProductDeleted")
        store.apply_snapshot.assert_called_once_with(store.fetch_snapshot.return_value, 1, "fp")
        workers.close()

    def test_calibration_counts_and_knn_prefilters(self):
        result = evaluate([{"positive": True, "correctSimilarity": 0.8, "bestSimilarity": 0.9}, {"positive": False, "correctSimilarity": None, "bestSimilarity": 0.6}], 0.7)
        self.assertEqual(result["f1"], 1)
        body = image_body([1] + [0] * 575, {"limit": 20, "categoryId": "root"}, 0.4)
        self.assertEqual(body["knn"]["filter"]["bool"]["filter"], filters({"categoryId": "root"}))


class LLMChecks(unittest.IsolatedAsyncioTestCase):
    async def test_health_and_image_search_share_readiness(self):
        client = Mock()
        store = SearchStore(Settings(), client)
        store.meta = Mock()
        encoder = Mock(ready=True, fingerprint="fp")
        app = create_app(Settings(), store, encoder, run_workers=False)
        try:
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as api:
                for threshold in (None, float("nan"), 2, True):
                    meta = {"encoderFingerprint": "fp", "calibration": {"fingerprint": "fp", "validated": True, "threshold": threshold}}
                    store.meta.return_value = ("index", meta)
                    with self.subTest(threshold=threshold):
                        self.assertEqual((await api.get("/api/ai/search/health")).json()["image"], "unavailable")
                        with self.assertRaises(SearchError):
                            store.image([1], {"limit": 1}, "fp")
                client.search.assert_not_called()
                meta["calibration"]["threshold"] = 0.5
                self.assertEqual(image_threshold(meta, "fp"), 0.5)
                self.assertEqual((await api.get("/api/ai/search/health")).json()["image"], "ready")
        finally:
            await app.state.expander.client.aclose()

    async def test_cancelled_image_holds_slot_until_native_completion(self):
        entered, finish = threading.Event(), threading.Event()
        def blocked(data):
            entered.set()
            finish.wait(2)
            raise RuntimeError("late native failure")
        encoder = Mock(ready=True, fingerprint="fp", embed_image=blocked)
        app = create_app(replace(Settings(), llm_enabled=False), Mock(), encoder, run_workers=False)
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
            task = asyncio.create_task(client.post("/api/ai/search/image", files={"file": ("query.png", b"query")}))
            try:
                async with asyncio.timeout(1):
                    while not entered.is_set(): await asyncio.sleep(0.001)
                task.cancel()
                with self.assertRaises(asyncio.CancelledError): await task
                self.assertTrue(app.state.image_busy)
                self.assertEqual((await client.post("/api/ai/search/image", files={"file": ("query.png", b"query")})).status_code, 429)
            finally:
                finish.set()
                async with asyncio.timeout(1):
                    while app.state.image_busy: await asyncio.sleep(0.001)
                await app.state.expander.client.aclose()

    async def test_wire_format_cache_and_failure_fallback(self):
        requests = []
        def handler(request):
            requests.append(json.loads(request.content))
            return httpx.Response(200, json={"choices": [{"message": {"content": '{"terms":["áo phông"]}'}}]})
        settings = replace(Settings(), llm_url="https://provider.test/v1", llm_key="test", llm_model="test", external_text=True)
        expander = Expander(settings, httpx.MockTransport(handler))
        try:
            self.assertEqual(await expander.expand_query("áo thun"), ["áo phông"])
            self.assertEqual(await expander.expand_query("áo thun"), ["áo phông"])
            self.assertEqual(len(requests), 1)
            self.assertFalse(requests[0]["stream"])
            expander.busy = True
            self.assertEqual(await expander.expand_query("điện thoại"), [])
        finally: await expander.client.aclose()
        with self.assertRaises(ValueError): validated_terms('{"terms":["x"],"tools":[]}', "áo")

    async def test_total_timeout_and_malformed_response(self):
        async def slow(request):
            await asyncio.sleep(1)
            return httpx.Response(200, json={})
        settings = replace(Settings(), llm_url="https://provider.test/v1", llm_key="test", llm_model="test", external_text=True, llm_timeout_ms=20)
        expander = Expander(settings, httpx.MockTransport(slow))
        try:
            self.assertEqual(await expander.expand_query("áo"), [])
            self.assertFalse(expander.busy)
        finally: await expander.client.aclose()

    async def test_invalid_provider_message_keeps_keyword_search_available(self):
        settings = replace(Settings(), llm_url="https://provider.test/v1", llm_key="test", llm_model="test", external_text=True)
        store = Mock()
        store.keyword.return_value = {"results": []}
        for message in (None, [], "invalid", 1, False):
            with self.subTest(message=message):
                transport = httpx.MockTransport(lambda request: httpx.Response(200, json={"choices": [{"message": message}]}))
                expander = Expander(settings, transport)
                app = create_app(settings, store, Mock(), expander, run_workers=False)
                try:
                    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
                        response = await client.get("/api/ai/search?keyword=ao")
                    self.assertEqual(response.status_code, 200)
                    self.assertEqual(store.keyword.call_args.args[1], [])
                    self.assertEqual(expander.fallback, 1)
                    self.assertFalse(expander.busy)
                finally:
                    await expander.client.aclose()


class APIChecks(unittest.TestCase):
    def test_guest_validation_multipart_and_contract_fields(self):
        store = Mock()
        store.keyword.return_value = {"keyword": "ao", "results": [], "page": 0, "size": 20, "totalElements": 0, "totalPages": 0}
        store.image.return_value = {"results": [], "limit": 20, "totalReturned": 0}
        encoder = Mock(ready=True, fingerprint="fp")
        encoder.embed_image.side_effect = lambda data: (decoded_image(data).close() or [1] + [0] * 575)
        app = create_app(replace(Settings(), llm_enabled=False), store, encoder, run_workers=False)
        with TestClient(app) as client:
            result = client.get("/api/ai/search?keyword=ao")
            self.assertEqual(result.status_code, 200)
            self.assertEqual(client.get("/api/ai/search?keyword=x&keyword=y").json()["error"]["code"], "INVALID_QUERY")
            image = io.BytesIO()
            Image.new("RGB", (8, 8)).save(image, format="PNG")
            self.assertEqual(client.post("/api/ai/search/image", files={"file": ("x.png", image.getvalue())}).status_code, 200)
            narrow = io.BytesIO()
            with Image.new("RGB", (1, 10_000)) as unsafe:
                unsafe.save(narrow, format="PNG")
            response = client.post("/api/ai/search/image", files={"file": ("narrow.png", narrow.getvalue())})
            self.assertEqual(response.status_code, 400)
            self.assertEqual(response.json()["error"]["code"], "INVALID_IMAGE")
            self.assertFalse(app.state.image_busy)
            self.assertEqual(client.post("/api/ai/search/image", files=[("file", ("x.png", b"x")), ("file", ("y.png", b"y"))]).status_code, 400)
            self.assertEqual(client.post("/api/ai/search/image", files={"file": ("x.jpg", b"bad")}).status_code, 400)
            malformed = client.post("/api/ai/search/image", headers={"Content-Type": "multipart/form-data; boundary=correct"}, content=b"--wrong\r\ninvalid")
            self.assertEqual(malformed.status_code, 400)
            self.assertEqual(malformed.json()["error"]["code"], "INVALID_IMAGE")
            self.assertEqual(client.post("/api/ai/search/image", headers={"Content-Length": "9" * 5000, "Content-Type": "multipart/form-data; boundary=x"}, content=b"x").status_code, 413)
            self.assertEqual(client.post("/api/ai/search/image", headers={"Content-Length": "11000001", "Content-Type": "multipart/form-data; boundary=x"}, content=b"x").status_code, 413)
            encoder.embed_image.side_effect = RuntimeError("private dependency detail")
            response = client.post("/api/ai/search/image", files={"file": ("x.png", image.getvalue())})
            self.assertEqual(response.status_code, 503)
            self.assertEqual(response.json()["error"]["code"], "IMAGE_SEARCH_UNAVAILABLE")
            self.assertNotIn("private dependency", response.text)
            self.assertFalse(app.state.image_busy)


if __name__ == "__main__": unittest.main()
