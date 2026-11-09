package com.offerchaser.application.delivery;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 岗位快照：一次岗位详情抓取得到的不可变数据集合。
 * <p>
 * 规则判定、AI 分析、投递台账都基于同一份快照，避免各处重复从页面/JSON 取值导致口径不一致。
 * 快照允许字段为空（页面字段确实可能缺失），是否可用由 {@link #hasIdentity()} /
 * {@link #hasEssentialContent()} 判断，而不是在构造时抛异常——抓取不全属于正常业务情况。
 */
public record JobSnapshot(
        String encryptId,
        String encryptUserId,
        String jobName,
        String salary,
        String city,
        String experience,
        String degree,
        String jobDescription,
        String companyName,
        String industry,
        String financingStage,
        String companyScale,
        String companyAddress,
        String recruitmentStatus,
        String hrName,
        String hrPosition,
        String hrActiveStatus,
        String jobUrl,
        String sourceKeyword,
        String sourceCity) {

    /**
     * 是否拿到了岗位身份：encryptId 非空白。
     * <p>
     * encryptId 是后续打开详情页、去重、写台账的唯一定位依据，没有它一律不能投递。
     */
    public boolean hasIdentity() {
        return encryptId != null && !encryptId.isBlank();
    }

    /**
     * 是否拿到了足够的岗位内容：岗位名称非空白，且职位描述去掉空白字符后长度 >= 10。
     * <p>
     * JD 太短通常意味着字段没取到（或只剩一句占位文案），此时规则与 AI 都无法有效判断。
     */
    public boolean hasEssentialContent() {
        if (jobName == null || jobName.isBlank()) {
            return false;
        }
        if (jobDescription == null) {
            return false;
        }
        return jobDescription.replaceAll("\\s+", "").length() >= 10;
    }

    /**
     * 快照哈希：sha256(encryptId \n jobName \n salary \n jobDescription) 的 64 位小写十六进制。
     * <p>
     * 空字段按空字符串参与计算，保证同一份抓取结果在多次运行中得到同一个哈希，可用于台账去重与
     * 「岗位内容是否变化」的比对。字段之间固定用单个换行分隔，不做任何 trim，避免不同实现算出不同结果。
     */
    public String snapshotHash() {
        String payload = nullToEmpty(encryptId) + "\n"
                + nullToEmpty(jobName) + "\n"
                + nullToEmpty(salary) + "\n"
                + nullToEmpty(jobDescription);
        return sha256Hex(payload);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必须提供的算法，走到这里说明运行环境异常，直接暴露而不是静默返回空串
            throw new IllegalStateException("当前 JVM 不支持 SHA-256，无法计算岗位快照哈希", e);
        }
    }

    /** 创建链式构造器。 */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 链式构造器：字段与 record 一一对应，允许不设置（保持 null）。
     */
    public static final class Builder {
        private String encryptId;
        private String encryptUserId;
        private String jobName;
        private String salary;
        private String city;
        private String experience;
        private String degree;
        private String jobDescription;
        private String companyName;
        private String industry;
        private String financingStage;
        private String companyScale;
        private String companyAddress;
        private String recruitmentStatus;
        private String hrName;
        private String hrPosition;
        private String hrActiveStatus;
        private String jobUrl;
        private String sourceKeyword;
        private String sourceCity;

        private Builder() {
        }

        public Builder encryptId(String encryptId) {
            this.encryptId = encryptId;
            return this;
        }

        public Builder encryptUserId(String encryptUserId) {
            this.encryptUserId = encryptUserId;
            return this;
        }

        public Builder jobName(String jobName) {
            this.jobName = jobName;
            return this;
        }

        public Builder salary(String salary) {
            this.salary = salary;
            return this;
        }

        public Builder city(String city) {
            this.city = city;
            return this;
        }

        public Builder experience(String experience) {
            this.experience = experience;
            return this;
        }

        public Builder degree(String degree) {
            this.degree = degree;
            return this;
        }

        public Builder jobDescription(String jobDescription) {
            this.jobDescription = jobDescription;
            return this;
        }

        public Builder companyName(String companyName) {
            this.companyName = companyName;
            return this;
        }

        public Builder industry(String industry) {
            this.industry = industry;
            return this;
        }

        public Builder financingStage(String financingStage) {
            this.financingStage = financingStage;
            return this;
        }

        public Builder companyScale(String companyScale) {
            this.companyScale = companyScale;
            return this;
        }

        public Builder companyAddress(String companyAddress) {
            this.companyAddress = companyAddress;
            return this;
        }

        public Builder recruitmentStatus(String recruitmentStatus) {
            this.recruitmentStatus = recruitmentStatus;
            return this;
        }

        public Builder hrName(String hrName) {
            this.hrName = hrName;
            return this;
        }

        public Builder hrPosition(String hrPosition) {
            this.hrPosition = hrPosition;
            return this;
        }

        public Builder hrActiveStatus(String hrActiveStatus) {
            this.hrActiveStatus = hrActiveStatus;
            return this;
        }

        public Builder jobUrl(String jobUrl) {
            this.jobUrl = jobUrl;
            return this;
        }

        public Builder sourceKeyword(String sourceKeyword) {
            this.sourceKeyword = sourceKeyword;
            return this;
        }

        public Builder sourceCity(String sourceCity) {
            this.sourceCity = sourceCity;
            return this;
        }

        /** 组装快照；所有字段允许为 null。 */
        public JobSnapshot build() {
            return new JobSnapshot(
                    encryptId, encryptUserId, jobName, salary, city, experience, degree, jobDescription,
                    companyName, industry, financingStage, companyScale, companyAddress, recruitmentStatus,
                    hrName, hrPosition, hrActiveStatus, jobUrl, sourceKeyword, sourceCity);
        }
    }
}
