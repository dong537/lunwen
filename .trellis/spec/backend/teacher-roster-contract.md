# 教师指导名单接口契约

## Scenario: 指导名单导入与统计

### 1. Scope / Trigger

- 教师按自己的毕业生指导名单创建学生账号、导入 Excel 名单并查看其名下学生和提交统计时使用。
- 名单归属唯一来源为 `sys_user.teacher_id`，没有班级或年级分组字段、筛选、请求参数或数据库迁移。
- 仅实现后端；沿用现有会话、CSRF、响应 envelope、账户和提交表。

### 2. Signatures

- `POST /api/teacher/students`：JSON `{username, name, major?}`，成功 `201 UserView`。
- `GET /api/teacher/students/imports/template`：返回 `.xlsx` 模板，列为学号、姓名、专业。
- `POST /api/teacher/students/imports/preview|execute`：multipart 字段仅 `file`，返回既有 `ImportResult`。
- `GET /api/teacher/statistics`：无参数，返回扁平汇总。

### 3. Contracts

- 服务端固定新账号角色为 `STUDENT`、指导教师为当前会话对应且数据库中有效的教师；忽略客户端指定角色或指导关系不安全，必须拒绝未知字段。
- 文件每行包含学号、姓名、专业；专业可空。文件限制为 `.xlsx`、5 MiB、2000 条数据行、单工作表，不执行公式。
- 预检只验证，不写数据库；执行时重读文件和数据库，每行独立提交。文件内重复学号全部失败；已存在账号跳过，不覆盖资料或转移指导关系。
- `ImportResult` 字段：`totalRows, validRows, successCount, skippedCount, failedCount, errors[{row,username,message}]`。部分失败仍返回逐行结果；账号撤权或数据库等系统异常终止请求，先前独立提交的行保留。
- 统计 `data` 直接含十项：`totalStudents, activeStudents, submittedStudents, notSubmittedStudents, studentsWithSuccessfulReview, successfulSubmissions, pendingSubmissions, processingSubmissions, failedSubmissions, latestSuccessfulProblemCount`。
- 名下学生总数包含停用账号；submitted 是至少一条持久提交的去重学生数，notSubmitted 为差额；四状态是提交记录数。最近成功问题和取每个学生按 `createdAt DESC, id DESC` 排序的最新 SUCCESS 之和。无成功结果为 `null`，真实零问题为 `0`；较新的失败不覆盖之前的成功。
- 学生列表和 `UserView` 使用现有契约，不增加年级/班级属性。统计只通过当前教师 ID 过滤数据库，不读 COS 或论文对象。

### 4. Validation & Error Matrix

| 条件 | 结果 |
| --- | --- |
| 教师未登录或账号状态失效 | 401；停用账号由会话守卫撤销会话 |
| 非教师或首次密码未修改 | 403 |
| 学号/姓名/专业字段错误 | 400，稳定中文错误 |
| 客户端提交角色、teacherId 或契约外的分组字段 | 400，不写入账号 |
| 已存在学号 | 单个创建返回 409；名单导入统计为跳过，不修改现有资料 |
| 文件类型、表头、公式、行数错误 | 文件结构错误返回 400；可定位行错误写入 `errors` |
| 文件超过 5 MiB | 413，不写入账号 |
| 文件内学号重复 | 重复的每一行记失败，不选择其中一行写入 |
| 导入执行时教师被停用/撤权或数据库故障 | 终止请求；此前已提交行不回滚 |
| 无任何成功审核 | `latestSuccessfulProblemCount = null`，其他计数按实际记录返回 |

### 5. Good / Base / Bad Cases

- Good：教师导入三列名单，新学生只归属于当前教师；无效行保留其他成功行，报告逐行原因。
- Base：空名单统计所有人数和记录数为零，最近成功问题数为 `null`。
- Bad：接受客户端给出的教师归属、把名单按班级过滤、覆盖已存在学生、或把最近失败的格式问题数显示为最新成功结果。

### 6. Tests Required

- 真实 PostgreSQL 与 MockMvc 测试：匿名、学生、管理员、未改密、停用教师及 CSRF。
- 创建与导入断言：未知字段拒绝；教师归属固定；重复账号不覆盖、不转移；预检不写入；文件限制与公式校验；文件内重复全部失败；部分成功保留。
- 并发测试：两个教师同时导入同一学号时仅创建一个账号，归属保持由成功创建者确定。
- 撤权测试：教师在多行执行途中失效时停止后续行，之前提交行保留。
- 统计测试：不同教师隔离、停用学生、提交状态记录口径、同一时间按 ID 决胜、最近失败不覆盖、无成功为 null、成功零问题为 0。
- 按平台 CI 执行 `mvn -B -f platform-backend/pom.xml verify`。

### 7. Wrong vs Correct

#### Wrong

按年级或班级生成名单分组和统计接口，或允许教师从请求体指定 `teacherId`。

#### Correct

教师上传不含分组字段的毕业生名单；后端用当前教师身份写入归属，并只查询其数据库名下学生。
