package com.offerchaser.verification;

import com.offerchaser.application.controller.BossMetricsController;
import com.offerchaser.application.metrics.MetricsService;
import com.offerchaser.application.metrics.MetricsValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B4 统计接口契约（Mockito service，不启动 Spring）：
 * 三接口映射、数据外壳、参数透传、400（INVALID_RANGE / INVALID_METRIC）与 500 兜底。
 */
class BossMetricsControllerContractTest {

    private MetricsService metricsService;
    private BossMetricsController controller;

    @BeforeEach
    void setUp() {
        metricsService = mock(MetricsService.class);
        controller = new BossMetricsController(metricsService);
    }

    @Test
    void overviewReturnsDataAndPassesParams() throws Exception {
        Map<String, Object> data = overviewData();
        when(metricsService.overview("2026-11-01", "2026-11-05", "R1")).thenReturn(data);

        ResponseEntity<Map<String, Object>> response =
                controller.overview("2026-11-01", "2026-11-05", "R1");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(Boolean.TRUE, response.getBody().get("success"));
        assertEquals(data, response.getBody().get("data"));
        verify(metricsService).overview("2026-11-01", "2026-11-05", "R1");
    }

    @Test
    void overviewValidationErrorsMapTo400WithCode() {
        when(metricsService.overview(any(), any(), any()))
                .thenThrow(new MetricsValidationException("INVALID_RANGE", "from 不能晚于 to"));

        ResponseEntity<Map<String, Object>> response = controller.overview("2026-11-05", "2026-11-01", null);

        assertEquals(400, response.getStatusCode().value());
        assertEquals(Boolean.FALSE, response.getBody().get("success"));
        assertEquals("INVALID_RANGE", response.getBody().get("code"));
        assertTrue(String.valueOf(response.getBody().get("message")).contains("from 不能晚于 to"));
    }

    @Test
    void overviewUnexpectedErrorReturns500Envelope() {
        when(metricsService.overview(any(), any(), any()))
                .thenThrow(new IllegalStateException("数据库不可用"));

        ResponseEntity<Map<String, Object>> response = controller.overview(null, null, null);

        assertEquals(500, response.getStatusCode().value());
        assertEquals(Boolean.FALSE, response.getBody().get("success"));
        assertTrue(String.valueOf(response.getBody().get("message")).contains("读取统计总览失败"));
        assertFalse(response.getBody().containsKey("code"), "500 兜底外壳不应伪造业务错误码");
    }

    @Test
    void seriesReturnsDataAndMapsInvalidMetric() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("metric", "newJobs");
        data.put("timezone", "Asia/Shanghai");
        data.put("points", List.of(Map.of("date", "2026-11-01", "value", 3L)));
        when(metricsService.series("2026-11-01", "2026-11-05", "newJobs")).thenReturn(data);

        ResponseEntity<Map<String, Object>> ok = controller.series("2026-11-01", "2026-11-05", "newJobs");
        assertEquals(200, ok.getStatusCode().value());
        assertEquals(data, ok.getBody().get("data"));
        verify(metricsService).series("2026-11-01", "2026-11-05", "newJobs");

        when(metricsService.series(any(), any(), any()))
                .thenThrow(new MetricsValidationException("INVALID_METRIC", "metric 非法"));
        ResponseEntity<Map<String, Object>> invalid = controller.series(null, null, "bogus");
        assertEquals(400, invalid.getStatusCode().value());
        assertEquals("INVALID_METRIC", invalid.getBody().get("code"));

        // 已有抛异常 stub 时再次 when(mock.x()) 会先触发旧 stub，必须用 doThrow 覆盖
        org.mockito.Mockito.doThrow(new IllegalStateException("聚合失败"))
                .when(metricsService).series(any(), any(), any());
        ResponseEntity<Map<String, Object>> error = controller.series(null, null, "newJobs");
        assertEquals(500, error.getStatusCode().value());
        assertTrue(String.valueOf(error.getBody().get("message")).contains("读取趋势序列失败"));
    }

    @Test
    void collectionReturnsDataAndValidationErrors() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("timezone", "Asia/Shanghai");
        data.put("keywords", List.of());
        data.put("daily", List.of());
        when(metricsService.collection("2026-11-01", "2026-11-05")).thenReturn(data);

        ResponseEntity<Map<String, Object>> ok = controller.collection("2026-11-01", "2026-11-05");
        assertEquals(200, ok.getStatusCode().value());
        assertEquals(data, ok.getBody().get("data"));
        @SuppressWarnings("unchecked")
        Map<String, Object> bodyData = (Map<String, Object>) ok.getBody().get("data");
        assertNotNull(bodyData.get("timezone"));
        verify(metricsService).collection("2026-11-01", "2026-11-05");

        when(metricsService.collection(any(), any()))
                .thenThrow(new MetricsValidationException("INVALID_RANGE", "时间跨度不能超过 366 天"));
        ResponseEntity<Map<String, Object>> invalid = controller.collection("2024-01-01", "2026-01-01");
        assertEquals(400, invalid.getStatusCode().value());
        assertEquals("INVALID_RANGE", invalid.getBody().get("code"));

        org.mockito.Mockito.doThrow(new RuntimeException("查询失败"))
                .when(metricsService).collection(any(), any());
        ResponseEntity<Map<String, Object>> error = controller.collection(null, null);
        assertEquals(500, error.getStatusCode().value());
        assertTrue(String.valueOf(error.getBody().get("message")).contains("读取采集汇总失败"));
    }

    private static Map<String, Object> overviewData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("timezone", "Asia/Shanghai");
        data.put("range", Map.of("from", "2026-11-01", "to", "2026-11-05", "days", 5));
        data.put("metrics", Map.of("newJobs", 4L, "textConfirmed", 2L));
        data.put("dataQuality", Map.of("unknownText", 2L));
        data.put("definitions", Map.of("newJobs", "口径"));
        data.put("links", Map.of("attempts", "/boss/attempts?from=2026-11-01&to=2026-11-05"));
        return data;
    }
}
