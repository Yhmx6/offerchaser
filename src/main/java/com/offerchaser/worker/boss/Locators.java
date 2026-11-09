package com.offerchaser.worker.boss;

/**
 * BOSS 直聘页面元素定位器（Playwright selector 常量表）。
 * <p>
 * 只放"页面结构相关"的 selector；业务判定（例如发送失败标记、身份标题匹配）留在各自的
 * 业务类里。按页面分区组织：
 * <ol>
 *   <li>登录 / 首页搜索；</li>
 *   <li>搜索结果页与岗位卡片；</li>
 *   <li>职位详情页；</li>
 *   <li>聊天（沟通弹窗）；</li>
 *   <li>消息中心列表；</li>
 *   <li>通用 / 异常页。</li>
 * </ol>
 * 说明：BOSS 的前端 DOM 会随版本变化，selector 失效时优先改本文件；
 * 改名前先全仓搜索（{@code Boss.java} 以 static import 方式引用这些常量）。
 */
public final class Locators {

    /** 工具类，不实例化。 */
    private Locators() {
    }

    // ------------------------------------------------------------------
    // 1. 登录 / 首页搜索
    // ------------------------------------------------------------------

    /** 首页右上角的登录入口。 */
    public static final String LOGIN_BTN = "//li[@class='nav-figure']";
    /** 登录弹窗里的"扫码登录"切换。 */
    public static final String LOGIN_SCAN_SWITCH = "//div[@class='btn-sign-switch ewm-switch']";
    /** 首页搜索框。 */
    public static final String HOME_SEARCH_INPUT = "input.ipt-search";
    /** 首页搜索按钮（用真实 UI 搜索代替拼 URL，降低风控概率）。 */
    public static final String HOME_SEARCH_BUTTON = "button.btn-search";

    // ------------------------------------------------------------------
    // 2. 搜索结果页与岗位卡片
    // ------------------------------------------------------------------

    /** 岗位列表外层容器：用于判断列表区块是否加载完成。 */
    public static final String JOB_LIST_CONTAINER = "//div[@class='job-list-container']";
    /** 单个岗位卡片的 class。 */
    public static final String JOB_CARD_BOX = "li.job-card-box";
    /** 当前已渲染的全部岗位卡片（虚拟列表滚动加载时用来计数）。 */
    public static final String JOB_LIST_SELECTOR = "ul.rec-job-list li.job-card-box";
    /** 卡片内岗位名称。 */
    public static final String JOB_NAME = "a.job-name";
    /** 卡片内公司名称。 */
    public static final String COMPANY_NAME = "span.boss-name";
    /** 卡片内工作区域（城市/区域）。 */
    public static final String JOB_AREA = "span.company-location";
    /** 卡片上的岗位标签（经验/学历等）。 */
    public static final String TAG_LIST = "ul.tag-list li";

    // ------------------------------------------------------------------
    // 3. 职位详情页
    // ------------------------------------------------------------------

    /** "立即沟通"按钮（class 含 `btn btn-startchat`）。 */
    public static final String CHAT_BUTTON = "[class*='btn btn-startchat']";
    /** 详情页错误内容区（岗位下线/风控提示）。 */
    public static final String ERROR_CONTENT = "//div[@class='error-content']";
    /** 详情页薪资。 */
    public static final String JOB_DETAIL_SALARY = "//div[@class='info-primary']//span[@class='salary']";
    /** 招聘者信息区（姓名/职位）。 */
    public static final String RECRUITER_INFO = "//div[@class='boss-info-attr']";
    /** 招聘者活跃时间。 */
    public static final String HR_ACTIVE_TIME = "//span[@class='boss-active-time']";
    /** 职位描述正文。 */
    public static final String JOB_DESCRIPTION = "//div[@class='job-sec-text']";

    // ------------------------------------------------------------------
    // 4. 聊天（沟通弹窗）
    // ------------------------------------------------------------------

    /** 聊天弹窗标题。 */
    public static final String DIALOG_TITLE = "//div[@class='dialog-title']";
    /** 聊天弹窗关闭按钮。 */
    public static final String DIALOG_CLOSE = "//i[@class='icon-close']";
    /** 聊天输入框（contenteditable）。 */
    public static final String CHAT_INPUT = "//div[@id='chat-input']";
    /** 聊天弹窗根容器。 */
    public static final String DIALOG_CONTAINER = "//div[@class='dialog-container']";
    /** 发送按钮。 */
    public static final String SEND_BUTTON = "//button[@type='send']";
    /** "发送图片"入口里的隐藏 file input。 */
    public static final String IMAGE_UPLOAD = "//div[@aria-label='发送图片']//input[@type='file']";
    /** 聊天消息列表容器（旧版弹窗）。 */
    public static final String DIALOG_CONTENT = "//div[@class='dialog-con']";
    /** 聊天记录"滚动加载更多"提示。 */
    public static final String SCROLL_LOAD_MORE = "//div[contains(text(), '滚动加载更多')]";

    // ------------------------------------------------------------------
    // 5. 消息中心列表
    // ------------------------------------------------------------------

    /** 消息中心每一条会话记录。 */
    public static final String CHAT_LIST_ITEM = "//li[@role='listitem']";
    /** 会话标题里的公司名称。 */
    public static final String COMPANY_NAME_IN_CHAT = "//div[@class='title-box']/span[@class='name-box']//span[2]";
    /** 会话最后一条消息文本。 */
    public static final String LAST_MESSAGE = "//div[@class='gray last-msg']/span[@class='last-msg-text']";
    /** 会话"已结束/已关闭"提示。 */
    public static final String FINISHED_TEXT = "//div[@class='finished']";

    // ------------------------------------------------------------------
    // 6. 通用 / 异常页
    // ------------------------------------------------------------------

    /** 聊天容器 CSS 版本（部分页面不用 XPath）。 */
    public static final String DIALOG_CON = ".dialog-con";
    /** 未登录时首页右上角的按钮组。 */
    public static final String LOGIN_BTNS = "//div[@class='btns']";
    /** 页面主标题。 */
    public static final String PAGE_HEADER = "//h1";
    /** 403 页面上的"登录"链接。 */
    public static final String ERROR_PAGE_LOGIN = "//a[@ka='403_login']";
}
