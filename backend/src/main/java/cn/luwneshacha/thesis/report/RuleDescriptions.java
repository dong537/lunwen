package cn.luwneshacha.thesis.report;

import cn.luwneshacha.thesis.rules.CheckResult;
import cn.luwneshacha.thesis.rules.Judgement;

/** 把内部规则编号转换为面向论文作者的中文说明。 */
public final class RuleDescriptions {
  private RuleDescriptions() {}

  public static String name(String ruleId) {
    if (ruleId == null) return "格式检查";
    return switch (ruleId) {
      case "PAGE-01" -> "页边距"; case "PAGE-04", "PAGE-05" -> "页码格式";
      case "PAGE-03" -> "章节分页";
      case "HEADER-01-MISSING", "HEADER-01-TEXT" -> "页眉文字";
      case "HEADER-01-SCOPE" -> "页眉检查范围";
      case "HEADER-01-FONT" -> "页眉字体";
      case "HEADER-01-SIZE" -> "页眉字号";
      case "HEADER-01-ALIGNMENT" -> "页眉对齐";
      case "COVER-01-FONT" -> "封面学校名称字体";
      case "COVER-01-SIZE" -> "封面学校名称字号";
      case "COVER-01-ALIGNMENT" -> "封面学校名称对齐";
      case "COVER-02-FONT" -> "封面论文题目字体";
      case "COVER-02-SIZE" -> "封面论文题目字号";
      case "COVER-03-FONT", "COVER-03-SIZE", "COVER-03-BOLD" -> "封面个人信息格式";
      case "COVER-03-LABEL" -> "封面个人信息标签";
      case "COVER-04-FONT", "COVER-04-SIZE", "COVER-04-BOLD" -> "封面日期格式";
      case "COVER-04-CONSISTENCY" -> "封面日期一致性";
      case "HEAD-05-MARKER" -> "三级以下标题编号";
      case "HEAD-01-SPACING" -> "一级标题编号空格";
      case "HEAD-01-ALIGNMENT" -> "一级标题对齐";
      case "HEAD-02-BLANK" -> "章标题后空行";
      case "HEAD-02-SPACING", "HEAD-03-SPACING" -> "标题编号空格";
      case "HEAD-03-INDENT" -> "三级标题缩进";
      case "HEAD-05-FONT" -> "三级以下标题字体";
      case "HEAD-05-SIZE" -> "三级以下标题字号";
      case "HEAD-05-LINE" -> "三级以下标题行距";
      case "HEAD-05-INDENT" -> "三级以下标题缩进";
      case "TOC-01", "TOC-01-SPACING", "TOC-01-BLANK", "TOC-01-LINE", "TOC-01-FONT",
          "TOC-01-SIZE", "TOC-01-ALIGNMENT" -> "目录标题格式";
      case "TOC-SPACING" -> "目录编号空格";
      case "TOC-ENTRY-FONT" -> "目录条目字体";
      case "TOC-ENTRY-SIZE" -> "目录条目字号";
      case "TOC-ENTRY-LINE" -> "目录条目行距";
      case "TOC-ENTRY-INDENT" -> "目录条目缩进";
      case "TOC-ENTRY-PAGE" -> "目录条目页码";
      case "TOC-REQUIRED-ENTRY" -> "目录必需条目";
      case "TOC-02" -> "目录与正文标题对应";
      case "TOC-02-ORDER" -> "目录与正文标题顺序";
      case "EN-TOC-01-BOLD" -> "英文目录标题加粗";
      case "EN-TOC-01-ALIGNMENT" -> "英文目录标题对齐";
      case "EN-TOC-01-FONT" -> "英文目录标题字体";
      case "EN-TOC-01-SIZE" -> "英文目录标题字号";
      case "EN-TOC-01-LINE" -> "英文目录标题行距";
      case "BODY-FONT" -> "正文字体"; case "BODY-SIZE" -> "正文字号"; case "BODY-INDENT" -> "正文首行缩进"; case "BODY-LINE" -> "正文行距";
      case "HEADING-FONT" -> "标题字体"; case "HEADING-SIZE" -> "标题字号"; case "HEADING-LINE" -> "标题行距";
      case "BODY-SCOPE" -> "正文范围";
      case "ABSTRACT-FONT" -> "摘要正文字体"; case "ABSTRACT-SIZE" -> "摘要正文字号";
      case "ABSTRACT-INDENT" -> "摘要首行缩进"; case "ABSTRACT-LINE" -> "摘要行距";
      case "ABSTRACT-KEYWORD-BOLD", "ABSTRACT-KEYWORD-FORMAT" -> "中文关键词格式";
      case "ABSTRACT-KEYWORD-ALIGN" -> "中文关键词对齐";
      case "ZH-01", "ZH-01-SPACING", "ZH-01-FONT", "ZH-01-SIZE", "ZH-01-ALIGNMENT" -> "中文摘要标题格式";
      case "ZH-02-BLANK" -> "摘要标题后空行";
      case "ZH-04-BLANK" -> "摘要与关键词间空行";
      case "EN-01", "EN-01-TEXT", "EN-01-BOLD", "EN-01-FONT", "EN-01-SIZE", "EN-01-ALIGNMENT" -> "英文摘要标题格式";
      case "EN-01-BLANK" -> "英文摘要标题后空行";
      case "EN-03-BOLD", "EN-03-FORMAT", "EN-03-ALIGNMENT" -> "英文关键词格式";
      case "EN-03-BLANK" -> "英文摘要与关键词间空行";
      case "REF-01", "REF-01-FONT" -> "参考文献标题字体";
      case "REF-01-SPACING" -> "参考文献标题字间格式";
      case "REF-01-SIZE" -> "参考文献标题字号";
      case "REF-01-ALIGNMENT" -> "参考文献标题对齐";
      case "REF-01-BLANK" -> "参考文献标题后空行";
      case "END-01", "END-01-FONT" -> "致谢标题字体";
      case "END-01-SIZE" -> "致谢标题字号";
      case "END-01-ALIGNMENT" -> "致谢标题对齐";
      case "END-01-SPACING" -> "致谢标题字间格式";
      case "END-01-BLANK" -> "致谢标题后空行";
      case "APP-01" -> "附录编号";
      case "APP-02", "APP-02-FONT" -> "附录标题字体";
      case "APP-02-SIZE" -> "附录标题字号";
      case "APP-02-ALIGNMENT" -> "附录标题对齐";
      case "APP-02-SPACING" -> "附录标题字间空格";
      case "APP-02-BLANK" -> "附录标题后空行";
      case "APP-03" -> "附录分页";
      case "TAB-01" -> "表格正文引用"; case "TAB-02" -> "三线表边框";
      case "TAB-03" -> "表号与表题"; case "TAB-04" -> "表格量和单位";
      case "TAB-05" -> "表号连续性"; case "TAB-06" -> "表格重复文字";
      case "TAB-07" -> "表注位置"; case "TAB-08" -> "续列表头";
      case "FIG-01" -> "图片正文引用"; case "FIG-02" -> "图片和图题位置";
      case "FIG-03" -> "图号与图题格式"; case "FIG-04" -> "图片尺寸"; case "FIG-05" -> "图号连续性";
      case "CAPTION-FONT" -> "图表题字体"; case "CAPTION-SIZE" -> "图表题字号";
      case "EQ-01" -> "公式编号"; case "EQ-02" -> "公式编号字体字号";
      case "EQ-03" -> "公式对齐"; case "EQ-04" -> "公式正文引用";
      case "EQ-05" -> "公式编号连续性"; case "EQ-06" -> "公式对象格式"; case "EQ-07" -> "公式变量说明";
      case "REF-02" -> "参考文献类型标识"; case "REF-03" -> "参考文献引用和顺序";
      case "REF-04" -> "参考文献著录格式"; case "REF-05" -> "参考文献数量";
      case "REF-07" -> "参考文献编号"; case "REF-FONT" -> "参考文献字体";
      case "REF-SIZE" -> "参考文献字号"; case "REF-LINE" -> "参考文献行距";
      case "SECTION-REQUIRED" -> "必需章节";
      case "COVER-05" -> "封面必填字段";
      case "CITE-01" -> "正文文献引用";
      default -> "格式错误";
    };
  }

  public static String name(CheckResult result) {
    if (result == null || !"HEADING-FONT".equals(result.ruleId()))
      return name(result == null ? null : result.ruleId());
    return headingLevel(result.expected()) + "字体";
  }

