"""Load operator configuration explicitly; environment overrides the service .env."""
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


@dataclass(frozen=True)
class Settings:
    app_env: str = env_field("APP_ENV", "dev")
    mongo_host: str = env_field("MONGO_HOST", "localhost")
    mongo_port: int = env_field("MONGO_PORT", 27018, int)
    mongo_direct: bool = env_field("MONGO_DIRECT_CONNECTION", True, boolean)
    db_name: str = env_field("DB_NAME", "vmarket_chatbot")
    mongo_timeout: float = env_field("MONGO_TIMEOUT_SECONDS", 3.0, float)
    jwt_secret: str = env_field("AUTH_JWT_SECRET", "dev-only-insecure-secret-change-me-please-0123456789")
    order_url: str = env_field("ORDER_SERVICE_URL", "http://localhost:8086")
    order_timeout: float = env_field("ORDER_TIMEOUT_SECONDS", 3.0, float)
    product_url: str = env_field("PRODUCT_SERVICE_URL", "http://localhost:8084")
    internal_key: str = env_field("INTERNAL_API_KEY", "")
    llm_enabled: bool = env_field("LLM_ENABLED", True, boolean)
    llm_provider: str = env_field("LLM_PROVIDER", "openai_compatible")
    llm_url: str = env_field("LLM_BASE_URL", "")
    llm_model: str = env_field("LLM_MODEL", "")
    llm_key: str = env_field("LLM_API_KEY", "")
    external_text: bool = env_field("ALLOW_EXTERNAL_QUERY_TEXT", False, boolean)
    llm_timeout: float = env_field("LLM_TIMEOUT_SECONDS", 20.0, float)
    llm_max_tokens: int = env_field("LLM_MAX_TOKENS", 512, int)
    embedding_provider: str = env_field("EMBEDDING_PROVIDER", "hashing")
    embedding_model: str = env_field("EMBEDDING_MODEL", "")
    embedding_timeout: float = env_field("EMBEDDING_TIMEOUT_SECONDS", 10.0, float)
    top_k: int = env_field("RETRIEVAL_TOP_K", 4, int)
    min_score: float = env_field("RETRIEVAL_MIN_SCORE", 0.25, float)
    refresh_seconds: float = env_field("KNOWLEDGE_REFRESH_SECONDS", 60.0, float)
    history_turns: int = env_field("CHAT_HISTORY_TURNS", 3, int)
    support_contact: str = env_field("SUPPORT_CONTACT", "mục Hỗ trợ trên VMarket")
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
        for value in (self.order_url, self.product_url):
            endpoint(value)
        if self.llm_provider not in {"openai_compatible", "ollama"}:
            raise ValueError("Unsupported LLM_PROVIDER")
        if self.llm_url:
            endpoint(self.llm_url, https=self.llm_provider != "ollama")
        if self.embedding_provider not in {"hashing", "openai_compatible"}:
            raise ValueError("Unsupported EMBEDDING_PROVIDER")
        if self.embedding_provider == "openai_compatible" and not (self.embedding_model and self.external_embeddings):
            raise ValueError("Remote embeddings need LLM_BASE_URL, EMBEDDING_MODEL and provider access")
        if self.app_env not in {"dev", "prod"} or not 1 <= self.mongo_port <= 65535 or not self.mongo_host:
            raise ValueError("Invalid environment or MongoDB address")
        # HS256 needs a 256-bit key; the Java services reject shorter secrets at startup too.
        if len(self.jwt_secret.encode()) < 32:
            raise ValueError("AUTH_JWT_SECRET must be at least 32 bytes")
        for value in (self.mongo_timeout, self.order_timeout, self.llm_timeout, self.embedding_timeout,
                      self.refresh_seconds):
            if not math.isfinite(value) or value <= 0:
                raise ValueError("Timeouts must be finite and positive")
        if not 1 <= self.top_k <= 10 or not 0 <= self.history_turns <= 10 or self.llm_max_tokens < 1:
            raise ValueError("Invalid retrieval or LLM limits")
        if not math.isfinite(self.min_score) or not 0 < self.min_score < 1:
            raise ValueError("RETRIEVAL_MIN_SCORE must be between 0 and 1")
        if self.app_env == "prod" and self.jwt_secret.startswith(("dev-", "replace-")):
            raise ValueError("Production requires a real AUTH_JWT_SECRET")

    @property
    def mongo_uri(self):
        return f"mongodb://{self.mongo_host}:{self.mongo_port}/?directConnection={str(self.mongo_direct).lower()}"

    @property
    def external_embeddings(self):
        return bool(self.llm_url) and (self.llm_provider == "ollama" or bool(self.llm_key and self.external_text))

    @property
    def llm_configured(self):
        return self.llm_enabled and bool(self.llm_url and self.llm_model) and (
            self.llm_provider == "ollama" or bool(self.llm_key and self.external_text))
