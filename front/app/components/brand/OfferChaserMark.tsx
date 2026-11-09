/**
 * OfferChaser 品牌标记（矢量）。
 *
 * 造型：陶土色方块 + 向上的箭头（Offer / Chaser），不依赖字体，20px 下仍可辨认。
 * 侧栏、登录层共用；favorite icon 使用 front/public/offerchaser-icon.svg 的同一造型。
 */
export function OfferChaserMark({ className }: { className?: string }) {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      aria-hidden="true"
      className={className}
      xmlns="http://www.w3.org/2000/svg"
    >
      <path
        d="M6.5 17.5 16.5 7.5M10.2 7.5h6.3v6.3"
        stroke="currentColor"
        strokeWidth="2.3"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
      <circle cx="6.2" cy="19.4" r="1.45" fill="currentColor" />
    </svg>
  )
}
