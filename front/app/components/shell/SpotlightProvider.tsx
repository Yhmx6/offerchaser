'use client'

import { useEffect } from 'react'

/**
 * 全站光标跟随光韵。
 *
 * 页面里大量卡片是服务端/静态渲染的普通 DOM，不适合逐个绑定 onPointerMove；
 * 这里用一个全局 pointermove 监听，把鼠标相对卡片的位置写入 --mx / --my，
 * 由 CSS 的 .oc-spot / .oc-card:hover 绘制径向光晕。
 *
 * 只处理鼠标，触屏不产生额外样式；用 requestAnimationFrame 合并高频事件。
 */
export default function SpotlightProvider({ children }: { children: React.ReactNode }) {
  useEffect(() => {
    let frame = 0
    let pending: PointerEvent | null = null

    const paint = () => {
      frame = 0
      const event = pending
      pending = null
      if (!event || (event.pointerType && event.pointerType !== 'mouse')) return

      const target = event.target instanceof Element
        ? event.target.closest('.oc-spot, .oc-card, .oc-hero, .oc-kpi')
        : null
      if (!(target instanceof HTMLElement)) return

      const rect = target.getBoundingClientRect()
      target.style.setProperty('--mx', `${event.clientX - rect.left}px`)
      target.style.setProperty('--my', `${event.clientY - rect.top}px`)
    }

    const onPointerMove = (event: PointerEvent) => {
      pending = event
      if (!frame) {
        frame = window.requestAnimationFrame(paint)
      }
    }

    document.addEventListener('pointermove', onPointerMove, { passive: true })
    return () => {
      document.removeEventListener('pointermove', onPointerMove)
      if (frame) window.cancelAnimationFrame(frame)
    }
  }, [])

  return <>{children}</>
}
