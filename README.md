# 毕业论文格式筛查研究

当前实现：单机 DOCX 筛查 API、浏览器上传页面、确定性格式规则、JSON 报告和可选 Word 批注副本。暂不包含登录注册、数据库或后台任务队列。

后端使用 Java 21、Spring Boot 和 docx4j；前端使用 React、TypeScript 和 Vite。DOCX 读取有压缩包、单部件和累计解压大小限制。对外结果只显示“通过/格式错误”，错误会生成带批注副本；实际 Word 分页仍不由 XML 规则裁决。

## 本地运行

需要 Java 21+、Maven 和 Node.js。

```powershell
rtk mvn -f backend/pom.xml spring-boot:run
```

在另一个终端运行前端：

```powershell
cd frontend
rtk npm run dev
```

默认后端地址为 `http://127.0.0.1:18098`，Vite 开发服务器会通过 `/api` 代理到该地址并在终端显示前端地址。上传接口为 `POST /api/inspections`；设置 `annotate=true` 可生成批注副本。部署到其他地址时可设置前端环境变量 `VITE_API_URL`。

## 验证

```powershell
rtk mvn -f backend/pom.xml verify
cd frontend
rtk npm run build
```

当前后端完整验证已通过，真实论文样本复筛记录、规则状态和剩余验收缺口见[学校规则矩阵与核心验收清单](docs/research/学校规则矩阵与核心验收清单.md)。

仍未完成真实分页渲染验证、页眉/页脚批注、参考文献自定义章节别名，以及基于多篇论文人工标注集的误报/漏报评估；因此当前不能宣称任意论文都能准确筛查或“没有问题”。

- [核心系统架构设计稿](docs/superpowers/specs/2026-10-04-论文格式筛查核心系统设计.md)
- [Word 批注副本试验](research-spikes/comments/README.md)
- [第九轮 Word 批注写入与回读](docs/research/第九轮Word批注写入与回读试验.md)
- [第十轮批注锚点覆盖评估](docs/research/第十轮批注锚点覆盖评估.md)
- [第十一轮表格批注锚点](docs/research/第十一轮批注表格锚点试验.md)
- [第十二轮文本框批注分支安全](docs/research/第十二轮文本框批注分支安全试验.md)
- [第十三轮页眉页脚批注关系](docs/research/第十三轮页眉页脚批注关系试验.md)

研究收敛入口：[学校规则矩阵与核心验收清单](docs/research/学校规则矩阵与核心验收清单.md)。它区分规范明确要求、建议、待确认项和当前实现缺口；不能把研究测试通过视为完整产品通过。

- [学校格式筛查蓝图草案](docs/论文格式筛查项目蓝图与计划.md)
- [第二轮开源研究](docs/research/第二轮开源与解析研究结论.md)
- [docx4j 可复现实验](research-spikes/docx4j/README.md)
- [第三轮实际运行结果](docs/research/第三轮docx4j能力试验结果.md)
- [第四轮遍历与原文定位结果](docs/research/第四轮遍历与原文定位试验结果.md)
- [第五轮真实模板来源关联](docs/research/第五轮真实模板来源关联试验结果.md)
- [第六轮字体与字号解析](docs/research/第六轮字体与字号解析试验结果.md)
- [第七轮旧公式解析](docs/research/第七轮旧公式解析研究结论.md)
- [第八轮公式外部规则与模板核对](docs/research/第八轮公式外部规则与模板核对.md)

已确认学校四边页边距严格为 2.6 cm。原始规范与论文保存在用户本地，代码库只保存研究、测试和不含论文全文的证据。研究中引用的第三方源码快照仅供溯源，复用时遵循各项目许可证。
