"""Elasticsearch queries and versioned documents; Product owns source data."""
import copy
import math
import time
import unicodedata

import httpx
from elasticsearch import ConflictError, Elasticsearch, NotFoundError

from contract import EMBEDDING_DIMS, INDEX_PREFIX, RETRY_ATTEMPTS, SNAPSHOTS_PATH
from diagnostics import log_failure
from schemas import PUBLIC_FIELDS, SearchError, Snapshot, public_hit
from settings import Settings


def normalize(text):
    text = text.lower().replace("đ", "d")
    return "".join(c for c in unicodedata.normalize("NFD", text) if not unicodedata.combining(c))


def index_definition(fingerprint=None, settings=None):
    settings = settings or Settings()
    properties = {k: {"type": "keyword"} for k in ("id", "shopId", "categoryId", "categoryPath", "currency", "imageState", "imageError", "modelFingerprint")}
    properties.update({k: {"type": "long"} for k in ("productVersion", "minPrice", "maxPrice", "variantPrices", "ratingCount", "soldCount", "availableStock", "imageAttempts")})
    properties.update({k: {"type": "date"} for k in ("createdAt", "updatedAt", "deletedAt", "imageNextAttempt", "indexedAt", "imageIndexedAt", "eventAt")})
    properties.update({k: {"type": "boolean"} for k in ("catalogVisible", "deleted")})
    properties["ratingAverage"] = {"type": "float"}
    properties["name"] = {"type": "text", "analyzer": "vi", "search_analyzer": "vi_search",
                          "fields": {"suggest": {"type": "search_as_you_type", "analyzer": "vi", "search_analyzer": "vi"}}}
    properties["description"] = {"type": "text", "analyzer": "vi", "search_analyzer": "vi_search"}
    properties["imageUrls"] = {"type": "keyword", "index": False}
    properties["images"] = {"type": "nested", "properties": {
        "url": {"type": "keyword", "index": False}, "fingerprint": {"type": "keyword"},
        "vector": {"type": "dense_vector", "dims": EMBEDDING_DIMS, "element_type": "float", "index": True,
                   "similarity": "dot_product", "index_options": {"type": "hnsw"}}}}
    return {"settings": {"number_of_shards": 1, "number_of_replicas": 0, "analysis": {
        "char_filter": {"vi_d": {"type": "mapping", "mappings": ["đ => d", "Đ => D"]}},
        "filter": {"vi_synonyms": {"type": "synonym_graph", "synonyms": list(settings.synonyms)}},
        "analyzer": {"vi": {"type": "custom", "char_filter": ["vi_d"], "tokenizer": "standard", "filter": ["lowercase", "asciifolding"]},
                     "vi_search": {"type": "custom", "char_filter": ["vi_d"], "tokenizer": "standard", "filter": ["lowercase", "asciifolding", "vi_synonyms"]}}}},
        "mappings": {"dynamic": "strict", "_meta": {"searchSchema": 1, "encoderFingerprint": fingerprint}, "properties": properties}}


def filters(params):
    result = [{"term": {"catalogVisible": True}}, {"term": {"deleted": False}}]
    if params.get("categoryId"):
        result.append({"term": {"categoryPath": params["categoryId"]}})
    price = {op: params[k] for k, op in (("minPrice", "gte"), ("maxPrice", "lte")) if k in params}
    if price:
        result.append({"range": {"variantPrices": price}})
    return result


def keyword_body(params, terms=(), settings=None):
    settings = settings or Settings()
    keyword = params["keyword"]
    fields = [f"name^{settings.name_boost}", "description"]
    matches = [{"multi_match": {"query": keyword, "fields": fields, "fuzziness": "AUTO", "prefix_length": 1, "max_expansions": 25}},
               {"match_phrase": {"name": {"query": keyword, "boost": settings.phrase_boost}}}]
    matches.extend({"multi_match": {"query": term, "fields": fields, "boost": settings.synonym_boost}} for term in terms)
    query = {"bool": {"filter": filters(params), "must": [{"bool": {"should": matches, "minimum_should_match": 1}}]}}
    if params["sort"] == "RELEVANCE":
        query = {"script_score": {"query": query, "script": {"source":
            "_score * (1 + Math.min(params.cap, params.sales * Math.log(1 + doc['soldCount'].value) + params.rating * doc['ratingAverage'].value))",
            "params": {"cap": settings.popularity_cap, "sales": settings.sales_weight, "rating": settings.rating_weight}}}}
    sorting = {"RELEVANCE": [{"_score": "desc"}], "PRICE_ASC": [{"minPrice": {"order": "asc", "missing": "_last"}}],
               "PRICE_DESC": [{"minPrice": {"order": "desc", "missing": "_last"}}],
               "BEST_SELLING": [{"soldCount": "desc"}], "NEWEST": [{"createdAt": "desc"}]}[params["sort"]]
    return {"query": query, "sort": [*sorting, {"id": "asc"}], "from": params["page"] * params["size"],
            "size": params["size"], "track_total_hits": True, "_source": list(PUBLIC_FIELDS)}


