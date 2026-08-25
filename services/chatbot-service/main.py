from fastapi import FastAPI

app = FastAPI(
    title="VMarket AI Chatbot Service",
    description="Tro ly ho tro khach hang bang RAG, tra cuu don hang",
    version="0.1.0",
)


@app.get("/api/ai/chat/health")
def health():
    return {"status": "UP", "service": "chatbot-service"}


@app.post("/api/ai/chat/messages")
def send_message(message: dict | None = None):
    # TODO (PBL6): RAG voi kho tri thuc - FR-BOT-01..04
    return {"reply": "Chatbot chua duoc trien khai trong pha setup moi truong."}