  public static String reason(CheckResult result) {
    String code = result.reasonCode();
    if (code == null || code.isBlank()) return "未达到学校论文格式要求";
    if ("INDENT_EVIDENCE_INVALID".equals(code)) return "缩进设置存在冲突或数值无法读取，不能按规范判定为正确";
    if ("INDENT_TEXT_WHITESPACE".equals(code)) {
      String section = "ABSTRACT-INDENT".equals(result.ruleId()) ? "摘要正文" : "正文";
      java.util.regex.Matcher count = java.util.regex.Pattern
          .compile("文字前有 (\\d+) 个文字空白").matcher(result.actual() == null ? "" : result.actual());
      String quantity = count.find() ? count.group(1) : "";
      return section + "段首另有 " + quantity + " 个空白字符，但段落首行缩进符合 2 字符要求。";
    }
    if ("LINE_SPACING_INVALID".equals(code)) return "行距设置的数值无法读取，不符合明确的行距要求";
    if ("MARGIN_INVALID".equals(code)) return "页边距设置的数值无法读取，不符合四边 2.6 cm 的要求";
    return switch (code) {
      case "MARGIN_MISMATCH" -> "页边距不是 2.6 cm";
      case "MARGIN_GUTTER_MISMATCH" -> "页面设置了额外装订线";
      case "MARGIN_GUTTER_UNKNOWN" -> "Word XML 中没有可确认的装订线数值";
      case "FONT_MISMATCH" -> "字体与规范不一致";
      case "SIZE_MISMATCH" -> "字号与规范不一致";
      case "INDENT_MISMATCH" -> switch (result.ruleId()) {
        case "BODY-INDENT" -> "正文段落首行缩进不符合要求";
        case "ABSTRACT-INDENT" -> "摘要正文首行缩进不符合要求";
        case "TOC-ENTRY-INDENT" -> "目录条目左侧缩进不符合要求";
        default -> "首行缩进不符合学校规范";
      };
      case "TABLE_CAPTION_MISSING" -> "缺少表号和表题";
      case "TABLE_CAPTION_BELOW" -> "表题放在了表格下方";
      case "FIGURE_CAPTION_MISSING" -> "缺少图号和图题";
      case "FIGURE_CAPTION_NUMBER_FORMAT" -> "图号与图题之间的格式不对";
      case "TABLE_CAPTION_NUMBER_FORMAT" -> "表号与表题之间应留一个空格";
      case "TOC_NUMBER_SPACING_MISMATCH" -> "目录编号后空格不符合要求";
      case "HEADING_ALIGNMENT_MISMATCH" -> "一级标题没有居中";
      case "TOC_PAGE_NUMBER_MISSING" -> "目录条目没有页码";
      case "TOC_PAGE_NUMBER_NON_NUMERIC" -> "目录条目页码不是数字";
      case "TOC_ENTRY_NOT_FOUND_IN_BODY" -> "目录中有条目，但正文中没有对应标题";
      case "BODY_HEADING_NOT_IN_TOC" -> "正文中有标题，但目录中没有对应条目";
      case "TOC_BODY_ORDER_MISMATCH" -> "目录条目顺序与正文标题顺序不一致";
      case "TOC_REQUIRED_CHAPTER_MISSING", "TOC_REQUIRED_REFERENCES_MISSING",
          "TOC_REQUIRED_ACKNOWLEDGEMENT_MISSING" -> "目录缺少学校要求的必需条目";
      case "TOC_REQUIRED_APPENDIX_MISSING" -> "论文已有附录，但目录中没有对应的附录条目";
      case "TOC_REQUIRED_ENTRY_PAGE_MISSING" -> "目录必需条目没有对应的数字页码";
      case "TABLE_CONTINUATION_NUMBER_FORMAT" -> "续表标记只能写“续表”加编号，例如“续表4-1”";
      case "EQUATION_NUMBER_MISSING" -> "公式没有公式号";
      case "EQUATION_NUMBER_PARENTHESES_MISSING" -> "公式编号没有加圆括号";
      case "EQUATION_NUMBER_PATTERN_MISMATCH" -> "公式编号应使用半角连字符，例如 3-1";
      case "EQUATION_ALIGNMENT_MISMATCH" -> "公式未居中或公式号未右对齐";
      case "EQUATION_NUMBER_SEQUENCE_MISMATCH" -> "公式号没有按章节连续编排";
      case "EQUATION_NOT_CITED" -> "正文没有引用该公式";
      case "REFERENCE_NOT_CITED" -> "这条参考文献没有在正文中引用";
      case "CITED_REFERENCE_MISSING" -> "正文引用没有对应的参考文献";
      case "CITATION_RANGE_MISMATCH" -> "引用范围中的编号必须都能在文末参考文献中找到对应条目";
      case "REFERENCE_COUNT_TOO_LOW" -> "参考文献少于 10 篇";
      case "REQUIRED_SECTION_MISSING" -> "学校论文必须包含此章节，但没有找到";
      case "APPENDIX_TITLE_MISSING" -> "已找到附录分项，但没有找到附录总标题“附    录”";
      case "APPENDIX_SECTION_MISSING" -> "已有附录总标题，但没有找到以字母编号的附录分项";
      case "THESIS_DOCUMENT_CLASSIFICATION_REVIEW" -> "文档结构线索不足，无法确认必需章节检查是否适用";
      case "COVER_MISSING" -> "未识别到规范封面信息";
      case "COVER_FIELD_MISSING" -> "封面缺少规范要求的字段";
      case "COVER_SCHOOL_NAME_IMAGE_UNCONFIRMED" -> "学校名称可能位于封面图片中，文档文字层无法读取图片内容，因此不能判定缺失";
      case "REFERENCE_TYPE_MISSING" -> "参考文献缺少文献类型标识";
      case "COVER_DATE_MISMATCH" -> "封面日期与其他日期不一致";
      case "LOWER_HEADING_MARKER_MISMATCH" -> "三级以下标题编号应使用中文括号和顿号";
      case "TABLE_UNIT_LOCATION_NOT_CAPTURED" -> "未检测到表格量名称和单位标注，按缺少量和单位处理";
      case "TABLE_UNIT_DIMENSION_UNKNOWN" -> "表头中的量名称无法识别，无法确认所标单位是否匹配";
      case "EQUATION_LAYOUT_REVIEW", "EQUATION_ALIGNMENT_UNKNOWN" -> "公式或编号的对齐方式无法从文件中确认，不代表格式错误";
      case "EQUATION_VARIABLES_REVIEW", "EQUATION_VARIABLES_UNREADABLE" -> "公式内部结构无法读取，暂时不能确认变量说明是否齐全，不代表格式错误";
      case "EQUATION_VARIABLES_MISSING" -> "公式变量说明不完整，请补充批注中列出的变量含义";
      case "CITATION_FORMAT_MISMATCH" -> "引用编号应使用小四号 Times New Roman 并设为上标";
      case "CITATION_FORMAT_UNKNOWN" -> "未读取到引用编号的完整字体或字号属性，暂无法确认是否符合规范，请在 Word 中核对";
      case "BOLD_UNKNOWN" -> "未读取到关键词标签的加粗属性";
      case "ALIGNMENT_UNKNOWN", "TABLE_CAPTION_ALIGNMENT_UNKNOWN" -> "Word XML 中没有明确的段落对齐属性，无法确认是否居中";
      case "FONT_UNKNOWN" -> "Word XML 中没有明确的字体属性，无法确认字体是否符合规范";
      case "SIZE_UNKNOWN" -> "Word XML 中没有明确的字号属性，无法确认字号是否符合规范";
      case "INDENT_UNKNOWN" -> "Word XML 中没有明确的缩进属性，无法确认首行缩进是否符合规范";
      case "LINE_SPACING_UNKNOWN" -> "Word XML 中没有明确的行距属性，无法确认行距是否符合规范";
      case "MARGIN_UNKNOWN" -> "Word XML 中没有明确的页边距属性，无法确认页边距是否为 2.6 cm";
      case "PAGE_NUMBER_ALIGNMENT_UNKNOWN" -> "页码段落没有明确的对齐属性，无法确认是否居中";
      case "PAGE_NUMBER_FONT_UNKNOWN" -> "页码没有明确的字体属性，无法确认是否为 Times New Roman";
      case "PAGE_NUMBER_SIZE_UNKNOWN" -> "页码没有明确的字号属性，无法确认是否为五号";
      case "CHAPTER_PAGE_BREAK_REVIEW" -> "Word XML 中没有明确的分页属性，无法确认章节是否另起一页";
      case "CITATION_RANGE_REVIEW" -> "引用编号或范围的 XML 结构不完整，无法确认编号顺序和对应关系";
      case "EQUATION_NUMBER_FORMAT_UNKNOWN" -> "公式编号字体或字号属性不完整，无法确认是否为五号 Times New Roman";
      case "EQUATION_OBJECT_UNSUPPORTED" -> "该公式以图片、OLE 或 MathType 形式保存，系统无法读取其内部字体、变量斜体、上下标和内部结构，因此不作错误判定；转为 Word 原生公式后才能自动核验";
      case "FIGURE_LAYOUT_REVIEW" -> "XML 无法证明图题是否位于图片下方并居中，暂无法确认是否符合规范";
      case "FIGURE_NUMBER_UNKNOWN" -> "图题编号结构不完整，无法确认是否按章节连续编号";
      case "FIGURE_REFERENCE_NEEDS_REVIEW" -> "图题引用结构不完整，XML 无法证明正文是否已引用，暂无法确认是否符合规范";
      case "TABLE_LAYOUT_REVIEW" -> "XML 无法证明表题是否位于表格上方，暂无法确认是否符合规范";
      case "TABLE_BORDER_REVIEW", "TABLE_CONTINUATION_BORDER_REVIEW" -> "表格边框可能来自样式继承或条件格式，XML 无法完整确认三线表边框";
      case "TABLE_AUXILIARY_BORDER_REVIEW" -> "表格存在辅助线；学校规范允许必要辅助线，仍须保留三条必需横线";
      case "TABLE_BORDER_INVALID", "TABLE_CONTINUATION_BORDER_INVALID" -> result.actual();
      case "TABLE_BORDER_EVIDENCE_UNRESOLVED" -> "表格条件边框无法从 DOCX XML 完整解析，暂无法确认是否符合三线表规范";
      case "TABLE_XML_NUMERIC_INVALID" -> "表格 XML 中的跨度、偏移、宽度、高度或边框数值不是规范范围内的整数";
      case "IMAGE_XML_NUMERIC_INVALID" -> "图片 XML 中的宽度或高度不是有效的正整数";
      case "KEYWORD_ALIGNMENT_UNKNOWN" -> "关键词段落没有明确的对齐属性，无法确认是否两端对齐";
      case "KEYWORD_LABEL_UNREADABLE" -> "关键词标签跨多个文本节点，无法确认“关键词”是否单独加粗";
      case "COVER_TITLE_LABEL_AND_VALUE_SHARE_RUN" -> "封面题目标签和题目内容在同一文本节点，无法分别确认题目格式";
      case "COVER_FIELD_LABEL_AND_VALUE_SHARE_RUN" -> "封面字段标签和字段内容在同一文本节点，无法分别确认字段格式";
      case "COVER_FIELD_LABEL_MISMATCH" -> "封面字段名称或分隔符不符合学校规范";
      case "COVER_FIELD_VALUE_UNKNOWN" -> "没有从封面字段中定位到可独立检查的字段内容";
      case "KEYWORD_ALIGNMENT_MISMATCH" -> "中文关键词段落没有两端对齐";
      case "TABLE_NOTE_NOT_LEFT_ALIGNED" -> "表注没有在表格下方左对齐";
      case "TABLE_NOTE_ALIGNMENT_UNKNOWN" -> "无法从 DOCX XML 确认表注是否左对齐";
      case "TABLE_CONTINUATION_HEADER_MISMATCH" -> "续表表头与原表表头不一致";
      case "TABLE_CONTINUATION_ORIGINAL_MISSING" -> "未找到与续表编号对应的前置原表";
      case "TABLE_CONTINUATION_HEADER_UNREADABLE" -> "未能读取原表的表头结构";
      case "TABLE_REPEATED_PLACEHOLDER" -> "表格中不能用“同前”“同左”代替内容";
      case "PAGE_NUMBER_FORMAT_MISMATCH" -> "正文到附录应使用阿拉伯数字页码";
      case "PAGE_NUMBER_START_MISMATCH" -> "正文第一页页码应从 1 开始";
      case "PAGE_NUMBER_START_INVALID" -> "页码起始值存在但不是可解析的有效整数";
      case "PAGE_NUMBER_SEQUENCE_RESTARTED" -> "页码在分节处重新开始，未保持连续编排";
      case "PAGE_NUMBER_SCOPE_UNKNOWN" -> "无法从章节和分节信息确定正文页码检查范围";
      case "PAGE_NUMBER_SEQUENCE_CONTINUITY_UNCONFIRMED" -> "无法仅凭 XML 确认分节间实际页码是否连续";
      case "PAGE_NUMBER_IN_FRONT_MATTER" -> "封面、摘要和目录部分出现了页码";
      case "PAGE_NUMBER_MISSING_IN_MAIN_BODY" -> "正文或附录有分节没有页码域";
      case "HEADER_MISSING" -> "摘要至附录有分节没有可用的页眉";
      case "HEADER_TEXT_MISMATCH" -> "页眉文字不是学校规定的完整名称";
      case "HEADER_FONT_UNKNOWN" -> "页眉没有明确的字体属性，无法确认是否为黑体";
      case "HEADER_FONT_MISMATCH" -> "页眉字体不是黑体";
      case "HEADER_SIZE_UNKNOWN" -> "页眉没有明确的字号属性，无法确认是否为五号";
      case "HEADER_SIZE_MISMATCH" -> "页眉字号不是五号";
      case "HEADER_ALIGNMENT_UNKNOWN" -> "页眉没有明确的对齐属性，无法确认是否居中";
      case "HEADER_NOT_CENTERED" -> "页眉没有居中";
      case "ABSTRACT_TITLE_SPACING_MISMATCH" -> "中文摘要标题字间空格数量不对";
      case "REFERENCE_TITLE_SPACING_MISMATCH" -> "参考文献标题文字之间不应加空格";
      case "ABSTRACT_TITLE_CASE_MISMATCH" -> "英文摘要标题应使用全大写 ABSTRACT";
      case "ABSTRACT_TITLE_BLANK_LINE_MISSING" -> "中文摘要标题后没有空一行";
      case "ABSTRACT_KEYWORD_BLANK_LINE_MISSING" -> "摘要正文与关键词之间没有空一行";
      case "ACKNOWLEDGEMENT_TITLE_SPACING_MISMATCH" -> "致谢标题中间应有 4 个普通空格";
      case "REFERENCE_TITLE_BLANK_LINE_MISSING" -> "参考文献标题后没有空一行";
      case "REFERENCE_TITLE_BLANK_LINE_COUNT_MISMATCH" -> "参考文献标题后应只空一行";
      case "ACKNOWLEDGEMENT_TITLE_BLANK_LINE_MISSING" -> "致谢标题后没有空一行";
      case "ACKNOWLEDGEMENT_TITLE_BLANK_LINE_COUNT_MISMATCH" -> "致谢标题后应只空一行";
      case "APPENDIX_TITLE_BLANK_LINE_MISSING" -> "附录标题后没有空一行";
      case "APPENDIX_TITLE_BLANK_LINE_COUNT_MISMATCH" -> "附录标题后应只空一行";
      case "APPENDIX_TITLE_SPACING_MISMATCH" -> "附录标题字间空格数量不对";
      case "APPENDIX_SEQUENCE_MISMATCH" -> "附录编号没有按字母连续排列";
      case "APPENDIX_OBJECT_PREFIX_MISSING" -> "附录中的图、表或公式编号缺少附录字母";
      case "APPENDIX_OBJECT_LETTER_MISMATCH" -> "附录中的图、表或公式编号字母不匹配";
      case "CHAPTER_BODY_BLANK_LINE_MISSING" -> "章标题与正文之间没有空一行";
      case "ENGLISH_KEYWORD_WRAP_ALIGNMENT_UNKNOWN" -> "英文关键词换行后的首字母对齐方式无法从 XML 确认";
      case "ENGLISH_KEYWORD_WRAP_ALIGNMENT_MISMATCH" -> "英文关键词换行后没有与第一个关键词首字母对齐";
      case "BODY_REGION_UNDETERMINED" -> "无法从章节证据确定正文检查范围";
      case "APPENDIX_PAGE_BREAK_REVIEW" -> "无法从 XML 确认附录标题是否另起一页";
      case "CONCLUSION_CHAPTER_MISMATCH" -> "结论章节不是第五章";
      case "CONCLUSION_CHAPTER_UNCONFIRMED" -> "无法从无编号标题确认结论是否为第五章";
      case "ENGLISH_KEYWORD_SEPARATOR_MISMATCH" -> "英文关键词没有用分号分隔";
      case "ENGLISH_KEYWORD_COUNT_MISMATCH" -> "英文关键词应为3至5个";
      case "EQUATION_NOT_REFERENCED" -> "正文没有引用该公式";
      case "EQUATION_NUMBER_DUPLICATE" -> "公式编号重复";
      case "EQUATION_NUMBER_FORMAT_MISMATCH" -> "公式号字体或字号不符合要求";
      case "FIGURE_NOT_CENTERED" -> "图题没有居中";
      case "FIGURE_NOT_CITED_IN_BODY" -> "正文没有引用该图";
      case "FIGURE_NUMBER_SEQUENCE_MISMATCH" -> "图号没有按章节连续编号";
      case "HEADING_INDENT_MISMATCH" -> "标题首行缩进不符合要求";
      case "HEADING_NUMBER_SPACING_MISMATCH" -> "标题序数后的空格数量不对";
      case "HEADING_NUMBER_INTERNAL_WHITESPACE" -> "标题序号内部多了空格";
      case "KEYWORD_LABEL_NOT_BOLD" -> "EN-03-BOLD".equals(result.ruleId())
          ? "“Keywords”标签（含冒号）没有加粗"
          : "关键词标签没有加粗";
      case "KEYWORD_CONTENT_BOLD" -> result.actual() == null || result.actual().isBlank()
          ? "Keywords 标签后的冒号或关键词内容被加粗"
          : "Keywords 标签后的" + result.actual() + "；规范只要求标签加粗";
      case "KEYWORD_CONTENT_BOLD_UNKNOWN" -> "无法读取 Keywords 标签后内容的字重，暂不能确认是否保持常规字重";
      case "KEYWORD_LIST_MISMATCH" -> "关键词数量、分隔符或结尾标点不符合要求";
      case "KEYWORD_SEPARATOR_MISMATCH" -> "关键词分隔符或结尾标点不符合要求";
      case "PAGE_NUMBER_FONT_MISMATCH" -> "页码字体不是 Times New Roman";
      case "PAGE_NUMBER_NOT_CENTERED" -> "页码没有居中";
      case "PAGE_NUMBER_SIZE_MISMATCH" -> "页码字号不是五号";
      case "REFERENCE_CATEGORY_MISSING" -> "参考文献缺少类别标识";
      case "REFERENCE_NUMBER_DUPLICATE" -> "参考文献编号重复";
      case "REFERENCE_NUMBER_SEQUENCE_MISMATCH" -> "参考文献编号没有连续排列";
      case "REFERENCE_NUMBER_INVALID" -> "参考文献编号格式不正确";
      case "REFERENCE_NUMBER_SPACING_MISMATCH" -> "参考文献编号与文献内容之间不是一个普通半角空格";
      case "REFERENCE_NUMBER_SPACING_UNCONFIRMED" -> "无法从文档结构确认自动编号后的分隔符是否为一个普通半角空格";
      case "REFERENCE_PAGE_RANGE_SPACING" -> "页码范围连接号两侧有多余空格";
      case "REFERENCE_ORDER_MISMATCH" -> "参考文献顺序与正文首次引用顺序不一致";
      case "SECTION_TITLE_BLANK_LINE_MISSING" -> "标题与正文之间没有空一行";
      case "TABLE_CAPTION_NOT_CENTERED" -> "表题没有居中";
      case "TABLE_CONTINUATION_CAPTION_NOT_RIGHT" -> "续表标记没有右对齐";
      case "TABLE_CONTINUATION_CAPTION_ALIGNMENT_UNKNOWN" -> "无法确认续表标记是否右对齐";
      case "TABLE_NOT_CITED_IN_BODY" -> "正文没有引用该表";
      case "TABLE_NUMBER_SEQUENCE_MISMATCH" -> "表号没有按章节连续编号";
      case "TOC_TITLE_BLANK_LINE_MISSING" -> "目录标题后没有空一行";
      case "TOC_TITLE_SPACING_MISMATCH" -> "目录标题字间空格数量不对";
      default -> "格式与学校论文规范不一致";
    };
  }

