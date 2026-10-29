# 项目协作说明

适用于当前项目及其全部子目录。面向在此开发、修复或维护文档的编码代理；用户在当前任务中的明确要求优先。

## 项目与技术栈

- Java Symbol MCP 是 IntelliJ IDEA 插件，复用正在运行的 IDE 的 Java PSI、项目索引和 Execution API。
- 当前版本 `0.6.11`；目标为 IDEA `2026.2.3` / `262.10968.63`～`262.*`，Java 21 语言级别和字节码，使用 JDK 25 编译和测试。
- 使用 Gradle 9.1.0 Wrapper、IntelliJ Platform Gradle Plugin 2.18.1、JUnit 4。
- 插件 ID：`dev.ideamcp.symbols`；显示名称：`Java Symbol MCP`。
- 本机 MCP 地址默认为 `http://127.0.0.1:64123/mcp`，使用 Bearer 认证。
- 原生 21 个工具的名称、schema、默认值以 [ToolCatalog.java](src/main/java/dev/ideamcp/protocol/ToolCatalog.java) 为准；可选官方入口来自当前 IDE 的真实提供方及筛选设置，完整基线为 56 个，合计最多 77 个。设计见 [能力对齐与自动连接](docs/design/jetbrains-parity-and-auto-connect.md)，使用说明见 [README.md](README.md)。

## 代码地图

| 文件或目录 | 职责 |
| --- | --- |
| `IdeaMcpStartup.java` / `IdeaMcpService.java` | 后台启动、应用级 HTTP 生命周期、连接配置与令牌更新 |
| `IdeaToolBackend.java` | 路由代码查询与 EDT 运行控制请求 |
| `OfficialMcpAdapter.java` / `PlatformToolProvider.java` | 可选官方 MCP 提供方的目录、筛选与协程调用；不绕过禁用或确认设置 |
| `FileEnumeration.java` | 明确目录下完整文件名枚举；复用官方匹配规则，复杂范围/截断/超预算回退 |
| `DirectoryTree.java` | 宽根完整磁盘目录树；有界名称形状与缓存负向资格判断，完整 NIO 属性与官方渲染，小树/目录密集/链接/变化/超预算回退 |
| `GitStatusBatch.java` | 2～4 个项目内独立 Git 根的有界并行官方查询；共享预算、快照校验、复杂范围回退 |
| `protocol/QueryTiming.java` / `protocol/HttpTimingEvent.java` | 默认关闭的请求内计时；认证响应头与可选 JFR 完成事件按数值序号关联，不存参数或输出 |
| `PlatformOperationService.java` | 官方长操作的异步提交、最终 MCP 结果和取消请求 |
| `EditorWriteBoundary.java` / `EditorSaveVetoer.java` | 按调用上下文跟踪编辑、限制提供方保存范围、后台落盘与版本/磁盘核验；不保存无关文档或覆盖冲突 |
| `ClientConfiguration.java` / `AutoClientService.java` | 客户端格式、原子合并、所有权摘要与后台连接配置维护 |
| `protocol/StdioBridge.java` | Claude Desktop 所需的独立 stdio→认证 HTTP 桥接 |
| `JavaCodeTools.java` | Java 符号、定义、引用、项目选择和位置转换 |
| `JavaCallTools.java` / `DiagnosticsTools.java` | 单层静态调用位置、精确重载和后台单文件错误/警告分析 |
| `RunControlService.java` | 项目级运行配置查询、异步重启状态机和执行事件跟踪 |
| `RunLogService.java` / `RunLogBuffer.java` | 项目内执行事件、实例隔离、有界 stdout/stderr 缓冲与游标 |
| `DebugService.java` | EDT 调试会话与断点协调、调试器管理线程上的 JDI 现场读取、暂停令牌和单步控制 |
| `TokenStore.java` / `*Action.java` | PasswordSafe 凭据与 IDEA 菜单操作 |
| `src/main/java/dev/ideamcp/protocol/` | HTTP、JSON-RPC、参数校验、工具 schema 与响应 |
| `src/main/resources/META-INF/plugin.xml` | 插件声明、兼容范围、启动扩展和菜单注册 |
| `src/test/java/dev/ideamcp/` | 协议、Java PSI、令牌及 Run / Debug 测试 |
| `scripts/` | 本地编译打包和已安装服务的只读连接自检 |
| `examples/java/demo/Sample.java` | 可复现的位置查询示例 |

