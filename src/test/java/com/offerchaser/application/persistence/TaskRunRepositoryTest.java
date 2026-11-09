package com.offerchaser.application.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;

import static com.offerchaser.application.persistence.PersistenceTestSupport.scalar;
import static com.offerchaser.application.persistence.PersistenceTestSupport.scalarString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 任务运行记录测试：创建、状态流转、结束与幂等。
 */
class TaskRunRepositoryTest {

    @TempDir
    Path directory;

    @Test
    void startUpdateAndFinishRoundTrip() {
        SQLiteDataSource dataSource = PersistenceTestSupport.migratedDataSource(directory);
        TaskRunRepository repository = new TaskRunRepository(dataSource);

        repository.start("run-1", "boss", "{\"keywords\":[\"Java\"]}");

        assertEquals("STARTING", scalarString(dataSource,
                "SELECT state FROM task_run WHERE run_id = 'run-1'"));
        assertEquals("boss", scalarString(dataSource,
                "SELECT platform FROM task_run WHERE run_id = 'run-1'"));
        assertEquals("{\"keywords\":[\"Java\"]}", scalarString(dataSource,
                "SELECT config_snapshot FROM task_run WHERE run_id = 'run-1'"));
        assertNotNull(scalarString(dataSource,
                "SELECT started_at FROM task_run WHERE run_id = 'run-1'"));

        // 同一 runId 重复 start 不会产生第二条记录
        repository.start("run-1", "boss", "{}");
        assertEquals(1, scalar(dataSource, "SELECT COUNT(*) FROM task_run"));

        repository.updateState("run-1", "RUNNING", null, null);
        assertEquals("RUNNING", scalarString(dataSource,
                "SELECT state FROM task_run WHERE run_id = 'run-1'"));

        repository.updateState("run-1", "PAUSED_VERIFICATION", "需要人工验证", null);
        assertEquals("需要人工验证", scalarString(dataSource,
                "SELECT stop_reason FROM task_run WHERE run_id = 'run-1'"));

        repository.finish("run-1", "COMPLETED", "用户停止", null);
        assertEquals("COMPLETED", scalarString(dataSource,
                "SELECT state FROM task_run WHERE run_id = 'run-1'"));
        assertEquals("用户停止", scalarString(dataSource,
                "SELECT stop_reason FROM task_run WHERE run_id = 'run-1'"));
        assertNotNull(scalarString(dataSource,
                "SELECT ended_at FROM task_run WHERE run_id = 'run-1'"));

        repository.finish("run-1", "FAILED", "异常", "页面崩溃");
        assertEquals("页面崩溃", scalarString(dataSource,
                "SELECT error FROM task_run WHERE run_id = 'run-1'"));
    }

    @Test
    void operationsOnUnknownRunIdDoNotThrow() {
        SQLiteDataSource dataSource = PersistenceTestSupport.migratedDataSource(directory);
        TaskRunRepository repository = new TaskRunRepository(dataSource);

        repository.updateState("missing-run", "RUNNING", null, null);
        repository.finish("missing-run", "FAILED", "异常", "错误");
        repository.start(null, "boss", null);
        repository.updateState(null, "RUNNING", null, null);

        assertEquals(0, scalar(dataSource, "SELECT COUNT(*) FROM task_run"));
    }
}
