# 提交队列与对象存储审计

- 日期：2026-10-19；审计者：`audit_queue_storage`。
- 模式：只读生产代码与既有测试。未修复、未运行 Maven、未写入 `platform-backend/target`，未访问真实 COS 或生产系统。
- 范围：`platform-backend/submission`、`storage`，对应 PostgreSQL/存储测试，以及下载控制器、认证过滤器、异步配置边界。
- 契约来源：`.trellis/spec/platform-contract.md`、`.trellis/spec/backend/platform-reliability.md`、本任务 PRD/需求文档及旧复审记录。
- 结论：**3 项新增确认问题：P1 1 项、P2 2 项**。旧复审中已修复的上传预留、attempt fencing、同 attempt SUCCESS 清理、下载排队后重授权，不重复列为问题。

## QS-01 · P1 · COS SDK 自行记录完整签名请求，外层异常脱敏没有覆盖

**状态：已确认，真实本地 HTTP 复现。**

### 精确位置

- `platform-backend/src/main/java/cn/luwneshacha/platform/storage/CosObjectStorage.java:23-32`：创建 SDK，未限制其携带请求内容的错误日志。
- 同文件 `:39-40`、`:45-47`、`:82-86`：调用 SDK 后才在 catch 中转换异常。
- 同文件 `:88-96`：只处理返回给调用方的 exception/cause。
- `platform-backend/src/main/resources/application.yml:52-56`：未抑制 SDK 原始请求日志。

### 触发与数据流

普通 COS 请求出现 HTTP 503 或请求超时 → `cos_api:5.6.227` 的 `DefaultCosHttpClient` 先以 ERROR 记录 `httpRequest` → 日志内包括 endpoint、对象路径、HTTP 方法和完整 `Authorization`，包含 `q-ak`、签名有效时间和 `q-signature` → 异常随后才到达 `CosObjectStorage.failure`。

当前 adapter 最终抛出的 IOException 确实已脱敏，但 SDK 已提前输出另一条不受该逻辑控制的日志。此项是原“外层异常脱敏”修复没有覆盖的实际日志路径，并非重复报告同一个 catch。

### 影响

日志读者取得的是仍有效的请求签名，而不是永久 SecretKey；可在签名期限内尝试重放被签名的方法/对象请求。对 PUT，日志还给出对象键和已签名的请求头；不能因为桶 ACL 为 private 就认为日志内签名无用。探针中的 SDK 默认签名窗口为 3600 秒。未对真实 COS 尝试重放。

### 证明

`artifacts/platform-audit/QueueStorageAuditProbe.java` 使用真实生产 `CosObjectStorage`、真实 SDK、本地 HTTP 服务、明确的无效测试身份。分别制造请求头超时和 PUT 的 503 XML 响应。捕获结果：

```text
SDK_ERROR_SIGNATURE_LEAK signedHeaderCount=2 fakeAccessKeyAndSignatureSeen=true
```

脚本在内存中断言 SDK 日志确实含测试 access key 与非空签名，再把整个 Authorization 从持久日志和终端输出中替换为 `[REDACTED: signed request header]`。真实凭据没有被读取或输出。

既有 `CosObjectStorageTest.java:12-20` 只手工调用 `failure`，不经过 `COSClient`，所以无法发现 SDK 自行输出日志。修复方向是从 SDK 日志入口阻止原始请求/签名落盘，再由 adapter 保留脱敏后的必要诊断，而不是仅继续清洗最终异常。

## QS-02 · P2 · SDK requestTimeout 不约束 XML 响应正文，总 COS 预算仍可被突破

**状态：已确认，真实本地 HTTP 复现；未使用真实 COS。**

### 精确位置

- `CosObjectStorage.java:26-30`：设置 socket 空闲时限与 SDK `requestTimeout`。
- `CosObjectStorage.java:34-40`：PUT 完全依赖 SDK 时限。
- `CosObjectStorage.java:74-86`：列举/删除也完全依赖 SDK 时限，且每次请求重新计时，没有整个清理操作的截止时间。
- 下载成功正文的包装器位于 `:49-72`；它不覆盖 PUT 错误正文或 listObjects 的 XML 正文。

### 触发与数据流

COS/中间网络及时返回响应头，然后以间隔小于 60 秒的速度缓慢传输 XML 正文。真实 SDK 的 `TimeOutCosHttpClient.executeOneRequest` 对 `Future.get(requestTimeout)` 计时，但该 future 只负责获取 HTTP response；列表 XML 与错误 XML 的解析在之后执行。只要后续每次读取没有触发 socket 空闲超时，方法可以持续超过 5 分钟。

本地 SDK 字节码核对确认这一调用边界；探针进一步证明不是 timeout 配置未生效。

### 影响

- `put` 失败响应可以让上传请求、处理中的审核任务超出既定存储预算。
- `listObjects` 可无限延长失败补偿阶段；结合 QS-03 阻止本实例继续领取和恢复其他任务。
- worker 的“引擎 timeout + 3×5 分钟 + 1 分钟”启动检查，不能据此保证操作在租约内结束；数据库 fencing 仍保护最终状态，但不能取消仍在执行的外部调用。

### 证明

为快速验证，把实际 SDK 配置的 300000ms 缩短为 250ms，其余生产 adapter 逻辑保持不变；所有 endpoint 强制指向 `127.0.0.1`。响应头控制组延迟 700ms，正文组每 100ms 发一小块。最新结果：

