# 文件枚举优化设计（0.6.2）

设计先于实现；目标平台固定为 IDEA 2026.2.3 / IU-262.10968.63。

## 问题与依据

0.6.1 同条件测量中，`search_file` 查询 `**/*.java`，`paths=[src/main/java/**,src/test/java/**]`、`includeExcluded=false`、`limit=1000`，完整返回 46 个文件：idea-java 中位数 184 ms，官方 183 ms。直接复用没有性能收益。安装目录 `mcpserver.jar` 的 `SearchToolsetKt.searchFiles` 字节码显示：可索引文件名模式先经过 `processIndexedFileNameMatches`，其文件名候选仍来自项目范围，再做路径匹配。多个目录的共同祖先不能充分缩小该阶段的候选。

## 方案与保持不变的语义

保留官方工具名称、schema、元数据、动态禁用及直连/路由筛选。仅在已经通过官方可用性检查和参数校验后，尝试以下可证明覆盖全部候选的快速路径：

1. `includeExcluded=false`；查询归一化后为 `**/文件名模式`，没有额外目录段；至少一个正向 paths，且每个正向 paths 均为不含通配符、绝对路径、`.`、`..` 的明确目录加 `/**`。负向 paths 继续交给官方匹配器。其他形式全部复用官方。
2. 使用当前 VFS 和 `ProjectFileIndex.iterateContentUnderDirectory` 分别枚举这些目录，去重重叠目录与候选。只访问项目内容，沿用索引的排除规则；不使用磁盘全文扫描、rg 或 PSI/AST 加载，也不保存编辑器文件。
3. 复用官方 `getProjectDirectory`、`normalizeGlobPattern`、`buildPathScope`、平台 `GlobUtilKt.getPathMatcher`，对每个候选重新执行项目目录边界、q 和 paths 匹配。不另写 glob 方言。目录无法可靠解析、索引中、符号链接入口等情况回退。
4. 仅在完整枚举结束且结果数**严格小于**官方归一化 limit 时返回。达到 limit（包括刚好相等）、内部枚举预算耗尽时丢弃临时结果，调用原实现；保留原有前 N 项、`more`、部分结果与错误行为。完整结果没有排序契约，验证比较路径集合并检查重复；不会以排序或缩小返回量冒充加速。
5. 不缓存文件列表：新增、删除、重命名、根目录及排除规则变化立即由当前 VFS/索引决定。使用可取消、写优先的非阻塞读操作，项目关闭失效；快速枚举预算 200 ms，整个调用仍共享 20 秒上限。取消不作为普通失败吞掉。

官方 search_file 本身只接受官方项目目录下的候选；项目目录外模块根的原生 Java PSI 查询规则保持有效，不借本次优化改变任何工具的范围。`includeExcluded=true`、无明确目录、复杂正向 glob 等查询继续原样复用。`execute_tool` 继续由官方会话执行，路由调用不启用此优化；避免改变官方路由与确认机制。

## 接入与测试

新增独立 `FileEnumeration`，由可选 `OfficialMcpAdapter` 调用；不影响缺少官方插件时的原生工具加载。保留包内仅供测试选择原实现的构造入口，以便同一沙盒、同一项目、同一输入比较优化和原实现。HTTP/RPC 仍走现有实现，返回完整 MCP 内容及 structuredContent，字段与官方序列化保持一致。

沙盒差分测试覆盖多目录、交集/排除、重叠目录、空结果、大小写与 Unicode、排除文件、达到/超出 limit、复杂模式回退、项目边界、文件变更、禁用和路由；比较完整集合和部分结果元数据。索引、取消、写操作与预算退出单独检查，不生成虚假的完整结果。

## 速度复测与验收

同一独立 IDE 沙盒中使用本项目源码的冻结副本，保持原测 q、paths、includeExcluded、limit；分别运行优化适配器、未优化官方适配器和不使用 MCP 的 rg。两轮预热、至少 20 轮，三种顺序交替，逐轮核对完整路径集合、无重复和无部分标记。记录请求层耗时与进程启动/查询体耗时，不能混成同一计时层；不使用历史生产 IDE 数字作为当前沙盒的直接对照。复用与优化使用相同 HTTP/RPC 链路；官方列指未优化的真实官方提供方，不声称测试沙盒为用户当前 Codex 已安装版本。

启用门槛延续能力对齐设计：中位数改善至少 20% 且至少 2 ms，P95 回退不超过 10%，所有结果一致。若当前语料不足以证明，扩大无关文件/目录的沙盒夹具并同时报告真实源码数据，不更换结果范围掩盖问题；未通过则不启用。

最终运行完整独立沙盒回归、协议/HTTP 自检、`buildPlugin verifyPluginStructure`、独立临时 Codex 的发现与调用，发布 0.6.2 ZIP 与校验摘要。README 中英文同步，分别报告源码版本、用户当前安装的 0.6.1 和新构建包。

线程与读取方式依据 [JetBrains Threading Model](https://plugins.jetbrains.com/docs/intellij/threading-model.html)；避免加载 AST 的依据见 [PSI Performance](https://plugins.jetbrains.com/docs/intellij/psi-performance.html)。实际 glob 和枚举行为以固定 262 SDK 和差分测试为准。

## 实施结果

已按此方案实现并启用：同一沙盒 48 个文件的完整结果全部一致，中位数从 15.73 ms 降为 3.34 ms，P95 从 19.68 ms 降为 4.02 ms，达到启用门槛。78 项沙盒测试、94 项协议断言及插件结构校验通过；安装包和边界见[0.6.2 验证记录](../verification/0.6.2-file-enumeration.md)。这些数值比较的是相同 HTTP/RPC 外壳中的新旧后端，不代表生产 Codex 的端到端耗时。

## English summary

Version 0.6.2 adds a conservative fast path for complete filename-glob searches constrained to explicit project directories. It uses the IDE content index and the installed official glob/path helpers, without caching file lists. Unsupported scopes, excluded-file searches, truncation and budget exhaustion delegate to the original provider. Official filtering remains authoritative; routed calls retain the original implementation. Equivalence, performance and packaging are verified in an isolated IDE sandbox with identical inputs and complete result sets.
