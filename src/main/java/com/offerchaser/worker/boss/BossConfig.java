package com.offerchaser.worker.boss;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 *
 * BOSS 投递配置数据类：由 BossService 从 boss_config 表装配，Boss / BossJobService 只读使用。
 *
 * <p>字段分三组：搜索条件（关键词/城市/行业/经验/薪资/学历/规模/阶段）、
 * 投递策略（招呼语、AI、死号过滤、图片简历、重复沟通、期望薪资、节奏）以及 D1 模板采集策略。
 * 所有字段都允许为空，缺省时的业务兜底由读取方负责。</p>
 */
@Data
public class BossConfig {

    /** 默认跟进话术：每行一条，随机挑选；用户配置为空时才会用到。 */
    public static final String DEFAULT_REPEAT_CHAT_MESSAGES = """
            请问是有什么顾虑需要沟通吗？
            我可以去贵公司面试吗？
            请问贵公司还招人吗？
            对于这个岗位有什么技术需求呢？
            我可以把我的简历发给您看看吗？
            能问一下贵公司办公地点在哪里吗？需不需要经常出差""";

    // ------------------------------------------------------------------
    // 跟进 / 投递策略
    // ------------------------------------------------------------------

    /** 每行一条跟进消息；空内容时 {@link #randomRepeatChatMessage()} 返回 null，调用方跳过跟进。 */
    private String repeatChatMessages = DEFAULT_REPEAT_CHAT_MESSAGES;

    /** 首次沟通的招呼语（非调试模式下必填）。 */
    private String sayHi;

    /** 调试模式：只遍历岗位，不真正投递。 */
    private Boolean debugger;

    /** 是否启用 AI 匹配（AI 只产出话术，不负责发送）。 */
    private Boolean enableAI;

    /** 是否过滤不活跃 HR。 */
    private Boolean filterDeadHR;

    /** 不活跃 HR 的判定文案列表，命中任一即视为死号。 */
    private List<String> deadStatus;

    /** 是否随文字消息发送图片简历。 */
    private Boolean sendImgResume;

    /** 是否允许对「继续沟通」的会话再次发送跟进消息；默认关闭。 */
    private Boolean allowRepeatChat = false;

    /** 期望月薪区间：第 0 位下限、第 1 位上限（单位 K/月，0 视为未配置）。 */
    private List<Integer> expectedSalary;

    /** 每个岗位之间的停顿秒数（字符串，兼容管理台的原始输入）。 */
    private String waitTime;

    // ------------------------------------------------------------------
    // 搜索条件
    // ------------------------------------------------------------------

    /** 搜索关键词列表。 */
    private List<String> keywords;

    /** 城市编码列表（已由管理台中文名转换成 BOSS 编码）。 */
    private List<String> cityCode;

    /** 自定义城市名 → 编码映射。 */
    private Map<String, String> customCityCode;

    /** 行业编码列表。 */
    private List<String> industry;

    /** 工作经验编码列表。 */
    private List<String> experience;

    /** 工作类型编码（单值）。 */
    private String jobType;

    /** 薪资范围（多选，编码列表）。 */
    private List<String> salary;

    /** 学历要求编码列表。 */
    private List<String> degree;

    /** 公司规模编码列表。 */
    private List<String> scale;

    /** 公司融资阶段编码列表。 */
    private List<String> stage;

    // ------------------------------------------------------------------
    // D1 模板级采集策略（null 表示沿用 application.yaml 的全局默认值）
    // ------------------------------------------------------------------

    /** 每个关键词最多采集多少个候选岗位；null/0 由运行侧决定「不限制」。 */
    private Integer collectMaxPerKeyword;

    /** 单个关键词的最长采集时长（分钟）。 */
    private Integer collectMaxDurationMinutes;

    /** 连续多少秒没有新岗位就提前结束该关键词的采集。 */
    private Integer collectNoNewWindowSeconds;

    /**
     * 从多行跟进话术中随机取一条：忽略空行与首尾空白；没有可用内容时返回 null。
     *
     * @return 一条可直接发送的跟进消息，或 null（调用方应跳过跟进）
     */
    public String randomRepeatChatMessage() {
        List<String> candidates = new ArrayList<>();
        if (repeatChatMessages != null) {
            for (String rawLine : repeatChatMessages.split("\\R")) {
                String line = rawLine.strip();
                if (!line.isEmpty()) {
                    candidates.add(line);
                }
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        int picked = ThreadLocalRandom.current().nextInt(candidates.size());
        return candidates.get(picked);
    }
}
