from fastapi import FastAPI

app = FastAPI(
    title="VMarket AI Search Service",
    description="Tim kiem thong minh: full-text tieng Viet, tim kiem bang hinh anh (CNN), dong bo chi muc Elasticsearch",
    version="0.1.0",
)


@app.get("/api/ai/search/health")
def health():
    return {"status": "UP", "service": "ai-search-service"}


@app.get("/api/ai/search")
def search(keyword: str = ""):
    # TODO (PBL6): tim kiem tren Elasticsearch - FR-SRCH-01..03
    return {"keyword": keyword, "results": []}
