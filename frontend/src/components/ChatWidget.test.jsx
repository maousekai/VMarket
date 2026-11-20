// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import ChatWidget from './ChatWidget'
import { AuthContext } from '../auth/useAuth'
import {
  listConversationMessages,
  listConversations,
  listMyTickets,
  sendChatMessage,
} from '../services/api/chatbotApi'

vi.mock('../services/api/chatbotApi', async (importOriginal) => ({
  ...(await importOriginal()),
  listConversationMessages: vi.fn(),
  listConversations: vi.fn(),
  listMyTickets: vi.fn(),
  sendChatMessage: vi.fn(),
}))

const CONVERSATION = 'a'.repeat(32)
const buyer = { session: { userId: 'u1', roles: ['BUYER'] }, bootstrapState: 'ready' }
const guest = { session: null, bootstrapState: 'ready' }

const reply = (overrides) => ({
  conversationId: null,
  messageId: null,
  reply: 'Bạn có thể yêu cầu trả hàng trong 7 ngày.',
  intent: 'answer',
  sources: [{ id: 'faq:1', title: 'Chính sách đổi trả', source: 'faq', docId: 'faq' }],
  handoff: { suggested: false, ticketId: null },
  loginRequired: false,
  ...overrides,
})

const renderWidget = (auth = guest) =>
  render(
    <AuthContext.Provider value={auth}>
      <MemoryRouter initialEntries={['/products/p1']}>
        <ChatWidget />
      </MemoryRouter>
    </AuthContext.Provider>,
  )

const openPanel = () => {
  fireEvent.click(screen.getByRole('button', { name: /Trợ lý/ }))
  return screen.getByRole('dialog', { name: 'Trợ lý VMarket' })
}

beforeEach(() => {
  vi.clearAllMocks()
  sessionStorage.clear()
  listConversations.mockResolvedValue([])
  listMyTickets.mockResolvedValue([])
})

