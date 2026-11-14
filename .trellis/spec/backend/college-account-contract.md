# 学院账号与专业目录契约

## 1. Scope / Trigger

- 修改账号列表、创建、编辑、启停、删除、Excel 导入、导师候选或专业目录时适用。毕业生按导师名单管理，不引入班级、年级分组。
- 学校固定“山东石油化工学院”；三个学院由后端 College 枚举与 Flyway 检查约束限定：INFORMATION_ENGINEERING、EDUCATION_ARTS、ECONOMICS_MANAGEMENT。
- sys_user.college_code 是归属权威字段。旧值 null 表示待归属，不根据专业、导师或前端选项推断；ADMIN 且学院为空是校级管理员，非空是学院管理员。

## 2. Signatures

| 接口 | 参数/响应 data |
| --- | --- |
| GET /api/admin/catalog | {schoolName,colleges:[{code,name}]}；校管返回三院，院管仅本院 |
| GET /api/admin/majors?collegeCode= | [{id:string,collegeCode,name,active}]，包含停用项 |
| POST /api/admin/majors | {collegeCode,name} → 201 MajorView |
| PATCH /api/admin/majors/{id}/active | {active:boolean} → MajorView |
| GET /api/admin/users | role 必传；q="",active?,collegeCode?,page=1,size=20 → PageResponse<UserView> |
| GET /api/admin/teachers | collegeCode 必传，q=""；最多100名本院启用教师，按账号、ID排序 |
| POST /api/admin/users | {username,name,role,major?,teacherId?,collegeCode} → 200 UserView |
| PATCH /api/admin/users/{id} | {name,major?,teacherId?,collegeCode} → UserView |
| POST /api/admin/users/{id}/active | {active:boolean} → {user,assignedStudents,message} |
| POST /api/admin/users/{id}/reset-password | → UserView，恢复初始密码；教师/管理员须改密，学生无需强制改密 |
| DELETE /api/admin/users/{id} | → {outcome:'DELETED'或'DEACTIVATED',user:UserView或null,message} |
| 管理员 Excel preview/execute | multipart 增 collegeCode；校管必传，院管可继承；模板不按院区分 |
| GET /api/teacher/majors | 当前教师本院启用专业；无学院返回409 COLLEGE_UNASSIGNED |

- UserView 增加 collegeCode、collegeName，未知学院两项为空。
- V3 增加可空 sys_user.college_code varchar(32) 和索引 (college_code,role,is_active)；新增 major_catalog(id bigint identity,college_code varchar(32),name varchar(64),is_active boolean)，同院专业名唯一。迁移不自动补旧账号学院，不改提交记录。

## 3. Contracts

- 沿用会话、CSRF、教师/管理员首次改密限制、{data,error,requestId}、字符串ID、UTC时间和1起始分页（每页1–100）。学生新建、导入、重置及存量迁移后 `mustChangePassword=false`；学生自愿修改密码仍可用。未知JSON字段拒绝，不能忽略伪造权限字段。
- 每请求从数据库读取当前账号；写操作锁定操作者行，再校验角色、启用状态、改密状态和学院。会话中的旧学院不能授权。
- 校管管理全校师生与学院管理员，不能操作校级管理员目标；院管只能管理本院师生和专业，不能列出或管理任何管理员。学院查询和目标ID均在服务端约束。
- 新账号必须有合法学院；校管创建的ADMIN只能是学院管理员。院管创建师生可继承自身学院，不能指定其他学院。
- username为3–32位字母、数字、下划线；姓名非空且最多64字符；专业可空且最多64字符；teacherId可空，提供时为正整数字符串。只有学生可有专业、导师。
- 专业名trim、非空、最多64字符；同院同名包含停用项，不因停用释放名称。新建或更改学生专业须在本院启用目录中。未改动的历史专业可保留；从已有非空学院转院必须重新校验。旧账号从未知学院补录时不猜测或抹去历史专业。
- 学生导师须是同院、启用的教师；创建或更新学生时锁导师行。教师已有学生且原学院非空时，变更学院返回冲突；补录旧教师学院不自动修改其学生。
- 删除按**受保护入向引用**判断：被学生引用的教师、有任意提交记录的学生原子停用，保留关联与历史，返回DEACTIVATED及账号；无此类引用时永久删除，返回DELETED、user=null。学生仅有teacherId、没有提交时允许删除，保留教师。
- 删除与新增关联共享被引用账号行锁：创建学生锁导师；代理上传与直传预留在短事务内再次锁定、核验学生后创建提交。停用/删除先提交时，迟到创建不得沿用事务外旧权限；创建先提交时，删除查到关联后停用。COS传输与签名不持有数据库行锁。数据库外键保留ON DELETE RESTRICT，不做级联删除。
- 教师名单导入和统计的逐行事务、十项口径见[教师名单契约](teacher-roster-contract.md)；教师新增学生从数据库当前教师继承学院及teacherId。

