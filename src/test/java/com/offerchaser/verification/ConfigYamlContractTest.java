package com.offerchaser.verification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.offerchaser.application.persistence.SchemaMigrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置契约验收（A+ 版）：解析真实 {@code application.yaml}，逐项断言阶段的配置键存在且类型正确；
 * A-01 后 `strict-chat-identity` 被删除，身份核验永远 fail-closed，配置里不得再出现关闭开关。
 * <p>
 * 用 Jackson YAML（项目已有依赖）而不是字符串包含判断，能同时发现拼写层级错误（例如键被写进
 * 错误的父节点）与类型错误（布尔被写成字符串）。
 */
class ConfigYamlContractTest {

    private static final Path APPLICATION_YAML = Path.of("src", "main", "resources", "application.yaml");

    private JsonNode yaml;

    @BeforeEach
    void loadApplicationYaml() throws Exception {
        assertTrue(Files.isRegularFile(APPLICATION_YAML),
                "找不到配置文件：" + APPLICATION_YAML.toAbsolutePath());
        yaml = new ObjectMapper(new YAMLFactory()).readTree(APPLICATION_YAML.toFile());
        assertNotNull(yaml, "application.yaml 解析结果为空");
    }

    @Test
    void serverBindsToLoopbackOnly() {
        JsonNode server = requireNode("server");
        assertEquals(9527, server.get("port").asInt(), "后端 API 端口应保持 9527");
        assertEquals("127.0.0.1", server.get("address").asText(),
                "server.address 必须显式绑定回环地址，不能监听 0.0.0.0/局域网");
        assertFalse(server.get("address").asText().contains("0.0.0.0"));
    }

    @Test
    void datasourceKeepsSqliteRepositoryDatabase() {
        JsonNode datasource = requireNode("spring").get("datasource");
        assertNotNull(datasource, "spring.datasource 配置缺失");
        assertEquals("jdbc:sqlite:./db/offerchaser.db", datasource.get("url").asText(),
                "数据源应继续指向仓库真实库 db/offerchaser.db");
        assertEquals("org.sqlite.JDBC", datasource.get("driver-class-name").asText());
        JsonNode hikari = datasource.get("hikari");
        assertNotNull(hikari, "SQLite 连接池配置不应被删除");
        assertTrue(hikari.get("connection-test-query").asText().contains("SELECT 1"));
    }

    @Test
    void aiTimeoutsAreConfiguredAsNumbers() {
        JsonNode ai = requireNode("offerchaser").get("ai");
        assertNotNull(ai, "offerchaser.ai 配置缺失");
        JsonNode connectTimeout = ai.get("connect-timeout-seconds");
        JsonNode requestTimeout = ai.get("request-timeout-seconds");
        assertNotNull(connectTimeout, "缺少 offerchaser.ai.connect-timeout-seconds");
        assertNotNull(requestTimeout, "缺少 offerchaser.ai.request-timeout-seconds（请求级超时是 S3 核心）");
        assertTrue(connectTimeout.isInt(), "connect-timeout-seconds 必须是整数");
        assertTrue(requestTimeout.isInt(), "request-timeout-seconds 必须是整数");
        assertEquals(10, connectTimeout.asInt());
        assertEquals(45, requestTimeout.asInt());
        assertTrue(requestTimeout.asInt() > connectTimeout.asInt(),
                "请求级超时必须大于连接超时（两者不是同一个概念）");
    }

    @Test
    void bossDeliveryContractsAreConfiguredWithExpectedTypes() {
        JsonNode boss = requireNode("offerchaser").get("boss");
        assertNotNull(boss, "offerchaser.boss 配置缺失");

        assertEquals(145, requireInt(boss, "daily-limit"));
        assertEquals(8, requireInt(boss, "consecutive-fail-limit"));
        assertTrue(requireBoolean(boss, "budget-enabled"), "每日预算开关必须存在且为布尔");
        assertEquals("Asia/Shanghai", requireText(boss, "budget-timezone"));
        assertEquals("local", requireText(boss, "budget-account"));
        assertEquals(30, requireInt(boss, "followup-daily-limit"));
        assertEquals("INTERSECT", requireText(boss, "salary-strategy"));
        assertEquals(120, requireInt(boss, "max-message-length"));
        assertEquals(15, requireInt(boss, "text-send-confirm-timeout-seconds"));
        assertEquals(45, requireInt(boss, "image-send-confirm-timeout-seconds"));
        assertEquals(300, requireInt(boss, "verification-timeout-seconds"));
        assertEquals(72, requireInt(boss, "followup-min-interval-hours"));
        assertEquals(1, requireInt(boss, "followup-max-count"));
    }

