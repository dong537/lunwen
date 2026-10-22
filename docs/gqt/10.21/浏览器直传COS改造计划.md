# 浏览器直传 COS 与直下改造计划

> 状态：草案，等待评审。本文只描述方案、任务、风险和验收标准，不包含实现代码。
>
> 范围：`frontend`、`platform-backend`、`compose.yaml`、`.env.example`、`docs`。
>
> 目标：把论文文件的字节传输从“浏览器 -> 平台服务器 -> COS”改为“浏览器 -> COS”，下载改为“浏览器 <- COS”，平台只负责鉴权、预签名、元数据校验和审核调度。

## 0. 摘要

- 采用预签名 PUT/GET：平台只做鉴权、预签名和 `HEAD` 校验，论文文件字节不再经过平台服务器。
- 服务器侧 `COS_ENDPOINT` 与浏览器侧 `COS_PUBLIC_ENDPOINT` 必须分离；预签名必须使用独立的公网 `COSClient`，不能复用内网 client。
- 上传使用 600 秒 URL 和 15 分钟预留；`upload-complete` 幂等，并通过 COS `HEAD` 校验大小和 Content-Type。
- 下载使用预签名 GET 和 `response-content-disposition`，浏览器导航下载，不把 50MiB 文件读入 JS 内存。
- 保留旧代理链路和 `TRANSFER_MODE` 开关；CORS 或签名问题时可快速回退。
- 最大技术风险是浏览器对签名 `Content-Length` 的处理；Phase 1 必须用真实浏览器验证，失败则退回 HEAD 复核或 POST policy。
- 上线前必须配置 COS CORS、确认公网域名可达，并轮换曾在聊天或工单中出现过的密钥。

## 1. 目标与范围

### 1.1 目标

- 学生上传时，浏览器使用平台签发的短期预签名 URL 直接 PUT 到 COS 私有桶。
- 学生和教师下载时，平台完成权限校验后签发短期预签名 GET URL，浏览器直接从 COS 下载。
- 平台不再中转论文文件字节，降低公网带宽、连接数、临时磁盘和下载线程池压力。
- 保持 COS 桶私有，SecretId/SecretKey 只存在于服务器。
- 保持现有账号、角色、导师关系、提交状态、审核 worker 和文件清理逻辑不变。
- 保持 50MiB 文件上限、DOCX 类型限制和现有错误文案风格。

### 1.2 非目标

- 不改造审核 worker 的 COS 拉取和回写逻辑；worker 仍然通过服务器内网/公网 client 访问 COS。
- 不引入 CDN、COS 全球加速或自定义下载域名。
- 不实现断点续传、分片上传或上传队列。
- 不引入病毒扫描；DOCX 结构校验仍由审核引擎完成。
- 不删除旧的代理上传/下载接口；保留一个发布周期用于回退。
- 不把桶改成公有读，不把 SecretId/SecretKey 下发到浏览器。
- 不修改数据库表结构，复用现有 `submission` 的预留和状态字段。

### 1.3 验收标准

- 学生在浏览器上传 1MiB 和 50MiB DOCX 时，开发者工具的网络面板显示 PUT 请求直接发往 `docx-check-1327210567.cos.ap-beijing.myqcloud.com`，不经过 `/api/submissions` 的 multipart 传输。
- 平台服务器在直传期间不承载 50MiB 文件字节；服务器公网出流量和临时磁盘占用明显下降。
- 上传完成后，审核 worker 能正常从 COS 取原件、生成批注并回写，提交状态最终为 SUCCESS。
- 学生和教师下载原稿/批注稿时，浏览器直接向 COS 发起 GET，文件名正确，文件可打开。
- 超过 50MiB、类型不符、对象缺失、URL 过期、跨账号访问、管理员访问均被正确拒绝。
- CORS 配置错误时前端给出明确提示；把 `TRANSFER_MODE` 切回 `proxy` 后旧流程可用。
- 日志中不出现预签名 URL、`q-signature`、`q-ak` 或完整请求签名。
- 现有代理模式的自动化测试继续通过。

## 2. 现状与约束

### 2.1 当前链路

```text
上传：
浏览器 --multipart--> 宿主 Nginx --> 前端 Nginx --> platform(Tomcat)
platform --HTTPS 公网--> COS

审核：
COS --HTTPS 公网--> platform(临时盘) --> 容器内网 --> 审核引擎
审核引擎 --> platform(临时盘) --HTTPS 公网--> COS

下载：
COS --HTTPS 公网--> platform(8 线程 StreamingResponseBody) --> 前端 Nginx --> 宿主 Nginx --> 浏览器
```

当前所有文件字节都经过 `platform-backend`，COS 默认使用公网 endpoint。相关代码：

| 位置 | 作用 |
| --- | --- |
| `platform-backend/src/main/java/cn/luwneshacha/platform/submission/SubmissionController.java` | `POST /api/submissions` 接收 multipart；`GET /api/submissions/{id}/files/{kind}` 流式下载 |
| `platform-backend/src/main/java/cn/luwneshacha/platform/submission/SubmissionService.java` | 创建预留、上传原件、释放排队、下载鉴权、打开 COS 流 |
| `platform-backend/src/main/java/cn/luwneshacha/platform/submission/Submission.java` | `storageDir`、`fileSize`、`status`、`updatedAt` 预留语义 |
| `platform-backend/src/main/java/cn/luwneshacha/platform/storage/CosObjectStorage.java` | `put`、`get`、`deletePrefix`，5 分钟预算，0 次 SDK 重试 |
| `platform-backend/src/main/java/cn/luwneshacha/platform/storage/CosProperties.java` | bucket、region、secret、`endpoint`（服务器侧，可为内网） |
| `frontend/src/features/submissions/api.ts` | 当前 `FormData` 上传 |
| `frontend/src/features/submissions/SubmissionPages.tsx` | 上传表单、下载按钮 |
| `frontend/src/shared/api/http.ts` | 同源 API、CSRF、`response.blob()` 下载 |
| `frontend/src/shared/api/contracts.ts` | 前端 API 类型 |

### 2.2 关键约束

- 文件上限：`SubmissionService.MAX_BYTES = 50 * 1024 * 1024`。
- 当前上传预留：`SubmissionService.UPLOAD_RESERVATION = 15 分钟`（改造前为 10 分钟），从创建 `Submission` 行开始计时。
- `.env` 已有 `PRESIGN_EXPIRE_SECONDS=600`，当前代码未读取。
- 下载线程池：`application.yml` 中 `spring.task.execution.pool` 为 8 个线程、16 个排队位。
- 会话：HttpOnly、SameSite Cookie；写请求需要 CSRF；每个请求从数据库重查用户状态和密码指纹。
- 权限：学生只能看本人；教师只能看名下学生；管理员不能下载论文。
- 私有桶：对象不能公开访问；当前没有浏览器直连路径。
- COS 服务器侧已经支持 `COS_ENDPOINT`（例如 `cos-internal.ap-beijing.myqcloud.com`），但浏览器不能访问内网域名。
- `COS_ENDPOINT` 和浏览器直传使用的域名必须是两个概念，不能共用。
- 现有审计已确认：COS SDK 错误日志可能包含完整请求签名；本次改造不能把预签名 URL 再写入日志。
- 现有审计已确认：切换账号后旧页面仍可能以新账号上传原账号所选文件；直传流程跨多个请求，需要一并处理。