def image_body(vector, params, threshold):
    limit = params["limit"]
    return {"size": limit, "_source": list(PUBLIC_FIELDS), "knn": {
        "field": "images.vector", "query_vector": vector, "k": limit,
        "num_candidates": min(1000, max(100, 5 * limit)), "similarity": threshold,
        "filter": {"bool": {"filter": filters(params)}},
        "inner_hits": {"name": "matched", "size": 1, "_source": ["images.url"]}},
        "sort": [{"_score": "desc"}, {"id": "asc"}]}


def metadata_document(snapshot, existing, fingerprint, event_at):
    if existing and existing["productVersion"] >= snapshot.productVersion:
        return None
    data = snapshot.model_dump()
    if not snapshot.catalogVisible or snapshot.deleted:
        data = {k: data[k] for k in ("id", "productVersion", "catalogVisible", "deleted", "deletedAt")}
        data.update(imageState="none", images=[], imageAttempts=0, modelFingerprint=fingerprint)
    else:
        previous = {item["url"]: item for item in (existing or {}).get("images", []) if item["fingerprint"] == fingerprint}
        urls = list(dict.fromkeys(snapshot.imageUrls))
        data["images"] = [previous[url] for url in urls if url in previous]
        # ponytail: one product job/worker; per-image job ownership only when adding replicas.
        data.update(imageState="ready" if len(data["images"]) == len(urls) else "pending",
                    imageAttempts=0, imageNextAttempt=0, imageError=None, modelFingerprint=fingerprint)
    data.update(indexedAt=int(time.time() * 1000), eventAt=event_at)
    return data


