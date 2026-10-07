# MTEF 公式研究试验

仅研究，不接入生产。结论见[第七轮报告](../../docs/research/第七轮旧公式解析研究结论.md)。不启动 OLE 对象，不写学校原件。

## 依赖及来源

- Python olefile 0.47，安装在本目录 `.deps`。
- JRuby complete 9.3.8.0，来自 Maven 公共镜像，SHA-256 为 `b574542f75c71d543ae057a707ca9840e778d62ab6da610e94cd7bbfa5eef5d5`。
- transpect/mathtype-extension 仓库中的 `ruby/mathtype-0.0.7.5/lib` 与 `ruby/bindata-2.3.5/lib`，共 83 个源文件。下载后逐个按 Git blob SHA 校验；对应清单为 `../../docs/research/2026-10-04/transpect__mathtype-extension-tree.json`。第三方库未修改。
- `vendor`、`.deps`、`private-output` 均忽略入库。若迁移环境，必须按保存的文件 SHA 恢复依赖，不能将上游 HEAD 当作固定版本。

未安装完整 Calabash、Nokogiri 或 MathML 转换链。直接调用记录解析器的 snapshot，避免引入无关转换、字体映射和上游 OLE 读取器的 rb+ 打开方式。

## 本地复现

在本目录运行：

```powershell
python -m pip install --target .deps olefile==0.47
python inspect_ole.py '<学校规范绝对路径>'
java -cp vendor/jruby-complete-9.3.8.0.jar org.jruby.Main -I vendor/ruby/bindata-2.3.5/lib -I vendor/ruby/mathtype-0.0.7.5/lib decode_records.rb private-output/oleObject1.mtef private-output/oleObject2.mtef
java -cp vendor/jruby-complete-9.3.8.0.jar org.jruby.Main -I vendor/ruby/bindata-2.3.5/lib -I vendor/ruby/mathtype-0.0.7.5/lib probe_size_precision.rb
```

最后一条是上游精度能力探针，**当前预期退出 1**：MTEF 3/5 均将 +0.5pt 增量输出为 0。它不能算通过的产品测试。Java/docx4j 的前轮 30 项测试与本目录是两套研究，不合并为“全部通过”。

`inspect_ole.py` 是本地受信样本工具，不是通用上传入口：检查了本样本的 28 字节头、载荷长度、支持版本及原件未变，没有实现恶意 ZIP/OLE 的完整资源限制。

完整公式记录放在 `private-output`；证据目录只保存格式信息、计数、哈希和失败结果。非 ASCII 字体名保留原始十六进制字节，不猜编码或用替换字符吞掉数据。JRuby 的本地子进程访问警告保留，本实验没有使用子进程功能，没有为此放宽 JVM 模块权限。