## 3. 目标架构

### 3.1 上传时序

```text
学生                     平台                          PostgreSQL              COS
 |                        |                               |                    |
 |-- POST /uploads ------>|                               |                    |
 |   {filename,size}      |-- 创建 PENDING 预留 -------->|                    |
 |                        |-- 生成 PUT 预签名 URL         |                    |
 |<-- {id,url,headers} ---|                               |                    |
 |                                                         |                    |
 |-- PUT file ---------------------------------------------|------------------->|
 |   Content-Type, Content-Length                          |                    |
 |<--------------------------------------------------------|-------- 200 ------|
 |                                                         |                    |
 |-- POST /{id}/upload-complete -------------------------->|                    |
 |                        |-- HEAD object ----------------------------------->|
 |                        |<-- size/type/etag --------------------------------|
 |                        |-- 校验并 releaseUpload ------>|                    |
 |<-- 202 SubmissionView -|                               |                    |
 |                                                         |                    |
 |                        |<-- worker claim --------------|                    |
 |                        |-- GET original (服务器侧) ---------------------->|
 |                        |-- 审核、PUT marked/report ---------------------->|
```

要点：

- 预签名 PUT 的 key 由平台生成：`submissions/{uuid}/original.docx`，客户端不能指定 key。
- 浏览器 PUT 不携带 Cookie、不携带 CSRF token；URL 本身是短期授权凭证。
- 上传完成后必须调用 `upload-complete`；平台通过 COS `HEAD Object` 校验对象存在、大小和 Content-Type，再释放预留。
- worker 逻辑保持不变，仍由平台服务器从 COS 读取原件并回写结果。

### 3.2 下载时序

```text
学生/教师                平台                          COS
 |                        |                             |
 |-- GET /files/{kind}/url ->|                           |
 |                        |-- 角色/归属/状态校验         |
 |                        |-- 生成 GET 预签名 URL        |
 |<-- {url,filename,expiresAt}                           |
 |                                                       |
 |-- GET url ------------------------------------------->|
 |<-- 文件字节（Content-Disposition 指定文件名）----------|
```

要点：

- 下载 URL 在签发时完成一次鉴权；URL 有效期内不再经过平台。
- 使用 COS 的 `response-content-disposition` 覆盖响应头，保留原文件名和 `.marked.docx` 文件名。
- 浏览器使用导航方式下载，不使用 `fetch` + `response.blob()`，避免 50MiB 文件全部进入 JS 内存。
- 导航下载不需要 CORS；如果后续改为 `fetch` 下载，才需要配置 GET/HEAD 的 CORS 和暴露响应头。

### 3.3 两个 endpoint 的边界

| 配置 | 使用方 | 示例 | 说明 |
| --- | --- | --- | --- |
| `COS_ENDPOINT` / `platform.cos.endpoint` | 服务器侧 `CosObjectStorage` 和 worker | `cos-internal.ap-beijing.myqcloud.com` | 可为空；为空走公网。只用于服务器到 COS。 |
| `COS_PUBLIC_ENDPOINT` / `platform.cos.public-endpoint` | 浏览器预签名 URL | `cos.ap-beijing.myqcloud.com` | 可为空；为空使用 SDK 默认公网 endpoint。只填域名后缀，不含协议、桶名和路径；绝不能填内网域名。 |

这是本次改造最容易出错的地方：如果服务器侧为了省流量配置了 `cos-internal`，又用同一个 `COSClient` 生成预签名 URL，浏览器会拿到无法解析的内网域名。必须使用独立的 `CosPresigner` 和独立的公网 `COSClient`。

### 3.4 关键设计决策

| 决策 | 选择 | 理由与替代方案 |
| --- | --- | --- |
| 上传方式 | 预签名 PUT + 签名的 `Content-Type`、`Content-Length` + `HEAD` 复核 | 改动小，浏览器只需一次 PUT；签 `Content-Length` 可在 COS 侧拒绝超限。备选：COS POST Object policy 的 `content-length-range`，更严格但实现更复杂。 |
| 下载方式 | 预签名 GET + `response-content-disposition` + 浏览器导航 | 不占 JS 内存，不需要下载 CORS，保留文件名。备选：`fetch` + blob，会整文件进内存，不推荐。 |
| 预签名客户端 | 独立 `CosPresigner`，使用公网 endpoint | 避免服务器内网 endpoint 泄漏到浏览器；职责分离。 |
| 上传 URL 有效期 | `PRESIGN_EXPIRE_SECONDS`，默认 600 秒 | 与现有 `.env` 一致；必须小于上传预留，并留安全余量。 |
| 上传预留 | 从 10 分钟调整为 15 分钟 | 直传的预留从“创建行”开始，包含浏览器上传时间；10 分钟没有余量。 |
| 幂等 | `upload-complete` 幂等；重复调用返回当前状态 | 网络重试、双提交不应产生二次状态变更。 |
| 对象校验 | `HEAD` 校验存在、大小、Content-Type；可选 ETag | 浏览器上传的内容不能仅相信客户端声明。ETag 需要 CORS 暴露，作为增强项。 |
| 旧链路 | 保留 `POST /api/submissions` 和 `GET /files/{kind}` | 回退和故障切换；发布一个周期后再评估删除。 |
| 运行模式 | `TRANSFER_MODE=proxy/direct`，默认 `proxy`，验证后设 `direct` | CORS 或签名问题时可快速切回旧链路，不需要回滚前端镜像。 |
| 数据库 | 不新增表/列 | 复用 `storageDir`、`fileSize`、`updatedAt` 预留语义；避免 Flyway 迁移。 |
| 上传状态展示 | 建议给 `SubmissionView` 增加 `uploadReserved` | 直传期间列表会出现 PENDING 但文件尚未到齐的记录；前端可显示“上传中”。 |

## 4. API 设计

### 4.1 端点总览

| 方法 | 路径 | 权限 | 作用 |
| --- | --- | --- | --- |
| `GET` | `/api/submissions/transfer-mode` | 学生/教师 | 返回当前 `mode`，前端决定使用直传还是旧代理流程 |
| `POST` | `/api/submissions/uploads` | 学生 | 创建 PENDING 预留并签发 PUT 预签名 URL |
| `POST` | `/api/submissions/{id}/upload-complete` | 学生本人 | HEAD 校验对象并释放上传预留 |
| `POST` | `/api/submissions/{id}/upload-url` | 学生本人 | 刷新同一 key 的 PUT URL，不延长预留 |
| `GET` | `/api/submissions/{id}/files/{kind}/url` | 学生本人/名下教师 | 签发 GET 预签名 URL |
| `POST` | `/api/submissions` | 学生 | 旧代理上传，保留 |
| `GET` | `/api/submissions/{id}/files/{kind}` | 学生本人/名下教师 | 旧代理下载，保留 |

