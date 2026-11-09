package com.offerchaser.worker.boss;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * BOSS 聊天列表只读解析器（D2）。
 *
 * <p>依据 2026-11-07 真实页面 DOM：
 * <ul>
 *   <li>聊天行：{@code li[role='listitem']}；HR 名 {@code .name-box .name-text}；
 *       公司/职位在 {@code .name-box} 的其他 span；未读红点 {@code .notice-badge}；</li>
 *   <li>最后一条消息若由自己发出，带 {@code .message-status}：
 *       {@code status-read} = 已读、{@code status-delivery} = 送达未读；</li>
 *   <li>最后一条消息若由 HR 发出，没有 {@code .message-status}；未读红点表示 HR 新消息。</li>
 * </ul>
 *
 * <p>这个类只读 DOM，不发送任何消息；打开会话仅用于判断“第一条消息是谁发的”，
 * 因此不会触碰输入框或发送按钮。打开会话可能让 BOSS 把 HR 消息标为已读，这是
 * BOSS 自身行为，不是本程序写状态。
 */
@Slf4j
@Component
public class BossChatStatusReader {

    /** 聊天列表中的一行（只保留 D2 需要的字段）。 */
    public record ChatItem(int index, String hrName, String companyName, String roleName,
                           String unreadText, int unreadCount, boolean hasOwnStatus,
                           String ownStatusText, boolean ownRead, boolean ownDelivered,
                           String lastMessage, String chatTime) {
    }

    /** 会话第一条有效消息的发送方。 */
    public enum Initiator { SELF, HR, UNKNOWN }

    private static final String CHAT_URL = "https://www.zhipin.com/web/geek/chat";

    private static final String LIST_SCRIPT = """
            () => {
              const items = Array.from(document.querySelectorAll("li[role='listitem']"));
              return items.map((li, index) => {
                const clean = (value) => (value || '').replace(/\\s+/g, ' ').trim();
                const hr = li.querySelector('.name-box .name-text');
                const boxSpans = Array.from(li.querySelectorAll('.name-box > span'))
                  .filter(el => !el.classList.contains('name-text'));
                const company = boxSpans[0] ? clean(boxSpans[0].innerText) : '';
                const role = boxSpans.length > 1
                  ? boxSpans.slice(1).map(el => clean(el.innerText)).filter(Boolean).join(' / ')
                  : '';
                const badge = li.querySelector('.notice-badge');
                const unreadText = badge ? clean(badge.innerText) : '';
                const status = li.querySelector('.message-status');
                const statusText = status ? clean(status.innerText) : '';
                const statusClass = status ? (status.className || '') : '';
                return {
                  index,
                  hrName: hr ? clean(hr.innerText) : '',
                  companyName: company,
                  roleName: role,
                  unreadText,
                  unreadCount: parseInt(unreadText, 10) || (badge ? 1 : 0),
                  hasOwnStatus: Boolean(status),
                  ownStatusText: statusText,
                  ownRead: /status-read/.test(statusClass) || /已读/.test(statusText),
                  ownDelivered: /status-delivery/.test(statusClass) || /送达/.test(statusText),
                  lastMessage: (() => {
                    const el = li.querySelector('.last-msg-text');
                    return el ? clean(el.innerText) : '';
                  })(),
                  chatTime: (() => {
                    const el = li.querySelector('.time');
                    return el ? clean(el.innerText) : '';
                  })()
                };
              });
            }
            """;

    private static final String FIRST_MESSAGE_SCRIPT = """
            () => {
              const root = document.querySelector('.chat-message .im-list')
                || document.querySelector('.chat-record');
              if (!root) return { messages: 0, first: '', firstText: '' };
              const clean = (value) => (value || '').replace(/\\s+/g, ' ').trim();
              const messages = Array.from(root.querySelectorAll('li.message-item'));
              for (const item of messages) {
                const text = clean(item.innerText);
                // 跳过系统提示（如“你与该职位竞争者PK情况”），只看真实聊天消息
                if (!text || /查看详细分析|竞争者PK|系统消息/.test(text)) continue;
                return {
                  messages: messages.length,
                  first: item.classList.contains('item-myself') ? 'SELF'
                    : item.classList.contains('item-friend') ? 'HR' : 'UNKNOWN',
                  firstText: text.slice(0, 160)
                };
              }
              return { messages: messages.length, first: 'UNKNOWN', firstText: '' };
            }
            """;

