import math
import re
from datetime import datetime, timezone
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator
from starlette.responses import JSONResponse
from contract import (CURRENCY, IMAGE_TOO_LARGE_CODE, IMAGE_TOO_LARGE_MESSAGE, MAX_BODY, MAX_FILE,
                      MAX_PAGE_SIZE, MAX_RESULT_WINDOW, MAX_SUGGESTIONS)

PUBLIC_FIELDS = ("id", "shopId", "name", "description", "imageUrls", "categoryId", "currency",
                 "minPrice", "maxPrice", "ratingAverage", "ratingCount", "soldCount", "availableStock")


class SearchError(Exception):
    def __init__(self, status, code, message):
        self.status, self.code, self.message = status, code, message


def image_too_large():
    return SearchError(413, IMAGE_TOO_LARGE_CODE, IMAGE_TOO_LARGE_MESSAGE)


def error_response(error, path):
    return JSONResponse({"timestamp": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
                         "status": error.status, "error": {"code": error.code, "message": error.message},
                         "path": path}, status_code=error.status)


def invalid_query():
    return SearchError(400, "INVALID_QUERY", "Invalid query parameters")


def parse_query(params, mode):
    allowed = {"keyword", "categoryId", "limit"} if mode == "suggestions" else (
        {"categoryId", "minPrice", "maxPrice", "limit"} if mode == "image" else
        {"keyword", "categoryId", "minPrice", "maxPrice", "sort", "page", "size"})
    pairs = list(params.multi_items())
    if any(k not in allowed for k, _ in pairs) or len({k for k, _ in pairs}) != len(pairs):
        raise invalid_query()
    result = dict(pairs)
    if mode != "image":
        keyword = result.get("keyword", "").strip()
        if not 1 <= len(keyword) <= 200 or any(ord(c) < 32 for c in keyword):
            raise invalid_query()
        result["keyword"] = keyword
    if "categoryId" in result:
        category = result["categoryId"].strip()
        if not 1 <= len(category) <= 128 or any(ord(c) < 32 for c in category):
            raise invalid_query()
        result["categoryId"] = category
    for key in ("minPrice", "maxPrice", "page", "size", "limit"):
        if key in result:
            if not re.fullmatch(r"[0-9]{1,19}", result[key]):
                raise invalid_query()
            result[key] = int(result[key])
            if result[key] > 2**63 - 1:
                raise invalid_query()
    if result.get("minPrice", 0) > result.get("maxPrice", 2**63 - 1):
        raise invalid_query()
    if mode == "keyword":
        result.setdefault("page", 0)
        result.setdefault("size", 20)
        result.setdefault("sort", "RELEVANCE")
        if (not 1 <= result["size"] <= MAX_PAGE_SIZE or (result["page"] + 1) * result["size"] > MAX_RESULT_WINDOW
                or result["sort"] not in {"RELEVANCE", "PRICE_ASC", "PRICE_DESC", "BEST_SELLING", "NEWEST"}):
            raise invalid_query()
    else:
        result.setdefault("limit", 10 if mode == "suggestions" else 20)
        if not 1 <= result["limit"] <= (MAX_SUGGESTIONS if mode == "suggestions" else MAX_PAGE_SIZE):
            raise invalid_query()
    return result


class Snapshot(BaseModel):
    model_config = ConfigDict(strict=True, extra="forbid", allow_inf_nan=False)
    id: str = Field(min_length=1, max_length=128)
    productVersion: int = Field(ge=0)
    catalogVisible: bool
    deleted: bool
    deletedAt: str | None
    shopId: str | None = None
    name: str | None = Field(default=None, max_length=200)
    description: str | None = Field(default=None, max_length=10000)
    imageUrls: list[str] = Field(default_factory=list, max_length=9)
    categoryId: str | None = None
    categoryPath: list[str] = Field(default_factory=list)
    variantPrices: list[int] = Field(default_factory=list)
    currency: Literal[CURRENCY] = CURRENCY
    minPrice: int | None = Field(default=None, ge=0)
    maxPrice: int | None = Field(default=None, ge=0)
    ratingAverage: float = Field(default=0, ge=0, le=5)
    ratingCount: int = Field(default=0, ge=0)
    soldCount: int = Field(default=0, ge=0)
    availableStock: int = Field(default=0, ge=0)
    createdAt: str | None = None
    updatedAt: str | None = None

    @model_validator(mode="after")
    def valid_state(self):
        if (self.deleted and (self.catalogVisible or not self.deletedAt)) or (not self.deleted and self.deletedAt):
            raise ValueError("Invalid tombstone")
        if not self.deleted:
            if any(getattr(self, k) is None for k in ("shopId", "name", "description", "categoryId", "createdAt", "updatedAt")):
                raise ValueError("Incomplete snapshot")
            if any(p < 0 for p in self.variantPrices):
                raise ValueError("Invalid prices")
            if self.minPrice != min(self.variantPrices, default=None) or self.maxPrice != max(self.variantPrices, default=None):
                raise ValueError("Inconsistent prices")
        for value in (self.createdAt, self.updatedAt, self.deletedAt):
            if value is not None and datetime.fromisoformat(value.replace("Z", "+00:00")).utcoffset() != timezone.utc.utcoffset(None):
                raise ValueError("Timestamps must be UTC")
        return self


def public_hit(hit):
    source = hit["_source"]
    result = {key: source.get(key) for key in PUBLIC_FIELDS}
    score = hit.get("_score")
    result["score"] = score if score is None or math.isfinite(score) else None
    return result
