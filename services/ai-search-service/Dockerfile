FROM python:3.12-slim@sha256:f77ac9e44ae96ef2c90b8053ea08c31f8be030f824196b0ae4db6d462c84e51f
RUN apt-get update && apt-get upgrade -y && rm -rf /var/lib/apt/lists/*
WORKDIR /app

COPY services/ai-search-service/requirements*.txt ./
RUN pip install --no-cache-dir -r requirements-local.txt

COPY services/ai-search-service/*.py ./
RUN useradd --uid 10001 --create-home search && mkdir -p /app/models /app/tmp && chown -R search:search /app
ENV TMPDIR=/app/tmp OMP_NUM_THREADS=1 MKL_NUM_THREADS=1 PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
USER search

EXPOSE 8100
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8100", "--workers", "1", "--no-access-log"]
