package cn.luwneshacha.thesis.inspection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The authoritative inventory of checks exposed by the school rule set.
 *
 * <p>The catalog is deliberately separate from the rule implementation.  A
 * rule can therefore be listed without claiming it passed when the current
 * result stream contains no record for it.</p>
 */
public final class RuleCatalog {
  private static final List<RuleSpec> SCHOOL_V1 = List.of(
      specs("封面", "封面", "学校名称、论文类型、题目、个人信息和日期字段的字体、字号、字重及对齐",
          "DOCX 段落和运行属性", true, "封面字段的段前、段后和行距未检查", ""),
      specs("摘要", "中文摘要", "中文摘要标题、正文段落字体字号缩进和行距、关键词格式及空行",
          "DOCX 段落、运行属性和相邻段落", true, ""),
      specs("英文摘要", "英文摘要", "英文摘要标题、正文段落字体字号缩进和行距、关键词格式及空行",
          "DOCX 段落、运行属性和相邻段落", true, ""),
      specs("目录", "目录", "目录标题格式、条目字体字号行距和整段左侧层级缩进（不检查首行缩进）、数字页码、编号空格及标题对应关系和顺序",
          "DOCX 段落文本、相邻段落和正文标题", true,
          "目录条目最终页码显示和 Word 字段更新状态不能仅由静态 XML 证明", ""),
      specs("正文标题", "正文标题", "一级标题居中及一级至三级标题格式、编号空格与三级标题缩进；二、三级标题1.5倍行距；章标题后空行及三级以下标题格式",
          "DOCX 段落样式、编号文本和运行属性", true,
          "章节是否实际另起一页无法仅凭 XML 确认", ""),
      specs("正文格式", "正文", "正文段落字体、字号、首行缩进和行距", "DOCX 段落和运行属性", true, ""),
      specs("必需章节", "论文章节与封面字段", "中文摘要、关键词、英文摘要、目录、正文、参考文献、致谢、附录及封面字段是否齐全",
          "DOCX 段落文本和章节结构线索", true, "缺少章节时只能锚定到现有正文或文档起始位置", ""),
      specs("页面与页码", "页面和分节", "四边页边距、正文页码、分节和章节分页", "sectPr、页脚 PAGE 域和分节证据", true,
          "实际显示的页码连续性、首页/奇偶页设置及链接到前一节后的最终显示不能仅由静态 XML 证明",
          "页码批注只能锚定到可定位的正文段落，无法对空页脚或复杂页脚对象写入范围批注"),
      specs("页眉", "摘要至附录页眉（封面豁免）", "页眉文字、黑体、五号和居中格式，并按分节继承关系检查封面以外各节及启用的首页、偶数页变体",
          "headerReference、header*.xml、段落样式和运行属性", true,
          "页眉最终显示位置和 Word/WPS 字体替换不能仅由静态 XML 证明",
          ""),
      specs("插图", "正文插图", "图号、图题、引用、编号连续性及图题位置", "图片关系、图题段落和正文引用", true,
          "组合图形、文本框题注以及最终图文分页位置不能仅由当前 XML 证明", ""),
      specs("图表题", "图题和表题", "图题和表题字体与字号", "图题、表题段落和运行属性", true,
          "图题和表题的最终分页位置不能仅由静态 XML 证明", ""),
      specs("表格", "正文表格", "表号、表题、三线表、单位、表注、续表和正文引用",
          "表格 XML、表题段落和正文引用", true,
          "单元格字体、字号、对齐、列宽、行高、合并布局和单元格边框未检查；是否实际跨页及最终打印分页不能仅由静态 XML 证明",
          ""),
      specs("公式", "正文公式", "公式编号、编号格式、引用、连续性和变量说明", "OMML/公式对象 XML、公式编号段落和正文引用", true,
          "MathType/OLE 或图片公式的内部字体、变量斜体、上下标和内部结构不能从当前 DOCX XML 完整证明",
          "复杂公式对象没有可用的独立批注锚点"),
      specs("参考文献", "参考文献", "文献编号、类别标识、著录字段、数量、引用及顺序", "参考文献段落、正文引用和文献字段", true, ""),
      specs("致谢", "致谢", "致谢标题（三号黑体、居中、字间四个普通空格）及标题后空行",
          "DOCX 标题段落和相邻段落", true, ""),
      specs("附录", "附录", "附录标题空格、字体、字号、对齐，以及附录和图表公式编号前缀",
          "DOCX 标题和附录对象编号", true,
          "是否单独起页及最终打印分页不能仅由静态 XML 完整证明", ""),
      specs("引用", "正文引用", "正文文献引用位置、字体、字号和上标", "DOCX 文本节点和运行属性", true, ""));

