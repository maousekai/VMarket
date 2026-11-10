import { View } from 'react-native'

import { getApiError } from '@/api/client'
import { AppText } from '@/components/ui/AppText'
import { Button } from '@/components/ui/Button'
import { InfoCard } from '@/components/ui/Card'

export interface EmptyStateProps {
  /** h2 heading, e.g. "Giỏ hàng đang trống". */
  heading: string
  /** Card title (defaults to heading when omitted -> heading hidden). */
  title?: string
  body?: string
  primaryLabel?: string
  onPrimary?: () => void
  secondaryLabel?: string
  onSecondary?: () => void
  testID?: string
}

/** design-system 4.12: h2 heading + InfoCard + primary / secondary buttons. */
export function EmptyState({
  heading,
  title,
  body,
  primaryLabel,
  onPrimary,
  secondaryLabel,
  onSecondary,
  testID,
}: EmptyStateProps) {
  return (
    <View testID={testID} style={{ gap: 12 }}>
      <AppText variant="h2">{heading}</AppText>
      {title || body ? <InfoCard title={title ?? heading} body={body} /> : null}
      {primaryLabel ? <Button title={primaryLabel} onPress={onPrimary} /> : null}
      {secondaryLabel ? (
        <Button title={secondaryLabel} kind="secondary" onPress={onSecondary} />
      ) : null}
    </View>
  )
}

export interface ErrorStateProps {
  /** Anything thrown by an API call; its message is shown in the card. */
  error?: unknown
  heading?: string
  title?: string
  body?: string
  onRetry?: () => void
  retryLabel?: string
  secondaryLabel?: string
  onSecondary?: () => void
  testID?: string
}

/** Error / offline variant of EmptyState with a retry button. */
export function ErrorState({
  error,
  heading = 'Kết nối bị gián đoạn',
  title = 'Không tải được dữ liệu',
  body,
  onRetry,
  retryLabel = 'Thử lại',
  secondaryLabel,
  onSecondary,
  testID,
}: ErrorStateProps) {
  const message =
    body ?? (error ? getApiError(error).message : 'Vui lòng kiểm tra kết nối và thử lại.')
  return (
    <EmptyState
      testID={testID}
      heading={heading}
      title={title}
      body={message}
      primaryLabel={onRetry ? retryLabel : undefined}
      onPrimary={onRetry}
      secondaryLabel={secondaryLabel}
      onSecondary={onSecondary}
    />
  )
}
