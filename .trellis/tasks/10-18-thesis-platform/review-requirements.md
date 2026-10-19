# 最终需求覆盖审查

审查日期：2026-10-19（Asia/Shanghai）。审查者：`line_fix_review`。模式：只读需求覆盖审查，仅新增本文档；未改源码、未运行 Maven、未启动或变更容器。

## 结论

以两份原文、PRD 与冻结接口契约为准，当前源码未发现缺失的核心业务能力。账号、角色、首次改密、提交与恢复、统计、私有对象存储适配、教师只读访问、管理端、Excel 导入均有对应实现。两张业务表的字段、类型和索引与设计一致。

这不是全链路验收通过声明。真实腾讯云 COS 的私有桶策略、上传/下载/删除、业务服务到真实引擎再到 COS 的成功与损坏文档链路，仍缺外部验收；最终 Docker、浏览器和全量验证由主 Agent 负责。不得用测试存储替身或本地 HTTP adapter 测试替代它们。

原文“点击一行进入提交详情”目前实现为行内明确的“查看”链接，整行没有点击导航；这是已确认的低风险交互差异。状态文案采用“等待审核 / 正在审核 / N 个问题”，含义覆盖原文“排队中 / 审核中 / 存在问题（N）”，未逐字照搬。

## 依据与判定口径

- 原始需求：`research/需求文档.md` v1.0。
- 原始数据设计：`research/数据库设计.md` v1.1。
- 已确认范围与决策：`prd.md`、`.trellis/spec/platform-contract.md`。
- “已实现”表示存在实际生产路径并已阅读其实现；“已有验证证据”只引用当前报告与证据文档，不声称本次重跑；“未验证”表示还缺相应真实环境证据；“未实现”只用于实际没有该行为，不能用来描述外部凭据缺失。
- 原文第7.3节“失败不保留文件”优先于第8节一般保留规则，按冻结契约执行。成功历史永久保留，失败记录保留、文件持续清理。
- 教师最近时间和问题数都取最近SUCCESS，成功提交次数仅计SUCCESS，来自用户明确确认及PRD。
- 原文模块统计是条件要求；当前引擎没有稳定中文章节聚合，`byScope=null`且前端隐藏模块统计符合原文，不属于缺失。
- 上传预留使用PENDING/attempts=0及未来updatedAt，30分钟执行租约与5分钟COS预算已写入契约，是对宕机/失败恢复的实施细化，不扩业务表。

## 需求到实现的映射

以下路径均相对仓库根目录。后端包前缀为 `platform-backend/src/main/java/cn/luwneshacha/platform/`。