`GET /api/submissions/transfer-mode` 返回：

```json
{
  "data": { "mode": "direct" },
  "error": null,
  "requestId": "..."
}
```

响应加 `Cache-Control: no-store`，避免模式切换后被浏览器缓存。

### 4.2 创建上传

请求：

```json
{
  "filename": "毕业论文.docx",
  "size": 5242880
}
```

响应 `201 Created`：

```json
{
  "data": {
    "submissionId": "123",
    "url": "https://docx-check-1327210567.cos.ap-beijing.myqcloud.com/submissions/.../original.docx?sign=...",
    "method": "PUT",
    "headers": {
      "Content-Type": "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    },
    "expiresAt": "<ISO-8601>"
  },
  "error": null,
  "requestId": "..."
}
```

约定：

- `filename` 经过现有 `filename()` 清洗，只用于数据库展示和下载文件名；不参与 key。
- `size` 必须大于 0 且不超过 `MAX_BYTES`。
- 后端固定签名 `SubmissionService.DOCX`，不信任浏览器传入的 `file.type`。
- `Content-Length` 由后端签名，但不出现在 `headers` 中；浏览器会自动发送，前端不要尝试手动设置。
- 响应加 `Cache-Control: no-store`。

### 4.3 完成上传

请求：无 body。

响应 `202 Accepted`：

```json
{
  "data": {
    "id": "123",
    "studentId": "11",
    "submissionNumber": 1,
    "originalFilename": "毕业论文.docx",
    "fileSize": 5242880,
    "status": "PENDING",
    "problemCount": null,
    "byScope": null,
    "errorMessage": null,
    "createdAt": "<ISO-8601>",
    "startedAt": null,
    "finishedAt": null,
    "canDownloadOriginal": true,
    "canDownloadMarked": false
  },
  "error": null,
  "requestId": "..."
}
```

如果实现可选的 `uploadReserved` 字段，则在上面的 `SubmissionView` 中额外返回该布尔值；不实现时前端通过 `canDownloadOriginal=false` 和 PENDING 状态判断“上传中”。

校验顺序：

1. 当前用户必须是学生，且是记录所有者。
2. 记录不存在返回 404；管理员返回 403；其他学生返回 403。
3. 状态为 `SUCCESS` 或 `PROCESSING`：幂等返回当前视图，不再 HEAD。
4. 状态为 `FAILED`：返回 409 `FILE_NOT_AVAILABLE`。
5. 状态为 `PENDING` 且 `attempts > 0`：说明上传已完成并进入审核重试，直接返回当前视图；不 HEAD、不释放、不清理。
6. 状态为 `PENDING` 且 `attempts = 0`：`HEAD` 对象。
7. `HEAD` 返回不存在：
   - 预留仍有效：返回 409 `UPLOAD_NOT_FOUND`，保留预留，允许客户端重新 PUT 后再次 complete。
   - 预留已过期：返回 409 `UPLOAD_EXPIRED`，对象由 worker/清理逻辑处理。
8. 大小不符：返回 409 `UPLOAD_SIZE_MISMATCH`，标记失败并清理。
9. Content-Type 不符：比较时忽略 `;charset=...` 等参数；不符则返回 409 `UPLOAD_TYPE_MISMATCH`，标记失败并清理。
10. 校验通过且预留仍有效：条件更新 `releaseUpload`。
    - 返回 1：成功，返回视图。
    - 返回 0：重新读取记录，不要直接清理。如果记录已被并发 complete 释放（`PENDING`、`attempts=0`、对象存在），幂等返回视图；如果已过期或对象不存在，返回 409。
11. 校验通过但预留已释放（`PENDING`、`attempts=0`、`updatedAt <= now`，例如上一次 complete 已提交但响应丢失）：幂等返回当前视图，不再次释放，不延长预留。
12. 预留已过期且对象存在但尚未被 worker 领取：仍可幂等返回当前视图；worker 会在下一次轮询中处理。

### 4.4 刷新上传 URL

请求：无 body。响应同 4.2，但 `submissionId` 不变，key 不变。只在预留仍有效时签发；不延长预留。剩余预留不足 60 秒时返回 409 `UPLOAD_EXPIRED`。

### 4.5 下载 URL

请求：`GET /api/submissions/{id}/files/{kind}/url`，`kind` 为 `original` 或 `marked`。

响应：

```json
{
  "data": {
    "url": "https://docx-check-1327210567.cos.ap-beijing.myqcloud.com/submissions/.../original.docx?sign=...&response-content-disposition=...",
    "filename": "毕业论文.docx",
    "expiresAt": "<ISO-8601>"
  },
  "error": null,
  "requestId": "..."
}
```

约定：

- 复用 `SubmissionService.prepareDownload` 的角色、归属、状态校验。
- `original` 和 `marked` 的可用条件与现有 `requireDownloadable` 完全一致。
- `marked` 只允许 SUCCESS，key 使用当前 `attempts`。
- `response-content-disposition` 使用 `ContentDisposition.attachment().filename(filename, UTF_8)`。
- 同时设置 `response-cache-control: private, no-store`。
- 响应加 `Cache-Control: no-store`。

### 4.6 错误码

| HTTP | code | 场景 | 前端文案建议 |
| --- | --- | --- | --- |
| 400 | `EMPTY_FILE` | 空文件 | 请选择非空的 DOCX 文件 |
| 400 | `INVALID_FILE_TYPE` | 扩展名不是 .docx | 仅支持 DOCX 文件 |
| 400 | `INVALID_FILENAME` | 文件名为空或过长 | 文件名不合法 |
| 413 | `FILE_TOO_LARGE` | 超过 50MiB | 论文文件不能超过 50MiB |
| 409 | `DIRECT_TRANSFER_DISABLED` | 当前为 proxy 模式 | 当前未启用直传，请使用旧上传方式 |
| 409 | `UPLOAD_NOT_FOUND` | HEAD 找不到对象 | 未检测到已上传的文件，请重试 |
| 409 | `UPLOAD_SIZE_MISMATCH` | 对象大小不符 | 上传文件与提交信息不一致，请重新选择文件 |
| 409 | `UPLOAD_TYPE_MISMATCH` | 对象类型不符 | 上传文件类型不正确，请重新选择 DOCX |
| 409 | `UPLOAD_EXPIRED` | 预留过期 | 上传已超时，请重新提交 |
| 409 | `FILE_NOT_AVAILABLE` | 当前状态不允许下载 | 当前审核状态不允许下载该文件 |
| 503 | `PRESIGN_UNAVAILABLE` | 预签名失败 | 文件服务暂时不可用，请稍后重试 |
| 503 | `STORAGE_UNAVAILABLE` | HEAD/存储异常 | 文件服务暂时不可用，请稍后重试 |

## 5. 后端改造任务

### 5.1 配置

- 在 `CosProperties` 增加：
  - `String publicEndpoint`：可选，空白转 `null`，正则同 `endpoint`。
- 新增 `TransferProperties`（`platform.transfer`）：
  - `enum Mode { PROXY, DIRECT }`
  - `int presignExpireSeconds`：默认 600，范围 60 到 3600。
  - 默认 `PROXY`；生产验证后设 `DIRECT`。
