package com.offerchaser.application.onboarding;

import com.offerchaser.application.mapper.AiMapper;
import com.offerchaser.application.service.AiService;
import com.offerchaser.application.service.ConfigService;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * AiService 的视觉/端点路由约定测试（反射调用私有方法，不发起网络请求）：
 * <ul>
 *   <li>DeepSeek 模型（包括名字带 reasoner 的）必须走 chat-completions，不能误走 Responses；</li>
 *   <li>视觉请求体必须是 chat-completions 多模态结构（messages + image_url），不能出现 Responses 的 input 字段。</li>
 * </ul>
 */
class AiServiceVisionRoutingTest {

    private final AiService aiService = new AiService(mock(ConfigService.class), mock(AiMapper.class));

    @Test
    void deepSeekModelsNeverUseResponsesApi() throws Exception {
        Method method = AiService.class.getDeclaredMethod("isResponsesModel", String.class);
        method.setAccessible(true);

        assertFalse((Boolean) method.invoke(aiService, "deepseek-chat"));
        assertFalse((Boolean) method.invoke(aiService, "deepseek-reasoner"));
        assertFalse((Boolean) method.invoke(aiService, "deepseek-r1"));
    }

    @Test
    void openAiReasoningModelsStillUseResponsesApi() throws Exception {
        Method method = AiService.class.getDeclaredMethod("isResponsesModel", String.class);
        method.setAccessible(true);

        assertTrue((Boolean) method.invoke(aiService, "o3-mini"));
        assertTrue((Boolean) method.invoke(aiService, "gpt-4.1"));
    }

    @Test
    void visionRequestBodyUsesChatCompletionsImageUrlAndNoInputField() throws Exception {
        Method method = AiService.class.getDeclaredMethod("buildVisionRequestBody",
                String.class, String.class, String.class, String.class);
        method.setAccessible(true);

        String json = (String) method.invoke(aiService, "deepseek-chat", "系统提示", "请提取画像",
                "data:image/jpeg;base64,QUJD");
        JSONObject body = new JSONObject(json);

        assertEquals("deepseek-chat", body.getString("model"));
        assertFalse(body.has("input"), "视觉请求不能使用 Responses API 的 input 字段");
        JSONArray messages = body.getJSONArray("messages");
        assertEquals(2, messages.length());
        assertEquals("system", messages.getJSONObject(0).getString("role"));
        assertEquals("user", messages.getJSONObject(1).getString("role"));

        JSONArray content = messages.getJSONObject(1).getJSONArray("content");
        assertEquals("text", content.getJSONObject(0).getString("type"));
        assertEquals("请提取画像", content.getJSONObject(0).getString("text"));
        assertEquals("image_url", content.getJSONObject(1).getString("type"));
        assertEquals("data:image/jpeg;base64,QUJD",
                content.getJSONObject(1).getJSONObject("image_url").getString("url"));
    }

    @Test
    void visionRequestBodySkipsBlankSystemPrompt() throws Exception {
        Method method = AiService.class.getDeclaredMethod("buildVisionRequestBody",
                String.class, String.class, String.class, String.class);
        method.setAccessible(true);

        String json = (String) method.invoke(aiService, "deepseek-chat", "  ", "请提取画像",
                "data:image/jpeg;base64,QUJD");
        JSONArray messages = new JSONObject(json).getJSONArray("messages");
        assertEquals(1, messages.length(), "空白 system 提示不应发送");
        assertEquals("user", messages.getJSONObject(0).getString("role"));
    }
}
