"""Content similarity only; personalized ranking belongs to PR-2."""
import re
import unicodedata


def tokens(product):
    text = ((product.get("name") or "") + " " + (product.get("description") or "")).casefold().replace("đ", "d")
    text = "".join(c for c in unicodedata.normalize("NFD", text) if not unicodedata.combining(c))
    return set(re.findall(r"[^\W_]+", text, re.UNICODE))


def content_score(seed, candidate):
    left, right = tokens(seed), tokens(candidate)
    overlap = len(left & right) / len(left | right) if left | right else 0
    category = bool(seed.get("categoryId") and seed["categoryId"] == candidate.get("categoryId"))
    return (category + overlap) / 2


def price_distance(seed, candidate):
    left, right = seed.get("minPrice"), candidate.get("minPrice")
    return abs(left - right) / max(1, left, right) if left is not None and right is not None else float("inf")


def popular(products):
    return sorted(products, key=lambda p: (-p["soldCount"], -p["ratingAverage"], p["id"]))


def similar(seed, products):
    return sorted((p for p in products if p["id"] != seed["id"]),
                  key=lambda p: (-content_score(seed, p), price_distance(seed, p), -p["soldCount"], p["id"]))
