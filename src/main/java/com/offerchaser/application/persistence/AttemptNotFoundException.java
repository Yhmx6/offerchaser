package com.offerchaser.application.persistence;

/**
 * 人工核对目标投递尝试不存在（对应 HTTP 404）。
 */
public class AttemptNotFoundException extends RuntimeException {

    private final String attemptId;

    public AttemptNotFoundException(String attemptId) {
        super("投递尝试不存在：" + attemptId);
        this.attemptId = attemptId;
    }

    public String getAttemptId() {
        return attemptId;
    }
}
