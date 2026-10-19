# 认证、账号及导入审计

日期：2026-10-19。生产源码与既有测试只读。独立代理完成源码追踪和探针初稿后两次连接中断，由主 Agent 接手验证及记录，没有把代理中断当作产品问题。

## AUTH-A01 / P2：并发旧请求会销毁改密后新建的认证状态

- 位置：`platform-backend/src/main/java/cn/luwneshacha/platform/auth/SessionGuardFilter.java:22-35`，以及 `AuthController.java:44-48,56-63`。
- 触发：同一会话已有请求持有旧 Authentication，尚未完成数据库指纹检查；另一请求成功改密并通过 establish 更新当前会话。旧请求随后读取新密码指纹，认为自身认证已失效，直接 invalidate 同一 HttpSession。
- 根因：改密仅更换 session ID 和其中的 SecurityContext，没有隔离旧请求仍引用的 HttpSession；过滤器只判断请求内的旧指纹，没有检查共享 session 已经进入更新的认证代次。
- 影响：改密接口已返回 200、数据库新密码已生效，当前有效会话仍会被并发旧请求销毁，页面下一次操作要求重新登录。此处是错误注销，不是权限绕过。
- 复现：真实当前 AuthController/AuthService/SecurityFilter 与一次性 PostgreSQL，MockMvc 发起真实认证链；独立线程在 SessionGuardFilter 的数据库读取边界暂停，以确定并发顺序。输出：`AUDIT_PASSWORD_RACE passwordResponse=200 oldRequestResponse=401 newSessionInvalidated=true passwordChanged=true`。
- 修复方向：由会话 owner 统一管理认证代次，失效请求只能撤销它原来所属的状态，不能注销已被后续登录/改密替换的有效状态；保留其他旧会话即时失效要求。
- 回归：改密与正在执行的 /me 或列表请求并发；改密与其他旧会话请求并发；不能为了避免误注销而放行真正过期请求。

## 观察到、但未计入确认缺陷的行为

### 教师停用后，不能保留原关系只编辑学生姓名

`UserService.java:55-61,100-105` 在每次资料更新时重新要求 teacherId 指向启用教师。实际创建教师/学生并停用教师，再用原 teacherId 仅修改学生姓名，得到 `INVALID_TEACHER`；原关系仍保留。

需求允许停用有学生的教师且保留数据，也允许修改学生姓名，但没有明说资料修改时必须先重新分配教师。建议明确规则：未更换 teacherId 的资料更新是否可以保留停用关系；在确认前不把该限制归类为权限缺陷。

### Excel 中 NUL 字符导致整批中断的假设未复现

曾怀疑姓名内控制字符会使 PostgreSQL 插入失败，造成前几行已提交、后几行未处理。实际 POI 生成文件经过生产解析器后，预检三行有效，执行三行均成功，`AUDIT_NUL_IMPORT_HYPOTHESIS reproduced=false`。不能把该假设写进缺陷清单。

## 验证与边界

命令：`python artifacts/platform-audit/run-auth-probe.py`，最终退出码 0。证据：`AuditAuthProbe.java`、`run-auth-probe.py`、`auth-probe.log`。独立编译目录 `classes-auth`，无生产代码修改、无 Maven target 写入、无真实 COS。数据库由 Testcontainers 创建并结束时销毁。

第一次执行由于 classpath 包含既有测试配置导致 Spring Bean 重复，修正为排除 test-classes 后再运行；这是探针配置问题，未作为产品缺陷。第二次执行并发会话问题成立，但 NUL 假设未成立导致探针提前退出；改为保留 disproved 结果并继续第三项，最终完整执行，未放宽任何产品断言。

源码检查涵盖认证、账号、导入、统一异常入口；不能据此承诺所有并发时序或恶意文件输入均已穷尽。
