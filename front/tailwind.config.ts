import type { Config } from 'tailwindcss'

/** 需要生成原子类的源码范围 */
const contentGlobs = [
  './pages/**/*.{ts,tsx}',
  './components/**/*.{ts,tsx}',
  './app/**/*.{ts,tsx}',
  './src/**/*.{ts,tsx}',
]

/** 中文优先的界面字体栈（Windows / macOS / Linux 均有回退） */
const sansStack = [
  'Inter',
  '-apple-system',
  'BlinkMacSystemFont',
  '"Segoe UI Variable Text"',
  '"Segoe UI"',
  '"PingFang SC"',
  '"Hiragino Sans GB"',
  '"Microsoft YaHei UI"',
  '"Microsoft YaHei"',
  '"Source Han Sans SC"',
  '"Noto Sans CJK SC"',
  'sans-serif',
]

const monoStack = [
  'ui-monospace',
  '"SF Mono"',
  '"JetBrains Mono"',
  'Menlo',
  'Consolas',
  '"Microsoft YaHei"',
  'monospace',
]

/** 字号 token 的元组结构：首项字号，次项行高/字距/字重 */
type FontSizeEntry = [
  size: string,
  metrics: { lineHeight: string; letterSpacing?: string; fontWeight?: string | number },
]

/**
 * 字号整体降一档（xs=11 / sm=12 / base=13），用于运维台的信息密度；
 * title/display/kpi 是 V2 设计系统的展示级字号。
 */
const fontSizeScale: Record<string, FontSizeEntry> = {
  xs: ['11px', { lineHeight: '16px', letterSpacing: '0.01em' }],
  sm: ['12px', { lineHeight: '18px' }],
  base: ['13px', { lineHeight: '20px' }],
  lg: ['14px', { lineHeight: '20px' }],
  xl: ['16px', { lineHeight: '22px' }],
  '2xl': ['20px', { lineHeight: '28px' }],
  '3xl': ['22px', { lineHeight: '30px', letterSpacing: '-0.01em' }],
  '4xl': ['26px', { lineHeight: '30px', letterSpacing: '-0.02em' }],
  title: ['30px', { lineHeight: '36px', letterSpacing: '-0.02em', fontWeight: '600' }],
  display: ['40px', { lineHeight: '44px', letterSpacing: '-0.03em', fontWeight: '600' }],
  kpi: ['26px', { lineHeight: '30px', letterSpacing: '-0.02em', fontWeight: '500' }],
  'kpi-lg': ['32px', { lineHeight: '36px', letterSpacing: '-0.02em', fontWeight: '500' }],
  'kpi-cover': ['48px', { lineHeight: '48px', letterSpacing: '-0.04em', fontWeight: '500' }],
}

/** 基础色：全部映射到 globals.css 的 CSS 变量 */
const baseColors = {
  border: 'hsl(var(--border))',
  hairline: 'hsl(var(--border-subtle))',
  input: 'hsl(var(--input))',
  ring: 'hsl(var(--ring))',
  background: 'hsl(var(--background))',
  foreground: 'hsl(var(--foreground))',
  card: { DEFAULT: 'hsl(var(--card))', foreground: 'hsl(var(--card-foreground))' },
  popover: { DEFAULT: 'hsl(var(--popover))', foreground: 'hsl(var(--popover-foreground))' },
  primary: {
    DEFAULT: 'hsl(var(--primary))',
    hover: 'hsl(var(--primary-hover))',
    active: 'hsl(var(--primary-active))',
    subtle: 'hsl(var(--primary-subtle))',
    foreground: 'hsl(var(--primary-foreground))',
    'on-subtle': 'hsl(var(--primary-on-subtle))',
  },
  secondary: { DEFAULT: 'hsl(var(--secondary))', foreground: 'hsl(var(--secondary-foreground))' },
  muted: { DEFAULT: 'hsl(var(--muted))', foreground: 'hsl(var(--muted-foreground))' },
  accent: { DEFAULT: 'hsl(var(--accent))', foreground: 'hsl(var(--accent-foreground))' },
  destructive: { DEFAULT: 'hsl(var(--destructive))', foreground: 'hsl(var(--destructive-foreground))' },
  sidebar: 'hsl(var(--sidebar))',
  surface: { DEFAULT: 'hsl(var(--card))', inset: 'hsl(var(--surface-inset))' },
}

