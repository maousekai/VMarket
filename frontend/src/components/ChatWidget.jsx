import { useCallback, useEffect, useId, useLayoutEffect, useRef, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { useAuth } from '../auth/useAuth'
import { getApiError } from '../services/api/apiClient'
import {
  CHAT_LIMITS,
  HANDOFF_MESSAGE,
  listConversationMessages,
  listConversations,
  listMyTickets,
  sendChatMessage,
} from '../services/api/chatbotApi'
import { formatDateTime } from '../utils/formatDate'
import './chatbot.css'

const SUGGESTIONS = [
  'Chính sách đổi trả như thế nào?',
  'Đơn hàng của tôi đang ở đâu?',
  'VMarket hỗ trợ những phương thức thanh toán nào?',
]
const WELCOME =
  'Xin chào! Mình là trợ lý VMarket. Mình có thể giải đáp về sản phẩm, chính sách mua hàng – đổi trả và tra cứu đơn hàng của bạn.'

// Hoi thoai dang mo cua nguoi da dang nhap: mo lai trang van tiep tuc dung hoi thoai do.
const storageKey = (userId) => `vmarket.chat.${userId}`
function readStored(userId) {
  try {
    return userId ? sessionStorage.getItem(storageKey(userId)) : null
  } catch {
    return null
  }
}
function writeStored(userId, conversationId) {
  try {
    if (!userId) return
    if (conversationId) sessionStorage.setItem(storageKey(userId), conversationId)
    else sessionStorage.removeItem(storageKey(userId))
  } catch {
    // Trinh duyet chan storage: chi mat kha nang mo lai hoi thoai sau khi tai trang.
  }
}

const fromServer = (message) => ({
  id: message.id,
  role: message.role,
  content: message.content,
  intent: message.intent,
  sources: message.sources || [],
})

function Sources({ sources }) {
  if (!sources?.length) return null
  return (
    <div className="chat-sources">
      <strong>Nguồn tham khảo</strong>
      <ul>
        {sources.map((source) => (
          <li key={source.id}>
            {source.source === 'catalog' ? (
              <Link to={`/products/${encodeURIComponent(source.docId)}`}>{source.title}</Link>
            ) : (
              source.title
            )}
          </li>
        ))}
      </ul>
    </div>
  )
}

function AssistantExtras({ message, signedIn, returnTo, onHandoff, busy }) {
  return (
    <>
      <Sources sources={message.sources} />
      {message.intent === 'order_lookup' && signedIn && !message.loginRequired && (
        <Link className="chat-link" to="/profile/orders">
          Xem đơn hàng của tôi
        </Link>
      )}
      {message.loginRequired && (
        <Link className="chat-link" to="/login" state={{ returnTo }}>
          Đăng nhập
        </Link>
      )}
      {message.handoff?.ticketId && (
        <p className="chat-ticket">Mã phiếu hỗ trợ: #{message.handoff.ticketId}</p>
      )}
      {message.handoff?.suggested && !message.handoff.ticketId && signedIn && (
        <button type="button" className="chat-link" disabled={busy} onClick={onHandoff}>
          Gửi yêu cầu tới bộ phận hỗ trợ
        </button>
      )}
    </>
  )
}

function History({ onOpen, onNew, onBack }) {
  const [state, setState] = useState({ status: 'loading', conversations: [], tickets: [] })
  useEffect(() => {
    let current = true
    Promise.all([listConversations(), listMyTickets().catch(() => [])])
      .then(
        ([conversations, tickets]) =>
          current && setState({ status: 'ready', conversations, tickets, error: '' }),
      )
      .catch(
        (failure) =>
          current &&
          setState({
            status: 'error',
            conversations: [],
            tickets: [],
            error: getApiError(failure).message,
          }),
      )
    return () => {
      current = false
    }
  }, [])

  return (
    <div className="chat-history">
      <div className="chat-history__head">
        <button type="button" className="chat-text-button" onClick={onBack}>
          ‹ Quay lại
        </button>
        <strong>Lịch sử hội thoại</strong>
      </div>
      <button type="button" className="chat-primary" onClick={onNew}>
        Bắt đầu hội thoại mới
      </button>
      {state.status === 'loading' && <p role="status">Đang tải…</p>}
      {state.status === 'error' && <p role="alert">{state.error}</p>}
      {state.status === 'ready' && state.conversations.length === 0 && (
        <p className="chat-muted">Bạn chưa có cuộc trò chuyện nào.</p>
      )}
      {state.conversations.length > 0 && (
        <ul className="chat-history__list" aria-label="Các cuộc trò chuyện">
          {state.conversations.map((conversation) => (
            <li key={conversation.id}>
              <button type="button" onClick={() => onOpen(conversation.id)}>
                <strong>{conversation.title}</strong>
                <small>
                  {formatDateTime(conversation.updatedAt)} · {conversation.messageCount} tin nhắn
                </small>
              </button>
            </li>
          ))}
        </ul>
      )}
      {state.tickets.length > 0 && (
        <section aria-labelledby="chat-tickets">
          <strong id="chat-tickets">Phiếu hỗ trợ của tôi</strong>
          <ul className="chat-history__tickets">
            {state.tickets.map((ticket) => (
              <li key={ticket.id}>
                <span>#{ticket.id}</span>
                <small>
                  {ticket.status === 'OPEN' ? 'Đang chờ xử lý' : 'Trùng lặp'} ·{' '}
                  {formatDateTime(ticket.createdAt)}
                </small>
                <p>{ticket.question}</p>
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  )
}

/**
 * Tro ly VMarket (PBL6-31, FR-BOT-01–04): nut noi o goc man hinh mo khung chat hoi dap RAG, tra
 * cuu don (can dang nhap, chi don cua chinh nguoi hoi), lich su hoi thoai va chuyen tiep ho tro.
 */
export default function ChatWidget() {
  const { session } = useAuth()
  const location = useLocation()
  const userId = session?.userId || null
  const panelId = useId()
  const inputRef = useRef(null)
  const listRef = useRef(null)
  const [open, setOpen] = useState(false)
  const [view, setView] = useState('chat')
  const [owner, setOwner] = useState(userId)
  const [conversationId, setConversationId] = useState(() => readStored(userId))
  const [messages, setMessages] = useState([])
  const [loadedFor, setLoadedFor] = useState(null)
  const [input, setInput] = useState('')
  const [sending, setSending] = useState(false)
  const [failed, setFailed] = useState(null)
  const returnTo = `${location.pathname}${location.search}`
  // Phien dang hien thi. Moi yeu cau ghi nho phien da tao ra no; phan hoi ve sau khi phien doi
  // (dang xuat / doi tai khoan o tab khac) bi bo, khong hien hay luu vao phien moi.
  const sessionRef = useRef(userId)
  const isStale = (startedFor) => sessionRef.current !== startedFor

  // Doi tai khoan / dang xuat: khong de lo hoi thoai cua nguoi truoc.
  if (owner !== userId) {
    setOwner(userId)
    setConversationId(readStored(userId))
    setMessages([])
    setLoadedFor(null)
    setFailed(null)
    setSending(false)
    setView('chat')
  }

  // Layout effect: cap nhat ngay khi commit, truoc khi phan hoi nao cua phien cu kip chay tiep.
  useLayoutEffect(() => {
    sessionRef.current = userId
  }, [userId])

  const openConversation = useCallback(async (id) => {
    const startedFor = sessionRef.current
    setView('chat')
    setFailed(null)
    setLoadedFor(id)
    try {
      const page = await listConversationMessages(id)
      if (sessionRef.current !== startedFor) return
      setConversationId(id)
      setMessages((page.messages || []).map(fromServer))
    } catch (failure) {
      if (sessionRef.current !== startedFor) return
      // Hoi thoai khong con (hoac cua nguoi khac): bat dau hoi thoai moi.
      if (getApiError(failure).status === 404) {
        setConversationId(null)
        setMessages([])
      } else {
        setFailed({ message: getApiError(failure).message })
      }
    }
  }, [])

  useEffect(() => {
    writeStored(userId, conversationId)
  }, [userId, conversationId])

  // Mo khung chat lan dau: nap lai hoi thoai dang do cua nguoi da dang nhap.
  useEffect(() => {
    if (open && userId && conversationId && loadedFor !== conversationId) {
      openConversation(conversationId)
    }
  }, [open, userId, conversationId, loadedFor, openConversation])

  useEffect(() => {
    if (open && view === 'chat') inputRef.current?.focus()
  }, [open, view])

  useEffect(() => {
    const list = listRef.current
    if (list) list.scrollTop = list.scrollHeight
  }, [messages, sending, failed])

  async function send(text) {
    const message = text.trim()
    if (!message || sending) return
    const startedFor = sessionRef.current
    setSending(true)
    setFailed(null)
    setInput('')
    const pendingId = `local-${Date.now()}`
    setMessages((current) => [...current, { id: pendingId, role: 'user', content: message }])
    try {
      const reply = await sendChatMessage({ message, conversationId })
      if (isStale(startedFor)) return
      if (reply.conversationId) {
        setConversationId(reply.conversationId)
        setLoadedFor(reply.conversationId)
      }
      setMessages((current) => [
        ...current,
        {
          id: reply.messageId || `reply-${pendingId}`,
          role: 'assistant',
          content: reply.reply,
          intent: reply.intent,
          sources: reply.sources || [],
          handoff: reply.handoff,
          loginRequired: reply.loginRequired,
        },
      ])
    } catch (failure) {
      if (isStale(startedFor)) return
      const problem = getApiError(failure)
      if (problem.code === 'CONVERSATION_NOT_FOUND') {
        setConversationId(null)
        setLoadedFor(null)
      }
      // Bo tin vua gui khoi khung de "Thu lai" khong hien hai lan.
      setMessages((current) => current.filter((item) => item.id !== pendingId))
      setFailed({
        message:
          problem.status === 503 || problem.status === 0 || problem.code === 'ECONNABORTED'
            ? 'Trợ lý đang bận hoặc tạm thời không phản hồi. Vui lòng thử lại sau ít phút.'
            : problem.message,
        retry: message,
      })
    } finally {
      if (!isStale(startedFor)) setSending(false)
    }
  }

  function startNew() {
    setConversationId(null)
    setLoadedFor(null)
    setMessages([])
    setFailed(null)
    setView('chat')
  }

  if (!open) {
    return (
      <button
        type="button"
        className="chat-launcher"
        aria-expanded="false"
        aria-controls={panelId}
        onClick={() => setOpen(true)}
      >
        <span aria-hidden="true">💬</span>
        Trợ lý
      </button>
    )
  }

  return (
    <section id={panelId} className="chat-panel" role="dialog" aria-label="Trợ lý VMarket">
      <header className="chat-panel__head">
        <div>
          <strong>Trợ lý VMarket</strong>
          <small>
            {userId ? 'Đã đăng nhập · lưu lịch sử hội thoại' : 'Khách · không lưu lịch sử'}
          </small>
        </div>
        <div className="chat-panel__tools">
          {userId && view === 'chat' && (
            <button type="button" className="chat-text-button" onClick={() => setView('history')}>
              Lịch sử
            </button>
          )}
          {view === 'chat' && messages.length > 0 && (
            <button type="button" className="chat-text-button" onClick={startNew}>
              Mới
            </button>
          )}
          <button
            type="button"
            className="chat-close"
            aria-label="Đóng trợ lý"
            onClick={() => setOpen(false)}
          >
            ×
          </button>
        </div>
      </header>

      {view === 'history' ? (
        <History onOpen={openConversation} onNew={startNew} onBack={() => setView('chat')} />
      ) : (
        <>
          <div className="chat-messages" ref={listRef} aria-live="polite">
            <div className="chat-message chat-message--assistant">
              <p>{WELCOME}</p>
              {!userId && (
                <p className="chat-muted">
                  <Link to="/login" state={{ returnTo }}>
                    Đăng nhập
                  </Link>{' '}
                  để tra cứu đơn hàng và lưu lịch sử trò chuyện.
                </p>
              )}
            </div>
            {messages.length === 0 && (
              <div className="chat-suggestions" role="group" aria-label="Câu hỏi gợi ý">
                {SUGGESTIONS.map((text) => (
                  <button key={text} type="button" disabled={sending} onClick={() => send(text)}>
                    {text}
                  </button>
                ))}
              </div>
            )}
            {messages.map((message) => (
              <div key={message.id} className={`chat-message chat-message--${message.role}`}>
                <span className="visually-hidden">
                  {message.role === 'user' ? 'Bạn:' : 'Trợ lý VMarket:'}
                </span>
                <p>{message.content}</p>
                {message.role === 'assistant' && (
                  <AssistantExtras
                    message={message}
                    signedIn={Boolean(userId)}
                    returnTo={returnTo}
                    busy={sending}
                    onHandoff={() => send(HANDOFF_MESSAGE)}
                  />
                )}
              </div>
            ))}
            {sending && (
              <p className="chat-message chat-message--assistant chat-typing" role="status">
                Trợ lý đang trả lời…
              </p>
            )}
            {failed && (
              <div className="chat-error" role="alert">
                <p>{failed.message}</p>
                {failed.retry && (
                  <button
                    type="button"
                    className="chat-text-button"
                    onClick={() => send(failed.retry)}
                  >
                    Thử lại
                  </button>
                )}
              </div>
            )}
          </div>
          <form
            className="chat-form"
            onSubmit={(event) => {
              event.preventDefault()
              send(input)
            }}
          >
            <label className="visually-hidden" htmlFor={`${panelId}-input`}>
              Nhập câu hỏi cho trợ lý
            </label>
            <textarea
              id={`${panelId}-input`}
              ref={inputRef}
              rows={2}
              maxLength={CHAT_LIMITS.message}
              placeholder="Hỏi về sản phẩm, chính sách, đơn hàng…"
              value={input}
              onChange={(event) => setInput(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
                  event.preventDefault()
                  send(input)
                }
              }}
            />
            <button type="submit" className="chat-primary" disabled={sending || !input.trim()}>
              Gửi
            </button>
          </form>
          <div className="chat-panel__foot">
            <button
              type="button"
              className="chat-text-button"
              disabled={sending}
              onClick={() => send(HANDOFF_MESSAGE)}
            >
              Liên hệ hỗ trợ
            </button>
          </div>
        </>
      )}
    </section>
  )
}
