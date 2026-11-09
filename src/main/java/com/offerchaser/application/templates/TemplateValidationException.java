package com.offerchaser.application.templates;

/** 方向模板参数/状态不合法（HTTP 400/409）。 */
public class TemplateValidationException extends RuntimeException {
    public TemplateValidationException(String message) {
        super(message);
    }
}
