# 登录学院选择契约

## 1. Scope / Trigger

修改登录表单、匿名学院目录、登录请求或会话建立时适用。学院是登录核对信息；归属和授权继续以数据库为准。仅增加现有学院选择，不提供匿名新增学院能力。

## 2. Signatures

- `GET /api/auth/colleges` → ApiResponse 中 data 为 `[{code:string,name:string}]`。
- `POST /api/auth/login {username,password,collegeCode:string|null}` → 原 UserView。
- `AuthService.validateCollegeSelection(String accountCollegeCode, String selectedCollegeCode)`：输入前者必须来自当前数据库用户视图，不来自会话旧值或客户端。
- 不增加数据库字段、角色或环境配置；完整学院名单通过V7扩展两处合法代码CHECK，既有账号归属不自动迁移。

## 3. Contracts

- College枚举为选项目录唯一来源，sys_user.college_code为账号归属权威。学校不作为新身份选择器。
- 登录顺序固定：校验凭据/启停 → 当前数据库用户视图 → 学院匹配 → 建立并轮换会话/CSRF。失败不创建新身份，也不替换原有成功会话。
- 有归属的账号必须选择匹配学院；校级管理员及旧未分配账号留空。不根据所选学院补写归属、推断角色或扩大权限。
- null、空字符串或全空白代表未选择；非空必须是合法College代码，长度上限32。请求DTO继续隐藏诊断日志中的凭据。
- 仅公开GET目录；SessionGuardFilter仍先处理失效/停用会话，之后允许读取目录。强制改密账号可读目录，不能因此读取其他业务或写入目录。
- 前端React Query仅在会话初始化完成且未登录时加载目录；沿用统一请求/CSRF/envelope，目录失败或为空不伪造选项，提供重试。表单提示空学院账号可留空，错误时保留输入，待处理时防重复提交；不持久化密码。
- 前后端需配套发布。登录功能回退不删除学院数据；若已经写入新学院账号/专业，不得直接降级到不认识新码的后端。旧调用方若使用有学院账号，须同步提交collegeCode。

## 4. Validation & Error Matrix

| 情况 | 结果 |
| --- | --- |
| 匿名GET目录 | 200，仅学院代码和名称 |
| 错误凭据/停用账号 | 沿用401 BAD_CREDENTIALS / ACCOUNT_DISABLED，未建立会话 |
| 有学院账号留空 | 400 COLLEGE_REQUIRED：请选择账号所属学院 |
| 所选学院与归属不符，含空归属选了学院 | 400 LOGIN_COLLEGE_MISMATCH；提示核对，校级/未分配账号留空 |
| 非法学院代码 | 400 INVALID_COLLEGE |
| 请求字段长度/结构不合法 | 沿用VALIDATION_ERROR / INVALID_REQUEST |
| 登录缺少CSRF | 403，不能因新目录放宽 |
| 目录网络错误/空列表 | 页面显示错误或空态和重试，保留表单，不提交假学院 |

## 5. Good / Base / Bad Cases

- 正常：教师选择所属学院、输入正确凭据，进入原教师首页。
- 基准：校级管理员学院留空，继续进入管理员首页；历史未分配师生亦允许留空。
- 异常：已登录管理员尝试用另一个学生账号及错误学院登录，返回错误，原管理员身份及session ID不变。

## 6. Tests Required

- HTTP穿过真实安全过滤器、密码校验及AuthService，覆盖三角色、漏选/错选/非法码、错误凭据、停用、CSRF、匿名/过期/强制改密目录访问。隔离repository不冒充真实PG。
- 真实PG核对数据库归属、拒绝后无新会话及原会话不被错误切换；已有集成登录helper提交对应真实学院。
- 浏览器验证真实Select与提交payload、校管留空、加载/空/失败重试、错误恢复、待处理、键盘、窄屏与缩放。受控HTTP fixture不冒充生产联调。

## 7. Wrong vs Correct

错误：复制前端三院常量、开放管理员目录、先建立会话再核对，或把学院选项写回账号。正确：仅提供College.options的公开只读投影，凭据通过后按数据库归属核对，最后建立会话，保留原授权边界。