/** 三档齐全的语义状态色（DEFAULT / bg / border） */
const statusColors = {
  success: {
    DEFAULT: 'hsl(var(--success))',
    bg: 'hsl(var(--success-bg))',
    border: 'hsl(var(--success-border))',
  },
  warning: {
    DEFAULT: 'hsl(var(--warning))',
    bg: 'hsl(var(--warning-bg))',
    border: 'hsl(var(--warning-border))',
  },
  danger: {
    DEFAULT: 'hsl(var(--danger))',
    bg: 'hsl(var(--danger-bg))',
    border: 'hsl(var(--danger-border))',
  },
  info: {
    DEFAULT: 'hsl(var(--info))',
    bg: 'hsl(var(--info-bg))',
    border: 'hsl(var(--info-border))',
  },
}

/** 暖中性色阶：少量直接使用，优先语义 token */
const warmNeutrals = {
  sand: {
    50: 'hsl(var(--sand-50))',
    100: 'hsl(var(--sand-100))',
    200: 'hsl(var(--sand-200))',
    300: 'hsl(var(--sand-300))',
  },
  clay: {
    400: 'hsl(var(--clay-400))',
    500: 'hsl(var(--clay-500))',
    600: 'hsl(var(--clay-600))',
    700: 'hsl(var(--clay-700))',
  },
  ink: {
    800: 'hsl(var(--ink-800))',
    900: 'hsl(var(--ink-900))',
    950: 'hsl(var(--ink-950))',
  },
}

/** 材质色只供 PageHeader 黄铜竖线等 signature 细节使用 */
const materialColors = {
  brass: { DEFAULT: 'hsl(var(--brass))', dark: 'hsl(var(--brass-dark))' },
}

/** V3 主题原始色（三套 data-theme 提供的 CSS 变量，直接映射；不带 hsl 包装） */
const ocColors = {
  bg: 'var(--bg)',
  surface: 'var(--surface)',
  surface2: 'var(--surface2)',
  line: 'var(--v3-line)',
  'line-strong': 'var(--line-strong)',
  text: 'var(--text)',
  muted: 'var(--v3-muted)',
  accent: 'var(--v3-accent)',
  'accent-ink': 'var(--accent-ink)',
  'accent-soft': 'var(--accent-soft)',
  ok: 'var(--v3-ok)',
  warn: 'var(--v3-warn)',
  bad: 'var(--v3-bad)',
  'bar-bg': 'var(--bar-bg)',
  'bar-text': 'var(--bar-text)',
  'bar-muted': 'var(--bar-muted)',
  'bar-line': 'var(--bar-line)',
}

const colors = {
  ...baseColors,
  ...statusColors,
  ...warmNeutrals,
  ...materialColors,
  oc: ocColors,
}

const radii = {
  lg: 'var(--radius)',
  md: 'var(--radius-control)',
  sm: 'var(--radius-tag)',
  card: 'var(--radius)',
  control: 'var(--radius-control)',
  tag: 'var(--radius-tag)',
  panel: '12px',
}

const shadows = {
  e1: 'var(--shadow-e1)',
  e2: 'var(--shadow-e2)',
  e3: 'var(--shadow-e3)',
  'e1-dark': 'var(--shadow-e1-dark)',
  overlay: 'var(--shadow-overlay)',
  focus: 'var(--shadow-focus)',
}

const accordionKeyframes = {
  'accordion-down': {
    from: { height: '0' },
    to: { height: 'var(--radix-accordion-content-height)' },
  },
  'accordion-up': {
    from: { height: 'var(--radix-accordion-content-height)' },
    to: { height: '0' },
  },
}

const accordionAnimation = {
  'accordion-down': 'accordion-down 0.16s cubic-bezier(0.2,0,0,1)',
  'accordion-up': 'accordion-up 0.16s cubic-bezier(0.2,0,0,1)',
}

const config: Config = {
  darkMode: ['class'],
  content: contentGlobs,
  prefix: '',
  theme: {
    container: {
      center: true,
      padding: '2rem',
      screens: {
        '2xl': '1400px',
      },
    },
    extend: {
      fontFamily: {
        sans: sansStack,
        mono: monoStack,
      },
      fontSize: fontSizeScale,
      colors,
      borderRadius: radii,
      boxShadow: shadows,
      keyframes: accordionKeyframes,
      animation: accordionAnimation,
    },
  },
  plugins: [require('tailwindcss-animate')],
}

export default config

