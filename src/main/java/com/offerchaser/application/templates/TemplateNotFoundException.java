package com.offerchaser.application.templates;

/** 方向模板不存在（HTTP 404）。 */
public class TemplateNotFoundException extends RuntimeException {
    public TemplateNotFoundException(String message) {
        super(message);
    }
}