| 原文条款 | 覆盖结论 | 具体路径与行为 | 验证边界 |
| --- | --- | --- | --- |
| §1、§2：React三角色、独立业务后端/引擎、COS | 已实现 | `frontend/src/app/App.tsx` 按角色路由；`Application.java`、`engine/HttpAuditEngine.java`、`storage/CosObjectStorage.java`；`compose.yaml` 分离四服务，仅前端暴露宿主端口 | 服务真实组合需主Agent容器验收 |
| §2：无注册、账号管理员创建、管理员初始化 | 已实现 | `account/UserController.java` 只含管理接口；`account/AdministratorBootstrap.java` 仅无管理员时使用环境密码初始化；没有注册接口 | 账号集成测试有证据；首次空库容器启动由主Agent验收 |
| §3：登录错误中文、禁用拒绝 | 已实现 | `auth/AuthService.java` 统一账号密码错误，正确凭据但禁用返回明确中文；`auth/SessionGuardFilter.java` 实时数据库检查 | 真实PG/HTTP认证测试已有证据 |
| §3：初始/重置密码123456、强制改密、6–128字符且不同旧密码 | 已实现 | `account/UserService.java`、`auth/AuthService.java`、`auth/PasswordHasher.java`；`SessionGuardFilter` 强制拦业务；`frontend/src/features/auth/AuthPages.tsx` | 首次改密、重置/禁用/自己改密撤销、长Unicode密码测试有证据 |
| §3：禁用保留历史，无删除入口，全站中文 | 已实现 | `account/User.java` 的is_active；管理Controller无DELETE；`frontend/src/features/`业务页面中文；无提交删除API | 保留字段/路径已静态核对，长期保留需实际COS策略配合 |
| §4.1：上传、倒序分页、时间/文件/状态/问题数、详情出口 | 已实现；整行点击有差异 | `submission/SubmissionRepository.java` 按createdAt DESC/id DESC；`SubmissionService.page`；`frontend/src/features/submissions/SubmissionPages.tsx`列表及“查看”链接 | 业务与前端测试已有证据；整行点击未实现，见下文 |
| §4.2：单DOCX、非空、≤50MiB、非法不建记录、不限提交 | 已实现 | `SubmissionService.upload` 先校验再写预留；`frontend/src/shared/files.ts`；Spring50MiB/52MiB及Nginx52MiB；没有次数上限 | PG非法/超限测试有证据；精确50MiB真实上传需COS |
| §4.2、§7：202排队、后台审核、自动刷新、失败可重提 | 已实现 | `SubmissionController.upload` 202；`SubmissionTasks`/`SubmissionWorker`；前端列表4秒、详情3秒轮询PENDING/PROCESSING | 状态机真实PG测试有证据；真实COS成功终态需验收 |
| §4.3：第N次、时间、原文件名、成功统计 | 已实现 | `SubmissionRepository.submissionNumbers` 按该生createdAt/id实时计数，含失败；`SubmissionView`；详情页 | 有代码/状态测试，未单独运行本次检查 |
| §4.3、§7.3：问题总数及条件模块统计 | 已实现 | `engine/HttpAuditEngine.java`统计真实FAIL；成功写problem_count；`byScope=null`；详情仅SUCCESS且byScope非空时展示 | 13项HTTP adapter证据含FAIL计数；不能把缺少引擎章节字段记为平台漏做 |
| §4.3：审核中下载原稿，成功下载marked，失败无统计/下载 | 已实现 | `SubmissionView`非SUCCESS统计null；`SubmissionService.requireDownloadable`；详情页FAILED不渲染按钮/统计 | 真实PG拒绝测试有证据；上传预留尚未存完原稿时下载禁用符合冻结契约 |
| §4.3：不做逐条网页/问题数据下载 | 已遵守 | 前端无逐条结果页；controller文件kind仅original/marked，report.json只保存在私有COS | 路由与Controller静态核对 |
| §4.4：个人信息只读、可改密 | 已实现 | `AuthPages.ProfilePage` 展示姓名/账号/专业/教师/状态；仅有改密入口；`AuthController`不提供个人资料编辑 | 前端/认证测试证据，真实浏览器由主Agent验证 |
| §5.1：名下学生、学号升序、分页/搜索、SUCCESS统计 | 已实现 | `SubmissionService.students`实时teacherId筛选，username排序；批量`successCounts`/`latestSuccesses`，最近时间与问题数取同条SUCCESS；`TeacherPages.tsx`无成功显示— | PG明确覆盖成功8→成功3→失败，统计仍2次/3问题 |
| §5.2、§5.3：学生信息/历史/共用详情、教师只读 | 已实现 | `SubmissionService.student/studentSubmissions/authorized`；`TeacherPages.tsx`、共用详情页；无批阅、打回、评语接口 | 关系变化、跨学生拒绝测试已有证据 |
| §6.1：启用学生/启用教师/全部提交数与入口 | 已实现 | `SubmissionService.dashboard`只查两项active计数及submission总数；`UserPages.AdminDashboardPage`管理与导入入口 | PG统计测试有证据，不把FAILED从累计数中扣除 |
| §6.2：学生/教师页签、搜索/状态筛选、新增编辑 | 已实现 | `account/UserService`、`UserController`；`UserPages.tsx`、`UserForm.tsx`；只允许学生专业/教师关系，导师搜索仅启用教师 | 账号/前端测试有证据；真实浏览器编辑流程需主Agent |
| §6.2：账号/角色不可变、管理员不被管理操作影响 | 已实现 | UpdateUser不含username/role；Jackson拒绝未知字段；实体updatable=false；managedRole拒绝ADMIN；列表仅STUDENT/TEACHER | 真实PG/HTTP测试覆盖管理员创建/修改拒绝 |
| §6.2：启停、重置、教师名下学生提示 | 已实现 | `UserService.setActive/resetPassword`；教师禁用返回assignedStudents与中文message；界面确认和完成反馈 | 账号测试覆盖分配计数提示和数据保留 |
| §2、§6.2、§8：管理员禁止论文详情和下载 | 已实现 | `auth/SecurityConfig.java`对submissions限制STUDENT/TEACHER，service再次拒绝ADMIN；前端RoleGuard限制共用详情 | HTTP及service测试均有拒绝证据，不能仅凭前端隐藏断言 |
| §6.3：两类模板、预检、确认原文件执行、错误下载 | 已实现 | `importing/ExcelUserFile.java`、`UserImportService.java`、`UserImportController.java`；`ImportsPage.tsx`保存同一File再次执行，CSV含行号/原因 | 预检不写库、执行重读、CSV前端测试有证据 |
| §6.3：xlsx≤5MiB、≤2000行、格式/重复/教师校验 | 已实现 | `ExcelUserFile.read`包/格式/大小/行数校验；`UserImportService`文件内重复全部报错、启用教师通过UserService验证 | 有精确2000行、前导零、公式与损坏包测试 |
| §6.3：既有账号跳过不覆盖、部分成功、无导入历史 | 已实现 | `UserImportService`每行独立事务，执行时重查DB；已有用户名跳过；仅返回ImportResult，无导入表 | 100条成功及重复100条跳过、混合错误测试已有证据 |
| §7.3：损坏不重试、服务异常有限重试、失败中文原因 | 已实现 | `HttpAuditEngine`400/413/422永久失败，其余服务错误重试；`SubmissionTasks`最多3次；`SubmissionWorker`落最终中文错误 | PG/HTTP测试有证据；真实坏DOCX经过真实引擎到FAILED尚需外部链路 |
| §7.3、§8：失败保留记录删除文件，成功永久保留 | 已实现 | `SubmissionWorker.compensateCleanup`仅FAILED全目录与旧attempt；没有成功当前attempt/原稿定时删除；失败清理失败有日志持续补偿 | 清理失败恢复、旧attempt保护、确认丢失保护测试有证据；真实COS删除未验收 |
| §8：原稿/marked/JSON存COS，私有读写、授权下载、命名 | 已实现，云环境未验证 | `CosObjectStorage`SDK HTTPS/Private ACL；COS key固定目录；`SubmissionService`下载开始前再次验证账号、密码指纹、导师、状态；`SubmissionController`UTF8 filename及.marked.docx | 存储适配边界和PG权限测试有证据；桶策略与真正Word下载待COS |
| §10：100条导入/重复/混合文件验收 | 已有集成证据 | `platform-backend/src/test/java/cn/luwneshacha/platform/account/AccountSecurityIntegrationTest.java`相应100条/混合/执行前教师变化用例 | 真实PG，不是内存数据库；浏览器完整导入演示仍独立 |

