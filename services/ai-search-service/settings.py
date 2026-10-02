"""Load operator configuration explicitly; environment overrides the service .env."""
import json
import math
import os
from dataclasses import dataclass, field, fields
from pathlib import Path
from urllib.parse import urlsplit

from dotenv import dotenv_values

ROOT = Path(__file__).resolve().parent


def endpoint(value: str, *, https=False) -> str:
    parsed = urlsplit(value)
    if (parsed.scheme not in ({"https"} if https else {"http", "https"})
            or not parsed.hostname or parsed.username or parsed.password
            or parsed.query or parsed.fragment):
        raise ValueError("Invalid configured endpoint")
    return value.rstrip("/")


def boolean(value):
    if value.lower() not in {"true", "false"}:
        raise ValueError("Expected true or false")
    return value.lower() == "true"


def env_field(name, default, cast=str):
    return field(default=default, metadata={"env": name, "cast": cast})


def synonyms_value(value):
    parsed = json.loads(value)
    if not isinstance(parsed, list):
        raise ValueError("Expected a JSON list")
    return tuple(parsed)


@dataclass(frozen=True)
class Settings:
    app_env: str = env_field("APP_ENV", "dev")
    es_url: str = env_field("ELASTICSEARCH_URL", "http://localhost:9200")
    alias: str = env_field("SEARCH_INDEX_ALIAS", "vmarket-products")
    product_url: str = env_field("PRODUCT_SERVICE_URL", "http://localhost:8084")
    internal_key: str = env_field("INTERNAL_API_KEY", "")
    minio_endpoint: str = env_field("MINIO_ENDPOINT", "http://localhost:9000")
    minio_origin: str = env_field("MINIO_PUBLIC_ORIGIN", "http://localhost:9000")
    bucket: str = env_field("MINIO_BUCKET", "vmarket-media")
    minio_key: str = env_field("MINIO_ACCESS_KEY", "")
    minio_secret: str = env_field("MINIO_SECRET_KEY", "")
    rabbit_host: str = env_field("RABBITMQ_HOST", "localhost")
    rabbit_port: int = env_field("RABBITMQ_PORT", 5672, int)
    rabbit_user: str = env_field("RABBITMQ_USERNAME", "guest")
    rabbit_password: str = env_field("RABBITMQ_PASSWORD", "guest")
    rabbit_heartbeat: int = env_field("RABBITMQ_HEARTBEAT_SECONDS", 30, int)
    rabbit_blocked_timeout: float = env_field("RABBITMQ_BLOCKED_TIMEOUT_SECONDS", 30.0, float)
    rabbit_socket_timeout: float = env_field("RABBITMQ_SOCKET_TIMEOUT_SECONDS", 2.0, float)
    rabbit_connect_timeout: float = env_field("RABBITMQ_CONNECT_TIMEOUT_SECONDS", 5.0, float)
    exchange: str = env_field("EVENT_EXCHANGE", "vmarket.events")
    queue: str = env_field("EVENT_QUEUE", "ai-search.events.v2")
    embedding_model: str = env_field("EMBEDDING_MODEL", "mobilenet_v3_small")
    weights: str = env_field("EMBEDDING_WEIGHTS_PATH", str(ROOT / "models/mobilenet_v3_small.pth"))
    weights_sha256: str = env_field("EMBEDDING_WEIGHTS_SHA256", "")
    llm_enabled: bool = env_field("LLM_ENABLED", True, boolean)
    llm_provider: str = env_field("LLM_PROVIDER", "openai_compatible")
    llm_url: str = env_field("LLM_BASE_URL", "")
    llm_model: str = env_field("LLM_MODEL", "")
    llm_key: str = env_field("LLM_API_KEY", "")
    external_text: bool = env_field("ALLOW_EXTERNAL_QUERY_TEXT", False, boolean)
    llm_timeout_ms: int = env_field("LLM_TIMEOUT_MS", 500, int)
    llm_max_tokens: int = env_field("LLM_MAX_TOKENS", 128, int)
    llm_format: str = env_field("LLM_RESPONSE_FORMAT", "json_object")
    llm_reasoning: str = env_field("LLM_REASONING_EFFORT", "")
    dependency_timeout: float = env_field("SEARCH_DEPENDENCY_TIMEOUT_SECONDS", 2.0, float)
    minio_timeout: float = env_field("MINIO_SOCKET_TIMEOUT_SECONDS", 1.0, float)
    image_download_timeout: float = env_field("IMAGE_DOWNLOAD_TIMEOUT_SECONDS", 5.0, float)
    maintenance_timeout: float = env_field("SEARCH_MAINTENANCE_TIMEOUT_SECONDS", 5.0, float)
    weights_timeout: float = env_field("WEIGHTS_DOWNLOAD_TIMEOUT_SECONDS", 30.0, float)
    synonyms: tuple = env_field("SEARCH_SYNONYMS", ("ao thun, ao phong", "giay the thao, sneaker"), synonyms_value)
    name_boost: float = env_field("SEARCH_NAME_BOOST", 3.0, float)
    phrase_boost: float = env_field("SEARCH_PHRASE_BOOST", 5.0, float)
    synonym_boost: float = env_field("SEARCH_SYNONYM_BOOST", 0.3, float)
    sales_weight: float = env_field("SEARCH_SALES_WEIGHT", 0.1, float)
    rating_weight: float = env_field("SEARCH_RATING_WEIGHT", 0.04, float)
    popularity_cap: float = env_field("SEARCH_POPULARITY_CAP", 2.0, float)
    cors: str = env_field("CORS_ALLOWED_ORIGINS", "http://localhost:5173")

    @classmethod
    def load(cls, env_file=ROOT / ".env", environ=None):
        values = {**dotenv_values(env_file), **(os.environ if environ is None else environ)}
        options = {}
        for item in fields(cls):
            name = item.metadata["env"]
            if name in values and values[name] is not None:
                try:
                    options[item.name] = item.metadata["cast"](values[name])
                except (ValueError, TypeError):
                    raise ValueError(f"Invalid configuration: {name}") from None
        return cls(**options)

    def __post_init__(self):
        for value in (self.es_url, self.product_url, self.minio_endpoint, self.minio_origin):
            endpoint(value)
        if self.llm_url:
            endpoint(self.llm_url, https=self.llm_provider != "ollama")
        if self.llm_provider not in {"openai_compatible", "ollama"}:
            raise ValueError("Unsupported LLM_PROVIDER")
        if self.app_env not in {"dev", "prod"} or not 1 <= self.rabbit_port <= 65535:
            raise ValueError("Invalid environment or RabbitMQ port")
        if self.llm_format not in {"json_object", "text"} or self.llm_timeout_ms < 1 or self.llm_max_tokens < 1:
            raise ValueError("Invalid LLM limits")
        if self.embedding_model != "mobilenet_v3_small":
            raise ValueError("Unsupported embedding model")
        for value in (self.dependency_timeout, self.minio_timeout, self.image_download_timeout,
                      self.rabbit_heartbeat, self.rabbit_blocked_timeout, self.rabbit_socket_timeout, self.rabbit_connect_timeout,
                      self.maintenance_timeout, self.weights_timeout, self.name_boost,
                      self.phrase_boost, self.synonym_boost, self.sales_weight, self.rating_weight, self.popularity_cap):
            if not math.isfinite(value) or value <= 0:
                raise ValueError("Timeouts and ranking weights must be finite and positive")
        if self.rabbit_connect_timeout <= self.rabbit_socket_timeout:
            raise ValueError("Rabbit connection deadline must exceed RABBITMQ_SOCKET_TIMEOUT_SECONDS")
        if len(self.synonyms) > 100 or any(not isinstance(v, str) or not 1 <= len(v) <= 500 for v in self.synonyms):
            raise ValueError("Invalid SEARCH_SYNONYMS")
        if self.app_env == "prod":
            secrets = (self.internal_key, self.minio_key, self.minio_secret, self.rabbit_user, self.rabbit_password)
            if any(not v or v in {"guest", "minioadmin"} or v.startswith(("dev-", "replace-")) for v in secrets):
                raise ValueError("Production requires infrastructure credentials")

    @property
    def llm_configured(self):
        return self.llm_enabled and bool(self.llm_url and self.llm_model) and (
            self.llm_provider == "ollama" or bool(self.llm_key and self.external_text))
