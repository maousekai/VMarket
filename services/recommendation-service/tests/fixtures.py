from dataclasses import replace

from settings import Settings


def settings(**changes):
    return replace(Settings(jwt_secret="test-only-secret-0123456789-0123456789", internal_key="test-internal-key"), **changes)


def snapshot(product_id="p1", version=1, **changes):
    data = {"id": product_id, "productVersion": version, "catalogVisible": True, "deleted": False,
            "deletedAt": None, "shopId": "shop-1", "name": "Áo thun đẹp", "description": "Cotton",
            "imageUrls": [], "categoryId": "clothing", "categoryPath": ["clothing"],
            "currency": "VND", "variantPrices": [100000], "minPrice": 100000, "maxPrice": 100000,
            "availableStock": 10, "soldCount": 5, "ratingAverage": 4.5, "ratingCount": 3,
            "createdAt": "2026-10-01T00:00:00Z", "updatedAt": "2026-10-01T00:00:00Z"}
    data.update(changes)
    return data
