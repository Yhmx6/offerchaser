'use client'

import { useEffect, useRef, useState } from 'react'

/**
 * 轻量数字入场：只对 KPI 主数字使用。
 * - 数值变化时从旧值平滑到新值，不滚动、不跳动布局；
 * - prefers-reduced-motion 下直接显示目标值；
 * - 大数字仍由父级决定字号，本组件只负责数值。
 */
export function CountUp({ value }: { value: number }) {
  const target = Math.max(0, Number.isFinite(value) ? Math.round(value) : 0)
  const [display, setDisplay] = useState(0)
  const fromRef = useRef(0)

  useEffect(() => {
    if (typeof window === 'undefined') return
    const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    if (reduced) {
      fromRef.current = target
      const frame = requestAnimationFrame(() => setDisplay(target))
      return () => cancelAnimationFrame(frame)
    }

    const from = fromRef.current
    if (from === target) {
      return
    }

    let frame = 0
    const duration = 620
    const started = performance.now()
    const tick = (now: number) => {
      const progress = Math.min(1, (now - started) / duration)
      // easeOutCubic：前段快、末段稳，避免数字长时间抖动
      const eased = 1 - Math.pow(1 - progress, 3)
      const next = Math.round(from + (target - from) * eased)
      setDisplay(next)
      if (progress < 1) {
        frame = requestAnimationFrame(tick)
      } else {
        fromRef.current = target
      }
    }
    frame = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(frame)
  }, [target])

  return <span className="tabular-nums">{display}</span>
}
