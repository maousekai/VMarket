"""Elasticsearch queries and versioned documents; Product owns source data."""
import copy
import math
import logging
import time
import unicodedata

import httpx
from elasticsearch import ConflictError, Elasticsearch, NotFoundError

from contract import EMBEDDING_DIMS, INDEX_PREFIX, RETRY_ATTEMPTS, SNAPSHOTS_PATH
from diagnostics import log_failure
from schemas import PUBLIC_FIELDS, SearchError, Snapshot, public_hit
from settings import Settings
from semantic import EmbeddingBusy, content_hash, product_text, validated_vector

TEXT_FIELDS = ('textVector', 'textHash', 'textFingerprint', 'textState', 'textAttempts',
               'textNextAttempt', 'textError', 'textIndexedAt')
IMAGE_FIELDS = ('images', 'imageState', 'imageAttempts', 'imageNextAttempt', 'imageError', 'imageIndexedAt')
META_TTL_SECONDS = 5


def normalize(text):
    text = text.lower().replace("đ", "d")
    return "".join(c for c in unicodedata.normalize("NFD", text) if not unicodedata.combining(c))


def index_definition(fingerprint=None, settings=None, text_fingerprint=None):
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
    meta = {"searchSchema": 1, "encoderFingerprint": fingerprint}
    if text_fingerprint:
        meta.update(searchSchema=2, textFingerprint=text_fingerprint, textDimensions=settings.text_dims)
        properties.update({k: {"type": "keyword"} for k in ('textHash', 'textFingerprint', 'textState', 'textError')})
        properties.update({k: {"type": "date"} for k in ('textNextAttempt', 'textIndexedAt')})
        properties['textAttempts'] = {"type": "long"}
        properties['textVector'] = {"type": "dense_vector", "dims": settings.text_dims, "index": False}
    return {"settings": {"number_of_shards": 1, "number_of_replicas": 0, "analysis": {
        "char_filter": {"vi_d": {"type": "mapping", "mappings": ["đ => d", "Đ => D"]}},
        "filter": {"vi_synonyms": {"type": "synonym_graph", "synonyms": list(settings.synonyms)}},
        "analyzer": {"vi": {"type": "custom", "char_filter": ["vi_d"], "tokenizer": "standard", "filter": ["lowercase", "asciifolding"]},
                     "vi_search": {"type": "custom", "char_filter": ["vi_d"], "tokenizer": "standard", "filter": ["lowercase", "asciifolding", "vi_synonyms"]}}}},
        "mappings": {"dynamic": "strict", "_meta": meta, "properties": properties}}


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


def hybrid_body(params, terms, settings, vector, fingerprint, threshold):
    body = keyword_body(params, terms, settings)
    lexical = keyword_body({**params, 'sort': 'NEWEST'}, terms, settings)['query']
    lexical = {'script_score': {'query': lexical, 'script': {'source': '_score / (1 + _score)'}}}
    # shortcut: brute-force cosine over every visible ready doc (textVector index=False, 1 ES processor);
    # fine for the demo catalog (~100s of products), switch to an indexed dense_vector + kNN before ~10k.
    semantic = {'script_score': {'query': {'bool': {'filter': [*filters(params),
        {'term': {'textState': 'ready'}}, {'term': {'textFingerprint': fingerprint}}, {'exists': {'field': 'textVector'}}]}},
        'script': {'source': "(cosineSimilarity(params.vector, 'textVector') + 1) / 2", 'params': {'vector': vector}},
        'min_score': (threshold + 1) / 2}}
    query = {'bool': {'should': [lexical, semantic], 'minimum_should_match': 1}}
    if params['sort'] == 'RELEVANCE':
        body['query']['script_score']['query'] = query
    else:
        body['query'] = query
    return body


def text_threshold(meta, fingerprint):
    calibration = meta.get('textCalibration')
    if (meta.get('searchSchema') != 2 or not fingerprint or meta.get('textFingerprint') != fingerprint
            or not isinstance(calibration, dict) or calibration.get('fingerprint') != fingerprint
            or calibration.get('validated') is not True):
        return None
    threshold = calibration.get('threshold')
    return threshold if type(threshold) in (int, float) and math.isfinite(threshold) and -1 <= threshold <= 1 else None