表中未标目录的 Java 文件位于 `src/main/java/dev/ideamcp/`。

## 实现约束

### Java 分析和线程

- 用 PSI 和 IDEA 索引提供语义结果；符号解析和引用查找不能退化为正则或全文搜索。
- 保持后台非阻塞读操作、取消机制、写操作优先和当前 20 秒查询等待上限。
- 读取已提交到 PSI 的编辑内容，不强制保存用户文件；索引中和文档未提交时返回可重试错误。
- 公共位置是从 1 开始的行号和 UTF-16 列号，范围终点右开。覆盖空行、越界和 emoji。
- 文件查询限于所选项目的内容根；项目目录外的模块内容根仍有效。
- 调用关系为静态单层解析；不要将 Lambda、方法引用或接口声明描述为已执行或运行时实现。诊断使用 IDEA 检查配置，不触发编译、构建或快速修复；源码变化时拒绝混合结果。
- RunManager、Execution API 和运行状态转换遵循 EDT 调度。HTTP 线程只等待短期提交或查询结果，不等待整个构建过程。
- PasswordSafe 在后台访问。生命周期锁不能覆盖可能阻塞或弹窗的凭据操作，避免关闭 IDEA 或卸载插件时死锁。

### MCP 和凭据

- schema、运行时校验和实现保持一致；拒绝未知参数、错误类型和越界值。
- 区分 JSON-RPC 错误、MCP 工具错误和异步操作失败；异步任务还要检查 `phase` / `error`。
- 保持回环地址绑定、Host / Origin 校验、Bearer 认证、严格 JSON 和 1 MiB 请求体限制。
- 当前支持 `2025-03-26`、`2025-06-18`、`2025-11-25` 协议的无会话 JSON 响应子集；GET / DELETE 返回 405。协议扩展需同步测试和文档。
- 令牌保存在 PasswordSafe。禁止把真实令牌、认证头、用户环境变量或完整启动参数写入源码、日志、测试夹具和文档。
- 保持现有凭据服务名称；若任务涉及变更，提供迁移方案，避免丢失已有固定令牌。
- 保存失败或已有令牌损坏时，不静默切换临时令牌；内存模式不能宣称可跨 IDE 重启持久化。
- 重启工具的元数据保持 `readOnlyHint=false`、`destructiveHint=true`、`idempotentHint=false`。

### Run / Debug 控制

- 真实业务服务仅在用户要求启动、重启或切换模式时操作。配置、状态和代码查询属于只读工作。
- 先列出项目和运行配置，使用返回的精确配置 ID；多项目时指定完整路径。
- 通过 IDEA 既有运行配置和执行器操作，保留参数、环境和 Before launch 任务。不要改用自行拼接的 shell 命令或按进程名批量终止。
- 目标范围仅限选中项目和选中配置的实例；检查配置类型及 `canRun` / `canDebug`。
- 先验证配置，再停止旧实例；确认进程退出后才启动替代实例。原生进程可用时也要检查其退出状态。
- 停止超时不启动替代实例，不自动升级为强制杀进程。
- 返回操作 ID 后查询同一操作。启动超时可能仍在构建，应保留进行中状态，不能直接重试重启。
- 保持同一配置的并发隔离、精确执行事件匹配及配置删除、重命名后的处理。
- Debug 连接成功以 `debuggerAttached=true` 为准，不能仅依靠模式或 `started`。
- 进程启动成功不能描述为 Spring Boot 已就绪或业务健康检查通过。
- 插件卸载和状态跟踪清理不额外终止业务进程。
- 日志按运行实例隔离并限制缓冲容量；只捕获监听以后产生的 stdout/stderr，排除系统/启动命令行输出，不回填旧控制台或读取磁盘日志。不要在测试报告中记录真实业务日志。
- 调试控制和断点变更须有用户明确要求，使用所选项目的精确 sessionId / breakpointId。断点在项目内共享，移除或设置时不要影响其他断点。控制命令返回 accepted 仅表示已提交，后续查询会话状态。
- 栈和变量必须带当前 pauseId；继续、单步、退出或再次暂停后拒绝旧令牌。JDI 读取安排在 IDEA 调试器管理线程，禁止在 EDT 等待 VM 响应。
- 局部变量只读取 JDI 原始值，不求值、不调用 getter / toString、不写变量、不展开对象。不要将真实调试变量或业务快照写入测试报告。服务卸载不恢复或停止调试进程，也不删除用户断点。

