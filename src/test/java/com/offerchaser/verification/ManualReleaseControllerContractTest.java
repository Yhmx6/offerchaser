package com.offerchaser.verification;

import com.offerchaser.application.controller.ManualReleaseController;
import com.offerchaser.application.manual.ManualReleaseService;
import com.offerchaser.application.persistence.ManualReleaseRepository;
import com.offerchaser.application.security.AuthInterceptor;
import com.offerchaser.application.security.LocalAuthService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.sqlite.SQLiteDataSource;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.offerchaser.verification.VerificationSupport.execute;
import static com.offerchaser.verification.VerificationSupport.migratedDataSource;
import static com.offerchaser.verification.VerificationSupport.scalar;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D3-B 人工放行接口契约验收（直接调用真实 Controller + 真实 Service，不启动 Spring/浏览器）：
 * <ul>
 *   <li>GET 队列 200 + items/counts；无/非法 status 回退 PENDING；</li>
 *   <li>POST 批准/拒绝 200；理由必填、action 非法、非 REVIEW、评估不存在 → 400；重复决策 → 409；</li>
 *   <li>B5 认证：只读 GET 放行；POST 未登录 401、已登录缺 CSRF 头 403；</li>
 *   <li>Controller 只依赖 ManualReleaseService，不存在任何直接发送/投递依赖（批准不立即发送）。</li>
 * </ul>
 */
class ManualReleaseControllerContractTest {

    private static final String PATH = "/api/boss/manual-releases";
    private static final String WRITE_URI = PATH;

    @TempDir
    Path tempDir;

    private SQLiteDataSource dataSource;
    private ManualReleaseRepository repository;
    private ManualReleaseService service;
    private ManualReleaseController controller;
    private LocalAuthService authService;
    private AuthInterceptor interceptor;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = migratedDataSource(tempDir);
        repository = new ManualReleaseRepository(dataSource);
        service = new ManualReleaseService(dataSource, repository);
        controller = new ManualReleaseController(service);

