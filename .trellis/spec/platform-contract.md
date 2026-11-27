# 论文审核平台契约

## 服务与模型

业务服务 `platform-backend` 包根 `cn.luwneshacha.platform`，端口18099。现有backend引擎18098只对业务服务网络开放。PG16，Flyway显式迁移；Hibernate ddl-auto=validate。账号与提交表不级联删除；专业目录由独立表维护。时间Instant/UTC；所有API id为字符串，计数数字。

`common.ApiResponse<T>(data,error,requestId)` 统一envelope，成功error=null；`common.ApiException(HttpStatus status,String code,String message)`；`common.PageResponse<T>(List<T> items,long total,int page,int size)`，page从1开始，size默认20最大100。错误中文，技术细节仅日志。参数超界400。HTTP 401未登录，403无权/须改密，404不存在，409重复，413超大小。

`account.Role`枚举STUDENT/TEACHER/ADMIN。`account.User` JPA实体getId/getUsername/getRole/getName/getMajor/getGrade/getCollegeCode/getTeacherId/getPasswordHash/isActive/isMustChangePassword/getCreatedAt/getUpdatedAt。`account.UserRepository extends JpaRepository<User,Long>`，findByUsername、existsByUsername。`auth.CurrentUser` record(long id,String username,Role role,String name,boolean mustChangePassword)，通过@AuthenticationPrincipal注入。

`account.UserView` 除原字段外包含 grade:number|null（仅学生）及学院名称。旧学生 grade=null 表示未登记，不能从学号推断；教师可有可选专业，管理员无专业/年级。其它模块不暴露User实体。

学院管理员（ADMIN且collegeCode非空）可同时担任指导教师，复用同一账号与学生teacherId关联；校级管理员（ADMIN且collegeCode为空）不具备指导能力。没有新增角色或数据库迁移。管理员的学院管理范围不等于论文读取范围，院管指导能力额外限制本人指导且同院。

## API

所有JSON请求响应camelCase，POST/PATCH携带GET /api/auth/csrf返回的headerName与token，credentials include。

- GET /api/auth/csrf → {token,headerName}
- GET /api/auth/colleges → [{code,name}]，匿名只读，来自College枚举
- POST /api/auth/login {username,password,collegeCode:string|null} → UserView；凭据通过后、建立会话前核对当前账号学院。有学院必须匹配，校级或历史未分配学院账号留空；详见[登录学院选择契约](backend/login-college-contract.md)
- POST /api/auth/logout → null
- GET /api/auth/me → UserView
- POST /api/auth/password {oldPassword,newPassword,confirmPassword} → UserView
- GET /api/admin/users?role=STUDENT|TEACHER|ADMIN&q=&active=true|false&collegeCode=&grade=2023|UNSET&page=1&size=20 → PageResponse<UserView>；grade 仅学生角色可用，不传含旧空值；ADMIN 仅校级管理员可查
- POST /api/admin/users {username,name,role,collegeCode,major?,teacherId?,grade?} → UserView，初始密码123456；新学生 grade 必填、2000–2100
- PATCH /api/admin/users/{id} {name,collegeCode,major?,teacherId?,grade?} → UserView；username/role不可修改，旧学生可补 grade
- DELETE /api/admin/users/{id} → {outcome:DELETED|DEACTIVATED,user,message}；详见学院账号契约
- POST /api/admin/users/{id}/active {active} → {user:UserView,assignedStudents:number,message:string|null}
- POST /api/admin/users/{id}/reset-password → UserView
- GET /api/admin/teachers?collegeCode=&q=&page=1&size=20 → PageResponse<UserView>，同院启用教师及学院管理员，服务端搜索分页，不静默截断候选；校级管理员不作为导师候选
- GET /api/admin/catalog → {schoolName,colleges:[{code,name}]}
- GET /api/admin/majors?collegeCode= → [{id,collegeCode,name,active}]
- POST /api/admin/majors {collegeCode,name} → Major；PATCH /api/admin/majors/{id}/active {active} → Major
- PATCH /api/admin/majors/{id} {name} → Major；同院重名含停用项，保留学院/ID/启停状态。同事务同步更新同院使用旧专业名称的STUDENT/TEACHER账号（含停用）的专业及updatedAt；其他字段及学院不变，失败全部回滚。改名和专业启停共用专业行锁，账号正式专业赋值与改名另用事务级协调锁防止并发写回旧名，详见学院账号契约。
- GET /api/admin/dashboard → {activeStudents,activeTeachers,totalSubmissions,dailySubmissions:[{date,count}],statusDistribution:[{status,count}],studentsByCollege:[{collegeCode,collegeName,count}]}；日期按 Asia/Shanghai，学院范围由服务端限定
- GET /api/admin/imports/template?role=STUDENT|TEACHER → xlsx下载
- POST /api/admin/imports/preview multipart(file,role,collegeCode?) → ImportResult
- POST /api/admin/imports/execute multipart(file,role,collegeCode?) → ImportResult
- 管理员 Excel：学生列为学号、姓名、学校、学院、专业、年级、指导教师工号；教师列为工号、姓名、学校、学院、专业。校级管理员可导入多学院，学院管理员只可导本院；学校及学院逐行核对，Excel 不构成权限来源。
- 管理员学生导入的专业、指导教师工号均必填，预检与执行在跳过已有账号前再次校验；全空白按缺失处理，两项同时缺失合并提示且失败数按行计。教师账号导入和本人指导名单导入不新增此要求，后者仍自动绑定当前导师。
- ImportResult={totalRows,validRows,successCount,skippedCount,failedCount,errors:[{row,username,message}]}；预检successCount=0，错误可由前端将响应CSV导出，执行必须重读文件/数据库，不接受前端可导入名单。
- GET /api/submissions?page=1&size=20 → PageResponse<SubmissionView>，仅学生本人
- GET /api/submissions/transfer-mode → {mode:direct|proxy}，学生、教师和合资格院管可读，校级管理员拒绝；详情页下载策略依赖此接口
- POST /api/submissions multipart(file) → 202 SubmissionView
- GET /api/submissions/{id} → SubmissionView，仅本人/当前导师
- GET /api/submissions/{id}/files/original|marked|report → 流式DOCX下载；FAILED拒绝，marked仅SUCCESS，report仅SUCCESS且report_available=true
- GET /api/submissions/{id}/files/{kind}/url → 短期COS预签名下载；kind同上，复用相同授权与报告可用性检查，report仅指向当前attempt的report.docx。
- GET /api/teacher/students?q=&page=1&size=20 → PageResponse<StudentSummary>
- POST /api/teacher/students {username,name,major?,grade} → UserView
- GET /api/teacher/majors → 本院启用的 Major[]
- GET /api/teacher/students/imports/template → xlsx下载；POST /api/teacher/students/imports/preview|execute multipart(file) → ImportResult
- GET /api/teacher/statistics?grade= → 教师名单与提交的十项实时汇总，详见教师名单接口契约
- GET /api/teacher/statistics/insights?grade=&range=30|90|365|ALL&page=1&size=20 → 真实规则榜单、问题分布、每日提交、热力图、分页学生明细与规则索引覆盖率
- 洞察topRules包含{ruleId,description,count,affectedStudents}；count是问题次数，affectedStudents是同规则涉及的去重学生人数，按本人名单及相同年级/日期、最新SUCCESS/READY索引口径从数据库聚合。常规榜单与热力图不重复显示覆盖文案，索引不完整的页面级警告保留。
- GET /api/teacher/statistics/export?grade=&range=30|90|365|ALL → 同过滤口径的 XLSX 学生明细
- GET /api/teacher/students/{id} → UserView
- GET /api/teacher/students/{id}/submissions?page=1&size=20 → PageResponse<SubmissionView>
- StudentSummary={id,username,name,major,grade,submissionCount,lastSubmittedAt,latestProblemCount}；后3项仅基于SUCCESS，最近时间与问题数来自同一条最近成功记录。
- 学生名单、本人名单新增/导入、专业选项、学生历史及提交详情/下载允许教师和学院管理员；院管每个对象读取和异步下载开始前均重新校验本人指导且同院。校级管理员不得使用指导接口。教师统计、规则分析和统计导出仍只向TEACHER角色开放。
- SubmissionView={id,studentId,submissionNumber,originalFilename,fileSize,status,problemCount,byScope,errorMessage,createdAt,startedAt,finishedAt,canDownloadOriginal,canDownloadMarked,canDownloadReport,uploadReserved}。非SUCCESS problemCount=null、byScope=null。状态枚举PENDING/PROCESSING/SUCCESS/FAILED；byScope为中文章节名→数量或null。
- GET /api/health → {status:UP} envelope，无敏感配置。

