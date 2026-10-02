"""Explicit local maintenance tools. Never called from public handlers."""
import argparse
import asyncio
import hashlib
import json
import re
import time
from pathlib import Path

import httpx
import pika
from dotenv import dotenv_values

from contract import IMAGE_PATH, INDEX_PREFIX, MAX_PAGE_SIZE, SEARCH_PATH, SNAPSHOTS_PATH
from diagnostics import log_failure
from event_consumer import connection_parameters, declare_topology, validate_event
from images import ImageEncoder, WEIGHTS_URL, decoded_image, s3_client
from llm import Expander
from schemas import MAX_FILE, Snapshot
from search import SearchStore, image_threshold, index_definition
from settings import ROOT, Settings, endpoint


def write_json(path, data):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    temporary.replace(path)


def topology(settings, require_stopped=False):
    with pika.BlockingConnection(connection_parameters(settings)) as connection:
        channel = connection.channel()
        declare_topology(channel, settings)
        if require_stopped and channel.queue_declare(queue=settings.queue, passive=True).method.consumer_count:
            raise ValueError("Stop Search workers before rebuilding")


def weights(settings):
    path = Path(settings.weights)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(".download")
    try:
        digest = hashlib.sha256()
        count = 0
        with httpx.stream("GET", WEIGHTS_URL, trust_env=False, follow_redirects=False, timeout=settings.weights_timeout) as response:
            response.raise_for_status()
            with temporary.open("wb") as stream:
                for chunk in response.iter_bytes():
                    count += len(chunk)
                    if count > 20_000_000:
                        raise ValueError("Unexpected weights size")
                    digest.update(chunk)
                    stream.write(chunk)
        if not digest.hexdigest().startswith("047dcff4"):
            raise ValueError("Official weights checksum mismatch")
        temporary.replace(path)
        path.with_suffix(path.suffix + ".sha256").write_text(digest.hexdigest(), encoding="ascii")
        print("Verified official MobileNetV3 weights cached")
    finally:
        temporary.unlink(missing_ok=True)


def evaluate(rows, threshold):
    positives = [r for r in rows if r["positive"]]
    negatives = [r for r in rows if not r["positive"]]
    correct = sum(r["correctSimilarity"] is not None and r["correctSimilarity"] >= threshold for r in positives)
    unrelated_false = sum(r["bestSimilarity"] is not None and r["bestSimilarity"] >= threshold for r in negatives)
    false = unrelated_false + sum(r["bestSimilarity"] is not None and r["bestSimilarity"] >= threshold
        and (r["correctSimilarity"] is None or r["correctSimilarity"] < threshold) for r in positives)
    precision = correct / (correct + false) if correct + false else 0
    recall = correct / len(positives) if positives else 0
    return {"recallAt5": recall, "falseAcceptance": unrelated_false / len(negatives) if negatives else 0,
            "f1": 2 * precision * recall / (precision + recall) if precision + recall else 0}


def calibrate(store, encoder, manifest):
    groups = {}
    paths = set()
    hashes = set()
    for group in ("calibration", "heldOut"):
        queries = manifest[group]
        if sum(bool(q["acceptableProductIds"]) for q in queries) < 10 or sum(not q["acceptableProductIds"] for q in queries) < 10:
            raise ValueError("Each split requires >=10 positive and >=10 unrelated queries")
        rows = []
        for query in queries:
            path = str(Path(query["image"]).resolve())
            if path in paths:
                raise ValueError("Calibration and held-out query images must be disjoint")
            paths.add(path)
            data = read_image_file(path)
            digest = hashlib.sha256(data).hexdigest()
            if digest in hashes:
                raise ValueError("Quality query images must have distinct contents")
            hashes.add(digest)
            hits = store.image(encoder.embed_image(data), {"limit": 5}, encoder.fingerprint, threshold=-1)["results"]
            correct = [h["similarity"] for h in hits if h["id"] in query["acceptableProductIds"]]
            rows.append({"positive": bool(query["acceptableProductIds"]), "bestSimilarity": hits[0]["similarity"] if hits else None,
                         "correctSimilarity": max(correct, default=None)})
        groups[group] = rows
    choices = {-1.0, 1.0}
    choices.update(r[k] for r in groups["calibration"] for k in ("bestSimilarity", "correctSimilarity") if r[k] is not None)
    threshold = max(choices, key=lambda t: (evaluate(groups["calibration"], t)["f1"], t))
    result = evaluate(groups["heldOut"], threshold)
    exact = manifest.get("exactImages", [])
    if not exact:
        raise ValueError("At least one exact-image top-one check required")
    for query in exact:
        hits = store.image(encoder.embed_image(read_image_file(query["image"])), {"limit": 1}, encoder.fingerprint, threshold=threshold)["results"]
        if not hits or hits[0]["id"] not in query["acceptableProductIds"]:
            raise ValueError("Exact-image quality check failed")
    validated = result["recallAt5"] >= 0.8 and result["falseAcceptance"] <= 0.1
    report = {"fingerprint": encoder.fingerprint, "threshold": threshold, "validated": validated,
              "calibration": evaluate(groups["calibration"], threshold), "heldOut": result, "exactTopOne": True}
    write_json(ROOT / ".local/calibration-report.json", report)
    if not validated:
        raise ValueError("Image quality gate failed; candidate retained, alias unchanged")
    return report


