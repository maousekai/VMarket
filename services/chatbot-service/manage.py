"""Operator commands that fill the knowledge base.

    python manage.py ingest             # FAQ/policy markdown in knowledge/
    python manage.py ingest --catalog   # FAQ + visible products exported by product-service
"""
import argparse

import httpx

from knowledge import create_embedder, product_chunk, split_markdown
from schemas import now
from settings import ROOT, Settings
from store import ChatStore

SNAPSHOTS_PATH = "/api/products/internal/search-snapshots"
PAGE_SIZE = 100


def ingest_source(store, embedder, source, chunks):
    """Embed only chunks whose content changed, then drop chunks the source no longer has."""
    known = store.chunk_hashes(source, embedder.fingerprint)
    changed = [c for c in chunks if known.get(c["_id"]) != c["contentHash"]]
    for item, vector in zip(changed, embedder.embed([c["title"] + "\n" + c["text"] for c in changed])):
        item.update(embedding=vector.tolist(), embedder=embedder.fingerprint, updatedAt=now())
    removed = store.replace_source(source, changed, {c["_id"] for c in chunks})
    return len(changed), removed


def faq_chunks(directory=ROOT / "knowledge"):
    chunks = []
    for path in sorted(directory.glob("*.md")):
        chunks.extend(split_markdown("faq", path.stem, path.read_text(encoding="utf-8")))
    return chunks


def ingest_faq(store, embedder, directory=ROOT / "knowledge"):
    return ingest_source(store, embedder, "faq", faq_chunks(directory))


def products(settings, transport=None):
    """Visible products from the product-service snapshot export (X-Internal-Api-Key)."""
    with httpx.Client(trust_env=False, follow_redirects=False, timeout=10, transport=transport,
                      headers={"X-Internal-Api-Key": settings.internal_key}) as client:
        page = 0
        while True:
            response = client.get(settings.product_url.rstrip("/") + SNAPSHOTS_PATH, params={"page": page, "size": PAGE_SIZE})
            response.raise_for_status()
            data = response.json()
            if data["page"] != page or len(data["items"]) > PAGE_SIZE:
                raise ValueError("Invalid export page")
            for item in data["items"]:
                if item.get("catalogVisible") and not item.get("deleted") and isinstance(item.get("name"), str):
                    yield item
            if not data["hasMore"]:
                return
            page += 1


def ingest_catalog(store, embedder, settings, transport=None):
    # The whole export is read before anything is written: a failed page leaves the old chunks intact.
    return ingest_source(store, embedder, "catalog", [product_chunk(p) for p in products(settings, transport)])


def main():
    parser = argparse.ArgumentParser(description="VMarket chatbot knowledge base")
    command = parser.add_subparsers(dest="command", required=True).add_parser("ingest")
    command.add_argument("--catalog", action="store_true", help="also ingest products from product-service")
    arguments = parser.parse_args()
    settings = Settings.load()
    store, embedder = ChatStore(settings), create_embedder(settings)
    try:
        store.ensure_indexes()
        changed, removed = ingest_faq(store, embedder)
        print(f"faq: {changed} chunks written, {removed} removed")
        if arguments.catalog:
            changed, removed = ingest_catalog(store, embedder, settings)
            print(f"catalog: {changed} chunks written, {removed} removed")
    finally:
        embedder.close()
        store.close()


if __name__ == "__main__":
    main()
