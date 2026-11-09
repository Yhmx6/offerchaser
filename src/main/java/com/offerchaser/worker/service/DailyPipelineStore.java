package com.offerchaser.worker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerchaser.worker.dto.PipelineStageStat;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 今日投递管线快照持久化。
 *
 * <p>内存里的 {@code BossRunContext} 只代表当前运行；停止后重新开始、或程序重启后，
 * 当天已结束的管线计数会丢失。这个存储把每轮结束时各阶段的汇总写到
 * {@code data/pipeline-daily.json}，状态接口启动时可恢复当天累计。</p>
 *
 * <p>只保存阶段计数和结果分布，不保存岗位正文、Cookie、消息内容；文件损坏时安全降级为空。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DailyPipelineStore {

    private static final Path DATA_FILE = Paths.get(System.getProperty("user.dir"), "data", "pipeline-daily.json")
            .toAbsolutePath()
            .normalize();

    private final ObjectMapper objectMapper;

    /** 一条已结束 run 的管线阶段汇总。 */
    public record StoredRun(String runId, String date, List<PipelineStageStat> stages, String updatedAt) {
    }

    /** 保存文件结构：只保留一个自然日，避免无限增长。 */
    private record FileState(String date, List<StoredRun> runs) {
    }

    /** 读取指定日期的已保存 run；文件不存在、日期不匹配或损坏时返回空列表。 */
    public synchronized List<StoredRun> load(LocalDate date) {
        if (date == null || !Files.isRegularFile(DATA_FILE)) {
            return List.of();
        }
        try {
            FileState state = objectMapper.readValue(DATA_FILE.toFile(), FileState.class);
            if (state == null || !date.toString().equals(state.date()) || state.runs() == null) {
                return List.of();
            }
            return List.copyOf(state.runs());
        } catch (Exception readError) {
            log.warn("读取今日管线快照失败，将按空数据继续：{}", readError.getMessage());
            return List.of();
        }
    }

    /** 保存/更新一轮已结束任务的阶段汇总；同一天内按 runId 去重。 */
    public synchronized void save(String runId, LocalDate date, List<PipelineStageStat> stages, long updatedAt) {
        if (date == null || runId == null || runId.isBlank()) {
            return;
        }
        try {
            List<StoredRun> existing = new ArrayList<>(loadUnlocked(date));
            Map<String, StoredRun> byRunId = new LinkedHashMap<>();
            for (StoredRun run : existing) {
                if (run != null && run.runId() != null) {
                    byRunId.put(run.runId(), run);
                }
            }
            byRunId.put(runId, new StoredRun(runId, date.toString(),
                    stages == null ? List.of() : List.copyOf(stages), String.valueOf(updatedAt)));
            FileState next = new FileState(date.toString(), new ArrayList<>(byRunId.values()));
            writeAtomically(next);
        } catch (Exception writeError) {
            log.warn("保存今日管线快照失败（不影响投递）：{}", writeError.getMessage());
        }
    }

    private List<StoredRun> loadUnlocked(LocalDate date) throws Exception {
        if (!Files.isRegularFile(DATA_FILE)) {
            return List.of();
        }
        FileState state = objectMapper.readValue(DATA_FILE.toFile(), FileState.class);
        if (state == null || !date.toString().equals(state.date()) || state.runs() == null) {
            return List.of();
        }
        return state.runs();
    }

    private void writeAtomically(FileState state) throws Exception {
        Path parent = DATA_FILE.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = DATA_FILE.resolveSibling(DATA_FILE.getFileName() + ".tmp");
        objectMapper.writeValue(temp.toFile(), state);
        try {
            Files.move(temp, DATA_FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temp, DATA_FILE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
