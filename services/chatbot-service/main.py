import asyncio
import logging
import re
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from starlette.exceptions import HTTPException

from auth import identify, require
from chat import ChatService, unavailable
from diagnostics import log_failure
from knowledge import KnowledgeIndex, create_embedder
from llm import Answerer
from manage import ingest_faq
from orders import OrderClient
from schemas import (CONVERSATION_ID, ChatError, ChatRequest, error_response, public_conversation, public_message,
                     public_ticket)
from settings import Settings
from store import ChatStore

logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
logging.getLogger("httpx").setLevel(logging.WARNING)

BASE = "/api/ai/chat"
CONVERSATION = re.compile(CONVERSATION_ID)


def create_app(settings=None, store=None, embedder=None, answerer=None, orders=None):
    settings = settings or Settings.load()
    store = store or ChatStore(settings)
    embedder = embedder or create_embedder(settings)
    answerer = answerer or Answerer(settings)
    orders = orders or OrderClient(settings)
    index = KnowledgeIndex(store, embedder, settings)
    service = ChatService(settings, store, index, answerer, orders)

    @asynccontextmanager
    async def lifespan(app):
        try:
            # MongoDB may start after this service; indexes and chunks are retried on first use.
            await asyncio.to_thread(store.ensure_indexes)
            if not await asyncio.to_thread(index.load):
                # First start: load the bundled FAQ so the assistant is not empty out of the box.
                await asyncio.to_thread(ingest_faq, store, embedder)
                await asyncio.to_thread(index.load)
        except Exception as error:
            log_failure("chat_startup_degraded", error)
        yield
        await answerer.client.aclose()
        await orders.client.aclose()
        embedder.close()
        store.close()

    app = FastAPI(title="VMarket AI Chatbot", version="1.0.0", lifespan=lifespan)
    app.state.index = index
    app.add_middleware(CORSMiddleware, allow_origins=[s.strip() for s in settings.cors.split(",") if s.strip()],
                       allow_credentials=True, allow_methods=["GET", "POST"],
                       allow_headers=["Content-Type", "Authorization"])

    @app.exception_handler(ChatError)
    async def chat_error(request, error):
        return error_response(error, request.url.path)

    @app.exception_handler(RequestValidationError)
    async def invalid_request(request, error):
        return error_response(ChatError(400, "INVALID_REQUEST", "Yêu cầu không hợp lệ"), request.url.path)

    @app.exception_handler(HTTPException)
    async def http_error(request, error):
        return error_response(ChatError(error.status_code, "NOT_FOUND" if error.status_code == 404 else "HTTP_ERROR",
                                        str(error.detail)), request.url.path)

    def identity(request):
        return identify(request.headers.get("authorization"), settings.jwt_secret)

    async def owned(function, *args):
        try:
            return await asyncio.to_thread(function, *args)
        except Exception as error:
            log_failure("chat_store_failed", error)
            raise unavailable() from None

    @app.post(BASE + "/messages")
    async def send_message(request: Request, body: ChatRequest):
        return await service.reply(identity(request), body)

    @app.get(BASE + "/conversations")
    async def conversations(request: Request):
        user = require(identity(request))
        return {"conversations": [public_conversation(c) for c in await owned(store.list_conversations, user.user_id)]}

    @app.get(BASE + "/conversations/{conversation_id}/messages")
    async def messages(request: Request, conversation_id: str):
        user = require(identity(request))
        if not CONVERSATION.fullmatch(conversation_id) or not await owned(store.conversation, user.user_id, conversation_id):
            raise ChatError(404, "CONVERSATION_NOT_FOUND", "Không tìm thấy hội thoại")
        found = await owned(store.list_messages, user.user_id, conversation_id)
        return {"conversationId": conversation_id, "messages": [public_message(m) for m in found]}

    @app.get(BASE + "/tickets")
    async def tickets(request: Request):
        """A buyer sees their own tickets; an ADMIN sees every ticket to follow up."""
        user = require(identity(request))
        found = await owned(store.list_tickets, None if user.admin else user.user_id)
        return {"tickets": [public_ticket(t, user.admin) for t in found]}

    @app.get(BASE + "/health")
    async def health():
        database = "ready"
        try:
            await asyncio.to_thread(store.ping)
        except Exception as error:
            log_failure("chat_health_degraded", error)
            database = "unavailable"
        return {"status": "UP", "service": "chatbot-service", "database": database,
                "knowledge": "ready" if index.size else "empty", "chunks": index.size,
                "embedding": embedder.fingerprint,
                "llm": "configured" if settings.llm_configured else "extractive"}

    return app


app = create_app()
