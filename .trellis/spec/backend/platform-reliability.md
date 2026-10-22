# 平台权限与任务可靠性

## 1. 适用范围

修改账号、异步审核、COS、下载或统计时使用本规范。数据库固定两张业务表；不得用前端缓存、内存任务或对象存储列表替代持久权限和状态。

## 2. 公共接口

- `UserRepository.findByIdForUpdate(long id)`：账号资料、启停、重置、改密事务统一行锁。
- `SubmissionService.upload(CurrentUser, MultipartFile)`：校验文件后持久预留，再上传和释放队列。
- `SubmissionTasks.claim/succeed/fail`：按状态、attempts、租约做条件更新；失败返回不得视为提交完成。
- `AuditEngine.inspect(Path, Path)`：返回问题数量、可读的标注 DOCX、Word 检查报告与 JSON；抛出带 retryable 的失败。
- `ObjectStorage.put/get/deletePrefix`：生产仅腾讯 COS；下载资源必须显式关闭。

## 3. 契约

- HTTP 使用平台 envelope，ID 为字符串；分页从1开始，最多100项。字段错误位于 `error.fieldErrors`。
- 认证 Cookie + CSRF；密码或账号状态变化立即在后端撤销旧权限。
- 账号 SHA256/Base64 预哈希后 BCrypt，6–128 Unicode 码点；前端不能以128个UTF16单元截断密码。
- 上传50MiB上限、multipart52MiB、Excel5MiB/2000数据行；网关、平台、引擎保持一致。
- 合法文件先持久化10分钟上传预留，再写COS；预留期不能领取/下载。上传失败也有记录用于清理。非法文件无记录。
- 默认引擎总超时10分钟，单COS传输5分钟，worker租约40分钟。租约须大于引擎总超时加21分钟；响应正文也受总超时约束。
- SUCCESS文件永久保留；提交成功但响应丢失时必须重读数据库，不得按本地异常直接删除文件。
- 教师统计仅SUCCESS；最近时间/问题数同一行；未知为null。模块统计无可靠章节源时null。
- 下载准备阶段只生成授权计划，异步执行开始前再次读取当前账号、密码指纹、导师关系和提交状态，再打开COS流。不能让排队请求沿用已撤销的权限。
- 下载复用Boot受限执行器（8线程、16排队），MVC超时330秒，代理360秒。拒绝或未提交响应的超时返回JSON 503，清除下载文件头；async/error dispatch复用原requestId。
- 引擎产物不能仅相信JSON中的WRITTEN：独立检查非空正文未清空、有效的新增非空批注引用及对应关系。原生OMML也属于正文内容；同段合并批注不能按错误数量要求批注数量相等。

## 4. 错误矩阵

| 条件 | 结果 |
| --- | --- |
| 未登录/密码指纹失效 | 401，清除会话 |
| 首次未改密 | 403 PASSWORD_CHANGE_REQUIRED |
| 跨学生/非导师/管理员读论文 | 403，无COS读取 |
| 文件超限 | 413，无提交记录 |
| 损坏DOCX | FAILED，不重试 |
| 引擎5xx/网络超时 | 同记录重试，最多3次 |
| 过期attempt返回 | 不覆盖新状态，不删除新文件 |
| 数据库提交结果不明 | 保留文件，依持久状态补偿 |
| SDK异常含签名URL | 丢弃原文/嵌套cause，仅安全类型和状态日志 |

## 5. 情形

- 正常：学生改密、上传、后台成功，当前导师与本人可下载。
- 无记录：教师列表成功次数0，时间/问题数为“—”。
- 异常：资料编辑与重置同时发生，两项变更均保留；COS故障不产生伪成功。

## 6. 验证要求

真实PostgreSQL验证行锁、队列竞争、attempt隔离、统计和导入重复。真实HTTP服务器验证请求/正文超时与损坏产物；独立引擎容器验证生成的Word能回读批注。前端验证切换账号清缓存、403不泄露旧数据、首次改密硬门。外部adapter替身不得冒充COS线上验收。

## 7. 反例与正确做法

- 错：捕获完成事务异常后无条件删除attempt目录。对：读取持久状态，只有确认FAILED或过期attempt才能清理。
- 错：资料和密码各自读实体再整行save。对：所有修改账号入口共享同一行锁。
- 错：200响应、非空ZIP就宣布审核成功。对：验证Word内容、必要批注及批注引用证据。
- 错：权限撤销后Query继续渲染旧结果。对：401/403/404阻止缓存内容展示；退出与切换账号清业务缓存。

## 8. 既有引擎接入账号平台的发布契约

### 适用与接口

