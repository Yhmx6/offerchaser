package com.offerchaser.application.onboarding;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 简历分析/模板草稿接口的异常出口：把业务异常映射成稳定的错误码与 HTTP 状态。
 * <p>
 * 只处理本功能相关异常，不接管全局异常（不影响现有接口行为）。
 * 前端依赖 {@code success:false} + {@code code} + {@code message} 结构做提示与兜底引导。
 * </p>
 */
@Slf4j
@RestControllerAdvice
public class OnboardingExceptionHandler {

    @ExceptionHandler(OnboardingException.class)
    public ResponseEntity<Map<String, Object>> handleOnboarding(OnboardingException exception) {
        log.warn("简历/模板草稿请求失败 | code={} | status={} | message={}",
                exception.code(), exception.status().value(), exception.getMessage());
        return response(exception.status(), exception.code(), exception.getMessage());
    }

    /** multipart 超过容器上限（10MB 文件 / 11MB 请求）时给前端明确错误码，而不是默认错误页。 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUploadSizeExceeded() {
        return response(HttpStatus.PAYLOAD_TOO_LARGE, OnboardingException.INVALID_IMAGE,
                "图片不能超过 10MB，请压缩后重试");
    }

    private static ResponseEntity<Map<String, Object>> response(HttpStatus status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", code);
        body.put("message", message == null || message.isBlank() ? "请求失败" : message);
        return ResponseEntity.status(status).body(body);
    }
}
