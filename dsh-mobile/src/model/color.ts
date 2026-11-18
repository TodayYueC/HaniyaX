/**
 * Colour helpers for the Haniya Relief tokens. The host (HaniyaX WebView)
 * injects the base palette as CSS custom properties; everything that the
 * Compose implementation derives (face gradient, shadow alphas, glow, diff
 * tints) is computed here so the page never depends on CSS color-mix().
 */

export interface Rgba {
  r: number
  g: number
  b: number
  a: number
}

/** Base tokens the host may inject, without the `--hx-` prefix. */
export const BASE_TOKENS = [
  'bg', 'glass', 'ink', 'ink2', 'accent', 'acc2', 'mid', 'hi', 'shadow',
  'track', 'soft', 'soft2', 'line', 'danger', 'amber', 'on',
] as const

export type BaseToken = typeof BASE_TOKENS[number]
export type Palette = Record<BaseToken, string>

/** 铂雾 Platinum Mist (tokens.md, derived values pre-computed). */
export const PLATINUM: Palette = {
  bg: '#E6EAF1', glass: '#E6EAF1', ink: '#2F3648', ink2: '#6F7890',
  accent: '#6A86F2', acc2: '#AA8CF0', mid: '#8088F1', hi: '#FFFFFF',
  shadow: '#A9B5C9', track: '#E0E5ED', soft: '#D7DEF1', soft2: '#DEDDF1',
  line: '#D3D9E4', danger: '#B4566A', amber: '#D2A26C', on: '#FFFFFF',
}

/** 曜石 Obsidian (tokens.md). */
export const OBSIDIAN: Palette = {
  bg: '#262A33', glass: '#262A33', ink: '#E8EBF3', ink2: '#9AA2B6',
  accent: '#7E95FF', acc2: '#B79CFF', mid: '#9297FF', hi: '#353B47',
  shadow: '#14171D', track: '#22262E', soft: '#323B57', soft2: '#3A3452',
  line: '#363C48', danger: '#E6A3AE', amber: '#D2A26C', on: '#FFFFFF',
}

const clamp255 = (v: number): number => Math.max(0, Math.min(255, Math.round(v)))
const clamp01 = (v: number): number => Math.max(0, Math.min(1, v))

/**
 * Parse a CSS colour as produced by hosts: `#rgb`, `#rgba`, `#rrggbb`,
 * `#rrggbbaa` (CSS order) and `rgb()/rgba()` with comma or space syntax.
 * @returns the colour, or undefined when the text is not one of those forms.
 */
export function parseColor(text: string | undefined | null): Rgba | undefined {
  if (typeof text !== 'string') return undefined
  const s = text.trim().toLowerCase()
  if (s === '') return undefined
  if (s.startsWith('#')) {
    const hex = s.slice(1)
    if (!/^[0-9a-f]+$/.test(hex)) return undefined
    if (hex.length === 3 || hex.length === 4) {
      const v = [...hex].map(ch => parseInt(ch + ch, 16))
      return { r: v[0]!, g: v[1]!, b: v[2]!, a: hex.length === 4 ? v[3]! / 255 : 1 }
    }
    if (hex.length === 6 || hex.length === 8) {
      const v = [0, 2, 4, 6].map(i => parseInt(hex.slice(i, i + 2), 16))
      return { r: v[0]!, g: v[1]!, b: v[2]!, a: hex.length === 8 ? v[3]! / 255 : 1 }
    }
    return undefined
  }
  const m = /^rgba?\(([^)]*)\)$/.exec(s)
  if (m === null) return undefined
  const parts = m[1]!.split(/[\s,/]+/).filter(p => p !== '')
  if (parts.length < 3 || parts.length > 4) return undefined
  const channel = (p: string): number => p.endsWith('%') ? parseFloat(p) * 2.55 : parseFloat(p)
  const [r, g, b] = parts.slice(0, 3).map(channel)
  const a = parts[3] === undefined ? 1 : parts[3].endsWith('%') ? parseFloat(parts[3]) / 100 : parseFloat(parts[3])
  if ([r, g, b, a].some(v => v === undefined || Number.isNaN(v))) return undefined
  return { r: clamp255(r!), g: clamp255(g!), b: clamp255(b!), a: clamp01(a) }
}

/** Linear interpolation between two colours (sRGB, like Compose `lerp`). */
export function mix(a: Rgba, b: Rgba, t: number): Rgba {
  return {
    r: a.r + (b.r - a.r) * t,
    g: a.g + (b.g - a.g) * t,
    b: a.b + (b.b - a.b) * t,
    a: a.a + (b.a - a.a) * t,
  }
}

/** The colour with a replaced alpha. */
export function withAlpha(c: Rgba, alpha: number): Rgba {
  return { ...c, a: clamp01(alpha) }
}

/** CSS text for a colour. */
export function css(c: Rgba): string {
  const r = clamp255(c.r)
  const g = clamp255(c.g)
  const b = clamp255(c.b)
  if (c.a >= 0.999) return `#${[r, g, b].map(v => v.toString(16).padStart(2, '0')).join('')}`
  return `rgba(${r}, ${g}, ${b}, ${Math.round(clamp01(c.a) * 1000) / 1000})`
}

/** WCAG relative luminance. */
export function luminance(c: Rgba): number {
  const ch = (v: number): number => {
    const s = clamp255(v) / 255
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4
  }
  return 0.2126 * ch(c.r) + 0.7152 * ch(c.g) + 0.0722 * ch(c.b)
}

