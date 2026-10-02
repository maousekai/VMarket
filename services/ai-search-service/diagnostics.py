"""Useful failure context without logging URLs, credentials or request payloads."""
import logging
import traceback
from pathlib import Path


def log_failure(operation, error):
    origin = traceback.extract_tb(error.__traceback__)[-1] if error.__traceback__ else None
    response = getattr(error, "response", None)
    status = getattr(error, "status", None) or getattr(error, "status_code", None) or getattr(response, "status_code", None)
    if isinstance(response, dict):
        status = response.get("ResponseMetadata", {}).get("HTTPStatusCode")
    logging.warning("%s exception=%s status=%s origin=%s:%s function=%s", operation,
                    type(error).__name__, status, Path(origin.filename).name if origin else None,
                    origin.lineno if origin else None, origin.name if origin else None)