def read_image_file(path):
    with Path(path).open("rb") as stream:
        data = stream.read(MAX_FILE + 1)
    if len(data) > MAX_FILE:
        raise ValueError(f"Fixture image exceeds {MAX_FILE} bytes")
    return data


def snapshots(settings):
    with httpx.Client(trust_env=False, follow_redirects=False, timeout=settings.dependency_timeout,
                      headers={"X-Internal-Api-Key": settings.internal_key}) as client:
        page = 0
        while True:
            response = client.get(settings.product_url.rstrip("/") + SNAPSHOTS_PATH, params={"page": page, "size": MAX_PAGE_SIZE})
            response.raise_for_status()
            if len(response.content) > 6_000_000:
                raise ValueError("Export page too large")
            data = response.json()
            if data["page"] != page or len(data["items"]) > MAX_PAGE_SIZE:
                raise ValueError("Invalid export page")
            for item in data["items"]:
                yield Snapshot.model_validate(item)
            if not data["hasMore"]:
                break
            if not data["items"]:
                raise ValueError("Empty continuing page")
            page += 1


def reindex(settings, args):
    if not args.maintenance:
        raise ValueError("Requires --maintenance after pausing ALL catalog writers and stopping Search")
    if not re.fullmatch(re.escape(INDEX_PREFIX) + r"[a-z0-9-]{1,80}", args.target):
        raise ValueError("Invalid managed index name")
    topology(settings, require_stopped=True)
    encoder = ImageEncoder(settings)
    encoder.load()
    store = SearchStore(settings, target=args.target)
    client = store.client
    if client.indices.exists(index=args.target):
        raise ValueError("Target already exists; choose a fresh target")
    old = SearchStore(settings)
    previous_name = previous_meta = None
    try:
        previous_name, previous_meta = old.meta()
    except Exception as error:
        log_failure("previous_index_unavailable", error)
        if client.indices.exists(index=settings.alias):
            raise ValueError("Alias is not a valid managed Search index") from None
    client.indices.create(index=args.target, body=index_definition(encoder.fingerprint, settings))
    revisions = {}
    for snapshot in snapshots(settings):
        if snapshot.id in revisions:
            raise ValueError("Duplicate outward ID; inspect mixed Mongo _id types")
        revisions[snapshot.id] = snapshot.productVersion
        store.apply_snapshot(snapshot, int(time.time() * 1000), encoder.fingerprint)
    client.indices.refresh(index=args.target)
    while client.count(index=args.target, query={"term": {"imageState": "pending"}})["count"]:
        store.process_image_job(encoder)
        client.indices.refresh(index=args.target)
        time.sleep(0.1)
    bad = client.count(index=args.target, query={"terms": {"imageState": ["failed", "pending"]}})["count"]
    if bad:
        raise ValueError("Candidate has failed/pending images")
    # A second snapshot pass detects ordinary mutations; quiescence remains an operator prerequisite.
    current = {s.id: s.productVersion for s in snapshots(settings)}
    if current != revisions or client.count(index=args.target)["count"] != len(revisions):
        raise ValueError("Catalog changed during maintenance or export count mismatch")
    if args.calibration:
        calibration = calibrate(store, encoder, json.loads(Path(args.calibration).read_text(encoding="utf-8")))
    else:
        calibration = (previous_meta or {}).get("calibration", {})
        image_threshold(previous_meta or {}, encoder.fingerprint)
    meta = {"searchSchema": 1, "encoderFingerprint": encoder.fingerprint, "calibration": calibration}
    client.indices.put_mapping(index=args.target, _meta=meta)
    actions = [{"remove": {"index": previous_name, "alias": settings.alias}}] if previous_name else []
    actions.append({"add": {"index": args.target, "alias": settings.alias, "is_write_index": True}})
    client.indices.update_aliases(actions=actions)
    print(f"Rebuilt {len(revisions)} products; alias switched to {args.target}")