- `.env.example` 增加：
  - `COS_PUBLIC_ENDPOINT=`
  - `TRANSFER_MODE=proxy`
  - 保留 `PRESIGN_EXPIRE_SECONDS=600`
- `compose.yaml` 增加：
  - `PLATFORM_COS_PUBLIC_ENDPOINT: ${COS_PUBLIC_ENDPOINT:-}`
  - `PLATFORM_TRANSFER_PRESIGN_EXPIRE_SECONDS: ${PRESIGN_EXPIRE_SECONDS:-600}`
  - `PLATFORM_TRANSFER_MODE: ${TRANSFER_MODE:-proxy}`
- 启动校验：
  - `publicEndpoint` 不能包含 `cos-internal`。
  - `presignExpireSeconds` 必须小于上传预留，并至少留 60 秒余量。
- 文档同步更新 `docs/部署与开发.md` 的配置表和数据流说明。

### 5.2 预签名组件

新增接口：

```java
public interface ObjectPresigner {
  record UploadTicket(URL url, Instant expiresAt) {}
  record DownloadTicket(URL url, String filename, Instant expiresAt) {}

  UploadTicket uploadUrl(String key, long size, Instant expiresAt);
  DownloadTicket downloadUrl(String key, String filename, Instant expiresAt);
}
```

新增实现 `CosPresigner`：

- 使用独立的 `COSClient`，配置顺序：
  - `new ClientConfig(new Region(region))`
  - `setHttpProtocol(HttpProtocol.https)`
  - 如果 `publicEndpoint` 非空，`setEndpointBuilder(new SuffixEndpointBuilder(publicEndpoint))`
  - 不读取 `platform.cos.endpoint`。
- 使用与 `CosObjectStorage` 相同的 credentials。
- `uploadUrl`：
  - `GeneratePresignedUrlRequest(bucket, key, HttpMethodName.PUT)`
  - `setExpiration(Date.from(expiresAt))`
  - `setContentType(SubmissionService.DOCX)`
  - `putCustomRequestHeader(Headers.CONTENT_LENGTH, Long.toString(size))`
- `downloadUrl`：
  - `GeneratePresignedUrlRequest(bucket, key, HttpMethodName.GET)`
  - `setExpiration(Date.from(expiresAt))`
  - `ResponseHeaderOverrides` 设置 `contentDisposition` 和 `cacheControl`
- `@PreDestroy` 关闭 client。
- 不记录 URL、query、签名。

`CosPresigner` 不访问网络；预签名是本地签名操作。测试可用假 credentials 生成 URL 并断言 host、key、query。

### 5.3 对象 HEAD

在 `ObjectStorage` 增加：

```java
record StoredMetadata(long size, String contentType, String etag) {}
Optional<StoredMetadata> head(String key) throws IOException;
```

`CosObjectStorage` 实现：

- `client.getObjectMetadata(bucket, key)`
- 404 返回 `Optional.empty()`；其他错误走现有 `failure("head", error)`。
- 不读取对象正文，不占用下载线程池。
- HEAD 使用独立的短预算（建议 15 到 30 秒），不能沿用下载的 5 分钟预算；否则 complete 请求可能被 COS 卡住，直到 Nginx 超时。
- 实现优先选择独立的 metadata client（单独的 `requestTimeout`/`socketTimeout`）；如果复用现有 client，则用 bounded executor + `Future.get` 超时。注意 SDK 的阻塞 HTTP 请求不一定响应中断，超时后要记录并让上层返回 503，不能无限等待。

同步更新测试中的 `MemoryBoundary`。

### 5.4 SubmissionService

新增方法：

```java
public UploadTicket createUpload(CurrentUser current, String filename, long size);
public SubmissionView completeUpload(CurrentUser current, long id);
public UploadTicket refreshUpload(CurrentUser current, long id);
public DownloadTicket prepareDownloadUrl(CurrentUser current, long id, String kind);
```

实现要点：

- `createUpload`：
  - 校验学生角色、文件名、大小。
  - 生成 `submissions/{UUID}`，创建 `Submission` 并 `reserveUploadUntil(now + 15 分钟)`。
  - 计算 `expiresAt = min(now + presignExpireSeconds, reservedUntil - 60 秒)`。
  - 调用 `presigner.uploadUrl(directory + "/original.docx", size, expiresAt)`。
  - 预签名失败时 `failUpload` 并清理；返回 503。
- `completeUpload`：
  - 按 4.3 的顺序实现。
  - `HEAD` 在事务外执行；校验通过后再做条件 `releaseUpload`。
  - 大小和类型不符时 `failUpload` + `cleanupUpload`。
  - 404 保留预留，允许客户端重试 PUT。
  - 幂等处理 PROCESSING/SUCCESS。
- `refreshUpload`：
  - 校验学生、所有者、PENDING、attempts=0、预留未过期。
  - 重新计算 `expiresAt`，不延长预留。
- `prepareDownloadUrl`：
  - 复用 `authorized`、`requireDownloadable`、文件名和 key 计算。
  - 调用 `presigner.downloadUrl`，返回 filename 和 expiresAt。
  - 不再调用 `openDownload`；旧代理下载仍保留。
- 预留时长：
  - 把 `UPLOAD_RESERVATION` 从 10 分钟调整为 15 分钟。
  - 更新受影响的测试时间计算。
- 清理：
  - 复用 `cleanupUpload`、`deletePrefix`、`compensateCleanup`。
  - 放弃的直传对象由预留过期、worker 失败和补偿清理处理。
- 可选：`SubmissionView` 增加 `uploadReserved`，让前端显示“上传中”。

### 5.5 Controller 与 Security

- `SubmissionController` 增加 4.1 中的端点。
- `POST /api/submissions/uploads` 必须显式限制为 STUDENT：
  - 在 `SecurityConfig` 中放在 `/api/submissions/**` 之前：
    `.requestMatchers(HttpMethod.POST, "/api/submissions/uploads").hasRole("STUDENT")`
- 其他新端点由 service 做角色和归属校验。
- 新端点响应加 `Cache-Control: no-store`。
- 旧 `POST /api/submissions` 和旧下载方法保持不变。
- 所有新端点沿用 `ApiResponse` 信封和 `requestId`。

### 5.6 worker 与清理

- worker 不改协议，仍使用 `ObjectStorage.get/put`。
- 可选改进：worker 在 `storage.get` 抛 `FileNotFoundException`/404 时按永久失败处理，避免无对象的直传记录重试 3 次。
- `compensateCleanup` 继续清理 FAILED 记录和旧 attempt 前缀。
- 直传对象 key 仍在 `submissions/{uuid}/` 下，`deletePrefix` 的正则和前缀校验不需要改。

### 5.7 日志与安全

- `CosPresigner` 不打印 URL、query、签名、access key。
- `completeUpload` 日志只记录 submission id、size、content type、结果。
- `prepareDownloadUrl` 日志只记录 submission id、kind、user id、expiresAt。
- 不把预签名 URL 放进异常消息；`ApiExceptionHandler` 仍只记录异常类型。
- 现有 COS SDK 签名日志问题单独修复；本次至少保证不新增 URL 日志。
- 前端错误提示不包含完整 URL，只包含错误码和 requestId。