## 安全与流程

浏览器不得通过nginx或Vite绕过平台访问/api/inspections。后台认证用实时DB与HttpSession密码指纹，重置密码立即使旧会话失效；通过账号管理新建或重置的教师、管理员必须修改初始密码，学生可自愿改密。环境引导创建的校级管理员沿用其原有密码策略。

下载凭数据库storageDir构造固定对象键，不接受用户任意COS路径。DB内studentId与teacherId校验，不用前端角色授权。原始文件名只用于显示和Content-Disposition（过滤目录/控制符，UTF8）；COS使用随机目录。

学院管理员增加“我的学生”菜单但保留管理菜单；校级管理员不显示指导入口，直接访问指导路由亦拒绝。学生绑定导师的创建、编辑和Excel导入沿用统一同院启用导师校验，接受学院管理员。已有指导学生的院管同样受到迁学院、停用提示和关联删除保护。

失败文件保留策略以需求第7.3节为准，覆盖第8节一般永久保留规则。成功记录/文件不自动清理。旧引擎临时文件保留清理与COS永久文件互不混用。

上传顺序固定为校验→短事务持久PENDING预留→COS原稿→短事务条件释放。attempts=0且updatedAt在未来代表尚未释放的上传预留；默认10分钟，预留期间不可领取/下载。COS失败持久标记FAILED并补偿删除；释放失败不删原稿，到期由正常worker恢复，缺稿最终FAILED。每次审核以attempts和租约条件更新完成，结果放独立attempt-N目录；清理先确认持久状态，数据库状态不可读时保留文件，绝不删除同attempt的SUCCESS文件。COS请求及整个下载流共用5分钟总预算，引擎审核加标注下载共用默认10分钟总预算；worker默认40分钟，启动验证lease必须大于engine timeout加21分钟存储/提交预算。所有外部调用在数据库短事务之外。

初始管理员账号/密码通过环境注入，只有数据库尚无管理员时创建；不提供默认生产管理员密码。BCrypt支持6–128字符须处理72字节限制，统一预哈希后BCrypt且测试长中文密码不截断。

## 前端设计约束

采用现有系统重构模式，保留React/TS/Vite/CSS与深绿基调。桌面1280/1440/1920、200%缩放可用。紧凑列表/详情/表单，不保留旧逐条问题列表。页面主任务：学生上传/查状态，教师搜索学生，管理员管理账号。真实API数据为唯一展示来源，无假统计；Query管理server state，表单局部state，页码搜索URL state。

覆盖loading/empty/error/403/首次改密/pending/success/retry；原生表单、表格、button/link与可见焦点。所有关键操作中文，角色路由拦截仅辅助后端。
