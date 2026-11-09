'use client';

import { useEffect, useMemo, useRef, useState, type ReactNode, type UIEvent } from 'react';

/* ---------- 纯函数：行高索引 ---------- */

interface RowIndex {
  total: number;
  offsetOf(index: number): number;
  /** 包含纵坐标 y 的行号（越界时夹在 0..count-1） */
  indexAt(y: number): number;
}

function fixedIndex(count: number, rowHeight: number): RowIndex {
  return {
    total: count * rowHeight,
    offsetOf: (i) => i * rowHeight,
    indexAt: (y) => Math.min(count - 1, Math.max(0, Math.floor(y / rowHeight))),
  };
}

function variableIndex(count: number, heightOf: (index: number) => number): RowIndex {
  const offsets = new Array<number>(count + 1);
  offsets[0] = 0;
  for (let i = 0; i < count; i += 1) offsets[i + 1] = offsets[i] + heightOf(i);
  return {
    total: offsets[count],
    offsetOf: (i) => offsets[Math.min(count, Math.max(0, i))],
    indexAt: (y) => {
      let lo = 0;
      let hi = count - 1;
      while (lo < hi) {
        const mid = (lo + hi + 1) >> 1;
        if (offsets[mid] <= y) lo = mid;
        else hi = mid - 1;
      }
      return lo;
    },
  };
}

export interface VirtualWindow {
  start: number;
  end: number;
  padTop: number;
  padBottom: number;
  total: number;
}

/** 根据滚动位置算出需要渲染的 [start, end) 区间以及上下占位高度。 */
export function computeWindow(
  index: RowIndex,
  count: number,
  scrollTop: number,
  viewport: number,
  overscan: number,
): VirtualWindow {
  if (count === 0) return { start: 0, end: 0, padTop: 0, padBottom: 0, total: 0 };
  const first = index.indexAt(scrollTop);
  const last = index.indexAt(scrollTop + viewport);
  const start = Math.max(0, first - overscan);
  const end = Math.min(count, last + 1 + overscan);
  return { start, end, padTop: index.offsetOf(start), padBottom: index.total - index.offsetOf(end), total: index.total };
}

/* ---------- 组件 ---------- */

export interface VirtualListProps<T> {
  items: readonly T[];
  /**
   * 固定行高（px），或按行返回高度的函数。
   * 用函数时请用 useCallback 稳定引用，否则每次渲染都会重算 O(n) 索引。
   * 行内“展开详情”这类会改变行高的场景：把展开后的高度如实返回，并保证行内容不超过该高度。
   */
  rowHeight: number | ((item: T, index: number) => number);
  /** 视口高度（px）。需要自适应高度时由外层测量后传入。 */
  height: number;
  overscan?: number;
  getKey: (item: T, index: number) => string | number;
  renderRow: (item: T, index: number) => ReactNode;
  /** 必填：列表的可访问名称，例如“投递明细” */
  ariaLabel: string;
  className?: string;
  emptyState?: ReactNode;
}

/**
 * 无依赖的虚拟滚动列表。
 * 可访问性：role=list + 每行 role=listitem，并带 aria-posinset / aria-setsize，
 * 读屏仍能读出“第 N 项，共 M 项”；容器可聚焦，键盘可用方向键 / PageUp / PageDown 滚动。
 */
export function VirtualList<T>({
  items,
  rowHeight,
  height,
  overscan = 6,
  getKey,
  renderRow,
  ariaLabel,
  className,
  emptyState,
}: VirtualListProps<T>) {
  const [scrollTop, setScrollTop] = useState(0);
  const frame = useRef<number | null>(null);

  const index = useMemo<RowIndex>(() => {
    const count = items.length;
    if (typeof rowHeight === 'number') return fixedIndex(count, rowHeight);
    return variableIndex(count, (i) => rowHeight(items[i], i));
  }, [items, rowHeight]);

  const win = computeWindow(index, items.length, scrollTop, height, overscan);

  useEffect(
    () => () => {
      if (frame.current !== null) cancelAnimationFrame(frame.current);
    },
    [],
  );

  const onScroll = (event: UIEvent<HTMLDivElement>) => {
    const top = event.currentTarget.scrollTop;
    if (frame.current !== null) cancelAnimationFrame(frame.current);
    frame.current = requestAnimationFrame(() => {
      frame.current = null;
      setScrollTop(top);
    });
  };

  if (items.length === 0) return <>{emptyState ?? null}</>;

  const rows: ReactNode[] = [];
  for (let i = win.start; i < win.end; i += 1) {
    const item = items[i];
    const h = typeof rowHeight === 'number' ? rowHeight : rowHeight(item, i);
    rows.push(
      <div
        key={getKey(item, i)}
        role="listitem"
        aria-posinset={i + 1}
        aria-setsize={items.length}
        style={{ height: h, overflow: 'hidden' }}
      >
        {renderRow(item, i)}
      </div>,
    );
  }

  return (
    <div
      role="list"
      aria-label={ariaLabel}
      tabIndex={0}
      className={className}
      onScroll={onScroll}
      style={{ height, overflowY: 'auto', position: 'relative' }}
    >
      <div aria-hidden="true" style={{ height: win.padTop }} />
      {rows}
      <div aria-hidden="true" style={{ height: win.padBottom }} />
    </div>
  );
}