def seed(settings, args):
    private = dotenv_values(args.credentials)
    if not private.get("SEED_SELLER_TOKEN") or not private.get("MINIO_WRITE_ACCESS_KEY") or not private.get("MINIO_WRITE_SECRET_KEY"):
        raise ValueError("Seed requires separate seller token and MinIO write credentials")
    manifest_raw = Path(args.input).read_text(encoding="utf-8")
    manifest = json.loads(manifest_raw)
    topology(settings)
    output = ROOT / ".local/search-fixtures.json"
    input_hash = hashlib.sha256(manifest_raw.encode()).hexdigest()
    saved = json.loads(output.read_text(encoding="utf-8")) if output.exists() else {"products": [], "inputHash": input_hash}
    if saved.get("inputHash") != input_hash:
        raise ValueError("Fixture manifest changed; preserve the recorded catalog and use a new dedicated fixture set")
    known = {p["ref"]: p for p in saved["products"]}
    s3 = s3_client(settings, private["MINIO_WRITE_ACCESS_KEY"], private["MINIO_WRITE_SECRET_KEY"])
    api_url = endpoint(private.get("SEED_API_URL", "http://localhost:8080"))
    with httpx.Client(trust_env=False, follow_redirects=False, timeout=settings.maintenance_timeout,
                      headers={"Authorization": "Bearer " + private["SEED_SELLER_TOKEN"]}) as client:
        for item in manifest["products"]:
            if item["ref"] in known:
                continue
            body = dict(item["product"])
            urls = []
            for photo in item["images"]:
                if not photo.get("license"):
                    raise ValueError("Fixture image license/source required")
                path = Path(photo["file"])
                data = read_image_file(path)
                image = decoded_image(data)
                image.close()
                extension = path.suffix.lower().lstrip(".")
                if extension not in {"jpg", "jpeg", "png", "webp"}:
                    raise ValueError("Invalid fixture extension")
                key = "products/" + hashlib.sha256(data).hexdigest() + "." + extension
                # Identical bytes use an immutable content key; no changing URL content.
                s3.put_object(Bucket=settings.bucket, Key=key, Body=data, ContentType="image/" + ("jpeg" if extension == "jpg" else extension))
                urls.append(settings.minio_origin.rstrip("/") + "/" + settings.bucket + "/" + key)
            body["imageUrls"] = urls
            identity = hashlib.sha256(json.dumps([item["ref"], body], sort_keys=True).encode()).hexdigest()
            response = client.post(api_url + "/api/products", json=body, headers={"Idempotency-Key": "search-fixture-" + identity})
            response.raise_for_status()
            record = {"ref": item["ref"], "id": response.json()["id"], "keyword": item["keyword"], "product": body}
            known[item["ref"]] = record
            saved["products"].append(record)
            write_json(output, saved)
    for group in ("calibration", "heldOut", "exactImages"):
        saved[group] = [{"image": str(Path(q["image"]).resolve()), "acceptableProductIds": [known[r]["id"] for r in q["acceptableProductRefs"]]}
                        for q in manifest[group]]
    write_json(output, saved)
    s3.close()
    print(f"Recorded {len(saved['products'])} dedicated fixture products")


def retry_images(settings, product_id):
    store = SearchStore(settings)
    name, _ = store.meta()
    hit = store.client.get(index=name, id=product_id)
    source = hit["_source"]
    if not source["catalogVisible"] or source["deleted"] or source["imageState"] != "failed":
        raise ValueError("Only a visible current failed job can be reset")
    source.update(imageState="pending", imageAttempts=0, imageError=None, imageNextAttempt=0)
    if not store.save_job(hit, source):
        raise ValueError("Product changed; retry using its current state")


