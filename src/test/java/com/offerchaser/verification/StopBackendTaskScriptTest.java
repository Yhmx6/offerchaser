package com.offerchaser.verification;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * D-05 停止脚本验收（只读脚本 + PowerShell 语法解析；<b>不执行脚本、不启动/停止真实任务</b>）：
 * <ul>
 *   <li>{@code OfferChaser.ps1} 为 UTF-8 BOM（Windows PowerShell 5.1 中文不乱码），语法可被 PowerShell Parser 解析；</li>
 *   <li>{@code Stop-BackendTask} 顺序：读 token → login → GET status → 仅 RUNNING/STARTING/
 *       PAUSED_VERIFICATION/STOPPING 才 POST /api/boss/stop（带 CSRF 头）→ 最多 60s 轮询
 *       state 非运行且 workerExited=true → 超时后才由 Stop-App taskkill 兜底；</li>
 *   <li>IDLE 等非运行态直接返回，不会发出停止请求。</li>
 * </ul>
 */
class StopBackendTaskScriptTest {

    private static final Path SCRIPT = Path.of("OfferChaser.ps1");

    @Test
    void scriptIsUtf8WithBomForChineseOutput() throws Exception {
        assertTrue(Files.isRegularFile(SCRIPT), "仓库根目录缺少 OfferChaser.ps1");
        byte[] bytes = Files.readAllBytes(SCRIPT);
        assertTrue(bytes.length > 3, "脚本内容异常");
        assertEquals((byte) 0xEF, bytes[0], "缺少 UTF-8 BOM 第 1 字节");
        assertEquals((byte) 0xBB, bytes[1], "缺少 UTF-8 BOM 第 2 字节");
        assertEquals((byte) 0xBF, bytes[2], "缺少 UTF-8 BOM 第 3 字节");

        String text = Files.readString(SCRIPT, StandardCharsets.UTF_8);
        assertTrue(text.contains("D-05"), "脚本应保留 D-05 说明注释");
        assertTrue(text.contains("优雅停止"), "停止脚本应说明“优雅停止”口径");
    }

    @Test
    void stopBackendTaskRequestsGracefulStopWithCsrfAndPollsWorkerExit() throws Exception {
        String script = Files.readString(SCRIPT, StandardCharsets.UTF_8);
        String task = functionBody(script, "function Stop-BackendTask", "function Stop-App");

        // 1) 读本地令牌 → login 建立会话
        assertTrue(task.contains("data\\local-session-token"), "必须先读取本机会话令牌文件");
        assertTrue(task.contains("Get-Content -LiteralPath $tokenPath -Raw"),
                "令牌应原样读取后用于登录");
        assertTrue(task.contains("/api/session/login"), "必须先调用登录接口");
        assertTrue(task.contains("$token = $token") || task.contains("token = $token"),
                "登录请求体应携带本地令牌");
        // 2) login → GET status 判定任务状态
        assertTrue(task.contains("/api/boss/status"), "登录后必须读取后端任务状态");
        assertTrue(task.contains("$state = [string]$status.state"), "应读取任务 state 字段");
        for (String state : new String[]{"RUNNING", "STARTING", "PAUSED_VERIFICATION", "STOPPING"}) {
            assertTrue(task.contains("'" + state + "'"), "运行态集合缺少：" + state);
        }
        // 3) 非运行态（如 IDLE）直接返回，不请求停止
        assertTrue(task.contains("if ($runningStates -notcontains $state) { return }"),
                "IDLE 等非运行态必须直接返回，不发送停止请求");
        // 4) POST /api/boss/stop + CSRF 头
        assertTrue(task.contains("/api/boss/stop"), "运行中必须调用停止接口");
        assertTrue(task.contains("X-Requested-With"), "写接口必须带 CSRF 头");
        assertTrue(task.contains("'offerchaser'"), "CSRF 头值应为 offerchaser");
        assertTrue(task.contains("-Headers @{ 'X-Requested-With' = 'offerchaser' }"),
                "停止请求必须携带 X-Requested-With: offerchaser");
        // 5) 最多 60s 轮询：state 非运行且 workerExited=true 才算退出
        assertTrue(task.contains("for ($i = 0; $i -lt 60; $i++)"), "等待 worker 退出最多 60 秒");
        assertTrue(task.contains("Start-Sleep -Seconds 1"), "轮询间隔应为 1 秒");
        assertTrue(task.contains("$runningStates -notcontains $currentState"), "轮询必须判断 state 已非运行");
        assertTrue(task.contains("$current.workerExited -eq $true"), "轮询必须判断 workerExited=true");
        assertTrue(task.contains("worker 已退出"), "worker 正常退出应写日志");
        // 6) 60s 超时才兜底
        assertTrue(task.contains("等待 worker 退出超时，执行兜底结束进程"),
                "超过 60 秒未退出应进入兜底结束进程分支");

        // 顺序：令牌 → 登录 → 读状态 → 运行态判断 → 停止请求 → 轮询 → workerExited → 超时兜底
        int tokenIdx = task.indexOf("local-session-token");
        int loginIdx = task.indexOf("/api/session/login");
        int statusIdx = task.indexOf("/api/boss/status");
        int guardIdx = task.indexOf("if ($runningStates -notcontains $state) { return }");
        int stopIdx = task.indexOf("/api/boss/stop");
        int pollIdx = task.indexOf("for ($i = 0; $i -lt 60; $i++)");
        int exitedIdx = task.indexOf("$current.workerExited -eq $true");
        int timeoutIdx = task.indexOf("等待 worker 退出超时");
        assertTrue(tokenIdx > 0 && tokenIdx < loginIdx && loginIdx < statusIdx && statusIdx < guardIdx
                        && guardIdx < stopIdx && stopIdx < pollIdx && pollIdx < exitedIdx
                        && exitedIdx < timeoutIdx,
                "Stop-BackendTask 顺序必须是：读 token → login → GET status → 非运行直接返回 → "
                        + "POST stop → 轮询 state+workerExited → 超时兜底");
    }

