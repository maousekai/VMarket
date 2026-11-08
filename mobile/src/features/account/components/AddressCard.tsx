import type { ReactNode } from 'react'

import type { Address } from '@/api/user'
import { AppText, Card } from '@/components/ui'

import { fullAddressLine, recipientLine, streetLine } from '../addressText'

interface Props {
  title: string
  address: Address
  /** One-line address (street, ward, province) instead of three lines. */
  compact?: boolean
  note?: string
  children?: ReactNode
  testID?: string
}

/** Design InfoCard: title, "name · phone", address lines. */
export function AddressCard({ title, address, compact, note, children, testID }: Props) {
  return (
    <Card testID={testID}>
      <AppText variant="label">{title}</AppText>
      <AppText variant="body">{recipientLine(address)}</AppText>
      {compact ? (
        <AppText variant="body">{fullAddressLine(address)}</AppText>
      ) : (
        <>
          <AppText variant="body">{streetLine(address)}</AppText>
          <AppText variant="body">{address.province}</AppText>
        </>
      )}
      {note ? (
        <AppText variant="body" color="textSecondary">
          {note}
        </AppText>
      ) : null}
      {children}
    </Card>
  )
}