def replay_dead(settings, limit):
    if not 1 <= limit <= MAX_PAGE_SIZE:
        raise ValueError(f"Replay limit must be 1..{MAX_PAGE_SIZE}")
    QUEUE = settings.queue
    with pika.BlockingConnection(connection_parameters(settings)) as connection:
        channel = connection.channel()
        declare_topology(channel, settings)
        channel.confirm_delivery()
        count = 0
        for _ in range(limit):
            method, _, body = channel.basic_get(queue=QUEUE + ".dead", auto_ack=False)
            if method is None:
                break
            envelope = json.loads(body)
            validate_event(body, envelope["eventType"])
            # Default exchange targets Search's own queue; shared events are never rebroadcast.
            channel.basic_publish(exchange="", routing_key=QUEUE, body=body, mandatory=True,
                                  properties=pika.BasicProperties(delivery_mode=2, content_type="application/json",
                                      headers={"x-search-event-type": envelope["eventType"]}))
            channel.basic_ack(method.delivery_tag)
            count += 1
        print(f"Confirmed replay of {count} Search deliveries")


async def llm_probe(settings):
    if not settings.llm_configured:
        raise ValueError("Configure the selected LLM and cloud-text permission")
    expander = Expander(settings)
    try:
        terms = await expander.expand_query("áo thun")
        if not expander.used or not terms:
            raise ValueError("Configured LLM produced no valid expansion within its deadline")
        print(json.dumps({"terms": terms, "expansionUsed": True}, ensure_ascii=False))
    finally:
        await expander.client.aclose()


def smoke(settings, args):
    manifest = json.loads((ROOT / ".local/search-fixtures.json").read_text(encoding="utf-8"))
    with httpx.Client(base_url=endpoint(args.base_url), trust_env=False, follow_redirects=False,
                      timeout=settings.maintenance_timeout) as client:
        for item in manifest["products"]:
            response = client.get(SEARCH_PATH, params={"keyword": item["keyword"], "size": MAX_PAGE_SIZE})
            response.raise_for_status()
            if item["product"]["status"] == "ACTIVE" and item["id"] not in {h["id"] for h in response.json()["results"]}:
                raise ValueError("Fixture keyword missing")
        for invalid in ({"keyword": "x", "minPrice": 2, "maxPrice": 1},
                        {"keyword": "x", "size": MAX_PAGE_SIZE + 1}, {"keyword": "x", "provider": "x"}):
            assert client.get(SEARCH_PATH, params=invalid).status_code == 400
        assert client.post(IMAGE_PATH, files={"file": ("bad.jpg", b"invalid", "image/jpeg")}).status_code == 400
        for query in manifest["exactImages"]:
            with Path(query["image"]).open("rb") as stream:
                response = client.post(IMAGE_PATH, files={"file": ("query.jpg", stream, "image/jpeg")})
            response.raise_for_status()
            hits = response.json()["results"]
            assert hits and hits[0]["id"] in query["acceptableProductIds"]
    print("Fixture API smoke checks passed")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("weights")
    commands.add_parser("llm-probe")
    commands.add_parser("topology")
    replay = commands.add_parser("replay-dead")
    replay.add_argument("--limit", type=int, default=1)
    rebuild = commands.add_parser("reindex")
    rebuild.add_argument("--target", required=True)
    rebuild.add_argument("--calibration")
    rebuild.add_argument("--maintenance", action="store_true")
    fixtures = commands.add_parser("seed")
    fixtures.add_argument("--input", default=".local/fixtures-input.json")
    fixtures.add_argument("--credentials", default=".env.seed")
    retry = commands.add_parser("retry-images")
    retry.add_argument("--product-id", required=True)
    test = commands.add_parser("smoke")
    test.add_argument("--base-url", default="http://localhost:8080")
    args = parser.parse_args()
    settings = Settings.load()
    if args.command == "weights":
        weights(settings)
    elif args.command == "llm-probe":
        asyncio.run(llm_probe(settings))
    elif args.command == "topology":
        topology(settings)
    elif args.command == "seed":
        seed(settings, args)
    elif args.command == "reindex":
        reindex(settings, args)
    elif args.command == "retry-images":
        retry_images(settings, args.product_id)
    elif args.command == "replay-dead":
        replay_dead(settings, args.limit)
    elif args.command == "smoke":
        smoke(settings, args)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        log_failure("maintenance_command_failed", error)
        # Safe operator-facing code. HTTP exceptions may contain credential-bearing URLs.
        print(f"Command failed: {error if isinstance(error, ValueError) else type(error).__name__}")
        raise SystemExit(1) from None
