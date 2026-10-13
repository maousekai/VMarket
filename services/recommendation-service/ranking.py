"""Content similarity only; personalized ranking belongs to PR-2."""
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
