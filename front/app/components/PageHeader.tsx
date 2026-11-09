import type { ReactNode } from 'react'

/**
 * PageHeader —— 设计系统 D.2 + H.1.3 + H.5（signature S1 / S2）
 *
 * 形态：贴内容的标题行 + 底部渐隐分隔线。无卡片容器、无图标牌坊、无动画。
 * 高度预算（主理人裁定，优先于 v1.1 的 82/92）：
 *   非总览页  块 69px（标题 36 + 4 + 副标题 18 + pb 10 + hairline 1）+ mb-5 20 = 89px
 *   总览页    块 73px（标题 44 + 4 + 副标题 18 + pb 6 + hairline 1）+ mb-5 20 = 93px
 *
 * props：
 * - title（必填）：页面 H1
 * - meta：副标题行（≤1 行 ≤40 字，`·` 分隔元信息，不加句号）
 * - eyebrow：11px / +0.12em / 600 的小标签（全站仅 3 处，见 H.1.3）
 * - actions：右侧操作区（只用 sm 按钮，最多 1 个 primary）
 * - back：编辑器等「同路视图切换」的返回入口（ghost sm + 左箭头）
 * - size：普通页 title(30/36)，/boss 总览 display(40/44)
 * - tone：存在 fail-closed / danger 级错误时竖线换 danger（S1 的唯一动态用法）
 */
export default function PageHeader({
  title,
  meta,
  metaAction,
  titleAccent,
  eyebrow,
  actions,
  back,
  size = 'title',
  tone = 'default',
}: {
  title: ReactNode
  meta?: ReactNode
  /** meta 行最右侧的次要动作（如「管理模板 →」，`ml-auto` + shrink-0） */
  metaAction?: ReactNode
  /** H1 与 meta 之间的装饰条（如 /boss 的 120×3 渐变切口），不参与语义 */
  titleAccent?: ReactNode
  eyebrow?: ReactNode
  actions?: ReactNode
  back?: ReactNode
  size?: 'title' | 'display'
  tone?: 'default' | 'danger'
}) {
  const isDisplay = size === 'display'
  return (
    <div className="mb-5">
      <div className={`flex items-start justify-between gap-6 ${isDisplay ? 'pb-0.5' : 'pb-2.5'}`}>
        <header className="relative min-w-0 flex-1 pl-4">
          {/* signature S1：2px × 40px 黄铜竖线，不占布局 */}
          <span
            aria-hidden="true"
            className={`absolute left-0 top-0.5 w-0.5 rounded-[1px] ${
              isDisplay ? 'h-11' : 'h-10'
            } ${tone === 'danger' ? 'bg-danger' : 'bg-brass dark:bg-brass-dark'}`}
          />
          {back ? <div className="mb-1.5">{back}</div> : null}
          {eyebrow ? (
            <p className="mb-1 text-xs font-semibold tracking-[0.12em] text-clay-600 dark:text-clay-500">
              {eyebrow}
            </p>
          ) : null}
          <h1
            className={`${
              isDisplay ? 'text-display' : 'text-title'
            } font-semibold text-ink-900 dark:text-sand-100`}
          >
            {title}
          </h1>
          {titleAccent ? <div className="-mt-1.5 mb-0 leading-[0]">{titleAccent}</div> : null}

          {meta || metaAction ? (
            <div className="mt-1.5 flex min-w-0 items-center justify-between gap-3 text-sm text-clay-600 dark:text-clay-500">
              {meta ? <div className="min-w-0 truncate">{meta}</div> : <span />}
              {metaAction ? <div className="shrink-0">{metaAction}</div> : null}
            </div>
          ) : null}
        </header>
        {actions ? (
          <div className="flex shrink-0 flex-wrap items-center justify-end gap-2">{actions}</div>
        ) : null}
      </div>
      {/* signature S2：区块级渐隐分隔线，不用 border-b 实线 */}
      <div className="divider-fade" />
    </div>
  )
}
