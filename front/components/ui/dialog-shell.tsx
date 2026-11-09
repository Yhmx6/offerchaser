'use client';

import { useEffect, useId, type CSSProperties, type MouseEvent, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { useFocusTrap } from '../../lib/use-focus-trap';

export interface DialogShellProps {
  open: boolean;
  onClose: () => void;
  /** 标题；bare 模式下可省略 */
  title?: string;
  description?: ReactNode;
  /** 无默认标题/描述，由调用方自带完整内容（AI 向导、跳转面板） */
  bare?: boolean;
  /** 无标题时提供给读屏的名称 */
  ariaLabel?: string;
  /** 额外面板类，例如 max-height / padding 覆盖 */
  panelClassName?: string;
  /** 显示右上角关闭按钮 */
  showClose?: boolean;
  /** false：不能用 Esc 或点遮罩关闭（例如登录门）。默认 true。 */
  dismissable?: boolean;
  /** 点遮罩是否关闭。确认类弹窗默认 false，防止误触。 */
  closeOnScrim?: boolean;
  tone?: 'default' | 'danger';
  width?: number;
  children: ReactNode;
}

/**
 * 全站唯一的对话框外壳：portal 到 body、role="dialog" + aria-modal、
 * 焦点陷阱、Esc、背景 inert（读屏和键盘都进不去）、锁滚动。
 *
 * 确认类弹窗约定：把“取消”按钮标上 data-autofocus，默认焦点落在安全选项上。
 * 动作文案、按钮、危险色由调用方放在 children 里，外壳不替业务做决定。
 */
export function DialogShell({
  open,
  onClose,
  title,
  description,
  bare = false,
  ariaLabel,
  panelClassName = '',
  showClose = false,
  dismissable = true,
  closeOnScrim = false,
  tone = 'default',
  width = 480,
  children,
}: DialogShellProps) {
  const titleId = useId();
  const descId = useId();
  const panelRef = useFocusTrap<HTMLDivElement>(open, { onEscape: dismissable ? onClose : undefined });

  useEffect(() => {
    if (!open) return;
    const siblings = Array.from(document.body.children).filter(
      (el): el is HTMLElement => el instanceof HTMLElement && !el.hasAttribute('data-oc-dialog-layer'),
    );
    const previousInert = siblings.map((el) => el.inert);
    siblings.forEach((el) => {
      el.inert = true;
    });
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      siblings.forEach((el, i) => {
        el.inert = previousInert[i];
      });
      document.body.style.overflow = previousOverflow;
    };
  }, [open]);

  if (!open || typeof document === 'undefined') return null;

  const onScrimMouseDown = (event: MouseEvent<HTMLDivElement>) => {
    if (event.target === event.currentTarget && closeOnScrim && dismissable) onClose();
  };

  const panelStyle = { '--oc-dialog-w': `${width}px` } as CSSProperties;

  return createPortal(
    <div data-oc-dialog-layer className="oc-dialog-layer oc-scrim" onMouseDown={onScrimMouseDown}>
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={!ariaLabel && title ? titleId : undefined}
        aria-label={ariaLabel}
        aria-describedby={description ? descId : undefined}
        data-tone={tone}
        className={`oc-dialog oc-pop ${bare ? 'oc-dialog-bare' : ''} ${panelClassName}`}
        style={panelStyle}
      >
        {showClose ? (
          <button
            type="button"
            aria-label="关闭"
            onClick={onClose}
            style={{ position: 'absolute', right: 16, top: 16, width: 32, height: 32, borderRadius: 999, border: '1px solid var(--v3-line)', background: 'var(--surface)', color: 'var(--v3-muted)', cursor: 'pointer' }}
          >
            ×
          </button>
        ) : null}
        {title ? (
          <h3 id={titleId} style={{ margin: 0, fontSize: 18, fontWeight: 600 }}>
            {title}
          </h3>
        ) : null}
        {description ? (
          <p id={descId} style={{ margin: title ? '12px 0 0' : 0, fontSize: 14, lineHeight: 1.65, color: 'var(--v3-muted)' }}>
            {description}
          </p>
        ) : null}
        {children}
      </div>
    </div>,
    document.body,
  );
}
