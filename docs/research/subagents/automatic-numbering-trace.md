# 自动编号禁用规则的线上生效链路审计

记录日期：2026-10-20，时区 Asia/Shanghai。只读检查线上运行状态、JAR、路由和本地源码；未修改服务器、账号、业务数据或生产代码。未读取服务器 `.env`、账号密码或 COS 密钥；检查结果不包含环境变量原值。

## 结论

用户反馈“修改没有效果”有明确的发布原因：**线上审核引擎没有包含全文禁止自动编号的代码**。账号平台、前端更新到了 2026-10-20 的版本，但审核引擎仍是 2026-10-19 20:45 创建的旧发布。

这不只是依据版本名推测：本次从运行中容器只读取得 `/app/app.jar`，在远端内存中检查 ZIP 目录和类常量，确认新增解析器、证据模型、规则模块均不存在，规则入口未引用该模块，中文文案没有 `NUMBERING-01`。本地规则已接入正式检测、批注和报告路径，本轮定向测试 76 项通过。

## 1. 生产事实

2026-10-20 的只读 SSH 查询结果：

| 项目 | 实测值 |
| --- | --- |
| 审核引擎容器 | `thesis-inspection-backend-1` |
| 引擎镜像 | `thesis-backend:release-20261019-204549` |
| 引擎镜像 ID | `sha256:f77b715fb19fb5407619b8ad2ff67be3bd75625e60d90fb095455c59db5d4fd3` |
| 运行容器 ID | `6b4d424ce33fbbf768f3432221753492d309a6ae6e7fb66dfdaf30dff466be12` |
| 镜像源码 revision | `6d06d8f7499a8e9d4b1d79ef30da8bea6f96b0f6` |
| 容器启动时间 | `2026-10-19T12:47:48.141585675Z`，即北京时间 20:47:48 |
| 引擎健康状态 | `healthy` |
| 实际 JAR SHA-256 | `7783129f8ee9f288b5df85459b29575c945394ef74debc06581cf8043e614a23` |
| 实际 JAR 大小 | `43,842,053` 字节 |
| 平台镜像 | `thesis-platform:release-20261020-080508` |
| 前端镜像 | `thesis-frontend:release-20261020-080508` |

运行 JAR 的三个条目均不存在：

```text
BOOT-INF/classes/cn/luwneshacha/thesis/document/AutomaticNumberingParser.class
BOOT-INF/classes/cn/luwneshacha/thesis/document/AutomaticNumberingModel.class
BOOT-INF/classes/cn/luwneshacha/thesis/rules/AutomaticNumberingRuleModule.class
```

同时 `RuleEngine.class` 不含 `AutomaticNumberingRuleModule` 引用，`RuleDescriptions.class` 不含 `NUMBERING-01`。两份发布指针与运行状态一致：

- `/opt/thesis-inspection/current-release.json`：`release-20261019-204549`、`verified`，JAR 摘要与实测一致。
- `/opt/thesis-platform/current-release.json`：平台发布为 `release-20261020-080508`、`verified`；其中 `images.backend` 仍列出旧引擎。

提交顺序进一步吻合：

- `6d06d8f`：2026-10-19 20:24:24，修正同段标题批注并取消结论固定章号。
- `e7f7131`：2026-10-19 22:04:43，新增全文禁止自动编号。
- `git merge-base --is-ancestor e7f7131 6d06d8f` 返回 1，新规则不是生产提交的祖先。

## 2. 运行入口与数据流

当前入口是账号平台路径，浏览器不能直接访问旧匿名审核接口：

```text
HTTPS 用户入口
  -> thesis-platform-frontend-1 的 /api/ 反向代理
  -> thesis-platform-platform-1
  -> HttpAuditEngine.inspect，POST /api/inspections，annotate=true
  -> thesis-inspection-backend-1
  -> 检测结果和批注下载
  -> 平台持久化结果与文件
```

本次确认：

- 运行前端配置把 `/api/` 转发至账号平台。
- 平台的引擎目标匹配当前后台服务，平台与引擎共享 `thesis-inspection_default` 网络。仅输出匹配布尔值，没有输出环境变量内容。
- 从平台容器请求引擎健康接口，返回 `UP`。
- 公开 `/api/health` 返回 200。
- 未登录的 `/api/submissions` 与 `/api/inspections/health` 均返回 401。
- 未执行登录、上传、重新审核、账号重置、对象清理或数据库写入。健康检查不是新编号规则的业务验收。

本地源码路径：

