# Java Symbol MCP

通过运行中的 IntelliJ IDEA 查询 Java 代码、调用链和错误诊断，读取运行日志，并启动或重启现有 Run / Debug 配置。

Query Java code, call sites and diagnostics, read runtime logs, and start or restart existing Run / Debug configurations through a running IntelliJ IDEA instance.

[中文](#中文) · [English](#english) · [协作指南 / Agent guide](AGENTS.md)

## 中文

### 概览

当前版本 **0.6.8**，目标为 **IntelliJ IDEA 2026.2.3（262.10968.63）**，兼容范围为 `262.10968.63`～`262.*`。使用 JDK 25 编译和测试，保留 Java 21 语言级别及字节码。保留原有 19 个工具，新增两个异步管理工具；再复用当前 IDE 中可用的官方工具提供方，完整基线为 **56 + 21 = 77 个工具**。实际目录受插件、许可、禁用及路由设置影响。

| 工具 | 用途 | 必要参数 |
| --- | --- | --- |
| `list_projects` | 列出打开的项目及索引状态 | 无 |
| `read_symbols` | 读取文件声明或按精确名称查询符号 | `file` / `query` 二选一 |
| `find_definition` | 定位定义，解析具体方法重载 | `file`, `line`, `column` |
| `find_references` | 查找项目中的语义引用 | `file`, `line`, `column` |
| `find_callers` | 查找方法或构造器的直接调用者与调用位置 | `file`, `line`, `column` |
| `find_callees` | 查找方法或构造器体内直接调用的目标 | `file`, `line`, `column` |
| `get_diagnostics` | 按需分析单个 Java 文件的错误和警告 | `file` |
| `read_run_logs` | 读取指定 Run / Debug 实例的 stdout / stderr | `configurationId`；多实例需 `sessionId` |
| `list_run_configurations` | 查询运行配置、模式和进程 | 无；可用 `name` 过滤 |
| `restart_run_configuration` | 以 Run / Debug 模式启动或重启现有配置 | `configurationId` |
| `get_run_operation` | 查询异步进度、进程及调试器连接情况 | `operationId` |
| `list_debug_sessions` | 查询本项目的本地 Java 调试会话及暂停令牌 | 无 |
| `read_debug_threads` | 读取当前暂停现场的线程信息 | `sessionId`, `pauseId` |
| `read_debug_stack` | 读取指定已暂停线程的调用栈 | 上述参数及 `threadId` |
| `read_debug_variables` | 读取局部变量、参数与 this 引用 | 上述参数；`frameIndex` 默认 0 |
| `control_debugger` | 暂停、继续、进入/跳过/跳出 | `sessionId`, `action`；继续/单步需 `pauseId` |
| `list_breakpoints` | 查询本项目 Java 行断点及精确 ID | 无 |
| `set_breakpoint` | 添加、启用或禁用指定行断点 | `file`, `line` |
| `remove_breakpoint` | 删除指定 Java 行断点 | `breakpointId` |
| `get_tool_operation` | 获取官方长操作的状态及最终完整 MCP 结果 | `operationId` |
| `cancel_tool_operation` | 请求取消官方长操作；不会撤销副作用或停止业务进程 | `operationId` |

原有项目工具使用 `project`，新增官方工具使用 `projectPath`；两个异步管理工具只需 `operationId`。多个项目打开时使用 `list_projects` 返回的完整项目路径。

### 官方工具覆盖与自动配置

0.6.3 按[只读工具性能设计](docs/design/readonly-tool-performance.md)减少调用中的全目录 schema 转换，并在请求内复用已选提供方；执行时仍重新检查当前可用性和 schema，不缓存结果或授权。官方诊断、符号搜索、Git 等算法继续复用。十三项生产基线、同层复测、完整项目概览任务及边界见[验证报告](docs/verification/0.6.3-readonly-performance.md)；单项的小幅改善与组合任务收益分别报告，不承诺所有工具都显著提速。

0.6.3 安装后的[生产复验](docs/verification/0.6.3-installed.md)确认自动连接和 77 项工具调用正常。公平交替复测中，完整四查询项目概览中位数为 13 ms、官方为 14 ms；文件枚举为 6 ms、官方为 215.5 ms。旧单项计时器的轮换耦合问题已更正；诊断和调用树没有显著提速，沙盒改善率不能直接当成生产改善率。

0.6.1 修复安装后官方目录只有 `execute_tool`、调用时缺少会话上下文的问题：目录跟随 IDEA 的直连/路由模式，路由复用真实官方筛选会话，预期工具错误保留内容。默认设置下的目录、路由调用与禁用规则均有回归测试；0.6.0 用户需安装修复包并重启 IDE、重载客户端。

0.6.2 根据[文件枚举设计](docs/design/file-enumeration-optimization.md)优化 `search_file`：明确目录 `paths`、文件名 glob、`includeExcluded=false` 且完整结果少于 limit 时，直接枚举 IDEA 内容目录，复用官方 glob 与排除规则，不缓存文件列表。达到上限、复杂范围、预算耗尽及路由调用继续使用官方实现；完整结果无排序保证，截断结果保留官方顺序。[同条件沙盒验证](docs/verification/0.6.2-file-enumeration.md)包含全部 48 个源码文件的等价结果、速度复测、78 项测试及临时 Codex 的实际发现与枚举调用。

安装 0.6.2 并重载后的[生产连接复验](docs/verification/0.6.2-installed.md)已确认 77 项工具及实际调用正常；相同 48 文件的当前 Codex 测量中位数为 idea-java 7.5 ms、官方 MCP 191 ms。

新增入口覆盖官方文件/补丁、搜索/调用关系、诊断/构建、重构/格式化、Run、终端、13 个调试器、检查脚本/PSI、VCS 和 14 个数据库工具。全部名称与依赖见 [设计清单](docs/design/jetbrains-parity-and-auto-connect.md)，此前目录及路由修复见 [0.6.1 验证记录](docs/verification/0.6.1.md)。官方入口通过 IDE 进程内实现复用；已有原生工具继续保持独立实现。新的原生替代须通过等价结果及速度门槛，不以缩小结果冒充性能提升。

安装后运行 **Tools → Java MCP: Auto-Configure Clients**，选择一个客户端，即可创建或接管匹配的 `idea-java` 条目。支持 Codex、Claude Code、Cursor、Windsurf、项目级 VS Code / Copilot；Claude Desktop 使用随插件提供的独立 Java stdio 桥接。只维护明确选中的客户端；IDE 重启、端口回退、固定令牌更新后自动同步。首次配置或工具升级后重载客户端。固定令牌仍由 PasswordSafe 保存；内存凭据模式不建立自动维护。JSON / JSONC 保留未知字段并重新排版，注释会移除；损坏或冲突条目拒绝覆盖。

原生工具不依赖官方 MCP 插件。新增官方工具需要启用内置 MCP Server，以及对应的 Terminal / Shell、Debugger MCP、Database Tools and SQL、VCS、Qodana / Ultimate 等提供方；保持 IDE 的禁用、路由及确认规则，不自动启用 brave mode。`get_run_configurations` 及路由返回的结构化启动参数、环境字段会脱敏。官方调试求值/对象展开不具备原生 `read_debug_variables` 的无求值保证。

`execute_tool`、构建、运行配置、终端、检查脚本、SQL 执行/预览/连接测试等长操作立即返回 `operationId`，轮询 `get_tool_operation`，检查 `phase`、`error` 和 `result.isError`；最终 `result` 保留完整 MCP 内容。取消仅表示请求已提交。完整异步清单以工具描述为准；其余官方调用等待上限 20 秒。

### 安装与连接

0.6.7 按[其他只读工具第二轮设计](docs/design/readonly-tool-performance-067.md)优化根目录至少 32 个直接子项的完整磁盘目录树。使用本次 NIO 枚举属性，保持官方排序、缩进、深度和全部节点；小目录、链接/junction、变化、异常或预算不足继续走官方。Git 状态保持现状。筛查、同层完整结果复测与安装边界见[验证报告](docs/verification/0.6.7-readonly-performance.md)。生产已通过 [0.6.7 安装后复验](docs/verification/0.6.7-installed.md)，自动连接、77 项目录和工具调用正常。

18 项生产筛查通过 1584 次完整结果校验；独立沙盒同层完整 4096 文件树两轮每方 80 次，HTTP 中位数减少 40.6%～43.5%，P95 减少 40.1%～43.7%。最终 1328 次完整树比较、107 项 JUnit、94 项协议/HTTP 自检、外部临时 Codex 及构建/结构验证通过。已缓存小根仅用于选择官方回退，输出始终来自完整磁盘遍历；小树没有宣称提速，未缓存小根仍可能增加一次资格探测。

安装后的[生产目录树复测](docs/verification/0.6.7-installed.md)通过 2001 次完整检查。4096 文件受控夹具在当前 Codex 中位数为 40/41 ms、官方均为 58 ms；同客户端 HTTP 中位数减少 38.1%～38.7%。小目录 160 个 HTTP 样本全部走官方回退，资格判断 P95 为 0.058～0.063 ms；自然 55 子项宽根没有稳定收益，HTTP P95 增加约 0.3 ms。受控夹具与自然目录分开报告；Git 和执行代码未改动。

0.6.8 按[资格判断设计](docs/design/directory-tree-qualification-068.md)只保留已证实有收益的目录密集形状判断：完整缓存仅用于负向选择，磁盘探测最多 32 个根名称、四个分支及 512 个名称，输出继续执行完整遍历。普通文件数量门槛因自然目录收益不稳定、边界退步而撤回；自然宽目录 P95 未宣称解决。Git 保持不变。官方、已发布 0.6.7 和候选在同一 HTTP/RPC 包装下的两轮完整结果复测、独立沙盒及安装边界见[0.6.8 验证报告](docs/verification/0.6.8-directory-qualification.md)。

已安装的 [0.6.8 生产复验](docs/verification/0.6.8-installed.md)确认自动连接、77 项发现及调用正常，4008 次完整结果检查通过。目录密集树在资格阶段全部转官方，耗时接近直接官方；4096 文件大树同客户端 HTTP 中位数仍减少 36.7%～37.5%，当前 Codex 两轮中位数均为 40 ms、官方均为 57 ms。自然宽根仍无稳定收益且 HTTP P95 仍有回退；Git 实现和设置、64 个源码/资源文件均未改动。

0.6.6 按[Git 命令与 HTTP 完成边界设计](docs/design/git-command-and-http-completion.md)扩展观测；[验证报告](docs/verification/0.6.6-command-http.md)记录完整结果与性能边界。96 项 JUnit、94 项协议/HTTP 断言、独立沙盒和构建/结构检查通过。没有达到门槛的新执行优化，继续使用 Default 和官方 Git 实现。

此前生产实例经 [0.6.6 安装后复验](docs/verification/0.6.6-installed.md)确认自动连接、77 项工具发现及原生/官方路由调用正常。完整 65 项 Git 状态、14 节点前序的公平基准和独立 JFR 诊断共通过 1254 次完整结果校验。固定每个 Git 样本的官方前序后，两轮每方各 80 次的 P95 为 77.908/78.770、70.490/77.834 ms（idea-java/官方）。88 个生产完成事件全部完成，86 个 Git（含预热）逐一关联；最慢混合样本 73.320 ms 中提供方占 70.899 ms、其余服务端合计 1.853 ms、客户端残余 0.568 ms。没有达到明显收益门槛，按用户要求停止继续优化，保留 0.6.6。

开启 `dev.ideamcp.timings` 后，响应头新增 HTTP 任务排队、进入 handler 前与认证准入阶段，以及 `X-Java-Mcp-Timing-Id` 数值序号。完成后的发送/关闭耗时通过同序号的 `dev.ideamcp.HttpRequestCompleted` 自定义 JFR 事件关联，不能放回已经发出的计时头。默认不开启计时或录制；事件只含固定数值和完成标志，不含参数、工具名、路径、输出或凭据。缺失阶段为 −1，不当作零。需要完成事件时，用精确 IDEA PID 执行 `scripts/record-http-timings.ps1 -IdeaProcessId <PID> -JdkHome <JDK25>`；脚本仅启用 `scripts/http-timing.jfc` 指定的自定义事件。Git 命令探针和等待栈录制仍仅用于独立沙盒，未进入生产插件。

0.6.5 的[完整验证](docs/verification/0.6.5-fixed-load.md)通过 **92 项 JUnit、94 项协议/HTTP 断言**及构建和结构检查。固定每次 Git 前序后，此前的大幅 P95 差距未重现；沙盒慢样本主要落在 Git 命令范围内，IO 调度候选未达门槛，运行路径继续使用 Default 与官方实现。

0.6.5 按[混合任务尾延迟设计](docs/design/mixed-git-tail-latency.md)增加默认关闭的 HTTP 分段计时。需要诊断时，可在 IDEA 的自定义 VM 选项中添加 `-Ddev.ideamcp.timings=true` 并重启 IDEA；认证成功的响应会附带 `Server-Timing`，只含固定阶段名与毫秒耗时，响应正文和工具目录不变，不记录参数、路径、凭据或业务输出。`*_total` 为嵌套总时间，不能把全部字段直接相加；头部不包含之后的响应发送时间，异步操作只表示提交阶段。真实 Git 进程监听仅用于沙盒测试，不进入发布包。固定前序生产测量与候选门槛见[0.6.5 验证记录](docs/verification/0.6.5-fixed-load.md)。

此前 0.6.5 的[安装后复验](docs/verification/0.6.5-installed.md)确认当时生产实例为 **0.6.5**，自动连接、77 项工具发现及原生/官方路由调用正常。固定每个 Git 样本的前序，完整 1 仓库 / 56 项状态、14 节点调用树的 830 次结果校验全部一致；三组每方各 80 次 Git 的 P95 为 73/72、89/87、78/80 ms（idea-java/官方），没有显示稳定提速，单次慢样本仍存在。上述测量未开启分段计时；随后已按用户要求添加 VM 选项并正常重启 IDEA，确认生产 Git 查询返回阶段计时，完整 57 项结果与官方一致。随后完成[生产分段复测](docs/verification/0.6.5-production-phases.md)：当前 Codex 对照与两轮同客户端 HTTP 共 2490 次完整结果校验通过。HTTP 首段排队 P95 为 0.034～0.064 ms；最大 177.605 ms 慢样本中提供方占 159.904 ms，固定前序组未覆盖成本 P95 约 17 ms。优化建议优先细分提供方内部与未覆盖阶段，继续使用 Default 和官方 Git 路径。

0.6.4 按[调用树与 Git 性能设计](docs/design/call-tree-and-git-performance.md)对 2～4 个项目内独立 Git 仓库做有界并行调度，状态命令、解析、过滤、计数、排序和截断仍复用当前官方提供方；取消共享 20 秒查询预算。单仓库、显式选根、嵌套/外部根等保留官方路径。二进制进程读取未达收益门槛，调用树的候选搜索不能安全省略，均不替换。完整结果差分、尾延迟数据与安装边界见[0.6.4 验证记录](docs/verification/0.6.4-query-performance.md)。安装后的[生产复验](docs/verification/0.6.4-installed.md)确认 0.6.4 自动连接和 77 项工具正常；完整 14 节点调用树与单仓库 49 项 Git 状态均与官方一致。分任务复测中位数分别为 561/581 ms、62/64 ms（idea-java/官方）；混合轮测 Git P95 曾为 103/69 ms，完整保留两组数据。多仓库沙盒收益不适用于当前单仓库项目。

1. 构建插件，或使用已生成的 [0.6.8 安装包](build/distributions/idea-symbol-mcp-0.6.8.zip)。构建产物不随源码保存。
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

### 调用链分析（0.3）

`find_callers` / `find_callees` 接受方法或构造器声明、可解析方法引用的 `file` / `line` / `column`，按 PSI 解析精确重载。支持与引用查询相同的 `limit` / `offset`，返回 `calls`、去重后的调用位置总数 `total` 和分页字段；按文件 URL、数值源码偏移稳定排序。源码或索引改变后从头分页。

```json
{"name":"find_callers","arguments":{"project":"D:/work/demo","file":"src/main/java/demo/Sample.java","line":5,"column":16}}
```

```json
{"name":"find_callees","arguments":{"project":"D:/work/demo","file":"src/main/java/demo/Sample.java","line":8,"column":17}}
```

每条结果包含 `caller`、`callee`、调用位置 `location`、`kind`（`method_call` / `constructor_call` / `method_reference`）及 `deferred`。同一方法里的两个调用位置是两条记录；字段/初始化块中的调用者为 null。用返回符号的位置继续查询可逐层展开。

这是**静态、单层**分析，不展开接口的运行时实现、Spring 代理或反射。Lambda 内调用和方法引用标记为 `deferred=true`，并不表示已执行。出向查询排除嵌套类体和隐式调用，包含显式构造器调用；无方法体时为空，`unresolvedCount` 表示遍历中无法解析的调用数。

### Java 错误诊断（0.3）

```json
{"name":"get_diagnostics","arguments":{"project":"D:/work/demo","file":"src/main/java/demo/Sample.java","severity":"all","limit":100,"offset":0}}
```

在后台按需使用 IDEA 检查配置分析单个项目 Java 文件，包括未打开的文件和已提交到 PSI 但未保存的内容。`severity` 为 `all`（默认）、`error` 或 `warning`；返回 `diagnostics`（严重级别、消息、UTF-16 位置）、过滤后的 `total`、分页字段和字符串 `documentStamp`。

这不是 Maven / Gradle / javac 完整编译诊断，不包含外部构建错误；不会构建、运行应用或应用快速修复。分析等待上限 20 秒，超时取消；源码变化返回 `SOURCE_CHANGED`，`INDEXING`、`DOCUMENTS_UNCOMMITTED`、`DIAGNOSTICS_BUSY` 可等待后重试。源码、检查配置或索引改变后重新分页。

### 运行日志读取（0.3）

先查 `list_run_configurations`：活跃 `sessions` 提供 `logSessionId`，顶层 `logSessions` 列出仍保留的运行/已退出实例。用这里的实例 ID 作为 `sessionId`，区分同一配置重启前后的输出。

```json
{"name":"read_run_logs","arguments":{"project":"D:/work/demo","configurationId":"Application.DemoApplication","sessionId":"<LOG_SESSION_ID>","cursor":"0","maxChars":8000}}
```

不传 `cursor` 读最近尾部，`"0"` 从最早保留内容开始；续读使用同一实例上次返回的 `nextCursor`。游标为 UTF-16 流偏移的十进制字符串。`maxChars` 默认 8000，范围 2–20000；为避免截断代理对，实际返回可略少。`entries` 保留 `stdout` / `stderr` 类型和文本，`hasMore` 表示已有输出未读完；即使为 false，也可用 `nextCursor` 轮询新输出。

`availableFromCursor` / `endCursor` 是保留范围；过期游标返回 `truncated=true` 并从最早可用位置继续，落在代理对中间的游标会调整并返回 `cursorAdjusted=true`。超出本实例末尾返回 `INVALID_CURSOR`；多实例匹配时必须指定 `sessionId`，否则返回 `LOG_SESSION_REQUIRED`。

每实例最多保留 262144 个 UTF-16 单元、2048 个输出块，每项目最多 32 个实例；优先淘汰较早的已退出实例，32 个实例全活跃时不再跟踪新实例。插件在项目启动时订阅执行事件，为已有进程附加监听，**只保留监听以后产生的输出，不回填旧控制台历史**，由 `captureStartedAt` 和 `historyBeforeCaptureAvailable=false` 明示。未捕获或已淘汰的实例返回 `LOG_SESSION_NOT_FOUND`。缓冲与实例 ID 在插件/IDEA 重启后清空；停止 MCP HTTP 不停止进程，也不清空项目日志。

不读取磁盘日志，不返回 IDEA 系统/启动命令行输出、VM 参数或环境变量；应用自身 stdout/stderr 仍可能包含业务敏感数据，分享结果前应自行检查。读取日志不会启动、停止或重启服务。

### 调试现场与断点控制（0.4）

0.4 支持已存在的本地 Java Application、Spring Boot、Maven 和 Gradle 调试会话；不提供远程 Attach、表达式求值、写变量或对象展开。启动 Debug 仍使用下节的 `restart_run_configuration(mode="debug")`，读取现场不会自动启动、暂停或重启服务。

先 `list_projects`，再 `list_debug_sessions`。会话返回 `sessionId`、`configurationId`、`executionId`、`debuggerAttached`、`state` 和 `pauseId`。`state` 为 `running`、`suspended` 或 `command_pending`；未暂停时没有可用的 `pauseId`。会话 ID 限于所选项目，进程结束或插件重启后失效。

```json
{"name":"list_debug_sessions","arguments":{"project":"D:/work/demo"}}
```

```json
{"name":"read_debug_threads","arguments":{"project":"D:/work/demo","sessionId":"<SESSION_ID>","pauseId":"<PAUSE_ID>"}}
```

```json
{"name":"read_debug_stack","arguments":{"project":"D:/work/demo","sessionId":"<SESSION_ID>","pauseId":"<PAUSE_ID>","threadId":"<THREAD_ID>","offset":0,"limit":20}}
```

```json
{"name":"read_debug_variables","arguments":{"project":"D:/work/demo","sessionId":"<SESSION_ID>","pauseId":"<PAUSE_ID>","threadId":"<THREAD_ID>","frameIndex":0,"maxStringLength":256}}
```

三项读取支持 `limit`（1–200，默认 100）和 `offset`（默认 0）。线程按数值 ID 排序，包含名称、JDI 状态编号、是否暂停及 `eventThreadId`；并未暂停的其他线程不能读取栈或变量，也不会被工具额外暂停。若断点只暂停一个线程，其余线程仍可能变化，线程列表是查询时的观测。

栈中的 `frameIndex` 为绝对索引，源码行号从 1 开始，缺少源码/行号时对应字段为空。变量返回 `this`（如有）及按名称排序的可见局部变量/参数；没有局部变量调试信息时，`localInformationAvailable=false`，回退到 `arg0` 等参数。基本类型以类型名和文本值返回，字符串截断到 `maxStringLength`（1–1000，默认 256），对象/数组只返回类型、对象 ID 和数组长度。读取通过 JDI 完成，不调用调试目标的 getter、`toString()` 或任何表达式；IDEA 自身已有的渲染器、条件断点或日志表达式仍由 IDEA 按原配置运行。变量可能含业务敏感值，不要直接发布真实快照。

每次暂停都必须使用新的 `pauseId`，继续、单步、退出和再次暂停都会使旧令牌失效，返回 `STALE_PAUSE`；其他可重试现场错误包括 `THREAD_NOT_SUSPENDED`、`FRAME_NOT_FOUND` 和 `DEBUG_DISCONNECTED`。刷新会话与线程后再读取，不拼接不同暂停的页面。

```json
{"name":"control_debugger","arguments":{"project":"D:/work/demo","sessionId":"<SESSION_ID>","pauseId":"<PAUSE_ID>","action":"step_into"}}
```

`action` 支持 `pause`、`resume`、`step_into`、`step_over`、`step_out`；仅 `pause` 必须省略 `pauseId`，其他操作必须传当前暂停令牌。返回 `accepted=true` 和 `commandId` **只表示提交给 IDEA**，随后轮询 `list_debug_sessions`，确认运行状态或新的暂停令牌。未完成命令返回 `DEBUG_CONTROL_PENDING`；20 秒等待超时返回 `DEBUG_TIMEOUT`，先核对会话，避免盲目重试。步骤可能受既有断点、IDEA 步进过滤或方法执行时间影响。

```json
{"name":"set_breakpoint","arguments":{"project":"D:/work/demo","file":"src/main/java/demo/Sample.java","line":9,"enabled":true}}
```

```json
{"name":"list_breakpoints","arguments":{"project":"D:/work/demo"}}
```

```json
{"name":"remove_breakpoint","arguments":{"project":"D:/work/demo","breakpointId":"<BREAKPOINT_ID>"}}
```

断点限于项目内容中的 Java 文件和 IDEA 可设置断点的行；`line` 从 1 开始。`set_breakpoint` 返回 `created`，同一行已有断点时只更新 `enabled`，保留条件、日志表达式和暂停策略；同一行多个内联断点则返回 `AMBIGUOUS_BREAKPOINT`，需在 IDEA 中选择。列表仅暴露是否有条件/日志表达式，不返回表达式正文。ID 在插件重启或断点删除后失效，删除仅作用于指定断点。

**断点是项目级共享设置，会影响该项目的其他调试会话并保留在 IDEA 中。** 控制和断点变更属于非只读操作，只在用户明确要求相应调试操作时调用；继续和单步可能执行代码并改变业务状态。插件卸载不会继续/停止业务进程或删除用户断点。

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

需要包含 javac 的完整 **JDK 25**，以读取 262 平台的 Java 25 API 类。插件源码和字节码仍使用 Java 21（`--release 21`），测试运行在 IDEA 对应的 JBR 25 上。PowerShell 本机路径示例：

```powershell
$env:IDEA_HOME = 'D:\Program Files\JetBrains\IntelliJ IDEA 2026.2.3'
$env:JAVA_HOME = Join-Path $env:IDEA_HOME 'jbr' # 必须包含 javac
.\gradlew.bat "-PlocalIdePath=$env:IDEA_HOME" test buildPlugin verifyPluginStructure
.\gradlew.bat "-PlocalIdePath=$env:IDEA_HOME" runIde
```

Wrapper 固定支持 Java 25 的 Gradle 9.1.0 和发行包 SHA-256，IntelliJ Platform Gradle Plugin 为 2.18.1。首次构建会下载 Gradle 和依赖，不传 `localIdePath` 时下载 IDEA 2026.2.3。若 Gradle 自身运行于其他受支持的 JDK 且未检测到 JDK 25，Foojay 工具链解析器 1.0.0 会自动下载 JDK 25；配置 `JAVA_HOME` 为 IDEA 的 JBR 25 可复用本机运行时。macOS / Linux 使用 `sh ./gradlew`；本地 PowerShell 打包脚本面向 Windows。

使用本机 IDEA JAR 编译打包。首次准备 TOML 运行依赖后，本地脚本可离线运行：

```powershell
.\gradlew.bat "-PlocalIdePath=$env:IDEA_HOME" prepareLocalDependencies
.\scripts\build-local.ps1 -IdeaHome $env:IDEA_HOME -JdkHome $env:JAVA_HOME
.\scripts\build-local.ps1 -IdeaHome $env:IDEA_HOME -JdkHome $env:JAVA_HOME -Test
.\scripts\build-local.ps1 -IdeaHome $env:IDEA_HOME -JdkHome $env:JAVA_HOME -PsiTest
```

首次使用 `prepareLocalDependencies` 准备 tomlj 及其运行依赖。之后 `-Test` 做协议 / HTTP 自检，无需 Gradle。262 发行包不包含完整测试框架，`-PsiTest` 委托 Gradle `test` 运行独立沙盒测试。未传 `-JdkHome` 时优先使用 `JAVA_HOME`，再尝试 IDEA `jbr`。

Gradle 及依赖已缓存时，可使用离线构建；也可单独运行测试类：

```powershell
.\gradlew.bat --gradle-user-home .gradle-user-home "-PlocalIdePath=$env:IDEA_HOME" test buildPlugin verifyPluginStructure --offline
.\gradlew.bat "-PlocalIdePath=$env:IDEA_HOME" test --tests dev.ideamcp.RunControlServiceTest
```

产物：`build/distributions/idea-symbol-mcp-<version>.zip`；测试报告：`build/reports/tests/test/index.html`。测试使用独立 IDEA 沙盒；运行控制测试启动并清理自己的临时 JVM。Windows 测试需要允许加载 IDEA 原生进程管理库。

0.6.4 的[最终验证](docs/verification/0.6.4-query-performance.md)通过 **87 项 JUnit、94 项协议/HTTP 断言**及插件构建和结构检查。两轮同层和 HTTP 的完整多仓库差分达到收益门槛，单仓库回退没有明显额外开销；调用树保留官方实现。[安装后复验](docs/verification/0.6.4-installed.md)已确认生产服务为 0.6.4，工具与完整结果正常；单仓库和调用树未显示明显提速。

2026-10-24 在 **IDEA 2026.2.3 / IU-262.10968.63、JBR 25.0.4、Gradle 9.1.0** 上，0.6.3 完整回归通过 **83 项 JUnit 测试**，协议自检含 **94 项断言**，并通过 `buildPlugin verifyPluginStructure`。覆盖真实 JVM 的 Run / Debug 与日志捕获、两台临时 JVM 的调试隔离、线程/栈/局部变量、暂停令牌失效、三种单步与继续/暂停、断点增删、实例隔离和缓冲淘汰、错误/警告与未保存修复、静态调用分析，以及 481 处引用和 241 处调用的完整分页。测试在独立配置、系统和日志目录中运行，并清理自行启动的临时 JVM；不修改用户 IDE。

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
| `JavaCallTools` / `DiagnosticsTools` | 静态调用关系、后台单文件错误/警告分析 |
| `FileEnumeration` | 有界、无缓存的完整目录枚举；不满足条件时回退官方搜索 |
| `RunControlService` | 项目级状态机、进程终止和调试器跟踪 |
| `RunLogService` / `RunLogBuffer` | 项目内执行事件订阅、实例隔离和有界日志游标 |
| `DebugService` | EDT 调试协调、调试器管理线程上的 JDI 读取、暂停令牌、断点和单步 |
| `TokenStore` / `*Action` | PasswordSafe 和 IDEA 菜单 |
| `protocol/` | schema、参数校验、JSON-RPC 与 HTTP 认证边界 |

仅监听回环地址，验证 Host / Origin 并要求 Bearer 认证。协议为 `2025-03-26`、`2025-06-18`、`2025-11-25` 的无会话 Streamable HTTP 子集，提供 `initialize`、初始化通知、`ping`、`tools/list`、`tools/call`。响应使用 JSON；GET / DELETE 返回 405；没有 SSE 推送、资源或 MCP 协议级任务接口；stdio 客户端通过独立桥接进程连接 HTTP。重启操作 ID 是工具的应用级约定。

POST 要求 `Content-Type: application/json` 和 `Accept: application/json, text/event-stream`，请求体上限 1 MiB。0.6.8 针对本地 IDEA 2026.2.3，其他版本、Remote Development 和分离前后端尚未验证；IDEA 2025.2 请使用旧版 0.4.0。

| 问题 | 处理 |
| --- | --- |
| HTTP 401 | 核对令牌，复制当前配置或保存客户端原令牌为固定值 |
| 连接失败或端口改变 | 打开 IDEA 项目，重新复制并核对实际 URL |
| 客户端仍只有旧工具 | 重新加载连接或重启客户端，确认原生 21 项及当前可用的官方目录 |
| `INDEXING` / `DOCUMENTS_UNCOMMITTED` | 等待索引或编辑内容提交后重试 |
| 项目或配置 ID 不匹配 | 重新列出项目和配置，使用完整路径和精确 ID |
| `IDE_BUSY` | 等待 UI 恢复，先查列表中的 `activeOperationId`，避免盲目重启 |
| `EDIT_BEFORE_RUN` / 校验失败 | 在 IDEA 中补齐配置并关闭启动前编辑 |
| `STOP_TIMEOUT` | 检查旧进程；插件不会自动强杀或启动替代实例 |
| `start_timeout` | 继续查询原操作并检查 IDEA Build / Run 窗口 |

开发协作规则见 [AGENTS.md](AGENTS.md)。

## English

### Overview

Version **0.6.8** targets **IntelliJ IDEA 2026.2.3 (262.10968.63)**, with a compatibility range of `262.10968.63`–`262.*`. It builds and tests with JDK 25 while retaining Java 21 language and bytecode compatibility. The original nineteen tools plus two operation-management tools are retained alongside available official providers: **56 + 21 = 77 tools** in the complete baseline environment. Availability depends on plugins, licensing, disabled tools and router settings.

| Tool | Purpose | Required arguments |
| --- | --- | --- |
| `list_projects` | List open projects and indexing state | None |
| `read_symbols` | Read declarations or search exact symbol names | Exactly one of `file` / `query` |
| `find_definition` | Locate declarations and resolve concrete overloads | `file`, `line`, `column` |
| `find_references` | Find semantic references within the project | `file`, `line`, `column` |
| `find_callers` | Find direct method/constructor callers and call sites | `file`, `line`, `column` |
| `find_callees` | Find direct call targets inside a method/constructor body | `file`, `line`, `column` |
| `get_diagnostics` | Analyze one Java file for errors and warnings on demand | `file` |
| `read_run_logs` | Read stdout/stderr from an exact Run / Debug instance | `configurationId`; `sessionId` for multiple instances |
| `list_run_configurations` | Inspect configurations, modes and processes | None; optional `name` filter |
| `restart_run_configuration` | Start or restart an existing Run / Debug configuration | `configurationId` |
| `get_run_operation` | Inspect asynchronous progress and debugger attachment | `operationId` |
| `list_debug_sessions` | List local Java debug sessions and suspension tokens | None |
| `read_debug_threads` | Read thread metadata at the current pause | `sessionId`, `pauseId` |
| `read_debug_stack` | Read a suspended thread's frames | Above plus `threadId` |
| `read_debug_variables` | Read locals, arguments and this reference | Above; `frameIndex` defaults to 0 |
| `control_debugger` | Pause, resume, step into/over/out | `sessionId`, `action`; resume/step require `pauseId` |
| `list_breakpoints` | List project Java line breakpoints and exact IDs | None |
| `set_breakpoint` | Add, enable or disable a line breakpoint | `file`, `line` |
| `remove_breakpoint` | Remove an exact Java line breakpoint | `breakpointId` |
| `get_tool_operation` | Query an official long operation and its full final MCP result | `operationId` |
| `cancel_tool_operation` | Request cancellation without undoing effects or stopping business processes | `operationId` |

Original project tools use `project`; added official tools use `projectPath`. The two new operation tools only require `operationId`. With multiple projects open, use the exact project path returned by `list_projects`.

### Official coverage and automatic configuration

Version 0.6.3 follows the [read-only performance design](docs/design/readonly-tool-performance.md): resolve only the requested schema and reuse its provider within that request, while checking current availability and schema again at execution. No results or authorizations are cached; official diagnostic, symbol-search and Git algorithms are retained. The [verification report](docs/verification/0.6.3-readonly-performance.md) separates thirteen production baselines, controlled measurements, the complete project-overview task and remaining limits. Small per-call savings are distinguished from combined-task improvements.

The installed 0.6.3 [production verification](docs/verification/0.6.3-installed.md) confirms automatic connection and 77 discovered tools with successful calls. With balanced provider order, the full four-query overview median was 13 ms versus 14 ms for official MCP; file enumeration was 6 ms versus 215.5 ms. Earlier coupled single-task ordering has been corrected. Diagnostics and call hierarchy showed no substantial speedup; sandbox percentages must not be presented as production improvements.

Version 0.6.1 fixes the installed adapter exposing only `execute_tool` from the official catalog and failing routed calls without a session handler. Discovery follows the IDE's direct/router mode; routing uses a real filtered official session and preserves expected tool errors. Default settings, routing and disabled tools have regression coverage. Users of 0.6.0 need to install the fix, restart the IDE and reload their client.

Version 0.6.2 implements the [file enumeration design](docs/design/file-enumeration-optimization.md). For explicit directory paths, filename globs, `includeExcluded=false` and complete results below limit, `search_file` enumerates IDE content directories using official glob/exclusion semantics without caching file lists. Limits, complex scopes, budget exhaustion and routed calls retain the official implementation. Complete results have no ordering guarantee; truncated results retain the official prefix/order. The [equal-scope sandbox report](docs/verification/0.6.2-file-enumeration.md) covers all 48 source files, performance comparisons, 78 tests and actual discovery/enumeration through a temporary Codex client.

After installing/reloading 0.6.2, [production verification](docs/verification/0.6.2-installed.md) confirmed 77 tools and successful calls. Through the current Codex connection, the same complete 48-file query measured a median of 7.5 ms for idea-java versus 191 ms for official MCP.

Added tools cover official file/patch operations, search/call analysis, diagnostics/builds, refactoring/formatting, Run, terminal, thirteen debugger tools, inspection scripts/PSI, VCS and fourteen database tools. See the [complete design inventory](docs/design/jetbrains-parity-and-auto-connect.md) and the earlier [0.6.1 discovery/router validation](docs/verification/0.6.1.md). Added capabilities reuse IDE implementations in-process, while existing native APIs remain independent. A new native replacement requires equivalent results and a measured speed improvement.

After installation, run **Tools → Java MCP: Auto-Configure Clients** and select one client to create or adopt its matching `idea-java` entry. Supported clients are Codex, Claude Code, Cursor, Windsurf and project-level VS Code / Copilot; Claude Desktop uses the bundled Java stdio bridge. Only selected clients are maintained across IDE restarts, port fallback and token changes. Reload the client after initial setup or tool upgrades. PasswordSafe remains the token source; memory-only credentials cannot establish automatic maintenance. JSON / JSONC merges preserve unknown fields but reformat the file and remove comments; malformed files or ownership conflicts are refused.

Native tools work without the official MCP plugin. Added tools need the bundled MCP Server and their Terminal / Shell, Debugger MCP, Database Tools and SQL, VCS, Qodana / Ultimate providers. IDE disabled-tool, router and confirmation settings are respected; brave mode is never enabled automatically. Structured launch argument/environment fields are sanitized in `get_run_configurations` and router results. Official debugger evaluation and object expansion do not inherit native `read_debug_variables`'s no-evaluation guarantee.

Long operations such as `execute_tool`, builds, run configurations, terminal commands, inspection scripts and SQL execution/preview/connection tests immediately return `operationId`. Poll `get_tool_operation`, checking `phase`, `error` and `result.isError`; final `result` preserves the full MCP result. Cancellation is a request. Tool descriptions identify the complete asynchronous set; other official calls have a 20-second wait limit.

### Install and connect

Version 0.6.7 follows the [second read-only performance design](docs/design/readonly-tool-performance-067.md) to optimize complete disk directory trees whose roots have at least 32 direct entries. Fresh NIO enumeration attributes preserve official sorting, indentation, depth and every node; thin roots, links/junctions, changes, errors or insufficient budgets retain official traversal. Git status is unchanged. See the [verification report](docs/verification/0.6.7-readonly-performance.md) for screening, equal-result same-layer benchmarks and installation boundaries. Production passed [0.6.7 installed verification](docs/verification/0.6.7-installed.md), with working automatic connection, 77 discovered tools and successful calls.

Eighteen production tasks passed 1584 full-result checks. In two controlled sandbox runs with 80 samples per provider, the complete 4096-file tree reduced HTTP median by 40.6%–43.5% and P95 by 40.1%–43.7%. All 1328 full-tree comparisons, 107 JUnit tests, 94 protocol/HTTP assertions, external temporary Codex integration, build and structure checks passed. Cached thin roots only select official fallback; output always comes from complete disk traversal. Small trees have no claimed speedup, and uncached thin roots can still incur one eligibility probe.

The [installed production directory benchmark](docs/verification/0.6.7-installed.md) passed 2001 complete checks. The controlled 4096-file fixture measured current Codex medians of 40/41 ms versus 58 ms for official MCP in both rounds; identical-client HTTP medians improved by 38.1%–38.7%. All 160 small-directory HTTP samples used official fallback, with eligibility P95 of 0.058–0.063 ms. The actual 55-entry wide root showed no stable gain and about 0.3 ms HTTP P95 regression. Controlled and natural workloads are reported separately; Git and runtime code are unchanged.

Version 0.6.8 follows the [eligibility design](docs/design/directory-tree-qualification-068.md) and retains only the demonstrated directory-heavy shape improvement. Complete cached VFS data is used only for negative selection; the disk probe is bounded to 32 root names, four branches and 512 total names. Output still uses a complete traversal. File-count thresholds were withdrawn after unstable natural-tree gains and boundary regressions; natural-wide P95 is not claimed fixed. Git is unchanged. See [0.6.8 verification](docs/verification/0.6.8-directory-qualification.md) for two equal-result rounds against official and released 0.6.7 under one HTTP/RPC wrapper, the independent sandbox and installation boundaries.

The [installed 0.6.8 production check](docs/verification/0.6.8-installed.md) confirms automatic connection, 77 discovered tools and successful calls, with 4008 complete-result checks. Directory-heavy trees select official traversal during qualification and perform close to direct official MCP. The 4096-file tree retains 36.7%–37.5% identical-client HTTP median gains; current Codex medians are 40 ms versus 57 ms in both rounds. Natural-wide gains remain unstable and HTTP P95 regression persists. Git implementation/settings and all 64 source/resource files are unchanged.

Version 0.6.6 extends observation according to the [Git-command and HTTP-completion design](docs/design/git-command-and-http-completion.md); the [verification report](docs/verification/0.6.6-command-http.md) records complete results and performance limits. All 96 JUnit tests, 94 protocol/HTTP assertions, isolated sandbox, build and structure checks passed. No new execution candidate met the benefit gate; Default and the official Git implementation are retained.

The earlier production instance passed [0.6.6 installed verification](docs/verification/0.6.6-installed.md): automatic connection, discovery of 77 tools, and native/official-router calls work. Fair benchmarks and a separate JFR diagnostic passed 1254 full-result checks for the complete 65-entry Git status and 14-node predecessor. With the same official predecessor before every Git sample, two rounds of 80 samples per provider measured P95 of 77.908/78.770 and 70.490/77.834 ms (idea-java/official). All 88 production completion events finished; all 86 Git samples including warmups matched their own events. In the slowest mixed sample of 73.320 ms, the provider took 70.899 ms, all other server work 1.853 ms, and the client residual 0.568 ms. No clear benefit gate was met; further optimization stops as requested, retaining 0.6.6.

With `dev.ideamcp.timings` enabled, headers add HTTP task-queue, pre-handler and admission phases plus a numeric `X-Java-Mcp-Timing-Id`. The matching custom JFR event `dev.ideamcp.HttpRequestCompleted` records later send/close durations, which cannot be inserted into already-sent timing headers. Timing and recording are off by default. Events contain only fixed numeric fields and a completion flag, excluding arguments, tool names, paths, output and credentials; −1 means an unobserved phase. To record completions, run `scripts/record-http-timings.ps1 -IdeaProcessId <PID> -JdkHome <JDK25>` with the exact IDEA PID. It enables only the custom event in `scripts/http-timing.jfc`. Git command probes and wait-stack recordings remain confined to the independent sandbox and are excluded from the production plugin.

The [full 0.6.5 verification](docs/verification/0.6.5-fixed-load.md) passed **92 JUnit tests, 94 protocol/HTTP assertions**, build and structure checks. With the same predecessor before every Git sample, the earlier large P95 gap did not recur. Sandbox slow samples were dominated by the Git command interval; the IO dispatcher candidate failed the benefit gate, so runtime calls retain Default and the official implementation.

Version 0.6.5 follows the [mixed-task tail-latency design](docs/design/mixed-git-tail-latency.md) and adds opt-in HTTP phase timings, disabled by default. For diagnostics, add `-Ddev.ideamcp.timings=true` to IDEA's custom VM options and restart IDEA. Authenticated responses include `Server-Timing` with fixed phase names and milliseconds, while response bodies and tool catalogs remain unchanged. Arguments, paths, credentials and business output are not recorded. Fields ending in `*_total` overlap their child phases; do not sum every field. Headers exclude subsequent response sending, and asynchronous operations measure submission only. Git process listeners are confined to sandbox tests. See the [0.6.5 verification](docs/verification/0.6.5-fixed-load.md) for fixed-predecessor production measurements and candidate gates.

Earlier [0.6.5 installed verification](docs/verification/0.6.5-installed.md) confirmed the production instance was **0.6.5**, with working automatic connection, discovery of 77 tools, and native/official-router calls. With the same predecessor before every Git sample, all 830 full-result checks matched for the complete 56-entry single-root status and 14-node call tree. Each of the three conditions used 80 Git samples per provider; P95 was 73/72, 89/87 and 78/80 ms (idea-java/official). No consistent speedup was observed, and individual slow samples remain. Those measurements had phase timings disabled. Subsequently, the VM option was added and IDEA was restarted normally at the user's request; production Git queries now return phase timings, with the complete 57-entry result matching official MCP. The subsequent [production phase benchmarks](docs/verification/0.6.5-production-phases.md) passed 2490 full-result checks across current Codex comparisons and two same-client HTTP runs. HTTP initial-dispatch P95 was 0.034–0.064 ms; the provider accounted for 159.904 ms of the largest 177.605 ms sample, while uncovered P95 was about 17 ms under fixed predecessors. Recommendations prioritize finer provider and uncovered-cost measurements while retaining Default and the official Git path.

Version 0.6.4 follows the [call-tree and Git performance design](docs/design/call-tree-and-git-performance.md): bounded parallel scheduling for 2–4 independent Git roots inside the project, while retaining the current official provider's status commands, parsing, filters, counters, ordering and limits. Cancellation shares the outer 20-second query budget. Single roots, explicit selections, nested/external roots and unsupported cases retain the official path. Binary process reading failed the benefit threshold, and call-tree candidate searches cannot safely be omitted; both remain unchanged. See the [0.6.4 verification](docs/verification/0.6.4-query-performance.md) for full-result comparisons, tail latency and installation limits. The installed [production verification](docs/verification/0.6.4-installed.md) confirms 0.6.4 automatic connection and 77 tools. The complete 14-node tree and 49-entry single-root status match official MCP. Separate-task medians were 561/581 ms and 62/64 ms (idea-java/official); mixed-task Git P95 was 103/69 ms. Both runs are retained. Multi-root sandbox gains do not apply to this single-root project.

1. Build the plugin or use the generated [0.6.8 distribution](build/distributions/idea-symbol-mcp-0.6.8.zip). Build artifacts are not included with the source.
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

### Call analysis (0.3)

`find_callers` / `find_callees` accept `file` / `line` / `column` at a method/constructor declaration or resolvable method reference, using PSI to resolve exact overloads. Both support the reference-query `limit` / `offset` convention, returning `calls`, the unique call-site `total` and pagination metadata. Results are deduplicated and sorted by file URL and numeric source offsets. Restart pagination after source or index changes.

```json
{"name":"find_callers","arguments":{"project":"D:/work/demo","file":"src/main/java/demo/Sample.java","line":5,"column":16}}
```

```json
{"name":"find_callees","arguments":{"project":"D:/work/demo","file":"src/main/java/demo/Sample.java","line":8,"column":17}}
```

Each edge contains `caller`, `callee`, call-site `location`, `kind` (`method_call` / `constructor_call` / `method_reference`) and `deferred`. Two occurrences in one caller produce two records. Calls in fields/initializer blocks have a null method `caller`. Follow returned symbol positions to expand the chain.

Analysis is **static and one-hop**, without expanding runtime interface implementations, Spring proxies or reflection. Calls inside lambdas and method references have `deferred=true`, which does not imply execution. Outgoing queries exclude nested class bodies and implicit calls, include explicit constructor calls, return no edges for absent bodies, and report unresolved calls via `unresolvedCount`.

### Java diagnostics (0.3)

```json
{"name":"get_diagnostics","arguments":{"project":"D:/work/demo","file":"src/main/java/demo/Sample.java","severity":"all","limit":100,"offset":0}}
```

Analyze one project Java file in the background using IDEA's inspection profile, including unopened files and committed but unsaved editor content. `severity` accepts `all` (default), `error` or `warning`. Results contain `diagnostics` (severity, message, UTF-16 location), the filtered `total`, pagination metadata and a string `documentStamp`.

These are not complete Maven / Gradle / javac compilation diagnostics and exclude external build failures. This tool does not build, run application code or apply quick fixes. Analysis waits at most 20 seconds and is cancelled on timeout. Source changes return `SOURCE_CHANGED`; retry `INDEXING`, `DOCUMENTS_UNCOMMITTED` and `DIAGNOSTICS_BUSY` after IDEA settles. Restart pagination after source, inspection-profile or index changes.

### Runtime log reads (0.3)

First call `list_run_configurations`: active `sessions` include `logSessionId`, and top-level `logSessions` lists retained running/terminated instances. Pass that instance ID as `sessionId` to separate output across restarts.

```json
{"name":"read_run_logs","arguments":{"project":"D:/work/demo","configurationId":"Application.DemoApplication","sessionId":"<LOG_SESSION_ID>","cursor":"0","maxChars":8000}}
```

Omit `cursor` for the recent tail, use `"0"` for earliest retained output, and continue with the previous `nextCursor` from the same instance. Cursors are decimal strings representing UTF-16 stream offsets. `maxChars` defaults to 8000 and accepts 2–20000; results can be slightly shorter to avoid splitting surrogate pairs. `entries` retain `stdout` / `stderr` types and text. `hasMore` indicates unread existing output; keep polling `nextCursor` for new output even when false.

`availableFromCursor` / `endCursor` describe the retained range. Evicted cursors produce `truncated=true` and resume at the earliest available offset; cursors inside surrogate pairs are adjusted with `cursorAdjusted=true`. Cursors beyond that instance's end return `INVALID_CURSOR`. Multiple matching instances require `sessionId`, otherwise returning `LOG_SESSION_REQUIRED`.

Each instance retains at most 262144 UTF-16 units and 2048 chunks, with 32 instances per project. Older terminated instances are evicted first; new instances are not tracked when all 32 slots are live. The plugin subscribes to execution events at project startup and attaches to existing processes. **Only output emitted after attachment is captured; earlier console history is not backfilled**, as reported by `captureStartedAt` and `historyBeforeCaptureAvailable=false`. Uncaptured/evicted instances return `LOG_SESSION_NOT_FOUND`. Plugin/IDE restarts clear buffers and session IDs; stopping MCP HTTP does not stop processes or clear project logs.

No disk logs, IDEA system/command-line output, VM arguments or environment variables are read or returned. Application stdout/stderr may still contain sensitive business data; inspect results before sharing. Reading logs never starts, stops or restarts a service.

### Debugger inspection and control (0.4)

0.4 supports existing local Java Application, Spring Boot, Maven and Gradle debug sessions. It does not provide remote Attach, expression evaluation, variable writes or object expansion. Starting Debug still uses `restart_run_configuration(mode="debug")` below. Inspection never automatically starts, pauses or restarts services.

Call `list_projects`, then `list_debug_sessions`. Each session returns `sessionId`, `configurationId`, `executionId`, `debuggerAttached`, `state` and `pauseId`. States are `running`, `suspended` and `command_pending`; running sessions have no usable pause token. Session IDs are scoped to the selected project and expire when the process ends or the plugin restarts.

```json
{"name":"list_debug_sessions","arguments":{"project":"D:/work/demo"}}
```

```json
{"name":"read_debug_threads","arguments":{"project":"D:/work/demo","sessionId":"<SESSION_ID>","pauseId":"<PAUSE_ID>"}}
```

```json
{"name":"read_debug_stack","arguments":{"project":"D:/work/demo","sessionId":"<SESSION_ID>","pauseId":"<PAUSE_ID>","threadId":"<THREAD_ID>","offset":0,"limit":20}}
```

```json
{"name":"read_debug_variables","arguments":{"project":"D:/work/demo","sessionId":"<SESSION_ID>","pauseId":"<PAUSE_ID>","threadId":"<THREAD_ID>","frameIndex":0,"maxStringLength":256}}
```

All three reads support `limit` (1–200, default 100) and `offset` (default 0). Threads are sorted by numeric ID, with names, JDI status numbers, suspension flags and `eventThreadId`. Stack/variable reads reject threads that are not suspended; they never suspend additional threads. With thread-only breakpoints, other threads may continue changing, so the thread list is an observation at query time.

Stack `frameIndex` values are absolute, source lines are 1-based, and unavailable source/line fields are null. Variables include `this` when present and visible locals/arguments sorted by name. Missing local debug information sets `localInformationAvailable=false` and falls back to `arg0`-style arguments. Primitives return type names and textual values; strings are bounded by `maxStringLength` (1–1000, default 256); objects/arrays expose only types, object IDs and array lengths. Reads use JDI without invoking target getters, `toString()` or expressions. Existing IDEA renderers, conditional breakpoints and log expressions continue following their own configuration. Variables may contain sensitive business data; do not publish real snapshots.

Use a fresh `pauseId` for every suspension. Resume, stepping, stop and another pause invalidate old tokens with `STALE_PAUSE`. Other snapshot errors include `THREAD_NOT_SUSPENDED`, `FRAME_NOT_FOUND` and `DEBUG_DISCONNECTED`. Refresh sessions and threads before retrying; never combine pages from different pauses.

```json
{"name":"control_debugger","arguments":{"project":"D:/work/demo","sessionId":"<SESSION_ID>","pauseId":"<PAUSE_ID>","action":"step_into"}}
```

Actions are `pause`, `resume`, `step_into`, `step_over` and `step_out`. Omit `pauseId` for `pause`; it is required for every other action. `accepted=true` and `commandId` **only mean submission to IDEA**. Poll `list_debug_sessions` to confirm running state or a fresh pause token. Overlapping commands return `DEBUG_CONTROL_PENDING`; after a 20-second `DEBUG_TIMEOUT`, inspect the session before retrying. Existing breakpoints, stepping filters and method duration can affect completion.

```json
{"name":"set_breakpoint","arguments":{"project":"D:/work/demo","file":"src/main/java/demo/Sample.java","line":9,"enabled":true}}
```

```json
{"name":"list_breakpoints","arguments":{"project":"D:/work/demo"}}
```

```json
{"name":"remove_breakpoint","arguments":{"project":"D:/work/demo","breakpointId":"<BREAKPOINT_ID>"}}
```

Breakpoints are restricted to project-content Java files and lines accepted by IDEA. `line` is 1-based. `set_breakpoint` reports `created`; an existing line breakpoint only changes `enabled`, preserving conditions, log expressions and suspend policy. Multiple inline breakpoints on the same line return `AMBIGUOUS_BREAKPOINT` for manual selection in IDEA. Listings disclose only whether conditions/log expressions exist, not their text. IDs expire after plugin restart or breakpoint removal; removal affects only the exact requested ID.

**Breakpoints are project-wide settings shared with other debug sessions and persist in IDEA.** Controls and breakpoint changes are mutations and must only be invoked for an explicitly requested debugging action. Resume/step can execute code and change business state. Plugin disposal neither resumes/stops business processes nor deletes user breakpoints.

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

Use a complete **JDK 25**, including javac, to read the 262 platform's Java 25 API classes. Plugin sources and bytecode remain at Java 21 (`--release 21`); tests run on the IDE's matching JBR 25. Example local PowerShell setup:

```powershell
$env:IDEA_HOME = 'D:\Program Files\JetBrains\IntelliJ IDEA 2026.2.3'
$env:JAVA_HOME = Join-Path $env:IDEA_HOME 'jbr' # must include javac
.\gradlew.bat "-PlocalIdePath=$env:IDEA_HOME" test buildPlugin verifyPluginStructure
.\gradlew.bat "-PlocalIdePath=$env:IDEA_HOME" runIde
```

The Wrapper pins Gradle 9.1.0, which supports Java 25, and its distribution SHA-256; the IntelliJ Platform Gradle Plugin is 2.18.1. Initial builds download Gradle and dependencies. Omitting `localIdePath` downloads IDEA 2026.2.3. If Gradle runs on another supported JDK and cannot detect JDK 25, Foojay toolchain resolver 1.0.0 downloads it automatically; set `JAVA_HOME` to the IDE's JBR 25 to reuse the local runtime. On macOS / Linux use `sh ./gradlew`; the local PowerShell packaging script targets Windows.

Compile against local IDEA JARs. Prepare the TOML runtime dependencies once; the local script then works offline:

```powershell
.\gradlew.bat "-PlocalIdePath=$env:IDEA_HOME" prepareLocalDependencies
.\scripts\build-local.ps1 -IdeaHome $env:IDEA_HOME -JdkHome $env:JAVA_HOME
.\scripts\build-local.ps1 -IdeaHome $env:IDEA_HOME -JdkHome $env:JAVA_HOME -Test
.\scripts\build-local.ps1 -IdeaHome $env:IDEA_HOME -JdkHome $env:JAVA_HOME -PsiTest
```

First use `prepareLocalDependencies` to prepare tomlj and its runtime dependencies. Afterwards `-Test` runs protocol / HTTP checks without Gradle. Because 262 distributions do not bundle the complete test framework, `-PsiTest` delegates to Gradle `test` for isolated sandbox tests. Without `-JdkHome`, the script tries `JAVA_HOME`, then IDEA's `jbr`.

Use offline Gradle only after Gradle and dependencies are cached, or run a specific test class:

```powershell
.\gradlew.bat --gradle-user-home .gradle-user-home "-PlocalIdePath=$env:IDEA_HOME" test buildPlugin verifyPluginStructure --offline
.\gradlew.bat "-PlocalIdePath=$env:IDEA_HOME" test --tests dev.ideamcp.RunControlServiceTest
```

Distribution: `build/distributions/idea-symbol-mcp-<version>.zip`. Test report: `build/reports/tests/test/index.html`. Tests use isolated IDEA sandboxes; execution tests start and clean up their own disposable JVMs. Windows testing requires access to IDEA's native process-management libraries.

The [final 0.6.4 verification](docs/verification/0.6.4-query-performance.md) passed **87 JUnit tests, 94 protocol/HTTP assertions**, plugin build and structure checks. Two controlled direct runs and the HTTP multi-root comparison met the benefit threshold with complete results; single-root fallback showed no material overhead. Call hierarchy retains the official implementation. The [installed verification](docs/verification/0.6.4-installed.md) confirms production 0.6.4, working tools and complete matching results; single-root Git and call hierarchy show no substantial speedup.

On 2026-10-24, the full 0.6.3 regression passed **83 JUnit tests**, with **94 protocol assertions** and `buildPlugin verifyPluginStructure`, using **IDEA 2026.2.3 / IU-262.10968.63, JBR 25.0.4 and Gradle 9.1.0**. Coverage includes real JVM Run / Debug and logs, isolation between two disposable debug JVMs, threads/frames/locals, stale pause tokens, all three step actions and resume/pause, breakpoint lifecycle, log isolation/eviction, errors/warnings and unsaved fixes, static calls, and complete pagination of 481 references and 241 call sites. Tests use separate config, system and log directories and clean up their disposable JVMs, without modifying the user's IDE.

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
| `JavaCallTools` / `DiagnosticsTools` | Static call edges and background single-file error/warning analysis |
| `FileEnumeration` | Bounded, uncached complete directory enumeration with official-search fallback |
| `RunControlService` | Project-scoped state machine, process termination and debugger tracking |
| `RunLogService` / `RunLogBuffer` | Project execution-event capture, isolated instances and bounded log cursors |
| `DebugService` | EDT coordination, manager-thread JDI reads, suspension tokens, breakpoints and stepping |
| `TokenStore` / `*Action` | PasswordSafe credentials and IDEA menu actions |
| `protocol/` | Schemas, validation, JSON-RPC and HTTP authentication boundaries |

The listener is loopback-only, validates Host / Origin and requires Bearer authentication. It implements a stateless Streamable HTTP subset for `2025-03-26`, `2025-06-18` and `2025-11-25`: `initialize`, initialization notifications, `ping`, `tools/list` and `tools/call`. Responses use JSON; GET / DELETE return 405. There is no SSE push, resource API or protocol-level MCP task API; stdio clients use the separate bridge process. Restart IDs are an application-level tool convention.

POST requires `Content-Type: application/json` and `Accept: application/json, text/event-stream`, with a 1 MiB body limit. Version 0.6.8 targets local IDEA 2026.2.3; other releases, Remote Development and split frontend / backend setups remain unverified. Use the older 0.4.0 release for IDEA 2025.2.

| Symptom | Action |
| --- | --- |
| HTTP 401 | Check the token; copy current settings or save the client's existing token as a fixed value |
| Connection failure or changed port | Open an IDEA project and copy the actual URL again |
| Client exposes only older tools | Reload or restart the client; confirm the 21 native tools plus the available official catalog |
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
