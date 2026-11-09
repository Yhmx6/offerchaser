package com.offerchaser.worker.utils;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;

/**
 * 线程休眠工具。
 * <p>
 * 这里不再包含浏览器初始化：项目已迁到 patchright，浏览器统一由
 * {@link com.offerchaser.worker.manager.PlaywrightManager} 管理，
 * 在这里另起一套 launch/newContext 只会重复启动内核并引入反检测泄漏。
 */
@Slf4j
public class PlaywrightUtil {

    /**
     * 让当前线程休眠指定秒数。被中断时恢复中断标记并记录错误，不向调用方抛出。
     *
     * @param seconds 休眠秒数；负数按 TimeUnit 语义视为不等待
     */
    public static void sleep(int seconds) {
        try {
            TimeUnit.SECONDS.sleep(seconds);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            log.error("Sleep被中断", interrupted);
        }
    }
}