    /**
     * A+ A-01：会话身份核对改为永远 fail-closed，`strict-chat-identity` 配置项被删除。
     * 该测试锁定“不能通过配置关闭身份核验”，防止后续把开关加回来。
     */
    @Test
    void identityDisablingSwitchIsRemovedFromYaml() throws Exception {
        JsonNode boss = requireNode("offerchaser").get("boss");
        assertNull(boss.get("strict-chat-identity"),
                "A+ 起 strict-chat-identity 已删除（身份核验永远 fail-closed）");
        assertNull(boss.get("chat-identity-enabled"), "不允许新增关闭身份核验的配置键");
        assertNull(boss.get("chat-identity-check"), "不允许新增关闭身份核验的配置键");

        String rawYaml = Files.readString(APPLICATION_YAML);
        assertFalse(rawYaml.contains("strict-chat-identity"),
                "application.yaml 不得再出现 strict-chat-identity（含注释）");
        assertTrue(rawYaml.contains("fail-closed"), "配置注释应明确身份核验永远 fail-closed");
    }

    /**
     * A-04/A-05 是代码内语义（workerExited、逐笔预算预留表），不需要新增配置项。
     * 断言不存在“fake”配置依赖；预算相关键仍只由现有配置驱动。
     */
    @Test
    void workerExitAndReservationSemanticsHaveNoExtraConfigKeys() {
        JsonNode offerchaser = requireNode("offerchaser");
        assertFalse(offerchaser.has("worker-exit"), "workerExited 不应依赖配置");
        assertFalse(offerchaser.has("worker-exit-timeout"), "workerExited 不应依赖配置");
        assertFalse(offerchaser.has("reservation"), "逐笔预算预留表不应依赖配置");
        assertEquals(13, SchemaMigrationService.TARGET_VERSION,
                "A-05 的 budget_reservation 由数据库迁移 V3 提供；B2–B5/方向模板/状态同步/人工放行表同样由迁移提供，与配置无关");
    }

    /** B4：有限增量采集的三个参数必须在 YAML 里可配置且默认值符合契约。 */
    @Test
    void collectionLimitConfigKeysAreConfigured() {
        JsonNode boss = requireNode("offerchaser").get("boss");
        assertEquals(60, requireInt(boss, "collect-max-per-keyword"),
                "B4 每关键词候选上限默认应为 60");
        assertEquals(15, requireInt(boss, "collect-max-duration-minutes"),
                "B4 单关键词采集时长上限默认应为 15 分钟");
        assertEquals(30, requireInt(boss, "collect-no-new-window-seconds"),
                "B4 无新增判定窗口默认应为 30 秒");
    }

    /** B5：本地会话认证四个配置键必须存在且默认值符合契约。 */
    @Test
    void securityConfigKeysAreConfigured() {
        JsonNode security = requireNode("offerchaser").get("security");
        assertNotNull(security, "缺少 offerchaser.security 配置块（B5 本地访问控制）");
        assertFalse(requireBoolean(security, "auth-enabled"),
                "管理台默认取消登录门（本机单用户直接进入）");
        assertEquals(168, requireInt(security, "session-ttl-hours"),
                "B5 会话有效期默认 168 小时（7 天）");
        assertEquals("data/local-session-token", requireText(security, "token-file"),
                "B5 令牌文件必须位于本机 data 目录");
        assertTrue(requireBoolean(security, "require-csrf-header"),
                "B5 写操作默认必须要求 X-Requested-With 头");
    }

