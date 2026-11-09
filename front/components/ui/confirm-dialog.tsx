'use client'

import * as React from 'react'

import { Button } from '@/components/ui/button'
import { DialogShell } from '@/components/ui/dialog-shell'

/**
 * ConfirmDialog —— 设计系统 D.4 / 信息架构（二次确认）
 *
 * 用于不可逆或会花钱/会发消息的动作：停止投递、强制复位、删除模板、覆盖模板。
 * 关键规则：**默认焦点在「取消」**（Nielsen H5 防错）；Esc 关闭；Tab 在弹窗内循环；
 * 危险动作的最终按钮才用 `danger-solid`，警告级用 `warning` 文案 + secondary 按钮。
 *
 * 外壳统一使用 DialogShell：portal、焦点陷阱、背景 inert、锁滚动由外壳负责。
 */

export type ConfirmTone = 'danger' | 'warning'

export function ConfirmDialog({
  open,
  tone = 'danger',
  title,
  description,
  confirmText = '确认',
  cancelText = '取消',
  busy = false,
  onConfirm,
  onCancel,
}: {
  open: boolean
  tone?: ConfirmTone
  title: string
  description?: React.ReactNode
  confirmText?: string
  cancelText?: string
  busy?: boolean
  onConfirm: () => void
  onCancel: () => void
}) {
  const descriptionText = typeof description === 'string' ? description : undefined

  return (
    <DialogShell
      open={open}
      onClose={onCancel}
      title={title}
      description={descriptionText}
      tone={tone === 'danger' ? 'danger' : 'default'}
      dismissable={!busy}
      closeOnScrim={false}
      width={420}
    >
      {description && !descriptionText ? (
        <div className="mt-3 text-sm leading-5 text-[hsl(var(--text-3))]">{description}</div>
      ) : null}
      <div className="mt-5 flex items-center justify-end gap-2">
        <Button
          type="button"
          size="md"
          variant="secondary"
          data-autofocus
          disabled={busy}
          onClick={onCancel}
        >
          {cancelText}
        </Button>
        <Button
          type="button"
          size="md"
          variant={tone === 'danger' ? 'danger-solid' : 'primary'}
          disabled={busy}
          onClick={onConfirm}
        >
          {busy ? '处理中…' : confirmText}
        </Button>
      </div>
    </DialogShell>
  )
}
