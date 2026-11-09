package com.offerchaser.application.persistence;

/**
 * 人工核对请求参数非法（对应 HTTP 400）。
 * <p>
 * 例如：resolution 不是 SENT/NOT_SENT/STILL_UNKNOWN；reason 为空或超过 500 字；
 * expectedVersion 为负数；当前 text_status 不允许该人工核对动作。
 */
public class InvalidResolutionException extends RuntimeException {

    public InvalidResolutionException(String message) {
        super(message);
    }
}
