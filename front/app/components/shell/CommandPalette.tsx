'use client'

import { useEffect, useMemo, useRef, useState } from 'react'
import { useRouter } from 'next/navigation'
import { CornerDownLeft, Search } from 'lucide-react'

import { commandEntries, type CommandEntry } from '@/app/lib/spaces'
import { DialogShell } from '@/components/ui/dialog-shell'

/**
 * 跳转面板：输入功能名/旧路径，覆盖 5 空间 + 13 个旧入口。
 * 键盘：↑↓ 移动、Enter 跳转、Esc 关闭；不触发任何写操作。
 */
export default function CommandPalette({ open, onClose }: { open: boolean; onClose: () => void }) {
  const router = useRouter()
  const inputRef = useRef<HTMLInputElement | null>(null)
  const [query, setQuery] = useState('')
  const [activeIndex, setActiveIndex] = useState(0)

  const entries = useMemo(() => commandEntries(), [])
  const filtered = useMemo(() => {
    const key = query.trim().toLowerCase()
    if (!key) return entries
    return entries.filter((entry) =>
      `${entry.label} ${entry.detail} ${entry.keywords}`.toLowerCase().includes(key)
    )
  }, [entries, query])

  const safeIndex = filtered.length === 0 ? -1 : Math.min(activeIndex, filtered.length - 1)

  useEffect(() => {
    if (!open) return
    const timer = window.setTimeout(() => inputRef.current?.focus(), 30)
    return () => window.clearTimeout(timer)
  }, [open])

  if (!open) return null

  const close = () => {
    setQuery('')
    setActiveIndex(0)
    onClose()
  }

  const choose = (entry: CommandEntry) => {
    setQuery('')
    setActiveIndex(0)
    onClose()
    router.push(entry.target)
  }

  const handleKeyDown = (event: React.KeyboardEvent<HTMLInputElement>) => {
    if (event.key === 'Escape') {
      event.preventDefault()
      close()
      return
    }
    if (event.key === 'ArrowDown') {
      event.preventDefault()
      setActiveIndex((prev) => (filtered.length ? (prev + 1) % filtered.length : 0))
      return
    }
    if (event.key === 'ArrowUp') {
      event.preventDefault()
      setActiveIndex((prev) => (filtered.length ? (prev - 1 + filtered.length) % filtered.length : 0))
      return
    }
    if (event.key === 'Enter') {
      event.preventDefault()
      const entry = filtered[safeIndex]
      if (entry) choose(entry)
    }
  }

  const groups: Array<'空间' | '旧入口'> = ['空间', '旧入口']

  return (
    <DialogShell
      open={open}
      onClose={close}
      bare
      ariaLabel="跳转面板"
      width={560}
      closeOnScrim
      panelClassName="overflow-hidden"
    >
      <div className="flex items-center gap-2 border-b px-4 py-3" style={{ borderColor: 'var(--v3-line)' }}>
        <Search aria-hidden="true" className="size-4 text-[var(--v3-muted)]" />
        <input
          ref={inputRef}
          value={query}
          data-autofocus
          onChange={(event) => {
            setQuery(event.target.value)
            setActiveIndex(0)
          }}
          onKeyDown={handleKeyDown}
          placeholder="输入功能名或旧路径，如 rules、/boss/reviews"
          aria-label="搜索功能"
          className="min-w-0 flex-1 bg-transparent text-sm outline-none placeholder:text-[var(--v3-muted)]"
        />
        <kbd className="rounded border px-1.5 py-0.5 text-[11px] text-[var(--v3-muted)]" style={{ borderColor: 'var(--v3-line)' }}>
          Esc
        </kbd>
      </div>

      <div className="max-h-[52vh] overflow-y-auto p-2">
        {filtered.length === 0 ? (
          <p className="px-3 py-6 text-sm text-[var(--v3-muted)]">没有匹配的入口</p>
        ) : (
          groups.map((group) => {
            const groupEntries = filtered.filter((entry) => entry.group === group)
            if (groupEntries.length === 0) return null
            return (
              <div key={group} className="mb-2">
                <p className="px-2 py-1 text-[11px] font-semibold tracking-[0.08em] text-[var(--v3-muted)]">{group}</p>
                {groupEntries.map((entry) => {
                  const index = filtered.indexOf(entry)
                  const active = index === safeIndex
                  return (
                    <button
                      key={entry.id}
                      type="button"
                      onMouseEnter={() => setActiveIndex(index)}
                      onClick={() => choose(entry)}
                      className="flex w-full items-center gap-3 rounded-[10px] px-2.5 py-2 text-left text-sm transition-colors"
                      style={
                        active
                          ? { backgroundColor: 'var(--accent-soft)', color: 'var(--v3-accent)' }
                          : undefined
                      }
                    >
                      <span className="min-w-0 flex-1">
                        <span className="block truncate font-medium">{entry.label}</span>
                        <span className="block truncate text-xs text-[var(--v3-muted)]">{entry.detail}</span>
                      </span>
                      {active ? <CornerDownLeft aria-hidden="true" className="size-3.5 shrink-0" /> : null}
                    </button>
                  )
                })}
              </div>
            )
          })
        )}
      </div>
    </DialogShell>
  )
}
