'use client';

import { useId, useState, type CSSProperties } from 'react';

export interface TrendSeries {
  key: string;
  name: string;
  values: number[];
}

export interface TrendChartProps {
  /** X 轴标签，与各 series.values 一一对应 */
  labels: string[];
  series: TrendSeries[];
  /** 当前绘制的指标 key；数据表会列出全部 series */
  activeKey: string;
  /**
   * 切换范围 / 指标时递增，用来重启折线重绘动画。
   * 不传则只在首次挂载时播放。
   */
  animationKey?: number;
  /** 是否显示“查看数据表”入口（洞察页改为下方明细卡承担） */
  showDataTable?: boolean;
  /** 峰值标签 */
  showPeak?: boolean;
  /** 日均虚线 */
  showAverage?: boolean;
}

const W = 720;
const H = 200;

/**
 * 折线趋势图：
 * - 悬停或键盘聚焦某个时间点，出现竖向参考线 + 提示框（读屏读出“周一，文字确认 28”）；
 * - “查看数据表”展开完整的数据表，作为图表的可读替代；
 * - 没有数据时展示空态，绝不伪造 0 趋势（契约要求）；
 * - 圆点用 HTML 叠加，避免 preserveAspectRatio="none" 把圆拉成椭圆。
 */
export function TrendChart({
  labels,
  series,
  activeKey,
  animationKey = 0,
  showDataTable = true,
  showPeak = false,
  showAverage = false,
}: TrendChartProps) {
  const [hover, setHover] = useState<number | null>(null);
  const [tableOpen, setTableOpen] = useState(false);
  const tableId = useId();
  const gradId = `${tableId.replace(/[^a-zA-Z0-9_-]/g, '')}-area`;

  const active = series.find((s) => s.key === activeKey) ?? series[0];
  const n = active ? Math.min(labels.length, active.values.length) : 0;

  if (!active || n === 0) {
    return <div className="oc-chart-empty">暂无数据</div>;
  }

  const values = active.values.slice(0, n);
  const max = Math.max(...values, 1);
  const min = Math.min(...values);
  const step = n > 1 ? 100 / (n - 1) : 100;
  const hovered = hover !== null && hover < n ? hover : null;

  const points = values.map((v, i) => {
    const x = n > 1 ? i * (W / (n - 1)) : W / 2;
    const y = H - (v / max) * (H - 24) - 8;
    const pct = (x / W) * 100;
    const lo = Math.max(0, pct - step / 2);
    const hi = Math.min(100, pct + step / 2);
    return { x, y, pct, left: `${pct}%`, top: `${(y / H) * 100}%`, hitLeft: `${lo}%`, hitWidth: `${hi - lo}%` };
  });

  const line = points.map((p, i) => `${i ? 'L' : 'M'}${p.x.toFixed(1)} ${p.y.toFixed(1)}`).join(' ');
  const area = `${line} L${W} ${H} L0 ${H} Z`;
  const summary = `${active.name}趋势折线图，共 ${n} 个数据点，最高 ${max}，最低 ${min}。可展开数据表查看全部数值。`;

  let tipStyle: CSSProperties | null = null;
  if (hovered !== null) {
    const p = points[hovered];
    const shift = p.pct < 14 ? '-8%' : p.pct > 86 ? '-92%' : '-50%';
    tipStyle = { left: p.left, top: p.top, transform: `translate(${shift}, -135%)` };
  }

  return (
    <div>
      {showDataTable ? (
        <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 8 }}>
          <button
            type="button"
            aria-expanded={tableOpen}
            aria-controls={tableId}
            onClick={() => setTableOpen((v) => !v)}
            style={{
              height: 32,
              padding: '0 10px',
              borderRadius: 10,
              border: 0,
              background: 'transparent',
              color: 'var(--muted)',
              fontSize: 12,
              cursor: 'pointer',
            }}
          >
            {tableOpen ? '收起数据表' : '查看数据表'}
          </button>
        </div>
      ) : null}

      <div className="oc-chart">
        <svg
          className="oc-chart-svg"
          viewBox={`0 0 ${W} ${H}`}
          width="100%"
          height="220"
          role="img"
          aria-label={summary}
          preserveAspectRatio="none"
        >
          <defs>
            <linearGradient id={gradId} x1="0" y1="0" x2="0" y2="1">
              <stop offset="0" stopColor="var(--oc-chart-color, var(--v3-accent))" stopOpacity="0.3" />
              <stop offset="1" stopColor="var(--oc-chart-color, var(--v3-accent))" stopOpacity="0" />
            </linearGradient>
          </defs>
          <path d="M0 8H720M0 74H720M0 140H720M0 192H720" style={{ fill: 'none', stroke: 'var(--line)', strokeWidth: 1 }} />
          {showAverage ? (
            <line
              x1="0"
              y1={H - (values.reduce((sum, value) => sum + value, 0) / n / max) * (H - 24) - 8}
              x2={W}
              y2={H - (values.reduce((sum, value) => sum + value, 0) / n / max) * (H - 24) - 8}
              stroke="var(--oc-chart-color, var(--v3-accent))"
              strokeDasharray="5 6"
              strokeWidth="1"
              opacity="0.5"
            />
          ) : null}
          <path className="oc-fadein" d={area} style={{ fill: `url(#${gradId})`, stroke: 'none' }} />
          <path
            key={animationKey}
            className={`oc-chart-line ${animationKey % 2 ? 'oc-draw-b' : 'oc-draw-a'}`}
            d={line}
            style={{
              fill: 'none',
              stroke: 'var(--oc-chart-color, var(--v3-accent))',
              strokeWidth: 2.5,
              strokeLinejoin: 'round',
              strokeLinecap: 'round',
              vectorEffect: 'non-scaling-stroke',
            }}
          />
        </svg>

        {points.map((p, i) => (
          <span
            key={`dot-${i}`}
            aria-hidden="true"
            className="oc-chart-dot"
            style={{
              left: p.left,
              top: p.top,
              transform: `scale(${hovered === i ? 1.6 : 1})`,
              animationDelay: `${0.45 + i * 0.07}s`,
            }}
          />
        ))}

        {showPeak && values.length > 0 ? (() => {
          const peakValue = Math.max(...values)
          const peakIndex = values.indexOf(peakValue)
          return (
            <span
              aria-hidden="true"
              style={{
                position: 'absolute',
                left: points[peakIndex].left,
                top: `calc(${points[peakIndex].top} - 26px)`,
                transform: 'translateX(-50%)',
                padding: '2px 7px',
                borderRadius: 999,
                background: 'var(--oc-chart-color, var(--v3-accent))',
                color: 'var(--accent-ink)',
                fontSize: 10,
                whiteSpace: 'nowrap',
              }}
            >
              峰值 {peakValue}
            </span>
          )
        })() : null}

        {hovered !== null && tipStyle ? (
          <>
            <div aria-hidden="true" className="oc-chart-guide" style={{ left: points[hovered].left }} />
            <div aria-hidden="true" className="oc-chart-tip oc-drop" style={tipStyle}>
              <div style={{ opacity: 0.7 }}>{labels[hovered]}</div>
              <div>
                <span style={{ opacity: 0.7 }}>{active.name}</span>{' '}
                <span style={{ fontFamily: 'var(--font-mono, ui-monospace, monospace)', fontSize: 14, fontWeight: 600 }}>
                  {values[hovered]}
                </span>
              </div>
            </div>
          </>
        ) : null}

        {points.map((p, i) => (
          <button
            key={`hit-${i}`}
            type="button"
            className="oc-chart-hit"
            aria-label={`${labels[i]}，${active.name} ${values[i]}`}
            style={{ left: p.hitLeft, width: p.hitWidth }}
            onMouseEnter={() => setHover(i)}
            onMouseLeave={() => setHover(null)}
            onFocus={() => setHover(i)}
            onBlur={() => setHover(null)}
          />
        ))}
      </div>

      <div
        aria-hidden="true"
        style={{ display: 'flex', justifyContent: 'space-between', marginTop: 10, fontSize: 12, color: 'var(--muted)' }}
      >
        {labels.slice(0, n).map((label, i) => (
          <span key={i} style={{ fontFamily: 'var(--font-mono, ui-monospace, monospace)' }}>
            {label}
          </span>
        ))}
      </div>

      {showDataTable && tableOpen ? (
        <div
          id={tableId}
          role="table"
          aria-label={`${active.name}数据表`}
          className="oc-chart-table oc-drop"
          style={{ '--oc-cols': series.length } as CSSProperties}
        >
          <div style={{ minWidth: 420 }}>
            <div role="row">
              <span role="columnheader">时间</span>
              {series.map((s) => (
                <span key={s.key} role="columnheader">
                  {s.name}
                </span>
              ))}
            </div>
            {labels.slice(0, n).map((label, i) => (
              <div key={i} role="row">
                <span role="rowheader" style={{ color: 'var(--muted)' }}>
                  {label}
                </span>
                {series.map((s) => (
                  <span
                    key={s.key}
                    role="cell"
                    style={{ color: s.key === active.key ? 'var(--accent)' : 'var(--text)' }}
                  >
                    {s.values[i] ?? '—'}
                  </span>
                ))}
              </div>
            ))}
          </div>
        </div>
      ) : null}
    </div>
  );
}