class SearchStore:
    def __init__(self, settings, client=None, target=None):
        self.settings = settings
        self.client = client or Elasticsearch(settings.es_url, request_timeout=settings.dependency_timeout, max_retries=0)
        self.target = target or settings.alias
        self.managed_alias = target is None

    def meta(self):
        mappings = self.client.indices.get_mapping(index=self.target)
        if len(mappings) != 1:
            raise ValueError("Search requires exactly one managed index")
        name, mapped = next(iter(mappings.items()))
        meta = mapped["mappings"].get("_meta", {})
        vector = mapped["mappings"].get("properties", {}).get("images", {}).get("properties", {}).get("vector", {})
        if (meta.get("searchSchema") != 1 or vector.get("dims") != EMBEDDING_DIMS or vector.get("similarity") != "dot_product"
                or not name.startswith(INDEX_PREFIX)):
            raise ValueError("Not a managed Search index")
        if self.managed_alias:
            aliases = self.client.indices.get_alias(name=self.target)
            if set(aliases) != {name} or self.target not in aliases[name]["aliases"]:
                raise ValueError("Concrete index cannot replace the Search alias")
        return name, meta

    def keyword(self, params, terms=()):
        self.meta()
        result = self.client.search(index=self.target, body=keyword_body(params, terms, self.settings))
        total = result["hits"]["total"]["value"]
        return {"keyword": params["keyword"], "results": [public_hit(h) for h in result["hits"]["hits"]],
                "page": params["page"], "size": params["size"], "totalElements": total, "totalPages": math.ceil(total / params["size"])}

    def suggestions(self, params):
        if len(params["keyword"]) < 2:
            return {"keyword": params["keyword"], "results": []}
        self.meta()
        result = self.client.search(index=self.target, body={"size": params["limit"], "_source": ["id", "name"],
            "sort": [{"_score": "desc"}, {"id": "asc"}], "query": {"bool": {"filter": filters(params), "must": [{
                "multi_match": {"query": params["keyword"], "type": "bool_prefix",
                                "fields": ["name.suggest", "name.suggest._2gram", "name.suggest._3gram"]}}]}}})
        return {"keyword": params["keyword"], "results": [h["_source"] for h in result["hits"]["hits"]]}

    def image(self, vector, params, fingerprint, threshold=None):
        name, meta = self.meta()
        threshold = image_threshold(meta, fingerprint, threshold)
        result = self.client.search(index=name, body=image_body(vector, params, threshold))
        hits = []
        for hit in result["hits"]["hits"]:
            similarity = max(-1.0, min(1.0, 2 * hit["_score"] - 1))
            if similarity + 1e-6 < threshold:
                continue
            matched = hit["inner_hits"]["matched"]["hits"]["hits"][0]["_source"]
            hits.append({**public_hit(hit), "matchedImageUrl": matched["url"], "similarity": similarity})
        return {"results": hits, "limit": params["limit"], "totalReturned": len(hits)}

    def fetch_snapshot(self, product_id):
        from urllib.parse import quote
        started = time.monotonic()
        with httpx.Client(trust_env=False, follow_redirects=False, timeout=self.settings.dependency_timeout) as client:
            with client.stream("GET", self.settings.product_url.rstrip("/") + SNAPSHOTS_PATH + "/" + quote(product_id, safe=""),
                               headers={"X-Internal-Api-Key": self.settings.internal_key}) as response:
                response.raise_for_status()
                raw = bytearray()
                for chunk in response.iter_bytes():
                    raw.extend(chunk)
                    if len(raw) > 512000 or time.monotonic() - started > self.settings.dependency_timeout:
                        raise ValueError("Snapshot limit exceeded")
        snapshot = Snapshot.model_validate_json(raw)
        if snapshot.id != product_id:
            raise ValueError("Snapshot ID mismatch")
        return snapshot

    def apply_snapshot(self, snapshot, event_at, fingerprint):
        name, _ = self.meta()
        for _ in range(RETRY_ATTEMPTS):
            try:
                old = self.client.get(index=name, id=snapshot.id)
            except NotFoundError:
                old = None
            document = metadata_document(snapshot, old["_source"] if old else None, fingerprint, event_at)
            if document is None:
                return
            options = {"if_seq_no": old["_seq_no"], "if_primary_term": old["_primary_term"]} if old else {"op_type": "create"}
            try:
                self.client.index(index=self.target, id=snapshot.id, document=document,
                                  require_alias=self.managed_alias, **options)
                return
            except ConflictError:
                continue
        raise RuntimeError("Concurrent metadata write")

    def pending(self):
        self.meta()
        return self.client.search(index=self.target, body={"size": 1, "sort": [{"imageNextAttempt": "asc"}, {"id": "asc"}],
            "seq_no_primary_term": True, "query": {"bool": {"filter": [
                {"term": {"imageState": "pending"}}, {"range": {"imageNextAttempt": {"lte": int(time.time() * 1000)}}}]}}})["hits"]["hits"]

    def save_job(self, hit, source):
        try:
            self.client.index(index=self.target, id=hit["_id"], document=source, require_alias=self.managed_alias,
                              if_seq_no=hit["_seq_no"], if_primary_term=hit["_primary_term"])
            return True
        except ConflictError:
            return False

    def process_image_job(self, encoder):
        if not encoder.ready:
            return False
        _, meta = self.meta()
        if meta.get("encoderFingerprint") != encoder.fingerprint:
            return False
        pending = self.pending()
        if not pending:
            return False
        hit = pending[0]
        source = copy.deepcopy(hit["_source"])
        if source["imageAttempts"] >= RETRY_ATTEMPTS:
            source.update(imageState="failed", imageError="IMAGE_RETRIES_EXHAUSTED")
            self.save_job(hit, source)
            return True
        source["imageAttempts"] += 1
        if not self.save_job(hit, source):
            return True
        try:
            images = {v["url"]: v for v in source["images"]}
            for url in source["imageUrls"]:
                if url not in images:
                    images[url] = {"url": url, "vector": encoder.embed_catalog(url), "fingerprint": encoder.fingerprint}
            source.update(images=list(images.values()), imageState="ready", imageError=None, imageIndexedAt=int(time.time() * 1000))
        except Exception as error:
            log_failure("catalog_image_failed", error)
            source.update(imageState="failed" if source["imageAttempts"] >= RETRY_ATTEMPTS else "pending",
                          imageError="IMAGE_PROCESSING_FAILED", imageNextAttempt=int((time.time() + 2**(source["imageAttempts"] - 1)) * 1000))
        # Reload sequence after the attempt write; verify all identity guards before committing native work.
        current = self.client.get(index=hit["_index"], id=hit["_id"])
        actual = current["_source"]
        if (actual["productVersion"] == source["productVersion"] and actual.get("modelFingerprint") == encoder.fingerprint
                and actual.get("imageUrls") == source.get("imageUrls") and actual.get("imageState") == "pending"):
            self.save_job({**current, "_id": hit["_id"]}, source)
        return True


def image_threshold(meta, fingerprint, threshold=None):
    if not fingerprint or meta.get("encoderFingerprint") != fingerprint:
        raise SearchError(503, "IMAGE_SEARCH_UNAVAILABLE", "Encoder does not match index")
    if threshold is None:
        calibration = meta.get("calibration")
        if (not isinstance(calibration, dict) or calibration.get("fingerprint") != fingerprint
                or calibration.get("validated") is not True):
            raise SearchError(503, "IMAGE_SEARCH_UNAVAILABLE", "Image calibration required")
        threshold = calibration.get("threshold")
    if type(threshold) not in (int, float) or not math.isfinite(threshold) or not -1 <= threshold <= 1:
        raise SearchError(503, "IMAGE_SEARCH_UNAVAILABLE", "Invalid image calibration threshold")
    return threshold
