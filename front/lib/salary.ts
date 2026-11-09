/**
 * 薪资文本解析（展示用，不参与投递决策）。
 *
 * 输入示例：`20-40K`、`30K`、`35-65K·16薪`、`20-40K·13薪`、`面议`、`20 - 40 K`。
 * 输出统一为「月薪 K」信息；`面议` / 空 / 无法解析时返回 undefined，由调用方决定展示。
 */

export type SalaryInfo = {
  minK: number
  maxK: number
  months: number
  annualTotal: number
  medianK: number
}

type KRange = { minK: number; maxK: number }

/** 标准区间（如 20-40K） */
const RANGE_RE = /^(\d+)-(\d+)[Kk]$/
/** 标准单值（如 30K） */
const SINGLE_RE = /^(\d+)[Kk]$/
/** 月数后缀（如 16薪） */
const MONTHS_RE = /([0-9]+)薪/
/** 宽松兜底：只保留数字、K/k 与连字符 */
const LOOSE_CLEAN_RE = /[^0-9Kk-]/g
/** 数字串（用于统一取值） */
const DIGITS_RE = /^\d+$/

function parseToK(text: string): KRange | undefined {
  const rangeMatch = RANGE_RE.exec(text)
  if (rangeMatch) {
    return { minK: Number(rangeMatch[1]), maxK: Number(rangeMatch[2]) }
  }
  const singleMatch = SINGLE_RE.exec(text)
  if (singleMatch && DIGITS_RE.test(singleMatch[1])) {
    const value = Number(singleMatch[1])
    return { minK: value, maxK: value }
  }
  return undefined
}

/** 拆出月数；返回去掉薪数后的薪资文本（有 K 时截断到最后一个 K） */
function splitMonths(compact: string): { months: number; salaryPart: string } {
  const monthsMatch = MONTHS_RE.exec(compact)
  if (!monthsMatch) {
    return { months: 12, salaryPart: compact }
  }
  const months = Number(monthsMatch[1]) || 12
  let salaryPart = compact.slice(0, monthsMatch.index)
  const lastK = Math.max(salaryPart.lastIndexOf('K'), salaryPart.lastIndexOf('k'))
  if (lastK >= 0) {
    salaryPart = salaryPart.slice(0, lastK + 1)
  }
  return { months, salaryPart }
}

/**
 * 解析薪资字符串为统一月薪区间。
 *
 * @param raw 原始薪资文本（可能带空格、薪数后缀或尾部杂符号）
 */
export function parseSalary(raw?: string): SalaryInfo | undefined {
  const text = raw?.trim()
  if (!text || text.includes('面议')) return undefined

  const compact = text.replace(/\s+/g, '')
  const { months, salaryPart } = splitMonths(compact)

  // 先严格解析；失败时用宽松清洗结果兜底（与旧实现保持一致）
  const range = parseToK(salaryPart) ?? parseToK(salaryPart.replace(LOOSE_CLEAN_RE, ''))
  if (!range) return undefined

  const medianK = (range.minK + range.maxK) / 2
  return {
    minK: range.minK,
    maxK: range.maxK,
    months: months,
    medianK,
    annualTotal: Math.round(medianK * 1000 * months),
  }
}
