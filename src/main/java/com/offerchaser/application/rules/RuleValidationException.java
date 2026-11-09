package com.offerchaser.application.rules;

/**
 * 规则校验失败（HTTP 400）。message 直接给用户看，说明哪个字段不合法。
 */
public class RuleValidationException extends RuntimeException {

    public RuleValidationException(String message) {
        super(message);
    }
}
