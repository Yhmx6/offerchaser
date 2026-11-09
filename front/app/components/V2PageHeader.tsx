import type { ReactNode } from 'react'

/**
 * v2 页头（暖纸色系）：眉标 / H1 / 右侧状态 / 渐隐分隔线。
 *
 * 与 `/boss` 总览页同一套语法（那边是内联实现，这里抽成组件供明细/模板/统计三页复用）。
 * 分隔线：金色 → 陶土 → 强线 → 透明，1px，作用是「切口」而不是装饰。
 */
export function V2PageHeader({
  eyebrow,
  title,
  status,
  meta,
  actions,
}: {
  eyebrow: ReactNode
  title: ReactNode
  /** 右侧第一行：● 状态 */
  status?: ReactNode
  /** 右侧第二行：时间 · 时区 */
  meta?: ReactNode
  actions?: ReactNode
}) {
  return (
    <header>
      <p className="oc-eyebrow text-xs font-medium tracking-[0.08em] text-[hsl(var(--text-3))]">{eyebrow}</p>

      <div className="mt-1.5 flex flex-wrap items-start justify-between gap-4">
        <h1 className="oc-title font-serif text-[38px] font-medium leading-none text-[hsl(var(--foreground))]">{title}</h1>

        <div className="flex items-start gap-4">
          {status || meta ? (
            <div className="text-right">
              {status ? <p className="text-sm font-medium text-[hsl(var(--text-2))]">{status}</p> : null}
              {meta ? <p className="mt-1 text-xs tabular-nums text-[hsl(var(--text-3))]">{meta}</p> : null}
            </div>
          ) : null}
          {actions ? <div className="flex shrink-0 items-center gap-2">{actions}</div> : null}
        </div>
      </div>

      <div
        aria-hidden="true"
        className="mt-4 h-px w-full"
        style={{
          background:
            'linear-gradient(90deg, hsl(var(--gold)), hsl(var(--brand)) 18%, hsl(var(--border-strong)) 52%, transparent 96%)',
        }}
      />
    </header>
  )
}
