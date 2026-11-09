package com.offerchaser.application.service;

import com.offerchaser.application.ai.AiCallResult;
import com.offerchaser.application.ai.AiConfigException;
import com.offerchaser.application.entity.AiEntity;
import com.offerchaser.application.mapper.AiMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.format.DateTimeFormatter;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * AI 服务（Spring 管理）。
 * <p>
 * 从数据库配置获取 BASE_URL、API_KEY、MODEL 并发起 AI 请求。本类的职责只有“传输”：
 * <ul>
 *   <li>复用同一个静态 {@link HttpClient}，连接超时读 {@code offerchaser.ai.connect-timeout-seconds}（默认 10 秒）；</li>
 *   <li>每个请求用 {@link HttpRequest.Builder#timeout(Duration)} 设置请求级超时，
 *       读 {@code offerchaser.ai.request-timeout-seconds}（默认 45 秒）；</li>
 *   <li>配置缺失或 URL 非法时，在任何网络请求之前抛 {@link AiConfigException}（中文信息）；</li>
 *   <li>{@link #sendRequestWithUsage(String)} 返回正文 + 模型 + token 用量 + 耗时；</li>
 *   <li>{@link #sendRequest(String)} 保留给旧的调用方（AiConfigController 等），只返回正文。</li>
 * </ul>
 * AI 输出是否可用由 {@code com.offerchaser.application.ai} 包严格判定，本类不把原始文本当结果。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiService {

    /** 连接超时配置键（秒） */
    private static final String CONNECT_TIMEOUT_KEY = "offerchaser.ai.connect-timeout-seconds";
    /** 请求级超时配置键（秒） */
    private static final String REQUEST_TIMEOUT_KEY = "offerchaser.ai.request-timeout-seconds";
    /** 连接超时默认值（秒） */
    private static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 10;
    /** 请求级超时默认值（秒） */
    private static final int DEFAULT_REQUEST_TIMEOUT_SECONDS = 45;
    /** 响应体读取停滞的兜底宽限（秒）：总超时 = 请求级超时 + 该值 */
    private static final int HARD_TIMEOUT_GRACE_SECONDS = 5;

    /** 默认采样温度（保持既有行为，不随配置变化） */
    private static final double DEFAULT_TEMPERATURE = 0.5;
    /** 视觉请求采样温度：低温度减少描述性发散，提高字段抽取稳定性 */
    private static final double VISION_TEMPERATURE = 0.2;
    /** 首次创建 AI 配置时写入的默认个人介绍 */
    private static final String DEFAULT_INTRODUCE = "请在此填写您的技能介绍";
    /** 首次创建 AI 配置时写入的默认提示词 */
    private static final String DEFAULT_PROMPT = "请在此填写AI提示词模板";

    /** Chat Completions 端点路径片段 */
    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";
    /** Responses API 端点路径片段 */
    private static final String RESPONSES_PATH = "/responses";
    /** 版本段：baseUrl 已带 /v1 时不再重复拼接 */
    private static final String VERSION_SEGMENT = "/v1";

    /** 需要走 Responses API 的模型关键字（o-系列、4.1、reasoner 等） */
    private static final String[] RESPONSES_MODEL_MARKERS = {
            "o1", "o3", "o4", "4.1", "reasoner", "4o-mini", "gpt-4o-mini"
    };

    /** 时间格式：与项目其他日志/DB 保持一致（本地时区） */
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 共享 HttpClient（懒加载静态单例）。
     * <p>连接超时是客户端级别配置，只建一次；请求级超时在 HttpRequest 上按请求设置。</p>
     */
    private static volatile HttpClient sharedHttpClient;

    /** 连接超时（秒），Spring 注入，缺省 10 */
    @Value("${offerchaser.ai.connect-timeout-seconds:10}")
    private int connectTimeoutSeconds = DEFAULT_CONNECT_TIMEOUT_SECONDS;

    /** 请求级超时（秒），Spring 注入，缺省 45 */
    @Value("${offerchaser.ai.request-timeout-seconds:45}")
    private int requestTimeoutSeconds = DEFAULT_REQUEST_TIMEOUT_SECONDS;

    private final ConfigService configService;
    private final AiMapper aiMapper;

    /** 岗位解读等长文本任务允许临时提高请求超时；只影响当前调用线程。 */
    private final ThreadLocal<Integer> requestTimeoutOverride = new ThreadLocal<>();

    /** AI 连接配置快照（发请求前一次性取出并校验） */
    private record ConnectionConfig(String baseUrl, String apiKey, String model) {
    }

    /** 一次 HTTP 调用结果 */
    private record HttpExchange(int statusCode, String body) {
    }

    /** token 用量 */
    private record TokenUsage(int promptTokens, int completionTokens, boolean present) {
    }

    // ================= AI 请求 =================

    /**
     * 校验 AI 配置是否可用于真实请求：BASE_URL / API_KEY / MODEL 齐全且 URL 合法。
     *
     * @throws AiConfigException 配置缺失或 URL 非法
     */
    public void validateConfiguration() {
        // 只做校验，不缓存；真正的请求还会再取一次，保证配置改动后立即生效
        loadConnectionConfig();
    }

    /**
     * 发送 AI 请求（非流式）并返回正文、模型、token 用量与耗时。
     *
     * @param content 用户消息内容
     * @return 调用结果（content 为 AI 原始回复文本）
     * @throws AiConfigException 配置缺失或 URL 非法（发请求前抛出）
     * @throws RuntimeException  网络异常、请求超时、HTTP 非 200、响应无法解析
     */
    public AiCallResult sendRequestWithUsage(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("AI 请求内容不能为空");
        }

        // 配置缺失/URL 非法：必须在发出任何网络请求之前失败
        ConnectionConfig config = loadConnectionConfig();
        long startedAt = System.nanoTime();

        // 根据模型类型选择兼容的端点（部分“推理/Reasoning”模型需要使用 Responses API）
        boolean responsesEndpoint = isResponsesModel(config.model());
        String endpoint = responsesEndpoint
                ? buildResponsesEndpoint(config.baseUrl())
                : buildChatCompletionsEndpoint(config.baseUrl());

        HttpExchange exchange = sendHttp(endpoint, config.apiKey(),
                buildRequestBody(content, config.model(), responsesEndpoint));

        if (exchange.statusCode() == 200) {
            return parseCallResult(exchange.body(), responsesEndpoint, config.model(),
                    elapsedMillis(startedAt));
        }

        // 更详细的错误日志，便于定位 400 问题
        log.error("AI请求失败: status={}, endpoint={}, body={}",
                exchange.statusCode(), endpoint, exchange.body());
        // 针对 Responses-only 模型误用 Chat Completions 的常见错误做一次自动重试
        if (!responsesEndpoint && containsReasoningParamError(exchange.body())) {
            String fallbackEndpoint = buildResponsesEndpoint(config.baseUrl());
            log.warn("检测到 reasoning 相关参数错误，自动切换到 Responses API 重试: {}", fallbackEndpoint);
            return sendViaResponses(content, config, startedAt);
        }
        throw new RuntimeException("AI请求失败，状态码: " + exchange.statusCode()
                + ", 详情: " + exchange.body());
    }

    /**
     * 长文本任务使用独立请求超时；不改变全局配置，也不影响投递链路。
     */
    public AiCallResult sendRequestWithUsage(String content, int timeoutSeconds) {
        if (timeoutSeconds <= 0) {
            return sendRequestWithUsage(content);
        }
        requestTimeoutOverride.set(timeoutSeconds);
        try {
            return sendRequestWithUsage(content);
        } finally {
            requestTimeoutOverride.remove();
        }
    }

    /**
     * 发送 AI 请求并只返回正文（保留给旧调用方，例如 AiConfigController 的测试接口）。
     *
     * @param content 用户消息内容
     * @return AI 回复文本
     */
    public String sendRequest(String content) {
        return sendRequestWithUsage(content).content();
    }

    /**
     * 发送视觉请求（chat-completions 多模态消息 + image_url data URL）。
     * <p>
     * 与文本请求共用同一套连接配置、请求级超时、响应解析与错误处理；
     * 端点固定为 Chat Completions：视觉输入不被 Responses API 支持，
     * DeepSeek 等兼容服务也不会因为模型名带 reasoner 而误走 Responses。
     * </p>
     *
     * @param systemPrompt 系统提示词（可为空）
     * @param userPrompt   用户指令
     * @param imageDataUrl 形如 {@code data:image/jpeg;base64,...} 的图片
     * @return 调用结果（content 为模型原始回复文本）
     * @throws AiConfigException 配置缺失或 URL 非法（发请求前抛出）
     * @throws RuntimeException  网络异常、超时、HTTP 非 200、响应无法解析
     */
    public AiCallResult sendVisionRequest(String systemPrompt, String userPrompt, String imageDataUrl) {
        if (userPrompt == null || userPrompt.isBlank()) {
            throw new IllegalArgumentException("AI 视觉请求内容不能为空");
        }
        if (imageDataUrl == null || imageDataUrl.isBlank()) {
            throw new IllegalArgumentException("AI 视觉请求缺少图片数据");
        }
        ConnectionConfig config = loadConnectionConfig();
        long startedAt = System.nanoTime();
        String endpoint = buildChatCompletionsEndpoint(config.baseUrl());
        HttpExchange exchange = sendHttp(endpoint, config.apiKey(),
                buildVisionRequestBody(config.model(), systemPrompt, userPrompt, imageDataUrl));
        if (exchange.statusCode() == 200) {
            return parseCallResult(exchange.body(), false, config.model(), elapsedMillis(startedAt));
        }
        log.error("AI视觉请求失败: status={}, endpoint={}, body={}",
                exchange.statusCode(), endpoint, exchange.body());
        throw new RuntimeException("AI视觉请求失败，状态码: " + exchange.statusCode()
                + ", 详情: " + exchange.body());
    }

    /**
     * 使用 Responses API 发送一次请求（用于自动降级/重试），同样带超时与 usage。
     */
    private AiCallResult sendViaResponses(String content, ConnectionConfig config, long startedAt) {
        String endpoint = buildResponsesEndpoint(config.baseUrl());
        HttpExchange exchange = sendHttp(endpoint, config.apiKey(),
                buildRequestBody(content, config.model(), true));
        if (exchange.statusCode() == 200) {
            return parseCallResult(exchange.body(), true, config.model(), elapsedMillis(startedAt));
        }
        log.error("Responses API 调用失败: status={}, endpoint={}, body={}",
                exchange.statusCode(), endpoint, exchange.body());
        throw new RuntimeException("AI请求失败，状态码: " + exchange.statusCode()
                + ", 详情: " + exchange.body());
    }

    /**
     * 读取并校验 BASE_URL / API_KEY / MODEL；任何一项缺失或 URL 非法都抛 {@link AiConfigException}。
     */
    private ConnectionConfig loadConnectionConfig() {
        Map<String, String> configs;
        try {
            configs = configService.getAiConfigs();
        } catch (RuntimeException e) {
            throw new AiConfigException("读取 AI 配置失败，请先在配置页填写 BASE_URL、API_KEY、MODEL："
                    + e.getMessage(), e);
        }
        if (configs == null) {
            throw new AiConfigException("读取 AI 配置失败：配置服务未返回任何配置");
        }

        String baseUrl = trimToNull(configs.get("BASE_URL"));
        String apiKey = trimToNull(configs.get("API_KEY"));
        String model = trimToNull(configs.get("MODEL"));
        if (baseUrl == null) {
            throw new AiConfigException("AI 配置缺少 BASE_URL（接口地址），请先在配置页填写");
        }
        if (apiKey == null) {
            throw new AiConfigException("AI 配置缺少 API_KEY，请先在配置页填写");
        }
        if (model == null) {
            throw new AiConfigException("AI 配置缺少 MODEL（模型名称），请先在配置页填写");
        }
        validateBaseUrl(baseUrl);
        return new ConnectionConfig(baseUrl, apiKey, model);
    }

    /**
     * 校验 BASE_URL：必须是带主机名的 http/https 地址。
     */
    private void validateBaseUrl(String baseUrl) {
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (IllegalArgumentException e) {
            throw new AiConfigException("AI 配置的 BASE_URL 不是合法 URL: " + baseUrl, e);
        }
        String scheme = uri.getScheme();
        boolean httpScheme = scheme != null
                && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
        if (!httpScheme || uri.getHost() == null || uri.getHost().isBlank()) {
            throw new AiConfigException("AI 配置的 BASE_URL 不是合法的 http(s) 地址: " + baseUrl);
        }
    }

    /**
     * 构建请求体：Responses API 用 input，Chat Completions API 用 messages。
     *
     * @param content           用户消息内容
     * @param model             模型名称
     * @param responsesEndpoint 是否走 Responses API
     * @return JSON 请求体字符串
     */
    private String buildRequestBody(String content, String model, boolean responsesEndpoint) {
        JSONObject request = new JSONObject();
        request.put("model", model);
        request.put("temperature", DEFAULT_TEMPERATURE);
        if (responsesEndpoint) {
            request.put("input", content);
        } else {
            request.put("messages", chatMessages(content));
        }
        return request.toString();
    }

    /**
     * 把用户内容包装成 Chat Completions 需要的单条 user 消息数组。
     */
    private JSONArray chatMessages(String content) {
        return new JSONArray()
                .put(new JSONObject().put("role", "user").put("content", content));
    }

    /**
     * 构建视觉请求体：system 消息 + user 多模态消息（text + image_url data URL）。
     */
    private String buildVisionRequestBody(String model, String systemPrompt,
                                          String userPrompt, String imageDataUrl) {
        JSONArray messages = new JSONArray();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.put(new JSONObject().put("role", "system").put("content", systemPrompt));
        }
        JSONArray userContent = new JSONArray()
                .put(new JSONObject().put("type", "text").put("text", userPrompt))
                .put(new JSONObject().put("type", "image_url")
                        .put("image_url", new JSONObject().put("url", imageDataUrl)));
        messages.put(new JSONObject().put("role", "user").put("content", userContent));
        return new JSONObject()
                .put("model", model)
                .put("temperature", VISION_TEMPERATURE)
                .put("messages", messages)
                .toString();
    }

    /**
     * 构建 HTTP 请求：请求级超时（HttpRequest.Builder.timeout）在这里设置。
     */
    private HttpRequest buildHttpRequest(String endpoint, String apiKey, String body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(resolveRequestTimeoutSeconds()));
            applyHttpHeaders(builder, apiKey);
            return builder.POST(HttpRequest.BodyPublishers.ofString(body)).build();
        } catch (IllegalArgumentException invalidEndpoint) {
            throw new AiConfigException("AI 请求地址不合法: " + endpoint, invalidEndpoint);
        }
    }

    /**
     * 设置 JSON 请求头与鉴权头：Bearer 为主，另保留 Azure OpenAI 兼容的 api-key 头。
     */
    private void applyHttpHeaders(HttpRequest.Builder builder, String apiKey) {
        builder.header("Content-Type", "application/json");
        builder.header("Accept", "application/json");
        builder.header("Authorization", "Bearer " + apiKey);
        builder.header("api-key", apiKey);
    }

    /**
     * 执行一次 HTTP POST，并把超时/中断/网络异常转成带中文说明的 RuntimeException。
     * <p>
     * 使用 {@link HttpClient#sendAsync} + 显式 {@code get(timeout)}：JDK 的
     * {@code HttpRequest.timeout} 在"响应头已返回但响应体读取停滞"时可能一直挂住，
     * 导致投递线程无限期阻塞（表现为任务停在"正在评估"不动）。这里再加一层总超时兜底，
     * 超时后取消 future 并抛错，让岗位按 AI 失败跳过，任务继续。
     */
    private HttpExchange sendHttp(String endpoint, String apiKey, String jsonBody) {
        HttpRequest request = buildHttpRequest(endpoint, apiKey, jsonBody);
        int requestTimeoutSeconds = resolveRequestTimeoutSeconds();
        long hardTimeoutSeconds = (long) requestTimeoutSeconds + HARD_TIMEOUT_GRACE_SECONDS;
        long startedAt = System.nanoTime();
        CompletableFuture<HttpResponse<String>> future = null;
        try {
            future = httpClient().sendAsync(request, HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> response = future.get(hardTimeoutSeconds, TimeUnit.SECONDS);
            log.debug("AI HTTP 请求完成: endpoint={}, status={}, durationMs={}",
                    endpoint, response.statusCode(), elapsedMillis(startedAt));
            return new HttpExchange(response.statusCode(), response.body());
        } catch (TimeoutException e) {
            cancelQuietly(future);
            log.error("AI 请求总超时：endpoint={}, 等待 {} 秒（请求级 {} 秒 + 兜底 {} 秒），已中止本次评估",
                    endpoint, hardTimeoutSeconds, requestTimeoutSeconds, HARD_TIMEOUT_GRACE_SECONDS);
            throw new RuntimeException("AI 请求超时（总超时 " + hardTimeoutSeconds
                    + " 秒，含响应体读取；可调整配置 " + REQUEST_TIMEOUT_KEY + "）", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof HttpTimeoutException) {
                log.error("AI 请求超时: endpoint={}, requestTimeoutSeconds={}",
                        endpoint, requestTimeoutSeconds);
                throw new RuntimeException("AI 请求超时（请求级超时 " + requestTimeoutSeconds
                        + " 秒，可调整配置 " + REQUEST_TIMEOUT_KEY + "）", cause);
            }
            if (cause instanceof IOException) {
                log.error("AI 请求网络异常: endpoint={}, 原因={}", endpoint, cause.getMessage());
                throw new RuntimeException("AI 请求网络异常: " + cause.getMessage(), cause);
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            log.error("AI 请求失败: endpoint={}, 原因={}", endpoint,
                    cause == null ? "unknown" : cause.getMessage());
            throw new RuntimeException("AI 请求失败: " + (cause == null ? "unknown" : cause.getMessage()), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelQuietly(future);
            log.error("AI 请求被中断: endpoint={}", endpoint);
            throw new RuntimeException("AI 请求被中断", e);
        } catch (RuntimeException e) {
            cancelQuietly(future);
            throw e;
        }
    }

    private static void cancelQuietly(CompletableFuture<?> future) {
        if (future != null) {
            try {
                future.cancel(true);
            } catch (Exception ignored) {
                // 取消失败不影响超时处理
            }
        }
    }

    /**
     * 懒加载共享 HttpClient：连接超时只配置一次，避免每次请求新建客户端。
     */
    private HttpClient httpClient() {
        HttpClient client = sharedHttpClient;
        if (client == null) {
            synchronized (AiService.class) {
                client = sharedHttpClient;
                if (client == null) {
                    int connectSeconds = resolveConnectTimeoutSeconds();
                    client = HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(connectSeconds))
                            .followRedirects(HttpClient.Redirect.NORMAL)
                            .build();
                    sharedHttpClient = client;
                    log.info("初始化共享 HttpClient：连接超时 {} 秒（{}），请求级超时 {} 秒（{}）",
                            connectSeconds, CONNECT_TIMEOUT_KEY,
                            resolveRequestTimeoutSeconds(), REQUEST_TIMEOUT_KEY);
                }
            }
        }
        return client;
    }

    private int resolveConnectTimeoutSeconds() {
        return connectTimeoutSeconds > 0 ? connectTimeoutSeconds : DEFAULT_CONNECT_TIMEOUT_SECONDS;
    }

    private int resolveRequestTimeoutSeconds() {
        Integer override = requestTimeoutOverride.get();
        if (override != null && override > 0) {
            return override;
        }
        return requestTimeoutSeconds > 0 ? requestTimeoutSeconds : DEFAULT_REQUEST_TIMEOUT_SECONDS;
    }

    /**
     * 解析 200 响应：正文、模型、usage、耗时。
     */
    private AiCallResult parseCallResult(String body, boolean responsesEndpoint,
                                         String configuredModel, long durationMs) {
        JSONObject responseObject;
        try {
            responseObject = new JSONObject(body);
        } catch (Exception e) {
            throw new RuntimeException("AI 响应不是合法 JSON，无法解析: " + abbreviate(body), e);
        }

        String usedModel = responseObject.optString("model", "").trim();
        if (usedModel.isEmpty()) {
            usedModel = configuredModel;
        }
        String responseContent = extractContent(responseObject, body, responsesEndpoint);
        TokenUsage usage = extractUsage(responseObject);

        String requestId = responseObject.optString("id", "");
        long created = responseObject.optLong("created", 0L);
        String createdTime = created > 0
                ? Instant.ofEpochSecond(created).atZone(ZoneId.systemDefault()).toLocalDateTime()
                        .format(TIME_FORMATTER)
                : LocalDateTime.now().format(TIME_FORMATTER);
        log.info("AI响应: id={}, time={}, model={}, promptTokens={}, completionTokens={}, usagePresent={}, durationMs={}",
                requestId, createdTime, usedModel, usage.promptTokens(), usage.completionTokens(),
                usage.present(), durationMs);

        return new AiCallResult(responseContent, usedModel, usage.promptTokens(),
                usage.completionTokens(), durationMs, usage.present());
    }

    /**
     * 从响应体中提取正文。
     * <p>Responses API 优先 output_text，再尝试 output 数组，最后兼容 choices/message 结构与原始体；
     * Chat Completions API 只接受 choices[0].message.content，缺失视为响应异常。</p>
     */
    private String extractContent(JSONObject responseObject, String rawBody, boolean responsesEndpoint) {
        if (responsesEndpoint) {
            // Responses API：优先读取 output_text
            String outputText = responseObject.optString("output_text", null);
            if (outputText != null && !outputText.isBlank()) {
                return outputText;
            }
            // 标准 Responses API 结构：output[].content[].text
            String fromOutput = extractResponsesOutputText(responseObject);
            if (fromOutput != null) {
                return fromOutput;
            }
            // 兜底：尝试从通用 choices/message 结构读取（部分代理/兼容层会返回该结构）
            String fromChoices = extractChatCompletionContent(responseObject);
            if (fromChoices != null) {
                return fromChoices;
            }
            // 最后兜底：返回原始响应体，避免空值中断流程；严格解析由 AiOutputParser 负责
            return rawBody;
        }
        // Chat Completions API
        String content = extractChatCompletionContent(responseObject);
        if (content == null) {
            throw new RuntimeException("AI 响应缺少 choices[0].message.content 字段");
        }
        return content;
    }

    /**
     * 读取 Responses API 的 output 数组文本（可能有多段，按换行拼接）。
     */
    private String extractResponsesOutputText(JSONObject responseObject) {
        JSONArray output = responseObject.optJSONArray("output");
        if (output == null || output.isEmpty()) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < output.length(); i++) {
            JSONObject item = output.optJSONObject(i);
            if (item == null) {
                continue;
            }
            JSONArray parts = item.optJSONArray("content");
            if (parts == null) {
                continue;
            }
            for (int j = 0; j < parts.length(); j++) {
                JSONObject part = parts.optJSONObject(j);
                if (part == null) {
                    continue;
                }
                String partText = part.optString("text", "");
                if (partText.isBlank()) {
                    continue;
                }
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(partText);
            }
        }
        return text.length() == 0 ? null : text.toString();
    }

    /**
     * 读取 choices[0].message.content；结构不存在时返回 null。
     */
    private String extractChatCompletionContent(JSONObject responseObject) {
        JSONArray choices = responseObject.optJSONArray("choices");
        if (choices == null || choices.isEmpty()) {
            return null;
        }
        JSONObject firstChoice = choices.optJSONObject(0);
        if (firstChoice == null) {
            return null;
        }
        JSONObject message = firstChoice.optJSONObject("message");
        if (message == null) {
            return null;
        }
        Object content = message.opt("content");
        return content instanceof String text ? text : null;
    }

    /**
     * 读取 token 用量：同时兼容 Chat Completions（prompt_tokens/completion_tokens）
     * 与 Responses API（input_tokens/output_tokens）。
     */
    private TokenUsage extractUsage(JSONObject responseObject) {
        JSONObject usage = responseObject.optJSONObject("usage");
        if (usage == null) {
            return new TokenUsage(-1, -1, false);
        }
        int promptTokens = usage.optInt("prompt_tokens", usage.optInt("input_tokens", -1));
        int completionTokens = usage.optInt("completion_tokens", usage.optInt("output_tokens", -1));
        return new TokenUsage(promptTokens, completionTokens, true);
    }

    private long elapsedMillis(long startedAtNanos) {
        return Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 300 ? body : body.substring(0, 300) + "...";
    }

    /**
     * 去掉 baseUrl 末尾多余的一个斜杠，避免拼出双斜杠端点。
     */
    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null) {
            return "";
        }
        String noTrailingSlash = baseUrl.trim();
        if (noTrailingSlash.endsWith("/")) {
            noTrailingSlash = noTrailingSlash.substring(0, noTrailingSlash.length() - 1);
        }
        return noTrailingSlash;
    }

    /**
     * 判断 baseUrl 是否已经包含 /v1 版本段（兼容官方地址与代理路径）。
     */
    private boolean hasVersionSegment(String normalizedBaseUrl) {
        return normalizedBaseUrl.endsWith(VERSION_SEGMENT)
                || normalizedBaseUrl.contains(VERSION_SEGMENT + "/");
    }

    /**
     * 根据配置构造 chat/completions 端点，避免重复拼接 /v1。
     */
    private String buildChatCompletionsEndpoint(String baseUrl) {
        String normalized = normalizeBaseUrl(baseUrl);
        return hasVersionSegment(normalized)
                ? normalized + CHAT_COMPLETIONS_PATH
                : normalized + VERSION_SEGMENT + CHAT_COMPLETIONS_PATH;
    }

    /**
     * 构造 Responses API 端点。
     */
    private String buildResponsesEndpoint(String baseUrl) {
        String normalized = normalizeBaseUrl(baseUrl);
        return hasVersionSegment(normalized)
                ? normalized + RESPONSES_PATH
                : normalized + VERSION_SEGMENT + RESPONSES_PATH;
    }

    /**
     * 粗略识别需要使用 Responses API 的推理类模型。
     */
    private boolean isResponsesModel(String model) {
        if (model == null) {
            return false;
        }
        String lowerModel = model.toLowerCase();
        // DeepSeek 等兼容 OpenAI 的推理模型只提供 chat-completions 端点：
        // 即使模型名带 reasoner 也不能走 Responses API，否则会误报 404/400。
        if (lowerModel.contains("deepseek")) {
            return false;
        }
        for (String marker : RESPONSES_MODEL_MARKERS) {
            if (lowerModel.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查错误响应中是否包含 reasoning 相关参数错误（例如 reasoning.summary unsupported_value）。
     */
    private boolean containsReasoningParamError(String body) {
        if (body == null) {
            return false;
        }
        String lowerBody = body.toLowerCase();
        boolean reasoningParamUnsupported = lowerBody.contains("reasoning")
                && lowerBody.contains("unsupported_value");
        return reasoningParamUnsupported || lowerBody.contains("reasoning.summary");
    }

    // ================= 合并的 AI 配置管理方法 =================

    /**
     * 获取AI配置（取最新一条，没有则创建默认配置）
     */
    @Transactional(readOnly = true)
    public AiEntity getAiConfig() {
        AiEntity latest = latestAiConfig();
        return latest != null ? latest : createDefaultConfig();
    }

    /**
     * 获取所有AI配置
     */
    @Transactional(readOnly = true)
    public List<AiEntity> getAllAiConfigs() {
        return aiMapper.selectList(null);
    }

    /**
     * 按 ID 读取 AI 配置，不存在时返回 null。
     */
    @Transactional(readOnly = true)
    public AiEntity getAiConfigById(Long id) {
        AiEntity config = aiMapper.selectById(id);
        if (config == null) {
            log.debug("AI 配置不存在，ID: {}", id);
        }
        return config;
    }

    /**
     * 保存或更新AI配置（introduce/prompt）。
     * <p>始终只维护最新一条配置：已有则原地更新，没有则新建一条。</p>
     */
    @Transactional
    public AiEntity saveOrUpdateAiConfig(String introduce, String prompt) {
        AiEntity latest = latestAiConfig();
        if (latest == null) {
            return createAiConfig(introduce, prompt);
        }
        latest.setIntroduce(introduce);
        latest.setPrompt(prompt);
        latest.setUpdatedAt(LocalDateTime.now());
        aiMapper.updateById(latest);
        log.info("更新AI配置，ID: {}", latest.getId());
        return latest;
    }

    /**
     * 取最新一条 AI 配置（查询结果的最后一条），不存在时返回 null。
     */
    private AiEntity latestAiConfig() {
        var configs = aiMapper.selectList(null);
        return (configs == null || configs.isEmpty()) ? null : configs.get(configs.size() - 1);
    }

    /**
     * 新建一条 AI 配置（自增 ID 由数据库回填）。
     */
    private AiEntity createAiConfig(String introduce, String prompt) {
        AiEntity created = new AiEntity();
        created.setIntroduce(introduce);
        created.setPrompt(prompt);
        LocalDateTime now = LocalDateTime.now();
        created.setCreatedAt(now);
        created.setUpdatedAt(now);
        aiMapper.insert(created);
        log.info("创建新的AI配置，ID: {}", created.getId());
        return created;
    }

    /**
     * 删除AI配置。
     *
     * @param id 配置 ID
     * @return 是否确实删除
     */
    @Transactional
    public boolean deleteAiConfig(Long id) {
        boolean deleted = aiMapper.deleteById(id) > 0;
        if (deleted) {
            log.info("删除AI配置成功，ID: {}", id);
        }
        return deleted;
    }

    /**
     * 创建默认配置：首次使用 AI 配置页时自动落一条占位配置。
     */
    @Transactional
    protected AiEntity createDefaultConfig() {
        AiEntity config = new AiEntity();
        config.setIntroduce(DEFAULT_INTRODUCE);
        config.setPrompt(DEFAULT_PROMPT);
        LocalDateTime now = LocalDateTime.now();
        config.setCreatedAt(now);
        config.setUpdatedAt(now);
        aiMapper.insert(config);
        log.info("创建默认AI配置，ID: {}", config.getId());
        return config;
    }
}
