package com.offerchaser.application.metrics;

/** 统计参数校验失败（HTTP 400，code=INVALID_RANGE / INVALID_METRIC）。 */
public class MetricsValidationException extends RuntimeException {

    private final String code;

    public MetricsValidationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
