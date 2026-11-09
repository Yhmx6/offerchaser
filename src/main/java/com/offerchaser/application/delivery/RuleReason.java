package com.offerchaser.application.delivery;

/**
 * 硬规则判定原因码（冻结，写库的 rule_reason_code 直接用枚举名）。
 * <p>
 * PASS 之外的原因码都对应一类明确的拒绝/复核依据；{@link #IDENTITY_MISMATCH} 与
 * {@link #MISSING_DETAIL_RESPONSE} 由主流程（快照校验、详情响应缺失）使用，规则引擎内部
 * 遇到无法判定的异常时也复用 {@link #MISSING_DETAIL_RESPONSE} 作为复核原因。
 */
public enum RuleReason {
    /** 全部规则通过。 */
    PASS,
    /** 缺少岗位身份（encryptId 为空）。 */
    MISSING_IDENTITY,
    /** 岗位名称或 JD 缺失/过短，无法判断。 */
    MISSING_JOB_CONTENT,
    /** 卡片标识与详情数据不一致（主流程使用）。 */
    IDENTITY_MISMATCH,
    /** 详情接口没有返回/无法解析（主流程使用，规则引擎内部异常也用它）。 */
    MISSING_DETAIL_RESPONSE,
    /** 学历要求高于本科（硕士/博士/研究生）。 */
    DEGREE_TOO_HIGH,
    /** 职位黑名单命中。 */
    JOB_BLACKLIST,
    /** 公司黑名单命中。 */
    COMPANY_BLACKLIST,
    /** 招聘者（HR 职位）黑名单命中。 */
    RECRUITER_BLACKLIST,
    /** HR 活跃度命中不活跃状态。 */
    HR_INACTIVE,
    /** 期望薪资策略判定不符合。 */
    SALARY_NOT_EXPECTED,
    /** 岗位薪资无法解析或为面议，无法判断，转人工复核。 */
    SALARY_UNPARSABLE,
    /** 岗位方向不在可接受方向内（阶段 B B2）。 */
    DIRECTION_NOT_ACCEPTED,
    /** 岗位城市不在可接受城市内（阶段 B B2）。 */
    CITY_NOT_ACCEPTED,
    /** 岗位城市命中不可接受城市（blacklistCities，互相包含即命中）。 */
    CITY_BLACKLISTED,
    /** 结构化学历与 JD 中的硬性高学历要求冲突，转人工复核（阶段 B B2）。 */
    EDUCATION_CONFLICT,
    /** 同一联系人已在其他岗位确认沟通，避免重复问候（阶段 B B3）。 */
    CONTACT_ALREADY_GREETED,
    /** 联系人已回复/拒绝/人工接管/停止联系，禁止自动沟通（阶段 B B3）。 */
    CONTACT_BLOCKED,
    /** 跟进需要管理台批准，当前没有已批准的跟进计划（阶段 B B3）。 */
    FOLLOWUP_NOT_APPROVED
}
