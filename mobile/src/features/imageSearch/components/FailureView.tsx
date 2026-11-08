import { AppText, Button, InfoCard } from '@/components/ui'
import type { SearchFailure } from '@/features/imageSearch/errors'

const COPY: Record<
  Exclude<SearchFailure, 'tooLarge'>,
  { summary: string; title: string; body: string }
> = {
  network: {
    summary: 'Kết nối bị gián đoạn',
    title: 'Ảnh của bạn vẫn được giữ',
    body: 'Kiểm tra kết nối rồi thử lại hoặc chọn ảnh khác.',
  },
  unavailable: {
    summary: 'Dịch vụ tìm bằng ảnh chưa phản hồi',
    title: 'Ảnh của bạn vẫn được giữ',
    body: 'Hệ thống tạm thời chưa xử lý được ảnh. Hãy thử lại sau ít phút hoặc chọn ảnh khác.',
  },
  busy: {
    summary: 'Hệ thống đang bận',
    title: 'Ảnh của bạn vẫn được giữ',
    body: 'Có nhiều yêu cầu tìm bằng ảnh cùng lúc. Hãy thử lại sau giây lát.',
  },
  invalidImage: {
    summary: 'Ảnh không hợp lệ',
    title: 'Không đọc được ảnh',
    body: 'Chọn ảnh JPEG, PNG hoặc WebP rõ nét.',
  },
}

/** Frame 3.48 - the request failed; the photo is kept so the user can retry. */
export function FailureView({
  failure,
  onRetry,
  onPickAnother,
}: {
  failure: Exclude<SearchFailure, 'tooLarge'>
  onRetry: () => void
  onPickAnother: () => void
}) {
  const copy = COPY[failure]
  const canRetry = failure !== 'invalidImage'
  return (
    <>
      <AppText variant="body">{copy.summary}</AppText>
      <InfoCard title={copy.title} body={copy.body} />
      {canRetry ? <Button testID="image-search-retry" title="Thử lại" onPress={onRetry} /> : null}
      <Button
        testID="image-search-pick-another"
        title="Chọn ảnh khác"
        kind={canRetry ? 'secondary' : 'primary'}
        onPress={onPickAnother}
      />
    </>
  )
}
