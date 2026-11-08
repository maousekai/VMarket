import { router, useLocalSearchParams } from 'expo-router'

import { AppText, Button, InfoCard, Screen } from '@/components/ui'
import { documentLabel, groupSources, parseSources } from '@/features/assistant/sources'

export default function AssistantSourcesScreen() {
  const { sources: raw } = useLocalSearchParams<{ sources?: string }>()
  const groups = groupSources(parseSources(raw))

  return (
    <Screen
      testID="assistant-sources"
      title="Nguồn trả lời"
      onBack={() => router.back()}
      footer={<Button testID="sources-back" title="Về hội thoại" onPress={() => router.back()} />}
    >
      {groups.length === 0 ? (
        <AppText>Câu trả lời này không có nguồn tham khảo.</AppText>
      ) : (
        groups.map((group) => (
          <Section key={group.document} document={group.document} titles={group.items} />
        ))
      )}
    </Screen>
  )
}

function Section({
  document,
  titles,
}: {
  document: string
  titles: { id: string; title: string }[]
}) {
  return (
    <>
      <AppText>{documentLabel(document)}</AppText>
      {titles.map((s) => (
        <InfoCard key={s.id} title={s.title} />
      ))}
    </>
  )
}