既有匿名引擎迁入账号平台时，复用一个引擎容器，通过实际Docker外部网络连接；不得建立两个相同 `backend` DNS别名。平台健康接口为 `GET /api/health`，成功必须是平台envelope且 `data.status=UP`；旧 `/api/inspections/health` 不再成为匿名放行路径。

### 配置与失败处理

前端只绑定宿主回环端口，宿主HTTPS代理为唯一公共入口，`SESSION_COOKIE_SECURE=true`。配置文件权限600，不输出插值后的完整Compose配置。两JVM与数据库必须分别设置内存预算，不能各自默认占宿主75%。

| 条件 | 发布行为 |
| --- | --- |
| 健康返回旧envelope或短暂5xx | reload后有界轮询，记录状态；30秒未收敛则回退入口 |
| 新健康UP但旧匿名业务仍可达 | 验收失败，关闭旧入口并重新检查权限 |
| COS没有真实可用配置 | 不宣称文件流程上线；不关闭worker伪造成功 |
| 回退入口或应用 | 保留数据库及COS，不删卷；分别确认引擎与前端回退版本 |

### 正常、基准与异常案例

正常：候选真实上传成功，切换HTTPS后相同提交仍能由本人和导师下载；基准：匿名健康UP但业务401；异常：旧前端8080仍公开导致绕过账号，阻止交付。

### 验证断言与反例

断言各角色真实浏览器登录、Secure Cookie、本人/导师下载、跨学生与管理员下载拒绝、实际Word及批注可读、旧入口不可达，并记录新旧镜像和发布指针。同一提交的切换后复验不得算成第二次上传。

错误：`nginx -t`通过就宣布切换成功，或无范围启动旧Compose把匿名前端重新开启。正确：等目标平台健康语义收敛，再做业务与权限检查；旧项目更新只限定引擎服务。

## 9. Word 报告交付契约

### 范围

修改引擎产物领取、审核成功、数据库报告状态或任何下载入口时适用。保持浏览器 COS 直传和平台中转两条既有通道，不用旧分支整体替换新的下载代码。

### 接口

- `AuditEngine.Result(int problemCount, Path marked, Path reportDocx, byte[] reportJson)`。
- Flyway V2 新增 `submission.report_available boolean NOT NULL DEFAULT false`。
- `SubmissionView.canDownloadReport` 为 `SUCCESS && reportAvailable`。
- `GET /api/submissions/{id}/files/report` 与 `GET /api/submissions/{id}/files/report/url`。

### 数据与时限

adapter 验证同一 `requestId` 的批注和报告地址，报告必须是具有合法主文档类型、内部关系及非空正文的 DOCX；不得接受 HTML、空 Word 或外部地址。审核与两个产物下载共享引擎总超时。Worker 在当前 attempt 下依次保存 `marked.docx`、`report.docx`、`report.json` 后，使用既有租约/attempt 条件原子写 SUCCESS 与报告可用标记；失败/重新领取时标记 false。COS 下载原稿加三个产物上传共四次传输，worker 租约大于引擎超时加21分钟，默认40分钟。

### 错误矩阵

| 条件 | 行为 |
| --- | --- |
| 报告未生成、下载失败、包损坏、地址不属于本次审核 | 引擎适配失败，可重试，不交付部分成功 |
| 报告/JSON 保存失败 | 当前 attempt 不成功，保留原稿并按已有补偿策略处理 |
| 历史 SUCCESS、报告标记 false | 原稿/批注仍可下载；报告返回 FILE_NOT_AVAILABLE |
| 非本人/当前导师，或管理员读论文 | 两种下载入口均 FORBIDDEN |
| kind 非 original/marked/report | INVALID_FILE_KIND，不映射到默认文件 |

### 正常、历史与异常

正常：审核完成，报告字节与 `attempt-N/report.docx` 一致。历史：升级后没有报告的成功记录保持无报告，需重新审核产生新记录。异常：迟到 attempt 或丢失完成响应不得清除已成功的新产物。

### 回归证据要求

真实 PostgreSQL 验证 V1→V2 保留数据、默认 false、报告状态与 SUCCESS 同步、失败清理和过期 attempt；真实 HTTP 边界验证两个下载与总时限；UI 验证两角色/两模式的报告入口、文件名、失败反馈与历史记录。COS 替身仅为测试边界，不能冒充线上存储验收。

### 反例

错误：仅新增报告按钮，沿用“非 original 一律下载 marked”的映射，或把全部历史成功记录改为有报告。正确：代理与预签名共用同一权限检查，明确映射 `report` 到当前 attempt 的 `report.docx`，仅在产物已保存后开放下载。