```text
COS_HEADERS requestTimeoutMs=250 elapsedMs=289 result=timed-out
COS_LIST requestTimeoutMs=250 elapsedMs=1086 result=completed
COS_PUT_ERROR requestTimeoutMs=250 elapsedMs=763 result=COS upload failed type=CosServiceException status=503 requestId=audit-request
```

控制组按时失败，而正文组超过同一预算后仍继续。此为时间尺度缩短的真实 HTTP 验证，不代表生产环境已经实测超过五分钟。既有 `CosObjectStorageTest.java:23-32` 只验证成功 GET 流的 deadline helper，不覆盖这两条 SDK 正文路径。

修复应覆盖完整请求及正文，并在截止后中止底层 HTTP 资源；单独调小 socketTimeout 不能建立总时限。

## QS-03 · P2 · 历史文件清理与审核领取共用默认单线程调度器，慢清理阻断全队列

**状态：已确认，实际 Spring Boot 自动配置与生产 worker 复现。**

### 精确位置

- `SubmissionWorker.java:35-40`：`poll` 在调度线程直接同步执行完整 `process`。
- `SubmissionWorker.java:74-85`：另一个 `@Scheduled` 在同一调度器内，每次同步处理最多 100 条记录及其 COS 前缀。
- `SubmissionWorker.java:106-108`：每个清理直接等待 `storage.deletePrefix`。
- `application.yml:38-44` 只限制 MVC `task.execution` 执行器，不配置 `task.scheduling`；实际 Boot 调度池大小为 1。

### 触发与影响

任意历史 FAILED 记录的 COS 列举/删除变慢时，补偿方法占住唯一调度线程。即使新的上传已持久释放、COS 上传与下载正常，`poll`、`recoverExhausted` 也无法执行，只能等清理方法返回。每页 100 条记录串行处理，会把一次清理的等待累加到全部新任务；QS-02 还使某个列表请求缺少真正的总时限。

反向也成立：一个长审核占住调度线程时，已失败论文的持久删除补偿不能按其 60 秒调度继续执行。这不是要求无限并发；问题在于不相干的清理 I/O 和队列推进没有隔离。

### 证明

探针加载 Boot 自动配置及 `@EnableScheduling`，使用真实 `SubmissionWorker`。仅把 repository/tasks/外部 storage 作为隔离边界，让 `deletePrefix` 用 latch 模拟尚未返回的远程调用；没有用替身声称数据库状态逻辑通过。把轮询周期缩为 10ms，阻塞清理 250ms：

```text
SCHEDULER pool=1 pollsBeforeCleanup=1 pollsWhileCleanupBlocked=1 pollsAfterRelease=2
```

阻塞期间经过约 25 个轮询周期却没有一次领取，解除清理等待后立即恢复领取。既有 `SubmissionIntegrationTest.java:198-205` 并发测试手动从两条线程调用 `claim`，验证 CAS 正确，却不验证生产的调度拓扑。

修复方向是对领取/执行与清理建立有界的调度隔离，并约束一次补偿工作的时间；仅加大下载执行器不会影响此调度器。

## 其他已核对路径

- 上传先校验，再持久预留，再 COS，再条件释放；上传未释放期间不可领取/下载；释放事务结果异常时原稿仍由持久记录持有。
- `failUpload` 限定 PENDING/attempts=0，已经被领取的记录不会被迟到的上传失败清掉。
- claim、succeed、fail 匹配状态、attempts 与租约，SUCCESS 是终态；旧 attempt 返回不能覆盖新 attempt。
- `cleanupAttempt` 先读持久状态；同 attempt SUCCESS 不删，状态读取失败则保留。补偿读到 FAILED/PENDING 的陈旧快照也不会变成删除未来成功 attempt 的条件，因为 FAILED 无后续成功迁移，PENDING 下一次领取增加 attempts。
- marked 下载从 SUCCESS 当前 attempt 生成固定 key；角色、本人/当前导师关系及准备后密码变化会在开始异步读取前再次校验。
- 初次请求认证到下载计划创建之间的并发密码变化尚未做真实 HTTP 链复现，不把该窗口列为确认缺陷。

## 验证命令、证据与边界

```powershell
rtk python artifacts/platform-audit/run-queue-storage-probe.py
```

最新运行退出码 **0**。脚本从现有 Surefire XML 提取依赖 classpath，把**当前生产 `CosObjectStorage.java`、`SubmissionWorker.java` 源码**与独立探针重新编译到 `artifacts/platform-audit/classes-queue-storage`，不依赖可能过期的这两份 target/classes，不修改 Maven target。

- 源码：`artifacts/platform-audit/QueueStorageAuditProbe.java`。
- 可重复入口：`artifacts/platform-audit/run-queue-storage-probe.py`。
- 最新已脱敏日志：`artifacts/platform-audit/queue-storage-probe.log`。
- 既有测试只读审查，没有重新运行其 52 项全量测试；真实 PostgreSQL CAS/权限行为没有用内存替身重新宣称验证。
- 不代表真实 COS ACL、线上超时、文件删除或端到端下载验收。
- 语义搜索工具首次调用失败：Windsurf API Key 不存在，已按规则熔断，使用 `rg`、文件及 SDK 字节码追踪补足，未重试同一路径。
