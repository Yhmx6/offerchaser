package com.offerchaser.worker.boss;

import com.offerchaser.application.delivery.JobSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;

import java.util.Optional;

/**
 * 岗位详情接口（/wapi/zpgeek/job/detail.json）解析器。
 * <p>
 * 只负责把接口 JSON 转成不可变的 {@link JobSnapshot}，不做任何过滤/匹配判断。
 * 解析失败、缺 jobInfo 时返回空，由调用方按"详情缺失"处理并跳过该岗位，
 * 绝不允许用空字段的 Job 继续走聊天流程（原实现的隐患）。
 */
@Slf4j
public final class BossJobDetailParser {

    private BossJobDetailParser() {
    }

    /**
     * 解析岗位详情 JSON。
     *
     * @return 解析成功返回快照；空数据/非法 JSON/缺 jobInfo 返回 empty
     */
    public static Optional<JobSnapshot> parse(String body) {
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        try {
            JSONObject root = new JSONObject(body);
            JSONObject zpData = root.optJSONObject("zpData");
            if (zpData == null) {
                return Optional.empty();
            }
            JSONObject jobInfo = zpData.optJSONObject("jobInfo");
            if (jobInfo == null) {
                return Optional.empty();
            }
            JSONObject brand = zpData.optJSONObject("brandComInfo");
            JSONObject bossInfo = zpData.optJSONObject("bossInfo");

            String encryptId = trimToNull(jobInfo.optString("encryptId", null));
            String encryptUserId = trimToNull(jobInfo.optString("encryptUserId", null));
            if (encryptUserId == null && bossInfo != null) {
                // 兼容部分页面字段落在 bossInfo 内
                encryptUserId = trimToNull(bossInfo.optString("encryptUserId", null));
                if (encryptUserId == null) {
                    encryptUserId = trimToNull(bossInfo.optString("encryptBossId", null));
                }
            }

            String jobUrl = encryptId == null ? null
                    : "https://www.zhipin.com/job_detail/" + encryptId + ".html";

            JobSnapshot snapshot = JobSnapshot.builder()
                    .encryptId(encryptId)
                    .encryptUserId(encryptUserId)
                    .jobName(trimToNull(jobInfo.optString("jobName", null)))
                    .salary(trimToNull(decodeSalaryText(jobInfo.optString("salaryDesc", null))))
                    .city(trimToNull(jobInfo.optString("locationName", null)))
                    .experience(trimToNull(jobInfo.optString("experienceName", null)))
                    .degree(trimToNull(jobInfo.optString("degreeName", null)))
                    .jobDescription(trimToNull(jobInfo.optString("postDescription", null)))
                    .companyName(brand == null ? null : trimToNull(brand.optString("brandName", null)))
                    .industry(brand == null ? null : trimToNull(brand.optString("industryName", null)))
                    .financingStage(brand == null ? null : trimToNull(brand.optString("stageName", null)))
                    .companyScale(brand == null ? null : trimToNull(brand.optString("scaleName", null)))
                    .companyAddress(trimToNull(jobInfo.optString("address", null)))
                    .recruitmentStatus(trimToNull(jobInfo.optString("jobStatusDesc", null)))
                    .hrName(bossInfo == null ? null : trimToNull(bossInfo.optString("name", null)))
                    .hrPosition(bossInfo == null ? null : trimToNull(bossInfo.optString("title", null)))
                    .hrActiveStatus(bossInfo == null ? null : trimToNull(bossInfo.optString("activeTimeDesc", null)))
                    .jobUrl(jobUrl)
                    .build();
            return Optional.of(snapshot);
        } catch (Throwable e) {
            log.debug("解析岗位详情 JSON 失败：{}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 从列表卡片「查看更多信息」链接里提取 encryptId。
     * 用于核对"当前详情响应"确实属于刚点击的那张卡片。
     *
     * @param href 形如 /job_detail/xxxx.html 或完整 URL
     * @return encryptId；无法提取返回 null
     */
    static String extractEncryptIdFromHref(String href) {
        if (href == null || href.isBlank()) {
            return null;
        }
        String key = "/job_detail/";
        int idx = href.indexOf(key);
        if (idx < 0) {
            return null;
        }
        int start = idx + key.length();
        int end = href.indexOf(".html", start);
        String id = end < 0 ? href.substring(start) : href.substring(start, end);
        return trimToNull(id);
    }

    /**
     * BOSS 薪资文案有时会带字体混淆字符，复用现有解码逻辑。
     */
    private static String decodeSalaryText(String salary) {
        if (salary == null || salary.isEmpty()) {
            return salary;
        }
        // 与 Boss.decodeSalary 等价的映射，避免解析器依赖 Boss 实例
        StringBuilder result = new StringBuilder(salary.length());
        for (char c : salary.toCharArray()) {
            result.append(switch (c) {
                case '\uE8F0' -> '0';
                case '\uE8F1' -> '1';
                case '\uE8F2' -> '2';
                case '\uE8F3' -> '3';
                case '\uE8F4' -> '4';
                case '\uE8F5' -> '5';
                case '\uE8F6' -> '6';
                case '\uE8F7' -> '7';
                case '\uE8F8' -> '8';
                case '\uE8F9' -> '9';
                default -> c;
            });
        }
        return result.toString();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
