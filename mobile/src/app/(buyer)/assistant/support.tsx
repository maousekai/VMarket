import { router, useLocalSearchParams } from 'expo-router'
import { useEffect, useRef, useState } from 'react'

import { getApiError } from '@/api/client'
import { useAuth } from '@/auth/AuthProvider'
import { useRequireAuth } from '@/auth/useRequireAuth'
import { AppText, Button, Screen, TextField } from '@/components/ui'
import { assistantHref } from '@/features/assistant/routes'
import { requestSupport, SUPPORT_MAX_LENGTH } from '@/features/assistant/supportRequest'

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

export default function AssistantSupportScreen() {
  const params = useLocalSearchParams<{ conversationId?: string; question?: string }>()
  const { status, user } = useAuth()
  const requireAuth = useRequireAuth()
  const [email, setEmail] = useState(user?.email ?? '')
  const [content, setContent] = useState((params.question ?? '').slice(0, SUPPORT_MAX_LENGTH))
  const [errors, setErrors] = useState<{ email?: string; content?: string }>({})
  const [submitError, setSubmitError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const busy = useRef(false)

  useEffect(() => {
    if (status === 'guest') requireAuth('/assistant')
  }, [status, requireAuth])

  const submit = async () => {
    if (busy.current) return
    const next: typeof errors = {}
    if (!email.trim()) next.email = 'Vui lòng nhập email'
    else if (!EMAIL_RE.test(email.trim())) next.email = 'Email không hợp lệ'
    if (!content.trim()) next.content = 'Vui lòng nhập nội dung cần hỗ trợ'
    setErrors(next)
    setSubmitError(null)
    if (next.email || next.content) return

    busy.current = true
    setSubmitting(true)
    try {
      const ticketId = await requestSupport(content, params.conversationId || null)
      if (!ticketId) {
        setSubmitError('Chưa tạo được phiếu hỗ trợ. Bạn vui lòng thử lại.')
        return
      }
      router.replace(assistantHref('/support-sent', { ticketId, content: content.trim() }))
    } catch (e) {
      setSubmitError(getApiError(e).message)
    } finally {
      busy.current = false
      setSubmitting(false)
    }
  }

  return (
    <Screen testID="assistant-support-screen" title="Liên hệ hỗ trợ" onBack={() => router.back()}>
      <AppText>Nội dung này được gửi tới bộ phận hỗ trợ</AppText>
      <TextField
        testID="support-email"
        label="Email liên hệ"
        required
        value={email}
        onChangeText={setEmail}
        keyboardType="email-address"
        autoCapitalize="none"
        autoComplete="email"
        error={errors.email}
      />
      <TextField
        testID="support-content"
        label="Nội dung cần hỗ trợ"
        required
        value={content}
        onChangeText={setContent}
        multiline
        maxLength={SUPPORT_MAX_LENGTH}
        error={errors.content}
      />
      {submitError ? (
        <AppText testID="support-error" color="danger">
          {submitError}
        </AppText>
      ) : null}
      <Button
        testID="support-submit"
        title="Gửi yêu cầu hỗ trợ"
        loading={submitting}
        onPress={() => void submit()}
      />
      <Button title="Về hội thoại" kind="secondary" onPress={() => router.back()} />
    </Screen>
  )
}
