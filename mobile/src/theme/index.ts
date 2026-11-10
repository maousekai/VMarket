export const colors = {
  primary50: '#E9F5FF',
  primary600: '#0670CE',
  primary700: '#0559A8',
  accent500: '#F97316',
  accent600: '#EA580C',
  accentStrong: '#C2410C',
  surface: '#FFFFFF',
  surfaceSubtle: '#F9FAFB',
  surfaceMuted: '#F3F4F6',
  border: '#E5E7EB',
  text: '#111827',
  textSecondary: '#4B5563',
  textSubtle: '#6B7280',
  textInverse: '#FFFFFF',
  textInverseMuted: 'rgba(255,255,255,0.9)',
  warning: '#D97706',
  neutral: '#6B7280',
  info: '#0284C7',
  transit: '#7C3AED',
  success: '#16A34A',
  danger: '#DC2626',
  warningSoft: '#FEF3C7',
  neutralSoft: '#F3F4F6',
  infoSoft: '#E0F2FE',
  transitSoft: '#EDE9FE',
  successSoft: '#DCFCE7',
  dangerSoft: '#FEE2E2',
  overlay: 'rgba(17,24,39,0.5)',
} as const

export const fonts = {
  regular: 'PlusJakartaSans_400Regular',
  medium: 'PlusJakartaSans_500Medium',
  semibold: 'PlusJakartaSans_600SemiBold',
  bold: 'PlusJakartaSans_700Bold',
} as const

export const type = {
  display: { fontFamily: fonts.bold, fontSize: 30, lineHeight: 39 },
  h2: { fontFamily: fonts.semibold, fontSize: 24, lineHeight: 31 },
  h3: { fontFamily: fonts.semibold, fontSize: 20, lineHeight: 26 },
  bodyLg: { fontFamily: fonts.regular, fontSize: 16, lineHeight: 24 },
  body: { fontFamily: fonts.regular, fontSize: 14, lineHeight: 21 },
  label: { fontFamily: fonts.semibold, fontSize: 14, lineHeight: 21 },
  button: { fontFamily: fonts.semibold, fontSize: 16, lineHeight: 24 },
  caption: { fontFamily: fonts.medium, fontSize: 12, lineHeight: 18 },
  wordmark: { fontFamily: fonts.semibold, fontSize: 18, lineHeight: 24 },
} as const

export const space = {
  1: 4,
  2: 8,
  3: 12,
  4: 16,
  5: 20,
  6: 24,
  8: 32,
  10: 40,
  12: 48,
  16: 64,
} as const

export const radius = { sm: 6, md: 10, lg: 16, full: 999 } as const

export const shadow = {
  card: {
    shadowColor: '#000',
    shadowOpacity: 0.04,
    shadowRadius: 4,
    shadowOffset: { width: 0, height: 1 },
    elevation: 1,
  },
  sheet: {
    shadowColor: '#000',
    shadowOpacity: 0.12,
    shadowRadius: 16,
    shadowOffset: { width: 0, height: -4 },
    elevation: 8,
  },
} as const

export const layout = {
  screenPaddingX: 20,
  screenPaddingTop: 20,
  stackGap: 12,
  cardPadding: 16,
  controlHeight: 48,
  controlHeightM: 40,
  tabItemHeight: 44,
  tabBarHeight: 76,
  headerHeight: 120,
  minTouch: 44,
} as const

export type Tone = 'warning' | 'neutral' | 'info' | 'transit' | 'success' | 'danger'

const toneMap: Record<Tone, { fg: string; bg: string }> = {
  warning: { fg: colors.warning, bg: colors.warningSoft },
  neutral: { fg: colors.neutral, bg: colors.neutralSoft },
  info: { fg: colors.info, bg: colors.infoSoft },
  transit: { fg: colors.transit, bg: colors.transitSoft },
  success: { fg: colors.success, bg: colors.successSoft },
  danger: { fg: colors.danger, bg: colors.dangerSoft },
}

/** tone -> { fg, bg } (text/dot colour and soft background). */
export function toneColors(tone: Tone) {
  return toneMap[tone]
}
