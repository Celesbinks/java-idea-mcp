# 其他只读工具的性能设计（0.6.3）

日期：2026-10-24。先做等价基线和 SDK 检查，再依据本文开发。目标固定 IDEA 262.10968.63，保持全部 schema、结果范围、动态权限与取消语义。

## 1. 已测基线与选择

当前已安装 0.6.2。相同项目、输入、完整结果，两轮预热后各 20 轮，轮换任务及两种提供方顺序；12 项任务共 480 个正式样本，全部结果一致。数组仅为集合比较排序，不删除字段或命中。含源码中的真实查询结果，不读取业务数据。

| 任务 | idea-java 中位数/P95 ms | 官方中位数/P95 ms |
| --- | ---: | ---: |
| 完整读取 Arguments.java | 7.5 / 12 | 8 / 15 |
| TokenStore 字面量搜索，全 Java 源目录 | 14 / 21 | 15.5 / 19 |
| class 正则搜索，全 Java 源目录 | 23 / 28 | 22 / 25 |
| TokenStore 语义片段搜索，全 Java 源目录 | 73 / 88 | 82 / 92 |
| src 完整目录树，深度 100 | 8 / 9 | 8 / 13 |
| 全部项目依赖 | 13 / 17 | 12 / 18 |
| 全部模块 | 6 / 9 | 6 / 8 |
| 全部 VCS 根 | 6 / 7 | 5 / 14 |
| 全部 Git 状态（不含 ignored，含 untracked） | 68.5 / 121 | 60.5 / 74 |
| Arguments 类的 Quick Documentation | 8 / 10 | 11 / 12 |
| RunLogBuffer 全部错误/警告 | 311.5 / 314 | 313 / 324 |
| 两文件 lint_files | 616.5 / 627 | 616 / 622 |

原始输入、完整结果、528 次含预热校验、样本及计时函数在忽略目录 `build/benchmarks/063-readonly-baseline/`。诊断样本目前为干净文件；lint_files 两边均返回 items=[]，不能据此宣称已证明存在问题的文件也能提速，后续沙盒须有非空诊断夹具。调用树另用同一明确的一层 incoming 范围补充核对，不能将原生调用位置与官方树节点直接混为同一结果。

SDK 字节码检查发现：官方批量 lint 使用项目级 mainPassesMutex，真实检查还涉及共享 Daemon 设置和写操作协调；不会通过并发绕过该互斥或缩短检查等待来换取速度。符号搜索已使用官方 ChooseByName 与路径作用域；Git 状态含真实进程/仓库结果，不能用陈旧缓存取代。上述功能本轮保留官方算法。

## 2. 优先原型：按名称查 schema，减少全目录转换

当前扩展调用会在 RpcHandler 与 IdeaToolBackend 两次构造完整工具 JSON 目录，OfficialMcpAdapter 又重新查可用工具和生成目标 schema。官方目录含 56 项，查询一个小工具却重复把全部描述、schema、注解转换成 Gson JSON。既定基线认为这不是全部耗时的主要来源，但对于 5～15 ms 的短查询，仍值得以同层对照验证。

引入单个工具描述查询：

1. `RpcHandler.Backend.tool(name)` 默认通过 tools() 查找，保持旧 lambda 后端与第三方实现兼容；原生查询保留已有原生目录路径。
2. `PlatformToolProvider.tool(name)` 同样提供兼容的默认实现；OfficialMcpAdapter 覆盖它，先重新获取真实官方筛选后的目录，再仅转换目标描述。
3. IdeaToolBackend 覆盖单名称查询；分派时也仅查目标描述，不将全部扩展工具转为 JSON。tools/list 仍返回原有完整、去重的并集。
4. 不新增跨请求缓存、ThreadLocal、目录版本猜测或预授权句柄。协议校验、分派与实际执行仍分别检查当前可用性；工具禁用、路由模式、动态提供方变更和执行确认立即生效。执行前仍用当前实际 schema 校验。
5. 所有后端算法、完整结果、原生语义、长操作提交/轮询、错误、认证与线程调度保持原样。不会通过省略结果字段、减少分析范围或缓存空诊断取得收益。

## 3. 验证与启用门槛

