package com.offerchaser.application.contacts;

import com.offerchaser.application.persistence.BossQueryRepository;
import com.offerchaser.application.persistence.ContactStateRepository;
import com.offerchaser.application.persistence.DeliveryLedgerService;
import com.offerchaser.application.persistence.FollowupPlanRepository;
import com.offerchaser.application.persistence.ManualActionRepository;
import com.offerchaser.application.persistence.PageResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 跟进待办服务（阶段 B B3，第一版：人工标记闭环、默认不自动发送）。
 * <p>
 * 流程：系统只生成"建议跟进"计划（SUGGESTED）→ 用户在管理台批准（APPROVED）→
 * 下一轮任务才允许执行该计划（需要 {@code followup-require-approval}）；执行后标记 EXECUTED。
 * 回复/拒绝/人工接管/停止联系会取消该联系人全部未执行的计划，使先前的批准失效。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FollowupService {

    public static final String PLAN_FOLLOWUP = "FOLLOWUP";
    public static final String PLAN_IMAGE_RESEND = "IMAGE_RESEND";
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_LIMIT = 200;

    private final FollowupPlanRepository followupPlanRepository;
    private final ContactStateService contactStateService;
    private final DeliveryLedgerService deliveryLedgerService;
    private final BossQueryRepository bossQueryRepository;
    private final ManualActionRepository manualActionRepository;

    @Value("${offerchaser.boss.budget-account:local}")
    private String account = "local";

    @Value("${offerchaser.boss.followup-min-interval-hours:72}")
    private int followupMinIntervalHours = 72;

    @Value("${offerchaser.boss.followup-max-count:1}")
    private int followupMaxCount = 1;

    /** 生成建议跟进；只创建 SUGGESTED，不发送。 */
    public int generateSuggestions(int limit) {
        int safeLimit = limit <= 0 ? 50 : Math.min(limit, MAX_LIMIT);
        List<Map<String, Object>> rows =
                bossQueryRepository.findCandidateFollowupAttempts(account, safeLimit * 3);
        int created = 0;
        for (Map<String, Object> row : rows) {
            if (created >= safeLimit) {
                break;
            }
            try {
                String contactKey = text(row, "contactKey");
                String encryptId = text(row, "encryptId");
                if (contactKey == null || encryptId == null) {
                    // 没有可信联系人标识：不生成自动跟进建议（保持待核对）
                    continue;
                }
                if (contactStateService.isAutoContactBlocked(contactKey)) {
                    continue;
                }
                if (followupPlanRepository.findActive(account, ContactStateService.PLATFORM,
                        contactKey, encryptId, PLAN_FOLLOWUP) != null) {
                    continue;
                }
                if (deliveryLedgerService.followupCount(encryptId) >= Math.max(1, followupMaxCount)) {
                    continue;
                }
                LocalDateTime lastFollowup = deliveryLedgerService.lastFollowupAt(encryptId);
                LocalDateTime lastContact = deliveryLedgerService.lastConfirmedFirstContactAt(encryptId);
                LocalDateTime reference = lastFollowup;
                if (reference == null || (lastContact != null && lastContact.isAfter(reference))) {
                    reference = lastContact;
                }
                if (reference != null
                        && reference.plusHours(Math.max(1, followupMinIntervalHours)).isAfter(LocalDateTime.now())) {
                    continue;
                }
                String now = now();
                FollowupPlanRepository.FollowupPlan plan = new FollowupPlanRepository.FollowupPlan(
                        0L,
                        UUID.randomUUID().toString(),
                        account,
                        ContactStateService.PLATFORM,
                        encryptId,
                        text(row, "encryptUserId"),
                        contactKey,
                        text(row, "jobName"),
                        text(row, "companyName"),
                        PLAN_FOLLOWUP,
                        "SUGGESTED",
                        "系统建议：距上次确认沟通已满 " + Math.max(1, followupMinIntervalHours) + " 小时，可生成跟进",
                        null,
                        0,
                        now, null, null, null, now, now);
                followupPlanRepository.insert(plan);
                created++;
            } catch (Exception e) {
                log.warn("生成跟进建议失败（跳过该条）：{}", e.getMessage());
            }
        }
        if (created > 0) {
            log.info("已生成 {} 条建议跟进（SUGGESTED，未发送）", created);
        }
        return created;
    }

    /** 批准跟进计划：只把状态改为 APPROVED，不发送；下一轮任务才可能执行。 */
    public FollowupPlanRepository.FollowupPlan approve(String planId, int expectedVersion,
                                                       String reason, String idempotencyKey) {
        FollowupPlanRepository.FollowupPlan plan = requirePlan(planId);
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            try {
                if (manualActionRepository.findByIdempotencyKey(idempotencyKey.trim()) != null) {
                    return plan;
                }
            } catch (Exception e) {
                log.warn("检查跟进批准幂等键失败：{}", e.getMessage());
            }
        }
        if (!"SUGGESTED".equals(plan.status())) {
            throw new ContactValidationException("只有待批准（SUGGESTED）的跟进计划可以批准，当前状态：" + plan.status());
        }
        if (PLAN_FOLLOWUP.equals(plan.planType()) && plan.contactKey() != null
                && contactStateService.isAutoContactBlocked(plan.contactKey())) {
            throw new ContactValidationException("联系人已回复/拒绝/人工接管/停止联系，先前批准已失效，不能批准跟进");
        }
        FollowupPlanRepository.FollowupPlan updated = followupPlanRepository.transitionStatus(
                planId, "SUGGESTED", "APPROVED", normalizeReason(reason),
                expectedVersion, now(), null, null);
        if (updated == null) {
            FollowupPlanRepository.FollowupPlan latest = requirePlan(planId);
            throw new ContactVersionConflictException(latest.version());
        }
        audit("followup_plan", planId, "APPROVE", plan.status(), updated.status(), reason, idempotencyKey);
        log.info("跟进计划已批准（不会立即发送）| planId={} | type={}", planId, plan.planType());
        return updated;
    }

    /** 拒绝跟进计划（待批准或已批准都可撤回）。 */
    public FollowupPlanRepository.FollowupPlan reject(String planId, int expectedVersion,
                                                      String reason, String idempotencyKey) {
        FollowupPlanRepository.FollowupPlan plan = requirePlan(planId);
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            try {
                if (manualActionRepository.findByIdempotencyKey(idempotencyKey.trim()) != null) {
                    return plan;
                }
            } catch (Exception e) {
                log.warn("检查跟进拒绝幂等键失败：{}", e.getMessage());
            }
        }
        if (!"SUGGESTED".equals(plan.status()) && !"APPROVED".equals(plan.status())) {
            throw new ContactValidationException("只有 SUGGESTED/APPROVED 的跟进计划可以拒绝，当前状态：" + plan.status());
        }
        FollowupPlanRepository.FollowupPlan updated = followupPlanRepository.transitionStatus(
                planId, plan.status(), "REJECTED", normalizeReason(reason),
                expectedVersion, now(), null, null);
        if (updated == null) {
            FollowupPlanRepository.FollowupPlan latest = requirePlan(planId);
            throw new ContactVersionConflictException(latest.version());
        }
        audit("followup_plan", planId, "REJECT", plan.status(), updated.status(), reason, idempotencyKey);
        return updated;
    }

    /** 任务运行时查询：是否已有批准的跟进计划（contact+job）。 */
    public FollowupPlanRepository.FollowupPlan findApproved(String contactKey, String encryptId, String planType) {
        if (contactKey == null || encryptId == null) {
            return null;
        }
        try {
            FollowupPlanRepository.FollowupPlan plan = followupPlanRepository.findActive(
                    account, ContactStateService.PLATFORM, contactKey, encryptId, planType);
            return plan != null && "APPROVED".equals(plan.status()) ? plan : null;
        } catch (Exception e) {
            log.warn("查询已批准跟进计划失败（按无计划处理）：{}", e.getMessage());
            return null;
        }
    }

    /** 跟进执行后标记计划已执行（结果未知也标记，避免重复打扰；明确失败不调用）。 */
    public void markExecuted(String planId, String attemptId) {
        if (planId == null) {
            return;
        }
        try {
            FollowupPlanRepository.FollowupPlan plan = followupPlanRepository.findByPlanId(planId);
            if (plan == null || !"APPROVED".equals(plan.status())) {
                return;
            }
            FollowupPlanRepository.FollowupPlan updated = followupPlanRepository.transitionStatus(
                    planId, "APPROVED", "EXECUTED", "任务执行跟进完成",
                    plan.version(), null, now(), attemptId);
            if (updated != null) {
                log.info("跟进计划已标记执行 | planId={} | attemptId={}", planId, attemptId);
            }
        } catch (Exception e) {
            log.warn("标记跟进计划执行失败：{}", e.getMessage());
        }
    }

    public PageResult<FollowupPlanRepository.FollowupPlan> list(String status, int page, int size) {
        return followupPlanRepository.list(status, page, size);
    }

    /**
     * 首页「跟进待办」：识别已经产生有效双向沟通的联系人。
     *
     * <p>有效沟通的口径是联系人状态为 REPLIED（HR 已回复）或 INTERVIEW（已进入面试），
     * 不是“已经发送首次沟通”。用最近一次投递尝试关联 boss_data，补齐岗位、公司、薪资和岗位链接；
     * 只读，不改变联系人状态，也不会触发跟进发送。</p>
     */
    public Map<String, Object> effectiveCommunications(int limit) {
        int safeLimit = limit <= 0 ? 20 : Math.min(limit, 100);
        PageResult<ContactStateRepository.ContactState> replied =
                contactStateService.list("REPLIED", null, null, 1, 100);
        PageResult<ContactStateRepository.ContactState> interview =
                contactStateService.list("INTERVIEW", null, null, 1, 100);

        List<ContactStateRepository.ContactState> contacts = new ArrayList<>();
        if (replied != null && replied.items() != null) {
            contacts.addAll(replied.items());
        }
        if (interview != null && interview.items() != null) {
            contacts.addAll(interview.items());
        }
        contacts.sort(Comparator.comparing(
                ContactStateRepository.ContactState::lastReplyAt,
                Comparator.nullsLast(Comparator.reverseOrder())));

        List<Map<String, Object>> items = new ArrayList<>();
        for (ContactStateRepository.ContactState contact : contacts) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("contactKey", contact.contactKey());
            item.put("hrName", contact.hrName());
            item.put("companyName", contact.companyName());
            item.put("replyState", contact.replyState());
            item.put("lastReplyAt", contact.lastReplyAt());
            item.put("lastContactAt", contact.lastContactAt());

            // 「已交换简历附件」的现有可验证口径：至少一次投递的图片简历已确认送达。
            // NOT_ATTEMPTED / FAILED / UNKNOWN 都不算已交换；只读查询，不会补发。
            Map<String, Object> latestAttempt = null;
            List<Map<String, Object>> attempts = bossQueryRepository.findAttemptsByContact(
                    account, contact.contactKey(), 10);
            if (attempts != null) {
                for (Map<String, Object> attempt : attempts) {
                    if (attempt != null
                            && text(attempt, "encryptId") != null
                            && "CONFIRMED".equalsIgnoreCase(text(attempt, "imageStatus"))) {
                        latestAttempt = attempt;
                        break;
                    }
                }
            }
            if (latestAttempt == null) {
                continue;
            }

            String encryptId = text(latestAttempt, "encryptId");
            Map<String, Object> job = bossQueryRepository.findJobRowByEncryptId(encryptId);
            String jobName = text(job, "jobName");
            if (jobName == null) {
                jobName = text(latestAttempt, "jobName");
            }
            String companyName = text(job, "companyName");
            if (companyName == null) {
                companyName = text(latestAttempt, "companyName");
            }
            String jobUrl = text(job, "jobUrl");
            if (jobUrl == null) {
                jobUrl = text(latestAttempt, "jobUrl");
            }
            if (jobUrl == null && encryptId != null) {
                jobUrl = "https://www.zhipin.com/job_detail/" + encryptId + ".html";
            }
            item.put("attemptId", text(latestAttempt, "attemptId"));
            item.put("encryptId", encryptId);
            item.put("imageStatus", text(latestAttempt, "imageStatus"));
            item.put("jobName", jobName);
            item.put("companyName", companyName);
            item.put("jobUrl", jobUrl);
            item.put("salary", text(job, "salary"));
            item.put("city", text(job, "location"));
            item.put("experience", text(job, "experience"));
            item.put("degree", text(job, "degree"));
            item.put("industry", text(job, "industry"));
            item.put("financingStage", text(job, "financingStage"));
            item.put("companyScale", text(job, "companyScale"));
            items.add(item);
        }

        int total = items.size();
        if (items.size() > safeLimit) {
            items = new ArrayList<>(items.subList(0, safeLimit));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        data.put("total", total);
        data.put("limit", safeLimit);
        return data;
    }

    /**
     * 生成补发图片计划（第一版只生成 SUGGESTED，不自动发送）。
     * 仅允许：文字已确认、图片状态为 FAILED/UNKNOWN、该联系人该岗位尚无确认成功图片、无待处理补发计划。
     */
    public FollowupPlanRepository.FollowupPlan createImageResendPlan(String attemptId, String reason) {
        if (attemptId == null || attemptId.isBlank()) {
            throw new ContactValidationException("attemptId 不能为空");
        }
        Map<String, Object> attempt = bossQueryRepository.findAttemptForImageResend(attemptId.trim());
        if (attempt == null) {
            throw new ContactNotFoundException("投递尝试不存在：" + attemptId);
        }
        String textStatus = text(attempt, "textStatus");
        String imageStatus = text(attempt, "imageStatus");
        String contactKey = text(attempt, "contactKey");
        String encryptId = text(attempt, "encryptId");
        if (!"CONFIRMED".equals(textStatus)) {
            throw new ContactValidationException("只有文字确认成功（CONFIRMED）的尝试才能补发图片，当前文字状态："
                    + (textStatus == null ? "未知" : textStatus));
        }
        boolean imageRetryable = "FAILED".equals(imageStatus) || "UNKNOWN".equals(imageStatus);
        if (!imageRetryable) {
            throw new ContactValidationException("该尝试图片状态为 " + (imageStatus == null ? "未知" : imageStatus)
                    + "，只有 FAILED/UNKNOWN 才需要补发");
        }
        if (contactKey == null) {
            throw new ContactValidationException("缺少可信联系人标识（encryptUserId），不能生成补发图片计划");
        }
        if (contactStateService.hasConfirmedImage(contactKey, encryptId)) {
            throw new ContactValidationException("该联系人该岗位已有确认成功的图片，不能重复发送");
        }
        if (followupPlanRepository.findActive(account, ContactStateService.PLATFORM,
                contactKey, encryptId, PLAN_IMAGE_RESEND) != null) {
            throw new ContactValidationException("该联系人该岗位已存在待处理的补发图片计划");
        }
        String now = now();
        FollowupPlanRepository.FollowupPlan plan = new FollowupPlanRepository.FollowupPlan(
                0L,
                UUID.randomUUID().toString(),
                account,
                ContactStateService.PLATFORM,
                encryptId,
                text(attempt, "encryptUserId"),
                contactKey,
                text(attempt, "jobName"),
                text(attempt, "companyName"),
                PLAN_IMAGE_RESEND,
                "SUGGESTED",
                reason == null || reason.isBlank() ? "人工创建补发图片计划" : reason.trim(),
                attemptId.trim(),
                0,
                now, null, null, null, now, now);
        FollowupPlanRepository.FollowupPlan inserted = followupPlanRepository.insert(plan);
        audit("followup_plan", inserted.planId(), "CREATE_IMAGE_RESEND", null, "SUGGESTED",
                reason, null);
        log.info("补发图片计划已创建（SUGGESTED，不自动发送）| planId={} | parentAttempt={}",
                inserted.planId(), attemptId);
        return inserted;
    }

    private FollowupPlanRepository.FollowupPlan requirePlan(String planId) {
        if (planId == null || planId.isBlank()) {
            throw new ContactValidationException("planId 不能为空");
        }
        FollowupPlanRepository.FollowupPlan plan = followupPlanRepository.findByPlanId(planId.trim());
        if (plan == null) {
            throw new ContactNotFoundException("跟进计划不存在：" + planId);
        }
        return plan;
    }

    private static String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "人工操作";
        }
        String trimmed = reason.trim();
        return trimmed.length() > 500 ? trimmed.substring(0, 500) : trimmed;
    }

    private void audit(String objectType, String objectId, String action, String before,
                       String after, String reason, String idempotencyKey) {
        try {
            manualActionRepository.insert(objectType, objectId, action, before, after,
                    reason == null ? null : reason.trim(),
                    idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.trim());
        } catch (IllegalStateException duplicate) {
            log.debug("人工操作幂等键已存在（忽略重复审计）：{}", duplicate.getMessage());
        } catch (Exception e) {
            log.warn("写入人工操作审计失败：{}", e.getMessage());
        }
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static String now() {
        return LocalDateTime.now().format(TIME_FORMAT);
    }

    /** 供健康检查/前端提示：计划类型/状态常量校验。 */
    public static boolean isValidPlanType(String planType) {
        return PLAN_FOLLOWUP.equals(planType) || PLAN_IMAGE_RESEND.equals(planType);
    }

    static String normalizeStatus(String status) {
        return status == null ? null : status.trim().toUpperCase(Locale.ROOT);
    }
}