### 源码编辑完成边界

- 四个官方源码编辑工具（创建、补丁、格式化、重构）先返回操作 ID，查询同一个操作直到终态；检查最终 MCP 错误及 `editorWrite.status`，不能把提交、提示期限或取消请求当成已保存。
- 编辑操作和潜在编辑的通用路由不使用 20 秒中途取消；只读查询仍保持现有上限。`deadlineExceeded` 仅提示等待较长；不要重提同一编辑。已开始的编辑取消须等当前提供方、保存与落盘核验结束，取消不代表回滚。
- 只跟踪本次上下文中的文档/VFS 事件，保存仍由本次操作持有版本的文档。禁止全局保存、强制加载磁盘、抑制冲突后覆盖一方。显式目标已有未保存内容或磁盘冲突时拒绝；并发编辑、保存被否决或核验失败必须显式返回未完成。
- 文件落盘等待和物理内容核验在后台进行，不持有写锁等待磁盘；保留编码/BOM/换行。仅存跟踪元数据，不记录源码内容；不把外部进程写盘纳入无证据的完成保证。
- 反射延迟 VFS 调度、暂停格式化回调等故障注入仅用于独立测试 JVM，必须在 finally 恢复；不得在真实 IDEA 或业务项目注入。

## 构建和验证

从项目根目录运行 PowerShell。`IDEA_HOME` 指向 IDEA 2026.2.3，`JAVA_HOME` 指向包含 javac 的完整 JDK 25（可用 IDEA 自带的 `jbr`）。262 平台 API 使用 Java 25 类文件；插件仍以 `--release 21` 编译。路径有空格时保持引号。

```powershell
# 标准构建、测试与结构校验
.\gradlew.bat "-PlocalIdePath=$env:IDEA_HOME" test buildPlugin verifyPluginStructure

# Gradle 和依赖均已缓存时可离线运行
.\gradlew.bat --gradle-user-home .gradle-user-home "-PlocalIdePath=$env:IDEA_HOME" test buildPlugin verifyPluginStructure --offline

# 使用本机 IDEA JAR 编译打包，并进行协议 / HTTP 自检
.\scripts\build-local.ps1 -IdeaHome $env:IDEA_HOME -JdkHome $env:JAVA_HOME -Test

# 独立调试沙盒
.\gradlew.bat "-PlocalIdePath=$env:IDEA_HOME" runIde

# 单个测试类
.\gradlew.bat "-PlocalIdePath=$env:IDEA_HOME" test --tests dev.ideamcp.RunControlServiceTest
```

按变更选择检查：

