/** V3 空间与旧路由映射（纯前端导航，不发请求）。 */

export type SpaceKey = 'today' | 'inbox' | 'ledger' | 'setup' | 'insights'

export interface SpaceItem {
  key: SpaceKey
  href: string
  label: string
  /** 命令面板/辅助文案 */
  hint: string
}

export const SPACES: SpaceItem[] = [
  { key: 'today', href: '/boss', label: '今日', hint: '现在跑得怎么样、有什么要我管' },
  { key: 'inbox', href: '/boss/inbox', label: '待办', hint: '需要我拍板的事：核对/放行/跟进/补发' },
  { key: 'ledger', href: '/boss/ledger', label: '记录', hint: '投递明细/评估/任务/联系人' },
  { key: 'setup', href: '/boss/setup', label: '配置', hint: '方向模板/规则/AI/模型服务' },
  { key: 'insights', href: '/boss/insights', label: '洞察', hint: '统计看板与跟进策略' },
]

export type LegacySlug =
  | 'flow'
  | 'tasks'
  | 'evaluations'
  | 'attempts'
  | 'contacts'
  | 'followups'
  | 'followup-strategy'
  | 'analysis'
  | 'reviews'
  | 'rules'
  | 'templates'
  | 'advanced'
  | 'ai-config'

export interface LegacyEntry {
  slug: LegacySlug
  /** 旧路径，命令面板里原样展示，书签可继续用 */
  legacyPath: string
  label: string
  space: SpaceKey
}

export const LEGACY_ENTRIES: LegacyEntry[] = [
  { slug: 'flow', legacyPath: '/boss/flow', label: '投递流程', space: 'today' },
  { slug: 'reviews', legacyPath: '/boss/reviews', label: '人工放行', space: 'inbox' },
  { slug: 'followups', legacyPath: '/boss/followups', label: '跟进待办', space: 'inbox' },
  { slug: 'attempts', legacyPath: '/boss/attempts', label: '投递明细 / 人工核对', space: 'ledger' },
  { slug: 'evaluations', legacyPath: '/boss/evaluations', label: '评估记录', space: 'ledger' },
  { slug: 'tasks', legacyPath: '/boss/tasks', label: '任务记录', space: 'ledger' },
  { slug: 'contacts', legacyPath: '/boss/contacts', label: '联系人', space: 'ledger' },
  { slug: 'templates', legacyPath: '/boss/templates', label: '配置中心 / 方向模板', space: 'setup' },
  { slug: 'rules', legacyPath: '/boss/rules', label: '筛选规则（高级）', space: 'setup' },
  { slug: 'ai-config', legacyPath: '/ai-config', label: 'AI 配置（高级）', space: 'setup' },
  { slug: 'advanced', legacyPath: '/boss/advanced', label: '高级调试', space: 'setup' },
  { slug: 'analysis', legacyPath: '/boss/analysis', label: '采集与统计', space: 'insights' },
  { slug: 'followup-strategy', legacyPath: '/boss/followup-strategy', label: '跟进策略', space: 'insights' },
]

function appendSearch(base: string, params: URLSearchParams, skip: string[] = []): string {
  const rest = new URLSearchParams(params)
  for (const key of skip) rest.delete(key)
  const text = rest.toString()
  if (!text) return base
  return `${base}${base.includes('?') ? '&' : '?'}${text}`
}

/**
 * 旧路由 → 新空间映射（保留原查询参数，书签不失效）。
 * 特殊：attempts 的 UNKNOWN 核对/图片补发属于待办；其余明细进记录。
 */
export function legacyTarget(slug: LegacySlug, rawSearch: string): string {
  const search = rawSearch.startsWith('?') ? rawSearch.slice(1) : rawSearch
  const params = new URLSearchParams(search)

  switch (slug) {
    case 'flow':
      return appendSearch('/boss?view=flow', params)
    case 'reviews':
      return appendSearch('/boss/inbox?kind=release', params)
    case 'followups':
      return appendSearch('/boss/inbox?kind=followup', params)
    case 'attempts': {
      const textStatus = params.get('textStatus')
      const imageStatus = params.get('imageStatus')
      if (textStatus === 'UNKNOWN' || (!textStatus && imageStatus === 'UNKNOWN')) {
        return appendSearch('/boss/inbox?kind=resolve', params, ['textStatus', 'imageStatus'])
      }
      if (textStatus === 'CONFIRMED' && (imageStatus === 'FAILED' || imageStatus === 'UNKNOWN')) {
        return appendSearch('/boss/inbox?kind=resend', params, ['textStatus', 'imageStatus'])
      }
      return appendSearch('/boss/ledger?entity=attempts', params)
    }
    case 'evaluations':
      return appendSearch('/boss/ledger?entity=evaluations', params)
    case 'tasks':
      return appendSearch('/boss/ledger?entity=runs', params)
    case 'contacts':
      return appendSearch('/boss/ledger?entity=contacts', params)
    case 'templates':
      return appendSearch('/boss/setup', params)
    case 'rules':
      return appendSearch('/boss/setup?tab=rules', params)
    case 'ai-config':
      return appendSearch('/boss/setup?tab=ai', params)
    case 'advanced':
      return appendSearch('/boss/setup?tab=advanced', params)
    case 'analysis':
      return appendSearch('/boss/insights', params)
    case 'followup-strategy':
      return appendSearch('/boss/insights?tab=strategy', params)
    default:
      return '/boss'
  }
}

/** 命令面板条目：5 空间 + 13 旧入口（覆盖全部历史页面）。 */
export interface CommandEntry {
  id: string
  label: string
  detail: string
  target: string
  keywords: string
  group: '空间' | '旧入口'
}

export function commandEntries(): CommandEntry[] {
  const spaces: CommandEntry[] = SPACES.map((space) => ({
    id: `space-${space.key}`,
    label: space.label,
    detail: space.hint,
    target: space.href,
    keywords: `${space.key} ${space.label} ${space.href}`,
    group: '空间',
  }))
  const legacy: CommandEntry[] = LEGACY_ENTRIES.map((entry) => ({
    id: `legacy-${entry.slug}`,
    label: entry.label,
    detail: entry.legacyPath,
    target: legacyTarget(entry.slug, ''),
    keywords: `${entry.slug} ${entry.label} ${entry.legacyPath}`,
    group: '旧入口',
  }))
  return [...spaces, ...legacy]
}
