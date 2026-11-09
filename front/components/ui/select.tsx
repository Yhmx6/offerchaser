import * as React from "react"
import { createPortal } from "react-dom"
import { cn } from "@/lib/utils"

type OptionItem = { value: string; label: React.ReactNode }

export interface SelectProps {
  value?: string
  onChange?: (e: { target: { value: string } }) => void
  placeholder?: string
  className?: string
  id?: string
  disabled?: boolean
  children?: React.ReactNode
}

const Select = React.forwardRef<HTMLDivElement, SelectProps>(
  ({ className, children, value, onChange, placeholder, disabled, id, ...props }, ref) => {
    const [open, setOpen] = React.useState(false)
    const wrapperRef = React.useRef<HTMLDivElement>(null)
    const buttonRef = React.useRef<HTMLButtonElement>(null)
    const dropdownRef = React.useRef<HTMLDivElement>(null)
    const [dropdownPosition, setDropdownPosition] = React.useState({ top: 0, left: 0, width: 0 })

    // 是否已挂载（createPortal 需要真实 DOM）。用快照读取，避免 effect 里 setState。
    const mounted = React.useSyncExternalStore(
      () => () => {},
      () => true,
      () => false
    )

    const options = React.useMemo<OptionItem[]>(() => {
      return React.Children.toArray(children)
        .filter(
          (child): child is React.ReactElement<{ value?: string | number; children?: React.ReactNode }> =>
            React.isValidElement(child) && child.type === 'option'
        )
        .map((child) => ({
          value: String(child.props.value ?? child.props.children),
          label: child.props.children,
        }))
    }, [children])

    const selected = options.find((o) => String(value ?? '') === String(o.value))

    const emitChange = (val: string) => onChange?.({ target: { value: val } })

    // 计算下拉框位置
    const updatePosition = React.useCallback(() => {
      if (buttonRef.current) {
        const rect = buttonRef.current.getBoundingClientRect()
        setDropdownPosition({
          top: rect.bottom + 8,
          left: rect.left,
          width: rect.width,
        })
      }
    }, [])

    // 打开时计算位置
    React.useEffect(() => {
      if (open) {
        updatePosition()
        // 监听滚动和窗口大小变化，更新位置
        const handleUpdate = () => updatePosition()
        window.addEventListener('scroll', handleUpdate, true)
        window.addEventListener('resize', handleUpdate)
        return () => {
          window.removeEventListener('scroll', handleUpdate, true)
          window.removeEventListener('resize', handleUpdate)
        }
      }
    }, [open, updatePosition])

    // 点击外部关闭下拉框
    React.useEffect(() => {
      const handleClickOutside = (event: MouseEvent) => {
        const target = event.target as Node
        // 检查点击是否在按钮或下拉框内
        const clickedButton = wrapperRef.current?.contains(target)
        const clickedDropdown = dropdownRef.current?.contains(target)

        if (!clickedButton && !clickedDropdown) {
          setOpen(false)
        }
      }

      const handleEscape = (event: KeyboardEvent) => {
        if (event.key === 'Escape') {
          setOpen(false)
        }
      }

      if (open) {
        // 使用 setTimeout 确保 DOM 已更新
        setTimeout(() => {
          document.addEventListener('mousedown', handleClickOutside)
          document.addEventListener('keydown', handleEscape)
        }, 0)
      }

      return () => {
        document.removeEventListener('mousedown', handleClickOutside)
        document.removeEventListener('keydown', handleEscape)
      }
    }, [open])

    return (
      <div ref={ref} {...props}>
        <div ref={wrapperRef} className="relative">
          <button
            ref={buttonRef}
            id={id as string}
            type="button"
            disabled={disabled}
            onClick={() => setOpen((v) => !v)}
            className={cn(
              "flex h-8 w-full rounded-control border border-border bg-card px-2.5 pr-8 text-base text-ink-800",
              "transition-colors duration-100 dark:bg-[hsl(36_15%_22%)] dark:text-sand-200",
              disabled
                ? "cursor-not-allowed bg-surface-inset text-clay-500"
                : "hover:border-clay-400 focus:border-primary focus:outline-none focus:shadow-focus",
              // 自定义箭头（暖灰）
              "bg-[url('data:image/svg+xml;utf8,<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"%239C907C\" stroke-width=\"2\"><path d=\"M6 9l6 6 6-6\"/></svg>')] bg-no-repeat bg-[length:12px_12px] bg-[position:right_10px_center]",
              className
            )}
          >
            <span className="truncate text-base">{selected ? selected.label : (placeholder ?? '')}</span>
          </button>

          {open && mounted && createPortal(
            <div
              ref={dropdownRef}
              className="dropdown-panel"
              style={{
                top: `${dropdownPosition.top}px`,
                left: `${dropdownPosition.left}px`,
                width: `${dropdownPosition.width}px`,
              }}
            >
              <ul className="py-1">
                {options.map((o) => {
                  const active = String(value ?? '') === String(o.value)
                  return (
                    <li
                      key={String(o.value)}
                      className={cn(
                        "group flex cursor-pointer items-center justify-between gap-3 border-b border-hairline px-3 py-2 transition-colors duration-100 last:border-b-0",
                        active ? "bg-primary-subtle text-primary-on-subtle" : "hover:bg-surface-inset"
                      )}
                      onClick={() => {
                        emitChange(String(o.value))
                        setOpen(false)
                      }}
                    >
                      <span className="flex items-center gap-3">
                        <span
                          aria-hidden="true"
                          className={cn(
                            "inline-flex size-1.5 items-center justify-center rounded-full transition-colors",
                            active ? "bg-primary" : "bg-transparent"
                          )}
                        />
                        <span className="truncate text-base">{o.label}</span>
                      </span>
                    </li>
                  )
                })}
              </ul>
            </div>,
            document.body
          )}
        </div>
      </div>
    )
  }
)
Select.displayName = "Select"

export { Select }
