# 旧 OLE 公式运行时隔离审计

日期：2026-10-19。模式：有界只读审计。未改生产源码、Dockerfile、已有运行容器、真实 COS 或现有测试。仅启动一个全新一次性容器并在其文件系统安装包，结束后由 --rm 删除；宿主补做同样本转换，使用独立 LibreOffice profile。

## 结论

**不能把当前失败完整归因于缺少 libreoffice-math，也不能宣称补装 Math 即可修复。**

- 已观察事实：当前引擎镜像只安装 Writer、缺少 Math；同样本在 Writer-only 环境转换后没有原生公式。
- 控制变量 A/B：仅新增同版本 libreoffice-math 和其 UI 配置包后，同样本出现一个原生公式，但内容仍为空 m:oMath 节点。原校验会因 formulaHasNoText 继续拒绝。
- 本机较新完整 LibreOffice 对同样本也输出空 m:oMath，因此该剩余失败不是容器缺 Math 独有。
- **源 OLE 公式有效性尚未独立验证。** 样本来自规范原件、包含一个 Equation.3 对象，只证明来源和类型，不能据此称公式内容正常。本轮没有执行 EquationNativeParser 或验证 MTEF 文本/结构，不把空结果进一步断言为特定解析器缺陷。

因此可报告“镜像缺少 Math 运行时，且该样本在补 Math 后仍失败，仍需验证源公式有效性/转换支持范围”；不应报告“已证明所有正常 Equation OLE 因缺 Math 失败”或“加 Math 就能解决此案例”。

## A/B 设计与结果

固定镜像：a48b038f9374（当前 thesis-platform-engine:local）。固定输入：artifacts/platform-audit/legacy-formula.docx，SHA-256：289f12052aa6b3d9ea4e379f00b889a115523456e0f7bbe99aa2cb6b2b0ce74a。

两组均通过 runuser 使用 thesis 用户、独立全新 profile，执行与 FormulaConversionService 相同的 DOCX → ODT → DOCX 两阶段命令。输入 bind mount 只读，输出只写本任务 artifacts。未执行 Spring 服务或访问任何业务存储。

| 环境 | LibreOffice 版本 | 输出 OLE | 输出原生 oMath | 公式内容 | 结果 |
|---|---|---:|---:|---|---|
| 源样本 | 不适用 | 1 | 0 | 尚未独立验证 | Equation.3 / rId13 |
| A：当前镜像，仅 Writer | 26.2.6.3 | 0 | 0 | 无 | 丢失公式对象 |
| B：A + 同版本 Math | 26.2.6.3 | 0 | 1 | 空 m:oMath | 仍无法通过内容校验 |
| 宿主对照 | 26.8.1.1 | 未计数 | 1 | 空 m:oMath | 同样未保留有效公式文本 |

包差异经 dpkg-query 清单比较：仅新增下列两包，已有包没有升级：

- libreoffice-math 4:26.2.6.3-0ubuntu0.26.04.2
- libreoffice-uiconfig-math 4:26.2.6.3-0ubuntu0.26.04.2

B 组还验证了 /usr/lib/libreoffice/program/libsmlo.so 已存在。进一步只读检查 B 的 ODT Object 1/content.xml，内容为无子节点的 MathML math 元素，表明空内容在第一阶段已经出现，非仅最后 DOCX 导出阶段丢失。

## 代码定位

- backend/Dockerfile:8-10：运行阶段安装 curl 与 libreoffice-writer，没有 libreoffice-math。
- backend/src/main/java/cn/luwneshacha/thesis/conversion/FormulaConversionService.java:32-39：独立 profile 和 DOCX → ODT → DOCX 流程。
- 同文件 :61-64：公式计数不足或 formulaHasNoText 都抛出 lost equation objects；所以 B 组虽 after=1，仍应拒绝。
- 同文件 formulaHasNoText：以公式文本是否空白判断，本次输出没有任何公式子节点。

主 Agent 已取得原运行容器 API 返回 before=1, after=0 的日志。本代理没有重新请求该运行容器；A/B 直接运行底层转换命令，结果与其失败机制一致。未通过新增 Math 后的完整服务 API 做端到端成功验证，因为 B 本身仍是空公式。

## 复现与证据

项目根目录执行：

    python artifacts/platform-audit/formula-runtime-ab.py
    python artifacts/platform-audit/formula-host-probe.py

两条最终执行均退出码 0，表示诊断脚本完成，不表示公式转换业务验收通过。

- artifacts/platform-audit/formula-ab/evidence.json：源/输出哈希、公式计数和包差异。
- formula-ab/packages-before.txt、packages-after.txt：包清单。
- formula-ab/apt-update.log、apt-install.log：安装证据。
- formula-ab/before/、after/：两个阶段的 ODT/DOCX 与转换日志。
- formula-ab/host/evidence.json：本机版本与空公式 XML。
- formula-runtime-ab.py：完整隔离容器命令，镜像 ID 固定，输入只读，转换/apt 均有超时。
- formula-host-probe.py：本机独立 profile 复现脚本。

首次脚本因 Windows text-mode stdin 被转成 CRLF 而失败，未完成转换；随后改成 UTF-8 bytes 输入并从同镜像重建一次性容器，最终 A/B 成功执行。没有把首次失败作为产品缺陷。报告只使用最终有效证据。

## 后续合理方向

如果项目仍以 LibreOffice 承担 Equation OLE 转换，应在受控镜像中显式包含并验证 Math 能力；验收必须使用**已知有效、有可核对文本/结构的 OLE 样本**，断言非空 OMML 和关键内容，而不能仅判断进程退出码或 oMath 节点数量。本样本仍需先验证 EquationNative/MTEF 内容，之后才区分输入问题、LibreOffice 支持范围或转换实现问题。本轮到此收敛，不扩大修复范围。
