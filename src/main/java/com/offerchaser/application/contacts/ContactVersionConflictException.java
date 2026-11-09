package com.offerchaser.application.contacts;

/** 联系人状态版本冲突（HTTP 409）。 */
public class ContactVersionConflictException extends RuntimeException {
    private final int currentVersion;

    public ContactVersionConflictException(int currentVersion) {
        super("联系人状态已被其他操作修改，请刷新后重试（当前版本 v" + currentVersion + "）");
        this.currentVersion = currentVersion;
    }

    public int getCurrentVersion() {
        return currentVersion;
    }
}
