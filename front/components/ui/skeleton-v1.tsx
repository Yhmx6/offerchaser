import type { CSSProperties, ReactNode } from 'react';

export interface SkeletonProps {
  width?: number | string;
  height?: number | string;
  radius?: number;
  /** 动画延迟（毫秒），相邻块错开更自然 */
  delay?: number;
  style?: CSSProperties;
}

/** 单个骨架块。纯装饰，对读屏隐藏。 */
export function Skeleton({ width, height = 16, radius = 10, delay = 0, style }: SkeletonProps) {
  return (
    <div
      aria-hidden="true"
      className="oc-skel"
      style={{ width, height, borderRadius: radius, animationDelay: delay ? `${delay}ms` : undefined, ...style }}
    />
  );
}

/**
 * 骨架区域外壳：给读屏一条“加载中”，并标记 aria-busy。
 * 加载结束后整体换成真实内容，不要保留骨架。
 */
export function SkeletonRegion({ label = '加载中', children }: { label?: string; children: ReactNode }) {
  return (
    <div role="status" aria-busy="true" aria-label={label} style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      <span className="oc-sr">{label}…</span>
      {children}
    </div>
  );
}

/** 数字卡网格（今日 / 洞察） */
export function SkeletonStatGrid({ count = 4 }: { count?: number }) {
  return (
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(230px, 1fr))', gap: 16 }}>
      {Array.from({ length: count }, (_, i) => (
        <Skeleton key={i} height={138} radius={20} delay={i * 100} />
      ))}
    </div>
  );
}

/** 表格（记录） */
export function SkeletonTable({ rows = 6 }: { rows?: number }) {
  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        padding: '8px 0',
        borderRadius: 20,
        background: 'var(--surface)',
        border: '1px solid var(--v3-line)',
      }}
    >
      {Array.from({ length: rows }, (_, i) => (
        <Skeleton key={i} height={40} delay={i * 80} style={{ margin: '8px 20px' }} />
      ))}
    </div>
  );
}

/** 分栏（待办 / 配置：左栏 + 列表 + 详情） */
export function SkeletonSplit() {
  return (
    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 16 }}>
      <Skeleton height={260} radius={20} style={{ flex: '1 1 200px', maxWidth: 260 }} />
      <div style={{ flex: '1 1 330px', display: 'flex', flexDirection: 'column', gap: 10 }}>
        {Array.from({ length: 4 }, (_, i) => (
          <Skeleton key={i} height={96} radius={14} delay={i * 100} />
        ))}
      </div>
      <Skeleton height={420} radius={20} delay={150} style={{ flex: '1.5 1 460px' }} />
    </div>
  );
}
