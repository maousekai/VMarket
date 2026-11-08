import type { ChatSource } from '@/api/chat'

export function parseSources(raw: string | undefined): ChatSource[] {
  if (!raw) return []
  try {
    const parsed: unknown = JSON.parse(raw)
    return Array.isArray(parsed) ? (parsed as ChatSource[]) : []
  } catch {
    return []
  }
}

/** Sources grouped by document, in first-seen order. */
export function groupSources(sources: ChatSource[]): { document: string; items: ChatSource[] }[] {
  const groups = new Map<string, ChatSource[]>()
  for (const s of sources) groups.set(s.source, [...(groups.get(s.source) ?? []), s])
  return [...groups].map(([document, items]) => ({ document, items }))
}

/** Document file names are shown without extension, e.g. "faq.md" -> "faq". */
export function documentLabel(source: string): string {
  return source.replace(/\.[a-z0-9]+$/i, '')
}
