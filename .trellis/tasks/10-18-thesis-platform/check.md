# 平台实施验收记录

> 2026-10-19 后续审计更新：已有测试仍通过，但新增场景确认 9 项缺陷（5 P1、4 P2），尚未修复。详见 `audit-2026-10-19.md` 和 `docs/implementation/2026-10-19-代码审计报告.md`。下文为前一轮验收的历史证据，“已修复”仅覆盖当时用例，不表示跨页签、所有 SDK 日志/正文或所有非空产物损坏均已解决。

更新：2026-10-19，Asia/Shanghai。主Agent整合三名Agent的实现与独立复审。任务仍为in_progress：缺少真实COS配置，完整外部文件链路尚未验收，不执行生产部署、Git提交或推送。

## 结论

已实现需求文档v1.0、数据库设计v1.1对应的学生/教师/管理员平台；原审核引擎继续保留。最近提交时间和最新问题数均取最近一次SUCCESS，成功次数也只计SUCCESS。无成功结果显示“—”。

本地代码、自动验证、四服务构建/启动、真实浏览器账号与导入流程已通过。不能把以下证据写成真实COS上线验收通过。

## 自动验证

| 验证 | 命令/证据 | 结果 |
| --- | --- | --- |
| 原引擎全量 | `rtk mvn verify`，`artifacts/platform-engine-full.log`，backend/target/surefire-reports | 59套、1230项，失败/错误/跳过均0 |
| 平台全量 | `rtk mvn clean verify`后明确捕获退出码的`rtk mvn -q verify`，`artifacts/platform-final-verified.log`、`platform-final-exit.txt` | 7套、52项，失败/错误/跳过均0；退出0 |
| 前端 | `rtk npm test`、`rtk npm run build`、`rtk npm run format:check` | 7文件26项通过；TypeScript/Vite/格式通过 |
| 差异检查 | `rtk git diff --check` | 通过；仓库历史未提交文件未回退或批量提交 |

平台52项分布：账号HTTP/PG12、密码3、Excel2、提交PG18、引擎HTTP13、COS适配边界2、真实Tomcat异步2。PG用真实PostgreSQL容器；COS隔离测试不能当作云存储验收。

一次PowerShell重定向把RTK写到stderr的“未安装hook”提示表现为外层退出1，且RTK压缩了完整输出；没有把它当作业务测试失败或直接声称通过。最终以显式保存原生命令LASTEXITCODE=0与当前JUnit XML逐套汇总为准。全量clean后没有计入已移动测试类的旧报告。

## Docker与真实引擎

- 三个最终镜像均构建成功：engine `a48b038…`、platform `e612f837…`、frontend `9bba637…`；构建日志位于artifacts中的platform-*-docker*.log。
- 隔离项目名 `thesis-platform-smoke`，入口 `http://127.0.0.1:18090`；PostgreSQL、引擎、平台、前端均启动，平台/数据库/引擎健康检查通过。仅前端公开端口。既有8080服务未覆盖。
- 使用临时随机数据库/管理员密码；测试环境COS填写明确无效值，worker关闭，不向COS发请求。这不是生产fallback，真实环境仍必须配置私有COS。
- 当前容器通过Spring标准环境变量连接真实PostgreSQL，Flyway建表、账号关系持久保存；镜像更新后原测试账号仍可登录。
- 真实引擎接收实际DOCX并生成可读取的标注Word：9个FAIL、9个WRITTEN，合并为2条批注。XML与ZIP检查通过。
- 另由当前`HttpAuditEngine`实际调用正在运行的引擎（临时本机HTTP桥接到容器网络，无存储替身），返回9个问题并通过独立Word产物校验。把该标注文档再次作为输入也成功，避免已有批注文档被新校验误拒绝。脚本和日志：`artifacts/platform-smoke/engine-adapter-smoke.py`、`engine-adapter.log`。此项没有覆盖COS。

## 真实浏览器

最终前端JS `index-Cjer8xxn.js`，CSS `index-61i61M85.css`。独立Chromium/Playwright连接实际Docker前端和实际平台/PG，无API拦截或生产假数据。

`artifacts/platform-smoke/browser-smoke.mjs`退出0，`browser-evidence.json`记录17项PASS，运行时错误为空：

- 管理员登录，创建教师和学生，保存真实导师关系。
- dialog Esc关闭、焦点恢复；账号管理1440宽与640 CSS宽无横向溢出。
- 管理员论文页面和API均403。
- 下载真实XLSX模板，构造实际三行XLSX，预检与执行均为成功1/重复跳过1/错误1；下载CSV含正确错误行。
- 学生首次登录强制改密，未改密时业务API403；改密后进入真实空列表。
- 非法PDF前端阻止；直接请求后端也400，提交总数仍0。
- 资料显示正确导师，教师只见名下学生；零成功记录显示两个“—”。
- 管理员重置密码立即撤销另一个浏览器会话，重新登录强制改密。
- 停用后旧会话失效、登录明确拒绝；启用后恢复。

`browser-layout.mjs`退出0：1280、1920及640×480 CSS视口检查无水平溢出，dialog在视口内且内部可滚动，背景锁滚，键盘可到关闭按钮，背景不能抢焦点，Esc恢复触发按钮。640 CSS宽用于1280宽下200%缩放的等效布局验证，不冒称操作过浏览器原生缩放菜单。原生dialog允许焦点进入浏览器工具栏，不把浏览器标准行为误改成自定义焦点循环。

已人工查看admin-users、teacher-students、admin-import及dialog-640截图；未见控件遮挡或不可达。正文/弱化文字/控件边界对比度数值由前端证据记录，不宣称完整WCAG认证。

## 复审与修复

独立复审：`review.md`、`review-frontend.md`、`review-requirements.md`；分别对权限/异步/存储、前端缓存/表单、原文逐条覆盖负责。已修复并回归：账号并发覆盖、上传孤儿对象、过期attempt删成功文件、响应正文无界超时、假批注产物、排队下载旧授权、下载饱和非JSON错误、async requestId丢失、多页签身份切换旧缓存、离页上传迟到跳转、首次改密导航竞争。

需求中的“点击一行进入详情”采用明确的“查看”链接承载，保留原生链接和键盘行为；详情路径完整。没有擅自把tr变成自定义交互控件。状态使用等义中文文案。

## 工具与剩余边界

- fast-context缺少Windsurf API Key，本轮不反复重试，用rg与调用链阅读补足。
- 内置浏览器插件引用失效的旧版browser-service.mjs，无法启动；已按技能的可用性门控记录并改用独立Playwright。RTK不能解析playwright install子命令时改用已安装CLI入口。
- 真实COS待用户配置：桶完整名称、地域、具有所需权限的凭据环境变量。密钥不写聊天、代码或日志。
- 仍需真实COS验收私有读写、合法与损坏DOCX整条流程、学生/当前导师授权Word下载、失败对象清理及成功文件持久保留。100GB容量估算不是已完成压力测试。
- 未做生产部署、GitHub推送和Git提交。按工作流保留任务进行中，未finish/archive。仓库既有大量未知历史改动需要与本任务明确分离。

稳定经验已回写`.trellis/spec/backend/platform-reliability.md`及frontend/index.md；本次环境、截图和工具故障仅记录在本任务，不成为永久生产规则。