- `frontend/nginx.conf`：API 反向代理。
- `platform-backend/src/main/java/cn/luwneshacha/platform/engine/HttpAuditEngine.java`：向内部引擎提交 DOCX，统计 FAIL，读取批注文件。
- `backend/src/main/java/cn/luwneshacha/thesis/api/InspectionService.java`：解析检测后依次生成批注与报告。
- `backend/src/main/java/cn/luwneshacha/thesis/document/Docx4jDocumentParser.java`：调用 `AutomaticNumberingParser`。
- `ParsedDocument.automaticNumbering` -> `AutomaticNumberingRuleModule` -> `NUMBERING-01`。
- `AnnotationFindings` 选择 FAIL；`CommentWriter` 写入对应中文说明；`WordReportWriter` 输出错误与 REVIEW。

因此无需为“没生效”继续放宽/重写编号规则，先发布包含新规则的引擎，并对新提交做验证。已有成功提交及此前下载的 Word 不会因镜像更新而重新生成。检查与批注也不会自动把论文中的编号改成普通文字。

## 3. 可安全重复的只读核验

下面仅查询选定字段，不运行 `docker inspect` 的全量输出，不读取 `.env`。SSH 使用此前授权的用户、主机和密钥路径；不要把密钥内容写进命令或记录。

```powershell
ssh -i 'C:/Users/Lenovo/Downloads/dong.pem' -o BatchMode=yes -o ConnectTimeout=15 ubuntu@124.223.86.157 "sudo -n docker inspect --format '{{.Config.Image}}|{{.Image}}|{{.State.Health.Status}}' thesis-inspection-backend-1"
ssh -i 'C:/Users/Lenovo/Downloads/dong.pem' -o BatchMode=yes -o ConnectTimeout=15 ubuntu@124.223.86.157 'sudo -n docker exec thesis-inspection-backend-1 sha256sum /app/app.jar'
ssh -i 'C:/Users/Lenovo/Downloads/dong.pem' -o BatchMode=yes -o ConnectTimeout=15 ubuntu@124.223.86.157 'sudo -n docker exec thesis-platform-platform-1 curl -fsS http://backend:18098/api/inspections/health'
```

确认类存在时可在远端内存执行下列 Python。`docker cp ... -` 只读导出 TAR 到标准输出，未在远端落盘：

```python
import hashlib, io, json, subprocess, tarfile, zipfile
archive = subprocess.check_output([
    'sudo', '-n', 'docker', 'cp',
    'thesis-inspection-backend-1:/app/app.jar', '-'])
with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
    jars = [entry for entry in tar.getmembers()
            if entry.isfile() and entry.name.endswith('app.jar')]
    assert len(jars) == 1
    data = tar.extractfile(jars[0]).read()
with zipfile.ZipFile(io.BytesIO(data)) as jar:
    keys = [
        'document/AutomaticNumberingParser.class',
        'document/AutomaticNumberingModel.class',
        'rules/AutomaticNumberingRuleModule.class']
    prefix = 'BOOT-INF/classes/cn/luwneshacha/thesis/'
    print(json.dumps({
        'jarSha256': hashlib.sha256(data).hexdigest(),
        'classes': {key: prefix + key in jar.namelist() for key in keys},
        'engineUsesModule': b'AutomaticNumberingRuleModule'
            in jar.read(prefix + 'rules/RuleEngine.class'),
        'descriptionUsesRule': b'NUMBERING-01'
            in jar.read(prefix + 'report/RuleDescriptions.class')}))
```

PowerShell 传多行远端脚本可能引入 CR。实际核验用 Base64 编码 LF 文本后传输执行；不把多行脚本与远端路径拼接在同一未经处理的字符串中。发布指针普通用户读取曾返回 `PermissionError`，随后使用 `sudo -n cat` 读取同一明确 JSON 文件、只输出白名单字段成功；没有放宽文件权限。

## 4. 本地回归与真实样本

### 本轮实际运行

在干净的 `.worktrees/table-border-occupancy-20261019/backend`，HEAD 为 `e7f7131d4f832bb7ba60cf4ca79cbfc7c2be65e0`：

```powershell
rtk proxy mvn -B -ntp '-Dtest=AutomaticNumberingParserTest,AutomaticNumberingPolicyTest,AutoNumberingCommentReadbackTest,RuleDescriptionsTest' test
```

2026-10-20 16:11:11 完成：**76 项，0 失败、0 错误、0 跳过**。日志：`.trellis/tasks/10-20-report-style-numbering-verification/evidence/numbering-targeted-20261020.log`。

