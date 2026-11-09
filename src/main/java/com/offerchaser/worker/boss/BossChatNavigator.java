package com.offerchaser.worker.boss;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 从岗位详情页进入聊天会话的导航器（只负责点开入口与确认弹层，不输入、不发送）。
 *
 * <p>阶段 A+（A-03）：{@link #open} 不再用 null/聊天对象两态表达结果，而是返回 {@link OpenResult}：
 * 明确区分「点击前就放弃」「点击已发生但结果未知」「已拿到可编辑输入框」。调用方据此决定预算
 * 是释放还是按不确定处理——只要点击动作发生过，就不能再声称没有触发外部动作。</p>
 */
@Slf4j
final class BossChatNavigator implements AutoCloseable {

    /** 聊天输入框选择器：新版是 contenteditable 的 div，老版是 textarea。 */
    private static final String CHAT_INPUT_SELECTOR =
            "div#chat-input.chat-input[contenteditable='true'], textarea.input-area";
    /** 「已向BOSS发送消息」确认弹层里的【继续沟通】按钮（2026-10-21 从真实页面采集）。 */
    private static final String DIALOG_CONFIRM_SELECTOR =
            ".greet-boss-dialog:visible .greet-boss-footer a.sure-btn";
    /** 确认弹层里的「留在此页」：以它为锚点向上找同时包含两个按钮的最近容器，避免误点同名岗位按钮。 */
    private static final String STAY_HERE_SELECTOR = ":text-is('留在此页'):visible";
    private static final String DIALOG_ANCESTOR_XPATH =
            "xpath=ancestor::*[not(self::body or self::html) and .//*[normalize-space(.)='继续沟通']][1]";
    private static final String CONTINUE_LABEL = "继续沟通";
    /** 单次点击入口按钮的等待上限（总超时更长时也不会卡在这里）。 */
    private static final long CLICK_TIMEOUT_MS = 5_000L;
    /** 确认弹层只在点击后的 1 秒内探测，之后的延迟弹层不算本次确认。 */
    private static final long DIALOG_DETECT_NANOS = 1_000_000_000L;
    private static final long POLL_WAIT_MS = 100L;
    private static final long EDITABLE_TIMEOUT_MS = 500L;

    /** 聊天页就绪后的三样东西：页面、输入框、本次是否重复沟通。 */
    record Chat(Page page, Locator input, boolean repeat) {
    }

    /**
     * 导航三态：
     * <ul>
     *   <li>{@link #NOT_CLICKED}：还没触发任何外部动作，可以安全释放预算；</li>
     *   <li>{@link #CLICKED_UNKNOWN}：点击动作已发生但没拿到输入框，必须按不确定处理；</li>
     *   <li>{@link #OPENED}：已拿到可编辑输入框，可以继续核对会话身份。</li>
     * </ul>
     */
    enum OpenOutcome {
        NOT_CLICKED,
        CLICKED_UNKNOWN,
        OPENED
    }

    /** 导航结构化结果；{@code chat} 只在 OPENED 时非空。 */
    record OpenResult(OpenOutcome outcome, Chat chat, String detail) {

        static OpenResult opened(Chat chat, String detail) {
            return new OpenResult(OpenOutcome.OPENED, chat, detail);
        }

        static OpenResult clickedUnknown(String detail) {
            return new OpenResult(OpenOutcome.CLICKED_UNKNOWN, null, detail);
        }

        static OpenResult notClicked(String detail) {
            return new OpenResult(OpenOutcome.NOT_CLICKED, null, detail);
        }
    }

    private final Page origin;
    private final List<Page> popups = new ArrayList<>();
    private final Consumer<Page> popupCollector = popups::add;

    BossChatNavigator(Page origin) {
        this.origin = origin;
        origin.onPopup(popupCollector);
    }

    /**
     * 点击沟通入口并等待聊天页就绪。
     *
     * @param button      岗位详情页的沟通按钮
     * @param repeat      页面上显示的是否为「继续沟通」（已有会话）
     * @param allowRepeat 配置是否允许重复沟通
     * @param stop        取消信号；点击前/等待中任一时刻返回 true 都会立刻结束
     * @param timeoutMs   从调用开始到放弃等待的总超时
     * @return 三态结果，永不返回 null
     */
    OpenResult open(Locator button, boolean repeat, boolean allowRepeat,
                    Supplier<Boolean> stop, long timeoutMs) {
        if (stopRequested(stop)) {
            return OpenResult.notClicked("点击前收到停止指令，未点击沟通入口");
        }
        if (repeat && !allowRepeat) {
            return OpenResult.notClicked("未开启重复沟通开关，未点击沟通入口");
        }

        String entryUrl = origin.url();
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;

        // 从这里开始点击动作已经发生：后续无论遇到什么异常，都只能返回 CLICKED_UNKNOWN。
        clickEntryButton(button, timeoutMs);
        long dialogDeadline = Math.min(deadline, System.nanoTime() + DIALOG_DETECT_NANOS);

        Page dialogPage = null;
        String dialogUrl = null;
        boolean dialogClicked = false;

        while (System.nanoTime() < deadline) {
            if (stopRequested(stop)) {
                return OpenResult.clickedUnknown("已点击沟通入口，等待聊天页时收到停止指令，结果未知");
            }
            List<Page> candidates = pagesToInspect();
            if (candidates.isEmpty()) {
                return OpenResult.clickedUnknown("已点击沟通入口，但相关页面在等待过程中全部关闭，结果未知");
            }
            for (Page candidate : candidates) {
                if (candidate.isClosed()) {
                    continue;
                }
                try {
                    if (!dialogClicked && System.nanoTime() < dialogDeadline) {
                        Locator confirm = findConfirmation(candidate);
                        if (confirm != null) {
                            dialogPage = candidate;
                            dialogUrl = candidate.url();
                            dialogClicked = true;
                            OpenResult dialogFailure = clickConfirmation(confirm, dialogDeadline);
                            if (dialogFailure != null) {
                                return dialogFailure;
                            }
                            // 每次只点一次；弹层还在或跳转未完成时继续等待聊天页。
                            continue;
                        }
                    }
                    if (isOldPage(candidate, dialogClicked, dialogPage, dialogUrl, repeat, entryUrl)) {
                        continue;
                    }
                    Locator input = findChatInput(candidate);
                    if (input != null) {
                        return OpenResult.opened(new Chat(candidate, input, repeat),
                                "已点击沟通入口并找到可编辑输入框");
                    }
                } catch (PlaywrightException pageSwitching) {
                    log.debug("聊天页正在切换，继续等待：{}", pageSwitching.getMessage());
                }
            }
            if (!pauseBeforeNextPoll()) {
                return OpenResult.clickedUnknown("已点击沟通入口，但相关页面在等待过程中全部关闭，结果未知");
            }
        }
        log.warn("等待聊天页超时，未取得可编辑输入框；已点击沟通入口，结果按未知处理");
        return OpenResult.clickedUnknown(
                "已点击沟通入口，但" + timeoutMs + "ms内未找到可编辑输入框，结果未知");
    }

    /** 点击入口按钮；页面跳转导致的 PlaywrightException 属于正常现象，继续检查聊天页。 */
    private void clickEntryButton(Locator button, long timeoutMs) {
        try {
            button.click(new Locator.ClickOptions().setTimeout(Math.min(timeoutMs, CLICK_TIMEOUT_MS)));
        } catch (PlaywrightException navigationInProgress) {
            log.debug("点击岗位按钮时发生页面切换，继续检查聊天入口：{}", navigationInProgress.getMessage());
        }
    }

    /**
     * 点击确认弹层里的【继续沟通】。
     *
     * @return 点击失败时返回「结果未知」；窗口已耗尽或点击成功时返回 null
     */
    private OpenResult clickConfirmation(Locator confirm, long dialogDeadline) {
        long remainingMs = (dialogDeadline - System.nanoTime()) / 1_000_000L;
        if (remainingMs <= 0) {
            return null;
        }
        try {
            confirm.click(new Locator.ClickOptions().setTimeout(remainingMs));
            log.info("已点击已沟通确认框内的【继续沟通】，等待聊天页跳转");
            return null;
        } catch (PlaywrightException clickFailure) {
            log.warn("弹框确认未在1秒检测窗口内完成，跳过当前岗位");
            return OpenResult.clickedUnknown("已点击沟通入口，但确认弹窗未能在检测窗口内点击，结果未知");
        }
    }

    /** 当前需要检查的页面：优先看点击产生的弹窗，没有弹窗（或全关了）时回看原页面。 */
    private List<Page> pagesToInspect() {
        List<Page> openPages = new ArrayList<>(popups.size() + 1);
        for (Page popup : popups) {
            if (!popup.isClosed()) {
                openPages.add(popup);
            }
        }
        if (openPages.isEmpty()) {
            openPages.add(origin);
        }
        return openPages;
    }

    /**
     * 弹层所在页（URL 变化之前）与重复沟通时仍停留在岗位页的标签页，都不能当作聊天页，
     * 否则会把页面上残留的输入框误判为已进入会话。
     */
    private boolean isOldPage(Page candidate, boolean dialogClicked, Page dialogPage, String dialogUrl,
                              boolean repeat, String entryUrl) {
        if (dialogClicked && candidate == dialogPage && candidate.url().equals(dialogUrl)) {
            return true;
        }
        return repeat && candidate == origin && candidate.url().equals(entryUrl);
    }

    /** 聊天输入框必须可见且可编辑；每轮都重新定位，避免导航后继续使用旧 frame 里的输入框。 */
    private Locator findChatInput(Page page) {
        Locator input = page.locator(CHAT_INPUT_SELECTOR).first();
        if (!input.isVisible()) {
            return null;
        }
        return input.isEditable(new Locator.IsEditableOptions().setTimeout(EDITABLE_TIMEOUT_MS)) ? input : null;
    }

    /** 每轮检查前的短等待；所有相关页面都已关闭时返回 false。 */
    private boolean pauseBeforeNextPoll() {
        Page pump = firstLivePage();
        if (pump == null) {
            return false;
        }
        try {
            pump.waitForTimeout(POLL_WAIT_MS);
        } catch (PlaywrightException pageSwitching) {
            log.debug("等待聊天页时页面已切换：{}", pageSwitching.getMessage());
        }
        return true;
    }

    private Page firstLivePage() {
        if (!origin.isClosed()) {
            return origin;
        }
        for (Page popup : popups) {
            if (!popup.isClosed()) {
                return popup;
            }
        }
        return null;
    }

    /**
     * 在页面上找「已沟通」确认弹层的【继续沟通】按钮：先试标准弹层，再按「留在此页」的
     * 共同祖先进阶定位；找不到返回 null。
     */
    private Locator findConfirmation(Page page) {
        Locator sureButton = page.locator(DIALOG_CONFIRM_SELECTOR);
        if (sureButton.count() == 1 && sureButton.isVisible()) {
            String label = sureButton.textContent();
            if (label != null && CONTINUE_LABEL.equals(label.trim())) {
                return sureButton;
            }
        }
        Locator stayHereButtons = page.locator(STAY_HERE_SELECTOR);
        int stayCount = stayHereButtons.count();
        for (int index = 0; index < stayCount; index++) {
            Locator dialog = stayHereButtons.nth(index).locator(DIALOG_ANCESTOR_XPATH);
            Locator confirm = dialog.getByText(CONTINUE_LABEL,
                    new Locator.GetByTextOptions().setExact(true));
            if (confirm.count() == 1 && confirm.isVisible()) {
                return confirm;
            }
        }
        return null;
    }

    private static boolean stopRequested(Supplier<Boolean> stop) {
        return stop != null && Boolean.TRUE.equals(stop.get());
    }

    @Override
    public void close() {
        try {
            origin.offPopup(popupCollector);
        } catch (PlaywrightException detachFailure) {
            log.debug("解除弹窗监听失败：{}", detachFailure.getMessage());
        }
        // 只清理由本轮岗位点击产生的页面，不影响其他业务标签页。
        List<Page> pages = new ArrayList<>(popups);
        pages.add(origin);
        for (Page page : pages) {
            closeQuietly(page);
        }
    }

    private void closeQuietly(Page page) {
        try {
            if (!page.isClosed()) {
                page.close();
            }
        } catch (PlaywrightException closeFailure) {
            log.debug("关闭本轮聊天页失败：{}", closeFailure.getMessage());
        }
    }
}
