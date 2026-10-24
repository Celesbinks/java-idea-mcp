# 其他只读工具第二轮性能设计（0.6.7）

日期：2026-10-24。目标 IDEA 2026.2.3 / 262.10968.63，生产已安装 0.6.6。
先筛查、设计，再开发；GitStatusBatch、Git 调度和状态语义保持现状。

## 基线与边界

使用配置中真实的 idea-java 与官方 JetBrains 端点，同一个原生 HTTP 客户端，
复用连接并维护官方事件流，计时至完整 JSON/SSE 接收和解析完成，断言在计时外。
两轮，每项每方独立预热 2 次、正式 20 次，按轮交替双方顺序，第二轮反转顺序。
不把宿主/HTTP/同层测试混算成优化比例；生产仍为旧版本，候选仅在独立沙盒运行。

覆盖 18 项任务：短文件和较长测试文件完整读取，完整 Java 目录的字面量、正则、
语义符号和文件名搜索，完整 src 树，全部依赖/模块/VCS 根，Quick Documentation，
单文件错误/警告、两文件 lint、完整一层 incoming 调用树、Java PSI 树、
Inspection KTS API/示例与示例文件运行入口。只读筛查不操作真实服务、断点、数据库或终端。
诊断性能结论须区分干净生产文件与非空错误/警告沙盒夹具。

比较整个 MCP 结果，保留全部字段、文本及错误，拒绝截断/超时/未分析。
探索发现官方搜索在相邻调用间的命中顺序会变化；仅对四个 search 工具的完整 items
集合按全字段排序，并同步规范化 content 中相同的 JSON；不删命中或字段。
其余数组、目录树文本、读取文本、调用树与文档顺序均保持原样。
第一次严格顺序检查的搜索不一致单独保留为探索失败，不混入正式轮次。

首轮探索显示诊断约 307 ms、双文件 lint 约 618 ms、调用树约 686 ms、符号约 63 ms；
两端相近。短读取、模块等已为约 1～3 ms，继续改公共分派的绝对收益有限。
不绕过诊断 mainPassesMutex、不缩短等待、不移除检查或调用树节点；符号继续使用索引。

## 候选：目录树的重复文件属性查询

本机固定 SDK 字节码表明 renderDirectoryTree 对每个节点用 File.isDirectory 两次，
File.listFiles 只返回 File，再按 name.toLowerCase(Locale.ROOT) 稳定排序，递归输出。
它枚举真实磁盘目录，不按 VFS/索引排除内容。以 VFS 替换会遗漏尚未刷新或排除文件，
因此不采用 VFS 缓存或项目内容过滤来加速。

试验 NIO DirectoryStream + 每项一次 BasicFileAttributes，复用目录枚举项的属性，
缓存仅存在于本次遍历。根路径解析继续使用官方 resolveInProject；保持大小写排序、
前缀缩进、根绝对路径、目录斜线、深度定义、traversedDirectory 与所有结果字段。
不隐藏 excluded、ignored、点文件或生成文件，不增加输出上限或删去任何节点。

只接受能完整遍历的本地普通目录/文件；遇到符号链接、junction/其他类型、名称排序键
冲突、IO/访问异常、预算耗尽或不支持参数时，丢弃候选结果并走真实官方提供方。
maxDepth<=1 的平凡输出直接走官方；递归抵达深度边界后只确认目录访问，
不读取或排序不属于请求结果的下一层节点属性，保持相同的深度输出。
实际取消/中断不吞掉、不回退为成功。限制候选预算不超过请求余量和工具 timeout，
保留官方 20 秒外层等待；复杂范围与错误、超时结果始终由官方生成。
目录在读取前后检查基本身份和修改时间，变化则回退，避免候选把不完整内容当成功。
不持有 IDE 读锁进行磁盘遍历，不阻止写操作，不修改磁盘或强制保存编辑内容。

入口必须在真实官方提供方查找、动态禁用/路由与 schema 校验之后，且仅当
fullyQualifiedName 精确匹配固定 SDK 的官方目录工具时启用。替换提供方仍执行其自身实现。
工具目录、schema、确认设置、自动连接及长操作行为不改变。

