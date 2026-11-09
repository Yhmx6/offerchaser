package com.offerchaser.verification;

import com.offerchaser.application.controller.MaintenanceController;
import com.offerchaser.application.persistence.DatabaseMaintenanceService;
import com.offerchaser.application.persistence.SchemaMigrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B5 维护接口契约（Mockito service）：/status 数据组装、/backups 默认验证最新且非法 kind→400、
 * /backup 手动备份成功与失败映射。只读接口开放由认证契约覆盖。
 */
class B5MaintenanceControllerContractTest {

    private DatabaseMaintenanceService maintenanceService;
    private SchemaMigrationService migrationService;
    private MaintenanceController controller;

    @BeforeEach
    void setUp() {
        maintenanceService = mock(DatabaseMaintenanceService.class);
        migrationService = mock(SchemaMigrationService.class);
        controller = new MaintenanceController(maintenanceService, migrationService);
    }

    @Test
    void statusCombinesDatabaseBackupAndMigration() {
        when(maintenanceService.status()).thenReturn(Map.of("databasePath", "db/offerchaser.db",
                "backupCount", 3, "preUpgradeBackupCount", 1, "preRunBackupCount", 2));
        when(maintenanceService.backupStatus()).thenReturn(Map.of("rollbackWarning", "warning",
                "latestSendFactAt", "2026-11-04 10:00:00"));
        when(migrationService.currentVersion()).thenReturn(8);
        when(migrationService.isReady()).thenReturn(true);

        ResponseEntity<Map<String, Object>> response = controller.status();

        assertEquals(200, response.getStatusCode().value());
        assertEquals(Boolean.TRUE, response.getBody().get("success"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        assertNotNull(data.get("database"));
        assertNotNull(data.get("backup"));
        @SuppressWarnings("unchecked")
        Map<String, Object> migration = (Map<String, Object>) data.get("migration");
        assertEquals(8, migration.get("schemaVersion"));
        assertEquals(SchemaMigrationService.TARGET_VERSION, migration.get("targetVersion"));
        assertEquals(true, migration.get("ready"));
        verify(maintenanceService).status();
        verify(maintenanceService).backupStatus();
    }

    @Test
    void statusFailureReturns500WithoutBusinessCode() {
        when(maintenanceService.status()).thenThrow(new IllegalStateException("数据库不可用"));
        ResponseEntity<Map<String, Object>> response = controller.status();
        assertEquals(500, response.getStatusCode().value());
        assertEquals(Boolean.FALSE, response.getBody().get("success"));
        assertTrue(String.valueOf(response.getBody().get("message")).contains("读取维护状态失败"));
        assertFalse(response.getBody().containsKey("code"));
    }

    @Test
    void backupsListVerifiesLatestItemOnly() {
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(new LinkedHashMap<>(Map.of("path", "/tmp/backups/pre-run/a.db",
                "kind", "pre-run", "sizeBytes", 100L)));
        items.add(new LinkedHashMap<>(Map.of("path", "/tmp/backups/pre-run/b.db",
                "kind", "pre-run", "sizeBytes", 90L)));
        when(maintenanceService.listBackups("pre-run", 20)).thenReturn(items);
        when(maintenanceService.verifyBackup(Path.of("/tmp/backups/pre-run/a.db")))
                .thenReturn(Map.of("verified", true, "integrity", "ok"));
        when(maintenanceService.verifyBackup(Path.of("/tmp/backups/pre-run/b.db")))
                .thenReturn(Map.of("verified", false, "reason", "should-not-be-called"));

        ResponseEntity<Map<String, Object>> response = controller.backups("pre-run", 20);

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        assertEquals("pre-run", data.get("kind"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> returned = (List<Map<String, Object>>) data.get("items");
        assertEquals(2, returned.size());
        assertNotNull(returned.get(0).get("verification"), "默认只验证最新一份备份");
        assertNull(returned.get(1).get("verification"), "非最新备份不做验证（避免大文件开销）");
        verify(maintenanceService).verifyBackup(Path.of("/tmp/backups/pre-run/a.db"));
        verify(maintenanceService, never()).verifyBackup(Path.of("/tmp/backups/pre-run/b.db"));
    }

    @Test
    void backupsInvalidKindReturns400WithCode() {
        when(maintenanceService.listBackups(anyString(), anyInt()))
                .thenThrow(new IllegalArgumentException("kind 必须是 pre-run/pre-upgrade/all"));
        ResponseEntity<Map<String, Object>> response = controller.backups("bogus", 20);
        assertEquals(400, response.getStatusCode().value());
        assertEquals(Boolean.FALSE, response.getBody().get("success"));
        assertEquals("INVALID_BACKUP_KIND", response.getBody().get("code"));
        assertTrue(String.valueOf(response.getBody().get("message")).contains("kind 必须是"));
    }

    @Test
    void backupsClampsLimitAndHandlesGenericFailure() {
        when(maintenanceService.listBackups("pre-run", 20)).thenReturn(List.of());
        controller.backups("pre-run", 0);
        verify(maintenanceService).listBackups("pre-run", 20);

        when(maintenanceService.listBackups("pre-run", 100)).thenReturn(List.of());
        controller.backups("pre-run", 1000);
        verify(maintenanceService).listBackups("pre-run", 100);

        when(maintenanceService.listBackups("all", 20)).thenThrow(new RuntimeException("列表失败"));
        ResponseEntity<Map<String, Object>> failed = controller.backups("all", 20);
        assertEquals(500, failed.getStatusCode().value());
        assertTrue(String.valueOf(failed.getBody().get("message")).contains("读取备份列表失败"));
    }

    @Test
    void manualBackupReturnsPathAndVerification() {
        Path backupPath = Path.of("/tmp/backups/pre-run/manual.db");
        when(maintenanceService.backup("manual")).thenReturn(backupPath);
        when(maintenanceService.verifyBackup(backupPath))
                .thenReturn(Map.of("verified", true, "integrity", "ok"));

        ResponseEntity<Map<String, Object>> response = controller.manualBackup();

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        assertEquals(backupPath.toString(), data.get("path"));
        assertEquals(true, data.get("verified"));
        verify(maintenanceService).backup("manual");
        verify(maintenanceService).verifyBackup(backupPath);
    }

    @Test
    void manualBackupFailureReturns500() {
        when(maintenanceService.backup("manual")).thenThrow(new IllegalStateException("磁盘已满"));
        ResponseEntity<Map<String, Object>> response = controller.manualBackup();
        assertEquals(500, response.getStatusCode().value());
        assertTrue(String.valueOf(response.getBody().get("message")).contains("手动备份失败"));
    }

    @Test
    void manualBackupWithNullPathDoesNotVerify() {
        when(maintenanceService.backup("manual")).thenReturn(null);
        ResponseEntity<Map<String, Object>> response = controller.manualBackup();
        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        assertNull(data.get("path"));
        assertEquals(false, data.get("verified"));
        verify(maintenanceService, never()).verifyBackup(any());
    }
}