## 6. 前端改造任务

### 6.1 API 类型与函数

在 `contracts.ts` 增加：

```ts
export interface UploadTicket {
  submissionId: string
  url: string
  method: 'PUT'
  headers: Record<string, string>
  expiresAt: string
}

export interface DownloadTicket {
  url: string
  filename: string
  expiresAt: string
}

export type TransferMode = 'proxy' | 'direct'
```

如果实现可选的 `uploadReserved`，同时给 `Submission` 增加 `uploadReserved?: boolean`。

在 `submissionApi` 增加：

- `transferMode()`
- `createUpload(filename, size)`
- `uploadToCos(ticket, file, onProgress, signal)`
- `completeUpload(id)`
- `refreshUpload(id)`
- `downloadUrl(id, kind)`

`uploadToCos` 使用 `XMLHttpRequest`：

- `xhr.open('PUT', ticket.url, true)`
- `xhr.setRequestHeader('Content-Type', ticket.headers['Content-Type'])`
- `xhr.withCredentials = false`
- 不设置 `Content-Length`、不设置 `Authorization`、不发送 CSRF。
- `xhr.upload.onprogress` 更新百分比。
- `xhr.timeout` 建议 11 分钟，略大于 URL TTL。
- 403 视为签名过期/无效：刷新一次 URL 后重试一次；仍失败则报错。
- `onerror` 且 status 0：提示检查网络或 COS CORS。
- 读取 `x-cos-request-id`（需 CORS 暴露）作为支持信息，不拼完整 URL。

### 6.2 上传流程

`SubmissionPages.tsx` 的 `UploadForm`：

1. 选择文件后校验扩展名、大小。
2. 点击提交：
   - 调用 `createUpload(filename, size)`。
   - 保存返回的 `submissionId`。
   - 如果 `expiresAt` 距当前时间不足 60 秒，先调用 `refreshUpload(id)` 换新 URL。
   - 调用 `uploadToCos`，显示进度。
   - 调用 `completeUpload(id)`。
   - 成功后 `invalidateQueries(['submissions'])`，写入 `['submission', id]`，跳转详情。
3. 失败处理：
   - 创建票据失败：显示后端错误。
   - PUT 失败：区分网络/CORS、403、超限/类型、其他。
   - complete 失败：保留 `submissionId`，允许“重试完成”按钮，不要立即创建新提交。
4. 上传期间禁用重复提交；保留“离开页面后继续上传”的现有行为，但组件卸载后不再依赖组件级回调更新缓存。
5. `invalidateQueries` 放在 `mutationFn` 内部或使用不会因组件卸载而丢失的 queryClient 回调，修复现有“离页上传后列表不刷新”的问题。
6. 会话身份变化：
   - 选择文件时记录当前 `user.id`。
   - 提交前比较当前 `user.id`；不一致则清除文件并提示“登录身份已变化，请重新选择文件”。
   - `useEffect` 监听 `user.id` 变化，重置文件、进度和 XHR。
7. 可选：增加“取消上传”按钮，调用 `xhr.abort()`；后端不新增取消接口，依赖预留过期清理。

### 6.3 下载流程

- 详情页的下载按钮改为：
  1. 调用 `downloadUrl(id, kind)`。
  2. 成功后创建 `<a href={url} rel="noopener">` 并点击，或 `window.location.assign(url)`。
  3. 失败显示现有 `ErrorNotice`。
- 保留 `shared/api/http.ts` 的 `download()` 给管理员 Excel 模板等小文件；不要全局替换成导航，因为模板下载需要读取错误 JSON。
- 新增 `openDownloadUrl(url)` 工具函数，集中处理 anchor 和 `rel` 属性。
- 跨域 `download` 属性通常被浏览器忽略，文件名以 COS 的 `Content-Disposition` 为准；不要依赖 `download` 属性。
- 不读取文件内容到 JS 内存；不调用 `response.blob()`。
- 每次点击都重新申请 URL，避免使用过期票据。

### 6.4 传输模式分支

- 页面加载时调用 `transferMode()`，缓存 5 分钟。
- `direct`：使用 6.2/6.3 新流程。
- `proxy`：沿用现有 `submissionApi.upload(file)` 和 `download(path)`。
- 模式切换后刷新页面生效；不需要前端重新构建。
- 如果 `transferMode()` 失败，默认使用 `proxy`，避免用户无法提交。

### 6.5 UI 状态

- 上传进度：显示百分比和“正在直传对象存储…”。
- 直传完成但审核中：沿用现有 PENDING 轮询。
- `uploadReserved=true` 时显示“上传中”，不要显示为“排队中”；下载按钮保持禁用。
- 错误文案：
  - CORS/网络：“浏览器直传对象存储失败，请检查网络后重试；如持续失败请联系管理员检查 COS 跨域配置。”
  - 403：“上传凭证已过期，正在重试…”（自动刷新一次后仍失败则报错）。
  - 404：“未检测到已上传的文件，请重新上传。”
  - 409 size/type：“上传文件与提交信息不一致，请重新选择文件。”
  - 413：“论文文件不能超过 50MiB。”

### 6.6 前端测试

- `UploadForm`：create -> XHR -> complete 成功路径。
- XHR 进度回调更新 UI。
- create 失败、PUT 网络失败、403 刷新重试、complete 404、complete 409。
- 模式为 proxy 时走旧 multipart 流程。
- 下载：URL 成功后触发 anchor，失败显示错误。
- 会话切换：选择文件后切换用户，提交被阻止；文件状态清空。
- 现有 `TeacherFlows`、`AdminFlows`、`SessionIsolation` 测试更新 mock。

## 7. 对象存储与运维前置

### 7.1 CORS

COS 桶需要为浏览器直传配置 CORS。建议规则：

```text
AllowedOrigins:
  https://124.223.86.157
  http://localhost:5173
  http://127.0.0.1:5173
  http://localhost:8080
  http://127.0.0.1:8080
AllowedMethods:
  PUT
  GET
  HEAD
AllowedHeaders:
  Content-Type
ExposeHeaders:
  ETag
  x-cos-request-id
MaxAgeSeconds:
  600
```

说明：

- 生产只保留实际使用的源；本地开发源可在开发桶配置，生产桶尽量最小化。
- 下载使用浏览器导航，不需要 CORS；保留 GET/HEAD 是为了后续可能的 `fetch` 下载和预检。
- `Content-Length` 是浏览器自动设置的 forbidden header，通常不需要放进 `AllowedHeaders`，但会被签名。如果实际预检的 `Access-Control-Request-Headers` 包含 `content-length`，把 `Content-Length` 也加入 `AllowedHeaders`。
- 不要把 `AllowedOrigins` 设为 `*`，除非只是临时验证。
- CORS 配置不是鉴权；真正的授权仍是预签名 URL。
- 如果宿主 Nginx 或前端配置了 CSP，需要在 `connect-src` 中加入 COS 公网域名，例如 `https://docx-check-1327210567.cos.ap-beijing.myqcloud.com`；否则 XHR 会被浏览器拦截。
- CORS 修改后等待生效，并用 `curl` 预检验证。

