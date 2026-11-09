package com.offerchaser.application.persistence;

/**
 * 一条岗位评估记录（规则结果 + AI 结果）。
 * <p>
 * 除 runId 外所有字段都允许为 null：评估是分阶段产生的，调用方可先记录规则结果，
 * 再在同一轮任务中补齐 AI 结果后一次性落库，也可分两次记录。
 */
public final class EvaluationEntry {

    private final String runId;
    private final String encryptId;
    private final String encryptUserId;
    private final String jobName;
    private final String companyName;
    private final String jobUrl;
    private final String sourceKeyword;
    private final String sourceCity;
    private final String snapshotHash;
    private final String ruleVersion;
    private final String ruleDecision;
    private final String ruleReasonCode;
    private final String ruleEvidence;
    private final String aiDecision;
    private final String aiErrorCode;
    private final String aiAnalysis;
    private final String aiMessage;
    private final String promptVersion;
    private final String aiModel;
    private final Integer promptTokens;
    private final Integer completionTokens;
    private final Long durationMs;
    /** 本轮使用的规则配置版本号（V5）；旧数据为 null。 */
    private final Integer ruleConfigVersion;
    /** 本轮规则 JSON 的 sha256（V5）；可空。 */
    private final String rulesHash;

    private EvaluationEntry(Builder builder) {
        this.runId = builder.runId;
        this.encryptId = builder.encryptId;
        this.encryptUserId = builder.encryptUserId;
        this.jobName = builder.jobName;
        this.companyName = builder.companyName;
        this.jobUrl = builder.jobUrl;
        this.sourceKeyword = builder.sourceKeyword;
        this.sourceCity = builder.sourceCity;
        this.snapshotHash = builder.snapshotHash;
        this.ruleVersion = builder.ruleVersion;
        this.ruleDecision = builder.ruleDecision;
        this.ruleReasonCode = builder.ruleReasonCode;
        this.ruleEvidence = builder.ruleEvidence;
        this.aiDecision = builder.aiDecision;
        this.aiErrorCode = builder.aiErrorCode;
        this.aiAnalysis = builder.aiAnalysis;
        this.aiMessage = builder.aiMessage;
        this.promptVersion = builder.promptVersion;
        this.aiModel = builder.aiModel;
        this.promptTokens = builder.promptTokens;
        this.completionTokens = builder.completionTokens;
        this.durationMs = builder.durationMs;
        this.ruleConfigVersion = builder.ruleConfigVersion;
        this.rulesHash = builder.rulesHash;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getRunId() {
        return runId;
    }

    public String getEncryptId() {
        return encryptId;
    }

    public String getEncryptUserId() {
        return encryptUserId;
    }

    public String getJobName() {
        return jobName;
    }

    public String getCompanyName() {
        return companyName;
    }

    public String getJobUrl() {
        return jobUrl;
    }

    public String getSourceKeyword() {
        return sourceKeyword;
    }

    public String getSourceCity() {
        return sourceCity;
    }

    public String getSnapshotHash() {
        return snapshotHash;
    }

    public String getRuleVersion() {
        return ruleVersion;
    }

    public String getRuleDecision() {
        return ruleDecision;
    }

    public String getRuleReasonCode() {
        return ruleReasonCode;
    }

    public String getRuleEvidence() {
        return ruleEvidence;
    }

    public String getAiDecision() {
        return aiDecision;
    }

    public String getAiErrorCode() {
        return aiErrorCode;
    }

    public String getAiAnalysis() {
        return aiAnalysis;
    }

    public String getAiMessage() {
        return aiMessage;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public String getAiModel() {
        return aiModel;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public Integer getRuleConfigVersion() {
        return ruleConfigVersion;
    }

    public String getRulesHash() {
        return rulesHash;
    }

    /** 链式构建器，字段名与表列一一对应。 */
    public static final class Builder {
        private String runId;
        private String encryptId;
        private String encryptUserId;
        private String jobName;
        private String companyName;
        private String jobUrl;
        private String sourceKeyword;
        private String sourceCity;
        private String snapshotHash;
        private String ruleVersion;
        private String ruleDecision;
        private String ruleReasonCode;
        private String ruleEvidence;
        private String aiDecision;
        private String aiErrorCode;
        private String aiAnalysis;
        private String aiMessage;
        private String promptVersion;
        private String aiModel;
        private Integer promptTokens;
        private Integer completionTokens;
        private Long durationMs;
        private Integer ruleConfigVersion;
        private String rulesHash;

        private Builder() {
        }

        public Builder runId(String value) {
            this.runId = value;
            return this;
        }

        public Builder encryptId(String value) {
            this.encryptId = value;
            return this;
        }

        public Builder encryptUserId(String value) {
            this.encryptUserId = value;
            return this;
        }

        public Builder jobName(String value) {
            this.jobName = value;
            return this;
        }

        public Builder companyName(String value) {
            this.companyName = value;
            return this;
        }

        public Builder jobUrl(String value) {
            this.jobUrl = value;
            return this;
        }

        public Builder sourceKeyword(String value) {
            this.sourceKeyword = value;
            return this;
        }

        public Builder sourceCity(String value) {
            this.sourceCity = value;
            return this;
        }

        public Builder snapshotHash(String value) {
            this.snapshotHash = value;
            return this;
        }

        public Builder ruleVersion(String value) {
            this.ruleVersion = value;
            return this;
        }

        public Builder ruleDecision(String value) {
            this.ruleDecision = value;
            return this;
        }

        public Builder ruleReasonCode(String value) {
            this.ruleReasonCode = value;
            return this;
        }

        public Builder ruleEvidence(String value) {
            this.ruleEvidence = value;
            return this;
        }

        public Builder aiDecision(String value) {
            this.aiDecision = value;
            return this;
        }

        public Builder aiErrorCode(String value) {
            this.aiErrorCode = value;
            return this;
        }

        public Builder aiAnalysis(String value) {
            this.aiAnalysis = value;
            return this;
        }

        public Builder aiMessage(String value) {
            this.aiMessage = value;
            return this;
        }

        public Builder promptVersion(String value) {
            this.promptVersion = value;
            return this;
        }

        public Builder aiModel(String value) {
            this.aiModel = value;
            return this;
        }

        public Builder promptTokens(Integer value) {
            this.promptTokens = value;
            return this;
        }

        public Builder completionTokens(Integer value) {
            this.completionTokens = value;
            return this;
        }

        public Builder durationMs(Long value) {
            this.durationMs = value;
            return this;
        }

        public Builder ruleConfigVersion(Integer value) {
            this.ruleConfigVersion = value;
            return this;
        }

        public Builder rulesHash(String value) {
            this.rulesHash = value;
            return this;
        }

        public EvaluationEntry build() {
            return new EvaluationEntry(this);
        }
    }
}