    /** B3：联系人跨岗位去重天数与跟进必须批准，都是行为开关，必须在 YAML 里可配置。 */
    @Test
    void contactAndFollowupApprovalConfigKeysAreConfigured() {
        JsonNode boss = requireNode("offerchaser").get("boss");
        assertEquals(30, requireInt(boss, "contact-dedupe-days"),
                "B3 联系人跨岗位去重天数默认应为 30");
        JsonNode requireApproval = boss.get("followup-require-approval");
        assertNotNull(requireApproval, "缺少 offerchaser.boss.followup-require-approval（B3 跟进批准开关）");
        assertTrue(requireApproval.isBoolean(), "followup-require-approval 必须是布尔值");
        assertTrue(requireApproval.asBoolean(), "B3 默认必须要求人工批准跟进（默认不自动发送）");
    }

    /** B2：用户最高学历用于「岗位最低学历 vs 个人最高学历」比较，必须在 YAML 里可配置。 */
    @Test
    void rulesHighestDegreeIsConfigured() {
        JsonNode rules = requireNode("offerchaser").get("rules");
        assertNotNull(rules, "缺少 offerchaser.rules 配置块（B2 最高学历）");
        String highestDegree = requireText(rules, "highest-degree");
        assertEquals("本科", highestDegree, "默认最高学历应为本科");
        assertTrue(java.util.List.of("高中", "中专", "大专", "本科", "硕士", "博士").contains(highestDegree),
                "highest-degree 必须是合法学历枚举之一，实际：" + highestDegree);
    }

    @Test
    void staticServerAndCorsOriginsAreRestricted() {
        JsonNode offerchaser = requireNode("offerchaser");
        JsonNode staticServer = offerchaser.get("static-server");
        assertNotNull(staticServer, "offerchaser.static-server 配置缺失");
        assertEquals(6866, requireInt(staticServer, "port"));
        assertEquals("127.0.0.1", requireText(staticServer, "address"),
                "6866 静态服务必须绑定回环地址");

        JsonNode admin = offerchaser.get("admin");
        assertNotNull(admin, "offerchaser.admin 配置缺失");
        String origins = requireText(admin, "cors-allowed-origins");
        assertFalse(origins.contains("*"), "CORS 允许来源绝不能包含 *（allowCredentials 下也不合法）");
        assertTrue(origins.contains("http://localhost:6866"));
        assertTrue(origins.contains("http://127.0.0.1:6866"));
        assertTrue(origins.contains("http://localhost:9527"));
        assertTrue(origins.contains("http://127.0.0.1:9527"));
        for (String origin : origins.split(",")) {
            assertTrue(origin.trim().matches("https?://[^,*\\s]+"),
                    "每个 CORS 来源都应是具体 URL，实际：" + origin);
        }
    }

    @Test
    void enabledPlatformsDefaultToBossOnly() {
        String platforms = requireText(requireNode("offerchaser"), "platforms");
        assertEquals("boss", platforms.trim(),
                "阶段 A 默认只启用 BOSS；恢复四平台需显式改配置");
    }

    // ==================== 工具 ====================

    private JsonNode requireNode(String... path) {
        JsonNode current = yaml;
        StringBuilder dotted = new StringBuilder();
        for (String segment : path) {
            assertNotNull(current, "YAML 节点缺失：" + dotted);
            current = current.get(segment);
            assertNotNull(current, "YAML 节点缺失：" + dotted + "." + segment);
            if (dotted.length() > 0) {
                dotted.append('.');
            }
            dotted.append(segment);
        }
        return current;
    }

    private static int requireInt(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        assertNotNull(node, "缺少配置项：" + field);
        assertTrue(node.isInt(), field + " 必须是整数，实际：" + node.getNodeType());
        return node.asInt();
    }

    private static boolean requireBoolean(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        assertNotNull(node, "缺少配置项：" + field);
        assertTrue(node.isBoolean(), field + " 必须是布尔值，实际：" + node.getNodeType());
        return node.asBoolean();
    }

    private static String requireText(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        assertNotNull(node, "缺少配置项：" + field);
        assertTrue(node.isTextual(), field + " 必须是字符串，实际：" + node.getNodeType());
        return node.asText();
    }
}