        Path tokenFile = tempDir.resolve("data/local-session-token");
        authService = new LocalAuthService();
        ReflectionTestUtils.setField(authService, "authEnabled", true);
        ReflectionTestUtils.setField(authService, "tokenFile", tokenFile.toString());
        ReflectionTestUtils.setField(authService, "sessionTtlHours", 168L);
        ReflectionTestUtils.setField(authService, "requireCsrfHeader", true);
        ReflectionTestUtils.invokeMethod(authService, "initialize");
        token = Files.readString(tokenFile, StandardCharsets.UTF_8).trim();
        interceptor = new AuthInterceptor(authService);
    }

    // ==================== GET 队列 ====================

    @Test
    void getQueueReturnsOkWithItemsAndCounts() {
        long reviewId = seedEvaluation("enc-get", "Java 开发", "REVIEW", "PASS");

        ResponseEntity<Map<String, Object>> response = controller.queue("ALL");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(Boolean.TRUE, response.getBody().get("success"));
        Object data = response.getBody().get("data");
        ManualReleaseService.Queue queue = assertInstanceOf(ManualReleaseService.Queue.class, data);
        assertEquals(1, queue.items().size());
        ManualReleaseService.QueueItem item = queue.items().get(0);
        assertEquals(reviewId, item.evaluationId());
        assertEquals("Java 开发", item.jobName());
        assertEquals("REVIEW", item.ruleDecision());
        assertNotNull(item.createdAt());
        assertEquals(1, queue.counts().get("PENDING"));
        assertEquals(0, queue.counts().get("APPROVED"));
        assertEquals(5, queue.counts().size());
    }

    @Test
    void getQueueDefaultsToPendingWhenStatusMissingOrInvalid() {
        seedEvaluation("enc-default", "岗位", "REVIEW", "PASS");

        List<Long> pendingIds = idsOf(controller.queue(null));
        assertEquals(1, pendingIds.size());
        assertEquals(pendingIds, idsOf(controller.queue("")));
        assertEquals(pendingIds, idsOf(controller.queue("bogus")));
        assertEquals(pendingIds, idsOf(controller.queue("PENDING")));
        assertTrue(idsOf(controller.queue("APPROVED")).isEmpty());
    }

    // ==================== POST 决策 ====================

    @Test
    void postApproveReturnsOkAndQueuesForNextRun() {
        long evaluationId = seedEvaluation("enc-approve", "岗位", "REVIEW", "REVIEW");

        ResponseEntity<Map<String, Object>> response = controller.decide(Map.of(
                "evaluationId", evaluationId,
                "action", "APPROVE",
                "reason", "  技术栈匹配，同意下一轮放行  "));

        assertEquals(200, response.getStatusCode().value());
        assertEquals(Boolean.TRUE, response.getBody().get("success"));
        ManualReleaseService.DecisionResult result = assertInstanceOf(
                ManualReleaseService.DecisionResult.class, response.getBody().get("data"));
        assertTrue(result.approved());
        assertEquals("APPROVED", result.status());
        assertNotNull(result.releaseId());
        assertTrue(result.message().contains("下一轮任务执行"), result.message());
        // 落库事实
        ManualReleaseRepository.ManualRelease stored = repository.findByEvaluationId(evaluationId);
        assertNotNull(stored);
        assertEquals("APPROVED", stored.status());
        assertEquals("技术栈匹配，同意下一轮放行", stored.reason());
        // 字符串 evaluationId 也兼容
        long second = seedEvaluation("enc-approve-string", "岗位2", "REVIEW", "PASS");
        ResponseEntity<Map<String, Object>> stringId = controller.decide(Map.of(
                "evaluationId", String.valueOf(second), "action", "approve", "reason", "同意"));
        assertEquals(200, stringId.getStatusCode().value());
        assertEquals("APPROVED", repository.findByEvaluationId(second).status());
    }

    @Test
    void postRejectReturnsOkAndNeverEntersExecutionQueue() {
        long evaluationId = seedEvaluation("enc-reject", "岗位", "PASS", "REVIEW");

        ResponseEntity<Map<String, Object>> response = controller.decide(Map.of(
                "evaluationId", evaluationId, "action", "REJECT", "reason", "岗位方向不符"));

        assertEquals(200, response.getStatusCode().value());
        ManualReleaseService.DecisionResult result = assertInstanceOf(
                ManualReleaseService.DecisionResult.class, response.getBody().get("data"));
        assertFalse(result.approved());
        assertEquals("REJECTED", result.status());
        assertTrue(result.message().contains("不会进入投递"), result.message());
        assertEquals("REJECTED", repository.findByEvaluationId(evaluationId).status());
        assertTrue(service.approvedForExecution().isEmpty());
    }

    @Test
    void postValidationFailuresReturn400() {
        long reviewId = seedEvaluation("enc-review", "岗位", "REVIEW", "PASS");
        long passId = seedEvaluation("enc-pass", "已通过岗位", "PASS", "MATCH");

        assertBadRequest(controller.decide(null));
        assertBadRequest(controller.decide(Map.of("evaluationId", reviewId, "action", "APPROVE")));
        assertBadRequest(controller.decide(Map.of("evaluationId", reviewId,
                "action", "APPROVE", "reason", "   ")));
        assertBadRequest(controller.decide(Map.of("evaluationId", reviewId,
                "action", "PASS", "reason", "理由")));
        assertBadRequest(controller.decide(Map.of("evaluationId", passId,
                "action", "APPROVE", "reason", "非 REVIEW 不允许放行")));
        assertBadRequest(controller.decide(Map.of("evaluationId", 99999,
                "action", "APPROVE", "reason", "不存在")));
        // D3-B 收尾：evaluationId 非数字时也必须是 400（而不是 NPE→500）
        assertBadRequest(controller.decide(Map.of("evaluationId", "not-a-number",
                "action", "APPROVE", "reason", "非法 id")));
        assertBadRequest(controller.decide(Map.of("action", "APPROVE", "reason", "缺少 id")));
        assertTrue(service.approvedForExecution().isEmpty(), "400 不得写入放行队列");
    }

    @Test
    void duplicateDecisionReturns409WithConflictCode() {
        long evaluationId = seedEvaluation("enc-conflict", "岗位", "REVIEW", "PASS");
        assertEquals(200, controller.decide(Map.of("evaluationId", evaluationId,
                "action", "APPROVE", "reason", "批准")).getStatusCode().value());

        ResponseEntity<Map<String, Object>> conflict = controller.decide(Map.of(
                "evaluationId", evaluationId, "action", "APPROVE", "reason", "重复批准"));

        assertEquals(409, conflict.getStatusCode().value());
        assertEquals(Boolean.FALSE, conflict.getBody().get("success"));
        assertEquals("MANUAL_RELEASE_CONFLICT", conflict.getBody().get("code"));
        assertTrue(String.valueOf(conflict.getBody().get("message")).contains("不能重复决策"));
        assertEquals("APPROVED", repository.findByEvaluationId(evaluationId).status(),
                "409 不得改变已有决策");
    }

    @Test
    void controllerHasNoDirectDeliveryDependency() throws Exception {
        Field[] fields = ManualReleaseController.class.getDeclaredFields();
        assertEquals(1, fields.length,
                "Controller 只允许依赖 ManualReleaseService（不得注入 Boss/台账/发送器）");
        assertEquals(ManualReleaseService.class, fields[0].getType());
        // 允许的对外动作只有 GET 队列 / POST 决策两个映射（接口本身不做任何发送）
        List<String> methods = java.util.Arrays.stream(ManualReleaseController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(
                        org.springframework.web.bind.annotation.GetMapping.class)
                        || method.isAnnotationPresent(
                        org.springframework.web.bind.annotation.PostMapping.class))
                .map(method -> method.getName()).sorted().toList();
        assertEquals(List.of("decide", "queue"), methods,
                "人工放行接口只允许「GET 队列 + POST 决策」，不得暴露直接发送接口");
    }

    // ==================== B5 认证 / CSRF ====================

    @Test
    void readOnlyGetIsPublicButWriteRequiresSessionAndCsrf() throws Exception {
        // 未登录 GET：只读接口按 B5 契约放行（与 /api/boss/status 等一致）
        assertTrue(interceptor.preHandle(request("GET", PATH), new MockHttpServletResponse(), new Object()),
                "未登录读取人工放行队列（只读 GET）应放行");

        // 未登录 POST：401，且不泄漏令牌
        MockHttpServletResponse unauthorized = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request("POST", WRITE_URI), unauthorized, new Object()));
        assertEquals(401, unauthorized.getStatus());
        String body = unauthorized.getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("UNAUTHORIZED"));
        assertFalse(body.contains(token), "401 响应不得泄漏本地令牌");

        // 已登录但缺 CSRF 头：403
        String sessionId = authService.createSession();
        MockHttpServletRequest withoutCsrf = request("POST", WRITE_URI);
        withoutCsrf.setCookies(new Cookie(LocalAuthService.SESSION_COOKIE, sessionId));
        MockHttpServletResponse csrfMissing = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(withoutCsrf, csrfMissing, new Object()));
        assertEquals(403, csrfMissing.getStatus());
        assertTrue(csrfMissing.getContentAsString(StandardCharsets.UTF_8)
                .contains("CSRF_HEADER_MISSING"));

        // 已登录 + CSRF 头：放行
        MockHttpServletRequest allowed = request("POST", WRITE_URI);
        allowed.setCookies(new Cookie(LocalAuthService.SESSION_COOKIE, sessionId));
        allowed.addHeader(LocalAuthService.CSRF_HEADER, LocalAuthService.CSRF_HEADER_VALUE);
        assertTrue(interceptor.preHandle(allowed, new MockHttpServletResponse(), new Object()));

        // X-Session-Token 头同样可认证（API 客户端）
        MockHttpServletRequest headerAuth = request("POST", WRITE_URI);
        headerAuth.addHeader(LocalAuthService.SESSION_HEADER, sessionId);
        headerAuth.addHeader(LocalAuthService.CSRF_HEADER, LocalAuthService.CSRF_HEADER_VALUE);
        assertTrue(interceptor.preHandle(headerAuth, new MockHttpServletResponse(), new Object()));
    }

    // ==================== 工具 ====================

    private static void assertBadRequest(ResponseEntity<Map<String, Object>> response) {
        assertEquals(400, response.getStatusCode().value(),
                "非法决策应返回 400：" + response.getBody());
        assertEquals(Boolean.FALSE, response.getBody().get("success"));
    }

    private List<Long> idsOf(ResponseEntity<Map<String, Object>> response) {
        ManualReleaseService.Queue queue = assertInstanceOf(
                ManualReleaseService.Queue.class, response.getBody().get("data"));
        return queue.items().stream().map(ManualReleaseService.QueueItem::evaluationId).toList();
    }

    private long seedEvaluation(String encryptId, String jobName, String ruleDecision, String aiDecision) {
        execute(dataSource, "INSERT INTO job_evaluation(run_id, encrypt_id, encrypt_user_id, job_name, " +
                        "company_name, job_url, source_keyword, rule_decision, ai_decision, created_at) " +
                        "VALUES('run-d3b', ?, ?, ?, '测试公司', ?, 'Java', ?, ?, '2026-11-07 10:00:00')",
                encryptId, "user-" + encryptId, jobName,
                "https://www.zhipin.com/job_detail/" + encryptId + ".html", ruleDecision, aiDecision);
        return scalar(dataSource, "SELECT MAX(id) FROM job_evaluation");
    }

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        request.setRemoteAddr("127.0.0.1");
        return request;
    }
}
