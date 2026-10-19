# 平台实现独立复审

## 2026-10-19：上传、队列、产物与下载

复审者：`ole_caption_parser_fix`。以当前 `platform-contract.md` 为契约；代码所有者修复提交/引擎/存储，本 Agent 仅修改其自有 common/resources 的异步配置与对应测试。此记录不代表真实 COS、Docker 或浏览器验收已经通过。

### 发现及处置

| 发现 | 影响 | 处置 |
| --- | --- | --- |
| 产物仅检查 `<w:document>/<w:body>`，FAIL+WRITTEN 对应空正文、无 comments 的文件仍能通过；原正向测试就是此形状 | P1：空白或未批注文件可成为 SUCCESS | 已交引擎 Agent；新增 MarkedDocumentValidator，独立校验原文非空内容未被清空、实际 anchor part 中有新增非空批注引用、有效 comments 关系；复制原文的零 FAIL 分支也检查 DOCX。合并批注不能按 FAIL 数量硬等于 comment 数量 |
| 下载在返回 StreamingResponseBody 前就打开 COS 流 | P1：线程池拒绝或排队超时不会运行 body，流无法由 body finally 关闭 | 提交 Agent 改为请求阶段鉴权并生成计划，异步执行时才打开流；测试断言 prepare 后 reads=0 |
| 上述延迟执行可能让计划在排队时过期：导师解绑、禁用或改密后仍能开流 | P1：延迟读取使用旧授权 | 已核对最终实现：DownloadPlan 构造私有，开流前重新检查角色、导师关系、状态、密码指纹；提交 Agent 的 18 项真实 PG 测试覆盖 prepare→变更→open 拒绝且不访问存储 |
| 未明确 MVC 异步超时，默认执行器线程/队列没有业务上限 | P2：大文件慢下载超时，多人下载占用无界队列 | 本 Agent 复用 Boot applicationTaskExecutor：8 工作线程、16 排队、330 秒 MVC 总超时；饱和 503，中文重试提示。代理超时主 Agent 同步调整 |
| 线程池拒绝后，响应已预设 DOCX Content-Type，异常处理器直接写 JSON 会再次失败 | P2：真实 HTTP 返回 500，丢失可读错误 | 嵌入式 Tomcat 复现，改为显式 JSON Content-Type 并清除未提交的下载文件头 |
| ASYNC redispatch 跳过 RequestIdFilter | P2：错误响应 requestId 为 null，无法关联原响应头 | 真实 HTTP 复现；请求属性保存同一个 ID，async/error dispatch 重建 MDC，响应头和错误 envelope 保持一致 |
| 15 分钟租约小于引擎 10 分钟加三次存储 5 分钟预算 | P2：合法慢处理可能反复被租约拒绝 | 提交 Agent 改默认 30 分钟，启动要求大于引擎预算+16分钟；COS 完整读取有5分钟到期 abort，SDK内部重试关闭 |
| COS 原始异常消息及 cause 可能含签名URL | P2：日志泄露 | 存储 Agent 在 adapter 仅保留异常类型、安全 requestId、状态码，丢弃原始消息与 cause，流错误同样处理 |
| 所有 4xx 都被解释为论文损坏 | P2：引擎401/403/404配置或路由错误错误归因论文 | 引擎 Agent 改为400/413/422作为输入永久错误，其他服务4xx使用通用服务异常与有界重试 |

### 未发现冲突的路径

1. **持久上传预留**：COS 上传前先提交 PENDING 记录并设置10分钟预留，预留期内队列不可领取、原件不可下载。成功后释放；释放提交失败保留对象和记录，预留到期可恢复。上传失败只有 `failUpload` 条件更新确实成功才删目录；已被 worker 接管的记录不会被迟到上传失败误删。
2. **成功文件保护**：队列成功/失败均匹配 PROCESSING 和 attempts；SUCCESS 是终态。旧尝试只能清理其自身前缀。提交成功但确认丢失时重新读取持久状态，SUCCESS 当前尝试不会清理；读取数据库失败时保留文件等待补偿。补偿扫描只删 FAILED 全目录或严格旧于当前尝试的目录。
3. **50MiB**：业务入口检查文件大小，Spring multipart 文件50MiB/请求52MiB，Nginx52MiB，引擎上传限制52428800字节一致。worker 从COS取原件时也对实际读取字节限额。产物允许因批注增长到100MiB，是输出限制而非输入绕过。
4. **批注覆盖**：适配器按旧引擎 AnnotationFindings 的身份去重，逐项核对 FAIL 的 WRITTEN 记录；同段合并错误不要求一条错误一个 comment。缺写、未写或外部下载地址均拒绝成功；新 validator 再独立查看文件证据。
5. **下载权限**：HTTP角色限制及 service 内当前数据库关系双重校验；管理员不得访问论文，教师只访问当前名下学生，学生仅本人。对象键由库内 storageDir 和当前 attempt 构造，不接收用户提供的COS地址。流在异步任务内 try-with-resources 关闭。
6. **ASYNC 安全链**：没有把 ASYNC dispatcher 全部放行；初次请求正常走身份/角色校验，延迟开流再次校验授权，Spring Security 为 MVC Callable 传递上下文。正在传输时无法收回已发送字节，撤销保证后续开流与新请求受阻。
7. **两张业务表**：迁移仅 sys_user/submission，外键RESTRICT，没有额外导入/租约/审计业务表。租约使用既有 started_at/attempts，上传预留使用 updated_at，契约已记录。
8. **无伪统计**：当前 byScope=null 是已确认的契约；原引擎未提供可靠中文章节聚合，不把规则模块冒充论文章节。问题总数仍来自真实 FAIL。

### 验证证据与边界

- 账号域证据见 `evidence-accounts.md`，包括真实 PostgreSQL 并发覆盖 RED→GREEN。
- 异步边界测试 `DownloadAsyncConfigurationTest` 放在 `cn.luwneshacha.platformtests`，位于生产组件扫描包外，避免测试配置关闭真实PG用例的自动配置。
- 异步 RED 已实际观测：未配置的池 maxSize 为2147483647；满载返回DOCX Content-Type导致HTTP500；调整错误输出后 requestId 为null。均对应明确断言，不放宽断言通过。
- 最新合并验证：`rtk mvn -q '-Dtest=DownloadAsyncConfigurationTest,PasswordHasherTest,AccountSecurityIntegrationTest,ExcelUserFileTest' test`，退出码 0；19 项通过，0 失败、0 错误、0 跳过。日志 `platform-backend/target/accounts-async-verification.log`。异步饱和使用真实嵌入式 Tomcat/Java HTTP Client，确认 503、JSON、中文错误和 requestId 一致；330 秒超时配置与 8+16 执行器边界有断言。
- 提交/引擎 Agent 独立报告 33 项定向测试通过（18 PG、13 HTTP、2 COS adapter）；此复审核对了相应代码，最终全量 Maven、真实COS与浏览器验收由主 Agent统一记录。
