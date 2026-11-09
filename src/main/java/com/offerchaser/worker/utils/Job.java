package com.offerchaser.worker.utils;

import lombok.Data;

import java.io.Serializable;

/**
 * Boss 岗位摘要：采集/投递过程中用于展示与日志的精简结构。
 * <p>
 * 字段顺序与历史数据保持一致（下游可能按此顺序展示）；数值与空值都按原始字符串保存，
 * 不做单位换算——需要结构化薪资时使用 {@code BossService.SalaryInfo}。
 */
@Data
public class Job implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 岗位详情页链接（Boss 返回的原始 href）。 */
    private String href;

    /** 岗位名称。 */
    private String jobName;

    /** 工作地区（可能是城市/区域拼接文本）。 */
    private String jobArea;

    /** 岗位信息补充（经验/学历等原始描述）。 */
    private String jobInfo;

    /** 薪资文本（保留 Boss 原始写法，如 "20-40K·16薪"）。 */
    private String salary;

    /** 公司标签。 */
    private String companyTag;

    /** 招聘者（HR）名称。 */
    private String recruiter;

    /** 公司名称。 */
    private String companyName;

    /** 公司补充信息。 */
    private String companyInfo;

    /**
     * 日志/展示用摘要：公司、岗位、地区、薪资、公司标签、招聘者。
     * <p>
     * 保留 {@code String.format} 对 null 的既有输出（"null"），不静默替换为空串。
     */
    @Override
    public String toString() {
        return String.format(
                "【%s, %s, %s, %s, %s, %s】",
                companyName, jobName, jobArea, salary, companyTag, recruiter);
    }
}
