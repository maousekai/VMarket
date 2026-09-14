from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from event_consumer import start_event_consumer_thread


@asynccontextmanager
async def lifespan(app: FastAPI):
    # PBL6-39: bật consumer RabbitMQ (ProductCreated...) trong luồng nền.
    start_event_consumer_thread()
    yield


app = FastAPI(
    title="VMarket AI Search Service",
    description="Tim kiem thong minh: full-text tieng Viet, tim kiem bang hinh anh (CNN), dong bo chi muc Elasticsearch",
    version="0.1.0",
    lifespan=lifespan,
)

# CORS cho response that (preflight OPTIONS da duoc api-gateway tra loi).
# Danh sach origin phai dong bo voi CORS_ALLOWED_ORIGINS trong .env goc.
app.add_middleware(
    CORSMiddleware,
    allow_origins=[
        "http://localhost:5173",
        "http://localhost:5174",
        "http://localhost:3000",
    ],
    allow_credentials=True,
    allow_methods=["GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"],
    allow_headers=["*"],
)


@app.get("/api/ai/search/health")
def health():
    return {"status": "UP", "service": "ai-search-service"}


@app.get("/api/ai/search")
def search(keyword: str = ""):
    # TODO (PBL6): tim kiem tren Elasticsearch - FR-SRCH-01..03
    return {"keyword": keyword, "results": []}