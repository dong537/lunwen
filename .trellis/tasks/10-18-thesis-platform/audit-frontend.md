# 前端独立只读审计

审计日期：2026-10-19。范围：`frontend/src` 会话、Query、上传、下载、导入、表单、路由与错误处理；必要时追到后端入口确认契约。任务模式为规则/评审模式，未修改生产源码、既有测试、依赖或运行配置。

依据：本任务 `prd.md`、`research/需求文档.md`、`.trellis/spec/platform-contract.md`。使用 systematic-debugging 追踪根因，并按 verification-before-completion 读取实际复现结果。fast-context 返回 `Windsurf API Key not found`；已熔断该路径，改用 `rg` 和入口到请求/缓存的代码追踪。浏览器插件缺失旧 browser-service 路径是主 Agent 已确认的环境故障，本次读取 Browser 技能后沿用获准的本地 Playwright 验证。

## 结论

发现 **2 项 P1、1 项 P2**。前两项使用真实本地服务的登录、Cookie/CSRF 与账号 API 复现；第三项使用真实登录/生产 UI，在测试边界拦截提交 API 复现。所有访问仅限 `http://127.0.0.1:18090`；没有触碰线上，没有发送合法论文到后端或 COS，没有启动 worker。

现有 `SessionIsolation.test.tsx` 覆盖了“显式刷新 session 后清缓存”和“离页上传不自动跳转”，但遗漏了正常跨页签切换的发现机制及后台上传完成后的列表失效。原有 26 项测试通过不能排除以下问题；本代理未重复运行或改写该套测试。

| 编号 | 严重度 | 问题 | 证据 |
|---|---|---|---|
| FE-A01 | P1 | 跨页签切换账号后，旧页保留上一学生身份和 File，写请求使用新账号 Cookie | 真实 Cookie 归属与上传请求拦截复现 |
| FE-A02 | P1 | 管理员名单请求已返回 403，界面仍展示缓存账号资料 | 真实后端 403 + 浏览器截图 |
| FE-A03 | P2 | 上传中离页，后台成功后回到列表仍显示无记录，且不再自动轮询 | API 边界受控、真实 UI 复现，手动刷新恢复 |

## FE-A01：跨页签切换导致上传身份与界面身份不一致

**定位**

- `frontend/src/features/auth/session.tsx:23-45`：仅在 `/auth/me` query 执行并发现身份变化后清理业务数据，且会话 `staleTime: 30_000`。
- `frontend/src/features/auth/session.tsx:51-59`：只监听当前 window 的 `platform:auth` 事件，无跨页签会话通知。
- `frontend/src/shared/api/http.ts:66-73`：每次写入取得当前 Cookie 会话的新 CSRF，并以 `credentials: include` 发送；未验证当前身份是否仍是选择文件时的身份。
- `frontend/src/features/submissions/SubmissionPages.tsx:20-48`：旧组件中的 File 可继续触发上传。
- 后端 `platform-backend/src/main/java/cn/luwneshacha/platform/submission/SubmissionController.java:17-18`：提交归属由实际 `@AuthenticationPrincipal CurrentUser` 传入服务决定，符合后端权限设计。

**触发与证据**

1. 同一浏览器 context 的页签 A 登录学生甲（本次 id `11`），选择 `审计甲的私有文件.docx`。
2. 页签 B 正常通过 UI 退出甲、登录学生乙（id `12`）。
3. 立即回 A：页头仍是甲、选择的 File 仍存在；点击“提交审核”确实产生上传 POST。
4. 同一 context 的真实 `/api/auth/me` 返回乙（id `12`）；本次 POST 在 Playwright 路由中被 400 阻断，**没有把论文上传到后端**。

这证明前端操作上下文与实际服务器身份已经脱节。依据后端 principal 归属逻辑，若该合法上传继续发送，会记到乙名下；该归属后果是代码数据流推论，未以真实上传验证。即使不上传，旧账号私有页面/文件选择也会保留到 session 被重新请求。30 秒只是缓存 fresh 窗口，并非最长暴露时间；过期本身不会主动触发 `/auth/me`。

**建议方向**

由会话模块统一发布/接收跨页签的登录、退出、改密事件，身份失效时阻断业务 UI 并清除 File/表单/私有缓存；在重新激活页面时确认 session 再开放写操作。写操作应绑定发起时的账号/会话代次，并在实际提交身份变化时拒绝或要求重选文件。仅将 staleTime 调小无法消除并发身份变化窗口。

**测试缺口**：`frontend/src/app/SessionIsolation.test.tsx:74-75` 手动 `invalidateQueries(['session'])`，因此主动绕过了真实场景缺失的切换发现机制。应增加同 context 两个页签通过真实 UI 切换账号的测试。

## FE-A02：已知无权限后仍展示管理员账号缓存

**定位**

- `frontend/src/features/users/UserPages.tsx:194-196`：展示 `list.error` 后仍无条件展示 `list.data`。
- `frontend/src/features/users/UserPages.tsx:222-272`：继续渲染缓存学号、姓名、专业、指导教师和管理操作。
- `frontend/src/features/users/UserPages.tsx:27-29`：管理员概览采用同样模式（本次具体复现的是账号表）。
- `frontend/src/shared/api/http.ts:47-52`：一般 403 不触发 session 刷新。
- 对比已有正确边界：`frontend/src/features/students/TeacherPages.tsx:28-29` 与 `frontend/src/features/submissions/SubmissionPages.tsx:125-126` 使用 `blocksCachedData` 隐藏 401/403/404 下的业务数据。