  public static String suggestion(CheckResult result) {
    if ("REFERENCE_PAGE_RANGE_SPACING".equals(result.reasonCode()))
      return "请删除页码范围连接号两侧的空格，写成“140-145”这样的格式。";
    if ("REFERENCE_NUMBER_SPACING_MISMATCH".equals(result.reasonCode())) {
      String actual = result.actual() == null ? "" : result.actual();
      if (actual.matches(".*suffix=0, formatTrailing=\\d+.*"))
        return "自动编号的“编号之后”应设为“空格”；不要使用制表符或不留间隔。";
      if (actual.contains("没有空格"))
        return "参考文献编号后缺少空格；请在编号后补一个普通半角空格，再接作者姓名。";
      if (actual.startsWith("编号后连续 ")) {
        String count = actual.substring("编号后连续 ".length()).split(" ", 2)[0];
        return "参考文献编号后有 " + count + " 个普通半角空格；请删去多余空格，只保留一个，再接作者姓名。";
      }
      if (actual.contains("空白字符不符合要求"))
        return "参考文献编号后使用的空白符号不符合要求；请换成一个普通半角空格，再接作者姓名。";
      return "参考文献编号与作者姓名之间只保留一个普通半角空格。";
    }
    if ("REFERENCE_NUMBER_SPACING_UNCONFIRMED".equals(result.reasonCode()))
      return "请在 Word 中检查参考文献自动编号后是否只保留一个普通半角空格；建议转换为可直接编辑的编号文本后重新检查。";
    if ("HEADING_NUMBER_INTERNAL_WHITESPACE".equals(result.reasonCode()))
      return "编号写成了“" + result.actual() + "”，请删除编号内部的空格，改为“" + result.expected()
          + "”。编号与标题文字之间保留一个普通空格。";
    if ("HEADING_NUMBER_SPACING_MISMATCH".equals(result.reasonCode()))
      return "标题编号与标题文字之间应留一个半角空格，请删掉多余空白或补上空格。";
    if ("EN-03-BOLD".equals(result.ruleId())
        && ("KEYWORD_CONTENT_BOLD".equals(result.reasonCode())
            || "KEYWORD_LABEL_NOT_BOLD".equals(result.reasonCode()))) {
      if ("KEYWORD_LABEL_NOT_BOLD".equals(result.reasonCode()))
        return "请将“Keywords”标签（含冒号）加粗；冒号后的空格和关键词正文保持常规字重。";
      String actual = result.actual() == null ? "" : result.actual();
      if (actual.contains("冒号和关键词内容"))
        return "“Keywords”标签（含冒号）应保持加粗；取消关键词正文的加粗，冒号后的空格保持常规字重。";
      if (actual.contains("冒号及其后的空格"))
        return "“Keywords”标签（含冒号）应保持加粗；请取消冒号后的空格加粗，关键词正文保持常规字重。";
      if (actual.contains("冒号、空格和关键词内容"))
        return "“Keywords”标签（含冒号）应保持加粗；取消冒号后的空格和关键词正文的加粗。";
      if (actual.contains("关键词内容"))
        return "关键词正文被加粗；请取消其加粗，保留“Keywords”标签（含冒号）加粗；冒号后的空格保持常规字重。";
      if (actual.contains("冒号"))
        return "请将“Keywords”标签（含冒号）加粗；冒号后的空格和关键词正文保持常规字重。";
      return "请将“Keywords”标签（含冒号）加粗；冒号后的空格和关键词正文保持常规字重。";
    }
    if ("BODY-INDENT".equals(result.ruleId()) || "ABSTRACT-INDENT".equals(result.ruleId()))
      return indentSuggestion(result);
    if (result.judgement() == Judgement.REVIEW) {
      return switch (result.reasonCode()) {
        case "CITATION_FORMAT_UNKNOWN" -> "请在 Word 中检查该引用的字体、字号和上标状态；确认确实不符合规范后再调整。";
        case "FIGURE_LAYOUT_REVIEW" -> "请在 Word 中确认图题是否确实位于图片下方并居中；确认不符合规范后再调整。";
        case "FIGURE_REFERENCE_NEEDS_REVIEW" -> "请在 Word 中确认正文是否已经引用该图；只有确认缺少时再补充引用。";
        case "TABLE_LAYOUT_REVIEW" -> "请在 Word 中确认表题是否确实位于表格上方；确认不符合规范后再调整。";
        case "TABLE_BORDER_EVIDENCE_UNRESOLVED", "TABLE_BORDER_REVIEW", "TABLE_CONTINUATION_BORDER_REVIEW" -> "请在 Word 中确认表格是否缺少三条必需横线；确认缺少后再调整边框。";
        case "TABLE_AUXILIARY_BORDER_REVIEW" -> "请在 Word 中确认辅助线是否确有必要，并保留表格要求的三条必需横线。";
        case "EQUATION_LAYOUT_REVIEW", "EQUATION_ALIGNMENT_UNKNOWN" -> "请在 Word 中确认公式和编号的实际对齐方式；确认不符合规范后再调整。";
        case "EQUATION_VARIABLES_REVIEW", "EQUATION_VARIABLES_UNREADABLE" -> "请在 Word 中确认公式中的变量是否都已说明；只有确认缺少说明时再补充。";
        default -> "请在 Word 中核对该项的实际排版；确认确实不符合规范后再修改。";
      };
    }
    return switch (result.ruleId()) {
      case "PAGE-01" -> "MARGIN_GUTTER_MISMATCH".equals(result.reasonCode())
          ? "将页面设置中的装订线设为 0；四边边距保持 2.6 cm。"
          : "页面设置中的上、下、左、右边距统一改为 2.6 cm。";
      case "PAGE-04" -> switch (result.reasonCode()) {
        case "PAGE_NUMBER_IN_FRONT_MATTER" -> "删除封面、摘要和目录部分的页码。";
        case "PAGE_NUMBER_SEQUENCE_RESTARTED" -> "取消此处重新编号，保持正文到附录页码连续。";
        case "PAGE_NUMBER_START_MISMATCH" -> "将正文第一页页码设置为 1，后续章节保持连续编号。";
        case "PAGE_NUMBER_MISSING_IN_MAIN_BODY" -> "在此节页脚插入页码，并保持正文到附录连续编排。";
        case "PAGE_NUMBER_SCOPE_UNKNOWN" -> "检查正文首章标题和分节结构，使系统能够确定正文页码检查范围后重新检测。";
        case "PAGE_NUMBER_SEQUENCE_CONTINUITY_UNCONFIRMED" -> "在 Word 中查看分节后的实际页码是否接续上一节；XML 不能仅凭本节起始值证明连续性。";
        default -> "将正文到附录的页码设置为阿拉伯数字。";
      };
      case "PAGE-05" -> switch (result.reasonCode()) {
        case "PAGE_NUMBER_NOT_CENTERED" -> "将页脚中的页码段落设为居中。";
        case "PAGE_NUMBER_FONT_MISMATCH" -> "将页码字体改为 Times New Roman。";
        case "PAGE_NUMBER_SIZE_MISMATCH" -> "将页码字号改为五号。";
        default -> "将页码设置为页脚居中、Times New Roman 五号。";
      };
      case "HEADER-01-MISSING", "HEADER-01-TEXT" -> "封面不要求页眉；摘要至附录各分节的页眉统一写成“山东石油化工学院本科毕业设计（论文）”。";
      case "HEADER-01-SCOPE" -> "封面不要求页眉；修复文档分节结构后重新检测，以确认摘要至附录各节的页眉设置。";
      case "HEADER-01-FONT" -> "将页眉字体统一改为黑体。";
      case "HEADER-01-SIZE" -> "将页眉字号统一改为五号。";
      case "HEADER-01-ALIGNMENT" -> "将页眉段落设为居中。";
      case "COVER-01-FONT" -> "学校名称和论文类型改为方正小标宋简体。";
      case "COVER-01-SIZE" -> "学校名称和论文类型字号改为小初。";
      case "COVER-01-ALIGNMENT" -> "将学校名称和论文类型设为居中。";
      case "COVER-02-FONT" -> "论文题目改为黑体。";
      case "COVER-02-SIZE" -> "论文题目字号改为小二。";
      case "COVER-03-FONT", "COVER-03-SIZE", "COVER-03-BOLD" -> "姓名、学号、院系、专业年级和指导教师统一改为仿宋、三号并加粗。";
      case "COVER-03-LABEL" -> "封面字段名称统一使用“学生姓名”“学号”“院系”“专业年级”“指导教师”。";
      case "COVER-04-FONT", "COVER-04-SIZE", "COVER-04-BOLD" -> "封面日期改为仿宋、三号并加粗。";
      case "COVER-04-CONSISTENCY" -> "统一封面上的日期，保持年月日内容一致。";
      case "ZH-01-SPACING" -> "将“摘”和“要”之间留四个空格，标题使用三号黑体并居中。";
      case "ZH-02-BLANK", "EN-01-BLANK" -> "标题后空一行，再开始摘要正文。";
      case "ZH-04-BLANK", "EN-03-BLANK" -> "摘要正文与关键词之间空一行。";
      case "EN-01-FONT" -> "英文摘要标题改为 Times New Roman。";
      case "EN-01-SIZE" -> "英文摘要标题改为三号。";
      case "EN-01-BOLD" -> "英文摘要标题加粗。";
      case "EN-01-TEXT" -> "将英文摘要标题改为全大写“ABSTRACT”。";
      case "EN-03-BOLD" -> "将“Keywords”标签（含冒号）加粗，冒号后的空格和关键词正文保持常规字重。";
      case "EN-03-FORMAT" -> "ENGLISH_KEYWORD_COUNT_MISMATCH".equals(result.reasonCode())
          ? "英文关键词控制在 3 至 5 个，使用分号分隔。"
          : "英文关键词之间用分号分隔，标签与内容之间使用冒号。";
      case "EN-TOC-01-BOLD" -> "将英文目录标题加粗。";
      case "EN-TOC-01-ALIGNMENT" -> "将英文目录标题段落设为居中。";
      case "EN-TOC-01-FONT" -> "将英文目录标题字体改为 Times New Roman。";
      case "EN-TOC-01-SIZE" -> "将英文目录标题字号改为三号。";
      case "EN-TOC-01-LINE" -> "将英文目录标题行距改为 1.5 倍。";
      case "ABSTRACT-FONT" -> "摘要正文中文用宋体，字母和数字用 Times New Roman。";
      case "ABSTRACT-SIZE" -> "摘要正文改为小四号。";
      case "ABSTRACT-LINE" -> "摘要正文行距改为 1.5 倍。";
      case "ABSTRACT-KEYWORD-BOLD" -> "只将“关键词”标签加粗，后面的关键词保持常规字重。";
      case "ABSTRACT-KEYWORD-FORMAT" -> "关键词改为 3 至 5 个，用中文冒号引出、中文分号分隔，末尾不加标点。";
      case "HEAD-05-MARKER" -> "三级以下标题编号应写成“（1）标题”，右括号后不要加顿号。";
      case "HEAD-01-SPACING" -> "一级标题序数后留两个空格，再写标题。";
      case "HEAD-01-ALIGNMENT" -> "将一级标题段落设为居中。";
      case "HEAD-02-SPACING", "HEAD-03-SPACING" -> "标题序数后留一个空格，再写标题。";
      case "HEAD-02-BLANK" -> "章标题与正文之间空一行。";
      case "HEAD-03-INDENT" -> "该三级标题的首行缩进应为 2 字符；请在 Word 的“段落”设置中把“特殊格式”设为“首行”，并将首行缩进设为 2 字符。";
      case "HEAD-05-INDENT" -> {
        String actual = result.actual() == null ? "" : result.actual();
        if (actual.contains("存在额外的左缩进"))
          yield "该编号标题还设置了多余的整体左缩进、右缩进或悬挂缩进；请在 Word 的“段落”设置中清除多余缩进，只保留首行缩进设为 2 字符，不要在编号前输入空格代替。";
        if ("0".equals(actual) || actual.startsWith("0（"))
          yield "该编号标题没有设置首行缩进；请在 Word 的“段落”设置中把“特殊格式”设为“首行”，并将首行缩进设为 2 字符，不要在编号前输入空格代替。";
        if (actual.matches("\\d+ 个文字空白"))
          yield "该编号标题前多了空格；请删除编号前的空格，并将首行缩进设为 2 字符。";
        yield "该编号标题的首行缩进与规范要求的 2 字符不一致；请在 Word 的“段落”设置中把“特殊格式”设为“首行”，并将首行缩进设为 2 字符。";
      }
      case "HEAD-05-FONT" -> "三级以下标题中文用宋体，字母和数字用 Times New Roman。";
      case "HEAD-05-SIZE" -> "三级以下标题改为小四号。";
      case "HEAD-05-LINE" -> "三级以下标题行距改为 1.5 倍。";
      case "CITE-01" -> "将引用编号设为小四号 Times New Roman，并设置为上标。";
      case "BODY-FONT" -> "正文中文改为宋体，字母和数字改为 Times New Roman。";
      case "BODY-SIZE" -> "将正文统一调整为学校规范要求的字号。";
      case "BODY-INDENT", "ABSTRACT-INDENT" -> indentSuggestion(result);
      case "BODY-LINE" -> "将正文段落行距调整为 1.5 倍。";
      case "BODY-SCOPE" -> "确认论文包含清晰的正文一级章节标题后重新检查，避免正文被识别为摘要或目录内容。";
      case "ABSTRACT-KEYWORD-ALIGN" -> "将中文关键词段落设为两端对齐。";
      case "EN-03-ALIGNMENT" -> "英文关键词换行时，将新行起始位置对齐到第一个关键词的首字母。";
      case "TOC-SPACING" -> tocSpacingSuggestion(result);
      case "TOC-ENTRY-FONT" -> "目录条目中文使用宋体，字母和数字使用 Times New Roman。";
      case "TOC-ENTRY-SIZE" -> "目录条目统一改为小四号。";
      case "TOC-ENTRY-LINE" -> "目录条目行距改为 1.5 倍。";
      case "TOC-ENTRY-INDENT" -> tocIndentSuggestion(result);
      case "TOC-ENTRY-PAGE" -> "在目录条目末尾补充实际页码，只填写阿拉伯数字，并用制表位或目录页码字段对齐。";
      case "TOC-02" -> switch (result.reasonCode()) {
        case "TOC_ENTRY_NOT_FOUND_IN_BODY" -> "请在正文中补上与该目录条目完全对应的标题，或删除目录中的多余条目。";
        case "BODY_HEADING_NOT_IN_TOC" -> "请在目录中补上该正文标题对应的条目，并核对页码。";
        default -> "核对目录条目和正文标题，确保每一项都能一一对应。";
      };
      case "TOC-02-ORDER" -> "按正文标题出现的先后重新生成目录，并更新目录页码。";
      case "TOC-REQUIRED-ENTRY" -> switch (result.reasonCode()) {
        case "TOC_REQUIRED_ENTRY_PAGE_MISSING" -> "在该目录条目末尾补充对应的阿拉伯数字页码。";
        case "TOC_REQUIRED_APPENDIX_MISSING" -> "论文已有附录，请在目录中列出对应的附录标题，并填写页码。";
        default -> "在目录中补齐正文各章、参考文献和致谢条目，并填写对应页码。";
      };
      case "HEADING-FONT" -> headingFontSuggestion(result);
      case "HEADING-SIZE" -> "按标题层级调整字号：一级标题三号、二级标题四号、三级标题小四号。";
      case "HEADING-LINE" -> "该标题当前为" + lineSpacingValue(result.actual())
          + "。选中标题整段，在 Word 的“段落”设置中，将“行距”改为“1.5 倍行距”。";
      case "TOC-01-SPACING" -> "将“目”和“录”之间留四个空格，标题使用三号黑体并居中。";
      case "TOC-01-BLANK" -> "目录标题后空一行，再开始目录内容。";
      case "TOC-01-LINE" -> "目录标题行距改为 1.5 倍。";
      case "TAB-02" -> switch (result.reasonCode() == null ? "" : result.reasonCode()) {
        case "TABLE_BORDER_INVALID", "TABLE_CONTINUATION_BORDER_INVALID" -> tableBorderSuggestion(result);
        default -> "保留顶线、表头下横线和底线；辅助线按实际需要保留。";
      };
      case "TAB-03" -> switch (result.reasonCode()) {
        case "TABLE_CONTINUATION_NUMBER_FORMAT" -> "删去续表编号后的文字，仅保留“续表”加编号，例如“续表4-1”。";
        case "TABLE_CONTINUATION_CAPTION_NOT_RIGHT" -> "将续表标记放在表格右上方并设为右对齐。";
        case "TABLE_CONTINUATION_CAPTION_ALIGNMENT_UNKNOWN" -> "将续表标记放在表格右上方并设为右对齐。";
        case "TABLE_CAPTION_MISSING" -> "在表格上方补充表号和表题。";
        case "TABLE_CAPTION_BELOW" -> "把表号和表题移到表格上方。";
        case "TABLE_CAPTION_NUMBER_FORMAT" -> "表号和表题之间留一个空格。";
        case "TABLE_CAPTION_NOT_CENTERED" -> "将表号和表题段落设为居中。";
        default -> "把表号和表题放到表格上方居中，二者之间只留一个空格。";
      };
      case "TAB-01" -> "在正文相关位置补充对该表的引用。";
      case "TAB-04" -> "TABLE_UNIT_DIMENSION_UNKNOWN".equals(result.reasonCode())
          ? "将表头改为明确的量名称，并标注对应单位，例如“长度/mm”或“温度/℃”。"
          : "核对“" + result.actual() + "”列的单位，可在对应数值后或该列表头标注；不同单位分别标注，相同单位可以统一注明。";
      case "TAB-05" -> "表号按所在章从 1 开始连续编号。";
      case "TAB-06" -> "删除“同前”“同左”等占位文字，重复写出完整内容。";
      case "TAB-07" -> "将表注放在表格底线下方并左对齐。";
      case "TAB-08" -> switch (result.reasonCode()) {
        case "TABLE_CONTINUATION_ORIGINAL_MISSING" -> "核对续表编号，并在此前补齐对应原表。";
        case "TABLE_CONTINUATION_HEADER_UNREADABLE" -> "将原表整理为可读取的表格行，再核对续表是否重复完整表头。";
        default -> "续表重复原表的表头内容，并保持单元格顺序一致。";
      };
      case "CAPTION-FONT" -> "图题和表题中文用宋体，字母和数字用 Times New Roman。";
      case "CAPTION-SIZE" -> "图题和表题统一调整为五号。";
      case "FIG-01" -> "在正文相关位置补充对该图的引用。";
      case "FIG-02" -> "把图题放到图下方并居中，补齐图号和图题。";
      case "FIG-03" -> "图号和图题之间只留一个空格，例如“图3-1 系统结构图”。";
      case "FIG-04" -> "重新插入图片，并设置有效的宽度和高度。";
      case "FIG-05" -> "图号按所在章从 1 开始连续编号。";
      case "EQ-01" -> "EQUATION_NUMBER_PARENTHESES_MISSING".equals(result.reasonCode())
          ? "给公式编号加圆括号，例如“(3-1)”。"
          : "按一级标题重新编号，例如第三章第一个公式写为（3-1）。";
      case "EQ-05" -> "按一级标题重新编号，例如第三章第一个公式写为（3-1）。";
      case "EQ-02" -> "公式号使用 5 号 Times New Roman，并检查公式号位置。";
      case "EQ-03" -> "公式段落设为居中，公式号所在段落设为右对齐。";
      case "EQ-04" -> "在正文相关位置补充对该公式的引用。";
      case "EQ-06" -> "该公式以 MathType、OLE 或图片形式保存，文档 XML 无法读取内部字体、变量斜体、上下标和内部结构；如需系统检查这些内容，请转换为 Word 原生公式。";
      case "EQ-07" -> "在公式下方补充各变量的名称和单位。";
      case "REF-02" -> "按文献类型补充对应标识，如 [M]、[J]、[D] 或 [EB/OL]。";
      case "REF-03" -> switch (result.reasonCode()) {
        case "REFERENCE_NOT_CITED" -> "为这条文献补充正文引用；如不再使用，请删除该条目。";
        case "CITED_REFERENCE_MISSING" -> "为正文中的引用编号补充对应的文末条目。";
        case "CITATION_RANGE_MISMATCH" -> "检查正文中的引用范围，确保每个编号都有对应的文末参考文献。";
        case "REFERENCE_ORDER_MISMATCH" -> "按文献在正文中首次出现的顺序重新排列编号。";
        case "REFERENCE_NUMBER_DUPLICATE" -> "检查并修正重复的参考文献编号。";
        default -> "检查正文引用与文末编号，并按首次引用顺序排列。";
      };
      case "REF-04" -> "按文献类别补齐该条目的必要信息，并统一标点格式。";
      case "REF-05" -> "参考文献补足到至少 10 篇。";
      case "REF-07" -> "参考文献编号从 [1] 开始，按正文首次引用顺序连续排列。";
      case "REF-SIZE" -> "参考文献中文用小四号宋体，字母和数字用 Times New Roman。";
      case "REF-FONT" -> "参考文献中文用宋体，字母和数字用 Times New Roman。";
      case "REF-LINE" -> "将参考文献段落行距调整为 1.5 倍。";
      case "APP-01" -> "附录按 A、B、C 顺序编号，并在附录图、表、公式编号前加对应字母。";
      case "APP-02-SPACING" -> "附录标题按“附    录”书写，中间留四个空格。";
      case "APP-03" -> "将“附    录”总标题设置为从新页开始；没有明确分页证据时先在 Word 中确认实际版面。";
      case "CONCLUSION_CHAPTER_MISMATCH" -> "将结论标题改为第五章，并保持章节编号与目录一致。";
      case "END-01-SPACING" -> "标题写成“致    谢”，两个字之间保留 4 个普通空格。";
      case "REF-01-SPACING" -> "标题写成“参考文献”，两个词之间不要插入空格。";
      case "END-01-BLANK", "APP-02-BLANK", "REF-01-BLANK" -> switch (result.reasonCode()) {
        case "REFERENCE_TITLE_BLANK_LINE_MISSING" -> "在“参考文献”标题与第一条文献之间保留一个空白段落。";
        case "ACKNOWLEDGEMENT_TITLE_BLANK_LINE_MISSING" -> "在“致谢”标题与正文之间保留一个空白段落。";
        case "APPENDIX_TITLE_BLANK_LINE_MISSING" -> "在“附    录”标题与附录内容之间保留一个空白段落。";
        case "REFERENCE_TITLE_BLANK_LINE_COUNT_MISMATCH" -> "“参考文献”标题与第一条文献之间只保留一个空白段落。";
        case "ACKNOWLEDGEMENT_TITLE_BLANK_LINE_COUNT_MISMATCH" -> "“致谢”标题与正文之间只保留一个空白段落。";
        case "APPENDIX_TITLE_BLANK_LINE_COUNT_MISMATCH" -> "“附    录”标题后只保留一个空白段落，再开始附录内容。";
        default -> "标题后空一行，再开始正文。";
      };
      case "ZH-01-FONT", "TOC-01-FONT", "REF-01-FONT", "END-01-FONT", "APP-02-FONT" -> "标题字体改为黑体。";
      case "ZH-01-SIZE", "TOC-01-SIZE", "REF-01-SIZE", "END-01-SIZE", "APP-02-SIZE" -> "标题字号改为三号。";
      case "ZH-01-ALIGNMENT", "EN-01-ALIGNMENT", "TOC-01-ALIGNMENT", "REF-01-ALIGNMENT", "END-01-ALIGNMENT", "APP-02-ALIGNMENT" -> "将标题段落设为居中。";
      default -> unknownSuggestion(result);
    };
  }

