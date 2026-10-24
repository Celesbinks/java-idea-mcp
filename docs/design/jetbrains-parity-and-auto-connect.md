# 0.6.0 设计：官方工具能力对齐与 IDE 自动连接

状态：设计先行，已按本文实现；安装后问题修复及验证见 [0.6.1 验证记录](../verification/0.6.1.md)。日期：2026-10-24。

## 1. 目标与设计决策

在当前 IDEA 2026.2.3（IU-262.10968.63）上，以实际发现的官方 JetBrains MCP **56 个工具**为覆盖基线，保留本项目 19 个原生工具。另增加 `get_tool_operation` / `cancel_tool_operation` 管理长操作，完整环境的预期目录为 **77 个工具**。这不是仅添加 56 个 schema：每一个新增入口都必须可调度到实际实现，并保留最终返回结果和错误语义。

用户的实现选择规则是：独立实现确实更快时使用独立实现，否则复用官方能力。因此采用混合架构，而不是承诺重新开发所有功能：

1. 保留本项目现有原生 PSI、Run/Debug 和日志实现及已有协议。
2. 新增官方入口默认通过 IDE 进程内适配器复用官方 MCP 的工具实现；不使用 shell 替代语义工具，也不额外绕行一个 HTTP 客户端。
3. 可选工具依赖缺失、官方禁用某工具或官方版本不兼容时，明确报告可用性；不把永久报错的占位方法计为完成。
4. 新的原生替代只有同时通过结果一致性、权限/取消行为和性能门槛，才能替换对应官方路径。未经对照验证，不宣称原生更快。

先前的本机基准仅证明既有请求在本项目、预热索引下的延迟：类查询 6/74 ms、定义查询 5/10 ms、调用者 9/160 ms（本项目/官方），诊断 311.5/313 ms。查询范围、结果结构存在差别，因此这些数据只能支持保留既有入口，不能直接证明同名官方工具的完整语义可以用现有方法替换。原始数据位于 `build/benchmarks/mcp-speed-20261024/`，不随源码发布。

## 2. 官方能力清单

基线来自当前安装的官方服务 `tools/list`，不是网页上可能变化的工具数量。新增入口保持官方名称、输入 schema、返回 content/structuredContent/isError；既有入口保持原协议兼容。

| 分组 | 官方工具 | 当前能力与补齐方式 |
| --- | --- | --- |
| 分析 | `analyze_calls`, `build_project`, `get_file_problems`, `get_project_dependencies`, `get_project_modules`, `lint_files` | 已有单层 Java 调用和单文件诊断，但不等于官方多语言调用树、构建和批量诊断；新增入口默认复用 |
| 文件、编辑器与修改 | `create_new_file`, `get_all_open_file_paths`, `list_directory_tree`, `open_file_in_editor`, `reformat_file`, `read_file`, `apply_patch`, `rename_refactoring` | 新增；保留官方项目、源码根、归档/反编译、撤销和重构行为 |
| 搜索与代码信息 | `search_file`, `search_regex`, `search_symbol`, `search_text`, `get_symbol_info` | 本项目已有精确 Java 符号/定义；官方入口仍需片段、多语言、文档和不同范围，默认复用 |
| 路由 | `execute_tool` | 新增；路由仍受工具目录、参数校验和目标工具策略约束，不能绕过禁用工具 |
| 运行与终端 | `execute_run_configuration`, `get_run_configurations`, `execute_terminal_command` | 保留原生精确 ID 异步重启；新增官方语义不改写为重启，不改变现有用户配置 |
| 调试 | `xdebug_control_session`, `xdebug_evaluate_expression`, `xdebug_get_debugger_status`, `xdebug_get_frame_values`, `xdebug_get_stack`, `xdebug_get_threads`, `xdebug_get_value_by_path`, `xdebug_list_breakpoints`, `xdebug_remove_breakpoint`, `xdebug_run_to_line`, `xdebug_set_breakpoint`, `xdebug_set_variable`, `xdebug_start_debugger_session` | 默认复用官方调试器提供方；保留原生 pauseId/JDI 无求值工具。求值、对象展开、变量写入属于新增的显式副作用入口，不混入原生只读变量工具 |
| 检查脚本与 PSI | `generate_inspection_kts_api`, `generate_inspection_kts_examples`, `generate_psi_tree`, `run_inspection_kts` | 新增；可用性取决于对应语言/检查脚本提供方，不伪造 Kotlin 执行结果 |
| VCS | `get_repositories`, `git_status` | 新增；复用 IDE VCS 映射及状态实现 |
| 数据库 | `cancel_sql_query`, `create_database_connection`, `edit_database_connection`, `execute_sql_query`, `fetch_query_result`, `get_database_object_description`, `introspect_schema`, `list_database_connections`, `list_database_schemas`, `list_recent_sql_queries`, `list_schema_object_kinds`, `list_schema_objects`, `preview_table_data`, `test_database_connection` | 新增；依赖 Database Tools and SQL、数据库驱动及许可。SQL 执行不按文本猜测为只读；不读取或输出数据源密码 |