| 测试类 | 数量 | 覆盖 |
| --- | ---: | --- |
| `AutomaticNumberingParserTest` | 9 | 直接/继承编号，多级列表，表格、文本框、实际使用附属部件，域、删除内容排除、禁用编号、未知定义 |
| `AutomaticNumberingPolicyTest` | 5 | 标题、列表和文献即使分隔符正确也须报自动编号；手工编号及项目符号不误报 |
| `AutoNumberingCommentReadbackTest` | 6 | 正式批注选择、段首锚点、实际写入、中文说明、保存回读与原文字保留 |
| `RuleDescriptionsTest` | 56 | 编号及其他中文错误/建议的语义 |

先前 2026-10-19 的 `clean verify` 记录是 1347 项全通过，证据为 `artifacts/no-auto-numbering/verification-summary.json` 和 `verify-final.log`。这是历史全量记录，不能表述为本轮报告样式变更后的全量测试。

### 最小真实 DOCX fixture 入口

直接复用 `AutomaticNumberingPolicyTest.prohibitsAutomaticHeadingListAndReferencesRegardlessOfSeparator`：使用 docx4j 创建、保存并重新读取 DOCX，构造数字多级标题、正文编号列表、自动编号参考文献，各自具有有效 `numPr` 和编号定义；三个分隔符 `space/tab/nothing` 参数都断言 3 条 `NUMBERING-01/FAIL/AUTOMATIC_NUMBERING_FORBIDDEN`。

`AutoNumberingCommentReadbackTest.formalAnnotationFlowAnchorsListNumberingAtTheCurrentParagraphStart` 的两个自动编号段落是更小的批注验收入口：2 条编号错误 -> 2 条 WRITTEN -> 2 个可读 Word 评论；原段落文字保持不变。其参数化域 fixture 另覆盖 SEQ、LISTNUM、AUTONUM、AUTONUMLGL、AUTONUMOUT。

### 实稿路径及已有基线

两份原稿本次重新检查存在，且 SHA-256 与历史实稿验证一致：

```text
F7（29 处自动编号）：
C:\Users\Lenovo\xwechat_files\wxid_6tcczogt6slf22_4d6d\msg\attach\b04b5a138fa65965ff56b553b53060b3\2026-10\Rec\acd9151ec1ed76f4\F\7\大数据与基础科学学院-计算机科学与技术-2206.docx
SHA256 ac2faa7dea2cdc9e94376356dd3b567de36ddb142c9aef128be2439eba515734

F12（手工编号反例）：
C:\Users\Lenovo\xwechat_files\wxid_6tcczogt6slf22_4d6d\msg\attach\b04b5a138fa65965ff56b553b53060b3\2026-10\Rec\acd9151ec1ed76f4\F\12\2406302112王鑫山-打印稿.docx
SHA256 ce1b855cf93449db15cfc77ec03e393c93cc16f3ec7470c89cb25adad7ae4817
```

不要根据聊天中的转义写法额外加入反斜杠；权威输入列表是 `artifacts/no-auto-numbering/real-input-inventory.json`。

- F7 已有新规则验证：29 条自动编号错误全部写入批注；移除 11 条旧“自动编号后空格”错误，其余 3939 条结果逐条相同。总 FAIL 由 55 变为 73，不等同于 Word 评论条数。
- F12 已有验证：4853 条结果前后相同、无自动编号误报。
- 现成对照：`artifacts/no-auto-numbering/real-validation.json`、`real-validation.md`、`new/F7-results.json`、`new/F7-annotated.docx`。
- 现成探针：`NumberingRealProbe.java` 和 `run-real-probe.ps1 -Stage baseline|new`。`new` 目前绑定上述 worktree 的 `target/classes`；若换发布 worktree，必须明确更新新任务探针输入，不能误把旧构建当候选结果。脚本会写验证产物，不属于线上只读核验。

## 5. 发布源码基线核对

建议从完整提交 **`e7f7131d4f832bb7ba60cf4ca79cbfc7c2be65e0`** 建本任务独立 worktree，只同步本轮报告改动及其测试。该提交已包含：

- `69487db`：目录二、三级缩进兼容。
- `4b3237b`：附录可选，只在实际出现时检查。
- `18bb5ea`：图题空格错误不破坏编号连续性。
- `1a36974` / `0ce76a0`：表格边线、图号引用相关修复。
- `6d06d8f`：同段标题批注修复、取消结论固定章号。
- `e7f7131`：全文禁止自动编号。

