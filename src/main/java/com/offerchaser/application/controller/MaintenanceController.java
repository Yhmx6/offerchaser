package com.offerchaser.application.controller;

import com.offerchaser.application.persistence.DatabaseMaintenanceService;
import com.offerchaser.application.persistence.SchemaMigrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 阶段 B B5：数据库备份/迁移状态与手动备份。
 * <p>
 * 读接口开放（本地回环），写接口（手动备份）受本机会话认证保护。
 */
@Slf4j
@RestController
@RequestMapping("/api/maintenance")
@RequiredArgsConstructor
public class MaintenanceController {

    private final DatabaseMaintenanceService databaseMaintenanceService;
    private final SchemaMigrationService schemaMigrationService;

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("database", databaseMaintenanceService.status());
            data.put("backup", databaseMaintenanceService.backupStatus());
            Map<String, Object> migration = new HashMap<>();
            migration.put("schemaVersion", schemaMigrationService.currentVersion());
            migration.put("targetVersion", SchemaMigrationService.TARGET_VERSION);
            migration.put("ready", schemaMigrationService.isReady());
            data.put("migration", migration);
            return ok(data);
        } catch (Exception e) {
            return error("读取维护状态失败", e);
        }
    }

    /**
     * 列出备份：kind=pre-run|pre-upgrade|all，默认 all；默认只验证最新一份（避免大文件开销）。
     */
    @GetMapping("/backups")
    public ResponseEntity<Map<String, Object>> backups(
            @RequestParam(value = "kind", required = false, defaultValue = "all") String kind,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit) {
        try {
            int safeLimit = limit <= 0 ? 20 : Math.min(limit, 100);
            List<Map<String, Object>> items = new ArrayList<>(databaseMaintenanceService.listBackups(kind, safeLimit));
            for (int i = 0; i < items.size() && i < 1; i++) {
                Map<String, Object> item = items.get(i);
                Object path = item.get("path");
                if (path != null) {
                    item.put("verification", databaseMaintenanceService.verifyBackup(Path.of(String.valueOf(path))));
                }
            }
            Map<String, Object> data = new HashMap<>();
            data.put("kind", kind);
            data.put("items", items);
            return ok(data);
        } catch (IllegalArgumentException invalidKind) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("code", "INVALID_BACKUP_KIND");
            response.put("message", invalidKind.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            return error("读取备份列表失败", e);
        }
    }

    /** 手动备份（需要本机会话认证 + CSRF 头）。 */
    @PostMapping("/backup")
    public ResponseEntity<Map<String, Object>> manualBackup() {
        try {
            Path path = databaseMaintenanceService.backup("manual");
            Map<String, Object> data = new HashMap<>();
            data.put("path", path == null ? null : path.toString());
            data.put("verified", path != null && Boolean.TRUE.equals(
                    databaseMaintenanceService.verifyBackup(path).get("verified")));
            return ok(data);
        } catch (Exception e) {
            return error("手动备份失败", e);
        }
    }

    private static ResponseEntity<Map<String, Object>> ok(Object data) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("data", data);
        return ResponseEntity.ok(response);
    }

    private static ResponseEntity<Map<String, Object>> error(String message, Exception e) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("message", message + ": " + e.getMessage());
        return ResponseEntity.internalServerError().body(response);
    }
}
