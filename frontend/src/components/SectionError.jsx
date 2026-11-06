import FormMessage from './FormMessage'
import { getApiError } from '../services/api/apiClient'

export default function SectionError({ what, error, onRetry }) {
  const detail = error ? getApiError(error).message : ''
  return (
    <div className="section-state">
      <FormMessage error={{ message: `Không tải được ${what}. ${detail}`.trim() }} />
      {onRetry && (
        <button className="text-button" type="button" onClick={onRetry}>
          Thử lại
        </button>
      )}
    </div>
  )
}