## 4. Validation & Error Matrix

| 条件 | HTTP / error.code |
| --- | --- |
| 未登录 | 401 UNAUTHENTICATED；HTTP会话守卫处理失效会话 |
| 教师/管理员首次未改密、停用或已删除账号 | 会话守卫401/403；服务写事务重读时拒绝，无新记录。学生无需首次改密 |
| 学院必填未提供 / 非法代码 | 400 COLLEGE_REQUIRED / 400 INVALID_COLLEGE；必传查询参数缺失为400 INVALID_REQUEST |
| 跨院目标、院管操作管理员、校级管理员目标 | 403 COLLEGE_SCOPE_FORBIDDEN |
| 教师学院未分配 | 409 COLLEGE_UNASSIGNED |
| 用户不存在 / 用户名重复 | 404 USER_NOT_FOUND / 409 USERNAME_EXISTS |
| 校验注解不通过 / 搜索超过100字符 / 非法分页 | 400 VALIDATION_ERROR / 400 INVALID_QUERY / 400 INVALID_PAGE |
| 非学生带专业或导师 | 400 INVALID_PROFILE |
| 导师不存在、停用、跨院、非教师、超long范围 | 400 INVALID_TEACHER；字符串格式不满足正整数为400 VALIDATION_ERROR |
| 非本院启用专业 / 专业不存在 / 同院重名 | 400 INVALID_MAJOR / 404 MAJOR_NOT_FOUND / 409 MAJOR_EXISTS |
| 有学生的教师转离原非空学院 | 409 TEACHER_HAS_STUDENTS |
| 外键、唯一约束等数据库完整性冲突 | 409 DATA_CONFLICT，不伪造删除或创建成功 |

## 5. Good / Base / Bad Cases

- Good：校管录入本院专业和导师；院管只能选择本院启用导师，伪造跨院ID被拒绝。有关联教师删除返回停用，学生和论文均保留。
- Base：旧账号无学院，只向校管呈现待归属；空专业目录不造选项。学生关联导师但没有论文可删除，导师仍存在。
- Bad：从专业名称猜学院、只在前端过滤导师、把teacherId视为学生的删除保护入向引用，或在学生停用后仍用初步校验结果新建提交。

## 6. Tests Required

- AccountSecurityIntegrationTest：校管/院管列表、候选、创建、编辑、停用、删除和导入隔离；跨院ID拒绝；旧会话学院改变与停用立即生效；专业启停、重名和旧专业保留。新建及重置后的学生可直接访问 `GET /api/submissions`，教师/管理员仍须改密。
- Flyway V4 升级测试：只清除既有学生强制改密标志，保持所有账号的密码哈希及教师/管理员标志原值。
- 删除断言：无关联教师永久删除；有关联教师和有提交学生停用；仅有导师的学生永久删除且导师保留；校级管理员拒绝删除。
- SubmissionIntegrationTest：真实PostgreSQL行锁下，代理上传和直传在并发学生停用提交前等待，提交后返回拒绝且无论文记录/存储对象；校验期间学生被删除也不能创建记录。
- TeacherStudentIntegrationTest、TeacherStatisticsIntegrationTest：学院继承、专业目录、名单只归属于当前教师及统计口径；ReportAvailabilityMigrationTest：V1→latest保留旧账号和提交，学院保持null。
- AdminScopeTest：校级与学院级作用域。执行Maven compile/testCompile与全量测试；数据库环境不可用时明确区分已通过单测和未执行的PostgreSQL断言，不以H2或mock冒充。

## 7. Wrong vs Correct

- Wrong：DELETE直接级联删除论文，或学生只因有导师就一律停用。
- Correct：事务内锁目标，再检查指向目标的受保护引用；有关联返回真实DEACTIVATED，无提交学生可删并保留导师。
- Wrong：上传先读取启用状态，在后续事务直接写入论文。
- Correct：预留提交的短事务再次findByIdForUpdate并核验学生；传输在事务外，权限撤销与新建记录按数据库提交顺序处理。
