import * as React from "react"
import { Slot } from "@radix-ui/react-slot"
import { cva, type VariantProps } from "class-variance-authority"

import { cn } from "@/lib/utils"

/**
 * Button —— 设计系统 D.4
 * 4 个真实变体：primary / secondary / ghost / danger（+ danger-solid 仅供强制复位确认弹窗）。
 * 旧名保留为别名（outline→secondary、destructive→danger、success→secondary、link→link、
 * default→primary），避免一次性改上百处调用点。
 * 尺寸 sm 28px / md 32px / icon-sm 28 / icon 32。
 * 圆角：全部变体统一用 rounded-control（6px）。此前 primary / dangerOutline 是 4px，
 * 与相邻的 secondary / ghost（6px）并排时能看出差异（如「新建模板」旁的「刷新」）。
 */
const buttonVariants = cva(
  [
    "inline-flex items-center justify-center gap-1.5 whitespace-nowrap",
    "rounded-control text-sm font-medium leading-none tracking-[0.01em]",
    "transition-colors duration-100",
    "focus-visible:outline-none focus-visible:shadow-focus",
    "disabled:pointer-events-none disabled:opacity-45 disabled:cursor-not-allowed",
    "[&_svg]:pointer-events-none [&_svg]:size-3.5 [&_svg]:shrink-0",
  ],
  {
    variants: {
      variant: {
        primary:
          "bg-[hsl(var(--brand-deep))] text-[hsl(var(--background))] hover:bg-[hsl(var(--brand))] active:bg-[hsl(var(--brand-deep))]",
        secondary:
          "border border-border bg-transparent text-ink-800 hover:bg-surface-inset hover:border-clay-400 dark:text-sand-200 dark:hover:bg-surface-inset",
        // 文字用 --text-3（两种模式都已定义且 ≥4.8:1）；原 dark:text-clay-500 在深色卡面上仅 3.98:1
        ghost:
          "text-[hsl(var(--text-3))] hover:bg-surface-inset hover:text-[hsl(var(--foreground))]",
        // v2：停止投递用描边版——实心红会把整页阅读重心拽过去
        dangerOutline:
          "border border-[hsl(var(--bad))] bg-transparent text-[hsl(var(--bad-text))] hover:bg-[hsl(var(--bad-bg))]",
        danger:
          "border border-danger-border bg-danger-bg text-danger hover:bg-danger-bg/70",
        "danger-solid":
          "bg-danger text-primary-foreground hover:brightness-95",
        link:
          "text-primary underline-offset-4 hover:underline",
        /* —— 旧名别名（不要在新代码里使用）—— */
        default:
          "bg-primary text-primary-foreground hover:bg-primary-hover active:bg-primary-active",
        outline:
          "border border-border bg-transparent text-ink-800 hover:bg-surface-inset hover:border-clay-400 dark:text-sand-200 dark:hover:bg-surface-inset",
        destructive:
          "border border-danger-border bg-danger-bg text-danger hover:bg-danger-bg/70",
        success:
          "border border-border bg-transparent text-ink-800 hover:bg-surface-inset hover:border-clay-400 dark:text-sand-200",
      },
      size: {
        sm: "h-7 px-2.5 text-sm",
        md: "h-8 px-3 text-sm",
        "icon-sm": "h-7 w-7",
        icon: "h-8 w-8",
        /* —— 旧名别名 —— */
        default: "h-8 px-3 text-sm",
        lg: "h-8 px-3 text-sm",
      },
    },
    defaultVariants: {
      variant: "primary",
      size: "md",
    },
  }
)

export interface ButtonProps
  extends React.ButtonHTMLAttributes<HTMLButtonElement>,
    VariantProps<typeof buttonVariants> {
  asChild?: boolean
}

const Button = React.forwardRef<HTMLButtonElement, ButtonProps>(
  ({ className, variant, size, asChild = false, ...props }, ref) => {
    const Comp = asChild ? Slot : "button"
    return (
      <Comp
        className={cn(buttonVariants({ variant, size, className }))}
        ref={ref}
        {...props}
      />
    )
  }
)
Button.displayName = "Button"

export { Button, buttonVariants }
