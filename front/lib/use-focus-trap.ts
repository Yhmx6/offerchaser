'use client';

import { useEffect, useRef } from 'react';

const FOCUSABLE = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled]):not([type="hidden"])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(',');

function focusables(root: HTMLElement): HTMLElement[] {
  return Array.from(root.querySelectorAll<HTMLElement>(FOCUSABLE)).filter(
    (el) => el.getClientRects().length > 0,
  );
}

export interface FocusTrapOptions {
  /** 按 Esc 时调用。不传则 Esc 不关闭（例如登录门）。 */
  onEscape?: () => void;
  /** 打开时优先聚焦的元素选择器，默认 `[data-autofocus]`，其次是第一个可聚焦元素。 */
  initialFocus?: string;
}

/**
 * 焦点陷阱：
 * 1. 打开时把焦点移进面板（破坏性确认弹窗请在“取消”按钮上加 data-autofocus）；
 * 2. Tab / Shift+Tab 只在面板内循环；点到遮罩后焦点会被拉回面板；
 * 3. 关闭时把焦点还给打开前的元素（若它还在文档里）。
 *
 * 一次只应有一个陷阱处于 active（不支持弹窗叠弹窗）。
 */
export function useFocusTrap<T extends HTMLElement>(active: boolean, options: FocusTrapOptions = {}) {
  const ref = useRef<T | null>(null);
  const escapeRef = useRef<(() => void) | undefined>(options.onEscape);
  const initialSelector = options.initialFocus ?? '[data-autofocus]';

  useEffect(() => {
    escapeRef.current = options.onEscape;
  });

  useEffect(() => {
    if (!active) return;
    const root = ref.current;
    if (!root) return;

    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null;

    const first = root.querySelector<HTMLElement>(initialSelector) ?? focusables(root)[0] ?? root;
    if (first === root && !root.hasAttribute('tabindex')) root.setAttribute('tabindex', '-1');
    first.focus();

    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && escapeRef.current) {
        event.stopPropagation();
        escapeRef.current();
        return;
      }
      if (event.key !== 'Tab') return;

      const items = focusables(root);
      if (items.length === 0) {
        event.preventDefault();
        root.focus();
        return;
      }
      const firstEl = items[0];
      const lastEl = items[items.length - 1];
      const current = document.activeElement;
      if (event.shiftKey && (current === firstEl || !root.contains(current))) {
        event.preventDefault();
        lastEl.focus();
      } else if (!event.shiftKey && (current === lastEl || !root.contains(current))) {
        event.preventDefault();
        firstEl.focus();
      }
    };

    const onFocusIn = (event: FocusEvent) => {
      if (event.target instanceof Node && !root.contains(event.target)) {
        (focusables(root)[0] ?? root).focus();
      }
    };

    document.addEventListener('keydown', onKeyDown, true);
    document.addEventListener('focusin', onFocusIn);
    return () => {
      document.removeEventListener('keydown', onKeyDown, true);
      document.removeEventListener('focusin', onFocusIn);
      if (previous && document.contains(previous)) previous.focus();
    };
  }, [active, initialSelector]);

  return ref;
}
