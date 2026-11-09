export interface JobInsightJob {
  jobName?: string | null
  companyName?: string | null
  salary?: string | null
  city?: string | null
  experience?: string | null
  degree?: string | null
  industry?: string | null
  financingStage?: string | null
  companyScale?: string | null
  companyAddress?: string | null
  jobUrl?: string | null
  jobDescriptionExcerpt?: string | null
}

export interface JobInsightReplyScript {
  scene: string
  script: string
}

export interface JobInsightAnalysis {
  jobSummary: string
  companySummary: string
  matchPoints: string[]
  gaps: string[]
  risks: string[]
  communicationAdvice: string[]
  openingScript: string
  replyScripts: JobInsightReplyScript[]
  questionsToAsk: string[]
  avoidTopics: string[]
  confidence: number
  disclaimer: string
}

export interface JobInsightResult {
  job: JobInsightJob
  insight: JobInsightAnalysis
}
