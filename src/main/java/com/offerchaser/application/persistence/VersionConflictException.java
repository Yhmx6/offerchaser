package com.offerchaser.application.persistence;

/**
 * 人工核对版本冲突（对应 HTTP 409，code=VERSION_CONFLICT）。
 * <p>
 * 前端必须刷新详情后带上最新 version 重新提交。
 */
public class VersionConflictException extends RuntimeException {

    private final String attemptId;
    private final int currentVersion;
    private final String currentStatus;

    public VersionConflictException(String attemptId, int currentVersion, String currentStatus) {
        super("版本冲突：当前 version=" + currentVersion
                + (currentStatus == null ? "" : "，当前 text_status=" + currentStatus));
        this.attemptId = attemptId;
        this.currentVersion = currentVersion;
        this.currentStatus = currentStatus;
    }

    public String getAttemptId() {
        return attemptId;
    }

    public int getCurrentVersion() {
        return currentVersion;
    }

    public String getCurrentStatus() {
        return currentStatus;
    }
}