## 两表与字段逐项核对

权威迁移：`platform-backend/src/main/resources/db/migration/V1__platform_users_and_submissions.sql`。实体：`account/User.java`、`submission/Submission.java`。

| 表 | 字段 | 结果 |
| --- | --- | --- |
| sys_user | id bigint；username varchar(32) unique；password_hash varchar(72)；role varchar(16)；name varchar(64) | 一致；角色CHECK；密码只输出UserView且JsonIgnore |
| sys_user | major varchar(64)；teacher_id bigint可空；must_change_password boolean；is_active boolean；created_at/updated_at timestamptz | 一致；非学生专业/导师须null；teacher_id外键RESTRICT；没有删除API |
| submission | id bigint；student_id bigint；original_filename varchar(255)；file_size bigint；status varchar(16)；storage_dir varchar(255) unique | 一致；student_id外键RESTRICT，状态CHECK，文件大小>0 |
| submission | problem_count int默认0；by_scope jsonb可空；error_message varchar(500)；attempts int默认0 | 一致；problem_count非负；失败保持0而DTO呈null；没有逐条问题表 |
| submission | started_at/finished_at可空timestamptz；created_at/updated_at timestamptz | 一致；UTC Instant；前端Intl按本地时区显示；updated_at预留/重试语义已由契约明确 |
| 索引 | username unique；teacher_id；role+is_active；student_id+created_at；status；storage_dir unique | 全部存在，提交复合索引额外带id保证同时间稳定顺序 |
| 表数量 | 仅sys_user、submission两个业务表 | 符合；Flyway自己的schema history属于迁移元数据，不是新增业务域 |