2026-10-20 16:12 的快照已比较 root 与该干净 worktree 的全部 `backend/src/main/java/**/*.java`、`backend/src/test/java/**/*.java`、`backend/pom.xml`：

- 基线 167 文件；root 168 文件。
- 167 个共有文件：57 个字节完全相同；110 个仅 LF/CRLF 或 BOM 不同，统一这两种表示后内容逐字一致。没有忽略其他空白或代码内容。
- root 唯一额外文件是本轮 `backend/src/test/java/cn/luwneshacha/thesis/report/WordReportLayoutTest.java`。快照时报告实现尚未修改；后续报告改动应以本轮明确的文件清单同步。
- 本次未复制覆盖任何源码。

完整两套原始 SHA-256、LF 文本 SHA-256、差异与时间：`.trellis/tasks/10-20-report-style-numbering-verification/evidence/numbering-source-baseline-comparison.json`。这是指定范围的内容相同性核验，不把 root 大量未提交历史改动自动归入本轮发布。

## 6. 已有部署入口、依赖和回滚

生产审核引擎使用独立 Compose 项目：

```text
项目名 thesis-inspection
目录 /opt/thesis-inspection
文件 compose.yaml + compose.release.yaml
服务 backend
数据卷 thesis-inspection_thesis-data
账号平台项目 thesis-platform（本次不应重启）
```

| 入口 | 用途与注意 |
| --- | --- |
| `artifacts/deploy-heading-fix/prepare_release.py` | 历史准备脚本，硬编码旧提交 `6d06d8f`、1324 测试、旧 verify 日志、输出文件必须不存在；**不能直接作为新发布脚本运行** |
| `artifacts/deploy-heading-fix/deploy_engine.py <release> <commit> <jarSha256>` | 服务器上部署指定、已验证 JAR；先核对 SHA，再基于当前运行镜像构建只替换 JAR 的新镜像 |
| `artifacts/deploy-heading-fix/finalize_engine.py <release>` | 读取该发布目录 `smoke.json`，成功才更新引擎和平台指针；失败恢复上个 override 并只重启 backend |
| `artifacts/deploy-heading-fix/business_smoke.py` | 旧标题问题的业务验收脚本，有登录与上传操作，且断言针对旧问题；未在本次执行，不可拿旧结果替代新编号验证 |

`deploy_engine.py` 的条件与行为：

1. 需要 `/tmp/<release>/app.jar`；新 `/opt/thesis-inspection/releases/<release>` 必须不存在。服务器运行该脚本需具备现有 Docker/发布目录权限。
2. 先确认旧引擎健康、非 root、数据卷符合预期、未暴露外部端口，并核对 Compose 镜像与运行镜像相同。
3. 保存前一版 override 和指针，给旧镜像加回滚标签；备份的展开 Compose 可能含敏感配置，只保存在受保护目录，不打印。
4. 运行环境沿用已验证镜像，因此保留 JRE 21、LibreOffice Writer、curl、运行用户、持久卷、网络和进程参数。只替换 JAR，不触碰平台/数据库镜像。
5. 切换前通过只读 SQL 确认 PENDING/PROCESSING 任务为 0；有审核进行时不切换。
6. 只运行 `docker compose ... up -d --no-build --no-deps --wait --wait-timeout 240 backend`；禁止无服务限定的根目录启动、`down -v` 或重建账号数据库。
7. 任意切换/健康验证失败，恢复 `previous-compose.release.yaml` 并使用同一限定命令启动上版 backend。
8. 部署后最初状态为 `healthy_pending_business_smoke`；新业务验收应以自动编号实稿/fixture 验证新错误和真实批注，再生成可追溯 smoke 记录，最后更新指针。

当下生产发布的回滚材料属于旧的 20261019 发布（会回退到 18:01 的版本），不能把它误当成未来新发布的“上一版”。未来发布应重新保存当前 `release-20261019-204549` 为直接前驱。

## 7. 本次范围与后续验收

本次结论已由服务器二进制、运行入口、源码基线及定向测试共同支持。未执行新引擎发布、线上上传或重新生成用户旧论文，不声称线上规则已经修好。

发布验收至少核对：新运行 JAR SHA 与候选一致；三个编号类存在且 RuleEngine 引用模块；自动编号 fixture 出现 `NUMBERING-01` 并实际写入 Word；手工编号反例不新增编号错误；页码/自动目录/交叉引用不被当作内容编号禁止；新旧结果必须来自不同审核记录，不能下载历史成功文件判断新规则。
