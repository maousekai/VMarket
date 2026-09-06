from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

app = FastAPI(
    title="VMarket Recommendation Service",
    description="Goi y san pham ca nhan hoa va san pham tuong tu",
    version="0.1.0",
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


@app.get("/api/ai/recommendations/health")
def health():
    return {"status": "UP", "service": "recommendation-service"}


@app.get("/api/ai/recommendations/for-you")
def for_you(user_id: str = ""):
    # TODO (PBL6): goi y ca nhan hoa - FR-REC-01..04
    return {"user_id": user_id, "items": []}
