# 毕业论文格式筛查

面向学校论文格式要求的 DOCX 检测项目。上传论文后，系统解析 Word 底层 XML，执行格式规则，生成带中文批注的 Word 副本和 Word 检查报告。当前聚焦检测核心功能，不包含登录注册、教师端或学生端权限划分。

## 技术栈与功能

- 后端：Java 21、Spring Boot、docx4j；JUnit 回归测试。
- 前端：React、TypeScript、Vite；同源 API 上传和结果下载。
- 部署：Docker Compose、Nginx；结果文件使用独立持久卷。
- 规则：封面、摘要、目录、正文、标题、图表、公式、参考文献、页眉页脚和附录等模块。
- 格式解析：字体、字号、加粗、样式继承、行距、首行缩进、左侧缩进、分节页边距及编号。
- 批注与报告：问题中文说明和修改建议；同一位置的问题可合并批注；报告记录检查结果、覆盖情况和批注写入情况。

规则区分可从 XML 确定的属性与证据不足的情况。真实分页、字体替换和部分旧公式内部格式仍存在检查边界；不能将测试通过理解为所有论文都可百分之百准确判断。

## 本地启动

需要 JDK 21 或更高版本、Maven、Node.js 22.12+（建议使用近期 Node.js 22 LTS）及 npm。

启动后端：

~~~bash
mvn -f backend/pom.xml spring-boot:run
~~~

另开一个终端启动前端：

~~~bash
cd frontend
npm ci
npm run dev
~~~

后端默认监听 18098，前端开发服务器将 /api 代理到后端；浏览器地址以 Vite 终端输出为准。构建时可通过 VITE_API_URL 指定独立 API 地址，默认使用同源路径。

旧公式自动转换依赖 LibreOffice。Docker 后端镜像已安装；本地运行时需安装 LibreOffice，并通过 JVM 参数 -Dthesis.soffice 指定其可执行文件。使用方式见 [部署与开发说明](docs/部署与开发.md)。

## 检查与构建

~~~bash
mvn -B -f backend/pom.xml verify
cd frontend
npm ci
npm run build
~~~

2026-10-18 本次上传前，后端 1219 项测试全部通过，前端 TypeScript 检查与生产构建通过。测试使用程序生成的脱敏 DOCX 场景，真实论文和批注结果不提交到仓库。

## Docker 部署

当前后端 Dockerfile 使用本地 Maven 构建产物，因此需要先生成 backend/target/app.jar：

~~~bash
mvn -B -f backend/pom.xml verify
docker compose up -d --build --wait
docker compose ps
~~~

默认前端映射到宿主机 8080 端口。后端仅在容器网络内开放；检测文件保存在 thesis-data 卷。具体配置、健康检查和结果保留时间见 [部署与开发说明](docs/部署与开发.md)。

## 项目结构

~~~text
backend/
  src/main/java/cn/luwneshacha/thesis/
    api/          上传、检测和下载接口
    conversion/   旧公式转换
    document/     DOCX/XML 解析和文档模型
    inspection/   检测编排与规则覆盖统计
    rules/        章节规则与通用格式规则
    reporting/    Word 批注与锚点处理
    report/       中文说明与 Word 检查报告
  src/test/       回归测试
frontend/         上传及结果页面
docs/             开发与部署说明
compose.yaml      Docker 服务编排
~~~

## 数据边界

仓库包含源码、测试、依赖锁文件和通用部署配置。学校规范原件、学生论文、批注结果、运行日志、机器专属部署资料和私钥均保留在本地，不作为项目代码上传。
