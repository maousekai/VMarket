"""Fingerprint-bound text embeddings; provider failures preserve lexical search."""
import asyncio
import hashlib
import json
import math
import time
from collections import Counter, OrderedDict

import httpx

from diagnostics import log_failure


def fingerprint(settings):
    spec = {"backend": settings.text_provider, "model": settings.text_model, "revision": settings.text_revision,
            "dims": settings.text_dims, "tokens": settings.text_tokens, "pooling": "masked-mean-l2-v1",
            "preprocessing": "query: |passage: |name-newline-description-v1"}
    return hashlib.sha256(json.dumps(spec, sort_keys=True).encode()).hexdigest()


def product_text(source):
    return (source.get("name") or "") + "\n" + (source.get("description") or "")


def content_hash(source):
    return hashlib.sha256(product_text(source).encode()).hexdigest()


def validated_vector(vector, dims):
    if not isinstance(vector, list) or len(vector) != dims:
        raise ValueError("Invalid embedding vector")
    try:
        if any(type(v) not in (int, float) or not math.isfinite(v) for v in vector):
            raise ValueError('Invalid embedding value')
        norm = math.hypot(*vector)
    except OverflowError:
        raise ValueError('Embedding value exceeds numeric limits') from None
    if not math.isfinite(norm) or norm <= 0:
        raise ValueError("Invalid embedding norm")
    return [v / norm for v in vector]


class EmbeddingBusy(Exception):
    pass


class TextEmbeddings:
    def __init__(self, settings, transport=None):
        self.settings = settings
        self.fingerprint = fingerprint(settings)
        self.client = httpx.AsyncClient(trust_env=False, follow_redirects=False, transport=transport,
                                        limits=httpx.Limits(max_connections=8, max_keepalive_connections=2))
        # One pooled client for the catalog worker instead of a new connection per product.
        self.catalog_client = httpx.Client(trust_env=False, follow_redirects=False,
                                           timeout=settings.text_catalog_timeout)
        self.cache = OrderedDict()
        self.counts = Counter()
        self.last_failure = None
        self.last_success = None
        self.active = 0

    def request(self, text, role):
        if not self.settings.text_configured:
            raise ValueError("Text embedding provider unavailable or permission missing")
        if not isinstance(text, str) or not text.strip() or len(text.encode()) > 50000:
            raise ValueError("Invalid embedding input")
        s = self.settings
        headers = {"X-Embedding-Role": role, "X-Embedding-Fingerprint": self.fingerprint}
        if s.text_provider == "native":
            headers["X-Internal-Api-Key"] = s.internal_key
            budget = s.text_query_ms / 1000 if role == 'query' else s.text_catalog_timeout
            headers['X-Embedding-Deadline-Ms'] = str(int((time.time() + budget) * 1000))
        elif s.text_key:
            headers["Authorization"] = "Bearer " + s.text_key
        return {"model": s.text_model, "input": [("query: " if role == "query" else "passage: ") + text],
                "encoding_format": "float"}, headers

    def response(self, raw):
        data = json.loads(raw)
        if self.settings.text_provider == "native" and data.get("fingerprint") != self.fingerprint:
            raise ValueError("Embedding fingerprint mismatch")
        rows = data["data"]
        if not isinstance(rows, list) or len(rows) != 1 or type(rows[0].get("index")) is not int or rows[0]["index"] != 0:
            raise ValueError("Embedding response correspondence mismatch")
        return validated_vector(rows[0]["embedding"], self.settings.text_dims)

    async def query(self, text):
        key = (self.fingerprint, text)
        cached = self.cache.get(key)
        if cached and cached[0] > time.monotonic():
            self.cache.move_to_end(key)
            self.counts['cache'] += 1
            return cached[1]
        if self.active >= 8:
            self.counts['busy'] += 1
            return None
        self.active += 1
        try:
            body, headers = self.request(text, "query")
            async with asyncio.timeout(self.settings.text_query_ms / 1000):
                async with self.client.stream("POST", self.settings.text_url.rstrip('/') + "/embeddings", json=body, headers=headers,
                                              timeout=self.settings.text_query_ms / 1000) as response:
                    response.raise_for_status()
                    raw = bytearray()
                    async for chunk in response.aiter_bytes():
                        if len(raw) + len(chunk) > 262144:
                            raise ValueError("Embedding response too large")
                        raw.extend(chunk)
                    vector = self.response(raw)
            self.cache[key] = (time.monotonic() + 300, vector)
            self.cache.move_to_end(key)
            while len(self.cache) > 128:
                self.cache.popitem(last=False)
            self.counts['embedded'] += 1
            self.last_success = time.monotonic()
            return vector
        except (httpx.HTTPError, TimeoutError, ValueError, KeyError, TypeError, AttributeError) as error:
            self.last_failure = time.monotonic()
            self.counts[type(error).__name__] += 1
            log_failure("text_embedding_fallback", error)
            return None
        finally:
            self.active -= 1

    def embed_catalog(self, text):
        body, headers = self.request(text, "catalog")
        started = time.monotonic()
        with self.catalog_client.stream("POST", self.settings.text_url.rstrip('/') + "/embeddings", json=body, headers=headers) as response:
            if response.status_code in (429, 503):
                raise EmbeddingBusy()
            response.raise_for_status()
            raw = bytearray()
            for chunk in response.iter_bytes():
                if len(raw) + len(chunk) > 262144 or time.monotonic() - started > self.settings.text_catalog_timeout:
                    raise ValueError("Embedding response limit exceeded")
                raw.extend(chunk)
            vector = self.response(raw)
            self.last_success = time.monotonic()
            return vector

    async def probe(self):
        if not self.settings.text_configured:
            return False
        if self.settings.text_provider == 'native':
            try:
                async with asyncio.timeout(self.settings.text_query_ms / 1000):
                    response = await self.client.get(self.settings.text_url.rstrip('/').removesuffix('/v1') + '/internal/health',
                        headers={'X-Internal-Api-Key': self.settings.internal_key}, timeout=self.settings.text_query_ms / 1000)
                    response.raise_for_status()
                    if len(response.content) > 4096 or response.json().get('fingerprint') != self.fingerprint:
                        return False
            except (httpx.HTTPError, TimeoutError, ValueError, AttributeError):
                return False
        elif self.last_success is None:
            return False
        return self.last_failure is None or time.monotonic() - self.last_failure > 30

    async def close(self):
        await self.client.aclose()
        self.catalog_client.close()
