# Word 检查报告 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 将论文格式检测结果生成可下载的 Word 检查报告，并在前端提供下载入口。

**Architecture:** 检测服务在得到最终确定性结果后调用独立的 `ReportWriter` 生成 DOCX，文件与批注副本存放在同一请求目录。控制器通过独立下载接口返回 DOCX，前端只负责链接下载，中文规则说明由后端统一提供。

**Tech Stack:** Spring Boot 3.4、Java 21、docx4j 17.2.1、React + TypeScript。

## Global Constraints

- 报告必须是 `.docx`，不能用 HTML 代替。
- 错误类型、问题说明和修改建议使用中文。
- 报告基于最终确定性结果生成；失败不得静默返回空报告。
- 不增加登录注册功能。

### Task 1: 生成 Word 报告

**Files:**
- Create: `backend/src/main/java/cn/luwneshacha/thesis/report/WordReportWriter.java`
- Create: `backend/src/main/java/cn/luwneshacha/thesis/report/RuleDescriptions.java`

- [ ] 实现标题、摘要统计和错误明细表格。
- [ ] 复用 `CheckResult` 的 source 地址，并输出中文规则、原因、规范、实际值和建议。
- [ ] 报告写入临时文件后原子移动，异常向上抛出。

### Task 2: 接入存储和下载接口

**Files:**
- Create: `backend/src/main/java/cn/luwneshacha/thesis/api/StoredReport.java`
- Modify: `InspectionService.java`, `InspectionController.java`, `InspectionResponse.java`

- [ ] 每次检测生成 `report.docx` 并登记。
- [ ] 增加 `GET /api/inspections/{requestId}/report`。
- [ ] 保持清理任务同时清理报告。

### Task 3: 前端下载

**Files:**
- Modify: `frontend/src/main.tsx`

- [ ] 删除 HTML Blob 报告逻辑。
- [ ] 使用后端报告下载路径，按钮文案改为“下载 Word 检查报告”。

### Task 4: 验证

- [ ] `mvn -q -f backend/pom.xml verify`
- [ ] `npm run build`
- [ ] `git diff --check`
