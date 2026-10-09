"""Knowledge chunks, their embeddings and the in-process vector search over them."""
import hashlib
import math
import re
import threading
import time
import unicodedata
import zlib

import httpx
import numpy

DIMENSIONS = 1024
MAX_CHUNK = 900
# Accent-free function words that carry no topic; they would otherwise match every chunk.
STOPWORDS = frozenset(
    "a ah anh ay ban bao bi cac cai can chi cho co con cua cung da dang dau de den di do duoc em gi giup ha hay "
    "hoac hoi khi khong la lam len ma minh mot muon nao nay neu nhe nhieu nhu nhung o oi qua ra rang rat roi sao "
    "se tai the thi toi trong tu va vao vay ve vi voi xin".split())


def normalize(text):
    """Lowercase, accent-free words: users type Vietnamese with and without diacritics."""
    text = unicodedata.normalize("NFD", text.lower().replace("đ", "d"))
    return re.findall(r"[a-z0-9]+", "".join(c for c in text if unicodedata.category(c) != "Mn"))


def hashed_vector(text):
    """Feature hashing of words and adjacent word pairs. Lexical, not semantic: see README."""
    words = [w for w in normalize(text) if w not in STOPWORDS]
    counts = {}
    for feature in words + [a + " " + b for a, b in zip(words, words[1:])]:
        counts[feature] = counts.get(feature, 0) + 1
    vector = numpy.zeros(DIMENSIONS, dtype=numpy.float32)
    for feature, count in counts.items():
        digest = zlib.crc32(feature.encode())
        vector[digest % DIMENSIONS] += (1 if digest >> 16 & 1 else -1) * (1 + math.log(count))
    return unit(vector)


def unit(vector):
    vector = numpy.asarray(vector, dtype=numpy.float32)
    norm = float(numpy.linalg.norm(vector))
    if not math.isfinite(norm):
        raise ValueError("Invalid embedding")
    return vector / norm if norm else vector


class HashingEmbedder:
    """Offline default so the service answers without any model download or provider account."""
    fingerprint = f"hashing-v1-{DIMENSIONS}"

    def embed(self, texts):
        return [self.vector(text) for text in texts]

    @staticmethod
    def vector(text):
        # A chunk is title + newline + body. The title counts as much as the whole body, so a short
        # question naming the topic ("COD là gì") still reaches a long chunk. Calibrated in tests/unit.
        title, _, body = text.partition("\n")
        return unit(hashed_vector(title) + hashed_vector(body)) if body.strip() else hashed_vector(title)

    def close(self):
        pass


class RemoteEmbedder:
    """OpenAI-compatible /embeddings (also served by Ollama) for semantic retrieval."""

    def __init__(self, settings, transport=None):
        self.settings = settings
        self.fingerprint = "openai:" + settings.embedding_model
        self.client = httpx.Client(trust_env=False, follow_redirects=False, transport=transport,
                                   timeout=settings.embedding_timeout)

    def embed(self, texts):
        s = self.settings
        vectors = []
        for start in range(0, len(texts), 64):
            batch = texts[start:start + 64]
            response = self.client.post(s.llm_url.rstrip("/") + "/embeddings", json={"model": s.embedding_model, "input": batch},
                                        headers={"Authorization": "Bearer " + s.llm_key} if s.llm_key else {})
            response.raise_for_status()
            data = sorted(response.json()["data"], key=lambda item: item["index"])
            if len(data) != len(batch):
                raise ValueError("Embedding count mismatch")
            vectors.extend(unit(item["embedding"]) for item in data)
        return vectors

    def close(self):
        self.client.close()


def create_embedder(settings):
    return RemoteEmbedder(settings) if settings.embedding_provider == "openai_compatible" else HashingEmbedder()


