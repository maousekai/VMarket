from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

app = FastAPI(
    title="VMarket AI Chatbot Service",
    description="Tro ly ho tro khach hang bang RAG, tra cuu don hang",
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


@app.get("/api/ai/chat/health")
def health():
    return {"status": "UP", "service": "chatbot-service"}


@app.post("/api/ai/chat/messages")
def send_message(message: dict | None = None):
    # TODO (PBL6): RAG voi kho tri thuc - FR-BOT-01..04
    return {"reply": "Chatbot chua duoc trien khai trong pha setup moi truong."}
