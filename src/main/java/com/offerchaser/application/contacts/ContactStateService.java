package com.offerchaser.application.contacts;

import com.offerchaser.application.persistence.BossQueryRepository;
import com.offerchaser.application.persistence.ContactStateRepository;
import com.offerchaser.application.persistence.FollowupPlanRepository;
import com.offerchaser.application.persistence.ManualActionRepository;
import com.offerchaser.application.persistence.PageResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 联系人状态服务（阶段 B B3）。
 * <p>
 * 联系人是"账户 + 平台 + 可信 HR/会话 ID"的粒度：只有拿得到可信 `encryptUserId` 才建立联系人记录；
 * 拿不到时不合并、不做跨岗位去重（避免把不同 HR 当成同一人）。
 * <p>
 * 人工标记（已回复/明确拒绝/面试/人工接管/停止联系）会立刻取消该联系人所有未执行的跟进计划，
 * 并使后续自动沟通被禁止；所有状态变更有版本保护与 manual_action 审计。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContactStateService {

    public static final String PLATFORM = "boss";
    public static final Set<String> REPLY_STATES =
            Set.of("UNKNOWN", "REPLIED", "REJECTED", "INTERVIEW", "NO_REPLY");
    private static final Set<String> BLOCKED_REPLY_STATES = Set.of("REPLIED", "REJECTED", "INTERVIEW");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ContactStateRepository contactStateRepository;
    private final FollowupPlanRepository followupPlanRepository;
    private final ManualActionRepository manualActionRepository;
    private final BossQueryRepository bossQueryRepository;

    @Value("${offerchaser.boss.budget-account:local}")
    private String account = "local";

    /** 人工标记请求。 */
    public record MarkStateRequest(String replyState, Boolean manualTakeover, Boolean stopContact,
                                   String reason, Integer expectedVersion, String idempotencyKey) {
    }

    /** 对外视图（只暴露前端需要的字段）。 */
    public record ContactStateView(String contactKey, String encryptUserId, String hrName, String companyName,
                                   String replyState, boolean manualTakeover, boolean stopContact,
                                   String lastContactAt, String lastReplyAt, int version,
                                   String createdAt, String updatedAt) {
        static ContactStateView from(ContactStateRepository.ContactState state) {
            if (state == null) {
                return null;
            }
            return new ContactStateView(state.contactKey(), state.encryptUserId(), state.hrName(),
                    state.companyName(), state.replyState(), state.manualTakeover(), state.stopContact(),
                    state.lastContactAt(), state.lastReplyAt(), state.version(),
                    state.createdAt(), state.updatedAt());
        }
    }

    /** 可信联系人键：只有 encryptUserId 存在时才生成；否则返回 null（不合并未知联系人）。 */
    public static String contactKey(String encryptUserId) {
        if (encryptUserId == null || encryptUserId.isBlank()) {
            return null;
        }
        return "uid:" + encryptUserId.trim();
    }

    public PageResult<ContactStateRepository.ContactState> list(String replyState, Boolean manualTakeover,
                                                               Boolean stopContact, int page, int size) {
        return contactStateRepository.list(replyState, manualTakeover, stopContact, page, size);
    }

    public ContactStateView getOrNull(String contactKey) {
        if (contactKey == null || contactKey.isBlank()) {
            return null;
        }
        return ContactStateView.from(contactStateRepository.findByKey(account, PLATFORM, contactKey));
    }

    public ContactStateView getOrThrow(String contactKey) {
        ContactStateView view = getOrNull(contactKey);
        if (view == null) {
            throw new ContactNotFoundException("联系人不存在：" + contactKey);
        }
        return view;
    }

    /** 联系人详情：状态 + 最近投递尝试。 */
    public Map<String, Object> detail(String contactKey) {
        ContactStateView state = getOrThrow(contactKey);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("contact", state);
        data.put("attempts", bossQueryRepository.findAttemptsByContact(account, contactKey, 20));
        return data;
    }

    /** 最近一次确认沟通后回写联系人最后联系时间（不存在则创建联系人记录）。 */
    public void ensureOnConfirmedFirstContact(String contactKey, String encryptUserId, String hrName,
                                              String companyName, String lastContactAt) {
        if (contactKey == null || contactKey.isBlank()) {
            return;
        }
        try {
            ContactStateRepository.ContactState current =
                    contactStateRepository.findByKey(account, PLATFORM, contactKey);
            String now = now();
            if (current == null) {
                contactStateRepository.insert(new ContactStateRepository.ContactState(
                        0L, account, PLATFORM, contactKey, encryptUserId, hrName, companyName,
                        "UNKNOWN", false, false,
                        lastContactAt == null ? now : lastContactAt, null,
                        0, now, now));
                log.info("联系人记录已创建 | contactKey={} | hr={}", contactKey, hrName);
            } else {
                contactStateRepository.touchLastContact(account, PLATFORM, contactKey,
                        lastContactAt == null ? now : lastContactAt);
            }
        } catch (Exception e) {
            log.warn("回写联系人最后联系时间失败（不影响投递结果）：{}", e.getMessage());
        }
    }

    /** 是否禁止自动沟通：停止联系/人工接管/已回复/明确拒绝/已面试。 */
    public boolean isAutoContactBlocked(String contactKey) {
        if (contactKey == null || contactKey.isBlank()) {
            return false;
        }
        ContactStateRepository.ContactState state =
                contactStateRepository.findByKey(account, PLATFORM, contactKey);
        return state != null && isBlocked(state);
    }

    /**
     * D-02：把投递状态同步读到的 HR 回复写回联系人。
     * <p>
     * 人工接管/停止/拒绝/面试优先；成功回写后取消该联系人未执行的跟进计划，
     * 后续发送/跟进由 isAutoContactBlocked 阻断。
     *
     * @return true 表示本次实际从非回复态更新为 REPLIED
     */
    public boolean applySyncedReply(String contactKey, String lastReplyAt) {
        if (contactKey == null || contactKey.isBlank()) {
            return false;
        }
        try {
            ContactStateRepository.ContactState current =
                    contactStateRepository.findByKey(account, PLATFORM, contactKey);
            if (current == null || current.manualTakeover() || current.stopContact()) {
                return false;
            }
            if (Set.of("REJECTED", "INTERVIEW", "REPLIED").contains(current.replyState())) {
                return false;
            }
            int updated = contactStateRepository.markRepliedFromStatus(
                    account, PLATFORM, contactKey, lastReplyAt);
            if (updated > 0) {
                cancelActiveFollowups(contactKey, "投递状态同步读取到 HR 已回复");
                log.info("状态同步已回写联系人回复 | contactKey={} | state={} -> REPLIED",
                        contactKey, current.replyState());
                return true;
            }
        } catch (Exception e) {
            log.warn("状态同步回写联系人失败（不阻断同步）：{}", e.getMessage());
        }
        return false;
    }

    /** 是否已在"其他岗位"确认沟通过同一联系人（用于跨岗位去重）。 */
    public boolean hasOtherJobConfirmedFirstContact(String contactKey, String currentEncryptId, int withinDays) {
        if (contactKey == null || contactKey.isBlank()) {
            return false;
        }
        try {
            String since = LocalDateTime.now().minusDays(Math.max(1, withinDays)).format(TIME_FORMAT);
            List<Map<String, Object>> rows =
                    bossQueryRepository.findConfirmedFirstContactsForContact(account, contactKey, since, 50);
            for (Map<String, Object> row : rows) {
                String encryptId = text(row, "encryptId");
                if (encryptId != null && !encryptId.equals(currentEncryptId)) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.warn("联系人跨岗位去重查询失败（按不去重处理）：{}", e.getMessage());
        }
        return false;
    }

    /** 该联系人该岗位是否已有确认成功的图片（用于图片重复保护）。 */
    public boolean hasConfirmedImage(String contactKey, String encryptId) {
        if (contactKey == null || contactKey.isBlank() || encryptId == null || encryptId.isBlank()) {
            return false;
        }
        try {
            return bossQueryRepository.hasConfirmedImageForContactJob(account, contactKey, encryptId);
        } catch (Exception e) {
            log.warn("图片重复保护查询失败（按未发送过处理）：{}", e.getMessage());
            return false;
        }
    }

    /**
     * 人工标记联系人状态。版本冲突 409；idempotencyKey 重复提交返回当前状态（幂等）。
     * 状态变化后取消该联系人所有未执行的跟进计划（回复/拒绝/接管/停止都会让先前批准失效）。
     */
    public ContactStateView markState(String contactKey, MarkStateRequest request) {
        if (contactKey == null || contactKey.isBlank()) {
            throw new ContactValidationException("contactKey 不能为空");
        }
        if (request == null) {
            throw new ContactValidationException("请求体不能为空");
        }
        String reason = request.reason() == null ? null : request.reason().trim();
        if (reason == null || reason.isEmpty()) {
            throw new ContactValidationException("reason 不能为空（人工操作需要留下原因）");
        }
        if (reason.length() > 500) {
            throw new ContactValidationException("reason 最多 500 字");
        }
        String idempotencyKey = request.idempotencyKey() == null ? null : request.idempotencyKey().trim();
        if (idempotencyKey != null && !idempotencyKey.isEmpty()) {
            try {
                ManualActionRepository.ManualAction existing =
                        manualActionRepository.findByIdempotencyKey(idempotencyKey);
                if (existing != null) {
                    return getOrThrow(contactKey);
                }
            } catch (Exception e) {
                log.warn("检查联系人状态幂等键失败：{}", e.getMessage());
            }
        }
        String requestedReplyState = request.replyState() == null
                ? null : request.replyState().trim().toUpperCase(Locale.ROOT);
        if (requestedReplyState != null && !REPLY_STATES.contains(requestedReplyState)) {
            throw new ContactValidationException("replyState 必须是 UNKNOWN/REPLIED/REJECTED/INTERVIEW/NO_REPLY 之一");
        }

        ContactStateRepository.ContactState current =
                contactStateRepository.findByKey(account, PLATFORM, contactKey);
        if (current == null) {
            int expected = request.expectedVersion() == null ? 0 : request.expectedVersion();
            if (expected != 0) {
                throw new ContactVersionConflictException(0);
            }
            String now = now();
            contactStateRepository.insert(new ContactStateRepository.ContactState(
                    0L, account, PLATFORM, contactKey, null, null, null,
                    "UNKNOWN", false, false, null, null, 0, now, now));
            current = contactStateRepository.findByKey(account, PLATFORM, contactKey);
            if (current == null) {
                throw new IllegalStateException("创建联系人记录失败：" + contactKey);
            }
        }
        int expectedVersion = request.expectedVersion() == null ? current.version() : request.expectedVersion();
        if (expectedVersion != current.version()) {
            throw new ContactVersionConflictException(current.version());
        }
        String newReplyState = requestedReplyState == null ? current.replyState() : requestedReplyState;
        boolean newTakeover = request.manualTakeover() == null ? current.manualTakeover() : request.manualTakeover();
        boolean newStop = request.stopContact() == null ? current.stopContact() : request.stopContact();
        String lastReplyAt = BLOCKED_REPLY_STATES.contains(newReplyState) ? now() : current.lastReplyAt();

        ContactStateRepository.ContactState updated;
        try {
            updated = contactStateRepository.updateState(account, PLATFORM, contactKey,
                    newReplyState, newTakeover, newStop, lastReplyAt, expectedVersion);
        } catch (Exception e) {
            log.warn("更新联系人状态失败：{}", e.getMessage());
            updated = null;
        }
        if (updated == null) {
            ContactStateRepository.ContactState latest =
                    contactStateRepository.findByKey(account, PLATFORM, contactKey);
            throw new ContactVersionConflictException(latest == null ? expectedVersion + 1 : latest.version());
        }
        cancelActiveFollowups(contactKey, "联系人状态变更：" + reason);
        audit("contact_state", contactKey, "MARK_STATE",
                beforeState(current), afterState(updated), reason, idempotencyKey);
        log.info("联系人状态已更新 | contactKey={} | reply={} | takeover={} | stop={} | version={}",
                contactKey, newReplyState, newTakeover, newStop, updated.version());
        return ContactStateView.from(updated);
    }

    /** 取消该联系人所有未执行的跟进计划（SUGGESTED/APPROVED → CANCELLED）。 */
    public int cancelActiveFollowups(String contactKey, String reason) {
        if (contactKey == null || contactKey.isBlank()) {
            return 0;
        }
        int cancelled = 0;
        try {
            List<FollowupPlanRepository.FollowupPlan> plans =
                    followupPlanRepository.listActiveByContact(account, PLATFORM, contactKey);
            for (FollowupPlanRepository.FollowupPlan plan : plans) {
                try {
                    FollowupPlanRepository.FollowupPlan updated = followupPlanRepository.transitionStatus(
                            plan.planId(), plan.status(), "CANCELLED", reason, plan.version(),
                            null, null, null);
                    if (updated != null) {
                        cancelled++;
                    }
                } catch (Exception e) {
                    log.warn("取消跟进计划失败 | planId={} | {}", plan.planId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("查询联系人跟进计划失败：{}", e.getMessage());
        }
        if (cancelled > 0) {
            log.info("联系人状态变化，已取消 {} 个跟进计划 | contactKey={}", cancelled, contactKey);
        }
        return cancelled;
    }

    private boolean isBlocked(ContactStateRepository.ContactState state) {
        return state.stopContact() || state.manualTakeover()
                || BLOCKED_REPLY_STATES.contains(state.replyState());
    }

    private static String beforeState(ContactStateRepository.ContactState state) {
        return state.replyState() + "/takeover=" + state.manualTakeover() + "/stop=" + state.stopContact();
    }

    private static String afterState(ContactStateRepository.ContactState state) {
        return state.replyState() + "/takeover=" + state.manualTakeover() + "/stop=" + state.stopContact();
    }

    private void audit(String objectType, String objectId, String action, String before,
                       String after, String reason, String idempotencyKey) {
        try {
            manualActionRepository.insert(objectType, objectId, action, before, after, reason, idempotencyKey);
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
}
