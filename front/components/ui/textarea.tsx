import * as React from "react"

import { cn } from "@/lib/utils"

export type TextareaProps = React.TextareaHTMLAttributes<HTMLTextAreaElement>

const Textarea = React.forwardRef<HTMLTextAreaElement, TextareaProps>(
  ({ className, ...props }, ref) => {
    return (
      <textarea
        className={cn(
          "flex min-h-[72px] w-full resize-y rounded-control border border-border bg-card p-2.5 text-base leading-5 text-ink-800 placeholder:text-clay-500",
          "transition-colors duration-100 dark:bg-[hsl(36_15%_22%)] dark:text-sand-200",
          "focus-visible:border-primary focus-visible:outline-none focus-visible:shadow-focus",
          "disabled:cursor-not-allowed disabled:bg-surface-inset disabled:text-clay-500",
          className
        )}
        ref={ref}
        {...props}
      />
    )
  }
)
Textarea.displayName = "Textarea"

export { Textarea }
