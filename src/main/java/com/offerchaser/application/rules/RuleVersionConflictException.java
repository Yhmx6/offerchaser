package com.offerchaser.application.rules;

/**
 * 规则版本冲突（HTTP 409）：提交时携带的 expectedVersion 与当前最新版本不一致。
 */
public class RuleVersionConflictException extends RuntimeException {

    private final int currentVersion;

    public RuleVersionConflictException(int currentVersion) {
        super("规则版本冲突：当前最新版本为 v" + currentVersion + "，请刷新后重新提交");
        this.currentVersion = currentVersion;
    }

    public int getCurrentVersion() {
        return currentVersion;
    }
}
