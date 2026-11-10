import { sendChatMessage } from '@/api/chat'

/** The chatbot opens a ticket only when the message matches its handoff intent (see the service). */
export const SUPPORT_PREFIX = 'Yêu cầu hỗ trợ trực tiếp: '
export const SUPPORT_MAX_LENGTH = 1000 - SUPPORT_PREFIX.length

/** Creates (or re-finds) the open support ticket through the chat message path. */
export async function requestSupport(content: string, conversationId: string | null) {
  const reply = await sendChatMessage(`${SUPPORT_PREFIX}${content.trim()}`, conversationId)
  return reply.handoff.ticketId
}
