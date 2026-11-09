package com.offerchaser.worker.manager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Chrome 持久化 profile 的文件系统维护。
 * <p>
 * 这里只处理 profile 目录下的文件（当前是 Default/Preferences 的退出状态），
 * 不持有浏览器对象、不调用 Playwright —— 从职责上与浏览器生命周期管理分开。
 */
@Slf4j
final class ProfileMaintenance {

    /** Preferences 是 JSON，复用同一个 ObjectMapper 即可。 */
    private static final ObjectMapper PREFERENCES_MAPPER = new ObjectMapper();

    private ProfileMaintenance() {
    }

    /**
     * 把 profile 里“上次是否正常退出”的标记修正为正常退出。
     * <p>
     * 应用被强制停止（IDEA 停止按钮、kill、崩溃）后，Chrome 下次启动会弹
     * “要恢复页面吗？”模态框。这个框会挂起所有 CDP 页面操作 —— navigate 永远不返回、
     * 连超时都不触发，整个初始化线程焊死。除了启动参数，这里再把 Preferences 改掉。
     *
     * @param userDataDir Chrome 持久化 profile 目录
     */
    static void sanitizeExitState(Path userDataDir) {
        Path preferencesFile = userDataDir.resolve("Default").resolve("Preferences");
        if (!Files.isRegularFile(preferencesFile)) {
            return;
        }
        try {
            ProfileExitState exitState = readExitState(preferencesFile);
            if (exitState == null || exitState.cleanExit()) {
                return;
            }
            writeCleanExitState(preferencesFile, exitState);
            log.info("已修复浏览器 profile 的崩溃退出标记，避免下次启动弹恢复页");
        } catch (Exception sanitizeError) {
            log.warn("修复浏览器 profile 退出状态失败（不影响启动）: {}", sanitizeError.getMessage());
        }
    }

    /** profile 的退出状态快照；root/profile 保留下来供写回。 */
    private record ProfileExitState(ObjectNode root, ObjectNode profile, boolean cleanExit) {
    }

    /** 读取 Preferences；结构不符（没有 profile 节点）时返回 null，表示无需处理。 */
    private static ProfileExitState readExitState(Path preferencesFile) throws Exception {
        JsonNode root = PREFERENCES_MAPPER.readTree(preferencesFile.toFile());
        if (!(root instanceof ObjectNode rootNode)) {
            return null;
        }
        JsonNode profile = rootNode.get("profile");
        if (!(profile instanceof ObjectNode profileNode)) {
            return null;
        }
        boolean cleanExit = "Normal".equals(profileNode.path("exit_type").asText(""))
                && profileNode.path("exited_cleanly").asBoolean(true);
        return new ProfileExitState(rootNode, profileNode, cleanExit);
    }

    /** 写回正常退出标记，保留 Preferences 其余字段。 */
    private static void writeCleanExitState(Path preferencesFile, ProfileExitState exitState) throws Exception {
        exitState.profile().put("exit_type", "Normal");
        exitState.profile().put("exited_cleanly", true);
        PREFERENCES_MAPPER.writeValue(preferencesFile.toFile(), exitState.root());
    }
}
