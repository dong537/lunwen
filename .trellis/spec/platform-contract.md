# 论文审核平台契约

## 服务与模型

业务服务 `platform-backend` 包根 `cn.luwneshacha.platform`，端口18099。现有backend引擎18098只对业务服务网络开放。PG16，Flyway显式迁移；Hibernate ddl-auto=validate。两个业务表严格按用户设计，不级联删除。时间Instant/UTC；所有API id为字符串，计数数字。

`common.ApiResponse<T>(data,error,requestId)` 统一envelope，成功error=null；`common.ApiException(HttpStatus status,String code,String message)`；`common.PageResponse<T>(List<T> items,long total,int page,int size)`，page从1开始，size默认20最大100。错误中文，技术细节仅日志。参数超界400。HTTP 401未登录，403无权/须改密，404不存在，409重复，413超大小。

`account.Role`枚举STUDENT/TEACHER/ADMIN。`account.User` JPA实体getId/getUsername/getRole/getName/getMajor/getTeacherId/getPasswordHash/isActive/isMustChangePassword/getCreatedAt/getUpdatedAt。`account.UserRepository extends JpaRepository<User,Long>`，findByUsername、existsByUsername。`auth.CurrentUser` record(long id,String username,Role role,String name,boolean mustChangePassword)，通过@AuthenticationPrincipal注入。

`account.UserView` record(String id,String username,String name,Role role,String major,String teacherId,String teacherName,boolean active,boolean mustChangePassword)。其它模块不暴露User实体。

## API

所有JSON请求响应camelCase，POST/PATCH携带GET /api/auth/csrf返回的headerName与token，credentials include。

- GET /api/auth/csrf → {token,headerName}
- POST /api/auth/login {username,password} → UserView
- POST /api/auth/logout → null
- GET /api/auth/me → UserView
- POST /api/auth/password {oldPassword,newPassword,confirmPassword} → UserView
- GET /api/admin/users?role=STUDENT|TEACHER&q=&active=true|false&page=1&size=20 → PageResponse<UserView>
- POST /api/admin/users {username,name,role,major?,teacherId?} → UserView，初始密码123456
- PATCH /api/admin/users/{id} {name,major?,teacherId?} → UserView；username/role不可修改
- POST /api/admin/users/{id}/active {active} → {user:UserView,assignedStudents:number,message:string|null}
- POST /api/admin/users/{id}/reset-password → UserView
- GET /api/admin/teachers?q= → UserView[]，仅启用教师，可搜索至多100项
- GET /api/admin/dashboard → {activeStudents,activeTeachers,totalSubmissions}
- GET /api/admin/imports/template?role=STUDENT|TEACHER → xlsx下载
- POST /api/admin/imports/preview multipart(file,role) → ImportResult
- POST /api/admin/imports/execute multipart(file,role) → ImportResult
- ImportResult={totalRows,validRows,successCount,skippedCount,failedCount,errors:[{row,username,message}]}；预检successCount=0，错误可由前端将响应CSV导出，执行必须重读文件/数据库，不接受前端可导入名单。
- GET /api/submissions?page=1&size=20 → PageResponse<SubmissionView>，仅学生本人
- POST /api/submissions multipart(file) → 202 SubmissionView
- GET /api/submissions/{id} → SubmissionView，仅本人/当前导师
- GET /api/submissions/{id}/files/original|marked → 流式DOCX下载；FAILED拒绝，marked仅SUCCESS
- GET /api/teacher/students?q=&page=1&size=20 → PageResponse<StudentSummary>
- GET /api/teacher/students/{id} → UserView
- GET /api/teacher/students/{id}/submissions?page=1&size=20 → PageResponse<SubmissionView>
- StudentSummary={id,username,name,major,submissionCount,lastSubmittedAt,latestProblemCount}；后3项仅基于SUCCESS，最近时间与问题数来自同一条最近成功记录。
- SubmissionView={id,studentId,submissionNumber,originalFilename,fileSize,status,problemCount,byScope,errorMessage,createdAt,startedAt,finishedAt,canDownloadOriginal,canDownloadMarked}。非SUCCESS problemCount=null、byScope=null。状态枚举PENDING/PROCESSING/SUCCESS/FAILED；byScope为中文章节名→数量或null。
- GET /api/health → {status:UP} envelope，无敏感配置。

## 安全与流程

浏览器不得通过nginx或Vite绕过平台访问/api/inspections。后台认证用实时DB与HttpSession密码指纹，重置密码立即使旧会话失效，必须修改初始密码。

下载凭数据库storageDir构造固定对象键，不接受用户任意COS路径。DB内studentId与teacherId校验，不用前端角色授权。原始文件名只用于显示和Content-Disposition（过滤目录/控制符，UTF8）；COS使用随机目录。

失败文件保留策略以需求第7.3节为准，覆盖第8节一般永久保留规则。成功记录/文件不自动清理。旧引擎临时文件保留清理与COS永久文件互不混用。

上传顺序固定为校验→短事务持久PENDING预留→COS原稿→短事务条件释放。attempts=0且updatedAt在未来代表尚未释放的上传预留；默认10分钟，预留期间不可领取/下载。COS失败持久标记FAILED并补偿删除；释放失败不删原稿，到期由正常worker恢复，缺稿最终FAILED。每次审核以attempts和租约条件更新完成，结果放独立attempt-N目录；清理先确认持久状态，数据库状态不可读时保留文件，绝不删除同attempt的SUCCESS文件。COS请求及整个下载流共用5分钟总预算，引擎审核加标注下载共用默认10分钟总预算；worker默认30分钟，启动验证lease必须大于engine timeout加16分钟存储/提交预算。所有外部调用在数据库短事务之外。

初始管理员账号/密码通过环境注入，只有数据库尚无管理员时创建；不提供默认生产管理员密码。BCrypt支持6–128字符须处理72字节限制，统一预哈希后BCrypt且测试长中文密码不截断。

## 前端设计约束

采用现有系统重构模式，保留React/TS/Vite/CSS与深绿基调。桌面1280/1440/1920、200%缩放可用。紧凑列表/详情/表单，不保留旧逐条问题列表。页面主任务：学生上传/查状态，教师搜索学生，管理员管理账号。真实API数据为唯一展示来源，无假统计；Query管理server state，表单局部state，页码搜索URL state。

覆盖loading/empty/error/403/首次改密/pending/success/retry；原生表单、表格、button/link与可见焦点。所有关键操作中文，角色路由拦截仅辅助后端。
