package com.offerchaser.application.status;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * D2：每天第一次启动时，后台补读前一天的投递/已读/回复情况。
 * 异步延迟执行，不阻塞管理台启动；当天已成功补读则服务层自动跳过。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeliveryStatusStartupSyncer {

    private final DeliveryStatusSyncService syncService;

    @Value("${offerchaser.status-sync.startup-delay-seconds:20}")
    private int startupDelaySeconds = 20;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        Thread worker = new Thread(() -> {
            try {
                Thread.sleep(Math.max(1, startupDelaySeconds) * 1000L);
                log.info("开始每日首次投递状态补读（前一天）");
                syncService.syncPreviousDayIfNeeded();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                // 启动补读失败不能影响管理台使用；下次启动会重试（当天未成功不会写 COMPLETED）
                log.warn("每日首次投递状态补读失败：{}", e.getMessage());
            }
        }, "delivery-status-startup-sync");
        worker.setDaemon(true);
        worker.start();
    }
}