预检命令：

```bash
curl -i -X OPTIONS \
  'https://docx-check-1327210567.cos.ap-beijing.myqcloud.com/submissions/test/original.docx' \
  -H 'Origin: https://124.223.86.157' \
  -H 'Access-Control-Request-Method: PUT' \
  -H 'Access-Control-Request-Headers: content-type'
```

期望看到：

```text
Access-Control-Allow-Origin: https://124.223.86.157
Access-Control-Allow-Methods: PUT, GET, HEAD
Access-Control-Allow-Headers: Content-Type
```

### 7.2 公网 endpoint

- 确认服务器所在地域；如果服务器和桶都在 `ap-beijing`，服务器侧可以配置 `COS_ENDPOINT=cos-internal.ap-beijing.myqcloud.com`。
- 浏览器侧使用 `COS_PUBLIC_ENDPOINT=cos.ap-beijing.myqcloud.com`，或留空使用默认公网。
- 在服务器容器内验证内网域名可达：
  ```bash
  getent hosts cos-internal.ap-beijing.myqcloud.com
  curl -I https://docx-check-1327210567.cos-internal.ap-beijing.myqcloud.com
  ```
- 在浏览器网络环境验证公网域名可达。
- 预签名 URL 的 host 必须是公网域名；测试断言 URL 不包含 `cos-internal`。

### 7.3 CAM 与权限

- 桶保持私有读写。
- 应用凭证至少需要 `PutObject`、`GetObject`、`HeadObject`、`ListObjects`、`DeleteObject`，限定到 `submissions/` 前缀。
- 预签名 URL 的权限由签名时的凭证决定；URL 只授予单个 key、单个方法、有限时间。
- 不建议为直传单独创建一套长期密钥；继续使用服务器侧凭证，但确保最小权限。
- 桶 CORS 配置需要单独的 `PutBucketCORS` 权限，建议由管理员在控制台完成，不交给应用运行时。
- 预签名 URL 会暴露 AccessKeyId，但不会暴露 SecretKey；如果 SecretId/SecretKey 曾在聊天、工单或截图中出现，上线前先轮换。

### 7.4 部署前检查

- [ ] 桶地域和服务器地域确认。
- [ ] `COS_PUBLIC_ENDPOINT` 与 `COS_ENDPOINT` 分离。
- [ ] CORS 预检通过。
- [ ] 浏览器可解析并访问公网 COS 域名。
- [ ] 服务器容器内可访问内网 COS 域名（如果启用）。
- [ ] `PRESIGN_EXPIRE_SECONDS` 与上传预留匹配。
- [ ] 生产源、开发源已写入 CORS。
- [ ] 没有把预签名 URL 写入日志或监控。
- [ ] 曾在聊天、工单或截图中出现的 COS 密钥已轮换。

## 8. 安全与威胁模型

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| 预签名 URL 泄漏 | URL 有效期内可被他人使用 | 600 秒 TTL；HTTPS；不记录 URL；CORS 限制源；下载 URL 可考虑缩短到 300 秒 |
| 会话注销后 URL 仍有效 | 注销后最多 600 秒内仍可下载 | 明确接受该窗口；如需更严格，缩短下载 TTL 或保留代理下载 |
| 下载 URL 进入浏览器历史 | 同一设备使用者可能从历史记录重放 | 600 秒 TTL；敏感场景使用代理下载或隐藏 iframe，但会增加复杂度 |
| 上传超大对象 | 存储成本和滥用 | 签名 `Content-Length`；HEAD 复核；超过上限失败并清理 |
| 上传错误类型 | 非 DOCX 进入存储 | 固定签名 DOCX Content-Type；HEAD 复核；worker 再次校验 DOCX 结构 |
| 跨账号上传 | 文件归属错误 | 前端绑定选择文件时的 user id；切换账号清空文件；后端按会话用户创建记录 |
| 跨学生/教师越权 | 下载他人论文 | 复用 `authorized`、`assigned`、`requireDownloadable`；新增端点测试 |
| 管理员下载 | 管理员不应读论文 | `authorized` 对 ADMIN 直接拒绝；测试覆盖 |
| CSRF | 伪造创建/完成请求 | 新 API 仍走现有 CSRF；COS PUT 不带 Cookie，不适用 CSRF |
| 恶意覆盖对象 | 覆盖他人文件 | key 由服务端生成 UUID；预签名 URL 只绑定该 key |
| 对象 ACL 被改写 | 对象变为公开 | 不签名 `x-cos-acl`；CORS 不允许 `x-cos-acl`；桶保持私有 |
| 日志泄漏签名 | 请求可重放 | 不记录 URL；修复现有 COS SDK 日志问题；日志只记录 requestId |
| 暴力创建票据 | 资源滥用 | 现有登录和 CSRF；可增加每用户创建频率限制（可选） |
| 直传绕过服务端病毒扫描 | 恶意文件进入桶 | 桶私有；worker 校验 DOCX；如业务要求 AV，需增加隔离前缀和扫描，另行设计 |
| 对象残留 | 存储成本 | 预留过期、worker 失败、`compensateCleanup` 清理 |

## 9. 兼容性与失败处理

| 场景 | 行为 | 用户提示 |
| --- | --- | --- |
| CORS 未配置/源不匹配 | XHR `onerror`，status 0 | 浏览器直传对象存储失败，请检查网络或联系管理员检查 CORS |
| 预签名 URL 过期 | COS 403 | 自动刷新 URL 重试一次；仍失败提示重新提交 |
| 浏览器上传中断 | XHR error/timeout | 网络中断，上传未完成，请重试 |
| 用户离开页面 | XHR 可能继续或中止；预留到期后清理 | 无强制提示；列表显示上传中/失败 |
| 上传完成但 complete 网络失败 | 记录仍 PENDING；对象已存在 | 提供“重试完成”；不要立即新建提交 |
| 对象不存在 | complete 409 | 未检测到已上传的文件，请重新上传 |
| 大小不符 | complete 409，失败并清理 | 上传文件与提交信息不一致，请重新选择 |
| 类型不符 | complete 409，失败并清理 | 上传文件类型不正确，请选择 DOCX |
| 重复点击 complete | 幂等返回当前状态 | 无额外提示 |
| 同账号多标签页 | 各自创建提交；后端按用户隔离 | 无特殊处理 |
| 切换账号后提交旧文件 | 前端阻止；后端按新会话归属 | 登录身份已变化，请重新选择文件 |
| 教师被解除绑定后使用旧 URL | URL 在 TTL 内仍可用 | 明确 TTL 窗口；后续请求重新鉴权 |
| 企业网络阻止 COS 域名 | XHR 网络错误 | 提示网络限制；可切回 proxy 模式 |
| 慢速网络超过 URL TTL | 403；刷新后重传 | 上传凭证已过期，请重试 |
| 移动端 Safari | 导航下载，依赖 Content-Disposition | 文件名由 COS 响应头决定 |
| 服务器时钟偏差 | 预签名时间不合法 | 保证 NTP；部署检查 |
| 服务器与桶跨地域 | 内网 endpoint 不可用 | 保持 `COS_ENDPOINT` 为空，走公网 |
| 直传模式故障 | 切换 `TRANSFER_MODE=proxy` | 旧流程可用，无需回滚前端 |

