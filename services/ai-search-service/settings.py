"""Operator configuration; request data never chooses a host, model or credential."""
import os
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlsplit

from dotenv import load_dotenv

ROOT = Path(__file__).resolve().parent
load_dotenv(ROOT / ".env", override=False)


def endpoint(value: str, *, https=False) -> str:
    parsed = urlsplit(value)
    if (parsed.scheme not in ({"https"} if https else {"http", "https"})
            or not parsed.hostname or parsed.username or parsed.password
            or parsed.query or parsed.fragment):
        raise ValueError("Invalid configured endpoint")
    return value.rstrip("/")


@dataclass(frozen=True)
class Settings:
    es_url: str = os.getenv("ELASTICSEARCH_URL", "http://localhost:9200")
    alias: str = os.getenv("SEARCH_INDEX_ALIAS", "vmarket-products")
    product_url: str = os.getenv("PRODUCT_SERVICE_URL", "http://localhost:8084")
    internal_key: str = os.getenv("INTERNAL_API_KEY", "")
    minio_endpoint: str = os.getenv("MINIO_ENDPOINT", "http://localhost:9000")
    minio_origin: str = os.getenv("MINIO_PUBLIC_ORIGIN", "http://localhost:9000")
    bucket: str = os.getenv("MINIO_BUCKET", "vmarket-media")
    minio_key: str = os.getenv("MINIO_ACCESS_KEY", "")
    minio_secret: str = os.getenv("MINIO_SECRET_KEY", "")
    embedding_model: str = os.getenv("EMBEDDING_MODEL", "mobilenet_v3_small")
    weights: str = os.getenv("EMBEDDING_WEIGHTS_PATH", str(ROOT / "models/mobilenet_v3_small.pth"))
    weights_sha256: str = os.getenv("EMBEDDING_WEIGHTS_SHA256", "")
    llm_enabled: bool = os.getenv("LLM_ENABLED", "true").lower() == "true"
    llm_provider: str = os.getenv("LLM_PROVIDER", "openai_compatible")
    llm_url: str = os.getenv("LLM_BASE_URL", "https://generativelanguage.googleapis.com/v1beta/openai/")
    llm_model: str = os.getenv("LLM_MODEL", "")
    llm_key: str = os.getenv("LLM_API_KEY", "")
    external_text: bool = os.getenv("ALLOW_EXTERNAL_QUERY_TEXT", "false").lower() == "true"
    llm_timeout_ms: int = int(os.getenv("LLM_TIMEOUT_MS", "500"))
    llm_max_tokens: int = int(os.getenv("LLM_MAX_TOKENS", "128"))
    llm_format: str = os.getenv("LLM_RESPONSE_FORMAT", "json_object")
    llm_reasoning: str = os.getenv("LLM_REASONING_EFFORT", "")
    cors: str = os.getenv("CORS_ALLOWED_ORIGINS", "http://localhost:5173")

    def __post_init__(self):
        for value in (self.es_url, self.product_url, self.minio_endpoint, self.minio_origin):
            endpoint(value)
        endpoint(self.llm_url, https=self.llm_provider != "ollama")
        if self.llm_provider not in {"openai_compatible", "ollama"}:
            raise ValueError("Unsupported LLM_PROVIDER")
        if self.llm_format not in {"json_object", "text"} or not 1 <= self.llm_timeout_ms <= 500:
            raise ValueError("Invalid LLM limits")
        if not 1 <= self.llm_max_tokens <= 128 or self.embedding_model != "mobilenet_v3_small":
            raise ValueError("Unsupported model or token limit")
        if os.getenv("APP_ENV", "dev") == "prod":
            secrets = (self.internal_key, self.minio_key, self.minio_secret,
                       os.getenv("RABBITMQ_USERNAME", "guest"), os.getenv("RABBITMQ_PASSWORD", "guest"))
            if any(not v or v == "guest" or v.startswith(("dev-", "replace-")) for v in secrets):
                raise ValueError("Production requires infrastructure credentials")

    @property
    def llm_configured(self):
        return self.llm_enabled and bool(self.llm_model) and (
            self.llm_provider == "ollama" or bool(self.llm_key and self.external_text))