| 变更 | 重点验证 |
| --- | --- |
| 协议、schema、认证、HTTP | `ProtocolTest` / `ProtocolSelfTest`：非法输入、请求边界、工具元数据 |
| Java 符号与位置 | `JavaCodeToolsTest` / `ReferencePaginationTest`：重载、语义引用、UTF-16、未保存内容和跨页完整性 |
| 调用链与诊断 | `JavaCallToolsTest` / `DiagnosticsToolsTest`：重载、构造器、Lambda、方法引用、调用分页、错误/警告及未保存修复 |
| 日志 | `RunLogBufferTest` / `RunLogServiceTest`：游标、截断、UTF-16、标准输出类型、实例隔离、淘汰和监听清理 |
| 令牌和生命周期 | `TokenPersistenceTest`：重建复用、令牌失效、保存失败、并发退出 |
| 客户端自动配置 | `ClientConfigurationTest` / `CodexInlineHeadersTest` / `ClientOwnershipTest` / `AutoClientServiceTest`：内联/独立请求头、Unicode、原文保留、幂等性、语义所有权、旧摘要迁移、显式重新接管和临时配置中的后台轮换 |
| 编辑保存与落盘 | `EditorPersistenceTest` / `PlatformOperationServiceTest`：真实提供方、延迟写盘、超过旧 20 秒限制、取消/卸载、路由、编码/BOM、无关未保存文档、并发/磁盘冲突与物理路径一致性 |
| 运行控制 | `RunControlServiceTest`：真实临时 JVM 的 Run / Debug、超时和事件隔离 |
| 调试现场与断点 | `DebugServiceTest` / `DebugValueTest`：两台临时 JVM 的会话隔离、暂停令牌、线程/栈/局部变量、三种单步、继续/暂停、断点增删和无求值渲染 |
| 发布包和兼容范围 | `buildPlugin verifyPluginStructure`，按需要补充兼容验证 |
| 仅文档 | 核对工具、版本、命令、默认值和链接；不必重跑完整集成测试或启动业务服务 |
| 文件枚举优化 | `FileEnumerationTest`：与真实官方提供方差分、目录/排除/上限回退、VFS 变化、取消和外部模块根；传 `-PrgExecutable=<rg.exe>` 执行同范围性能复测 |
| 目录树优化 | `DirectoryTreeTest`：真实官方全对象差分、深度/Unicode/磁盘变化/排除/未刷新/宽度阈值/junction/取消/禁用与替换提供方；传 `-PdirectoryBenchmark=true` 执行两轮每方 80 次同层小树/4096 文件树基准（也兼容 rgExecutable） |
| 目录树资格判断 | `DirectoryQualificationTest`：31/32 根项、浅深度、混合根、陈旧完整目录密集 VFS、增删改名及取消；传 `-PdirectoryQualificationBenchmark=true -PbaselinePluginJar=<0.6.7插件JAR>` 做官方/已发布旧算法/候选同包装两轮每方 84 次完整结果对照（含撤回数量门槛后的 63/64、127/128、255/256）；先将自然目录冻结到 `build/benchmarks/068-qualification/input/natural`，不同时启用其他性能实验 |
| 按名称分派/schema | `OfficialMcpAdapterTest`：单项描述与完整目录一致、默认提供方兼容、请求内分派、查找后禁用/路由变化/移除提供方、非空诊断与未保存修复；传 `-PrgExecutable=<rg.exe>` 运行同层单项与项目概览基准 |
| Git 调度 / 调用树定位 | `GitStatusBatchTest`：真实多仓库、过滤/计数/截断、分支、取消与回退；`QueryPerformanceTest`：官方 Git 处理器与调用树阶段定位。传 `-PrgExecutable=<rg.exe>` 启用 80 对完整结果性能实验，反射仅限测试 |
| 混合任务尾延迟 / 分段计时 | `QueryTimingTest`：默认关闭、认证、响应不变与上下文隔离；`FixedLoadGitPerformanceTest`：每次取样固定完整前序、真实 Git 命令阶段、三方 84 次平衡对照、两轮复测、取消与 20 秒超时。传 `-PrgExecutable=<rg.exe>` 启用性能实验，服务代理和命令识别反射仅限独立沙盒 |
| HTTP 完成 / Git 等待细分 | `protocol.HttpCompletionTest`：认证、数值事件、头/事件关联、缺失阶段、线程/连接复用和写失败关闭；`FixedLoadGitPerformanceTest` 另做隔离的进程等待 JFR 诊断。性能对照传 `-PbaselinePluginJar=<0.6.5插件JAR>` 使用旧 HTTP 边界；未传时保留 IO 测试候选。见 `docs/design/git-command-and-http-completion.md` |

运行测试只使用独立沙盒和临时 JVM，结束时清理自己启动的进程。Windows 原生进程库被执行环境限制时，区分环境失败与代码失败，使用获得授权的测试环境；不修改用户的 IDE 安装来规避限制。

`scripts/smoke-mcp.ps1` 仅做协议和查询自检，不重启业务服务。令牌通过环境变量或参数传入，禁止硬编码。

## 维护和交付

- 延续 Java 21 和 `dev.ideamcp` 包结构，保持协议层、IDE 适配层和界面层职责清晰。
- 工具变更同步维护 `ToolCatalog`、参数校验、后端路由、测试和中英文 README。
- 发布时同步 `build.gradle.kts`、`plugin.xml`、`RpcHandler.serverInfo.version` 及 README 版本和安装包路径。本地脚本从 `plugin.xml` 读取版本。
- 插件 ID 和 `262.10968.63`～`262.*` 范围的变更需要实际依据和验证，不能仅为消除提示而扩大兼容范围。
- 交付 `build/distributions/idea-symbol-mcp-<version>.zip`。分别确认源码构建版本、用户已安装版本和 Codex 已发现的工具目录。
- README 中英文内容保持一致，文末保留双语协作邀请。
- 避免修改或提交 `build/`、`.gradle/`、`.gradle-user-home/`、`.intellijPlatform/`、`.idea/` 等生成内容；临时验证输出放在已忽略目录中。
- 完成后说明改动、验证结果及实际限制。服务操作按证据报告配置名称、模式、进程状态和调试器连接结果。
