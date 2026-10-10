"""One chat turn: decide the intent, answer inside the knowledge base, keep the caller's history."""
import asyncio
import re
import uuid

from diagnostics import log_failure
from knowledge import normalize
from orders import order_intent
from schemas import ChatError

HANDOFF = re.compile(r"nhan vien|tu van vien|ho tro vien|nguoi that|gap nguoi|noi chuyen voi nguoi|tao phieu"
                     r"|phieu ho tro|gap admin|cskh|cham soc khach hang|ho tro truc tiep")
GREETING = frozenset("xin chao hello hi hey alo ban shop ad admin oi a em anh chi bot vmarket".split())
THANKS = re.compile(r"\b(cam on|thanks|thank you)\b")
SELLER_HINT = "liên hệ người bán qua trang gian hàng"


def unavailable():
    return ChatError(503, "CHAT_UNAVAILABLE", "Trợ lý đang tạm gián đoạn, bạn vui lòng thử lại sau ít phút.")


def small_talk(message):
    words = normalize(message)
    if len(words) <= 5 and set(words) <= GREETING and set(words) & {"chao", "hello", "hi", "hey", "alo"}:
        return ("Xin chào! Mình là trợ lý VMarket. Mình có thể giải đáp về sản phẩm, chính sách mua hàng – đổi trả "
                "và tra cứu đơn hàng của bạn.")
    if len(words) <= 6 and THANKS.search(" ".join(words)):
        return "Rất vui được hỗ trợ bạn. Bạn cần gì thêm cứ nhắn mình nhé!"
    return None


def source_of(chunk):
    return {"id": chunk["_id"], "title": chunk["title"], "source": chunk["source"], "docId": chunk["docId"]}


class ChatService:
    def __init__(self, settings, store, index, answerer, orders):
        self.settings, self.store, self.index, self.answerer, self.orders = settings, store, index, answerer, orders

    async def call(self, function, *args):
        try:
            return await asyncio.to_thread(function, *args)
        except Exception as error:
            log_failure("chat_dependency_failed", error)
            raise unavailable() from None

    async def reply(self, identity, request):
        conversation_id, created = request.conversationId, False
        if conversation_id is not None:
            # A guest, or someone else's conversation id, looks exactly like an unknown id.
            if identity is None or not await self.call(self.store.conversation, identity.user_id, conversation_id):
                raise ChatError(404, "CONVERSATION_NOT_FOUND", "Không tìm thấy hội thoại")
        elif identity is not None:
            conversation_id, created = uuid.uuid4().hex, True
        answer = await self.answer(identity, request.message, conversation_id, created)
        message_id = None
        if identity is not None:
            try:
                _, message_id = await asyncio.to_thread(self.store.append_exchange, identity.user_id, conversation_id,
                                                        request.message, answer, created)
            except Exception as error:
                # The answer is already computed; losing one history entry beats failing the turn.
                log_failure("chat_history_failed", error)
                conversation_id = None if created else conversation_id
        return {"conversationId": conversation_id, "messageId": message_id, **answer}

    async def answer(self, identity, message, conversation_id, created):
        if HANDOFF.search(" ".join(normalize(message))):
            return await self.handoff(identity, message, conversation_id)
        asks_order, order_id = order_intent(message)
        if asks_order:
            if identity is None:
                return result("order_lookup", "Bạn vui lòng đăng nhập để mình tra cứu đơn hàng của bạn nhé.",
                              login_required=True)
            return result("order_lookup", await self.orders.answer(identity, order_id))
        greeting = small_talk(message)
        if greeting:
            return result("small_talk", greeting)
        hits = await self.call(self.index.search, message)
        if not hits:
            return self.refusal()
        if not self.settings.llm_configured:
            # No LLM configured: quote the best matching chunk instead of generating.
            return result("answer", hits[0][0]["text"], sources=[source_of(hits[0][0])])
        history = [] if created or identity is None else await self.history(identity.user_id, conversation_id)
        try:
            reply = await self.answerer.answer(message, hits, history)
        except Exception as error:
            log_failure("llm_answer_failed", error)
            raise unavailable() from None
        if reply is None:
            return self.refusal()
        return result("answer", reply, sources=[source_of(chunk) for chunk, _ in hits])

    async def history(self, user_id, conversation_id):
        """Earlier answered turns as context. Order lookups stay out: they never reach the LLM provider."""
        turns = self.settings.history_turns
        if not turns:
            return []
        messages = await self.call(self.store.recent_messages, user_id, conversation_id, turns * 4)
        # Pair by turnId, never by neighbouring rows: concurrent turns can be stored side by side.
        by_turn = {}
        for message in messages:
            if message.get("turnId"):
                by_turn.setdefault(message["turnId"], {})[message["role"]] = message
        pairs = [(turn["user"], turn["assistant"]) for turn in by_turn.values()
                 if "user" in turn and turn.get("assistant", {}).get("intent") == "answer"]
        return [m for pair in pairs[-turns:] for m in pair]

    def refusal(self):
        return result("out_of_scope",
                      "Xin lỗi, mình chỉ hỗ trợ các câu hỏi về sản phẩm, chính sách mua hàng – đổi trả và đơn hàng "
                      f"trên VMarket nên chưa trả lời được câu này. Nếu cần hỗ trợ thêm, bạn có thể {SELLER_HINT} "
                      f"hoặc gửi yêu cầu tới quản trị viên qua {self.settings.support_contact}.", handoff=True)

    async def handoff(self, identity, message, conversation_id):
        """FR-BOT-03: a signed-in user gets a support ticket; a guest gets contact guidance."""
        contact = self.settings.support_contact
        if identity is None:
            return result("handoff", f"Bạn vui lòng đăng nhập để mình tạo phiếu hỗ trợ. Bạn cũng có thể {SELLER_HINT} "
                                     f"hoặc liên hệ quản trị viên qua {contact}.", handoff=True, login_required=True)
        ticket = await self.call(self.store.create_ticket, identity.user_id, conversation_id, message)
        return result("handoff", f"Mình đã tạo phiếu hỗ trợ #{ticket['_id']} để quản trị viên VMarket xem xét. "
                                 f"Với vấn đề về một sản phẩm hoặc đơn hàng cụ thể, bạn cũng có thể {SELLER_HINT}.",
                      handoff=True, ticket_id=ticket["_id"])


def result(intent, reply, sources=(), handoff=False, ticket_id=None, login_required=False):
    return {"reply": reply, "intent": intent, "sources": list(sources),
            "handoff": {"suggested": handoff, "ticketId": ticket_id}, "loginRequired": login_required}