  private static String tableBorderSuggestion(CheckResult result) {
    String locations = result.expected().replace("顶线", "首行的上边框")
        .replace("表头下横线", "表头行的下边框")
        .replace("底线", "末行的下边框");
    return "请在 Word 中为" + locations + "补上横线。";
  }

  private static String indentSuggestion(CheckResult result) {
    String section = "ABSTRACT-INDENT".equals(result.ruleId()) ? "摘要正文" : "正文";
    String actual = result.actual() == null ? "" : result.actual();
    java.util.regex.Matcher count = java.util.regex.Pattern
        .compile("文字前有 (\\d+) 个文字空白").matcher(actual);
    if (count.find()) {
      String quantity = count.group(1);
      if ("INDENT_TEXT_WHITESPACE".equals(result.reasonCode()))
        return section + "文字开头有 " + quantity + " 个空白字符，请删除该空白，保留段落首行缩进为 2 字符。";
      String measuredIndent = indentMeasurement(actual, result.expected());
      return section + "段首有 " + quantity + " 个空白，" + measuredIndent
          + "请删掉这些空白，并在段落设置中将首行缩进调整为 2 字符。";
    }
    java.util.regex.Matcher overallLeft = java.util.regex.Pattern
        .compile("整体左缩进 (\\d+) twips").matcher(actual);
    if (overallLeft.find()) {
      int twips = Integer.parseInt(overallLeft.group(1));
      return section + "没有设置首行缩进；当前设置的是整体左缩进约为 "
          + twipsToCentimeters(twips) + " 厘米，不能替代首行缩进。请在 Word 的“段落”设置中把“特殊格式”设为“首行”，并将首行缩进设为 2 字符。";
    }
    java.util.regex.Matcher overallLeftChars = java.util.regex.Pattern
        .compile("整体左缩进 (\\d+) chars").matcher(actual);
    if (overallLeftChars.find()) {
      int hundredthsOfCharacter = Integer.parseInt(overallLeftChars.group(1));
      return section + "没有设置首行缩进；当前设置的是整体左缩进 "
          + (hundredthsOfCharacter / 100.0) + " 字符，不能替代首行缩进。请在 Word 的“段落”设置中把“特殊格式”设为“首行”，并将首行缩进设为 2 字符。";
    }
    String measuredIndent = indentMeasurement(actual, result.expected());
    return section + measuredIndent
        + "请在 Word 的“段落”设置中将“特殊格式”设为“首行”，缩进量设为 2 字符。";
  }