  private static final Map<String, RuleSpec> INDEX = buildIndex();

  private RuleCatalog() {}

  public static List<RuleSpec> schoolV1() { return SCHOOL_V1; }

  /** Returns one row per rule id, including rules that produce no finding in a document. */
  public static List<Entry> entries() {
    var entries = new ArrayList<Entry>();
    for (RuleSpec group : SCHOOL_V1) {
      List<String> ids = ruleIds(group.module());
      if (ids.isEmpty()) {
        throw new IllegalStateException("rule catalog module has no rule ids: " + group.module());
      }
      for (String ruleId : ids) entries.add(new Entry(ruleId, group));
    }
    return List.copyOf(entries);
  }

  public static RuleSpec find(String ruleId) { return INDEX.get(ruleId); }

  private static Map<String, RuleSpec> buildIndex() {
    var index = new LinkedHashMap<String, RuleSpec>();
    for (RuleSpec group : SCHOOL_V1) {
      for (String ruleId : ruleIds(group.module())) {
        if (index.putIfAbsent(ruleId, group) != null) {
          throw new IllegalStateException("duplicate rule id in catalog: " + ruleId);
        }
      }
    }
    return Map.copyOf(index);
  }

  private static List<String> ruleIds(String module) {
    return switch (module) {
      case "封面" -> List.of("COVER-01-FONT", "COVER-01-SIZE", "COVER-01-ALIGNMENT",
          "COVER-02-FONT", "COVER-02-SIZE", "COVER-03-FONT", "COVER-03-SIZE", "COVER-03-BOLD",
          "COVER-03-LABEL", "COVER-04-FONT", "COVER-04-SIZE", "COVER-04-BOLD", "COVER-04-CONSISTENCY", "COVER-05");
      case "摘要" -> List.of("ZH-01-SPACING", "ZH-01-FONT", "ZH-01-SIZE", "ZH-01-ALIGNMENT",
          "ZH-02-BLANK", "ZH-04-BLANK", "ABSTRACT-FONT", "ABSTRACT-SIZE", "ABSTRACT-INDENT", "ABSTRACT-LINE",
          "ABSTRACT-KEYWORD-BOLD", "ABSTRACT-KEYWORD-FORMAT", "ABSTRACT-KEYWORD-ALIGN");
      case "英文摘要" -> List.of("EN-01-TEXT", "EN-01-BOLD", "EN-01-FONT", "EN-01-SIZE", "EN-01-ALIGNMENT",
          "EN-01-BLANK", "EN-03-BOLD", "EN-03-FORMAT", "EN-03-BLANK", "EN-03-ALIGNMENT");
      case "目录" -> List.of("TOC-01-SPACING", "TOC-01-BLANK", "TOC-01-LINE", "TOC-01-FONT",
          "TOC-01-SIZE", "TOC-01-ALIGNMENT", "TOC-SPACING", "TOC-02", "TOC-02-ORDER",
          "TOC-ENTRY-FONT", "TOC-ENTRY-SIZE", "TOC-ENTRY-LINE", "TOC-ENTRY-INDENT", "TOC-ENTRY-PAGE",
          "TOC-REQUIRED-ENTRY",
          "EN-TOC-01-BOLD", "EN-TOC-01-ALIGNMENT", "EN-TOC-01-FONT",
          "EN-TOC-01-SIZE", "EN-TOC-01-LINE");
      case "正文标题" -> List.of("HEAD-01-SPACING", "HEAD-01-ALIGNMENT", "HEAD-02-SPACING", "HEAD-03-SPACING", "HEAD-02-BLANK",
          "HEAD-03-INDENT", "HEAD-05-MARKER", "HEAD-05-FONT", "HEAD-05-SIZE", "HEAD-05-LINE", "HEAD-05-INDENT",
          "HEADING-FONT", "HEADING-SIZE", "HEADING-LINE");
      case "正文格式" -> List.of("BODY-FONT", "BODY-SIZE", "BODY-INDENT", "BODY-LINE", "BODY-SCOPE");
      case "必需章节" -> List.of("SECTION-REQUIRED");
      case "页面与页码" -> List.of("PAGE-01", "PAGE-03", "PAGE-04", "PAGE-05");
      case "页眉" -> List.of("HEADER-01-SCOPE", "HEADER-01-MISSING", "HEADER-01-TEXT", "HEADER-01-FONT", "HEADER-01-SIZE", "HEADER-01-ALIGNMENT");
      case "插图" -> List.of("FIG-01", "FIG-02", "FIG-03", "FIG-05");
      case "图表题" -> List.of("CAPTION-FONT", "CAPTION-SIZE");
      case "表格" -> List.of("TAB-01", "TAB-02", "TAB-03", "TAB-04", "TAB-05", "TAB-06", "TAB-07", "TAB-08");
      case "公式" -> List.of("EQ-01", "EQ-02", "EQ-03", "EQ-04", "EQ-05", "EQ-06", "EQ-07");
      case "参考文献" -> List.of("REF-01-SPACING", "REF-01-BLANK", "REF-01-FONT", "REF-01-SIZE",
          "REF-01-ALIGNMENT",
          "REF-02", "REF-03", "REF-04", "REF-05", "REF-07", "REF-FONT", "REF-LINE", "REF-SIZE");
      case "致谢" -> List.of("END-01-SPACING", "END-01-BLANK", "END-01-FONT", "END-01-SIZE", "END-01-ALIGNMENT");
      case "附录" -> List.of("APP-01", "APP-02-SPACING", "APP-02-BLANK", "APP-02-FONT", "APP-02-SIZE", "APP-02-ALIGNMENT", "APP-03");
      case "引用" -> List.of("CITE-01");
      default -> List.of();
    };
  }

