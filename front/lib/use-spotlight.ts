'use client';

import { useCallback, type PointerEvent as ReactPointerEvent } from 'react';

/**
 * 卡片“光标跟随高光”：把指针位置写进 --mx / --my，配合 `.oc-spot:hover` 使用。
 * 只在鼠标下生效，触屏不处理。
 *
 * <div className="oc-spot" onPointerMove={onSpot}>…</div>
 */
export function useSpotlight() {
  return useCallback((event: ReactPointerEvent<HTMLElement>) => {
    if (event.pointerType !== 'mouse') return;
    const el = event.currentTarget;
    const rect = el.getBoundingClientRect();
    el.style.setProperty('--mx', `${event.clientX - rect.left}px`);
    el.style.setProperty('--my', `${event.clientY - rect.top}px`);
  }, []);
}
