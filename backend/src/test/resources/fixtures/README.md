# 检测内核测试样本

测试当前使用 JUnit 在临时目录生成的脱敏 DOCX fixture，覆盖样式继承、混合中英文、分节页边距和结构路径。学校原始规范不复制进仓库；未获得授权的论文不能作为测试 fixture。

运行后端验证：

```powershell
mvn -B -ntp verify -f backend/pom.xml
```

当前通过的测试包括受限解包、有效属性解析、规则 PASS/FAIL/REVIEW、正文/表格批注副本和端到端 JSON 报告。