## 收益门槛与验收

首次原型的大树两轮直调/HTTP 中位数减少约 44%，但 src 小树回退约 3 ms，
因此没有直接启用。收紧为根目录至少 32 个直接子项才尝试 NIO；用 DirectoryStream
仅读名称，达到阈值即关闭，未达到则走官方。它只决定是否采用候选，不删任何结果；
小树、瘦根下的大树仍完整使用官方实现。重新复测这一最终入口，保留首次失败数据。

先在同一个独立 IDEA 沙盒比较官方基线和候选。复制真实项目 src 小树，并建立
多层、大量普通文件的大树；两边返回相同的完整树，不用小树与大树互相比。
再以同一个 HTTP/RPC 包装比较双方，并增加官方独立 MCP 端点对照（若沙盒可启动）。
每条件每方 3 次预热、至少 40 次正式样本，两轮独立预热且平衡先后；不删离群值。
中位数至少减少 20% 且 2 ms，P95 不回退超过 10%，两轮成立才默认保留。
小树及回退范围单独报告，不用大树收益宣称所有目录都提速。未达门槛则撤回候选。

收紧后的 40 对探索中，大树两轮直调/HTTP 均达标，小树中位数差约 0.2 ms，
最大 P95 回退约 8.2%。最终发布回归提高到每方 80 次、各先 40 次，以核实小树尾部；
使用 -PdirectoryBenchmark=true 单独启用目录基准，避免顺带重跑 Git 性能实验。

80 对复核中，大树仍约快 44%，但小树第一轮 P95 多出约 0.7 ms（12%～13%），
没有作为最终通过数据。补充公开 VFS 的负向资格检查：在可立即取得的短读动作中，
只读已缓存、已加载的根子项数；不足 32 就直接走新鲜官方磁盘遍历，省去磁盘资格探测。
未取得读锁或缓存不完整则继续原 NIO 探测。陈旧的“瘦根”只会少启用优化，
不会把 VFS 当结果或丢掉未刷新磁盘节点；增加真实未刷新文件的反例测试。
两端基准共享相同已加载的 src 根缓存，大树磁盘夹具仍无需 VFS 刷新；再次完整复核。

差分测试覆盖深度 0/1/2/默认/完整、Unicode/emoji/大小写、空目录、点文件、
excluded 和未刷新磁盘文件、增删改名、缺失/文件路径、父路径和外部范围回退、
链接/junction、预算与取消、真实官方工具禁用/移除/提供方变化。
目录正文和 structuredContent 必须全对象一致，不只是节点计数。

完成针对性回归、完整独立沙盒测试、协议/HTTP 自检、buildPlugin 和
verifyPluginStructure；0.6.7 同步版本及中英文 README，生成 ZIP 与 SHA-256。
记录源码/安装包版本 0.6.7、生产已安装版本 0.6.6 和当前目录 77 项的边界。
不安装或重启生产 IDE；安装后的实际生产收益留待安装后单独复验。

## English

Keep Git unchanged. Screen eighteen equal-result read-only tasks against the actual official
endpoint before implementing a conservative directory-tree prototype. The candidate uses fresh
NIO enumeration and request-local attributes to avoid repeated filesystem metadata calls; it
preserves the entire disk tree, exact rendering, dynamic tool filtering and official fallback.
Retain it only after two balanced isolated runs meet the 20% / 2 ms median gate without a
greater than 10% P95 regression, with complete-result differential tests and release validation.

## 实施验收

保留目录候选及已缓存瘦根的负向选择，不修改 Git。最终每方 80 次，两轮直调/HTTP
完整 4096 文件树均达标；HTTP 中位数减少 40.6%～43.5%，P95 减少 40.1%～43.7%。
相同热 VFS 小树中位数差不超过 0.125 ms，P95 最大回退约 2.6%；冷根资格探测成本
单独保留限制，不宣称所有小目录都提速。1328 次完整树比较、107 项 JUnit、94 项
协议/HTTP 自检、独立临时 Codex 与构建/结构校验通过。
完整生产筛查、失败原型、最终表格和安装包见[0.6.7 验证记录](../verification/0.6.7-readonly-performance.md)。
