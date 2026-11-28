import { createContext, createElement, useContext, useMemo } from 'react'
import { StyleSheet, useColorScheme } from 'react-native'

// Token mau theo design system Figma "VMarket" (trang Design system / Mobile), dong bo voi web.
export const lightColors = Object.freeze({
  scheme: 'light',
  primary: '#0670CE', // primary-600 / action-default
  primaryDark: '#04427F',
  primaryText: '#0559A8', // primary-700: gia, tab dang chon
  primarySoft: '#E9F5FF', // primary-50
  header: '#0670CE',
  onHeader: '#FFFFFF',
  accent: '#F97316', // accent-500: nen logo
  text: '#111827', // content-default
  muted: '#4B5563', // content-secondary
  subtle: '#6B7280',
  border: '#E5E7EB', // border-default
  surface: '#FFFFFF', // surface-default
  background: '#F9FAFB', // surface-subtle
  surfaceMuted: '#F3F4F6',
  danger: '#B91C1C',
  dangerSoft: '#FEE2E2',
  success: '#15803D',
  successSoft: '#DCFCE7',
  warning: '#B45309',
  warningSoft: '#FEF3C7',
  onPrimary: '#FFFFFF',
})

/**
 * Theme toi that (review PR 68 lan 4, muc 1.3): moi mau chu / nen deu co cap tuong phan du doc,
 * thay vi de ROM (Flyme, MIUI...) tu ep toi lam badge va tieu de mat chu.
 */
export const darkColors = Object.freeze({
  scheme: 'dark',
  primary: '#3B8FE0',
  primaryDark: '#BFDBFE',
  primaryText: '#7CB8F2',
  primarySoft: '#0F2A47',
  header: '#0A4F92',
  onHeader: '#FFFFFF',
  accent: '#F97316',
  text: '#F3F4F6',
  muted: '#C7CDD6',
  subtle: '#9CA3AF',
  border: '#334155',
  surface: '#131C2B',
  background: '#0B1220',
  surfaceMuted: '#1C2738',
  danger: '#FCA5A5',
  dangerSoft: '#3F1518',
  success: '#86EFAC',
  successSoft: '#0F2F1C',
  warning: '#FCD34D',
  warningSoft: '#3A2A08',
  onPrimary: '#FFFFFF',
})

/** Mau sang mac dinh (dung cho code ngoai React, vd. thong bao he thong). */
export const colors = lightColors

export const radius = { sm: 6, md: 10, lg: 16, full: 999 }
export const spacing = (n) => n * 4

/** Kieu chu VMarket/* trong Figma (Plus Jakarta Sans -> font he thong, giu co chu / do dam). */
export const type = {
  h2: { fontSize: 24, fontWeight: '600', lineHeight: 31 },
  h3: { fontSize: 20, fontWeight: '600', lineHeight: 26 },
  label: { fontSize: 14, fontWeight: '600', lineHeight: 21 },
  body: { fontSize: 14, fontWeight: '400', lineHeight: 21 },
  caption: { fontSize: 12, fontWeight: '500', lineHeight: 18 },
  button: { fontSize: 16, fontWeight: '600', lineHeight: 24 },
}

const ThemeContext = createContext(null)

export function ThemeProvider({ children, scheme }) {
  const system = useColorScheme()
  const value = useMemo(() => {
    const dark = (scheme || system) === 'dark'
    return { dark, colors: dark ? darkColors : lightColors }
  }, [scheme, system])
  return createElement(ThemeContext.Provider, { value }, children)
}

/** {dark, colors}; ngoai ThemeProvider (vd. test don le) dung theme sang. */
export function useTheme() {
  return useContext(ThemeContext) || { dark: false, colors: lightColors }
}

/** makeStyles((colors) => ({...})) -> hook tao StyleSheet theo theme hien tai. */
export function makeStyles(factory) {
  return function useStyles() {
    const { colors: palette } = useTheme()
    return useMemo(() => StyleSheet.create(factory(palette)), [palette])
  }
}
