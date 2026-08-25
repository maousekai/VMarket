from fastapi import FastAPI

app = FastAPI(
    title="VMarket Recommendation Service",
    description="Goi y san pham ca nhan hoa va san pham tuong tu",
    version="0.1.0",
)


@app.get("/api/ai/recommendations/health")
def health():
    return {"status": "UP", "service": "recommendation-service"}


@app.get("/api/ai/recommendations/for-you")
def for_you(user_id: str = ""):
    # TODO (PBL6): goi y ca nhan hoa - FR-REC-01..04
    return {"user_id": user_id, "items": []}