  private static RuleSpec specs(String module, String scope, String description, String evidence,
      boolean annotationSupported, String limitation) {
    return specs(module, scope, description, evidence, annotationSupported, limitation, "");
  }

  private static RuleSpec specs(String module, String scope, String description, String evidence,
      boolean annotationSupported, String inspectionLimitation, String annotationLimitation) {
    // The first argument is the report module; individual ids are provided by ruleIds().
    return new RuleSpec(module, scope, description, evidence, annotationSupported,
        inspectionLimitation, annotationLimitation);
  }

  public record RuleSpec(String module, String scope, String description, String evidence,
      boolean annotationSupported, String inspectionLimitation, String annotationLimitation) {
    public RuleSpec {
      if (module == null || module.isBlank()) throw new IllegalArgumentException("module is required");
      if (scope == null || scope.isBlank()) throw new IllegalArgumentException("scope is required");
      if (description == null || description.isBlank()) throw new IllegalArgumentException("description is required");
      if (evidence == null || evidence.isBlank()) throw new IllegalArgumentException("evidence is required");
      inspectionLimitation = inspectionLimitation == null ? "" : inspectionLimitation;
      annotationLimitation = annotationLimitation == null ? "" : annotationLimitation;
    }
  }

  public record Entry(String ruleId, RuleSpec spec) {
    public Entry {
      if (ruleId == null || ruleId.isBlank()) throw new IllegalArgumentException("ruleId is required");
      if (spec == null) throw new IllegalArgumentException("spec is required");
    }
    public String module() { return spec.module(); }
    public String scope() { return spec.scope(); }
    public String evidence() { return spec.evidence(); }
    public boolean annotationSupported() { return spec.annotationSupported(); }
    public String inspectionLimitation() { return spec.inspectionLimitation(); }
    public String annotationLimitation() { return spec.annotationLimitation(); }
  }
}
