"""Deterministic content, collaborative and cold-start ranking."""
import re
import unicodedata
from heapq import nsmallest


def tokens(product):
    text = ((product.get("name") or "") + " " + (product.get("description") or "")).casefold().replace("đ", "d")
    text = "".join(c for c in unicodedata.normalize("NFD", text) if not unicodedata.combining(c))
    return set(re.findall(r"[^\W_]+", text, re.UNICODE))


def content_score(seed, candidate, seed_tokens=None):
    left = tokens(seed) if seed_tokens is None else seed_tokens
    right = tokens(candidate)
    overlap = len(left & right) / len(left | right) if left | right else 0
    left_path, right_path = set(seed.get("categoryPath") or []), set(candidate.get("categoryPath") or [])
    category = len(left_path & right_path) / len(left_path | right_path) if left_path | right_path else 0
    return (category + overlap) / 2


def price_distance(seed, candidate):
    left, right = seed.get("minPrice"), candidate.get("minPrice")
    return abs(left - right) / max(1, left, right) if left is not None and right is not None else float("inf")


def popular(products):
    return nsmallest(50, products, key=lambda p: (-p["soldCount"], -p["ratingAverage"], p["id"]))


def similar(seed, products):
    seed_tokens = tokens(seed)
    return nsmallest(50, (p for p in products if p["id"] != seed["id"]),
                    key=lambda p: (-content_score(seed, p, seed_tokens), price_distance(seed, p), -p["soldCount"], p["id"]))


def personalized(seeds, products, collaborative, purchased):
    seed_tokens = [tokens(seed) for seed in seeds]
    scores = []
    for product in products:
        if product["id"] in purchased:
            continue
        content = sum(content_score(seed, product, terms) for seed, terms in zip(seeds, seed_tokens)) / len(seeds) if seeds else 0
        scores.append((product["id"], product["soldCount"], product["ratingAverage"], content,
                       collaborative.get(product["id"], 0)))
    content_max = max((row[3] for row in scores), default=0)
    collaborative_max = max((row[4] for row in scores), default=0)
    def order(row):
        content = row[3] / content_max if content_max else 0
        score = (content + row[4] / collaborative_max) / 2 if collaborative_max else content
        return -score, -row[1], -row[2], row[0]
    return [row[0] for row in nsmallest(50, scores, key=order)]
