import apiClient from './apiClient'

// AI Chatbot Service (PBL6-18). POST /messages cong khai o gateway: khach hoi dap chung, nguoi da
// dang nhap duoc tra cuu don cua chinh minh va luu lich su. Danh tinh chi lay tu access token
// (apiClient tu gan khi co phien); service khong doc userId tu body.
const chatUrl = '/api/ai/chat'
// RAG + LLM co the cham hon API thuong; van du ngan de bao loi khi bot khong phan hoi.
const CHAT_TIMEOUT = 30_000

export const CHAT_LIMITS = Object.freeze({ message: 1000 })

/**
 * {conversationId, messageId, reply, intent, sources[], handoff:{suggested, ticketId}, loginRequired}.
 * Khach: conversationId luon null (khong luu lich su).
 */
export const sendChatMessage = async ({ message, conversationId = null }) =>
  (
    await apiClient.post(
      `${chatUrl}/messages`,
      { message: message.trim(), conversationId: conversationId || null },
      { timeout: CHAT_TIMEOUT },
    )
  ).data

/** Hoi thoai cua toi, cap nhat gan nhat truoc (toi da 50). */
export const listConversations = async () =>
  (await apiClient.get(`${chatUrl}/conversations`)).data.conversations || []

/** Trang moi nhat truoc, trong trang cu -> moi; `before` = nextCursor de doc tiep ve truoc. */
export const listConversationMessages = async (conversationId, { before, limit = 100 } = {}) =>
  (
    await apiClient.get(`${chatUrl}/conversations/${encodeURIComponent(conversationId)}/messages`, {
      params: before ? { before, limit } : { limit },
    })
  ).data

/** Phieu ho tro do chatbot chuyen tiep (nguoi mua chi thay phieu cua minh). */
export const listMyTickets = async () =>
  (await apiClient.get(`${chatUrl}/tickets`)).data.tickets || []

// Cau kich hoat y dinh "gap nguoi ho tro" cua chatbot (chat.py HANDOFF): tao phieu cho nguoi da
// dang nhap, voi khach thi huong dan lien he.
export const HANDOFF_MESSAGE = 'Tôi muốn gặp nhân viên hỗ trợ'
