FROM python:3.12-slim
WORKDIR /app

COPY services/recommendation-service/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY services/recommendation-service/*.py ./
RUN useradd --uid 10001 --create-home recommendation
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
USER recommendation

EXPOSE 8101
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8101", "--workers", "1", "--no-access-log"]