/** WCAG contrast ratio of two opaque colours. */
export function contrast(a: Rgba, b: Rgba): number {
  const la = luminance(a)
  const lb = luminance(b)
  return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05)
}

/** A background is treated as dark when its luminance is below the midpoint. */
export function isDarkBackground(bg: Rgba): boolean {
  return luminance(bg) < 0.18
}

const WHITE: Rgba = { r: 255, g: 255, b: 255, a: 1 }
const BLACK: Rgba = { r: 0, g: 0, b: 0, a: 1 }

/**
 * Fill missing or unparsable base tokens from the default palette of the same
 * lightness, so a partially injected theme still renders consistently.
 */
export function completePalette(input: Partial<Record<BaseToken, string>>): { palette: Record<BaseToken, Rgba>, dark: boolean } {
  const bgParsed = parseColor(input.bg) ?? parseColor(input.glass)
  const dark = bgParsed === undefined ? false : isDarkBackground(bgParsed)
  const fallback = dark ? OBSIDIAN : PLATINUM
  const out = {} as Record<BaseToken, Rgba>
  for (const key of BASE_TOKENS) out[key] = parseColor(input[key]) ?? parseColor(fallback[key])!
  if (parseColor(input.glass) === undefined) out.glass = out.bg
  if (parseColor(input.mid) === undefined) out.mid = mix(out.accent, out.acc2, 0.35)
  return { palette: out, dark }
}

/**
 * Derived material tokens (names without the `--hx-` prefix), mirroring
 * relief/component/Glass.kt and references/materials.md. Dark themes use the
 * banding mitigation: half highlight offset, 1.6x blur, faint inset highlight.
 */
export function deriveTokens(p: Record<BaseToken, Rgba>, dark: boolean): Record<string, string> {
  const t: Record<string, string> = {}
  t['face-hi'] = css(dark ? mix(p.glass, WHITE, 0.035) : mix(p.glass, WHITE, 0.42))
  t['face-lo'] = css(dark ? mix(p.glass, BLACK, 0.10) : mix(p.glass, p.shadow, 0.05))
  t['sh-light'] = css(withAlpha(p.hi, dark ? 0.55 : 0.95))
  t['sh-dark'] = css(withAlpha(p.shadow, dark ? 0.85 : 0.55))
  t['bevel-hi'] = css(dark ? withAlpha(WHITE, 0.07) : withAlpha(WHITE, 0.85))
  t['bevel-lo'] = css(dark ? withAlpha(BLACK, 0.25) : withAlpha(p.shadow, 0.16))
  t['inset-bg'] = css(dark ? p.track : mix(p.bg, p.shadow, 0.05))
  t['inset-dark'] = css(withAlpha(p.shadow, dark ? 0.95 : 0.62))
  t['inset-light'] = css(withAlpha(p.hi, dark ? 0.25 : 1))
  t['glow'] = css(withAlpha(p.mid, dark ? 0.45 : 0.42))
  t['glaze-hi'] = css(withAlpha(p.hi, dark ? 0.35 : 0.9))
  t['div-dark'] = css(withAlpha(p.shadow, dark ? 0.6 : 0.22))
  t['div-light'] = css(withAlpha(p.hi, dark ? 0.35 : 0.9))
  t['press-dark'] = css(withAlpha(p.shadow, 0.20))
  t['press-light'] = css(withAlpha(p.hi, 0.35))
  t['scrim'] = css(dark ? { r: 12, g: 14, b: 18, a: 0.6 } : withAlpha(mix(p.ink, BLACK, 0.3), 0.32))
  t['amber-ink'] = css(dark ? parseColor('#E6C9A2')! : parseColor('#8C6235')!)
  t['amber-soft'] = css(withAlpha(p.amber, 0.14))
  // Diff tints: muted green / rose tuned per lightness, text kept >= 4.5:1 on its tint.
  const addHue = dark ? parseColor('#7FD1A2')! : parseColor('#2F8A5B')!
  const delHue = dark ? parseColor('#E6A3AE')! : parseColor('#B4566A')!
  t['add-bg'] = css(mix(p.bg, addHue, dark ? 0.16 : 0.13))
  t['del-bg'] = css(mix(p.bg, delHue, dark ? 0.16 : 0.12))
  t['add-ink'] = css(readableOn(addHue, mix(p.bg, addHue, dark ? 0.16 : 0.13), dark))
  t['del-ink'] = css(readableOn(delHue, mix(p.bg, delHue, dark ? 0.16 : 0.12), dark))
  t['add-gutter'] = css(mix(p.bg, addHue, dark ? 0.28 : 0.24))
  t['del-gutter'] = css(mix(p.bg, delHue, dark ? 0.28 : 0.22))
  t['code-bg'] = css(dark ? mix(p.track, BLACK, 0.12) : mix(p.bg, p.shadow, 0.07))
  return t
}

/**
 * Darken (light theme) or lighten (dark theme) a hue until it reaches 4.5:1
 * against the background it sits on.
 */
export function readableOn(hue: Rgba, background: Rgba, dark: boolean): Rgba {
  let c = hue
  for (let i = 0; i < 20 && contrast(c, background) < 4.5; i++) c = mix(c, dark ? WHITE : BLACK, 0.12)
  return c
}
