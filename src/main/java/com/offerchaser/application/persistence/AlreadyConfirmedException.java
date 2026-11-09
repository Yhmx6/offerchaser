package com.offerchaser.application.persistence;

/**
 * 已确认成功的记录不允许被人工改成「未发送 / 仍未知」（对应 HTTP 409，code=ALREADY_CONFIRMED）。
 * <p>
 * 已 CONFIRMED 的记录再提交 SENT 属于幂等成功，不会抛这个异常。
 */
public class AlreadyConfirmedException extends RuntimeException {

    private final String attemptId;

    public AlreadyConfirmedException(String attemptId) {
        super("该投递已确认成功，不能再标记为未发送/仍未知：" + attemptId);
        this.attemptId = attemptId;
    }

    public String getAttemptId() {
        return attemptId;
    }
}