describe('ChatWidget', () => {
  it('lets a guest ask a question and shows the answer with its sources', async () => {
    sendChatMessage.mockResolvedValue(reply())
    renderWidget()
    const panel = openPanel()
    expect(within(panel).getByText(/Khách · không lưu lịch sử/)).toBeInTheDocument()
    expect(within(panel).queryByRole('button', { name: 'Lịch sử' })).not.toBeInTheDocument()

    fireEvent.change(within(panel).getByLabelText('Nhập câu hỏi cho trợ lý'), {
      target: { value: 'Điều kiện trả hàng?' },
    })
    fireEvent.click(within(panel).getByRole('button', { name: 'Gửi' }))

    expect(
      await within(panel).findByText('Bạn có thể yêu cầu trả hàng trong 7 ngày.'),
    ).toBeInTheDocument()
    expect(within(panel).getByText('Điều kiện trả hàng?')).toBeInTheDocument()
    expect(within(panel).getByText('Chính sách đổi trả')).toBeInTheDocument()
    expect(sendChatMessage).toHaveBeenCalledWith({
      message: 'Điều kiện trả hàng?',
      conversationId: null,
    })
  })

  it('asks a guest to sign in for order lookup', async () => {
    sendChatMessage.mockResolvedValue(
      reply({
        intent: 'order_lookup',
        reply: 'Bạn vui lòng đăng nhập để tra cứu đơn hàng.',
        sources: [],
        loginRequired: true,
      }),
    )
    renderWidget()
    const panel = openPanel()
    fireEvent.click(within(panel).getByRole('button', { name: 'Đơn hàng của tôi đang ở đâu?' }))
    expect(await within(panel).findByText(/đăng nhập để tra cứu/)).toBeInTheDocument()
    const links = within(panel).getAllByRole('link', { name: 'Đăng nhập' })
    expect(links.at(-1)).toHaveAttribute('href', '/login')
  })

  it('keeps a signed-in conversation going and links order answers to my orders', async () => {
    sendChatMessage
      .mockResolvedValueOnce(
        reply({
          conversationId: CONVERSATION,
          messageId: 'm2',
          intent: 'order_lookup',
          reply: 'Đơn #0P4S6A2B đang giao.',
          sources: [],
        }),
      )
      .mockResolvedValueOnce(reply({ conversationId: CONVERSATION, messageId: 'm4' }))
    renderWidget(buyer)
    const panel = openPanel()
    fireEvent.click(within(panel).getByRole('button', { name: 'Đơn hàng của tôi đang ở đâu?' }))
    expect(await within(panel).findByText('Đơn #0P4S6A2B đang giao.')).toBeInTheDocument()
    expect(within(panel).getByRole('link', { name: 'Xem đơn hàng của tôi' })).toHaveAttribute(
      'href',
      '/profile/orders',
    )
    expect(sessionStorage.getItem('vmarket.chat.u1')).toBe(CONVERSATION)

    fireEvent.change(within(panel).getByLabelText('Nhập câu hỏi cho trợ lý'), {
      target: { value: 'Còn đổi trả?' },
    })
    fireEvent.keyDown(within(panel).getByLabelText('Nhập câu hỏi cho trợ lý'), { key: 'Enter' })
    await waitFor(() =>
      expect(sendChatMessage).toHaveBeenLastCalledWith({
        message: 'Còn đổi trả?',
        conversationId: CONVERSATION,
      }),
    )
  })

  it('reopens the stored conversation and browses history and tickets', async () => {
    sessionStorage.setItem('vmarket.chat.u1', CONVERSATION)
    listConversationMessages.mockResolvedValue({
      conversationId: CONVERSATION,
      messages: [
        { id: 'm1', role: 'user', content: 'Phí ship?', sources: [] },
        { id: 'm2', role: 'assistant', content: 'Phí ship tính khi giao.', sources: [] },
      ],
      hasMore: false,
      nextCursor: null,
    })
    listConversations.mockResolvedValue([
      {
        id: CONVERSATION,
        title: 'Phí ship?',
        messageCount: 2,
        createdAt: '2026-11-10T03:00:00Z',
        updatedAt: '2026-11-10T03:00:00Z',
      },
    ])
    listMyTickets.mockResolvedValue([
      {
        id: 'T1',
        status: 'OPEN',
        question: 'Cần gặp nhân viên',
        conversationId: CONVERSATION,
        createdAt: '2026-11-10T03:00:00Z',
      },
    ])
    renderWidget(buyer)
    const panel = openPanel()
    expect(await within(panel).findByText('Phí ship tính khi giao.')).toBeInTheDocument()
    expect(listConversationMessages).toHaveBeenCalledWith(CONVERSATION)

    fireEvent.click(within(panel).getByRole('button', { name: 'Lịch sử' }))
    expect(await within(panel).findByText('Cần gặp nhân viên')).toBeInTheDocument()
    expect(within(panel).getByText('#T1')).toBeInTheDocument()
    fireEvent.click(within(panel).getByRole('button', { name: 'Bắt đầu hội thoại mới' }))
    expect(within(panel).queryByText('Phí ship tính khi giao.')).not.toBeInTheDocument()
    expect(within(panel).getByRole('group', { name: 'Câu hỏi gợi ý' })).toBeInTheDocument()
  })

  it('hands off to human support and shows the ticket number', async () => {
    sendChatMessage.mockResolvedValue(
      reply({
        conversationId: CONVERSATION,
        intent: 'handoff',
        reply: 'Mình đã tạo phiếu hỗ trợ #T9.',
        sources: [],
        handoff: { suggested: true, ticketId: 'T9' },
      }),
    )
    renderWidget(buyer)
    const panel = openPanel()
    fireEvent.click(within(panel).getByRole('button', { name: 'Liên hệ hỗ trợ' }))
    expect(await within(panel).findByText('Mã phiếu hỗ trợ: #T9')).toBeInTheDocument()
    expect(sendChatMessage).toHaveBeenCalledWith({
      message: 'Tôi muốn gặp nhân viên hỗ trợ',
      conversationId: null,
    })
  })

  it('explains an unavailable assistant and retries the same question', async () => {
    sendChatMessage
      .mockRejectedValueOnce({
        response: { status: 503, data: { error: { code: 'CHAT_UNAVAILABLE', message: 'x' } } },
      })
      .mockResolvedValueOnce(reply())
    renderWidget()
    const panel = openPanel()
    fireEvent.click(within(panel).getByRole('button', { name: 'Chính sách đổi trả như thế nào?' }))
    expect(await within(panel).findByText(/Trợ lý đang bận/)).toBeInTheDocument()
    fireEvent.click(within(panel).getByRole('button', { name: 'Thử lại' }))
    expect(
      await within(panel).findByText('Bạn có thể yêu cầu trả hàng trong 7 ngày.'),
    ).toBeInTheDocument()
    expect(sendChatMessage).toHaveBeenCalledTimes(2)
    expect(within(panel).getAllByText('Chính sách đổi trả như thế nào?')).toHaveLength(1)
  })

  it('drops a reply that arrives after the account changed', async () => {
    let resolveReply
    sendChatMessage.mockReturnValue(new Promise((resolve) => (resolveReply = resolve)))
    const tree = (auth) => (
      <AuthContext.Provider value={auth}>
        <MemoryRouter initialEntries={['/products/p1']}>
          <ChatWidget />
        </MemoryRouter>
      </AuthContext.Provider>
    )
    const { rerender } = render(tree(buyer))
    const panel = openPanel()
    fireEvent.click(within(panel).getByRole('button', { name: 'Đơn hàng của tôi đang ở đâu?' }))
    expect(await within(panel).findByText('Trợ lý đang trả lời…')).toBeInTheDocument()

    const other = { session: { userId: 'u2', roles: ['BUYER'] }, bootstrapState: 'ready' }
    rerender(tree(other))
    resolveReply(reply({ conversationId: CONVERSATION, intent: 'order_lookup', reply: 'Đơn #A1' }))

    const current = screen.getByRole('dialog', { name: 'Trợ lý VMarket' })
    await waitFor(() =>
      expect(within(current).queryByText('Trợ lý đang trả lời…')).not.toBeInTheDocument(),
    )
    expect(within(current).queryByText('Đơn #A1')).not.toBeInTheDocument()
    expect(
      within(current).queryByText('Đơn hàng của tôi đang ở đâu?', { selector: 'p' }),
    ).not.toBeInTheDocument()
    expect(sessionStorage.getItem('vmarket.chat.u2')).toBeNull()
    expect(within(current).getByRole('button', { name: 'Gửi' })).toBeInTheDocument()
  })
})
