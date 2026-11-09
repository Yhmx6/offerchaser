import * as React from "react"

import { cn } from "@/lib/utils"

/**
 * Input —— 设计系统 D.7
 * h-8（32px）、rounded-control（6px）、全不透明 1px 描边、聚焦用 shadow-focus（不用 ring-offset）。
 */
const Input = React.forwardRef<HTMLInputElement, React.ComponentProps<"input">>(
  ({ className, type, ...props }, ref) => {
    return (
      <input
        type={type}
        className={cn(
          "flex h-8 w-full rounded-control border border-border bg-card px-2.5 py-1 text-base text-ink-800",
          "placeholder:text-clay-500 dark:bg-[hsl(36_15%_22%)] dark:text-sand-200",
          "transition-colors duration-100",
          "focus-visible:border-primary focus-visible:outline-none focus-visible:shadow-focus",
          "file:border-0 file:bg-transparent file:text-sm file:font-medium file:text-foreground",
          "disabled:cursor-not-allowed disabled:bg-surface-inset disabled:text-clay-500 disabled:opacity-100",
          className
        )}
        ref={ref}
        {...props}
      />
    )
  }
)
Input.displayName = "Input"

export { Input }
