import logging
import asyncio
from contextlib import asynccontextmanager

import jwt
from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.exceptions import RequestValidationError
from starlette.exceptions import HTTPException
from starlette.responses import JSONResponse

from event_consumer import Consumer
from schemas import RecommendationError, error_response, valid_id
from settings import Settings
from store import Store

BASE = "/api/ai/recommendations"


def authenticated_user(request, secret):
    values = request.headers.getlist("authorization")
    if not values:
        return ""
    try:
        if len(values) != 1 or not values[0].startswith("Bearer "):
            raise ValueError("Invalid authorization")
        claims = jwt.decode(values[0][7:], secret, algorithms=["HS256"], issuer="auth-service",
                            leeway=30, options={"require": ["exp", "sub", "iss"]})
        if not valid_id(claims["sub"]):
            raise ValueError("Invalid subject")
        return claims["sub"]
    except (jwt.PyJWTError, ValueError, TypeError):
        raise RecommendationError(401, "UNAUTHORIZED", "Invalid access token") from None


def parameters(request, category=False):
    pairs = list(request.query_params.multi_items())
    allowed = {"limit", "category_id"} if category else {"limit"}
    if any(k in {"user_id", "category_id"} and k not in allowed for k, _ in pairs):
        raise RecommendationError(400, "INVALID_QUERY", "Invalid query parameters")
    pairs = [(k, v) for k, v in pairs if k in allowed]
    if len({k for k, _ in pairs}) != len(pairs):
        raise RecommendationError(400, "INVALID_QUERY", "Invalid query parameters")
    values = dict(pairs)
    limit = values.get("limit", "12")
    if not limit.isascii() or not limit.isdecimal() or len(limit) > 2 or not 1 <= int(limit) <= 50:
        raise RecommendationError(400, "INVALID_QUERY", "Limit must be between 1 and 50")
    if "category_id" in values and not valid_id(values["category_id"]):
        raise RecommendationError(400, "INVALID_QUERY", "Invalid category ID")
    return int(limit), values.get("category_id")


def create_app(settings=None, store=None, run_consumer=True):
    settings = settings or Settings.load()
    store = store or Store(settings)
    consumer = Consumer(store) if run_consumer else None

    @asynccontextmanager
    async def lifespan(app):
        settings.require_secrets()
        try:
            store.start()
            if consumer:
                consumer.start()
            yield
        finally:
            if consumer:
                consumer.close()
            store.close()

    app = FastAPI(title="VMarket Recommendation Service", version="1.0.0", lifespan=lifespan)
    app.state.store, app.state.consumer = store, consumer
    app.add_middleware(CORSMiddleware,
        allow_origins=[origin.strip() for origin in settings.cors.split(",") if origin.strip()],
        allow_credentials=True, allow_methods=["GET"], allow_headers=["Content-Type", "Authorization"])

    @app.exception_handler(RecommendationError)
    async def recommendation_error(request, error):
        return error_response(error, request.url.path)

    @app.exception_handler(HTTPException)
    async def http_error(request, error):
        code = {400: "INVALID_QUERY", 401: "UNAUTHORIZED", 403: "FORBIDDEN", 404: "NOT_FOUND",
                405: "METHOD_NOT_ALLOWED", 413: "PAYLOAD_TOO_LARGE", 429: "TOO_MANY_REQUESTS"}.get(error.status_code, "HTTP_ERROR")
        response = error_response(RecommendationError(error.status_code, code, str(error.detail)), request.url.path)
        if error.headers:
            response.headers.update(error.headers)
        return response

    @app.exception_handler(RequestValidationError)
    async def validation_error(request, error):
        return error_response(RecommendationError(400, "INVALID_QUERY", "Invalid request parameters"), request.url.path)

    def recommend(**options):
        try:
            return store.recommendations(**options)
        except RecommendationError:
            raise
        except Exception as error:
            logging.warning("recommendation_unavailable type=%s", type(error).__name__)
            raise RecommendationError(503, "RECOMMENDATION_UNAVAILABLE", "Recommendation dependency unavailable") from None

    @app.get(BASE + "/for-you")
    def for_you(request: Request):
        user_id = authenticated_user(request, settings.jwt_secret)
        limit, category = parameters(request, category=True)
        return {"user_id": user_id, "items": recommend(category=category, limit=limit)}

    @app.get(BASE + "/similar/{product_id}")
    def similar_products(product_id: str, request: Request):
        authenticated_user(request, settings.jwt_secret)
        limit, _ = parameters(request)
        if not valid_id(product_id):
            raise RecommendationError(400, "INVALID_QUERY", "Invalid product ID")
        return {"product_id": product_id, "items": recommend(seed_id=product_id, limit=limit)}

    @app.get(BASE + "/health")
    async def health():
        state = await asyncio.to_thread(store.health)
        ready = state["postgres"] == "ready"
        return JSONResponse({"status": "UP" if ready else "DOWN", "service": "recommendation-service", **state,
                "rabbitmq": "ready" if consumer and consumer.connected else "unavailable",
                "rabbitmqDeadLetters": consumer.dead_letters if consumer else None}, status_code=200 if ready else 503)

    return app


app = create_app()