## 10. 测试计划

### 10.1 后端单元测试

- `CosPresigner`：
  - PUT URL 的 host 是公网域名，不包含 `cos-internal`。
  - URL 包含 `sign` 参数；解码后包含 `q-signature`、`q-key-time`、`q-header-list`。
  - 解码后的 `q-header-list` 包含 `content-length`、`content-type`、`host`。
  - GET URL 包含 `response-content-disposition` 和 `response-cache-control`。
  - 中文文件名编码正确。
  - 过期时间与 `PRESIGN_EXPIRE_SECONDS` 一致。
- `CosProperties`：
  - 空白 `publicEndpoint` 转 null。
  - 非法 endpoint 启动失败。
  - `presignExpireSeconds` 范围校验。
- `SubmissionView`（如果增加 `uploadReserved`）：预留期间为 true，释放后为 false。

### 10.2 后端集成测试

使用现有 `SubmissionIntegrationTest` + Testcontainers PostgreSQL + `MemoryBoundary`：

- 创建上传：生成 PENDING 行、预留、返回 ticket；不写入对象。
- 完成上传：`MemoryBoundary` 放入对象，HEAD 成功，`releaseUpload`，worker 可 claim。
- 对象缺失：complete 返回 409，预留保留。
- 大小不符/类型不符：complete 返回 409，行 FAILED，对象清理。
- 预留过期且对象缺失：complete 返回 409；对象存在时幂等返回视图，由 worker/清理处理。
- 幂等：连续两次 complete，第二次返回当前视图，不重复释放。
- 刷新 URL：同一 key，新 URL；过期预留返回 409。
- 下载 URL：学生本人、名下教师可获取；其他学生、非名下教师、管理员拒绝。
- 下载 URL：FAILED、未释放的 PENDING、未成功的 marked 拒绝。
- 旧代理上传/下载测试全部保留通过。
- `MemoryBoundary` 增加 `head` 实现，保持现有测试语义。

### 10.3 前端测试

- `SubmissionPages`：
  - direct 模式：create -> XHR -> complete -> 跳转。
  - proxy 模式：旧 multipart 路径。
  - 进度显示。
  - XHR 403 刷新一次；仍失败报错。
  - complete 404 显示重试完成。
  - CORS/network 错误提示。
  - 下载按钮调用 URL API 并触发 anchor。
  - 会话切换清空文件并阻止提交。
- `contracts`/API 类型编译通过。
- `npm test`、`npm run build` 通过。

### 10.4 真实 COS 验收

在测试账号和真实桶上执行：

- [ ] 1MiB 有效 DOCX 直传 -> complete -> worker SUCCESS。
- [ ] 50MiB 有效 DOCX 直传，确认 COS 对象大小和 Content-Type。
- [ ] 50MiB+1 文件在 API 层被拒绝，或在 COS 签名层被拒绝；不留下成功记录。
- [ ] 原稿下载：浏览器直接 GET COS，文件名正确，SHA256 与上传文件一致。
- [ ] 批注稿下载：SUCCESS 后 GET COS，文件名 `.marked.docx`，Word 可打开。
- [ ] 中文文件名和空格文件名。
- [ ] URL 过期：使用测试 profile 的短 TTL（或单元测试断言签名时间），等待过期后 GET 返回 403。
- [ ] 跨学生、非名下教师、管理员下载均拒绝。
- [ ] 切换账号后提交旧文件被阻止。
- [ ] CORS 预检通过；在隔离桶或维护窗口移除源后，浏览器报 CORS 错误并提示。
- [ ] `TRANSFER_MODE=proxy` 时旧上传/下载仍可用。
- [ ] 日志中无 `q-signature`、`q-ak`、完整 URL。
- [ ] 服务器公网出流量和 Nginx/Tomcat 临时文件峰值下降。
- [ ] COS 访问日志显示 PUT/GET 来自浏览器 IP，而不是平台服务器 IP。

### 10.5 负载与流量

- 8 个并发 50MiB 下载：平台下载线程池不参与，服务器出流量不随文件大小增长；COS 侧带宽成为唯一限制。
- 8 个并发 50MiB 上传：平台只处理 JSON/HEAD；服务器出流量不承载文件。
- worker 并发不变；审核期间服务器仍从 COS 拉取原件和回写结果。
- 记录改造前后的 COS 公网流量、服务器公网出流量、平台 CPU、临时磁盘峰值。

## 11. 分阶段任务

### Phase 1：技术验证与前置条件

- [ ] 用临时 JUnit 测试或独立 Java main，使用测试凭证生成 PUT 预签名 URL，签 `Content-Type` 和 `Content-Length`。测试凭证不得打印到日志，优先使用测试子账号或测试桶。
- [ ] 在真实浏览器中用 `fetch`/XHR 上传 1MiB 文件，确认：
  - COS 接受签名的 `Content-Length`。
  - CORS 预检通过。
  - 返回 200，对象大小和 Content-Type 正确。
- [ ] 生成 GET 预签名 URL，设置 `response-content-disposition`，确认浏览器下载文件名正确。
- [ ] 验证如果服务器配置 `COS_ENDPOINT=cos-internal...`，预签名 URL 仍使用公网 host。
- [ ] 确认生产源、开发源的 CORS 列表。
- [ ] 确认服务器与桶的地域关系，决定 `COS_ENDPOINT` 是否启用内网。
- [ ] 把验证结果写入本计划或独立验证记录；如果 `Content-Length` 签名不可用，记录改用 HEAD 复核或 POST policy 的决定。
- [ ] Perform a self-review of your work, once you're certain it's 100% complete to the requirements in this phase mark the task as done.
- [ ] STOP and wait for human review.

### Phase 2：后端实现

- [ ] `CosProperties` 增加 `publicEndpoint`，补充校验。
- [ ] 新增 `TransferProperties`，包含 `mode` 和 `presignExpireSeconds`。
- [ ] 新增 `ObjectPresigner` 接口和 `CosPresigner` 实现。
- [ ] `ObjectStorage` 增加 `head`；`CosObjectStorage` 实现 404 语义。
- [ ] `SubmissionService` 增加 create/complete/refresh/downloadUrl。
- [ ] `UPLOAD_RESERVATION` 调整为 15 分钟。
- [ ] 新增 Controller 端点；`SecurityConfig` 限制 `/uploads` 为 STUDENT。
- [ ] 新增 `GET /api/submissions/transfer-mode`。
- [ ] 可选：`SubmissionView.uploadReserved`。
- [ ] 日志脱敏；不记录 URL。
- [ ] 更新 `SubmissionIntegrationTest`、`MemoryBoundary`、新增 `CosPresignerTest`。
- [ ] 运行 `mvn -B -f platform-backend/pom.xml verify`。
- [ ] Perform a self-review of your work, once you're certain it's 100% complete to the requirements in this phase mark the task as done.
- [ ] STOP and wait for human review.

