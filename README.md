# Java Symbol MCP

通过运行中的 IntelliJ IDEA 查询 Java 代码，并启动或重启现有 Run / Debug 配置。

Query Java code and start or restart existing Run / Debug configurations through a running IntelliJ IDEA instance.

[中文](#中文) · [English](#english) · [协作指南 / Agent guide](AGENTS.md)

## 中文

### 概览

当前版本 **0.2.1**，目标为 **IntelliJ IDEA 2025.2（252.*）**，使用 Java 21。插件复用 IDEA 的 Java PSI、项目索引和 Execution API，提供以下 7 个 MCP 工具。

| 工具 | 用途 | 必要参数 |
| --- | --- | --- |
| `list_projects` | 列出打开的项目及索引状态 | 无 |
| `read_symbols` | 读取文件声明或按精确名称查询符号 | `file` / `query` 二选一 |
| `find_definition` | 定位定义，解析具体方法重载 | `file`, `line`, `column` |
| `find_references` | 查找项目中的语义引用 | `file`, `line`, `column` |
| `list_run_configurations` | 查询运行配置、模式和进程 | 无；可用 `name` 过滤 |
| `restart_run_configuration` | 以 Run / Debug 模式启动或重启现有配置 | `configurationId` |
| `get_run_operation` | 查询异步进度、进程及调试器连接情况 | `operationId` |

除 `list_projects` 外，均可传 `project`。多个项目打开时，使用 `list_projects` 返回的完整项目路径；仅打开一个项目时可省略。

### 安装与连接

1. 构建插件，或使用已生成的 [0.2.1 安装包](build/distributions/idea-symbol-mcp-0.2.1.zip)。构建产物不随源码保存。
2. IDEA → **Settings → Plugins → 齿轮 → Install Plugin from Disk**，选择 ZIP，按提示安装或重启。
3. 打开 Java 项目，在 IDEA 主窗口执行 **Tools → Java MCP: Copy Connection Configuration**；也可以连按两次 Shift 搜索此操作。
4. 将复制的配置加入支持 Streamable HTTP 的 MCP 客户端。索引查询应等待 IDEA 完成索引。

通用 JSON 配置示例，地址和令牌以实际复制值为准：

```json
{
  "mcpServers": {
    "idea-java": {
      "type": "http",
      "url": "http://127.0.0.1:64123/mcp",
      "headers": { "Authorization": "Bearer <YOUR_TOKEN>" }
    }
  }
}
```

Codex 用户级 `~/.codex/config.toml` 使用以下格式。合并到现有配置，避免重复添加同名表：

```toml
[mcp_servers.idea-java]
url = "http://127.0.0.1:64123/mcp"
enabled = true
startup_timeout_sec = 20
tool_timeout_sec = 30
http_headers = { Authorization = "Bearer <YOUR_TOKEN>" }
```

添加或升级工具后，重新加载 MCP 连接或重启 Codex，更新工具目录。服务端已升级与当前聊天已发现新工具需要分别确认。

默认端口为 `64123`，被占用时选择空闲端口。可用 IDEA VM option `-Didea.mcp.port=64124` 指定首选端口，或设置 `0` 使用随机端口。端口变化后更新客户端 URL。

### 固定令牌与 IDEA 菜单

首次启动生成随机令牌，并保存到 IDEA PasswordSafe。启用持久密码保存时，IDEA 重启和 MCP 服务重启继续复用该令牌。

| 菜单操作 | 行为 |
| --- | --- |
| **Java MCP: Copy Connection Configuration** | 启动服务，复制当前 URL 和认证配置 |
| **Java MCP: Set Fixed Token** | 保存指定令牌、重启 MCP 服务并复制配置 |
| **Java MCP: Reset Token** | 确认后生成新令牌，旧令牌失效 |
| **Java MCP: Stop Server** | 停止 MCP HTTP 服务，保留已存令牌 |

自定义令牌接受 32–256 位字母、数字、下划线和连字符，输入时去掉 `Bearer ` 前缀。若 Passwords 设置为“重启后忘记密码”，插件会提示存储仅限内存；跨重启复用需要启用密码保存。

从 0.1.0 升级时，可通过 **Set Fixed Token** 保存客户端原令牌，保留认证配置。从 0.1.1 升级会继续读取同一凭据。重置令牌后需更新客户端。不要把真实令牌提交到源码、日志或文档。

### Java 查询

- `file` 支持项目相对路径或绝对路径，限于所选项目内容根中 IDEA 已知的 Java 文件；也支持项目目录之外的模块内容根。
- `query` 是区分大小写的精确短名称（如 `UserService`、`findById`）或完整类名（如 `com.example.UserService`），可能返回多个类或重载。`includeLibraries` 默认 `false`，仅对名称查询有效。
- 行列从 **1** 开始，列按 **UTF-16** 计数；emoji 通常占两列。位置应落在标识符上，输出范围终点 `endLine` / `endColumn` 为右开区间。
- `read_symbols`、`find_references` 支持 `limit`（1–200，默认 100）和 `offset`（0–1,000,000，默认 0），返回 `hasMore` / `nextOffset`。引用可用 `includeDeclaration=true` 包含声明。
- 文件声明按源码顺序返回，名称结果按文件与位置排序。引用先按完整文件位置去重，再按文件 URL、起始偏移、结束偏移稳定排序后分页，返回唯一结果总数 `total`；包含声明时声明始终排在首位，且计入总数。源码或索引改变后应从头查询。方法名查询约有 10,000 个候选收集上限，结果用 `candidateLimitReached` 提示。
- 结果包含名称、类型、签名、修饰符、位置、导航位置和最多 500 个 UTF-16 字符的所在行片段。依赖没有源码时，位置可能仅有反编译路径或 URL。
- 读取当前已提交到 PSI 的编辑内容，不要求保存到磁盘。`DOCUMENTS_UNCOMMITTED`、`INDEXING` 可在 IDEA 稳定后重试。

以下 JSON 是 `tools/call` 的 `params`。先查询项目，再替换示例路径：

```json
{"name":"list_projects","arguments":{}}
```

```json
{"name":"read_symbols","arguments":{"project":"D:/work/demo","file":"src/main/java/demo/Sample.java"}}
```

```json
{"name":"read_symbols","arguments":{"project":"D:/work/demo","query":"demo.Sample"}}
```

```json
{"name":"find_definition","arguments":{"project":"D:/work/demo","file":"src/main/java/demo/Sample.java","line":9,"column":16}}
```

```json
{"name":"find_references","arguments":{"project":"D:/work/demo","file":"src/main/java/demo/Sample.java","line":9,"column":16,"includeDeclaration":true}}
```

将 [Sample.java](examples/java/demo/Sample.java) 放入 Java 源码根后，第 9 行第 16 列对应其 `add` 调用，可复现位置查询。

### Run / Debug 启动与重启

先查询配置，使用返回的精确 ID。`name` 是可选的不区分大小写的名称片段。

```json
{"name":"list_run_configurations","arguments":{"project":"D:/work/demo","name":"Application"}}
```

```json
{"name":"restart_run_configuration","arguments":{"project":"D:/work/demo","configurationId":"<EXACT_ID_FROM_LIST>","mode":"debug","stopTimeoutSeconds":20,"startTimeoutSeconds":120}}
```

```json
{"name":"get_run_operation","arguments":{"project":"D:/work/demo","operationId":"<RETURNED_OPERATION_ID>"}}
```

- 支持本地 **Application、Spring Boot、Maven、Gradle**，以 `supported`、`canRun`、`canDebug` 为准。Maven / Gradle 调试目标取决于原配置。不支持 Compound、测试配置、远程目标及远程 Attach。
- `mode` 可选 `run`、`debug`、`preserve`（默认）。`preserve` 沿用当前模式；未运行时使用 Run。实例模式混合或未知时需要明确指定模式。
- 先验证配置，仅停止选中项目中同一配置的已有实例，等待退出后才启动替代实例；未运行时直接启动。复用原 SDK、目录、参数、环境和 Before launch 任务。
- 配置应在 IDEA 中事先设置完整，并关闭 **Edit configuration before launch**。列表不暴露 VM 参数和环境变量。
- 操作异步返回 ID，常见阶段为 `queued → checking → stopping → building / starting → started`，也可能为 `failed` 或 `terminated`。
- 停止超时默认 20 秒（1–120）：`STOP_TIMEOUT` 不启动替代实例，也不自动追加强制终止。
- 启动超时默认 120 秒（1–600）：`start_timeout` 保持 `inProgress=true`，因为可能仍在构建或启动。继续查询同一操作，避免直接重试。
- 同一配置已有操作时，相同模式或 `preserve` 复用该操作；不同模式返回 `OPERATION_IN_PROGRESS`，错误中提示现有操作 ID。
- 同时检查 `phase`、`error`、`process.status` 和 `inProgress`。异步失败不一定表现为 MCP 响应 `isError=true`。
- **`started` 仅表示进程启动，不代表 Spring Boot 就绪或业务健康。** Debug 连接以 `debuggerAttached=true` 为准。退出时可查看 `exitCode`；日志仍在 IDEA 的 Run / Debug / Build 窗口。
- 项目内存最多保留最近 100 个操作，关闭项目或 IDEA 后失效。重建 MCP HTTP 连接不会主动撤销已提交操作。

在 Codex 中可说“列出这个项目的运行配置”“以 Debug 模式重启某某服务”“把指定的正在运行的服务以 Run 模式重启”。重启会改变服务状态，应针对用户明确请求的配置执行。

### 构建与测试

需要包含 javac 的完整 **JDK 21**。PowerShell 本机路径示例：

```powershell
$env:IDEA_HOME = 'D:\Program Files\JetBrains\IntelliJ IDEA 2025.2'
# JAVA_HOME 应指向完整 JDK 21
.\gradlew.bat "-PlocalIdePath=$env:IDEA_HOME" test buildPlugin verifyPluginStructure
.\gradlew.bat "-PlocalIdePath=$env:IDEA_HOME" runIde
```

Wrapper 固定 Gradle 9.0.0 和发行包 SHA-256，IntelliJ Platform Gradle Plugin 为 2.18.1。首次构建会下载 Gradle 和依赖，不传 `localIdePath` 时下载 IDEA 2025.2。macOS / Linux 使用 `sh ./gradlew`；本地 PowerShell 打包脚本面向 Windows。

使用本机 IDEA JAR 离线编译打包，无需下载 Gradle 或 SDK：

```powershell
.\scripts\build-local.ps1 -IdeaHome $env:IDEA_HOME -JdkHome $env:JAVA_HOME
.\scripts\build-local.ps1 -IdeaHome $env:IDEA_HOME -JdkHome $env:JAVA_HOME -Test
.\scripts\build-local.ps1 -IdeaHome $env:IDEA_HOME -JdkHome $env:JAVA_HOME -PsiTest
```

`-Test` 做协议 / HTTP 自检；`-PsiTest` 额外运行 PSI、令牌及 Run / Debug 集成测试，需要 JUnit 4.13.2、Hamcrest Core 1.3、OpenTest4j 1.3.0。默认读取用户 Maven 缓存，可用 `-JUnitJar`、`-HamcrestJar`、`-OpenTest4jJar` 指定。未传 `-JdkHome` 时优先使用 `JAVA_HOME`，再尝试 IDEA `jbr`；选中的运行时必须包含所需 JDK 工具。

Gradle 及依赖已缓存时，可使用离线构建；也可单独运行测试类：

```powershell
.\gradlew.bat --gradle-user-home .gradle-user-home "-PlocalIdePath=$env:IDEA_HOME" test buildPlugin verifyPluginStructure --offline
.\gradlew.bat "-PlocalIdePath=$env:IDEA_HOME" test --tests dev.ideamcp.RunControlServiceTest
```

产物：`build/distributions/idea-symbol-mcp-<version>.zip`；测试报告：`build/reports/tests/test/index.html`。测试使用独立 IDEA 沙盒；运行控制测试启动并清理自己的临时 JVM。Windows 测试需要允许加载 IDEA 原生进程管理库。

最近一次 0.2.1 回归通过 **26 项 JUnit 测试**，协议自检含 **47 项断言**，并通过插件结构校验。覆盖真实 JVM 的 Run / Debug 切换、保留模式重启、超时、执行事件隔离，以及 481 处引用在多种页大小和请求顺序下的无重复、无遗漏分页。

对已安装插件做只读连接自检：

```powershell
$env:IDEA_MCP_TOKEN = '<YOUR_TOKEN_WITHOUT_BEARER_PREFIX>'
.\scripts\smoke-mcp.ps1 -Url 'http://127.0.0.1:64123/mcp'
.\scripts\smoke-mcp.ps1 -Project 'D:/work/demo' -File 'src/main/java/demo/Sample.java' -Line 9 -Column 16
```

### 结构、边界与常见问题

| 组件 | 职责 |
| --- | --- |
| `IdeaMcpStartup` / `IdeaMcpService` | 后台启动、应用级 HTTP 生命周期、连接配置 |
| `IdeaToolBackend` | 路由查询与 EDT 运行控制，提交 / 查询等待上限 5 秒 |
| `JavaCodeTools` | 后台非阻塞读操作中的 PSI 查询，等待上限 20 秒 |
| `RunControlService` | 项目级状态机、进程终止和调试器跟踪 |
| `TokenStore` / `*Action` | PasswordSafe 和 IDEA 菜单 |
| `protocol/` | schema、参数校验、JSON-RPC 与 HTTP 认证边界 |

仅监听回环地址，验证 Host / Origin 并要求 Bearer 认证。协议为 `2025-03-26`、`2025-06-18`、`2025-11-25` 的无会话 Streamable HTTP 子集，提供 `initialize`、初始化通知、`ping`、`tools/list`、`tools/call`。响应使用 JSON；GET / DELETE 返回 405；没有 SSE 推送、stdio、资源或 MCP 协议级任务接口。重启操作 ID 是工具的应用级约定。

POST 要求 `Content-Type: application/json` 和 `Accept: application/json, text/event-stream`，请求体上限 1 MiB。目前仅验证本地 IDEA 2025.2，其他版本、Remote Development 和分离前后端尚未验证。

| 问题 | 处理 |
| --- | --- |
| HTTP 401 | 核对令牌，复制当前配置或保存客户端原令牌为固定值 |
| 连接失败或端口改变 | 打开 IDEA 项目，重新复制并核对实际 URL |
| 客户端仍只有旧工具 | 重新加载连接或重启客户端，确认 `tools/list` 为 7 项 |
| `INDEXING` / `DOCUMENTS_UNCOMMITTED` | 等待索引或编辑内容提交后重试 |
| 项目或配置 ID 不匹配 | 重新列出项目和配置，使用完整路径和精确 ID |
| `IDE_BUSY` | 等待 UI 恢复，先查列表中的 `activeOperationId`，避免盲目重启 |
| `EDIT_BEFORE_RUN` / 校验失败 | 在 IDEA 中补齐配置并关闭启动前编辑 |
| `STOP_TIMEOUT` | 检查旧进程；插件不会自动强杀或启动替代实例 |
| `start_timeout` | 继续查询原操作并检查 IDEA Build / Run 窗口 |

开发协作规则见 [AGENTS.md](AGENTS.md)。

## English

### Overview

Version **0.2.1** targets **IntelliJ IDEA 2025.2 (252.*)** and uses Java 21. The plugin reuses IDEA's Java PSI, project indexes and Execution API to expose seven MCP tools.

| Tool | Purpose | Required arguments |
| --- | --- | --- |
| `list_projects` | List open projects and indexing state | None |
| `read_symbols` | Read declarations or search exact symbol names | Exactly one of `file` / `query` |
| `find_definition` | Locate declarations and resolve concrete overloads | `file`, `line`, `column` |
| `find_references` | Find semantic references within the project | `file`, `line`, `column` |
| `list_run_configurations` | Inspect configurations, modes and processes | None; optional `name` filter |
| `restart_run_configuration` | Start or restart an existing Run / Debug configuration | `configurationId` |
| `get_run_operation` | Inspect asynchronous progress and debugger attachment | `operationId` |

Every tool except `list_projects` accepts `project`. With multiple projects open, use the exact path returned by `list_projects`; omit it only when one project is open.

### Install and connect

1. Build the plugin or use the generated [0.2.1 distribution](build/distributions/idea-symbol-mcp-0.2.1.zip). Build artifacts are not included with the source.
2. Select **Settings → Plugins → gear → Install Plugin from Disk** in IDEA, choose the ZIP and follow any restart prompt.
3. Open a Java project and run **Tools → Java MCP: Copy Connection Configuration** in the main window. Alternatively, press Shift twice and search for the action.
4. Add the copied configuration to a Streamable HTTP MCP client. Wait for indexing before index-dependent queries.

Generic JSON example; use the actual copied URL and token:

```json
{
  "mcpServers": {
    "idea-java": {
      "type": "http",
      "url": "http://127.0.0.1:64123/mcp",
      "headers": { "Authorization": "Bearer <YOUR_TOKEN>" }
    }
  }
}
```

For Codex, merge this TOML into user-level `~/.codex/config.toml`, preserving existing settings and avoiding duplicate tables:

```toml
[mcp_servers.idea-java]
url = "http://127.0.0.1:64123/mcp"
enabled = true
startup_timeout_sec = 20
tool_timeout_sec = 30
http_headers = { Authorization = "Bearer <YOUR_TOKEN>" }
```

Reload the MCP connection or restart Codex after adding or upgrading tools. Verify the server version and the current chat's discovered tool inventory separately.

The default port is `64123`, with an available-port fallback. Set IDEA VM option `-Didea.mcp.port=64124` for a preferred port, or `0` for a random port. Update the client URL when the port changes.

### Persistent tokens and IDEA actions

A random token is generated on first start and saved in IDEA PasswordSafe. With persistent password storage enabled, IDE and MCP service restarts reuse that token.

| Menu action | Behavior |
| --- | --- |
| **Java MCP: Copy Connection Configuration** | Start the server and copy its URL and authentication settings |
| **Java MCP: Set Fixed Token** | Save a supplied token, restart MCP and copy its configuration |
| **Java MCP: Reset Token** | Generate a replacement after confirmation, invalidating the old token |
| **Java MCP: Stop Server** | Stop MCP HTTP while retaining the saved token |

Custom tokens contain 32–256 letters, digits, underscores or hyphens, without the `Bearer ` prefix. If IDEA is configured to forget passwords after restart, the plugin reports memory-only storage. Enable password persistence to retain the token across restarts.

When upgrading from 0.1.0, save the existing client token with **Set Fixed Token** to keep its authentication settings. Upgrades from 0.1.1 reuse the same credential entry. Update clients after a reset. Never commit real tokens to source, logs or documentation.

### Java queries

- `file` accepts project-relative or absolute paths to Java files known to IDEA within the selected project's content roots, including module roots outside the project directory.
- `query` is an exact, case-sensitive short name such as `UserService` or `findById`, or a fully qualified class name such as `com.example.UserService`. Multiple classes or overloads can match. `includeLibraries` defaults to `false` and applies only to name queries.
- Positions are **1-based**, with **UTF-16** columns; an emoji typically occupies two columns. Point at an identifier. Returned `endLine` / `endColumn` positions are exclusive.
- `read_symbols` and `find_references` accept `limit` (1–200, default 100) and `offset` (0–1,000,000, default 0), returning `hasMore` / `nextOffset`. References can include declarations with `includeDeclaration=true`.
- File results follow source order; name results are sorted by file and position. References are deduplicated by complete source range, sorted by file URL and numeric start/end offsets, then paginated, with a unique-result `total`. A requested declaration stays first and counts toward that total. Restart pagination after source or index changes. Method-name collection has an approximately 10,000-candidate cap, reported by `candidateLimitReached`.
- Results include names, kinds, signatures, modifiers, locations, navigation locations and up to 500 UTF-16 units from the containing line. Dependencies without source may provide only decompiled paths or URLs.
- Queries use current editor content committed to PSI without requiring a disk save. Retry `DOCUMENTS_UNCOMMITTED` or `INDEXING` after IDEA settles.

These JSON objects are `tools/call` parameters. List projects first and replace the example paths:

```json
{"name":"list_projects","arguments":{}}
```

```json
{"name":"read_symbols","arguments":{"project":"D:/work/demo","file":"src/main/java/demo/Sample.java"}}
```

```json
{"name":"read_symbols","arguments":{"project":"D:/work/demo","query":"demo.Sample"}}
```

```json
{"name":"find_definition","arguments":{"project":"D:/work/demo","file":"src/main/java/demo/Sample.java","line":9,"column":16}}
```

```json
{"name":"find_references","arguments":{"project":"D:/work/demo","file":"src/main/java/demo/Sample.java","line":9,"column":16,"includeDeclaration":true}}
```

Copy [Sample.java](examples/java/demo/Sample.java) into a Java source root. Line 9, column 16 points to its `add` call and reproduces the position-based examples.

### Start and restart in Run / Debug mode

List configurations first, then use an exact returned ID. Optional `name` is a case-insensitive substring filter.

```json
{"name":"list_run_configurations","arguments":{"project":"D:/work/demo","name":"Application"}}
```

```json
{"name":"restart_run_configuration","arguments":{"project":"D:/work/demo","configurationId":"<EXACT_ID_FROM_LIST>","mode":"debug","stopTimeoutSeconds":20,"startTimeoutSeconds":120}}
```

```json
{"name":"get_run_operation","arguments":{"project":"D:/work/demo","operationId":"<RETURNED_OPERATION_ID>"}}
```

- Supports local **Application, Spring Boot, Maven and Gradle** configurations, subject to `supported`, `canRun` and `canDebug`. Maven / Gradle debug targets depend on the existing configuration. Compound configurations, tests, remote targets and remote Attach are excluded.
- `mode` accepts `run`, `debug` or `preserve` (default). Preserve keeps the active mode and defaults to Run if stopped. Mixed or unknown modes require an explicit choice.
- Validation precedes stopping. Only instances of the selected configuration in the selected project are stopped; replacement waits for termination. A stopped configuration starts directly. Existing SDK, directory, arguments, environment and Before launch tasks are reused.
- Configure the target in IDEA beforehand and disable **Edit configuration before launch**. Listings do not expose VM arguments or environment values.
- Restart returns an asynchronous ID. Typical phases are `queued → checking → stopping → building / starting → started`, with `failed` and `terminated` also possible.
- Stop timeout defaults to 20 seconds (1–120). `STOP_TIMEOUT` prevents replacement startup and does not trigger automatic force-killing.
- Start timeout defaults to 120 seconds (1–600). `start_timeout` retains `inProgress=true` because IDEA may still be building or starting. Keep polling the same operation instead of blindly retrying.
- An active operation is reused for the same mode or `preserve`. A different mode returns `OPERATION_IN_PROGRESS`, including the existing ID in its error.
- Inspect `phase`, `error`, `process.status` and `inProgress` together. Asynchronous failures do not necessarily return MCP `isError=true`.
- **`started` means process start, not Spring Boot readiness or application health.** Confirm Debug attachment with `debuggerAttached=true`. Inspect `exitCode` after exit; logs remain in IDEA's Run / Debug / Build windows.
- Project memory retains the last 100 operations. History expires when the project or IDE closes. Reconnecting MCP HTTP does not actively cancel submitted operations.

Example Codex requests: “list this project's run configurations,” “restart this service in Debug mode,” or “restart these specified running services in Run mode.” Restart changes service state and should target configurations explicitly requested by the user.

### Build and test

Use a complete **JDK 21**, including javac. Example local PowerShell setup:

```powershell
$env:IDEA_HOME = 'D:\Program Files\JetBrains\IntelliJ IDEA 2025.2'
# JAVA_HOME must point to a complete JDK 21
.\gradlew.bat "-PlocalIdePath=$env:IDEA_HOME" test buildPlugin verifyPluginStructure
.\gradlew.bat "-PlocalIdePath=$env:IDEA_HOME" runIde
```

The Wrapper pins Gradle 9.0.0 and its distribution SHA-256; the IntelliJ Platform Gradle Plugin is 2.18.1. Initial builds download Gradle and dependencies. Omitting `localIdePath` downloads IDEA 2025.2. On macOS / Linux use `sh ./gradlew`; the local PowerShell packaging script targets Windows.

Compile against local IDEA JARs without downloading Gradle or an SDK:

```powershell
.\scripts\build-local.ps1 -IdeaHome $env:IDEA_HOME -JdkHome $env:JAVA_HOME
.\scripts\build-local.ps1 -IdeaHome $env:IDEA_HOME -JdkHome $env:JAVA_HOME -Test
.\scripts\build-local.ps1 -IdeaHome $env:IDEA_HOME -JdkHome $env:JAVA_HOME -PsiTest
```

`-Test` runs protocol / HTTP checks. `-PsiTest` adds PSI, token and Run / Debug integration tests, requiring JUnit 4.13.2, Hamcrest Core 1.3 and OpenTest4j 1.3.0. JARs default to the user's Maven cache; override with `-JUnitJar`, `-HamcrestJar` and `-OpenTest4jJar`. Without `-JdkHome`, the script tries `JAVA_HOME`, then IDEA's `jbr`; the selected runtime must contain the required JDK tools.

Use offline Gradle only after Gradle and dependencies are cached, or run a specific test class:

```powershell
.\gradlew.bat --gradle-user-home .gradle-user-home "-PlocalIdePath=$env:IDEA_HOME" test buildPlugin verifyPluginStructure --offline
.\gradlew.bat "-PlocalIdePath=$env:IDEA_HOME" test --tests dev.ideamcp.RunControlServiceTest
```

Distribution: `build/distributions/idea-symbol-mcp-<version>.zip`. Test report: `build/reports/tests/test/index.html`. Tests use isolated IDEA sandboxes; execution tests start and clean up their own disposable JVMs. Windows testing requires access to IDEA's native process-management libraries.

The latest 0.2.1 regression passed **26 JUnit tests**, with **47 protocol assertions** and plugin structure verification. Coverage includes real JVM Run / Debug transitions, mode-preserving restart, timeouts, execution-event isolation, and complete duplicate-free pagination of 481 usages across different page sizes and request orders.

Run read-only connectivity checks against an installed plugin:

```powershell
$env:IDEA_MCP_TOKEN = '<YOUR_TOKEN_WITHOUT_BEARER_PREFIX>'
.\scripts\smoke-mcp.ps1 -Url 'http://127.0.0.1:64123/mcp'
.\scripts\smoke-mcp.ps1 -Project 'D:/work/demo' -File 'src/main/java/demo/Sample.java' -Line 9 -Column 16
```

### Architecture, limitations and troubleshooting

| Component | Responsibility |
| --- | --- |
| `IdeaMcpStartup` / `IdeaMcpService` | Background startup, application-level HTTP lifecycle, connection settings |
| `IdeaToolBackend` | Query routing and EDT execution requests; five-second submission / query wait |
| `JavaCodeTools` | Background non-blocking PSI reads; 20-second wait limit |
| `RunControlService` | Project-scoped state machine, process termination and debugger tracking |
| `TokenStore` / `*Action` | PasswordSafe credentials and IDEA menu actions |
| `protocol/` | Schemas, validation, JSON-RPC and HTTP authentication boundaries |

The listener is loopback-only, validates Host / Origin and requires Bearer authentication. It implements a stateless Streamable HTTP subset for `2025-03-26`, `2025-06-18` and `2025-11-25`: `initialize`, initialization notifications, `ping`, `tools/list` and `tools/call`. Responses use JSON; GET / DELETE return 405. There is no SSE push, stdio, resource API or protocol-level MCP task API. Restart IDs are an application-level tool convention.

POST requires `Content-Type: application/json` and `Accept: application/json, text/event-stream`, with a 1 MiB body limit. Only local IDEA 2025.2 is verified; other releases, Remote Development and split frontend / backend setups remain unverified.

| Symptom | Action |
| --- | --- |
| HTTP 401 | Check the token; copy current settings or save the client's existing token as a fixed value |
| Connection failure or changed port | Open an IDEA project and copy the actual URL again |
| Client exposes only older tools | Reload or restart the client; confirm `tools/list` returns seven tools |
| `INDEXING` / `DOCUMENTS_UNCOMMITTED` | Retry after indexing or editor-to-PSI commits finish |
| Project or configuration ID mismatch | List projects and configurations again, using exact paths and IDs |
| `IDE_BUSY` | Wait for the UI and inspect `activeOperationId` before retrying a restart |
| `EDIT_BEFORE_RUN` / validation failure | Complete the configuration and disable editing before launch |
| `STOP_TIMEOUT` | Inspect the old process; no automatic force-kill or replacement is attempted |
| `start_timeout` | Continue polling the same operation and inspect IDEA's Build / Run windows |

See [AGENTS.md](AGENTS.md) for development guidance.

## 参考资料 / References

- [JetBrains PSI Cookbook](https://plugins.jetbrains.com/docs/intellij/psi-cookbook.html)
- [JetBrains threading model](https://plugins.jetbrains.com/docs/intellij/threading-model.html)
- [JetBrains credential persistence](https://plugins.jetbrains.com/docs/intellij/persisting-sensitive-data.html)
- [JetBrains Run Configurations](https://plugins.jetbrains.com/docs/intellij/run-configurations.html)
- [IntelliJ Platform Gradle Plugin](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html)
- [MCP Streamable HTTP](https://modelcontextprotocol.io/specification/2025-03-26/basic/transports)
- [Codex MCP configuration](https://developers.openai.com/codex/mcp)

## 欢迎协作 / Contributions welcome

欢迎大家一同参与 Java Symbol MCP！无论是反馈问题、改进中英文文档、补充测试，还是贡献代码与新功能，都非常欢迎。分享改动时，请说明使用场景、目标 IDEA 版本和验证结果；涉及运行控制的改动，请使用独立沙盒和临时进程验证。让我们一起把它做成更可靠、更好用的 Java 开发工具。

Everyone is welcome to collaborate on Java Symbol MCP! Bug reports, Chinese and English documentation improvements, tests, code contributions and new features are all welcome. When sharing changes, describe the use case, target IDEA version and validation results. Test execution-control changes with isolated sandboxes and disposable processes. Let's build a more reliable and useful tool for Java development together.
