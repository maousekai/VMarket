"""Service .env defaults; process environment takes precedence."""
import math
import os
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlsplit

from dotenv import dotenv_values

ROOT = Path(__file__).resolve().parent


@dataclass(frozen=True)
class Settings:
    app_env: str = "dev"
    db_host: str = "localhost"
    db_port: int = 5433
    db_name: str = "vmarket_recommendation"
    db_user: str = "vmarket"
    db_password: str = "vmarket"
    redis_host: str = "localhost"
    redis_port: int = 6379
    redis_password: str = ""
    rabbit_host: str = "localhost"
    rabbit_port: int = 5672
    rabbit_user: str = "guest"
    rabbit_password: str = "guest"
    exchange: str = "vmarket.events"
    queue: str = "recommendation.events"
    product_url: str = "http://localhost:8084"
    internal_key: str = ""
    jwt_secret: str = ""
    cors: str = "http://localhost:5173,http://localhost:5174,http://localhost:3000"
    timeout: float = 2.0

    @classmethod
    def load(cls, env_file=ROOT / ".env", environ=None):
        env = {**dotenv_values(env_file), **(os.environ if environ is None else environ)}
        names = {"app_env": "APP_ENV", "db_host": "DB_HOST", "db_port": "DB_PORT",
                 "db_name": "DB_NAME", "db_user": "DB_USERNAME", "db_password": "DB_PASSWORD",
                 "redis_host": "REDIS_HOST", "redis_port": "REDIS_PORT", "redis_password": "REDIS_PASSWORD",
                 "rabbit_host": "RABBITMQ_HOST", "rabbit_port": "RABBITMQ_PORT",
                 "rabbit_user": "RABBITMQ_USERNAME", "rabbit_password": "RABBITMQ_PASSWORD",
                 "exchange": "EVENT_EXCHANGE", "queue": "EVENT_QUEUE",
                 "product_url": "PRODUCT_SERVICE_URL", "internal_key": "INTERNAL_API_KEY",
                 "jwt_secret": "AUTH_JWT_SECRET", "cors": "CORS_ALLOWED_ORIGINS",
                 "timeout": "RECOMMENDATION_DEPENDENCY_TIMEOUT_SECONDS"}
        values = {key: env[name] for key, name in names.items() if env.get(name) is not None}
        for key in ("db_port", "redis_port", "rabbit_port"):
            if key in values:
                values[key] = int(values[key])
        if "timeout" in values:
            values["timeout"] = float(values["timeout"])
        return cls(**values)

    def __post_init__(self):
        if self.app_env not in {"dev", "prod"}:
            raise ValueError("APP_ENV must be dev or prod")
        if any(not 1 <= port <= 65535 for port in (self.db_port, self.redis_port, self.rabbit_port)):
            raise ValueError("Invalid dependency port")
        if not math.isfinite(self.timeout) or not 0 < self.timeout <= 10:
            raise ValueError("Dependency timeout must be in (0, 10] seconds")
        url = urlsplit(self.product_url)
        if (url.scheme not in {"http", "https"} or not url.hostname or url.username
                or url.password or url.query or url.fragment):
            raise ValueError("Invalid PRODUCT_SERVICE_URL")
        for value in (self.db_host, self.db_name, self.db_user, self.rabbit_host, self.queue, self.exchange):
            if not value or any(ord(c) < 32 for c in value):
                raise ValueError("Invalid dependency configuration")
        if self.app_env == "prod":
            secrets = (self.db_password, self.rabbit_user, self.rabbit_password, self.internal_key, self.jwt_secret)
            if any(not v or v in {"guest", "vmarket"} or v.startswith(("dev-", "replace-")) for v in secrets):
                raise ValueError("Production requires real credentials")

    def require_secrets(self):
        if len(self.jwt_secret.encode("utf-8")) < 32 or not self.internal_key:
            raise ValueError("Set AUTH_JWT_SECRET (>=32 bytes) and INTERNAL_API_KEY")
