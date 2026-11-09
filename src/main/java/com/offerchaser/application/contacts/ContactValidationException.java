package com.offerchaser.application.contacts;

/** 联系人状态/跟进接口的校验失败（HTTP 400）。 */
public class ContactValidationException extends RuntimeException {
    public ContactValidationException(String message) {
        super(message);
    }
}