    /** 打开 BOSS 聊天页并读取当前可见聊天列表。调用方负责 Playwright 线程与登录态。 */
    public List<ChatItem> readChatList(Page page) {
        page.navigate(CHAT_URL, new Page.NavigateOptions()
                .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                .setTimeout(45_000));
        try {
            // BOSS 聊天列表是异步渲染的；固定 sleep 在冷启动时可能读到空列表。
            page.waitForSelector("li[role='listitem']",
                    new Page.WaitForSelectorOptions().setTimeout(12_000));
        } catch (Exception e) {
            log.debug("等待聊天列表超时，按当前 DOM 读取: {}", e.getMessage());
        }
        page.waitForTimeout(1_200);
        Object raw = page.evaluate(LIST_SCRIPT);
        List<ChatItem> items = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return items;
        }
        for (Object value : list) {
            if (!(value instanceof java.util.Map<?, ?> map)) continue;
            try {
                items.add(new ChatItem(
                        intValue(map.get("index")),
                        stringValue(map.get("hrName")),
                        stringValue(map.get("companyName")),
                        stringValue(map.get("roleName")),
                        stringValue(map.get("unreadText")),
                        intValue(map.get("unreadCount")),
                        Boolean.TRUE.equals(map.get("hasOwnStatus")),
                        stringValue(map.get("ownStatusText")),
                        Boolean.TRUE.equals(map.get("ownRead")),
                        Boolean.TRUE.equals(map.get("ownDelivered")),
                        stringValue(map.get("lastMessage")),
                        stringValue(map.get("chatTime"))
                ));
            } catch (Exception e) {
                log.debug("解析聊天行失败: {}", e.getMessage());
            }
        }
        return items;
    }

    /**
     * 按聊天行内容（HR 名/公司）打开会话，判断第一条有效消息是谁发的。
     * 不按 index 点击：打开会话后 BOSS 可能重排聊天列表，index 会漂移。
     */
    public Initiator detectInitiator(Page page, ChatItem item) {
        if (item == null) return Initiator.UNKNOWN;
        try {
            Object targetIndex = page.evaluate("""
                    (target) => {
                      const clean = (value) => (value || '').replace(/\\s+/g, ' ').trim();
                      const rows = Array.from(document.querySelectorAll("li[role='listitem']"));
                      const wantedHr = clean(target.hrName);
                      const wantedCompany = clean(target.companyName);
                      for (let i = 0; i < rows.length; i++) {
                        const li = rows[i];
                        const hrEl = li.querySelector('.name-box .name-text');
                        const hr = hrEl ? clean(hrEl.innerText) : '';
                        const spans = Array.from(li.querySelectorAll('.name-box > span'))
                          .filter(el => !el.classList.contains('name-text'));
                        const company = spans[0] ? clean(spans[0].innerText) : '';
                        const hrOk = !wantedHr || hr === wantedHr;
                        const companyOk = !wantedCompany || company === wantedCompany
                          || company.includes(wantedCompany) || wantedCompany.includes(company);
                        if (hrOk && companyOk) return i;
                      }
                      return target.index >= 0 ? target.index : -1;
                    }
                    """, java.util.Map.of(
                    "hrName", item.hrName() == null ? "" : item.hrName(),
                    "companyName", item.companyName() == null ? "" : item.companyName(),
                    "index", item.index()));
            int rowIndex = targetIndex instanceof Number number ? number.intValue() : -1;
            if (rowIndex < 0) return Initiator.UNKNOWN;
            Locator rows = page.locator("li[role='listitem']");
            if (rowIndex >= rows.count()) return Initiator.UNKNOWN;
            // 用 Playwright 的真实点击（Vue 聊天列表可能忽略 synthetic click）；
            // force 跳过动画/可点击性等待，避免列表单项检测把一次同步拖到一分钟以上。
            rows.nth(rowIndex).click(new Locator.ClickOptions().setTimeout(2_500).setForce(true));
            page.waitForTimeout(750);
            Object raw = page.evaluate(FIRST_MESSAGE_SCRIPT);
            if (raw instanceof java.util.Map<?, ?> map) {
                String first = stringValue(map.get("first"));
                if ("SELF".equals(first)) return Initiator.SELF;
                if ("HR".equals(first)) return Initiator.HR;
            }
        } catch (Exception e) {
            log.debug("判断聊天发起方失败 | hr={} | company={} | {}", item.hrName(), item.companyName(), e.getMessage());
        }
        return Initiator.UNKNOWN;
    }

    /** 仅用于诊断/测试的原始列表脚本。 */
    public static String listScript() {
        return LIST_SCRIPT;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static int intValue(Object value) {
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(String.valueOf(value).replaceAll("\\D", ""));
        } catch (Exception e) {
            return 0;
        }
    }
}
