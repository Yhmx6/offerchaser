'use client';

import Link from 'next/link';
import type { CSSProperties } from 'react';

export interface SpaceTabItem {
  key: string;
  label: string;
  href: string;
  /** 待处理数量；0 / undefined 不渲染 */
  badge?: number;
}

export interface SpaceTabsProps {
  items: SpaceTabItem[];
  activeKey: string;
  ariaLabel?: string;
}

/**
 * 顶栏空间标签。N 个标签等分宽度，滑块宽度 = 一列、按“自身宽度的百分比”平移，
 * 因此在任何容器宽度下都对齐，不再依赖固定的像素宽度。
 * 窄屏的换行由 increment-v1.css 里的 `.oc-tabs`（≤640px 独占一行）处理。
 */
export function SpaceTabs({ items, activeKey, ariaLabel = '主要空间' }: SpaceTabsProps) {
  const activeIndex = Math.max(
    0,
    items.findIndex((item) => item.key === activeKey),
  );
  const style = { '--oc-tabs-n': items.length } as CSSProperties;

  return (
    <nav className="oc-tabs" aria-label={ariaLabel} style={style}>
      <span className="oc-tabs-ink" aria-hidden="true" style={{ transform: `translateX(${activeIndex * 100}%)` }} />
      {items.map((item) => (
        <Link
          key={item.key}
          href={item.href}
          className="oc-tab"
          aria-current={item.key === activeKey ? 'page' : undefined}
        >
          <span>{item.label}</span>
          {item.badge && item.badge > 0 ? (
            <>
              <span className="oc-badge" aria-hidden="true">
                {item.badge}
              </span>
              <span className="oc-sr">，{item.badge} 项待处理</span>
            </>
          ) : null}
        </Link>
      ))}
    </nav>
  );
}
