'use client'

import Link from 'next/link'
import { ArrowRight } from 'lucide-react'

import { V2PageHeader } from '@/app/components/V2PageHeader'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { InlineAlert } from '@/components/ui/inline-alert'
import { StatusTag } from '@/components/ui/status-tag'

/**
 * 后续跟进 · 措施清单（规划）/boss/followup-strategy
 *
 * D1 只读说明页：投递是投递，跟进是另一条线。
 * 本页只列出现有/规划的跟进措施，不执行发送、不修改投递模板。
 * - 「当前已有」沿用旧行为，不在 D1 范围；
 * - 「后续待做」暂缓，等后续批次再做；
 * - D1 模板里的「采集策略」只影响采集，不含任何跟进策略。
 */

type MeasureStatus = 'existing' | 'planned' | 'not-executed'

interface Measure {
  title: string
  description: string
  status: MeasureStatus
}

const STATUS_META: Record<MeasureStatus, { tone: 'ok' | 'warn' | 'idle'; label: string }> = {
  existing: { tone: 'ok', label: '已有' },
  planned: { tone: 'warn', label: '规划中' },
  'not-executed': { tone: 'idle', label: '尚未执行' },
}

/** 当前已有（沿用，不在 D1 范围） */
const EXISTING_MEASURES: Measure[] = [
  {
    title: '跟进建议生成',
    description: '扫描符合条件的岗位生成 SUGGESTED 跟进建议；只生成建议，绝不自动发送。',
    status: 'existing',
  },
  {
    title: '人工批准 / 拒绝',
    description: '在「跟进待办」中人工批准或拒绝；批准/拒绝携带 expectedVersion，冲突时提示刷新。',
    status: 'existing',
  },
  {
    title: '下一轮执行',
    description: '批准不会立即发送，只允许下一轮任务在满足条件时执行（默认必须存在已批准计划）。',
    status: 'existing',
  },
  {
    title: '跟进间隔 / 次数上限',
    description: '后端按最小跟进间隔与最大跟进次数约束，避免对同一联系人重复打扰。',
    status: 'existing',
  },
  {
    title: '回复 / 接管 / 拒绝会停止',
    description: '联系人被标记为已回复、已拒绝、人工接管或停止联系后，该联系人已批准的跟进计划会失效。',
    status: 'existing',
  },
  {
    title: '图片补发计划',
    description: '文字已确认但图片失败/未知时可生成 IMAGE_RESEND 计划；当前只生成计划，不自动执行。',
    status: 'existing',
  },
]

/** 后续待做（暂缓） */
const PLANNED_MEASURES: Measure[] = [
  {
    title: '自动读取 BOSS 回复并更新联系人状态',
    description: '当前联系人回复状态由人工标记，不读取招聘方回复内容；后续批次再评估是否自动读取。',
    status: 'planned',
  },
  {
    title: '跟进前回复检查',
    description: '执行跟进前再次确认联系人没有新回复，避免打断已进入沟通的对话。',
    status: 'planned',
  },
  {
    title: '模板级跟进策略（间隔 / 次数 / 自动跟进）',
    description: 'D1 模板只包含采集策略，不包含跟进策略；跟进策略留待后续批次单独设计。',
    status: 'planned',
  },
  {
    title: '已批准图片补发自动执行',
    description: '当前图片补发只生成待办计划，批准后也不会自动发送，留待人工/后续批次处理。',
    status: 'not-executed',
  },
  {
    title: '跟进效果统计',
    description: '跟进后的回复率/约面率等效果指标统计，暂不在统计看板范围内。',
    status: 'planned',
  },
]

function MeasureRow({ measure }: { measure: Measure }) {
  const meta = STATUS_META[measure.status]
  return (
    <li className="flex flex-col gap-1 border-t border-hairline py-3 first:border-t-0 first:pt-0 last:pb-0">
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-sm font-medium text-foreground">{measure.title}</span>
        <StatusTag tone={meta.tone}>{meta.label}</StatusTag>
      </div>
      <p className="text-sm leading-5 text-[hsl(var(--text-2))]">{measure.description}</p>
    </li>
  )
}

export default function FollowupStrategyPage() {
  return (
    <div className="space-y-5">
      <V2PageHeader
        eyebrow="复盘 · 跟进"
        title="后续跟进 · 措施清单（规划）"
        status="只读说明"
        meta="投递是投递，跟进是另一条线"
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <Link
              href="/boss/followups"
              className="inline-flex h-8 items-center gap-1 rounded-control border border-border bg-card px-3 text-sm font-medium text-foreground transition-colors hover:bg-surface-inset"
            >
              跟进待办
              <ArrowRight aria-hidden="true" className="size-3.5" />
            </Link>
            <Link
              href="/boss/contacts"
              className="inline-flex h-8 items-center gap-1 rounded-control border border-border bg-card px-3 text-sm font-medium text-foreground transition-colors hover:bg-surface-inset"
            >
              联系人
              <ArrowRight aria-hidden="true" className="size-3.5" />
            </Link>
          </div>
        }
      />

      <InlineAlert variant="info">
        本页只列措施，不执行发送、不修改投递模板；跟进发送仍需人工批准，默认不自动发送
      </InlineAlert>

      <Card>
        <CardHeader>
          <CardTitle>当前已有（沿用，不在 D1 范围）</CardTitle>
          <CardDescription>
            这些是已实现的跟进闭环能力，本次 D1 不改动；入口在「跟进待办」与「联系人」。
          </CardDescription>
        </CardHeader>
        <CardContent>
          <ul>
            {EXISTING_MEASURES.map((measure) => (
              <MeasureRow key={measure.title} measure={measure} />
            ))}
          </ul>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>后续待做（暂缓）</CardTitle>
          <CardDescription>
            这些能力尚未纳入 D1；D1 的模板字段只有「采集策略」，与跟进无关。
          </CardDescription>
        </CardHeader>
        <CardContent>
          <ul>
            {PLANNED_MEASURES.map((measure) => (
              <MeasureRow key={measure.title} measure={measure} />
            ))}
          </ul>
        </CardContent>
      </Card>

      <div className="rounded-card border border-hairline bg-surface-inset px-4 py-3 text-sm leading-6 text-[hsl(var(--text-2))]">
        <p>
          投递模板不包含跟进策略：模板编辑器里的「采集策略（仅本模板）」只影响每关键词候选上限、单关键词采集时长与无新增判定；
          每日首次沟通上限由全局预算统一控制、所有模板共享，不随模板变化。
        </p>
        <p className="mt-1.5">
          需要管理模板采集策略请到{' '}
          <Link href="/boss/templates" className="font-medium text-foreground underline underline-offset-2">
            配置中心 · 方向模板
          </Link>
          ；需要处理跟进建议请到{' '}
          <Link href="/boss/followups" className="font-medium text-foreground underline underline-offset-2">
            跟进待办
          </Link>
          。
        </p>
      </div>
    </div>
  )
}