def metadata_document(snapshot, existing, fingerprint, event_at, text_fingerprint=None):
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
    if text_fingerprint:
        if not snapshot.catalogVisible or snapshot.deleted:
            data.update(textState='none', textAttempts=0, textFingerprint=text_fingerprint)
        elif (existing and existing.get('textHash') == content_hash(data)
              and existing.get('textFingerprint') == text_fingerprint):
            data.update({k: existing[k] for k in TEXT_FIELDS if k in existing})
        else:
            data.update(textState='pending', textAttempts=0, textNextAttempt=0, textError=None,
                        textFingerprint=text_fingerprint, textHash=content_hash(data))
    return data


class SearchStore:
    def __init__(self, settings, client=None, target=None):
        self.settings = settings
        self.client = client or Elasticsearch(settings.es_url, request_timeout=settings.dependency_timeout, max_retries=0)
        self.target = target or settings.alias
        self.managed_alias = target is None
        self.semantic_used = self.semantic_fallback = 0
        self._meta = None

    def cached_meta(self, client=None):
        # shortcut: up to META_TTL_SECONDS stale after an alias swap/recalibration (one 503 window per maintenance).
        cached = self._meta
        if cached and time.monotonic() - cached[0] < META_TTL_SECONDS:
            return cached[1]
        value = self.meta(client)
        self._meta = (time.monotonic(), value)
        return value

    def meta(self, client=None):
        client = client or self.client
        mappings = client.indices.get_mapping(index=self.target)
        if len(mappings) != 1:
            raise ValueError("Search requires exactly one managed index")
        name, mapped = next(iter(mappings.items()))
        meta = mapped["mappings"].get("_meta", {})
        vector = mapped["mappings"].get("properties", {}).get("images", {}).get("properties", {}).get("vector", {})
        if (meta.get("searchSchema") not in (1, 2) or vector.get("dims") != EMBEDDING_DIMS or vector.get("similarity") != "dot_product"
                or not name.startswith(INDEX_PREFIX)):
            raise ValueError("Not a managed Search index")
        if meta.get('searchSchema') == 2:
            text = mapped['mappings'].get('properties', {}).get('textVector', {})
            if (not meta.get('textFingerprint') or text.get('dims') != meta.get('textDimensions')
                    or text.get('index') is not False or text.get('type') != 'dense_vector'):
                raise ValueError('Invalid text projection mapping')
        if self.managed_alias:
            aliases = client.indices.get_alias(name=self.target)
            if set(aliases) != {name} or self.target not in aliases[name]["aliases"]:
                raise ValueError("Concrete index cannot replace the Search alias")
        return name, meta

    def keyword(self, params, terms=(), vector=None, fingerprint=None, threshold=None):
        started = time.monotonic()
        deadline = started + self.settings.dependency_timeout
        def bounded():
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise SearchError(503, 'SEARCH_UNAVAILABLE', 'Search deadline exceeded')
            return self.client.options(request_timeout=remaining)
        for attempt in range(2):
            name, meta = self.cached_meta(client=bounded())
            limit = text_threshold(meta, fingerprint) if threshold is None else threshold
            semantic = vector is not None and limit is not None and meta.get('textFingerprint') == fingerprint
            if semantic:
                body = hybrid_body(params, terms, self.settings, validated_vector(vector, meta['textDimensions']),
                                   fingerprint, limit)
            else:
                body = keyword_body(params, terms, self.settings)
            body['timeout'] = str(max(1, int((deadline - time.monotonic()) * 1000))) + 'ms'
            try:
                result = bounded().search(index=name, body=body, allow_partial_search_results=False)
                break
            except NotFoundError:
                if attempt:
                    raise
                self._meta = None  # Cached index retired by an alias swap; re-resolve once.
        shards = result.get('_shards', {})
        if (time.monotonic() > deadline or result.get('timed_out') is not False
                or shards.get('failed') != 0 or shards.get('successful') != shards.get('total')
                or result['hits']['total'].get('relation') != 'eq'):
            raise SearchError(503, 'SEARCH_UNAVAILABLE', 'Incomplete search response')
        if semantic: self.semantic_used += 1
        else: self.semantic_fallback += 1
        logging.info('text_search_complete semantic=%s duration_ms=%d', semantic, int((time.monotonic() - started) * 1000))
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
        name, meta = self.meta()
        source_index = self.target if self.managed_alias else name
        for _ in range(RETRY_ATTEMPTS):
            try:
                old = self.client.get(index=source_index, id=snapshot.id)
            except NotFoundError:
                old = None
            document = metadata_document(snapshot, old["_source"] if old else None, fingerprint, event_at,
                                         meta.get('textFingerprint') if meta.get('searchSchema') == 2 else None)
            if document is None:
                return
            options = {"if_seq_no": old["_seq_no"], "if_primary_term": old["_primary_term"]} if old else {"op_type": "create"}
            # Read and write through the alias: after a concurrent alias swap the seq_no guard conflicts
            # and the retry re-reads the new index instead of landing the write in the retired one.
            target = {"index": self.target, "require_alias": True} if self.managed_alias else {"index": name}
            try:
                self.client.index(id=snapshot.id, document=document, **target, **options)
                return
            except ConflictError:
                continue
        raise RuntimeError("Concurrent metadata write")

    def pending(self):
        self.meta()
        return self.client.search(index=self.target, body={"size": 1, "sort": [{"imageNextAttempt": "asc"}, {"id": "asc"}],
            "seq_no_primary_term": True, "query": {"bool": {"filter": [
                {"term": {"imageState": "pending"}}, {"range": {"imageNextAttempt": {"lte": int(time.time() * 1000)}}}]}}})["hits"]["hits"]

    def save_job(self, hit, source, owned=None):
        try:
            if owned:
                self.client.update(index=hit['_index'], id=hit['_id'], doc={k: source[k] for k in owned if k in source},
                                   if_seq_no=hit['_seq_no'], if_primary_term=hit['_primary_term'])
            else:
                self.client.index(index=hit.get('_index', self.target), id=hit["_id"], document=source,
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
            self.save_job(hit, source, IMAGE_FIELDS)
            return True
        source["imageAttempts"] += 1
        if not self.save_job(hit, source, IMAGE_FIELDS):
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
            self.save_job({**current, "_id": hit["_id"]}, source, IMAGE_FIELDS)
        return True

    def process_text_job(self, encoder):
        name, meta = self.cached_meta()
        if (not self.settings.text_configured or meta.get('searchSchema') != 2
                or meta.get('textFingerprint') != encoder.fingerprint):
            return False
        pending = self.client.search(index=name, body={'size': 1, 'seq_no_primary_term': True,
            'sort': [{'textNextAttempt': 'asc'}, {'id': 'asc'}], 'query': {'bool': {'filter': [
                {'term': {'textState': 'pending'}}, {'range': {'textNextAttempt': {'lte': int(time.time() * 1000)}}}]}}})['hits']['hits']
        if not pending: return False
        hit = pending[0]
        source = copy.deepcopy(hit['_source'])
        if source['textAttempts'] >= RETRY_ATTEMPTS:
            source.update(textState='failed', textError='TEXT_RETRIES_EXHAUSTED')
            self.save_job(hit, source, TEXT_FIELDS)
            return True
        source['textAttempts'] += 1
        attempt = source['textAttempts']
        if not self.save_job(hit, source, TEXT_FIELDS): return True
        try:
            vector = encoder.embed_catalog(product_text(source))
            source.update(textVector=validated_vector(vector, meta['textDimensions']), textState='ready',
                          textError=None, textIndexedAt=int(time.time() * 1000))
        except EmbeddingBusy:
            source.update(textAttempts=source['textAttempts'] - 1, textNextAttempt=int((time.time() + 1) * 1000))
        except Exception as error:
            log_failure('catalog_text_failed', error)
            source.update(textState='failed' if source['textAttempts'] >= RETRY_ATTEMPTS else 'pending',
                          textError='TEXT_PROCESSING_FAILED', textNextAttempt=int((time.time() + 2 ** (source['textAttempts'] - 1)) * 1000))
        current = self.client.get(index=name, id=hit['_id'])
        actual = current['_source']
        # Rebase unchanged text onto newer metadata; never publish across a text/visibility change.
        if (actual['productVersion'] >= source['productVersion'] and actual.get('catalogVisible') and not actual.get('deleted')
                and actual.get('textAttempts') == attempt
                and actual.get('textHash') == source['textHash']
                and actual.get('textFingerprint') == encoder.fingerprint and actual.get('textState') == 'pending'):
            self.save_job(current, source, TEXT_FIELDS)
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