    @Test
    void stopAppOnlyKillsAfterGracefulStopAndStopSwitchUsesIt() throws Exception {
        String script = Files.readString(SCRIPT, StandardCharsets.UTF_8);
        String stopApp = functionBody(script, "function Stop-App", "if ($Status)");

        int graceful = stopApp.indexOf("Stop-BackendTask");
        int taskkill = stopApp.indexOf("taskkill.exe /PID $appPid /T /F");
        assertTrue(graceful > 0, "Stop-App 必须先调用 Stop-BackendTask 优雅停止");
        assertTrue(taskkill > graceful,
                "taskkill 只能在优雅停止（最长 60s 等待 worker 退出）之后兜底");
        assertTrue(stopApp.contains("# 先取消当前任务并等 worker 退出（最多 60s）"),
                "必须保留“先取消任务再结束进程”的说明");

        // -Stop 参数入口走 Stop-App（先任务退出、再结束进程）
        int stopBranch = script.indexOf("if ($Stop)");
        assertTrue(stopBranch > 0, "脚本缺少 -Stop 参数入口");
        String stopEntry = script.substring(stopBranch, stopBranch + 200);
        assertTrue(stopEntry.contains("Stop-App"), "-Stop 入口必须调用 Stop-App");
    }

    @Test
    void scriptHasNoSyntaxErrorsByPowerShellParser() throws Exception {
        String root = System.getenv("SystemRoot");
        assumeTrue(root != null && !root.isBlank(), "非 Windows 环境跳过 PowerShell 语法检查");
        Path powershell = Path.of(root, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        assumeTrue(Files.isRegularFile(powershell), "未找到 Windows PowerShell，跳过语法检查");

        String scriptPath = SCRIPT.toAbsolutePath().toString().replace("'", "''");
        String command = "$errors = $null; $tokens = $null; "
                + "[void][System.Management.Automation.Language.Parser]::ParseFile('" + scriptPath
                + "', [ref]$tokens, [ref]$errors); "
                + "if ($errors -and $errors.Count -gt 0) { "
                + "$errors | ForEach-Object { Write-Output ('PARSE_ERROR: ' + $_.ToString()) }; exit 3 } "
                + "Write-Output 'PS_PARSE_OK'; exit 0";
        Process process = new ProcessBuilder(powershell.toString(),
                "-NoProfile", "-NonInteractive", "-Command", command)
                .redirectErrorStream(true)
                .start();
        String output;
        try (InputStream input = process.getInputStream()) {
            output = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "PowerShell 语法解析超时");
        assertEquals(0, process.exitValue(), "OfferChaser.ps1 存在语法错误：\n" + output);
        assertTrue(output.contains("PS_PARSE_OK"), "语法解析应成功：\n" + output);
    }

    @Test
    void stopScriptVerifiesInstanceIdentityBeforeKilling() throws Exception {
        String script = Files.readString(SCRIPT, StandardCharsets.UTF_8);

        // 1) 身份校验要素：CIM 取进程信息，按可执行文件路径/命令行判断是否属于本安装目录
        assertTrue(script.contains("Get-CimInstance Win32_Process"),
                "必须用 CIM 查询进程信息做实例归属校验");
        assertTrue(script.contains("ExecutablePath"), "身份校验必须检查进程可执行文件路径");
        assertTrue(script.contains("CommandLine"), "身份校验必须检查进程命令行");
        assertTrue(script.contains("function Test-ProcessBelongsToRoot"),
                "必须有统一的“进程是否属于本安装目录”判定函数");

        // 2) Get-AppPid 只返回本实例 PID（不再把 netstat 的任意监听 PID 当成本实例）
        String getAppPid = functionBody(script, "function Get-AppPid", "function Get-ExternalPortOwner");
        assertTrue(getAppPid.contains("Test-ProcessBelongsToRoot"),
                "Get-AppPid 必须校验监听进程属于本安装目录");
        assertTrue(getAppPid.contains("return $null"), "非本实例监听时 Get-AppPid 必须返回空");

        // 3) 外部占用者查询 helper（用于可读提示）
        assertTrue(script.contains("function Get-ExternalPortOwner"),
                "必须提供外部端口占用者查询，用于提示 PID/名称/路径");

        // 4) Stop-App：身份校验 → 优雅停止 → taskkill，外部占用时拒绝停止
        String stopApp = functionBody(script, "function Stop-App", "if ($Status)");
        int identity = stopApp.indexOf("Get-AppPid");
        int graceful = stopApp.indexOf("Stop-BackendTask");
        int taskkill = stopApp.indexOf("taskkill.exe /PID $appPid /T /F");
        assertTrue(identity > 0 && identity < graceful && graceful < taskkill,
                "Stop-App 顺序必须是：身份校验 → 优雅停止 → taskkill");
        assertTrue(stopApp.contains("Get-ExternalPortOwner"), "Stop-App 必须识别外部端口占用");
        assertTrue(stopApp.contains("拒绝停止"), "外部进程占用端口时必须给出拒绝停止提示");

        // 5) Start-App：外部占用时拒绝启动并提示，不打开别人的页面
        String startApp = functionBody(script, "function Start-App", "function Open-Admin");
        assertTrue(startApp.contains("Get-ExternalPortOwner"), "Start-App 必须识别外部端口占用");
        assertTrue(startApp.contains("拒绝启动"), "外部进程占用端口时必须给出拒绝启动提示");
        assertTrue(startApp.contains("MessageBox"), "外部占用时必须弹可读提示");
        assertTrue(startApp.contains("$LauncherOut"), "外部占用分支必须写启动器日志");

        // 6) 删除全盘通配 kill：裸 '*启动.bat*' 不得再出现，只允许本目录形式
        assertFalse(script.contains("'*启动.bat*'"), "不得再使用全盘通配 '*启动.bat*' 杀进程");
        assertTrue(script.contains("'*' + $Root + '\\启动.bat*'"),
                "启动批处理清理必须限定在本安装目录");
        assertTrue(script.contains("function Clear-InstanceLaunchers"),
                "必须把启动器清理收敛到独立 helper（仅本目录）");

        // 7) -Quit 的 powershell kill 限定本目录，只结束本目录托盘
        int quitIdx = script.indexOf("if ($Quit)");
        assertTrue(quitIdx > 0, "缺少 -Quit 托盘退出分支");
        String quitBlock = script.substring(quitIdx, Math.min(script.length(), quitIdx + 600));
        assertTrue(quitBlock.contains("$Root"), "-Quit 托盘清理必须限定 $Root");
        assertTrue(quitBlock.contains("OfferChaser.ps1*"), "-Quit 只允许结束本目录的托盘进程");
        assertTrue(script.contains("'*' + $Root + '\\OfferChaser.ps1*'"),
                "-Quit 的匹配模板必须包含本安装目录");
    }

    // ==================== 工具 ====================

    /** 截取两个标记之间的脚本片段（用于按函数断言顺序，避免跨函数误判）。 */
    private static String functionBody(String script, String startMarker, String endMarker) {
        int start = script.indexOf(startMarker);
        assertTrue(start > 0, "脚本缺少：" + startMarker);
        int end = script.indexOf(endMarker, start);
        assertTrue(end > start, "未找到函数结束标记：" + endMarker);
        return script.substring(start, end);
    }
}
