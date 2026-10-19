# 审核引擎适配器审计

日期：2026-10-19。只读审计生产源码，以独立故障注入探针验证，未部署、未修改论文原件或业务数据。

## ENG-A01 / P1：转换服务故障被误判为不可重试的文档损坏

- 位置：`backend/src/main/java/cn/luwneshacha/thesis/api/InspectionService.java:83-85,100-101`、`ApiExceptionHandler.java:22-28`；`platform-backend/src/main/java/cn/luwneshacha/platform/engine/HttpAuditEngine.java:120-124`；`submission/SubmissionWorker.java:60-67,93-99`。
- 根因：转换失败使用 `FORMULA_CONVERSION_FAILED`，报告写入失败使用 `REPORT_GENERATION_FAILED`，异常映射没有为它们区分服务故障，统一落为 HTTP 400。平台又把 400/413/422 一律归为论文损坏并设置 `retryable=false`。
- 影响：转换程序不可用等服务故障会让合法论文第一次审核即 FAILED，并触发失败文件清理；用户收到重新保存 DOCX 的误导建议，而不是按服务故障重试。
- 动态证据：用真实 `InspectionService` 在独立 JVM 内将转换程序设为不存在路径，再通过真实异常处理器与 `HttpAuditEngine` 跑本地 HTTP 调用；结果 `code=FORMULA_CONVERSION_FAILED http=400 retryable=false`。报告写入失败是同一路径的静态代码证据，未单独注入。
- 修复方向：引擎与适配器共享明确的错误分类；服务、存储、转换运行故障与文件非法分开，决定 HTTP 状态、用户文案、重试与清理策略。不能只根据宽泛 4xx 推断论文损坏。
- 应补测试：转换程序不可用、转换超时、报告输出 I/O 故障、确实损坏 DOCX；从引擎响应穿过平台适配器验证重试性及最终状态。

## ENG-A02 / P1：批注文件正文完全替换仍通过产物校验

- 位置：`platform-backend/src/main/java/cn/luwneshacha/platform/engine/MarkedDocumentValidator.java:17-33`，核心为第 22 行。
- 根因：内容保留检查只比较输入有无内容、输出有无内容两个布尔值；任何非空正文都可以通过，没有验证原文文本、表格、图片和公式的保留。新增合法批注引用也不能证明原文保留。
- 动态证据：输入两段原文；故障注入的输出将原文全部替换成无关文本，保留完整 DOCX 包结构、一个合法非空批注及关系，JSON 声称一条 FAIL 已 WRITTEN。真实 `HttpAuditEngine` 接受并返回成功：`accepted=true problems=1 original-paragraphs=2 marked-original-paragraphs=0`。
- 影响：引擎一旦回传错文档或发生非空内容损坏，业务层会把有内容但已失去原文的产物当作成功提供下载。这是故障注入证实的保护缺口，并非声称实际用户论文已经丢失。
- 修复方向：明确批注允许的变更，验证规范化原文/对象序列保留；对经确认的公式转换建立独立变换契约。不能用 ZIP 字节相等代替内容保留，也不能以任意非空输出作为成功依据。
- 应补测试：全文替换、局部段落丢失、图片/表格/公式丢失、正确的批注增量与合规公式转换。

## 复现与基线

`python artifacts/platform-audit/run-engine-probe.py` 调用当前编译产物，在 `artifacts/platform-audit/classes-engine` 独立编译探针。证据：`EngineAuditProbe.java`、`run-engine-probe.py`、`engine-probe.log`。探针退出码 0 表示成功复现缺陷，不能解读为产品验收通过。未使用真实 COS。

旧公式容器转换另做实际样本检查，结论见 `audit-formula-runtime.md`；不能仅凭包缺失推断根因。
