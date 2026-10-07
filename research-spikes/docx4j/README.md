# docx4j 能力试验

最新进展：[第八轮公式外部规则](../../docs/research/第八轮公式外部规则与模板核对.md)。完整 docx4j 研究检查目前为 33 项；本轮通过报告保存于 `evidence/round8/`。独立 MTEF 试验仍有精度失败，不包含在此通过数内。SourceIndex、LegacyProjection、Binder 及格式检查代码均为 test 范围内的研究原型，不能直接视为生产解析器。

研究范围：验证实际发布组件，不是产品实现。固定 docx4j 17.2.1，对应已审查的源码分支；不追随最新版本自动升级。

断言来自明确 XML fixture 和已独立检查的学校模板。合成 DOCX 用于暴露属性继承边界，不冒充真实论文业务验收。学校文件仅只读加载，不进入公开仓库。

先运行不带表格预处理的能力检查，预期表格条件样式断言失败；再加入库的表格预处理验证，同一断言应通过。此变化仅是测试中的组件调用组合，不修改库、不降低期望值。

```powershell
mvn -s settings.xml -Dschool.docx="规范文件的绝对路径" test
mvn -s settings.xml -Dprobe.tableAware=true -Dschool.docx="规范文件的绝对路径" test
```

首次命令中的预期失败不能被计入产品通过率。测试报告位于 target/surefire-reports；正式设计需结合试验报告的限制。

完整研究检查（包含单样本计时）：

```powershell
mvn -s settings.xml -Dprobe.tableAware=true -Dprobe.measure=true -Dschool.docx="规范文件的绝对路径" test
```

不传 probe.measure 时，计时类明确跳过。首次基线仅针对 EffectiveFormattingTest 的 6 个初始检查；当前完整源码另有补充检查，复现该已保存基线可使用 Git 前的证据文件核对，不将新增检查数量混入历史结果。

[实际结果及局限](../../docs/research/第三轮docx4j能力试验结果.md)。日志中的 WPS 兼容警告是已复现的覆盖限制，不能屏蔽后宣称完全兼容。