def split_markdown(source, name, text):
    """One chunk per `##` section, so every chunk is a self-contained answer with its own title."""
    document = name
    sections, title, lines = [], None, []
    for line in text.splitlines():
        if line.startswith("# "):
            document = line[2:].strip()
        elif line.startswith("## "):
            sections.append((title, lines))
            title, lines = line[3:].strip(), []
        else:
            lines.append(line)
    sections.append((title, lines))
    chunks = []
    for title, lines in sections:
        body = "\n".join(lines).strip()
        if not title or not body:
            continue
        for part in split_long(body):
            chunks.append(chunk(source, name, len(chunks), title, part, document))
    return chunks


def split_long(body):
    parts, current = [], ""
    for paragraph in re.split(r"\n\s*\n", body):
        if current and len(current) + len(paragraph) > MAX_CHUNK:
            parts.append(current)
            current = ""
        current = (current + "\n\n" + paragraph).strip()
    return parts + [current] if current else parts


def chunk(source, doc_id, number, title, text, document):
    return {"_id": f"{source}:{doc_id}#{number}", "source": source, "docId": doc_id, "title": title,
            "document": document, "text": text,
            "contentHash": hashlib.sha256(f"{title}\n{text}".encode()).hexdigest()}


def money(value):
    return f"{int(value):,}".replace(",", ".") + " ₫"


def product_chunk(product):
    """Catalog knowledge uses the same public fields buyers already see in search results."""
    low, high = product.get("minPrice"), product.get("maxPrice")
    facts = [f"Sản phẩm: {product['name']}."]
    if low is not None:
        facts.append(f"Giá: {money(low)}." if low == high else f"Giá từ {money(low)} đến {money(high)}.")
    facts.append("Tình trạng: còn hàng." if product.get("availableStock", 0) > 0 else "Tình trạng: tạm hết hàng.")
    if product.get("ratingCount"):
        facts.append(f"Đánh giá {product['ratingAverage']:.1f}/5 từ {product['ratingCount']} lượt.")
    if product.get("soldCount"):
        facts.append(f"Đã bán {product['soldCount']}.")
    description = " ".join(str(product.get("description") or "").split())[:600]
    if description:
        facts.append("Mô tả: " + description)
    return chunk("catalog", product["id"], 0, product["name"], " ".join(facts), "Sản phẩm trên VMarket")


class KnowledgeIndex:
    """All current chunks in memory; cosine similarity is one matrix product.

    ponytail: brute force is fine for a FAQ plus a catalog of some thousands of products; a catalog
    far beyond that needs an approximate index (Atlas Vector Search or a dedicated vector store).
    """

    def __init__(self, store, embedder, settings):
        self.store, self.embedder, self.settings = store, embedder, settings
        self.lock = threading.Lock()
        self.state = None  # (chunks, matrix, loaded_at) replaced as a whole, never mutated.

    def load(self):
        chunks = self.store.chunks(self.embedder.fingerprint)
        matrix = (numpy.array([c.pop("embedding") for c in chunks], dtype=numpy.float32)
                  if chunks else numpy.zeros((0, 1), dtype=numpy.float32))
        self.state = (chunks, matrix, time.monotonic())
        return len(chunks)

    def current(self):
        state = self.state
        if state is None or time.monotonic() - state[2] > self.settings.refresh_seconds:
            with self.lock:
                if self.state is state:
                    try:
                        self.load()
                    except Exception:
                        if state is None:
                            raise
                        # Keep answering from the last good copy while MongoDB is unreachable.
                        self.state = (state[0], state[1], time.monotonic())
        return self.state

    def search(self, question):
        chunks, matrix, _ = self.current()
        if not chunks:
            return []
        query = self.embedder.embed([question])[0]
        if query.shape[0] != matrix.shape[1]:
            raise ValueError("Embedding dimensions changed; run manage.py ingest")
        scores = matrix @ query
        best = numpy.argsort(-scores)[:self.settings.top_k]
        return [(chunks[i], float(scores[i])) for i in best if scores[i] >= self.settings.min_score]

    @property
    def size(self):
        return len(self.state[0]) if self.state else 0
