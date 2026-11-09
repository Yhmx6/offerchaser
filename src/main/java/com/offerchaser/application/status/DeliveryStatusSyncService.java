package com.offerchaser.application.status;

import com.offerchaser.application.contacts.ContactStateService;
import com.offerchaser.application.persistence.DeliveryStatusRepository;
import com.offerchaser.worker.boss.BossChatStatusReader;
import com.offerchaser.worker.manager.PlaywrightManager;
import com.microsoft.playwright.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * D2：投递状态只读同步。
 *
 * <p>从 BOSS 聊天列表读取：自动投递（DB attempt 匹配）、手动投递（对话由自己发起且未匹配
 * attempt）、HR 主动招呼（对话第一条消息来自 HR）；读取每条对话的已读/回复状态并聚合。
 * 不发送消息、不修改投递状态；失败一律记 FAILED/UNKNOWN，不伪造统计。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryStatusSyncService {

    private static final String PLATFORM = "boss";
    private static final String TRIGGER_ACTIVE = "ACTIVE";
    private static final String TRIGGER_STARTUP = "STARTUP";
    private static final String SCOPE_TODAY = "TODAY";
    private static final String SCOPE_PREVIOUS = "PREVIOUS_DAY";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final int MAX_CHATS = 40;
    private static final Pattern MONTH_DAY = Pattern.compile("^(\\d{1,2})-(\\d{1,2})$");
    private static final Pattern FULL_DATE = Pattern.compile("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$");
    private static final Pattern CLOCK = Pattern.compile("^\\d{1,2}:\\d{2}$");

    private final PlaywrightManager playwrightManager;
    private final BossChatStatusReader chatReader;
    private final DeliveryStatusRepository statusRepository;
    private final ContactStateService contactStateService;
    private final DataSource dataSource;

    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 手动按钮：同步今天到目前为止的投递状态。 */
    public Map<String, Object> syncActiveToday() {
        LocalDate today = LocalDate.now(ZONE);
        return sync(TRIGGER_ACTIVE, SCOPE_TODAY, today);
    }

    /**
     * 每天第一次启动：同步前一天；同一天已成功同步过则跳过。
     * 由启动器异步调用，不能阻塞管理台启动。
     */
    public Map<String, Object> syncPreviousDayIfNeeded() {
        LocalDate previous = LocalDate.now(ZONE).minusDays(1);
        DeliveryStatusRepository.StatusSyncRun existing =
                statusRepository.findCompletedStartup(previous.toString());
        if (existing != null) {
            log.info("前一天状态同步已完成，跳过 | date={} | syncId={}", previous, existing.syncId());
            return Map.of("skipped", true, "reason", "前一天已完成同步", "targetDate", previous.toString());
        }
        return sync(TRIGGER_STARTUP, SCOPE_PREVIOUS, previous);
    }

    /** 最近同步摘要：今日主动同步、昨天启动同步、最近记录。 */
    public Map<String, Object> summary() {
        LocalDate today = LocalDate.now(ZONE);
        LocalDate previous = today.minusDays(1);
        List<DeliveryStatusRepository.StatusSyncRun> recent = statusRepository.findRecent(30);
        DeliveryStatusRepository.StatusSyncRun active = recent.stream()
                .filter(run -> TRIGGER_ACTIVE.equals(run.triggerType())
                        && today.toString().equals(run.targetDate())
                        && "COMPLETED".equals(run.status()))
                .findFirst().orElse(null);
        DeliveryStatusRepository.StatusSyncRun startup = recent.stream()
                .filter(run -> TRIGGER_STARTUP.equals(run.triggerType())
                        && previous.toString().equals(run.targetDate())
                        && "COMPLETED".equals(run.status()))
                .findFirst().orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("today", today.toString());
        out.put("previousDay", previous.toString());
        out.put("active", active);
        out.put("startup", startup);
        out.put("latest", statusRepository.findLatest());
        out.put("recent", recent);
        return out;
    }

    private Map<String, Object> sync(String trigger, String scope, LocalDate targetDate) {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("已有投递状态同步正在进行，请稍后再试");
        }
        String syncId = UUID.randomUUID().toString();
        String startedAt = now();
        DeliveryStatusRepository.StatusSyncRun runningRun = new DeliveryStatusRepository.StatusSyncRun(
                syncId, trigger, scope, targetDate.toString(), "RUNNING", startedAt, null,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null);
        statusRepository.start(runningRun);
        try {
            if (!playwrightManager.isInitialized()) {
                throw new IllegalStateException("浏览器尚未初始化");
            }
            if (playwrightManager.isPlaywrightBusy()) {
                throw new IllegalStateException("投递任务或浏览器操作正在进行中，不能同时同步状态");
            }
            if (!playwrightManager.isLoggedIn(PLATFORM)) {
                throw new IllegalStateException("BOSS 未登录，无法读取聊天状态");
            }

            List<AutoCandidate> candidates = loadAutoCandidates(targetDate);
            List<ContactCandidate> contacts = loadContactCandidates();
            ReadResult read = readChats(syncId, candidates);
            BuildResult built = buildSnapshots(read, candidates, contacts, targetDate);
            statusRepository.finish(syncId, "COMPLETED",
                    built.autoCounts(), built.manualCounts(), built.hrCounts(), null);
            statusRepository.insertSnapshots(built.snapshots());
            int replyStateSynced = applyReplyWriteback(built.snapshots());
            DeliveryStatusRepository.StatusSyncRun completed = statusRepository.findBySyncId(syncId);
            log.info("投递状态同步完成 | syncId={} | trigger={} | auto={} | manual={} | hr={} | replyStateSynced={}",
                    syncId, trigger, built.autoCounts(), built.manualCounts(), built.hrCounts(), replyStateSynced);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("run", completed == null ? runningRun : completed);
            out.put("snapshotCount", built.snapshots().size());
            out.put("replyStateSynced", replyStateSynced);
            out.put("skipped", false);
            return out;
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            try {
                statusRepository.finish(syncId, "FAILED",
                        DeliveryStatusRepository.Counts.zero(),
                        DeliveryStatusRepository.Counts.zero(),
                        DeliveryStatusRepository.Counts.zero(),
                        message);
            } catch (Exception finishError) {
                log.warn("状态同步失败后写入 FAILED 也失败 | syncId={} | {}", syncId, finishError.getMessage());
            }
            log.warn("投递状态同步失败 | syncId={} | {}", syncId, message);
            throw new IllegalStateException("投递状态同步失败：" + message, e);
        } finally {
            running.set(false);
        }
    }

    private List<AutoCandidate> loadAutoCandidates(LocalDate targetDate) {
        String start = targetDate + " 00:00:00";
        String end = targetDate.plusDays(1) + " 00:00:00";
        String sql = "SELECT a.encrypt_user_id, a.contact_key, a.company_name, a.job_name, c.hr_name " +
                "FROM delivery_attempt a " +
                "LEFT JOIN contact_state c ON c.account = a.account AND c.platform = 'boss' " +
                "AND c.contact_key = a.contact_key " +
                "WHERE a.kind = 'FIRST_CONTACT' AND a.text_status = 'CONFIRMED' " +
                "AND a.started_at >= ? AND a.started_at < ? ORDER BY a.id ASC";
        Map<String, AutoCandidate> unique = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, start);
            statement.setString(2, end);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String encryptUserId = trim(rs.getString("encrypt_user_id"));
                    String contactKey = trim(rs.getString("contact_key"));
                    String key = !encryptUserId.isBlank() ? "uid:" + encryptUserId
                            : !contactKey.isBlank() ? contactKey
                            : "company:" + trim(rs.getString("company_name"));
                    if (unique.containsKey(key)) continue;
                    unique.put(key, new AutoCandidate(
                            key, encryptUserId, contactKey,
                            trim(rs.getString("company_name")), trim(rs.getString("job_name")),
                            trim(rs.getString("hr_name"))));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取本轮投递记录失败：" + e.getMessage(), e);
        }
        return new ArrayList<>(unique.values());
    }

    private ReadResult readChats(String syncId, List<AutoCandidate> candidates) {
        return playwrightManager.callOnPlaywright(() -> {
            boolean paused = false;
            try {
                playwrightManager.pauseBossMonitoring();
                paused = true;
                Page page = playwrightManager.getBossPage();
                if (page == null) {
                    throw new IllegalStateException("BOSS 页面未初始化");
                }
                List<BossChatStatusReader.ChatItem> all = chatReader.readChatList(page);
                List<BossChatStatusReader.ChatItem> items = all.size() <= MAX_CHATS
                        ? all : all.subList(0, MAX_CHATS);
                Map<Integer, BossChatStatusReader.Initiator> initiators = new HashMap<>();
                for (BossChatStatusReader.ChatItem item : items) {
                    if (match(candidates, item) != null) {
                        continue;
                    }
                    // 只有“最后一条不是自己发的”才需要打开会话确认是 HR 主动招呼还是手动投递后的回复
                    if (!item.hasOwnStatus()) {
                        initiators.put(item.index(), chatReader.detectInitiator(page, item));
                    }
                }
                return new ReadResult(syncId, items, initiators);
            } finally {
                if (paused) {
                    try {
                        playwrightManager.resumeBossMonitoring();
                    } catch (Exception ignored) {
                    }
                }
            }
        });
    }

    /** D-02：把本次同步中“HR 已回复”的联系人回写 contact_state，供后续发送/跟进阻断。 */
    private int applyReplyWriteback(List<DeliveryStatusRepository.DeliveryStatusSnapshot> snapshots) {
        int synced = 0;
        String now = now();
        for (DeliveryStatusRepository.DeliveryStatusSnapshot snapshot : snapshots) {
            if (snapshot.contactKey() == null || snapshot.contactKey().isBlank()) continue;
            if (!"REPLIED".equals(snapshot.replyStatus())) continue;
            if (!"AUTO".equals(snapshot.source()) && !"MANUAL".equals(snapshot.source())) continue;
            if (contactStateService.applySyncedReply(snapshot.contactKey(), now)) {
                synced++;
            }
        }
        return synced;
    }

    private List<ContactCandidate> loadContactCandidates() {
        String sql = "SELECT contact_key, encrypt_user_id, hr_name, company_name " +
                "FROM contact_state WHERE platform = 'boss' AND contact_key IS NOT NULL";
        List<ContactCandidate> result = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                result.add(new ContactCandidate(trim(rs.getString("contact_key")),
                        trim(rs.getString("encrypt_user_id")),
                        trim(rs.getString("hr_name")),
                        trim(rs.getString("company_name"))));
            }
        } catch (Exception e) {
            log.warn("读取联系人候选失败（不影响会话统计）：{}", e.getMessage());
        }
        return result;
    }

    private BuildResult buildSnapshots(ReadResult read, List<AutoCandidate> candidates,
                                       List<ContactCandidate> contacts, LocalDate targetDate) {
        LocalDate today = LocalDate.now(ZONE);
        List<DeliveryStatusRepository.DeliveryStatusSnapshot> snapshots = new ArrayList<>();
        Set<String> matchedCandidateKeys = new HashSet<>();
        int autoTotal = 0, autoRead = 0, autoReplied = 0, autoUnread = 0, autoUnknown = 0;
        int manualTotal = 0, manualRead = 0, manualReplied = 0, manualUnread = 0, manualUnknown = 0;
        int hrTotal = 0, hrRead = 0, hrReplied = 0, hrUnread = 0, hrUnknown = 0;

        for (BossChatStatusReader.ChatItem item : read.items()) {
            AutoCandidate candidate = match(candidates, item);
            if (candidate != null) {
                if (!matchedCandidateKeys.add(candidate.key())) {
                    continue;
                }
                autoTotal++;
                String readStatus = resolveReadStatus(item, false);
                String replyStatus = resolveReplyStatus(item, false);
                if ("READ".equals(readStatus)) autoRead++;
                else if ("UNREAD".equals(readStatus)) autoUnread++;
                else autoUnknown++;
                if ("REPLIED".equals(replyStatus)) autoReplied++;
                snapshots.add(snapshot(read.syncId(), "AUTO", candidate, null, item,
                        readStatus, replyStatus, "SELF", "AUTO_CHAT_MATCH"));
                continue;
            }

            // 非自动投递：只统计目标日期内的聊天，避免把历史会话算进今天/昨天
            if (!chatDateMatches(item.chatTime(), targetDate, today)) {
                continue;
            }
            BossChatStatusReader.Initiator initiator = read.initiators().getOrDefault(
                    item.index(), BossChatStatusReader.Initiator.UNKNOWN);
            boolean hrInitiated = !item.hasOwnStatus() && initiator == BossChatStatusReader.Initiator.HR;
            boolean inboundLast = !item.hasOwnStatus() && !item.lastMessage().isBlank();
            ContactCandidate contact = matchContact(contacts, item);
            String readStatus = resolveReadStatus(item, inboundLast);
            String replyStatus = resolveReplyStatus(item, inboundLast);
            if (hrInitiated) {
                hrTotal++;
                // HR 主动招呼场景下，“已读”指我们有没有读对方，而不是 HR 是否读我们的投递；
                // 不把两者的口径混在一起，统一记为未知，只统计“招呼数/是否已回复”。
                hrUnknown++;
                if ("REPLIED".equals(replyStatus)) hrReplied++;
                snapshots.add(snapshot(read.syncId(), "HR_INITIATED", null, contact, item,
                        "UNKNOWN", "REPLIED", "HR", "HR_INITIATED_FIRST_MESSAGE"));
            } else {
                manualTotal++;
                if ("READ".equals(readStatus)) manualRead++;
                else if ("UNREAD".equals(readStatus)) manualUnread++;
                else manualUnknown++;
                if ("REPLIED".equals(replyStatus)) manualReplied++;
                snapshots.add(snapshot(read.syncId(), "MANUAL", null, contact, item,
                        readStatus, replyStatus,
                        initiator == BossChatStatusReader.Initiator.SELF ? "SELF" : "UNKNOWN",
                        "MANUAL_CHAT"));
            }
        }

        // 有投递记录但聊天列表里没匹配到（聊天被删/未加载到）→ 仍计入自动投递，状态 UNKNOWN
        for (AutoCandidate candidate : candidates) {
            if (matchedCandidateKeys.contains(candidate.key())) continue;
            autoTotal++;
            autoUnknown++;
            snapshots.add(new DeliveryStatusRepository.DeliveryStatusSnapshot(0L, read.syncId(), "AUTO",
                    candidate.contactKey(), candidate.encryptUserId(), candidate.hrName(),
                    candidate.companyName(), candidate.jobName(), "DELIVERED", "UNKNOWN", "UNKNOWN",
                    "SELF", null, null, "DB_ONLY_NO_CHAT", null));
        }

        return new BuildResult(snapshots,
                new DeliveryStatusRepository.Counts(autoTotal, autoRead, autoReplied, autoUnread, autoUnknown),
                new DeliveryStatusRepository.Counts(manualTotal, manualRead, manualReplied, manualUnread, manualUnknown),
                new DeliveryStatusRepository.Counts(hrTotal, hrRead, hrReplied, hrUnread, hrUnknown));
    }

    private String resolveReadStatus(BossChatStatusReader.ChatItem item, boolean inboundLast) {
        if (item.ownRead()) return "READ";
        if (item.ownDelivered()) return "UNREAD";
        if (inboundLast || item.unreadCount() > 0) return "READ";
        return "UNKNOWN";
    }

    private String resolveReplyStatus(BossChatStatusReader.ChatItem item, boolean inboundLast) {
        if (item.unreadCount() > 0 || inboundLast) return "REPLIED";
        return "UNKNOWN";
    }

    private DeliveryStatusRepository.DeliveryStatusSnapshot snapshot(String syncId, String source,
                                                                     AutoCandidate candidate,
                                                                     ContactCandidate contact,
                                                                     BossChatStatusReader.ChatItem item,
                                                                     String readStatus, String replyStatus,
                                                                     String initiator, String evidence) {
        String jobName = candidate == null ? null : candidate.jobName();
        String companyName = candidate != null ? candidate.companyName()
                : contact != null && !contact.companyName().isBlank() ? contact.companyName() : item.companyName();
        String hrName = candidate != null ? candidate.hrName()
                : contact != null && !contact.hrName().isBlank() ? contact.hrName() : item.hrName();
        String contactKey = candidate != null ? candidate.contactKey()
                : contact == null ? null : contact.contactKey();
        String encryptUserId = candidate != null ? candidate.encryptUserId()
                : contact == null ? null : contact.encryptUserId();
        return new DeliveryStatusRepository.DeliveryStatusSnapshot(0L, syncId, source,
                contactKey, encryptUserId, hrName, companyName, jobName, "DELIVERED",
                readStatus, replyStatus, initiator, item.lastMessage(), item.chatTime(),
                evidence + (item.unreadCount() > 0 ? ";unread=" + item.unreadCount() : ""), null);
    }

    private AutoCandidate match(List<AutoCandidate> candidates, BossChatStatusReader.ChatItem item) {
        String hr = normalize(item.hrName());
        String company = normalize(item.companyName());
        AutoCandidate companyOnly = null;
        for (AutoCandidate candidate : candidates) {
            String candidateCompany = normalize(candidate.companyName());
            if (candidateCompany.isBlank() || company.isBlank()) continue;
            boolean companyMatch = candidateCompany.equals(company)
                    || candidateCompany.contains(company) || company.contains(candidateCompany);
            if (!companyMatch) continue;
            String candidateHr = normalize(candidate.hrName());
            if (!candidateHr.isBlank() && (candidateHr.equals(hr)
                    || candidateHr.contains(hr) || hr.contains(candidateHr))) {
                return candidate;
            }
            if (companyOnly == null) companyOnly = candidate;
        }
        return companyOnly;
    }

    private boolean chatDateMatches(String chatTime, LocalDate targetDate, LocalDate today) {
        if (chatTime == null || chatTime.isBlank()) return false;
        String text = chatTime.trim();
        if (text.contains("昨天")) return targetDate.equals(today.minusDays(1));
        if (text.contains("前天")) return targetDate.equals(today.minusDays(2));
        if (CLOCK.matcher(text).matches()) return targetDate.equals(today);
        Matcher full = FULL_DATE.matcher(text);
        if (full.matches()) {
            return targetDate.equals(LocalDate.of(Integer.parseInt(full.group(1)),
                    Integer.parseInt(full.group(2)), Integer.parseInt(full.group(3))));
        }
        Matcher monthDay = MONTH_DAY.matcher(text);
        if (monthDay.matches()) {
            int month = Integer.parseInt(monthDay.group(1));
            int day = Integer.parseInt(monthDay.group(2));
            LocalDate date = LocalDate.of(today.getYear(), month, day);
            if (date.isAfter(today)) date = date.minusYears(1);
            return targetDate.equals(date);
        }
        return false;
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}，。、·（）()【】\\[\\]]", "");
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String now() {
        return LocalDateTime.now(ZONE).format(DATE_TIME);
    }

    private ContactCandidate matchContact(List<ContactCandidate> contacts,
                                           BossChatStatusReader.ChatItem item) {
        String hr = normalize(item.hrName());
        String company = normalize(item.companyName());
        if (hr.isBlank() && company.isBlank()) return null;
        ContactCandidate companyOnly = null;
        boolean companyOnlyAmbiguous = false;
        for (ContactCandidate contact : contacts) {
            String candidateHr = normalize(contact.hrName());
            String candidateCompany = normalize(contact.companyName());
            if (candidateCompany.isBlank()) continue;
            boolean companyMatch = company.isBlank() || candidateCompany.equals(company)
                    || candidateCompany.contains(company) || company.contains(candidateCompany);
            if (!companyMatch) continue;
            if (!candidateHr.isBlank() && !hr.isBlank()
                    && (candidateHr.equals(hr) || candidateHr.contains(hr) || hr.contains(candidateHr))) {
                return contact;
            }
            if (companyOnly == null) {
                companyOnly = contact;
            } else {
                // 同名公司下有多个不同 HR，不能靠公司兜底猜测，避免把回复写到错的人。
                companyOnlyAmbiguous = true;
            }
        }
        return companyOnlyAmbiguous ? null : companyOnly;
    }

    private record AutoCandidate(String key, String encryptUserId, String contactKey,
                                 String companyName, String jobName, String hrName) {
    }

    private record ContactCandidate(String contactKey, String encryptUserId,
                                    String hrName, String companyName) {
    }

    private record ReadResult(String syncId, List<BossChatStatusReader.ChatItem> items,
                              Map<Integer, BossChatStatusReader.Initiator> initiators) {
    }

    private record BuildResult(List<DeliveryStatusRepository.DeliveryStatusSnapshot> snapshots,
                               DeliveryStatusRepository.Counts autoCounts,
                               DeliveryStatusRepository.Counts manualCounts,
                               DeliveryStatusRepository.Counts hrCounts) {
    }
}