数量校验：6 + 8 + 5 + 1 + 3 + 13 + 4 + 2 + 14 = 56。

## 3. 分层与调度

### 3.1 原生协议层

- `ToolCatalog` 维护原生 19 个工具及两个异步管理工具的静态 schema；官方 schema 来自当前提供方。
- `RpcHandler.Backend` 扩展默认目录与完整 MCP 结果接口，保持现有 lambda 后端和协议测试可用。
- 原生后端返回业务 JSON，协议层按原规则包装；官方适配器返回完整 MCP 工具结果，协议层不得再次包装，也不得把 `isError=true` 改成成功。
- `tools/list` 返回原生目录与当前可用扩展目录的去重并集；请求使用同一目录快照查找和校验，拒绝未知工具和未知参数。
- schema 校验增加数组、嵌套对象、nullable、枚举、数值边界和组合 schema；通过官方 schema 的输入也仍需服从实现约束。
- 不宣称支持尚未实现的 SSE、会话、MCP tasks 或事件推送。
- 构建、运行配置、终端、执行检查脚本、SQL 执行/连接测试/预览等长操作在独立工作任务中执行，立即返回 `operationId`；通过 `get_tool_operation` 获取最终完整 MCP 结果。`cancel_tool_operation` 仅请求取消任务，不宣称撤销已发生副作用，也不自动终止业务进程。这是与官方同步返回形态的显式差异，避免 HTTP 线程等待整个构建。异步提交延迟不能冒充完整任务的性能提升。

### 3.2 可选官方适配器

- 通过可选依赖 `com.intellij.mcpServer` 和独立插件描述文件加载，原生 19 个工具不能因为官方插件缺失而无法加载。
- 使用目标 SDK 中实际存在的工具描述、工具调用及注册/过滤接口；编译与沙盒测试锁定 262.10968.63。
- 核对官方项目解析、工具禁用、router-only 和确认上下文；不能调用绕过这些机制的裸工具实现。
- 直连与路由遵循 `McpToolFilterSettings.invocationMode`：`DIRECT` 使用官方 `DIRECT` 过滤模式（默认 `routerOnly=true` 不隐藏工具），`VIA_ROUTER` 才使用 `DIRECT_WITH_ROUTER_ENABLED` 隐藏仅路由入口。官方 `execute_tool` 必须附带真实的 `McpSessionHandler`，其两个提供方继续启用筛选；调用结束取消自身协程及关闭无传输 SDK 服务，不打开额外监听端口。
- 不自动打开 brave mode、不自动启用用户禁用的官方工具，也不启动未请求的运行配置。
- 数据库、终端、VCS、调试器、Kotlin 等工具的可用性来自实际启用的提供方。目录随提供方重建；当前无推送的客户端需重载目录。
- 不吞掉提供方异常，不记录完整参数、认证头、环境变量、业务日志、调试变量或数据库结果。

### 3.3 原生替代的性能门槛

同一输入、项目、源码版本、检索范围、返回完整性和副作用策略下，先两轮预热，再交替顺序各测至少 20 轮。验证中位数和 P95，并记录失败率和结果大小。

启用条件：结果及关键语义通过；无新增错误/取消/越权问题；中位数至少改善 20% 且绝对改善至少 2 ms，P95 不回退超过 10%。达不到则继续复用。字段删减、分页缩小、少返回调用位置、遗漏依赖或省略检查不算性能优化。已有原生接口保留，但不能仅凭其更小的工作量替换同名官方接口。

## 4. 自动连接

### 4.1 可验收的行为

1. 打开 IDE 项目后，现有后台启动逻辑自动启动本插件 HTTP 服务；手动 Stop 后打开另一个项目不会偷偷重启。
2. 提供 `Java MCP: Auto-Configure Clients` 操作，检测受支持的本机 MCP 客户端，并一次写入 `idea-java` 条目，不再要求用户复制令牌和 JSON。
3. 支持 Codex TOML、Claude Code / Cursor 的 `mcpServers` JSON、VS Code / Copilot 的 `servers` JSON，以及 Windsurf 的 `serverUrl`。Claude Desktop 使用独立 Java stdio→HTTP 桥接进程，令牌仅从其客户端环境传入，不放入启动参数。各客户端格式单独适配。
4. 只对用户通过该操作选中的客户端建立受管关系。后续服务启动、端口回退、令牌主动更新时，自动同步仍属于本插件的条目；不覆盖其他服务器、用户编辑过的冲突条目、未知字段或不属于本插件的配置。
5. 保留 PasswordSafe 服务名称、固定令牌和旧 HTTP 配置。凭据保存失败时不启动临时替代服务，不向客户端写入无效凭据。
6. 客户端未运行时，下次启动自动读取配置并连接 IDE。客户端已经运行时，仅说明需要它支持的 MCP 重载或重启；不承诺能热修改任意正在进行的聊天。

官方自动配置同样需要客户端重载/重启，不能把“写入配置成功”报告为“当前聊天已发现新工具”。

