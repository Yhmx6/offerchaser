package com.offerchaser.worker.boss;

import com.offerchaser.worker.utils.PlaywrightUtil;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.LongConsumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 聊天页 DOM 发送确认器（阶段 A+ / A-01、A-02）。
 * <p>
 * 只做「看 DOM 判断发送结果 / 核对会话身份」这一件事，不负责打开聊天页、填话术、发图片或写数据库。
 * 失败策略遵循总原则：<b>不确定 = 不记成功</b>（fail-closed）。所有探测都遍历
 * {@link Page#frames()}，单个 frame 在页面切换时失效只会跳过该帧，不会让确认流程抛出异常。
 * <p>
 * 三态语义：
 * <ul>
 *   <li>身份：{@link IdentityOutcome#VERIFIED} / {@link IdentityOutcome#MISMATCH} /
 *       {@link IdentityOutcome#UNKNOWN}，只有 VERIFIED 允许继续发送；</li>
 *   <li>文字：{@code confirmed}（本次新增的匹配消息自身发送完成且无失败标记）、
 *       {@code failed}（本次新增消息出现失败标记）、{@code uncertain}（超时/取消/基线不可用/页面异常）；
 *       {@code clickAttempted} 表示是否已经对发送按钮执行过点击（false 时调用方应释放预算且不重发）；</li>
 *   <li>图片：{@code confirmed}（新图片消息连续稳定 3 秒且不在发送中）、{@code uncertain}（超时/取消/基线未知），
 *       失败标记时 {@code confirmed=false, uncertain=false}（调用方据此记 IMAGE_FAILED）。</li>
 * </ul>
 * <p>
 * 探测选择器沿用 {@code Boss.java} 现有实现：聊天根 {@code .chat-message .im-list} 或 {@code .chat-record}，
 * 本人消息 {@code li.message-item.item-myself}，文字/图片都在 {@code .message-content} 内判断。
 * 本类为 package-private，由 {@code Boss}（同包）调用。
 */
@Slf4j
final class ChatSendConfirmer {

    // ------------------------------------------------------------------
    // 证据级别：由调用方写入 delivery_attempt.text_evidence / image_evidence / 事件。
    // 取值保持稳定，便于历史数据按证据来源检索。
    // ------------------------------------------------------------------
    /** 本人的新文字消息已出现在聊天记录中、内容匹配且该消息自身已发送完成。 */
    static final String EVIDENCE_TEXT_CONFIRMED = "DOM_TEXT_MESSAGE";
    /** 本次新增的本人文字消息出现失败标记，确认发送失败。 */
    static final String EVIDENCE_TEXT_FAILED = "DOM_TEXT_FAILED";
    /** 本人的新图片消息已出现并连续稳定。 */
    static final String EVIDENCE_IMAGE_CONFIRMED = "DOM_IMAGE_MESSAGE";
    /** 本人新图片消息出现失败标记。 */
    static final String EVIDENCE_IMAGE_FAILED = "DOM_IMAGE_FAILED";
    /** 页面 URL 命中 encryptUserId（会话/联系人稳定标识）。 */
    static final String EVIDENCE_URL_CHAT_ID = "URL_CHAT_ID";
    /** 聊天标题命中 HR 姓名（强证据）。 */
    static final String EVIDENCE_TITLE_HR = "DOM_TITLE_HR";
    /** 聊天标题同时命中公司名与岗位名（中等证据）。 */
    static final String EVIDENCE_TITLE_COMPANY_JOB = "DOM_TITLE_COMPANY_JOB";
    /** 聊天标题存在但与公司/岗位/HR 全部冲突。 */
    static final String EVIDENCE_TITLE_CONFLICT = "DOM_TITLE_CONFLICT";
    /** 没有可用证据。 */
    static final String EVIDENCE_NONE = "NONE";

    /** 轮询间隔（秒）。 */
    private static final int POLL_INTERVAL_SECONDS = 1;
    /** 身份标题证据等待轮数：新版聊天页头部异步渲染，root 出现后标题可能稍后挂载。 */
    private static final int IDENTITY_EVIDENCE_WAIT_POLLS = 4;
    /** 新图片消息需要连续稳定（非发送中）的秒数。 */
    private static final int IMAGE_STABLE_SECONDS = 3;
    /** 点击前读取基线的最大尝试次数（每次间隔 1 秒）。 */
    private static final int BASELINE_READ_ATTEMPTS = 3;
    /** 判断「互相包含」身份匹配时，短于该长度的字段不单独作为身份证据，避免单字误匹配。 */
    private static final int MIN_IDENTITY_FIELD_LENGTH = 2;
    /**
     * 聊天标题中的「联系人形态」：中文姓名 + 称谓（先生/女士/小姐/老师）。
     * <p>
     * A-01 补充裁决：提供了 hrName 但标题未命中 HR 时，如果标题里出现<b>其他联系人形态</b>，
     * 说明是「同公司不同 HR」，必须 MISMATCH（不能因为公司+岗位命中就放行）。
     * 前置边界要求称谓前是标题开头或非中文字符，避免把「示例科技李女士」这类粘连文本误切成
     * 长姓名；宁可返回 UNKNOWN 也不误判 VERIFIED。
     */
    private static final Pattern CONTACT_FORM_PATTERN =
            Pattern.compile("(?<![\\u4e00-\\u9fa5])[\\u4e00-\\u9fa5]{1,4}(?:先生|女士|小姐|老师)");
    /** detail 里记录标题时的最大长度，避免日志/数据库字段被整页文本撑爆。 */
    private static final int MAX_DETAIL_TITLE_LENGTH = 160;

    /**
     * 一次探测本人消息列表的脚本。
     * <p>
     * 返回字段：
     * <ul>
     *   <li>{@code rootFound}：聊天根节点是否存在；</li>
     *   <li>{@code ownCount/textCount/imageCount}：本人消息 / 本人文字消息 / 本人图片消息条数；</li>
     *   <li>{@code lastFailed/lastSending}：最后一条本人消息的失败/发送中标记（仅作辅助诊断，
     *       文字确认必须使用匹配消息自身的状态）；</li>
     *   <li>{@code failed/sending/status}：最后一条本人<b>图片</b>消息的失败/发送中标记与状态文案
     *       （沿用 Boss.java 现有图片探测口径）；</li>
     *   <li>{@code texts}：最后若干条本人文字消息文本（已压缩空白）；</li>
     *   <li>{@code tail}：最后若干条本人消息的详细状态，供本次新增消息锚定与自身状态判定。</li>
     * </ul>
     */
    private static final String CHAT_STATE_SCRIPT = """
            () => {
              const root = document.querySelector('.chat-message .im-list')
                || document.querySelector('.chat-record');
              if (!root) {
                return JSON.stringify({
                  rootFound: false, ownCount: 0, textCount: 0, imageCount: 0,
                  lastFailed: false, lastSending: false,
                  failed: false, sending: false, status: '', texts: [], tail: []
                });
              }

              const ownMessages = Array.from(root.querySelectorAll('li.message-item.item-myself'));
              const describe = (item) => {
                const content = item.querySelector('.message-content');
                const classText = [item, ...item.querySelectorAll('*')]
                  .map(el => typeof el.className === 'string' ? el.className : '')
                  .join(' ')
                  .toLowerCase();
                const statusEl = item.querySelector('.message-status');
                const status = statusEl ? (statusEl.textContent || '').trim() : '';
                const messageText = (item.textContent || '').trim();
                const text = content
                  ? (content.innerText || content.textContent || '').replace(/\\s+/g, ' ').trim()
                  : '';
                const hasImage = content ? Boolean(content.querySelector(
                  'img, [class*="image-content"], [class*="message-image"], [class*="message-img"], ' +
                  '[class*="picture"], [style*="background-image"]'
                )) : false;
                const sending = /uploading|sending|upload-progress|send-progress/.test(classText)
                  || /上传中|发送中/.test(messageText + status);
                const failed = /upload-fail|upload-error|send-fail|send-error|message-fail/.test(classText)
                  || /上传失败|发送失败|重新发送|点击重试/.test(messageText + status);
                return { text: text, hasImage: hasImage, sending: sending, failed: failed, status: status };
              };

              const infos = ownMessages.map(describe);
              const textInfos = infos.filter(info => !info.hasImage && info.text);
              const imageInfos = infos.filter(info => info.hasImage);
              const last = infos.length > 0 ? infos[infos.length - 1] : null;
              const lastImage = imageInfos.length > 0 ? imageInfos[imageInfos.length - 1] : null;

              return JSON.stringify({
                rootFound: true,
                ownCount: infos.length,
                textCount: textInfos.length,
                imageCount: imageInfos.length,
                lastFailed: last ? last.failed : false,
                lastSending: last ? last.sending : false,
                failed: lastImage ? lastImage.failed : false,
                sending: lastImage ? lastImage.sending : false,
                status: lastImage ? lastImage.status : '',
                texts: textInfos.slice(-8).map(info => info.text),
                tail: infos.slice(-8)
              });
            }
            """;

    /**
     * 探测聊天页身份证据的脚本。
     * <p>
     * 聊天根节点存在 → {@code rootFound=true}；聊天头/标题区域取到非空文本 → {@code titleFound=true}；
     * {@code title} 是去重后的标题文本拼接。
     * <p>
     * <b>禁止</b>把 {@code document.title} 当作身份标题来源（A-01 要求）：标题只从聊天区域内元素取，
     * 取不到就返回空，由调用方按 UNKNOWN 处理，而不是拿整页标题兜底。
     */
    private static final String CHAT_IDENTITY_SCRIPT = """
            () => {
              const root = document.querySelector('.chat-message .im-list')
                || document.querySelector('.chat-record');
              const selectors = [
                // 2026-11 BOSS 聊天页改版：聊天头部在 .chat-user / .user-info / .chat-position-content 中
                '.chat-user .name-text', '.chat-user .base-title', '.chat-user .title-box',
                '.user-info .name-text', '.user-info .base-title', '.user-info .title-box',
                '.user-info-wrap .name-text', '.user-info-wrap .base-title',
                '.chat-position-content .position-name', '.position-name',
                // 新版「开始沟通」简化弹层
                '.startchat-title .boss-info .name', '.startchat-title .boss-info .position',
                '.chat-user', '.user-info-wrap', '.chat-position-content',
                // 旧版聊天页选择器（保留兼容）
                '.chat-title', '.chat-header', '.chat-name', '.boss-name',
                '[class*="chat-title"]', '[class*="chat-header"]'
              ];
              const parts = [];
              const seen = new Set();
              for (const selector of selectors) {
                let elements = [];
                try {
                  elements = Array.from(document.querySelectorAll(selector));
                } catch (error) {
                  continue;
                }
                for (const el of elements) {
                  const text = (el.innerText || el.textContent || '').replace(/\\s+/g, ' ').trim();
                  if (text && text.length <= 200 && !seen.has(text)) {
                    seen.add(text);
                    parts.push(text);
                  }
                }
              }
              return JSON.stringify({
                rootFound: Boolean(root),
                titleFound: parts.length > 0,
                title: parts.join(' | ')
              });
            }
            """;

    /** 轮询休眠的抽象：生产用真实秒级休眠，单元测试注入空实现，避免测试真的等待。 */
    private final LongConsumer sleeper;

    /** 生产构造器：每秒轮询一次。 */
    ChatSendConfirmer() {
        this(seconds -> PlaywrightUtil.sleep((int) seconds));
    }

    /** 供测试注入休眠实现（package-private，仅同包测试使用）。 */
    ChatSendConfirmer(LongConsumer sleeper) {
        this.sleeper = sleeper != null ? sleeper : seconds -> { };
    }

    // ==================================================================
    // A-01：聊天身份核对（fail-closed 三态）
    // ==================================================================

    /**
     * 核对当前聊天页是否是目标岗位/公司/HR 的会话，返回 fail-closed 三态。
     * <p>
     * 证据优先级（弱证据不能覆盖冲突）：
     * <ol>
     *   <li>聊天根不存在 / 聊天区标题取不到 → UNKNOWN（禁止用 document.title 兜底）；</li>
     *   <li>页面 URL 命中 expectedChatId（encryptUserId，会话/联系人稳定标识）→ VERIFIED；</li>
     *   <li>标题包含 HR 姓名（去空白后长度 ≥ 2，互相包含判定）→ VERIFIED；</li>
     *   <li><b>提供了 hrName 但标题未命中 HR</b>：
     *       <ul>
     *         <li>标题出现其他联系人形态（中文姓名+先生/女士/小姐/老师，且与 hrName 不同）
     *             → MISMATCH（同公司不同 HR，绝不能 VERIFIED）；</li>
     *         <li>标题没有联系人形态、只命中公司+岗位 → UNKNOWN（无法确认联系人身份）；</li>
     *         <li>公司/岗位也都不命中 → 按冲突 MISMATCH。</li>
     *       </ul>
     *   </li>
     *   <li>只有 hrName 为空时，标题同时包含公司名（长度 ≥ 2）和岗位名（长度 ≥ 2）→ VERIFIED；</li>
     *   <li>标题只命中公司名、或只命中岗位名、或只命中 HR 之外的单字段 → UNKNOWN
     *       （岗位通用名不能单独证明联系人身份）；</li>
     *   <li>标题已经取到、公司/岗位/HR 都不匹配且至少一个身份字段非空 → MISMATCH（冲突）。</li>
     * </ol>
     * {@code strict} 参数保留但仅影响 detail 文案；身份核验本身永远 fail-closed。
     *
     * @param chatPage       聊天页（可能带子 frame）
     * @param companyName    目标公司名，可空
     * @param jobName        目标岗位名，可空
     * @param hrName         目标 HR 姓名，可空
     * @param expectedChatId 预期的会话/联系人稳定标识（来自 JobSnapshot.encryptUserId），可空
     * @param strict         仅为兼容旧调用保留，只影响 detail 文案
     */
    IdentityResult verifyChatIdentity(Page chatPage, String companyName, String jobName,
                                      String hrName, String expectedChatId, boolean strict) {
        String strictNote = strict
                ? "strict=true（仅文案标记，核对始终 fail-closed）"
                : "strict=false（核对始终 fail-closed）";
        if (chatPage == null) {
            return IdentityResult.unknown(EVIDENCE_NONE, "聊天页为空，无法核对聊天身份；" + strictNote);
        }

        ChatIdentity identity = readChatIdentity(chatPage);
        if (!identity.rootFound()) {
            return IdentityResult.unknown(EVIDENCE_NONE,
                    "未找到聊天根节点（.chat-message .im-list / .chat-record），无法核对身份；" + strictNote);
        }

        // 新版聊天页头部异步渲染：root 出现后标题可能稍后才挂载，短暂等待/重读，避免把"还没渲染完"当成未知。
        String chatId = normalizeDomText(expectedChatId);
        for (int poll = 0; poll < IDENTITY_EVIDENCE_WAIT_POLLS && !identity.titleFound(); poll++) {
            if (!chatId.isEmpty() && safePageUrl(chatPage) != null
                    && safePageUrl(chatPage).contains(chatId)) {
                break;
            }
            sleepOnePoll();
            identity = readChatIdentity(chatPage);
        }

        // 2. 强证据：URL 命中稳定的会话/联系人标识（encryptUserId）。URL 证据不依赖标题，
        //    因此放在"标题必填"之前，避免新版页面标题选择器变化导致误判未知。
        if (!chatId.isEmpty()) {
            String url = safePageUrl(chatPage);
            if (url != null && url.contains(chatId)) {
                log.info("聊天身份核对通过：页面 URL 命中会话标识 encryptUserId={}", chatId);
                return IdentityResult.verified(EVIDENCE_URL_CHAT_ID,
                        "页面 URL 命中会话标识「" + chatId + "」；" + strictNote);
            }
        }

        if (!identity.titleFound()) {
            logIdentityDiagnostics(chatPage);
            log.warn("聊天身份核对失败：聊天根节点存在但标题为空 | url={} | 已等待{}轮",
                    safePageUrl(chatPage), IDENTITY_EVIDENCE_WAIT_POLLS);
            return IdentityResult.unknown(EVIDENCE_NONE,
                    "聊天根节点存在但聊天区域标题为空（不采用 document.title），"
                            + "已等待新版聊天头渲染，仍无法核对身份；" + strictNote);
        }

        String title = identity.title();
        String titlePreview = abbreviate(normalizeDomText(title), MAX_DETAIL_TITLE_LENGTH);

        String company = normalizeDomText(companyName);
        String job = normalizeDomText(jobName);
        String hr = normalizeDomText(hrName);
        boolean hrUsable = hr.length() >= MIN_IDENTITY_FIELD_LENGTH;
        boolean companyUsable = company.length() >= MIN_IDENTITY_FIELD_LENGTH;
        boolean jobUsable = job.length() >= MIN_IDENTITY_FIELD_LENGTH;
        boolean hrHit = hrUsable && mutuallyContains(title, hr);
        boolean companyHit = companyUsable && mutuallyContains(title, company);
        boolean jobHit = jobUsable && mutuallyContains(title, job);

        // 3. 强证据：标题命中 HR 姓名。
        if (hrHit) {
            log.info("聊天身份核对通过：聊天标题命中 HR 姓名「{}」", hr);
            return IdentityResult.verified(EVIDENCE_TITLE_HR,
                    "聊天标题命中 HR 姓名「" + hr + "」；" + strictNote);
        }
        // 4. 提供了 hrName 但标题未命中 HR：同公司不同 HR 必须 MISMATCH，不能放行。
        //    弱证据（公司/岗位）不能覆盖联系人冲突；没有联系人形态时最多 UNKNOWN。
        boolean hrProvided = !hr.isEmpty();
        if (hrProvided) {
            String otherContact = findOtherContactForm(title, hr);
            if (otherContact != null) {
                log.warn("聊天身份核对不通过（同公司不同 HR）：标题出现其他联系人「{}」，目标 HR「{}」。标题：{}",
                        otherContact, hr, titlePreview);
                return IdentityResult.mismatch(EVIDENCE_TITLE_CONFLICT,
                        "聊天标题出现其他联系人「" + otherContact + "」，与目标 HR「" + hr
                                + "」不符（同公司不同 HR 不得放行）；标题：" + titlePreview + "；" + strictNote);
            }
            if (companyHit || jobHit) {
                log.warn("聊天身份证据不足：已提供 HR 姓名「{}」但标题未命中，仅命中公司/岗位。标题：{}",
                        hr, titlePreview);
                return IdentityResult.unknown(EVIDENCE_NONE,
                        "已提供 HR 姓名「" + hr + "」但聊天标题未命中，且标题没有其他联系人形态，"
                                + "不能仅凭公司/岗位名确认联系人身份；标题：" + titlePreview + "；" + strictNote);
            }
            // 公司/岗位/HR 都不命中：继续走下面的冲突判定（MISMATCH）。
        }
        // 5. 只有 hrName 为空时，公司+岗位同时命中才可作为中等证据 → VERIFIED。
        if (!hrProvided && companyHit && jobHit) {
            log.info("聊天身份核对通过：未提供 HR 姓名，聊天标题同时命中公司「{}」与岗位「{}」", company, job);
            return IdentityResult.verified(EVIDENCE_TITLE_COMPANY_JOB,
                    "未提供 HR 姓名，聊天标题同时命中公司「" + company + "」与岗位「" + job + "」；" + strictNote);
        }
        // 6. 单字段命中（公司或岗位）：岗位通用名不能单独证明联系人身份，必须 UNKNOWN。
        if (companyHit || jobHit) {
            String hitField = companyHit ? "公司名「" + company + "」" : "岗位名「" + job + "」";
            log.warn("聊天身份证据不足：标题只命中{}，不足以证明联系人身份。标题：{}", hitField, titlePreview);
            return IdentityResult.unknown(EVIDENCE_NONE,
                    "聊天标题只命中" + hitField + "，单一字段不足以证明联系人身份"
                            + "（岗位通用名不能单独作为证据）；标题：" + titlePreview + "；" + strictNote);
        }

        // 6. 标题已取到但公司/岗位/HR 都不匹配：冲突。
        boolean anyIdentityField = !company.isEmpty() || !job.isEmpty() || !hr.isEmpty();
        if (!anyIdentityField) {
            return IdentityResult.unknown(EVIDENCE_NONE,
                    "聊天标题已取得，但公司/岗位/HR 名均为空，无法核对身份；标题：" + titlePreview + "；" + strictNote);
        }
        String conflictFields = describeConflictFields(company, job, hr);
        log.warn("聊天身份核对不通过（冲突）：{}；标题：{}", conflictFields, titlePreview);
        return IdentityResult.mismatch(EVIDENCE_TITLE_CONFLICT,
                "聊天标题与公司/岗位/HR 均不匹配，疑似打开错误会话（" + conflictFields + "）；标题："
                        + titlePreview + "；" + strictNote);
    }

    /** 拼出冲突字段描述，便于审计。 */
    private String describeConflictFields(String company, String job, String hr) {
        List<String> fields = new ArrayList<>();
        if (!company.isEmpty()) {
            fields.add("公司=" + company);
        }
        if (!job.isEmpty()) {
            fields.add("岗位=" + job);
        }
        if (!hr.isEmpty()) {
            fields.add("HR=" + hr);
        }
        return String.join("、", fields);
    }

    /**
     * 标题与身份字段「互相包含」判定（忽略大小写）。
     * 单字符字段（如「张」）会匹配几乎所有标题，不作为身份证据。
     */
    private boolean mutuallyContains(String title, String candidate) {
        String left = normalizeDomText(title).toLowerCase(Locale.ROOT);
        String right = normalizeDomText(candidate).toLowerCase(Locale.ROOT);
        if (left.isEmpty() || right.isEmpty()) {
            return false;
        }
        // 标题或身份字段过短时包含关系没有区分度（例如单字姓名会命中所有标题），不作为证据。
        if (left.length() < MIN_IDENTITY_FIELD_LENGTH || right.length() < MIN_IDENTITY_FIELD_LENGTH) {
            return false;
        }
        return left.contains(right) || right.contains(left);
    }

    /**
     * 在聊天标题里找「与目标 HR 不同的其他联系人形态」。
     * <p>
     * 例如目标 hrName=王先生、标题出现「李女士」→ 返回「李女士」（同公司不同 HR，必须 MISMATCH）；
     * 标题没有联系人形态 → 返回 null（按 UNKNOWN 处理，不能 VERIFIED）。
     *
     * @return 第一个与 hrName 不同的联系人称谓；没有则 null
     */
    private String findOtherContactForm(String title, String hrName) {
        String hr = normalizeDomText(hrName).toLowerCase(Locale.ROOT);
        String normalizedTitle = normalizeDomText(title);
        Matcher matcher = CONTACT_FORM_PATTERN.matcher(normalizedTitle);
        while (matcher.find()) {
            String contact = normalizeDomText(matcher.group());
            if (contact.isEmpty()) {
                continue;
            }
            String contactLower = contact.toLowerCase(Locale.ROOT);
            boolean samePerson = !hr.isEmpty()
                    && (contactLower.equals(hr) || contactLower.contains(hr) || hr.contains(contactLower));
            if (!samePerson) {
                return contact;
            }
        }
        return null;
    }

    // ==================================================================
    // A-02：文字发送确认（不得假成功）
    // ==================================================================

    /**
     * 点击发送按钮并确认文字消息真的进入本人聊天记录。
     * <p>
     * 关键保护：
     * <ol>
     *   <li>点击前基线必须有效（rootFound=true 且字段完整），最多重试 3 次；仍不可用则
     *       <b>不点击</b>，返回 {@code uncertain=true, clickAttempted=false}；</li>
     *   <li>只认「点击后本次新增」的本人消息，并用<b>匹配消息自身的 sending/failed</b> 判定，
     *       不使用全局最后一条消息的状态；</li>
     *   <li>历史消息前插/分页加载不算本次新增，不得据此确认；</li>
     *   <li>点击后新增消息出现失败标记 → failed；匹配消息仍发送中 → 继续等，超时 uncertain。</li>
     * </ol>
     *
     * @param chatPage        聊天页（可能带子 frame）
     * @param sendButton      发送按钮 Locator
     * @param expectedMessage 期望发送的文本（比较前做空白归一化）
     * @param timeoutSeconds  轮询超时秒数（&lt;=0 时按 1 秒处理）
     * @param stop            停止回调，可为 null
     */
    TextResult clickAndConfirmText(Page chatPage, Locator sendButton, String expectedMessage,
                                   int timeoutSeconds, Supplier<Boolean> stop) {
        if (chatPage == null || sendButton == null) {
            return TextResult.uncertain(false, EVIDENCE_NONE,
                    "聊天页或发送按钮为空，未发送，无法确认文字发送结果");
        }
        if (isStopped(stop)) {
            return TextResult.uncertain(false, EVIDENCE_NONE,
                    "发送前收到停止指令，未点击发送按钮，发送结果未知");
        }
        String expected = normalizeDomText(expectedMessage);
        if (expected.isEmpty()) {
            return TextResult.uncertain(false, EVIDENCE_NONE,
                    "期望发送文本为空，未点击发送按钮，无法确认文字发送结果");
        }
        int effectiveTimeout = Math.max(1, timeoutSeconds);
        if (timeoutSeconds <= 0) {
            log.warn("文字发送确认超时配置非法（{}），按 {} 秒处理", timeoutSeconds, effectiveTimeout);
        }

        // 1. 点击前必须拿到有效基线：聊天根存在且字段完整。拿不到就不点，避免把迟到历史消息当成本次发送。
        ChatSnapshot baseline = null;
        for (int attempt = 1; attempt <= BASELINE_READ_ATTEMPTS; attempt++) {
            if (isStopped(stop)) {
                return TextResult.uncertain(false, EVIDENCE_NONE,
                        "读取发送基线期间收到停止指令，未点击发送按钮，发送结果未知");
            }
            baseline = readOwnMessages(chatPage);
            if (baseline.usable()) {
                break;
            }
            log.warn("文字发送前基线不可用（第{}次尝试）：聊天根={}，字段完整={}",
                    attempt, baseline.rootFound(), baseline.complete());
            if (attempt < BASELINE_READ_ATTEMPTS) {
                sleepOnePoll();
            }
        }
        if (baseline == null || !baseline.usable()) {
            log.error("文字发送确认中止：点击前聊天基线不可用，未发送。页面：{}", safePageUrl(chatPage));
            return TextResult.uncertain(false, EVIDENCE_NONE,
                    "基线不可用，未发送：点击前未取到完整的本人消息列表");
        }
        if (isStopped(stop)) {
            return TextResult.uncertain(false, EVIDENCE_NONE,
                    "点击发送前收到停止指令，未点击发送按钮，发送结果未知");
        }

        // 2. 执行点击。clickAttempted 在点击动作发生前置为 true：哪怕点击过程抛异常，
        //    也可能已经触达外部动作，调用方不能按“未发送”释放预算。
        boolean clickAttempted = true;
        try {
            sendButton.click(new Locator.ClickOptions().setTimeout(clickTimeoutMillis(effectiveTimeout)));
        } catch (Throwable clickError) {
            log.warn("点击发送按钮异常，文字发送结果未知：{}", safeMessage(clickError));
            return TextResult.uncertain(true, EVIDENCE_NONE,
                    "点击发送按钮异常，发送结果未知：" + safeMessage(clickError));
        }
        log.info("已点击发送按钮，开始轮询本次新增的本人文字消息（最多{}秒）", effectiveTimeout);

        boolean sawAppendedMessage = false;
        MessageInfo matchedMessage = null;
        boolean matchedStillSending = false;
        for (int second = 1; second <= effectiveTimeout; second++) {
            if (isStopped(stop)) {
                return TextResult.uncertain(true, EVIDENCE_NONE,
                        "发送确认已取消：点击后收到停止指令（第" + second + "秒），发送结果未知");
            }
            if (isPageClosed(chatPage)) {
                return TextResult.uncertain(true, EVIDENCE_NONE, "聊天页已关闭，文字发送结果未知");
            }
            try {
                ChatSnapshot current = readOwnMessages(chatPage);
                if (!current.usable()) {
                    // 页面切换/聊天根短暂缺失：本轮不确定，继续等下一轮。
                    if (second < effectiveTimeout) {
                        sleepOnePoll();
                    }
                    continue;
                }
                List<MessageInfo> appended = current.appendedMessages(baseline);
                if (appended.isEmpty()) {
                    if (second < effectiveTimeout) {
                        sleepOnePoll();
                    }
                    continue;
                }
                sawAppendedMessage = true;

                // 2a. 失败标记只采信本次新增消息，历史失败消息不污染本次判断。
                for (MessageInfo message : appended) {
                    if (message.failed()) {
                        log.error("文字发送失败：本次新增本人消息出现失败标记，状态：{}",
                                blankToDefault(message.status(), "无"));
                        return TextResult.failed(true, EVIDENCE_TEXT_FAILED,
                                "本次新增本人消息出现发送失败标记，状态："
                                        + blankToDefault(message.status(), "无"));
                    }
                }

                // 2b. 在本次新增消息中找文本匹配项，并用该消息自身的 sending 状态判定。
                MessageInfo matched = findMatchedTextMessage(appended, expected);
                if (matched != null) {
                    matchedMessage = matched;
                    matchedStillSending = matched.sending();
                    if (!matched.sending()) {
                        log.info("文字发送已确认：本次新增匹配消息自身已发送完成，第{}秒确认", second);
                        return TextResult.confirmed(EVIDENCE_TEXT_CONFIRMED,
                                "本次新增本人消息匹配期望文本且该消息自身已发送完成，第" + second + "秒确认");
                    }
                    log.debug("第{}秒匹配消息仍显示发送中，继续观察其自身状态", second);
                }
            } catch (Throwable probeError) {
                // 页面切换期间 frame 失效属于正常现象，下一轮重新获取 page.frames() 再试。
                log.debug("第{}秒读取文字发送状态失败，稍后重试：{}", second, safeMessage(probeError));
            }
            if (second < effectiveTimeout) {
                sleepOnePoll();
            }
        }

        if (matchedMessage != null && matchedStillSending) {
            return TextResult.uncertain(true, EVIDENCE_NONE,
                    "超时：匹配消息仍处于发送中，未确认发送成功");
        }
        if (matchedMessage != null) {
            return TextResult.uncertain(true, EVIDENCE_NONE,
                    "超时：匹配消息已出现但状态证据不足，未确认发送成功");
        }
        if (sawAppendedMessage) {
            return TextResult.uncertain(true, EVIDENCE_NONE,
                    "超时：本次新增本人消息未匹配到期望文本，证据不足");
        }
        return TextResult.uncertain(true, EVIDENCE_NONE,
                "超时：未检测到本次新增的本人消息，无法确认文字是否发送成功");
    }

    /** 在本次新增消息中找最后一条文本匹配的本人文字消息。 */
    private MessageInfo findMatchedTextMessage(List<MessageInfo> appended, String expected) {
        MessageInfo matched = null;
        for (MessageInfo message : appended) {
            if (!message.hasImage() && normalizeDomText(message.text()).contains(expected)) {
                matched = message;
            }
        }
        return matched;
    }

    // ==================================================================
    // A-02：图片消息确认（基线未知不得当作 0）
    // ==================================================================

    /**
     * 读取当前聊天记录中本人图片消息数量。
     * <p>
     * 调用方必须在完整聊天页就绪后、上传图片之前调用。聊天根不存在/消息列表不完整时
     * 返回 <b>-1</b>（未知基线），调用方不得把 -1 当成 0；{@link #awaitImageConfirmed} 收到
     * 负数直接返回 uncertain。为避免页面刚加载导致的瞬时不可用，内部最多重试 3 次（每秒一次）。
     *
     * @return 本人图片消息数；基线未知时返回 -1
     */
    int readOwnImageMessageCount(Page chatPage) {
        if (chatPage == null) {
            return -1;
        }
        for (int attempt = 1; attempt <= BASELINE_READ_ATTEMPTS; attempt++) {
            ChatSnapshot snapshot = readOwnMessages(chatPage);
            if (snapshot.usable()) {
                return Math.max(0, snapshot.imageCount());
            }
            log.warn("图片发送前基线不可用（第{}次尝试）：聊天根={}，字段完整={}",
                    attempt, snapshot.rootFound(), snapshot.complete());
            if (attempt < BASELINE_READ_ATTEMPTS) {
                sleepOnePoll();
            }
        }
        log.error("图片发送前基线不可用，返回 -1（未知基线）。页面：{}", safePageUrl(chatPage));
        return -1;
    }

    /**
     * 等待新图片真正进入本人消息列表并稳定下来。
     * <p>
     * 判定沿用 Boss.java 现有逻辑：新图出现（imageCount &gt; baseline）且不在发送中，
     * 连续稳定 3 秒 → confirmed；出现失败标记 → confirmed=false、uncertain=false（detail 带失败）；
     * 超时/取消/页面关闭 → uncertain=true。
     * <b>baselineCount &lt; 0</b> 表示基线未知，直接返回 uncertain，绝不把未知基线当作 0。
     *
     * @param chatPage       聊天页
     * @param baselineCount  上传前 {@link #readOwnImageMessageCount(Page)} 的基线（&lt;0 表示未知）
     * @param timeoutSeconds 轮询超时秒数（&lt;=0 时按 1 秒处理）
     * @param stop           停止回调，可为 null
     */
    ImageResult awaitImageConfirmed(Page chatPage, int baselineCount, int timeoutSeconds,
                                    Supplier<Boolean> stop) {
        if (baselineCount < 0) {
            return ImageResult.uncertain(EVIDENCE_NONE,
                    "图片基线不可用（-1），无法区分历史图片与本次发送，不确认图片发送结果");
        }
        if (chatPage == null) {
            return ImageResult.uncertain(EVIDENCE_NONE, "聊天页为空，无法确认图片发送结果");
        }
        int effectiveTimeout = Math.max(1, timeoutSeconds);
        if (timeoutSeconds <= 0) {
            log.warn("图片发送确认超时配置非法（{}），按 {} 秒处理", timeoutSeconds, effectiveTimeout);
        }

        int stableSeconds = 0;
        ChatSnapshot lastState = null;
        int lastImageCount = baselineCount;
        for (int second = 1; second <= effectiveTimeout; second++) {
            if (isStopped(stop)) {
                return ImageResult.uncertain(EVIDENCE_NONE,
                        "图片发送确认已取消：收到停止指令（第" + second + "秒），发送结果未知");
            }
            if (isPageClosed(chatPage)) {
                return ImageResult.uncertain(EVIDENCE_NONE, "聊天页已关闭，图片发送结果未知");
            }
            try {
                ChatSnapshot current = readOwnMessages(chatPage);
                if (!current.usable()) {
                    stableSeconds = 0;
                    if (second < effectiveTimeout) {
                        sleepOnePoll();
                    }
                    continue;
                }
                lastState = current;
                lastImageCount = current.imageCount();
                boolean newImageAppeared = current.imageCount() > baselineCount;

                if (newImageAppeared && current.failed()) {
                    log.error("图片发送失败：本人图片消息出现失败标记，状态：{}",
                            blankToDefault(current.status(), "无"));
                    return ImageResult.failed(EVIDENCE_IMAGE_FAILED,
                            "本人图片消息出现发送失败标记，状态：" + blankToDefault(current.status(), "无"));
                }
                if (newImageAppeared && !current.sending()) {
                    stableSeconds++;
                    // 新图片消息连续稳定三秒后再结束确认，避免刚插入的临时消息仍在后台上传。
                    if (stableSeconds >= IMAGE_STABLE_SECONDS) {
                        log.info("图片发送已确认：本人图片消息数 {}→{}，状态：{}",
                                baselineCount, current.imageCount(), blankToDefault(current.status(), "无"));
                        return ImageResult.confirmed(EVIDENCE_IMAGE_CONFIRMED,
                                "新图片消息连续稳定" + IMAGE_STABLE_SECONDS + "秒，确认发送完成");
                    }
                } else {
                    stableSeconds = 0;
                }
            } catch (Throwable probeError) {
                stableSeconds = 0;
                log.debug("第{}秒读取图片发送状态失败，稍后重试：{}", second, safeMessage(probeError));
            }
            if (second < effectiveTimeout) {
                sleepOnePoll();
            }
        }

        if (lastState != null && lastState.sending()) {
            return ImageResult.uncertain(EVIDENCE_NONE,
                    "超时：新图片仍处于上传/发送中，未确认发送成功");
        }
        if (lastState != null && lastState.imageCount() > baselineCount) {
            return ImageResult.uncertain(EVIDENCE_NONE,
                    "超时：图片消息已出现但未连续稳定" + IMAGE_STABLE_SECONDS + "秒，证据不足");
        }
        return ImageResult.uncertain(EVIDENCE_NONE,
                "超时：未检测到本人新增图片消息（基线" + baselineCount + "条，最后" + lastImageCount + "条）");
    }

    // ==================================================================
    // DOM 探测
    // ==================================================================

    /**
     * 读取本人消息状态。逐 frame 尝试，返回第一个找到聊天根节点的 frame 的快照；
     * 失活 frame 或页面切换异常只记 debug，不影响调用方。
     */
    private ChatSnapshot readOwnMessages(Page chatPage) {
        ChatSnapshot empty = ChatSnapshot.empty();
        if (chatPage == null) {
            return empty;
        }
        try {
            for (Frame frame : chatPage.frames()) {
                if (frame == null || frame.isDetached()) {
                    continue;
                }
                try {
                    Object rawState = frame.evaluate(CHAT_STATE_SCRIPT);
                    if (rawState == null) {
                        continue;
                    }
                    ChatSnapshot snapshot = parseChatSnapshot(rawState);
                    if (snapshot.rootFound()) {
                        return snapshot;
                    }
                } catch (Throwable staleFrame) {
                    // 页面切换中的旧 frame 会失效（Object doesn't exist: frame），继续试下一帧。
                    log.debug("读取聊天消息状态时 frame 已失效，尝试下一帧：{}", safeMessage(staleFrame));
                }
            }
        } catch (Throwable frameListError) {
            log.debug("读取聊天消息状态时页面正在切换：{}", safeMessage(frameListError));
        }
        return empty;
    }

    /** 解析 JS 返回的本人消息状态 JSON（字符串或已是 JSONObject 都兼容）。 */
    private ChatSnapshot parseChatSnapshot(Object rawState) {
        JSONObject json = rawState instanceof JSONObject
                ? (JSONObject) rawState
                : new JSONObject(String.valueOf(rawState));

        List<String> texts = new ArrayList<>();
        JSONArray textArray = json.optJSONArray("texts");
        if (textArray != null) {
            for (int i = 0; i < textArray.length(); i++) {
                texts.add(textArray.optString(i, ""));
            }
        }

        List<MessageInfo> tail = new ArrayList<>();
        JSONArray tailArray = json.optJSONArray("tail");
        if (tailArray != null) {
            for (int i = 0; i < tailArray.length(); i++) {
                JSONObject item = tailArray.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                tail.add(new MessageInfo(
                        item.optString("text", ""),
                        item.optBoolean("hasImage", false),
                        item.optBoolean("sending", false),
                        item.optBoolean("failed", false),
                        item.optString("status", "")));
            }
        }

        // 「字段完整」= 探测脚本确实返回了消息列表的核心字段，避免把半截 JSON 当有效基线。
        boolean complete = json.has("ownCount") && json.has("textCount")
                && json.has("texts") && json.has("tail");

        return new ChatSnapshot(
                json.optBoolean("rootFound", false),
                complete,
                json.optInt("ownCount", 0),
                json.optInt("textCount", 0),
                json.optInt("imageCount", 0),
                json.optBoolean("lastFailed", false),
                json.optBoolean("lastSending", false),
                json.optBoolean("failed", false),
                json.optBoolean("sending", false),
                json.optString("status", ""),
                texts,
                tail);
    }

    /**
     * 读取聊天身份证据。逐 frame 尝试：rootFound / titleFound 取所有 frame 的并集，
     * 标题取第一个非空文本；失活 frame 跳过。iframe 切换导致当前 frame 没有聊天根时返回
     * rootFound=false，由调用方按 UNKNOWN 处理。
     */
    private ChatIdentity readChatIdentity(Page chatPage) {
        boolean rootFound = false;
        boolean titleFound = false;
        String title = "";
        try {
            for (Frame frame : chatPage.frames()) {
                if (frame == null || frame.isDetached()) {
                    continue;
                }
                try {
                    Object rawState = frame.evaluate(CHAT_IDENTITY_SCRIPT);
                    if (rawState == null) {
                        continue;
                    }
                    JSONObject json = rawState instanceof JSONObject
                            ? (JSONObject) rawState
                            : new JSONObject(String.valueOf(rawState));
                    rootFound = rootFound || json.optBoolean("rootFound", false);
                    if (!titleFound && json.optBoolean("titleFound", false)) {
                        String candidate = json.optString("title", "");
                        if (!normalizeDomText(candidate).isEmpty()) {
                            title = candidate;
                            titleFound = true;
                        }
                    }
                    if (rootFound && titleFound) {
                        return new ChatIdentity(true, true, title);
                    }
                } catch (Throwable staleFrame) {
                    log.debug("读取聊天身份时 frame 已失效，尝试下一帧：{}", safeMessage(staleFrame));
                }
            }
        } catch (Throwable frameListError) {
            log.debug("读取聊天身份时页面正在切换：{}", safeMessage(frameListError));
        }
        return new ChatIdentity(rootFound, titleFound, title);
    }

    // ==================================================================
    // 通用工具
    // ==================================================================

    /**
     * 身份核对失败时的只读诊断：把页面里 class 含 chat/header/title/name/user/boss/position
     * 的元素与前 25 个非空文本写日志，便于 BOSS 页面改版后快速定位新的标题容器。
     * 不发送、不点击，仅本地日志。
     */
    private void logIdentityDiagnostics(Page chatPage) {
        try {
            for (Frame frame : chatPage.frames()) {
                if (frame == null || frame.isDetached()) {
                    continue;
                }
                try {
                    Object raw = frame.evaluate("""
                            () => {
                              const out = [];
                              const seen = new Set();
                              for (const el of document.querySelectorAll('[class]')) {
                                const cls = String(el.className || '');
                                if (!/chat|header|title|name|user|boss|position/i.test(cls)) continue;
                                const text = (el.innerText || el.textContent || '').replace(/\\s+/g, ' ').trim();
                                if (!text || text.length > 160) continue;
                                const key = cls + '|' + text.slice(0, 60);
                                if (seen.has(key)) continue;
                                seen.add(key);
                                out.push(cls.slice(0, 120) + ' => ' + text.slice(0, 120));
                                if (out.length >= 25) break;
                              }
                              return out.join('\\n');
                            }
                            """);
                    if (raw != null && !String.valueOf(raw).isBlank()) {
                        log.warn("聊天身份诊断（class => text 候选）：\n{}", raw);
                        return;
                    }
                } catch (Throwable ignored) {
                    // 单个 frame 失效不影响诊断循环
                }
            }
        } catch (Throwable e) {
            log.debug("输出聊天身份诊断失败：{}", safeMessage(e));
        }
    }

    /** DOM 文本归一化：全角空格转半角、连续空白压成单个空格、去首尾空白。 */
    static String normalizeDomText(String text) {
        if (text == null) {
            return "";
        }
        return text.replace('\u3000', ' ').replaceAll("\\s+", " ").trim();
    }

    /** 轮询休眠；被中断或注入实现异常时不打断确认流程。 */
    private void sleepOnePoll() {
        try {
            sleeper.accept(POLL_INTERVAL_SECONDS);
        } catch (Throwable sleepError) {
            log.debug("发送确认轮询休眠异常，继续下一轮：{}", safeMessage(sleepError));
        }
    }

    /** 点击按钮的超时：与确认总超时联动，但不小于 1 秒、不超过 5 秒。 */
    private long clickTimeoutMillis(int timeoutSeconds) {
        return Math.max(1_000L, Math.min(timeoutSeconds, 5) * 1_000L);
    }

    private boolean isStopped(Supplier<Boolean> stop) {
        if (stop == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(stop.get());
        } catch (Throwable stopError) {
            log.debug("停止回调异常，按未停止处理：{}", safeMessage(stopError));
            return false;
        }
    }

    private boolean isPageClosed(Page chatPage) {
        try {
            return chatPage.isClosed();
        } catch (Throwable pageError) {
            // 连页面状态都读不到时按不可用处理，走「不确定」分支。
            log.debug("读取聊天页状态失败，按页面不可用处理：{}", safeMessage(pageError));
            return true;
        }
    }

    /** 异常信息兜底：不带堆栈进入 detail，避免数据库字段过长。 */
    private String safeMessage(Throwable error) {
        if (error == null) {
            return "未知异常";
        }
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    /** 读取页面 URL：页面切换/关闭时返回 null，不抛异常。 */
    private String safePageUrl(Page chatPage) {
        if (chatPage == null) {
            return null;
        }
        try {
            return chatPage.url();
        } catch (Throwable pageError) {
            log.debug("读取聊天页 URL 失败：{}", safeMessage(pageError));
            return null;
        }
    }

    private String blankToDefault(String text, String defaultValue) {
        String normalized = normalizeDomText(text);
        return normalized.isEmpty() ? defaultValue : normalized;
    }

    /** detail 中标题等长文本的截断，避免撑爆日志与数据库字段。 */
    private String abbreviate(String text, int maxLength) {
        String normalized = normalizeDomText(text);
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "…";
    }

    // ==================================================================
    // 结果与快照类型（接口按《阶段 A+ 修复-接口约定》第 1/2 节冻结）
    // ==================================================================

    /** 身份核对结果三态：只有 VERIFIED 允许继续发送。 */
    enum IdentityOutcome { VERIFIED, MISMATCH, UNKNOWN }

    /** 身份核对结果（fail-closed）。 */
    record IdentityResult(IdentityOutcome outcome, String evidenceLevel, String detail) {
        static IdentityResult verified(String evidenceLevel, String detail) {
            return new IdentityResult(IdentityOutcome.VERIFIED, evidenceLevel, detail);
        }

        static IdentityResult mismatch(String evidenceLevel, String detail) {
            return new IdentityResult(IdentityOutcome.MISMATCH, evidenceLevel, detail);
        }

        static IdentityResult unknown(String evidenceLevel, String detail) {
            return new IdentityResult(IdentityOutcome.UNKNOWN, evidenceLevel, detail);
        }
    }

    /** 文字发送确认结果：confirmed / failed / uncertain 三态互斥；clickAttempted 表示是否已点击。 */
    record TextResult(boolean confirmed, boolean uncertain, boolean failed,
                      boolean clickAttempted, String evidenceLevel, String detail) {
        static TextResult confirmed(String evidenceLevel, String detail) {
            return new TextResult(true, false, false, true, evidenceLevel, detail);
        }

        static TextResult failed(boolean clickAttempted, String evidenceLevel, String detail) {
            return new TextResult(false, false, true, clickAttempted, evidenceLevel, detail);
        }

        static TextResult uncertain(boolean clickAttempted, String evidenceLevel, String detail) {
            return new TextResult(false, true, false, clickAttempted, evidenceLevel, detail);
        }
    }

    /** 图片发送确认结果：失败时 confirmed=false 且 uncertain=false，由调用方映射 IMAGE_FAILED。 */
    record ImageResult(boolean confirmed, boolean uncertain, String evidenceLevel, String detail) {
        static ImageResult confirmed(String evidenceLevel, String detail) {
            return new ImageResult(true, false, evidenceLevel, detail);
        }

        static ImageResult failed(String evidenceLevel, String detail) {
            return new ImageResult(false, false, evidenceLevel, detail);
        }

        static ImageResult uncertain(String evidenceLevel, String detail) {
            return new ImageResult(false, true, evidenceLevel, detail);
        }
    }

    /** 一条本人消息的探测快照。 */
    private record MessageInfo(String text, boolean hasImage, boolean sending, boolean failed, String status) {
    }

    /** 一次本人消息列表探测的汇总快照。 */
    private record ChatSnapshot(boolean rootFound, boolean complete, int ownCount, int textCount, int imageCount,
                                boolean lastFailed, boolean lastSending,
                                boolean failed, boolean sending, String status,
                                List<String> texts, List<MessageInfo> tail) {
        static ChatSnapshot empty() {
            return new ChatSnapshot(false, false, 0, 0, 0, false, false, false, false,
                    "", List.of(), List.of());
        }

        /** 基线是否可用：聊天根存在且核心字段完整。 */
        boolean usable() {
            return rootFound && complete;
        }

        /**
         * 计算「点击后本次新增」的本人消息。
         * <p>
         * 通过基线最后一条消息作锚点：若当前尾部仍以基线尾部结尾，说明消息数增长来自历史消息
         * 前插/分页加载，不是本次新增；锚点之后的才是本次新增。锚点不在尾部窗口内时，
         * 窗口内消息都晚于锚点，按新增处理。
         */
        List<MessageInfo> appendedMessages(ChatSnapshot baseline) {
            if (!usable() || baseline == null || !baseline.usable()) {
                return List.of();
            }
            int grown = ownCount - baseline.ownCount;
            if (grown <= 0) {
                return List.of();
            }
            if (baseline.tail.isEmpty()) {
                // 基线没有可锚定的消息（会话刚开始）：当前尾部消息都可能是本次新增。
                return tail;
            }
            if (endsWithBaselineTail(baseline.tail)) {
                // 本人消息数增加但尾部序列没变：历史消息前插，不是本次发送。
                return List.of();
            }
            MessageInfo anchor = baseline.tail.get(baseline.tail.size() - 1);
            int anchorIndex = -1;
            for (int i = tail.size() - 1; i >= 0; i--) {
                if (sameMessage(tail.get(i), anchor)) {
                    anchorIndex = i;
                    break;
                }
            }
            if (anchorIndex < 0) {
                // 锚点已滑出尾部窗口：窗口内都是锚点之后的消息（最多取尾部窗口内条数）。
                int from = Math.max(0, tail.size() - Math.min(grown, tail.size()));
                return tail.subList(from, tail.size());
            }
            if (anchorIndex + 1 >= tail.size()) {
                return List.of();
            }
            return tail.subList(anchorIndex + 1, tail.size());
        }

        /** 当前尾部是否仍以基线尾部结尾（历史消息前插时成立）。 */
        private boolean endsWithBaselineTail(List<MessageInfo> baselineTail) {
            if (tail.size() < baselineTail.size()) {
                return false;
            }
            int offset = tail.size() - baselineTail.size();
            for (int i = 0; i < baselineTail.size(); i++) {
                if (!sameMessage(tail.get(offset + i), baselineTail.get(i))) {
                    return false;
                }
            }
            return true;
        }

        /** 消息同一性判定：文本（归一化后）+ 是否图片消息。 */
        private boolean sameMessage(MessageInfo left, MessageInfo right) {
            return left.hasImage() == right.hasImage()
                    && normalizeDomText(left.text()).equals(normalizeDomText(right.text()));
        }
    }

    /** 聊天身份证据快照。 */
    private record ChatIdentity(boolean rootFound, boolean titleFound, String title) {
    }
}