没有导入批次、逐条问题、审计、任务租约、session等额外业务表；没有级联删除。教师/学生角色关系由业务服务校验，数据库普通FK保证对象存在，不冒称FK本身能约束目标角色。

## 现有证据与本次未验证范围

只读取了现有报告与文档，没有重新执行测试：

- `evidence-accounts.md`：账号/认证/导入17项，后补异步2项；真实PG、Flyway与嵌入式Tomcat证据。
- `evidence-submissions.md`：提交18、HTTP13、COS边界2，共33项；明确COS替身只在外部测试边界。
- 当前 `platform-backend/target/surefire-reports/` 七个报告分别12/3/13/2/2/2/18项，合计52项，均记录0失败/错误/跳过；本审查不将旧报告读取包装成自己的全量执行。最终全量归主Agent记录。
- `evidence-frontend.md`：26项组件/路由/请求边界测试，TypeScript/Vite/format检查通过；不是浏览器布局或真实COS证据。
- `review.md`、`review-frontend.md`：已发现问题与修复路径，可用于交叉核对，不替代部署与浏览器验收。

仍应标记**未验证**：

1. 真实COS私有桶策略（含匿名读写拒绝）、原稿/marked/report写入、授权下载、失败全目录删除及成功文件持久保留。
2. 学生真实上传→后台调用真实引擎→COS产物→SUCCESS→学生/教师下载；损坏原稿→永久FAILED且无文件。这里需要真实COS，不能用本地存储替代。
3. Docker最终镜像、环境注入、四服务健康与重启恢复，以及成功论文数据重启后保留。当前只检查配置存在，主Agent负责实际执行。
4. 真实浏览器Cookie/CSRF、首次改密/重置/禁用、角色跳转、教师解绑、导入部分成功、Word保存命名与内容；以及1280/1440/1920和200%缩放、键盘、dialog焦点恢复、无溢出/遮挡。
5. 容量估算不是性能承诺：原文100GB量级未做真实COS容量或并发吞吐验收；当前单调度worker的吞吐也未跑负载测试。原文没有具体吞吐SLA，不据此断言业务缺失。

## 未实现与明确不做

**未实现的字面交互**：§4.1“点击一行进入详情”的整行点击；当前保留语义明确、键盘可达的“查看”链接，核心详情路径可用。无需为覆盖报告擅自修改源码；是否要求整行点击由主Agent按产品决定。

**未发现缺失的本期核心功能**。以下不属于遗漏：中文章节统计在引擎无该数据时隐藏；教师批阅/打回/评语、通知邮件、网页逐条问题、问题JSON下载、报表/批量论文下载、在线规则配置、PDF审核、移动端重设计、删除账号/提交、导入历史/审计页面均是原文明确不做。旧引擎已有的格式规则继续保留，没有因本平台工作新增网页规则配置入口。

**外部验收阻断**：缺COS凭据使真实存储主链路不能验收。可以说明实现和自动测试已完成，不能说明整个产品已通过全部需求验收。