**触发与证据**

1. 页签 C 登录管理员并打开账号管理，加载真实账号列表。
2. 同 context 页签 D 退出管理员、登录学生乙。
3. 在 session 30 秒 fresh 窗口内，等待业务缓存 10 秒 stale，然后在 C 从个人信息返回账号管理。
4. 真实 `/api/admin/users` 返回 **403**，页面显示“没有权限执行此操作，或操作凭证已过期”，下方仍显示缓存的六条账号和编辑/停用/重置按钮；真实 `/api/auth/me` 角色为 **STUDENT**。

这是前端缓存数据泄漏，不是后端 API 越权：服务器已经正确拒绝了请求。即使后续解决 FE-A01 的跨页签同步，所有有权限数据的输出面仍应在已知拒绝时 fail closed；不能仅展示错误条然后保留敏感表格。

**建议方向**

账号表与概览使用统一的授权失效展示规则，401/403/404 后立即隐藏对应缓存内容和动作，并通知会话 owner 重新确认身份。网络/5xx 是否展示已标明 stale 的缓存应与权限拒绝分开。增加“先有缓存，下一次真实/边界响应 403”的管理员页回归。

## FE-A03：离页上传成功后列表不会自动更新

**定位**

- `frontend/src/features/submissions/SubmissionPages.tsx:27-29,41-47`：缓存写入、列表失效和导航都只放在 `mutate` 的组件级 `onSuccess` 中。上传组件卸载后，该回调不执行。
- `frontend/src/main.tsx:9-15`：业务 query 10 秒内仍 fresh。
- `frontend/src/features/submissions/SubmissionPages.tsx:119-124`：只在当前列表中存在 PENDING/PROCESSING 时轮询；旧空列表或全部终态列表不会轮询。

**触发与证据**

1. 生产页面加载空提交列表，首个列表 GET 完成。
2. 发起上传，在响应返回前跳到个人信息页；随后提交 API 在测试边界返回 202/PENDING。
3. 在 10 秒内回“我的论文”，旧空列表仍 fresh，不触发 GET；之后持续等待 11.5 秒，列表 GET 总数仍是 **1**，仍显示“还没有提交记录”。
4. 点击“刷新记录”立刻获取并展示该 PENDING 记录。

本项只替换提交列表/上传两个外部 HTTP 边界，以便控制 202 时序；认证与页面为真实实现。未声称验证 COS、worker 或真实提交持久化。影响是上传成功被表现为无记录，用户可能误判上传丢失并重复提交；页面“可离开，审核仍继续”的流程缺少结果恢复。

**建议方向**

将账号/会话代次校验后的列表失效置于不依赖 UploadForm 是否挂载的成功处理，避免新账号被旧响应污染；导航仍限定发起页面存活。既有回归只断言不跳转且不填详情缓存，应补充回到列表时能取得后台完成的提交。

## 复现命令与产物

工作目录为项目根目录。两个脚本均已实际执行，退出码 **0** 表示成功捕获上述缺陷，不表示产品通过验收：

```powershell
node artifacts/platform-audit/frontend-session-audit.mjs
node artifacts/platform-audit/frontend-background-upload-audit.mjs
```

- `artifacts/platform-audit/frontend-session-evidence.json`：FE-A01/02 身份和 HTTP 状态记录。
- `artifacts/platform-audit/cross-tab-upload-owner.png`：旧身份和选中文件、拦截错误。
- `artifacts/platform-audit/admin-cache-after-403.png`：403 错误与缓存账号表同屏，已人工查看截图。
- `artifacts/platform-audit/frontend-background-upload-evidence.json`：FE-A03 GET 计数与手动恢复。
- `artifacts/platform-audit/background-upload-empty-cache.png`：后台完成后旧空列表。

会话脚本只从现有 `.env` 读取本地管理员凭据用于登录，不输出凭据；它在隔离数据库中创建两名专用审计学生并设置各自密码。账号 ID/用户名记录在证据文件，密码不入证据。后一个脚本依赖前一个脚本创建的审计账号。没有删除其他人的本地数据。

## 其余审计面与限制

- 上传校验：大小、空文件、扩展名在浏览器和后端分别有边界；没有将静态校验当作 DOCX 内容验收。
- 下载：路径以 submission ID 与 original/marked 构造，走授权 API，保存文件名过滤目录/控制符；本次没有有效 COS，不确认真实流、文件内容或中断恢复。
- 导入：预检/执行使用同一 File；更换 File/角色清除结果；服务重新校验契约与前端一致；错误 CSV 有引号与公式前缀处理。本轮未新增确定可复现缺陷。
- 表单/路由：原生控件、首次改密/角色路由和字符串 ID 契约已对照；前端 guard 不被当作后端安全边界。
- 本轮不是完整视觉/a11y 审计；未覆盖全部视口/缩放/键盘路径，不复述既有证据为本代理验证结果。
- 本文为审计产物，不修复问题，不修改 task 生命周期、不提交、不部署。修复与回归应由主 Agent 按用户授权安排。
