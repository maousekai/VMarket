from datetime import datetime, timezone
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator
from starlette.responses import JSONResponse

PUBLIC_FIELDS = ("id", "shopId", "name", "description", "imageUrls", "categoryId", "currency",
                 "minPrice", "maxPrice", "ratingAverage", "ratingCount", "soldCount", "availableStock")


class RecommendationError(Exception):
    def __init__(self, status, code, message):
        self.status, self.code, self.message = status, code, message


def error_response(error, path):
    return JSONResponse({"timestamp": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
                         "status": error.status, "error": {"code": error.code, "message": error.message},
                         "path": path}, status_code=error.status)


def valid_id(value):
    return (isinstance(value, str) and 1 <= len(value) <= 128 and value.strip() == value
            and value not in {".", ".."} and not any(c in "/\\?#" or ord(c) < 32 for c in value))


class Snapshot(BaseModel):
    model_config = ConfigDict(strict=True, extra="forbid", allow_inf_nan=False)
    id: str = Field(min_length=1, max_length=128)
    productVersion: int = Field(ge=0, le=2**63 - 1)
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
    currency: Literal["VND"] = "VND"
    minPrice: int | None = Field(default=None, ge=0, le=2**63 - 1)
    maxPrice: int | None = Field(default=None, ge=0, le=2**63 - 1)
    ratingAverage: float = Field(default=0, ge=0, le=5)
    ratingCount: int = Field(default=0, ge=0, le=2**63 - 1)
    soldCount: int = Field(default=0, ge=0, le=2**63 - 1)
    availableStock: int = Field(default=0, ge=0, le=2**63 - 1)
    createdAt: str | None = None
    updatedAt: str | None = None

    @model_validator(mode="after")
    def validate_snapshot(self):
        if not valid_id(self.id):
            raise ValueError("Invalid product ID")
        if self.deleted:
            if self.catalogVisible or not self.deletedAt:
                raise ValueError("Invalid tombstone")
        else:
            if self.deletedAt is not None or any(getattr(self, k) is None for k in
                                               ("shopId", "name", "description", "createdAt", "updatedAt")):
                raise ValueError("Incomplete snapshot")
            if any(type(price) is not int or not 0 <= price <= 2**63 - 1 for price in self.variantPrices):
                raise ValueError("Invalid variant prices")
            if (self.minPrice != min(self.variantPrices, default=None)
                    or self.maxPrice != max(self.variantPrices, default=None)):
                raise ValueError("Inconsistent prices")
        for stamp in (self.createdAt, self.updatedAt, self.deletedAt):
            if stamp is not None and datetime.fromisoformat(stamp.replace("Z", "+00:00")).utcoffset() != timezone.utc.utcoffset(None):
                raise ValueError("Snapshot timestamps must be UTC")
        return self
