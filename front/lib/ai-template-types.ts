/**
 * 「AI 帮我建模板」向导的数据类型（前端新增；对应后端 resume/analyze、analyze-text、templates/ai-draft 契约）。
 * 不修改 front/lib/boss-types.ts 中已有的任何类型字段。
 */

import type { TemplateConfigRequest } from './boss-types'

/** 简历解析出的候选人画像 */
export interface ResumeProfile {
  yearsExperience?: number | null
  roles?: string[]
  skills?: string[]
  highestDegree?: string | null
  /** 专业（AI 从教育经历推断，只读展示） */
  major?: string | null
  /** 毕业年份（AI 从教育经历推断，只读展示） */
  graduationYear?: number | null
  cities?: string[]
  expectedSalaryMin?: number | null
  expectedSalaryMax?: number | null
  industries?: string[]
  strengths?: string[]
  warnings?: string[]
  /** 字段 → 证据摘要（只用于用户确认，不在前端保存） */
  evidence?: Record<string, string>
  confidence?: number | null
}

/** AI 推荐方向 */
export interface ResumeDirection {
  title: string
  fit?: number | null
  reason?: string
  risks?: string[]
  keywords?: string[]
  /** 推荐行业名称（与 BOSS 行业选项名称对齐） */
  industries?: string[]
}

/** AI 推荐的薪资区间 */
export interface ResumeSalaryBand {
  label: string
  min: number
  max: number
  basis?: string
  confidence?: number | null
}

/** 简历分析结果（analyze / analyze-text 的 data） */
export interface ResumeAnalysis {
  profile: ResumeProfile
  directions?: ResumeDirection[]
  salaryBands?: ResumeSalaryBand[]
  warnings?: string[]
  visionUsed?: boolean
}

/** 生成草稿请求体 */
export interface AiDraftRequest {
  profile: ResumeProfile
  direction: string
  keywords: string[]
  /** 用户确认的多城市（中文名，最多 4；第 1 个为主搜索城市） */
  cities: string[]
  /** 用户确认的行业名称（最多 6） */
  industries: string[]
  /** 兼容旧后端：cityCode 仍传第一个城市 */
  cityCode?: string | null
  expectedSalaryMin?: number | null
  expectedSalaryMax?: number | null
  /**
   * BOSS 薪资范围档位名称（如 ["5-10K","10-20K"]，可多选；最多 6 个、单项 ≤ 20 字）。
   * 写入模板 config.salary；空数组/null 表示档位不限。
   * 显式传值（即使是空数组）表示用户已处理过薪资问题，后端不会再用画像推荐值回填。
   */
  salary?: string[]
  exclusions?: string[]
  intensity: 'trial' | 'normal' | 'active'
  sendImgResume: boolean
  templateName: string
}

/** 生成草稿返回（config 直接喂给现有 updateTemplateConfig） */
export interface AiDraftResult {
  name: string
  direction?: string | null
  description?: string | null
  /** 与 TemplateConfigRequest 一致：salary 为 BOSS 薪资档位名称数组，expectedSalaryMin/Max 为期望薪资上下限 */
  config: TemplateConfigRequest
  warnings?: string[]
}