  private static String tocSpacingSuggestion(CheckResult result) {
    String actual = result.actual() == null ? "" : result.actual();
    if ("章节序号内部不夹空格".equals(result.expected())) {
      String corrected = actual.replaceAll("[\\p{javaWhitespace}\\p{Z}\\u200B-\\u200D\\u2060\\uFEFF]+", "");
      String separatorAdvice = corrected.startsWith("第")
          ? "编号与标题文字之间保留 2 个普通半角空格。"
          : "编号与标题文字之间保留 1 个普通半角空格。";
      return "目录编号写成了“" + actual + "”，请删除编号内部的空格，改为“" + corrected + "”。" + separatorAdvice;
    }
    int expectedCount = "编号后空 2 格".equals(result.expected()) ? 2 : 1;
    String requirement = expectedCount + " 个普通半角空格";
    if (actual.isBlank() || actual.contains("没有空格"))
      return "目录编号后没有空格，请在编号与标题文字之间补 " + expectedCount + " 个普通半角空格。";
    if (actual.matches("普通空格(?:、普通空格)*")) {
      int count = actual.split("、").length;
      if (count > expectedCount)
        return "目录编号后有 " + count + " 个普通空格，请删除 " + (count - expectedCount)
            + " 个，只保留 " + requirement + "。";
      if (count < expectedCount)
        return "目录编号后只有 " + count + " 个普通空格，请再补 " + (expectedCount - count)
            + " 个，使编号与标题文字之间共有 " + requirement + "。";
    }
    String detail = actual.contains("制表符") ? "用了制表符，间隔会随位置变化"
        : actual.contains("全角空格") ? "用了全角空格"
        : actual.contains("零宽") || actual.contains("不可见") ? "夹有看不见的空白字符"
        : "用了特殊空白，外观看起来相近但不是要求的普通空格";
    return "目录编号后" + detail + "。请选中编号与标题文字之间的全部间隔，删除后切换到英文输入，按空格键 "
        + expectedCount + " 次，保留 " + requirement + "。";
  }

