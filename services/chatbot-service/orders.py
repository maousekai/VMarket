"""FR-BOT-02: order status for the person asking, and nobody else.

The chatbot never tells order-service whose orders to read. It forwards the caller's own access
token, and order-service derives the owner from that token (`/api/orders` is "my orders" only).
An order id that belongs to someone else therefore answers 404, exactly like one that does not
exist. Order data is formatted here and never sent to the LLM provider.
"""
import re
from datetime import datetime, timedelta, timezone

import httpx

from diagnostics import log_failure
from knowledge import money, normalize
from schemas import ChatError

ORDER_ID = re.compile(r"(?<![0-9A-Za-z])[0-9A-HJKMNP-TV-Za-hjkmnp-tv-z]{26}(?![0-9A-Za-z])")  # ULID
ORDER_WORD = re.compile(r"\b(don(?! gian)|kien hang|order)\b")
STATUS_CUE = re.compile(
    r"cua (toi|minh|em|anh|chi|tui)|(toi|minh|em|tui) (da |vua |moi )?(dat|mua)|trang thai|tinh trang|toi dau|den dau"
    r"|o dau|tra cuu|kiem tra|theo doi|giao chua|da giao|chua (nhan|toi|den|giao)|(bao gio|khi nao) (toi|den|giao|nhan)"
    r"|xem don|don nao")
HOW_TO = re.compile(r"lam sao|lam the nao|cach |huong dan|chinh sach|quy dinh|dieu kien")
STATUS_LABELS = {"PENDING": "Chờ xác nhận", "PROCESSING": "Đang chuẩn bị hàng", "SHIPPED": "Đang giao",
                 "DELIVERED": "Đã giao", "CANCELLED": "Đã hủy"}
VIETNAM = timezone(timedelta(hours=7))
MAX_LISTED = 5


def order_intent(message):
    """Return (is_order_question, order_id). Deterministic so no prompt can steer the lookup."""
    match = ORDER_ID.search(message)
    text = " ".join(normalize(message)) + " "
    named, cue = ORDER_WORD.search(text), STATUS_CUE.search(text)
    if match and (named or cue):
        return True, match.group().upper()
    return bool(named and cue) and not HOW_TO.search(text), None


def describe(order):
    """Status, time, size and total only: the delivery address and phone are not repeated in chat."""
    status = STATUS_LABELS.get(order["status"], order["status"])
    parts = [f"Đơn {order['id']}: {status}"]
    created = order.get("createdAt")
    if isinstance(created, str):
        try:
            moment = datetime.fromisoformat(created.replace("Z", "+00:00")).astimezone(VIETNAM)
            parts.append(f"đặt lúc {moment:%H:%M %d/%m/%Y}")
        except ValueError:
            pass
    items = order.get("items")
    if isinstance(items, list) and items:
        parts.append(f"{sum(i.get('quantity', 0) for i in items)} sản phẩm")
    if isinstance(order.get("totalAmount"), (int, float)):
        parts.append(f"tổng {money(order['totalAmount'])}")
    return ", ".join(parts) + "."


def valid(order):
    return isinstance(order, dict) and isinstance(order.get("id"), str) and isinstance(order.get("status"), str)


class OrderClient:
    def __init__(self, settings, transport=None):
        self.url = settings.order_url.rstrip("/") + "/api/orders"
        self.client = httpx.AsyncClient(trust_env=False, follow_redirects=False, transport=transport,
                                        timeout=settings.order_timeout)

    async def answer(self, identity, order_id):
        try:
            response = await self.client.get(self.url + ("/" + order_id if order_id else ""),
                                             headers={"Authorization": identity.authorization})
            if response.status_code == 401:
                raise ChatError(401, "UNAUTHORIZED", "Phiên đăng nhập đã hết hạn, vui lòng đăng nhập lại")
            if order_id and response.status_code == 404:
                return (f"Mình không tìm thấy đơn {order_id} trong tài khoản của bạn. "
                        "Bạn kiểm tra lại mã đơn trong mục Đơn hàng của tôi nhé.")
            response.raise_for_status()
            data = response.json()
            if order_id:
                if not valid(data) or data["id"].upper() != order_id:
                    raise ValueError("Unexpected order response")
                return describe(data)
            if not isinstance(data, list) or not all(valid(order) for order in data):
                raise ValueError("Unexpected order list")
        except ChatError:
            raise
        except (httpx.HTTPError, ValueError, KeyError, TypeError) as error:
            log_failure("order_lookup_failed", error)
            return "Hiện mình chưa tra cứu được đơn hàng. Bạn vui lòng thử lại sau ít phút hoặc xem trong mục Đơn hàng của tôi."
        if not data:
            return "Tài khoản của bạn chưa có đơn hàng nào."
        lines = [describe(order) for order in data[:MAX_LISTED]]
        head = (f"Bạn có {len(data)} đơn hàng, đây là {MAX_LISTED} đơn gần nhất:" if len(data) > MAX_LISTED
                else "Đơn hàng của bạn:")
        return "\n".join([head] + ["- " + line for line in lines]
                         + ["Gửi kèm mã đơn nếu bạn muốn xem riêng một đơn."])