### 4.2 配置更新机制

- 文件操作在后台完成，使用同目录临时文件加原子替换；写入前验证完整 JSON/TOML。
- JSON / JSONC 按所属根节点合并，保留未知字段，重新排版会移除 JSON 注释；Codex TOML 只更新 `mcp_servers.idea-java` 所属表，保留其他内容。复杂的内联认证表不自动改写。
- 管理状态仅存客户端类型、配置位置、受管条目的摘要和启用标记，不在普通设置中另存明文令牌。
- 读取损坏配置、权限不足或摘要冲突时拒绝覆盖，给出具体路径和可重试原因；不打印配置全文。
- 多 IDE 实例使用项目级配置或明确的实例归属；全局 `idea-java` 条目只有其受管实例可以更新，禁止不同 IDE 来回覆盖。
- 只绑定回环地址；Host/Origin 校验、Bearer 认证和 1 MiB 请求上限保持不变。自动连接不以移除认证实现。
- 自动配置是一次性的本机客户端注册和后续受管条目维护，不会自动安装插件、启动 IDE、重启 Codex、停止业务进程或修改任意其他客户端。

## 5. 权限与行为边界

原生查询、原生无求值变量读取保持既有约束。新增表达式求值、变量修改、运行到行、带表达式的日志断点、运行配置、终端命令、检查脚本、文件变更和 SQL 入口按实际副作用标记，并要求调用方有明确用户请求；旧只读入口不获取这些能力。

官方运行配置查询若包含环境或启动参数，需要在本插件暴露面遵循既有不泄露约束；此处的脱敏差异必须在能力对照和 README 中列明。不能把“全覆盖”解释为取消本项目已有的凭据和业务数据保护。

客户端确认/elicitation 是适配器的关键验收项。若官方实现需要确认而当前连接不支持其交互，不得自行批准或切换 brave mode；返回明确的交互需求，不把操作记为已执行。

## 6. 验证与交付

| 检查层 | 必须覆盖 |
| --- | --- |
| 目录与协议 | 56 个基线名称逐一对应可用提供方；并集去重；未知参数、嵌套类型、完整错误透传；协议/HTTP 旧回归 |
| 适配器 | 真实沙盒中的目录发现、项目隔离、只读代表调用、禁用/缺失提供方、确认与取消、不重复包装结果 |
| 文件与构建 | 沙盒临时文件的读写、补丁、重命名、格式化、搜索和构建；验证撤销及项目外限制 |
| Run/Debug | 只操作临时 JVM；保留原 49 项回归；新增控制、求值和变量写入只使用测试探针；清理自建进程和断点 |
| SQL/检查脚本 | 仅临时测试数据源/测试脚本；缺驱动、许可或可选插件必须报告限制，不能假记通过 |
| 自动配置 | 每客户端的临时配置夹具；幂等、未知字段、冲突、损坏、原子写入、端口/令牌变化、多实例、关闭并发和内存凭据模式 |
| 性能 | 使用与官方相同任务的基准门槛；记录原生/复用选择依据，不以工具数量代替验收 |
| 包与客户端 | `test buildPlugin verifyPluginStructure`；ZIP 中版本/schema/描述文件一致；独立临时 Codex 客户端发现与实际调用 |

开发顺序：基线/文档 → 协议扩展与适配器 → 自动配置和生命周期 → 工具组集成测试 → 性能判定 → 中英文 README、版本及安装包。生产 IDE 的安装、已发现目录和源码构建版本分别报告。

未通过实际调用的工具标记为未验收；存在许可/驱动限制时区分“已实现适配入口”和“已通过功能验收”，不笼统宣称 77 个工具均已验证。

## 7. 依据

- 当前 IDEA 安装：2026.2.3 / IU-262.10968.63；当前官方目录快照：56 个工具。
- [JetBrains 官方 MCP 设置与工具说明](https://www.jetbrains.com/help/idea/mcp-server.html)：客户端自动配置、提供方依赖、工具筛选和确认设置。
- [JetBrains MCP 扩展开发说明](https://github.com/JetBrains/intellij-community/blob/master/plugins/mcp-server/README.md)：工具描述、项目/协程上下文、过滤、线程和可选提供方。
- IDE 本机 JAR 的签名核对优先于 master 分支推断；使用的 API 必须在目标 262 SDK 上编译并验证。

## English summary

Version 0.6.0 targets the 56 tools discovered from the installed JetBrains MCP, alongside the existing 19 native tools. The selected architecture is hybrid: retain existing native implementations; reuse official providers for added capabilities; enable a new native replacement only after equivalent-result tests and a measured latency improvement. Optional providers and licensing remain real dependencies, not stubbed successes.

Automatic connection means IDE startup plus one-time client auto-configuration and later maintenance of explicitly managed entries. Credentials remain backed by PasswordSafe, unrelated client configuration is preserved, and an already running client may still require reload. Verification uses isolated IDE sandboxes, disposable JVMs and temporary client/database fixtures. Every implementation and validation limitation must be reported separately.