  private static String tocIndentSuggestion(CheckResult result) {
    String actual = result.actual() == null ? "" : result.actual();
    String detail = actual.contains("空白字符") ? "条目开头有空白字符，请删除这些字符。"
        : actual.startsWith("存在额外的") ? "条目设置了额外的右侧或悬挂缩进，请清除这些设置。"
        : actual.contains("无法读取") ? "未能读取到有效的目录左侧缩进设置。"
        : "";
    return detail + "一级目录条目应顶格，左侧缩进为 0 字符。请选中该条目，在段落设置中将“左侧缩进”设为 0 字符；不要用行首空格代替。目录不要求首行缩进。";
  }

  private static String indentMeasurement(String actual, String expected) {
    Integer actualTwips = indentTwips(actual);
    Integer expectedTwips = indentTwips(expected);
    if (actualTwips != null && expectedTwips != null) {
      return "首行缩进约为 " + twipsToCentimeters(actualTwips) + " 厘米，规范要求 2 字符（约 "
          + twipsToCentimeters(expectedTwips) + " 厘米）；";
    }
    java.util.regex.Matcher actualChars = java.util.regex.Pattern.compile("(-?\\d+) chars").matcher(actual);
    if (actualChars.find()) {
      try {
        java.math.BigDecimal chars = new java.math.BigDecimal(actualChars.group(1))
            .divide(new java.math.BigDecimal("100"));
        return "首行缩进为 " + chars.stripTrailingZeros().toPlainString()
            + " 字符，规范要求 2 字符；";
      } catch (NumberFormatException ignored) {
        return "首行缩进未达到规范要求的 2 字符；";
      }
    }
    if ("0".equals(actual) || "未设置首行缩进".equals(actual))
      return "本段没有设置首行缩进，规范要求 2 字符；";
    return "首行缩进未达到规范要求的 2 字符；";
  }

