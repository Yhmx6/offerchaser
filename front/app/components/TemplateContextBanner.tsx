'use client'

import Link from 'next/link'
import { useEffect, useState } from 'react'
import { BiInfoCircle } from 'react-icons/bi'
import { getTemplates } from '@/lib/api'
import type { TemplateView } from '@/lib/boss-types'

/**
 * 配置中心上下文横幅（B5 配置中心整合）。
 *
 * 后端已把「生效模板」设为最高优先级：各编辑页保存会自动写回当前模板，
 * 任务启动前会按生效模板校正当前配置。本组件放在各编辑页顶部，
 * 提示用户「当前正在改哪个模板」以及保存/启动时的优先级规则。
 *
 * 加载失败时静默不渲染，绝不阻塞页面。
 */
export default function TemplateContextBanner({
  scope,
  className = '',
}: {
  /** 当前页面负责的配置范围，如「搜索与投递」「AI 配置」「筛选规则」 */
  scope: string
  className?: string
}) {
  const [active, setActive] = useState<TemplateView | null>(null)
  const [loaded, setLoaded] = useState(false)

  useEffect(() => {
    let cancelled = false
    getTemplates()
      .then((data) => {
        if (cancelled) return
        setActive(data?.active ?? null)
        setLoaded(true)
      })
      .catch(() => {
        // 静默失败：横幅不是关键路径，不能阻塞页面
        if (!cancelled) setLoaded(true)
      })
    return () => {
      cancelled = true
    }
  }, [])

  if (!loaded) return null

  return (
    <div
      className={`rounded-control border border-info-border border-l-2 border-l-info bg-info-bg px-3 py-2 text-sm text-info ${className}`}
    >
      {active ? (
        <p className="flex flex-wrap items-center gap-x-1.5 gap-y-1">
          <BiInfoCircle className="shrink-0" />
          <span className="font-medium">{scope}：</span>
          <span>
            当前模板 <span className="font-semibold">{active.name}</span>
            {active.direction ? `（${active.direction}）` : ''} · 本页保存会自动同步到该模板；任务启动前以模板为准。
          </span>
          <span className="opacity-80">切换/管理模板 →</span>
          <Link
            href="/boss/templates"
            className="font-medium underline underline-offset-2 hover:no-underline"
          >
            配置中心
          </Link>
        </p>
      ) : (
        <p className="flex flex-wrap items-center gap-x-1.5 gap-y-1">
          <BiInfoCircle className="shrink-0" />
          <span className="font-medium">{scope}：</span>
          <span>当前没有生效模板，任务将使用当前配置；建议到</span>
          <Link
            href="/boss/templates"
            className="font-medium underline underline-offset-2 hover:no-underline"
          >
            配置中心
          </Link>
          <span>创建并激活模板。</span>
        </p>
      )}
    </div>
  )
}
