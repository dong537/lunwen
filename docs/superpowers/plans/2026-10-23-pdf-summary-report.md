# PDF 审核结果单实现计划

## 目标

在保留现有 Word 详细检查报告的前提下，为每次成功审核生成一份独立、严格一页、嵌入中文字体的 PDF 审核结果单，并通过现有平台的鉴权、对象存储和下载链路交付。

## 数据和边界

- 审核依据只读取同一次审核生成的 `report.json`；`FAIL` 计为明确问题，`REVIEW` 单独计为待确认，不把待确认算作错误。
- 学生姓名、学号、专业和指导教师从平台数据库读取；平台没有学院字段，PDF 不编造学院。
- Word 详细报告继续使用 `report.docx`，既有下载接口和文件名保持不变。
- PDF 使用独立对象 `attempt-N/report.pdf` 和独立 `pdf_available` 状态。历史成功记录迁移为不可下载 PDF，避免没有实际文件却显示可用。

## 模块和接口

- `platform.report.PdfSummaryReportWriter`：解析审核 JSON、统计结果、嵌入配置或系统候选中文字体、绘制固定一页 PDF。
- `SubmissionWorker`：在发布成功状态前生成并上传 PDF；任何 PDF 生成或上传失败都沿用现有 attempt 清理和失败/重试流程。
- `Submission`/`SubmissionTasks`/Flyway：维护 PDF 可用状态，成功提交同时发布 Word 和 PDF。
- `SubmissionService`/`SubmissionController`：新增 `report-pdf` 文件类型、`application/pdf` MIME、`.report.pdf` 文件名，沿用当前学生/指导教师授权和二次流式鉴权。
- 前端提交详情：新增 PDF 下载按钮和 `canDownloadPdfReport` 合同字段，Word 按钮不变。

## 失败处理

- 找不到中文字体、JSON 无法解析、PDF 超过一页、对象存储写入失败都不得标记 SUCCESS。
- 历史成功但 `pdf_available=false` 的记录继续可下载 Word 报告，但不能显示或下载 PDF。
- PDF 与 Word 的下载均不能接受客户端提供的对象路径；对象键由服务端按当前成功 attempt 构造。

## 验证

- PDF 单元测试覆盖 FAIL/REVIEW/零问题、长中文字段、字体缺失、恰好一页和嵌入字体。
- 平台测试覆盖 PDF 生成/上传失败清理、迁移默认值、两种 MIME/文件名、历史记录、角色权限和当前 attempt 隔离。
- 前端测试覆盖成功双入口、无 PDF 历史记录、PENDING/FAILED 隐藏、代理和预签名下载。
- 运行平台 Maven 测试、引擎 Maven 测试、前端 Playwright/build；检查 Docker 构建配置安装字体且不写入密钥。