  private static Integer indentTwips(String value) {
    java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(-?\\d+) twips").matcher(value);
    if (!matcher.find()) return null;
    try {
      return Integer.valueOf(matcher.group(1));
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private static String twipsToCentimeters(int twips) {
    return new java.math.BigDecimal(twips).multiply(new java.math.BigDecimal("2.54"))
        .divide(new java.math.BigDecimal("1440"), 2, java.math.RoundingMode.HALF_UP)
        .toPlainString();
  }

  private static String missingSectionSuggestion(CheckResult result) {
    String section = result.expected() == null ? "该章节" : result.expected().strip();
    if ("结论".equals(section) || "结论与展望".equals(section))
      return "未找到“结论”章节，请补齐第五章“结论”并与目录保持一致后重新检测。";
    if (section.isBlank()) return "未找到学校要求的章节，请补齐对应标题和内容后重新检测。";
    return "未找到“" + section + "”章节，请补齐该章节标题和内容后重新检测。";
  }

  private static String unknownSuggestion(CheckResult result) {
    String code = result.reasonCode() == null ? "" : result.reasonCode();
    if ("INDENT_EVIDENCE_INVALID".equals(code))
      return "在段落设置中清除冲突或异常的缩进值，再按规范重新设置首行缩进。";
    if ("LINE_SPACING_INVALID".equals(code))
      return "在段落设置中重新选择 1.5 倍行距，清除无法识别的自定义数值。";
    if ("MARGIN_INVALID".equals(code))
      return "在页面设置中重新将上、下、左、右边距分别设为 2.6 cm。";
    return switch (result.reasonCode() == null ? "" : result.reasonCode()) {
      case "ALIGNMENT_UNKNOWN", "TABLE_CAPTION_ALIGNMENT_UNKNOWN" -> "在 Word 中显式设置该段落的对齐方式后重新检查。";
      case "FONT_UNKNOWN" -> "在 Word 中显式设置中文和西文字体后重新检查。";
      case "SIZE_UNKNOWN" -> "在 Word 中显式设置字号后重新检查。";
      case "INDENT_UNKNOWN" -> "在 Word 中显式设置首行缩进后重新检查。";
      case "LINE_SPACING_UNKNOWN" -> "在 Word 中显式设置行距后重新检查。";
      case "MARGIN_UNKNOWN" -> "在页面设置中显式设置上下左右边距为 2.6 cm。";
      case "MARGIN_GUTTER_UNKNOWN", "MARGIN_GUTTER_MISMATCH" -> "将页面设置中的装订线设为 0。";
      case "PAGE_NUMBER_ALIGNMENT_UNKNOWN" -> "将页码段落显式设置为页脚居中。";
      case "PAGE_NUMBER_FONT_UNKNOWN" -> "将页码显式设置为 Times New Roman。";
      case "PAGE_NUMBER_SIZE_UNKNOWN" -> "将页码显式设置为五号。";
      case "HEADER_FONT_UNKNOWN", "HEADER_SIZE_UNKNOWN", "HEADER_ALIGNMENT_UNKNOWN" -> "在 Word 中将页眉显式设置为黑体、五号并居中。";
      case "CHAPTER_PAGE_BREAK_REVIEW" -> "Word XML 无法确认是否另起一页，请先在 Word 中查看章节起始页；只有确实未另起一页时，再给章节标题设置段前分页。";
      case "APPENDIX_PAGE_BREAK_REVIEW" -> "请先在 Word 中确认“附    录”是否从新页开始；只有确认未另起一页时，再设置段前分页。";
      case "BODY_REGION_UNDETERMINED" -> "请确认论文有清晰的正文一级章节标题后重新检查，系统才能准确区分正文与前置内容。";
      case "CONCLUSION_CHAPTER_UNCONFIRMED" -> "请在 Word 中确认结论标题属于第五章，并保留明确的章节编号。";
      case "CITATION_RANGE_REVIEW" -> "逐个核对引用编号与文末条目，并按首次出现顺序排列。";
      case "REQUIRED_SECTION_MISSING" -> missingSectionSuggestion(result);
      case "APPENDIX_TITLE_MISSING" -> "在附录A等分项之前补充独立的“附    录”总标题，并在标题后保留一空行。";
      case "APPENDIX_SECTION_MISSING" -> "在已有的“附    录”总标题后写明“附录A”等分项标题及内容，后续附录按 B、C 顺序编号。";
      case "THESIS_DOCUMENT_CLASSIFICATION_REVIEW" -> "确认上传的是完整毕业论文，并检查摘要、目录和正文标题等结构是否完整后重新检测。";
      case "COVER_MISSING", "COVER_FIELD_MISSING" -> "补齐封面学校名称、论文类型、个人信息、题目和日期等规范字段后重新检查。";
      case "EQUATION_ALIGNMENT_UNKNOWN" -> "文件中没有读到公式或编号的明确对齐设置，暂时不能确认是否符合要求；可在 Word 中查看公式是否居中、编号是否右对齐。";
      case "EQUATION_VARIABLES_UNREADABLE" -> "文件无法读取公式内部的变量结构，暂时不能确认变量说明是否齐全；请在 Word 中查看公式，并核对每个变量是否都有说明。";
      case "EQUATION_NUMBER_FORMAT_UNKNOWN" -> "系统没有读到公式编号的完整字体或字号设置，暂时不能判定对错；请在 Word 中把编号设为五号 Times New Roman 后重新检查。";
      case "EQUATION_OBJECT_UNSUPPORTED" -> "当前对象无法确认内部字体、变量斜体、上下标和内部结构；如需自动检查这些内容，请转换为 Word 原生公式（OMML）后重新检查。";
      case "FIGURE_LAYOUT_REVIEW" -> "请在 Word 中确认图题是否位于图片下方并居中；若位置不对，再将图题移到图片下方并居中。";
      case "FIGURE_NUMBER_UNKNOWN" -> "按一级标题重新编排图号并显式写入图题。";
      case "FIGURE_REFERENCE_NEEDS_REVIEW" -> "请在 Word 中检查正文是否出现该图号的引用；只有确认缺少时再补充引用。";
      case "TABLE_LAYOUT_REVIEW" -> "请在 Word 中确认表题是否位于表格上方并居中；只有确认位置不对时再调整。";
      case "TABLE_BORDER_REVIEW", "TABLE_CONTINUATION_BORDER_REVIEW", "TABLE_AUXILIARY_BORDER_REVIEW" -> "显式设置三线表边框，删除不必要的竖线。";
      case "TABLE_BORDER_INVALID", "TABLE_CONTINUATION_BORDER_INVALID" -> tableBorderSuggestion(result);
      case "TABLE_BORDER_EVIDENCE_UNRESOLVED" -> "请在 Word 中确认表格是否为三线表；只有确认边框缺失或错误时再调整为三线表。";
      case "TABLE_XML_NUMERIC_INVALID" -> "在 Word 中重新设置表格列宽、行高、单元格合并和边框数值，删除非法或损坏的表格属性后重新检查。";
      case "TABLE_UNIT_DIMENSION_UNKNOWN" -> "将表头改为明确的量名称，并标注对应单位，例如“长度/mm”或“温度/℃”。";
      case "KEYWORD_ALIGNMENT_UNKNOWN" -> "将关键词段落显式设置为两端对齐。";
      case "ENGLISH_KEYWORD_WRAP_ALIGNMENT_UNKNOWN" -> "请在 Word 中确认英文关键词换行位置；将新行对齐到第一个关键词的首字母。";
      case "ENGLISH_KEYWORD_WRAP_ALIGNMENT_MISMATCH" -> "将英文关键词换行后的新行起始位置移到第一个关键词的首字母处。";
      case "KEYWORD_LABEL_UNREADABLE" -> "只将“关键词”或“Keywords”标签单独设为加粗。";
      case "COVER_TITLE_LABEL_AND_VALUE_SHARE_RUN", "COVER_FIELD_LABEL_AND_VALUE_SHARE_RUN" -> "将封面标签和实际内容拆分为独立文本节点，并分别设置规范字体、字号和字重。";
      case "COVER_FIELD_LABEL_MISMATCH" -> "按规范使用“学生姓名”“学号”“院系”“专业年级”“指导教师”字段名，并在字段名后使用全角冒号。";
      case "COVER_FIELD_VALUE_UNKNOWN" -> "将封面字段标签和字段内容分开输入后重新检查。";
      default -> result.expected() == null || result.expected().isBlank()
          ? "按照学校论文规范修正此项格式。"
          : "将此项调整为“" + displayValue(result.expected(), result.ruleId()) + "”。";
    };
  }

  private static String headingFontSuggestion(CheckResult result) {
    return headingLevel(result.expected()) + "：中文改为黑体，字母和数字改为 Times New Roman。";
  }

  private static String headingLevel(String expected) {
    if (expected != null) {
      for (String level : new String[]{"一级标题", "二级标题", "三级标题"}) {
        if (expected.startsWith(level)) return level;
      }
    }
    return "标题";
  }

  public static String expectedValue(CheckResult result) {
    if ("TOC-ENTRY-INDENT".equals(result.ruleId())) return tocIndentValue(result.expected());
    return switch (result.reasonCode() == null ? "" : result.reasonCode()) {
      case "MARGIN_GUTTER_MISMATCH", "MARGIN_GUTTER_UNKNOWN" -> "装订线 0 twips";
      case "PAGE_NUMBER_FORMAT_MISMATCH" -> "阿拉伯数字页码";
      case "PAGE_NUMBER_START_MISMATCH" -> "1";
      case "PAGE_NUMBER_START_INVALID" -> "有效的页码起始整数";
      case "PAGE_NUMBER_SEQUENCE_RESTARTED" -> "正文到附录连续编排";
      case "PAGE_NUMBER_IN_FRONT_MATTER" -> "封面、摘要和目录不显示页码";
      case "PAGE_NUMBER_MISSING_IN_MAIN_BODY" -> "正文和附录每节页脚均有连续页码";
      default -> isIndentRule(result.ruleId()) ? indentValue(result.expected(), result.expected())
          : displayValue(result.expected(), result.ruleId());
    };
  }

  public static String actualValue(CheckResult result) {
    if ("TOC-ENTRY-INDENT".equals(result.ruleId())) return tocIndentValue(result.actual());
    return switch (result.reasonCode() == null ? "" : result.reasonCode()) {
      case "MARGIN_MISMATCH" -> preciseMarginValue(result.actual());
      case "LINE_SPACING_MISMATCH" -> preciseLineSpacingValue(result.actual());
      case "MARGIN_GUTTER_MISMATCH" -> result.actual() + " twips";
      case "MARGIN_GUTTER_UNKNOWN" -> "未读取到装订线值";
      case "PAGE_NUMBER_FORMAT_MISMATCH" -> pageNumberFormat(result.actual());
      case "PAGE_NUMBER_START_INVALID" -> "XML 中的页码起始值无效";
      case "PAGE_NUMBER_SEQUENCE_RESTARTED" -> restartedPageNumber(result.actual());
      case "PAGE_NUMBER_IN_FRONT_MATTER" -> "封面、摘要或目录中存在页码域";
      case "PAGE_NUMBER_MISSING_IN_MAIN_BODY" -> "此节页脚未检测到适用的页码域";
      default -> isIndentRule(result.ruleId()) ? indentValue(result.expected(), result.actual())
          : displayValue(result.actual(), result.ruleId());
    };
  }

  private static String tocIndentValue(String value) {
    if (value == null || value.isBlank()) return "未读取到左侧缩进";
    var characters = java.util.regex.Pattern
        .compile("^leftChars=(-?\\d+)(?: / left=-?\\d+ twips)?$").matcher(value.strip());
    if (characters.matches()) {
      String count = new java.math.BigDecimal(characters.group(1)).movePointLeft(2)
          .stripTrailingZeros().toPlainString();
      return "左侧缩进 " + count + " 字符";
    }
    var distance = java.util.regex.Pattern.compile("^left=(-?\\d+) twips$").matcher(value.strip());
    if (distance.matches()) {
      String centimeters = new java.math.BigDecimal(distance.group(1))
          .multiply(new java.math.BigDecimal("2.54"))
          .divide(new java.math.BigDecimal("1440"), 5, java.math.RoundingMode.HALF_UP)
          .stripTrailingZeros().toPlainString();
      return "左侧缩进 " + centimeters + " 厘米";
    }
    return displayValue(value, "TOC-ENTRY-INDENT");
  }

  private static String restartedPageNumber(String actual) {
    if (actual == null || actual.isBlank()) return "此分节重新开始编号";
    String value = actual.strip();
    if (value.matches("-?\\d+")) return "此分节从 " + value + " 重新编号";
    if (value.equalsIgnoreCase("restarted at 1")) return "此分节从 1 重新编号";
    return "此分节重新开始编号（" + value + "）";
  }

  private static boolean isIndentRule(String ruleId) {
    return ruleId != null && ruleId.endsWith("-INDENT");
  }

  private static String indentValue(String expected, String value) {
    if (value == null || value.isBlank()) return "未读取到";
    try {
      java.util.regex.Matcher expectedTwips = java.util.regex.Pattern
          .compile("^(-?\\d+) twips(?:\\s*/.*)?$").matcher(expected == null ? "" : expected.strip());
      java.util.regex.Matcher valueTwips = java.util.regex.Pattern
          .compile("^(-?\\d+) twips(?:\\s*/.*)?$").matcher(value.strip());
      if (expectedTwips.matches() && valueTwips.matches()) {
        String required = expectedTwips.group(1);
        String actual = valueTwips.group(1);
        return required.equals(actual)
            ? "2\u4e2a\u5b57\u7b26\uff08" + required + " twips\uff09"
            : actual + " twips\uff08\u8981\u6c42 " + required
                + " twips\uff0c\u5373 2\u4e2a\u5b57\u7b26\uff09";
      }
      java.math.BigDecimal numeric = new java.math.BigDecimal(value.strip());
      if ("200".equals(expected)) {
        return numeric.divide(new java.math.BigDecimal("100"), 2, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros().toPlainString() + " 字符";
      }
      if ("480".equals(expected)) {
        return numeric.stripTrailingZeros().toPlainString() + " 缇（约 2 字符）";
      }
    } catch (NumberFormatException ignored) {
      // 保留非数字诊断文本的通用展示路径。
    }
    return displayValue(value, "");
  }

  private static String displayValue(String value, String ruleId) {
    if (value == null || value.isBlank()) return "未读取到";
    String normalized = value.strip();
    if ("all sides".equalsIgnoreCase(normalized)) return "四边均符合要求";
    if ("EQ-02".equals(ruleId) && normalized.matches(".+ / \\d+")) {
      int separator = normalized.lastIndexOf(" / ");
      return normalized.substring(0, separator) + "，" + fontSizeValue(new java.math.BigInteger(normalized.substring(separator + 3)));
    }
    if ("EQ-03".equals(ruleId) && normalized.contains(" / ")) {
      String[] alignments = normalized.split(" / ", 2);
      return "公式：" + displayValue(alignments[0], null) + "；编号：" + displayValue(alignments[1], null);
    }
    if ("PAGE-01".equals(ruleId)) return marginValue(normalized);
    if (normalized.matches("\\d+") && isSizeRule(ruleId))
      return fontSizeValue(new java.math.BigInteger(normalized));
    if (normalized.matches("\\d+ / (?:auto|exact|atLeast)")) return lineSpacingValue(normalized);
    if (normalized.matches("-?\\d+ twips")) return normalized.replaceFirst("(?i) twips$", " 缇");
    if (normalized.regionMatches(true, 0, "body reference to table ", 0, "body reference to table ".length()))
      return "正文引用表号 " + normalized.substring("body reference to table ".length()).strip();
    if (normalized.regionMatches(true, 0, "body reference to equation ", 0, "body reference to equation ".length()))
      return "正文引用公式（" + normalized.substring("body reference to equation ".length()).strip() + "）";
    var appendixEquation = java.util.regex.Pattern.compile("^appendix ([A-Z]) reference to equation (.+)$",
        java.util.regex.Pattern.CASE_INSENSITIVE).matcher(normalized);
    if (appendixEquation.matches()) return "附录" + appendixEquation.group(1).toUpperCase(java.util.Locale.ROOT)
        + "引用公式（" + appendixEquation.group(2).strip() + "）";
    return switch (normalized.toLowerCase(java.util.Locale.ROOT)) {
      case "center", "centered" -> "居中";
      case "not centered" -> "未居中";
      case "left" -> "左对齐";
      case "right" -> "右对齐";
      case "both", "justified" -> "两端对齐";
      case "caption above table" -> "表题应位于表格上方";
      case "body reference to figure" -> "正文引用图号";
      case "matching reference entry" -> "正文引用对应的文末条目";
      case "cited in body" -> "正文中有引用";
      case "unique" -> "编号唯一";
      case "caption below and centered" -> "图题位于图下并居中";
      case "centered caption" -> "图题应居中";
      case "caption centered=false" -> "图题未居中";
      case "verified from docx xml" -> "结构检查符合要求";
      case "matched" -> "符合要求";
      case "format mismatch" -> "字体、字号或上标格式不符合要求";
      case "no page number before main body" -> "正文前不应显示页码";
      case "chapter starts on new page" -> "每章另起一页";
      case "three-line table" -> "三线表边框";
      case "decimal" -> "阿拉伯数字";
      case "upperroman" -> "大写罗马数字";
      case "lowerroman" -> "小写罗马数字";
      case "upperletter" -> "大写字母";
      case "lowerletter" -> "小写字母";
      case "continuous numbering" -> "连续编排";
      case "restarted at 1" -> "从 1 重新编号";
      case "page field is used" -> "存在页码域";
      case "true" -> "是";
      case "false" -> "否";
      default -> normalized;
    };
  }

  private static boolean isSizeRule(String ruleId) {
    return ruleId != null && (ruleId.endsWith("-SIZE") || "PAGE-05".equals(ruleId)
        || "EQ-02".equals(ruleId));
  }

  private static String fontSizeValue(java.math.BigInteger halfPoints) {
    String name = null;
    if (halfPoints.bitLength() < 31) {
      name = switch (halfPoints.intValue()) {
        case 72 -> "小初";
        case 36 -> "小二";
        case 32 -> "三号";
        case 28 -> "四号";
        case 24 -> "小四";
        case 21 -> "五号";
        default -> null;
      };
    }
    String points = halfPoints.remainder(java.math.BigInteger.TWO).signum() == 0
        ? halfPoints.divide(java.math.BigInteger.TWO).toString()
        : halfPoints.divide(java.math.BigInteger.TWO) + ".5";
    return name == null ? points + " 磅" : name + "（" + points + " 磅）";
  }

  private static String marginValue(String twips) {
    try {
      java.math.BigDecimal centimeters = new java.math.BigDecimal(twips)
          .multiply(new java.math.BigDecimal("2.54"))
          .divide(new java.math.BigDecimal("1440"), 2, java.math.RoundingMode.HALF_UP);
      return centimeters.stripTrailingZeros().toPlainString() + " cm";
    } catch (NumberFormatException ignored) {
      return twips;
    }
  }

  private static String preciseMarginValue(String twips) {
    try {
      java.math.BigDecimal centimeters = new java.math.BigDecimal(twips)
          .multiply(new java.math.BigDecimal("2.54"))
          .divide(new java.math.BigDecimal("1440"), 5, java.math.RoundingMode.HALF_UP);
      return centimeters.stripTrailingZeros().toPlainString() + " cm (" + twips + " twips)";
    } catch (NumberFormatException ignored) {
      return twips;
    }
  }

  private static String lineSpacingValue(String value) {
    return formatLineSpacing(value, false);
  }

  private static String preciseLineSpacingValue(String value) {
    return formatLineSpacing(value, true);
  }

  private static String formatLineSpacing(String value, boolean includeXmlValue) {
    if (value == null) return "未读取到";
    String[] parts = value.split(" / ", 2);
    if (parts.length != 2) return value;
    try {
      java.math.BigInteger line = new java.math.BigInteger(parts[0]);
      return switch (parts[1]) {
        case "auto" -> {
          java.math.BigInteger divisor = java.math.BigInteger.valueOf(240);
          java.math.BigInteger gcd = line.abs().gcd(divisor);
          java.math.BigInteger numerator = line.divide(gcd);
          java.math.BigInteger denominator = divisor.divide(gcd);
          String ratio = finiteDecimalRatio(numerator, denominator);
          if (ratio == null) ratio = numerator + "/" + denominator;
          yield ratio + " 倍行距" + xmlEvidence(value, includeXmlValue);
        }
        case "exact" -> exactLineSpacingValue(line, "固定值", value, includeXmlValue);
        case "atLeast" -> exactLineSpacingValue(line, "最小值", value, includeXmlValue);
        default -> value;
      };
    } catch (NumberFormatException error) {
      return value;
    }
  }

  private static String finiteDecimalRatio(java.math.BigInteger numerator, java.math.BigInteger denominator) {
    try {
      return new java.math.BigDecimal(numerator).divide(new java.math.BigDecimal(denominator))
          .stripTrailingZeros().toPlainString();
    } catch (ArithmeticException nonTerminatingDecimal) {
      return null;
    }
  }

  private static String exactLineSpacingValue(java.math.BigInteger twips, String label, String xmlValue,
      boolean includeXmlValue) {
    String points = new java.math.BigDecimal(twips)
        .divide(new java.math.BigDecimal("20"))
        .stripTrailingZeros().toPlainString();
    return label + " " + points + " 磅" + xmlEvidence(xmlValue, includeXmlValue);
  }

  private static String xmlEvidence(String value, boolean includeXmlValue) {
    return includeXmlValue ? "（XML：" + value + "）" : "";
  }

  private static String pageNumberFormat(String format) {
    return switch (format == null ? "" : format.toLowerCase(java.util.Locale.ROOT)) {
      case "decimal" -> "阿拉伯数字";
      case "upperroman" -> "大写罗马数字";
      case "lowerroman" -> "小写罗马数字";
      case "upperletter" -> "大写字母";
      case "lowerletter" -> "小写字母";
      default -> displayValue(format, "PAGE-04");
    };
  }
}
