from datetime import datetime, timezone

from pydantic import BaseModel, ConfigDict, Field, field_validator
from starlette.responses import JSONResponse

MAX_MESSAGE = 1000
CONVERSATION_ID = r"^[0-9a-f]{32}$"


class ChatError(Exception):
    def __init__(self, status, code, message):
        self.status, self.code, self.message = status, code, message


def now():
    return datetime.now(timezone.utc)


def iso(value):
    # MongoDB returns naive UTC datetimes; the API always answers UTC ISO 8601.
    return value.replace(tzinfo=timezone.utc).isoformat().replace("+00:00", "Z")


def error_response(error, path):
    return JSONResponse({"timestamp": iso(now()), "status": error.status,
                         "error": {"code": error.code, "message": error.message}, "path": path},
                        status_code=error.status)


class ChatRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    message: str = Field(min_length=1, max_length=MAX_MESSAGE)
    conversationId: str | None = Field(default=None, pattern=CONVERSATION_ID)

    @field_validator("message")
    @classmethod
    def printable(cls, value):
        value = value.strip()
        if not value or any(ord(c) < 32 and c not in "\n\t" for c in value):
            raise ValueError("Invalid message")
        return value


def public_message(message):
    return {"id": message["_id"], "role": message["role"], "content": message["content"],
            "intent": message.get("intent"), "sources": message.get("sources", []),
            "createdAt": iso(message["createdAt"])}


def public_conversation(conversation):
    return {"id": conversation["_id"], "title": conversation["title"],
            "messageCount": conversation["messageCount"], "createdAt": iso(conversation["createdAt"]),
            "updatedAt": iso(conversation["updatedAt"])}


def public_ticket(ticket, admin=False):
    result = {"id": ticket["_id"], "status": ticket["status"], "question": ticket["question"],
              "conversationId": ticket.get("conversationId"), "createdAt": iso(ticket["createdAt"])}
    if admin:
        result["userId"] = ticket["userId"]
    return result
