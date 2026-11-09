package com.offerchaser.application.contacts;

/** 联系人/跟进对象不存在（HTTP 404）。 */
public class ContactNotFoundException extends RuntimeException {
    public ContactNotFoundException(String message) {
        super(message);
    }
}
