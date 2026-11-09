import type { ReactNode } from 'react'
import { GlobalRunBar } from './boss/GlobalRunBar'

/**
 * ContentArea —— 设计系统 C.1 + H.2.3（主理人裁定：居中对称容器）
 *
 * - 删除了 framer-motion 的 motion.div 与 `key={pathname}`：`key` 会让每次路由切换
 *   卸载重挂整棵子树，SSE 订阅（/boss/flow 等）会跟着重连。
 * - 入场动效改为纯 CSS 类 `.animate-page-in`（240ms opacity + 8px，仅一层容器）。
 * - `.accent-*` / `.content-bg` 已删除：画布渐层与噪点只存在于 html 层。
 * - 容器：`mx-auto max-w-[1240px] px-8 pt-8 pb-10`（居中、对称，1600 视口下左右各 60）。
 */
export default function ContentArea({ children }: { children: ReactNode }) {
  return (
    // min-w-0：flex 子项默认 min-width:auto，宽表格（min-w-[1180px]）会把 <main> 顶宽，
    // 导致 1280 视口整页横向滚动。加 min-w-0 后横向滚动被限制在表格自身的 overflow-x-auto 里。
    <main id="main" tabIndex={-1} className="ml-60 min-h-screen min-w-0 flex-1 focus:outline-none">
      <div className="animate-page-in mx-auto w-full max-w-[1240px] px-8 pb-10 pt-8">
        <GlobalRunBar />
        {children}
      </div>
    </main>
  )
}