第一版单名称描述查询的六项沙盒结果全部一致，但收益尚未达到双重门槛：短查询约省 1.5～1.8 ms，符号搜索省 2.68 ms 但只改善约 17.8%。原始记录保存在 `single-lookup-prototype.json`，不能把这版宣称为已通过。下一步在同一请求内传递描述与提供方的调用闭包，合并协议查找和分派查找；执行时仍由提供方重新获取实时可用工具，保留第二次权限/schema 校验。闭包不跨请求缓存，长操作在工作线程执行时再次检查提供方仍注册和当前工具可用，不能复用旧授权。第三方提供方的默认闭包保留额外实时查找，官方闭包使用既有 call 的当前筛选校验。测试补充查找后禁用、路由变化及提供方卸载，基线仍为完整原流程。

请求内分派原型在第二次小范围测试中符号搜索达到门槛，但完整回归中单项绝对收益又落到约 0.5～1.7 ms。因此不以某一次较有利的结果证明单项已稳定达标。补充预先定义的“项目概览”真实组合任务：并发查询全部模块、全部依赖、完整 Arguments.java 和完整 src 树，四个结果都返回且逐项校验，衡量同配置 HTTP 服务在相同四请求负载下的实际完成时间。该任务两次预热、20 次测量；不将四个单请求中位数相加当成端到端耗时。如果组合任务同样达不到 20% / 2 ms / P95 门槛，则撤回默认优化；单项未达到的部分明确保留限制，不声称诊断或 Git 算法已加速。

先在相同独立沙盒与 HTTP/RPC 层比较原 0.6.2 调用流程和新流程（原目录和原调用算法不变）。保留原流程测试对照，统计目录完整转换次数及描述一致性，确认确实去除了重复工作。短查询选取模块、依赖、文件读取、文档、符号搜索和目录树；先预热两轮，再至少 20 轮交替测量。返回全字段，分别检查集合与响应大小，不把提交 accepted 当成任务完成。

只有中位数改善至少 20% 且至少 2 ms、P95 回退不超过 10%、结果全部一致时，才保留默认优化；达不到则撤回原型，保留官方复用并如实记录。不能拿生产 Codex 的计时与沙盒 HTTP 数值混算改善率。优化后的生产 Codex 耗时需用户安装并重载后另外复验。

沙盒验证目录 73 项（四项 Ultimate/Qodana 限制不变）、官方禁用与 router-only、请求准备后设置变化、完整 schema/注解一致、非法参数、真实路由与长操作最终结果、真实非空诊断、未保存内容及独立 Codex 查询。运行完整回归与协议/HTTP 自检，构建并校验 0.6.3 ZIP；不启动用户服务、不执行 SQL、不读取业务日志/变量，不修改用户 IDE 安装。

线程规则参考 [JetBrains Threading Model](https://plugins.jetbrains.com/docs/intellij/threading-model.html)，具体 API 与 mutex 以本机固定 SDK 为准。

## 实施验收

最终保留请求内公共分派优化，不替换任何官方工具算法。含真实依赖的同层项目概览中位数 6.78235 → 4.49125 ms（减少 2.2911 ms / 33.8%），P95 9.6494 → 4.8194 ms，四份完整响应大小相同。单项收益仍小于 2 ms，按实测报告，不据此宣传单项替代达标。83 项测试、94 项协议断言及结构验证通过；完整数值、边界与安装包见[0.6.3 验证记录](../verification/0.6.3-readonly-performance.md)。

安装后审计更正了单项基准的顺序轮换耦合：历史十二项生产基线及六项沙盒单项只作探索数据；项目概览及目录树尾延迟的独立轮换不受影响。新的公平交替生产复测中项目概览为 13 对 14 ms，收益约 7.1%，见[安装后记录](../verification/0.6.3-installed.md)。

## English summary

The equal-result production baseline covers twelve read-only tools. Expensive diagnostics, symbol search and Git operations retain their official algorithms; diagnostics must not bypass the project's main-passes mutex. The proposed optimization resolves and converts only the requested tool descriptor instead of repeatedly converting the entire catalog. It uses fresh official filtering at each existing validation/dispatch/execution stage, with no cross-request cache or preauthorization. The implementation will be retained only after controlled same-layer benchmarks meet the existing 20% / 2 ms median and P95 gate, complete-result comparisons and isolated regression/client tests.
