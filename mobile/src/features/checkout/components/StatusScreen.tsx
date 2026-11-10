import type { ReactNode } from 'react'

import { AppText, Button, InfoCard, Screen } from '@/components/ui'

export interface StatusCard {
  title: string
  body?: string
}

export interface StatusAction {
  label: string
  onPress: () => void
  loading?: boolean
  testID?: string
}

export interface StatusScreenProps {
  title: string
  summary?: string
  cards?: StatusCard[]
  primary?: StatusAction
  secondary?: StatusAction
  onBack?: () => void
  children?: ReactNode
  testID?: string
}

/** Figma "ResultScreen": header, summary line, info cards, primary + secondary buttons. */
export function StatusScreen({
  title,
  summary,
  cards,
  primary,
  secondary,
  onBack,
  children,
  testID,
}: StatusScreenProps) {
  return (
    <Screen title={title} onBack={onBack} testID={testID}>
      {summary ? <AppText>{summary}</AppText> : null}
      {cards?.map((card) => (
        <InfoCard key={card.title + card.body} title={card.title} body={card.body} />
      ))}
      {children}
      {primary ? (
        <Button
          title={primary.label}
          onPress={primary.onPress}
          loading={primary.loading}
          testID={primary.testID}
        />
      ) : null}
      {secondary ? (
        <Button
          title={secondary.label}
          kind="secondary"
          onPress={secondary.onPress}
          loading={secondary.loading}
          testID={secondary.testID}
        />
      ) : null}
    </Screen>
  )
}
