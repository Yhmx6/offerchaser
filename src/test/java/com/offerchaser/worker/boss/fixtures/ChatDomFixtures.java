package com.offerchaser.worker.boss.fixtures;

import org.json.JSONObject;

/**
 * 真实聊天页 HTML 夹具（阶段 A+ A+5）。
 * <p>
 * 用 {@code page.setContent(...)} / 路由替换把聊天 DOM 注入真实 Playwright 页面，
 * 让 {@code ChatSendConfirmer} 的探测脚本在真实浏览器里执行，而不是只 mock
 * {@code frame.evaluate} 的返回值。不访问招聘网站、不发任何消息。
 * <p>
 * 结构对应 Boss.java 现有探测口径：聊天根 {@code .chat-message .im-list}（或 {@code .chat-record}），
 * 本人消息 {@code li.message-item.item-myself}，文字/图片都在 {@code .message-content} 内，
 * 状态在 {@code .message-status}，聊天标题在 {@code .chat-title} / {@code .chat-header}。
 */
public final class ChatDomFixtures {

    /** 1x1 透明 GIF，足够让图片消息探测（img 节点）命中。 */
    public static final String OWN_IMAGE_SRC =
            "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==";

    private ChatDomFixtures() {
    }

    /**
     * 带聊天根的完整聊天页。
     *
     * @param pageTitle     document.title（身份核对不应使用它）
     * @param chatTitle     聊天区域标题（.chat-title）
     * @param messagesHtml  本人消息 li 列表 HTML
     * @param extraHtml     额外内容（发送按钮 / 脚本等）
     */
    public static String pageWithChat(String pageTitle, String chatTitle, String messagesHtml, String extraHtml) {
        return """
                <!DOCTYPE html>
                <html><head><meta charset="utf-8"><title>%s</title></head>
                <body>
                  <div class="chat-header"><span class="chat-title">%s</span></div>
                  <div class="chat-message"><ul class="im-list">%s</ul></div>
                  %s
                </body></html>
                """.formatted(html(pageTitle), html(chatTitle), messagesHtml, extraHtml);
    }

    /**
     * 没有聊天根节点的聊天页（只有聊天头标题和额外内容）：
     * 用于验证「聊天根不存在 → 身份 UNKNOWN / 文字基线不可用不点击 / 图片基线 -1」。
     */
    public static String chatWithoutRoot(String pageTitle, String chatTitle, String extraHtml) {
        return """
                <!DOCTYPE html>
                <html><head><meta charset="utf-8"><title>%s</title></head>
                <body>
                  <div class="chat-header"><span class="chat-title">%s</span></div>
                  <div class="chat-panel">聊天记录还没加载出来</div>
                  %s
                </body></html>
                """.formatted(html(pageTitle), html(chatTitle), extraHtml);
    }

    /** 拼接若干消息 li。 */
    public static String messages(String... items) {
        return String.join("\n", items);
    }

    /** 一条已就绪的本人文字消息。 */
    public static String ownTextMessage(String text) {
        return "<li class=\"message-item item-myself\">"
                + "<div class=\"message-content\">" + html(text) + "</div></li>";
    }

    /** 一条仍处于「发送中」的本人文字消息（class 命中 sending 正则）。 */
    public static String ownTextMessageSending(String text) {
        return "<li class=\"message-item item-myself message-sending\">"
                + "<div class=\"message-content\">" + html(text) + "</div>"
                + "<span class=\"message-status\">发送中</span></li>";
    }

    /** 一条发送失败的本人文字消息（class/文案命中失败正则）。 */
    public static String ownTextMessageFailed(String text) {
        return "<li class=\"message-item item-myself message-fail\">"
                + "<div class=\"message-content\">" + html(text) + "</div>"
                + "<span class=\"message-status\">发送失败</span></li>";
    }

    /** 一条已就绪的本人图片消息。 */
    public static String ownImageMessage() {
        return "<li class=\"message-item item-myself\">"
                + "<div class=\"message-content\"><img src=\"" + OWN_IMAGE_SRC + "\" alt=\"图片简历\"/></div></li>";
    }

    /** 一条上传中的本人图片消息。 */
    public static String ownImageMessageUploading() {
        return "<li class=\"message-item item-myself message-sending\">"
                + "<div class=\"message-content\"><img src=\"" + OWN_IMAGE_SRC + "\" alt=\"图片简历\"/></div>"
                + "<span class=\"message-status\">上传中</span></li>";
    }

    /** 发送按钮。 */
    public static String sendButton(String id) {
        return "<button id=\"" + attribute(id) + "\" class=\"send-message\" type=\"button\">发送</button>";
    }

    /**
     * 点击按钮后向本人消息列表追加一段 HTML 的消息脚本（模拟 Boss 发送成功后插入新消息）。
     *
     * @param buttonId   触发按钮 id
     * @param messagesHtml 追加的 li HTML
     */
    public static String appendOnClickScript(String buttonId, String messagesHtml) {
        return "<script>document.getElementById(" + JSONObject.quote(buttonId) + ").addEventListener('click', function(){"
                + "document.querySelector('.chat-message .im-list')"
                + ".insertAdjacentHTML('beforeend', " + JSONObject.quote(messagesHtml) + ");"
                + "});</script>";
    }

    /** 点击按钮后延迟一段时间再追加消息（模拟慢网络/迟到历史加载）。 */
    public static String appendOnClickScript(String buttonId, String messagesHtml, int delayMs) {
        return "<script>document.getElementById(" + JSONObject.quote(buttonId) + ").addEventListener('click', function(){"
                + "setTimeout(function(){document.querySelector('.chat-message .im-list')"
                + ".insertAdjacentHTML('beforeend', " + JSONObject.quote(messagesHtml) + ");}, " + delayMs + ");"
                + "});</script>";
    }

    /** HTML 文本转义。 */
    public static String html(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /** HTML 属性转义。 */
    public static String attribute(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("'", "&#39;");
    }
}