### Phase 3：前端实现

- [ ] `contracts.ts` 增加上传/下载/模式类型。
- [ ] `submissionApi` 增加 create/uploadToCos/complete/refresh/downloadUrl/transferMode。
- [ ] `UploadForm` 改造为直传流程，增加进度和错误处理。
- [ ] 下载改为 URL + 导航。
- [ ] 会话身份绑定和文件清理。
- [ ] 模式分支与 proxy 回退。
- [ ] 更新 `TeacherFlows`、`AdminFlows`、`SessionIsolation` 和新增上传/下载测试。
- [ ] 运行 `npm test`、`npm run build`。
- [ ] Perform a self-review of your work, once you're certain it's 100% complete to the requirements in this phase mark the task as done.
- [ ] STOP and wait for human review.

### Phase 4：联调与验收

- [ ] 在隔离环境配置真实 COS、CORS、内网/公网 endpoint。
- [ ] 执行 10.4 的真实 COS 验收。
- [ ] 执行 10.5 的负载和流量对比。
- [ ] 回归代理模式、Excel 导入、账号和教师流程。
- [ ] 修复验收发现的问题，补充自动化测试。
- [ ] 更新 `docs/部署与开发.md` 和运维 runbook。
- [ ] Perform a self-review of your work, once you're certain it's 100% complete to the requirements in this phase mark the task as done.
- [ ] STOP and wait for human review.

### Phase 5：生产发布与收尾

- [ ] 备份当前发布指针和 `.env`（不含明文密钥的副本按现有安全流程保存）。
- [ ] 部署后端，保持 `TRANSFER_MODE=proxy`，验证旧流程。
- [ ] 配置 CORS 和 `COS_PUBLIC_ENDPOINT`，执行预检。
- [ ] 部署前端；把平台侧 `TRANSFER_MODE` 设为 `direct` 并重启 platform，小范围验证。
- [ ] 观察 COS/服务器流量、错误日志和用户反馈。
- [ ] 写实现记录，记录验收证据和回退步骤。
- [ ] 一个稳定周期后再评估是否删除旧代理端点。
- [ ] Perform a self-review of your work, once you're certain it's 100% complete to the requirements in this phase mark the task as done.
- [ ] STOP and wait for human review.

## 12. 上线与回退

### 12.1 上线顺序

1. 配置桶 CORS 和 `COS_PUBLIC_ENDPOINT`，预检通过。
2. 部署后端，保持 `TRANSFER_MODE=proxy`；旧上传/下载可用。
3. 部署前端；`transferMode()` 返回 `proxy` 时走旧流程。
4. 把 `TRANSFER_MODE` 改为 `direct`，重启 platform。
5. 用测试账号上传 1MiB、50MiB，完成审核和下载。
6. 观察错误率和流量稳定后，再对全部学生开放。

### 12.2 回退

- 设置 `TRANSFER_MODE=proxy`，重启 platform；前端下一次请求模式接口后回到旧流程。
- 旧接口和旧代码保留，无需数据库回滚。
- 已上传的 COS 对象保留；未完成的预留由现有清理逻辑处理。
- 如果前端缓存了 `direct`，刷新页面或等待 5 分钟缓存过期。
- 如果 CORS 配置错误导致直传全部失败，回退到 proxy 后旧流程不受影响。
- 禁止删除桶、清空对象或执行 `docker compose down -v`。

### 12.3 监控指标

- COS 公网下行/请求数。
- 服务器公网出流量。
- `direct_upload_created`、`direct_upload_completed`、`direct_upload_failed` 计数。
- `download_url_issued` 计数。
- `UPLOAD_NOT_FOUND`、`UPLOAD_SIZE_MISMATCH`、`UPLOAD_TYPE_MISMATCH`、`UPLOAD_EXPIRED` 错误率。
- worker 审核成功率和耗时。
- 平台 5xx 和请求延迟。

## 13. 风险与待确认问题

### 13.1 主要风险

- 浏览器对签名 `Content-Length` 的处理可能与预期不同；Phase 1 必须先验证。
- 如果 `COS_ENDPOINT` 配成内网且预签名复用了同一个 client，浏览器会拿到不可达 URL；必须使用独立 `CosPresigner`。
- CORS 配置错误会让所有直传失败；需要模式开关和预检。
- 预签名 URL 在 TTL 内是 bearer token；会话注销/角色变更不能立即失效。
- 直传绕过了平台接收文件，恶意文件会先进入私有桶；worker 仍会校验 DOCX，但如需要 AV 需另行设计。
- 旧审计的 COS SDK 签名日志问题和跨标签页身份问题需要在本次改造中一并处理或明确接受。

### 13.2 待确认

- [ ] 生产环境的浏览器来源是 `https://124.223.86.157`，还是另有域名？CORS 需要精确列表。
- [ ] 生产服务器是否在 `ap-beijing`？如果是，服务器侧是否启用 `cos-internal`？
- [ ] 是否接受 `TRANSFER_MODE` 开关（推荐），还是直接上线直传、回退时回滚前端镜像？
- [ ] 下载 URL 的 TTL 是否保持 600 秒？如果要求注销后尽快失效，是否缩短到 120-300 秒？
- [ ] 是否需要下载进度或断点续传？当前方案使用浏览器导航，不支持应用内进度。
- [ ] 是否要求上传前病毒扫描？如果需要，直传方案要增加隔离桶/前缀和扫描任务。
- [ ] 50MiB 上限是否需要在 COS 侧强制（签名 Content-Length），还是 HEAD 复核即可？
- [ ] 是否需要在列表中显示“上传中”状态（增加 `uploadReserved` 字段）？
- [ ] 旧代理接口保留多久？一个发布周期还是长期保留？
- [ ] 桶 CORS 由谁配置、使用哪个账号？应用凭证是否有 `PutBucketCORS` 权限？

## 14. QA Checklist

- [ ] 所有用户指令已遵循。
- [ ] 目标、非目标、验收标准已明确。
- [ ] 浏览器直传和直下的时序、权限边界、endpoint 边界已说明。
- [ ] `COS_ENDPOINT` 与 `COS_PUBLIC_ENDPOINT` 已明确分离。
- [ ] 预签名 PUT 的 `Content-Type`/`Content-Length` 方案已说明并有 Phase 1 验证。
- [ ] 下载 `response-content-disposition` 和导航方案已说明。
- [ ] 上传预留、TTL、complete 幂等、清理逻辑已说明。
- [ ] 新旧模式切换和回退步骤已说明。
- [ ] CORS、CAM、私有桶和日志脱敏已覆盖。
- [ ] 现有审计问题（COS SDK 签名日志、跨标签页身份）已纳入。
- [ ] 后端、前端、真实 COS、负载和安全测试已列出。
- [ ] 没有引入不必要的抽象、数据库迁移或新依赖。
- [ ] 文档更新、监控指标和上线检查已列出。
- [ ] 待确认问题已集中列出，等待评审。
