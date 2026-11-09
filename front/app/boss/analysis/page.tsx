'use client'

import LegacyRedirect from '@/app/components/shell/LegacyRedirect'

/** 旧路由占位：客户端重定向到新空间并保留查询参数。 */
export default function LegacyPage() {
  return <LegacyRedirect slug="analysis" />
}
