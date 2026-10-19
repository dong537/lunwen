package cn.luwneshacha.thesis.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.luwneshacha.thesis.document.SourceAddress;
import cn.luwneshacha.thesis.rules.Applicability;
import cn.luwneshacha.thesis.rules.CheckResult;
import cn.luwneshacha.thesis.rules.ExecutionStatus;
import cn.luwneshacha.thesis.rules.Judgement;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuleDescriptionsTest {
  private static final SourceAddress SOURCE = new SourceAddress("word/document.xml", List.of(0), 0, 1);
  @TempDir Path temp;

  @Test
  void namesEveryEmittedRuleInChinese() {
    Map<String, String> names = Map.ofEntries(
        Map.entry("ABSTRACT-FONT", "摘要正文字体"), Map.entry("ABSTRACT-INDENT", "摘要首行缩进"),
        Map.entry("ABSTRACT-KEYWORD-BOLD", "中文关键词格式"), Map.entry("ABSTRACT-KEYWORD-FORMAT", "中文关键词格式"),
        Map.entry("ABSTRACT-LINE", "摘要行距"), Map.entry("ABSTRACT-SIZE", "摘要正文字号"),
        Map.entry("APP-01", "附录编号"), Map.entry("APP-02-SPACING", "附录标题字间空格"),
        Map.entry("APP-02-BLANK", "附录标题后空行"),
        Map.entry("CAPTION-FONT", "图表题字体"), Map.entry("CAPTION-SIZE", "图表题字号"),
        Map.entry("COVER-01-FONT", "封面学校名称字体"), Map.entry("COVER-01-SIZE", "封面学校名称字号"),
        Map.entry("COVER-01-ALIGNMENT", "封面学校名称对齐"), Map.entry("COVER-02-FONT", "封面论文题目字体"),
        Map.entry("COVER-02-SIZE", "封面论文题目字号"), Map.entry("COVER-03-FONT", "封面个人信息格式"),
        Map.entry("COVER-04-SIZE", "封面日期格式"), Map.entry("EN-01-BOLD", "英文摘要标题格式"),
        Map.entry("EN-03-FORMAT", "英文关键词格式"),
        Map.entry("FIG-03", "图号与图题格式"), Map.entry("FIG-05", "图号连续性"),
        Map.entry("HEAD-01-SPACING", "一级标题编号空格"), Map.entry("HEAD-02-BLANK", "章标题后空行"),
        Map.entry("HEAD-01-ALIGNMENT", "一级标题对齐"),
        Map.entry("HEAD-03-INDENT", "三级标题缩进"), Map.entry("HEAD-05-LINE", "三级以下标题行距"),
        Map.entry("PAGE-03", "章节分页"), Map.entry("PAGE-05", "页码格式"),
        Map.entry("REF-01-FONT", "参考文献标题字体"), Map.entry("REF-05", "参考文献数量"),
        Map.entry("REF-SIZE", "参考文献字号"), Map.entry("TAB-05", "表号连续性"),
        Map.entry("TOC-01-SPACING", "目录标题格式"), Map.entry("ZH-01-SPACING", "中文摘要标题格式"),
        Map.entry("ZH-02-BLANK", "摘要标题后空行"), Map.entry("ZH-04-BLANK", "摘要与关键词间空行"));

    names.forEach((ruleId, expectedName) -> assertEquals(expectedName, RuleDescriptions.name(ruleId), ruleId));
  }

  @Test
  void namesEveryCatalogRuleWithAnExplicitChineseLabel() {
    for (var entry : cn.luwneshacha.thesis.inspection.RuleCatalog.entries()) {
      String name = RuleDescriptions.name(entry.ruleId());
      assertTrue(name != null && !name.isBlank(), entry.ruleId());
      assertFalse("格式错误".equals(name),
          "catalog rule must not fall through to the generic report label: " + entry.ruleId());
    }
  }

  @Test
  void givesSpecificAdviceForNewCatalogLabels() {
    assertTrue(RuleDescriptions.suggestion(result("COVER-03-LABEL", "COVER_FIELD_LABEL_MISMATCH"))
        .contains("指导教师"));
    assertTrue(RuleDescriptions.suggestion(result("EN-TOC-01-LINE", "LINE_SPACING_MISMATCH"))
        .contains("1.5 倍"));
  }

  @Test
  void explainsFirstLevelIndentWithoutInventingLowerLevelRequirements() {
    CheckResult wrongValue = result("TOC-ENTRY-INDENT", "INDENT_MISMATCH",
        "leftChars=0 / left=0 twips", "left=360 twips", Judgement.FAIL);
    CheckResult typedWhitespace = result("TOC-ENTRY-INDENT", "INDENT_MISMATCH",
        "leftChars=0 / left=0 twips", "条目文字前有空白字符", Judgement.FAIL);

    String wrongValueAdvice = RuleDescriptions.suggestion(wrongValue);
    assertTrue(wrongValueAdvice.contains("一级目录条目"), wrongValueAdvice);
    assertTrue(wrongValueAdvice.contains("0 字符"), wrongValueAdvice);
    assertTrue(wrongValueAdvice.contains("目录不要求首行缩进"), wrongValueAdvice);
    assertFalse(wrongValueAdvice.contains("2 字符") || wrongValueAdvice.contains("4 字符"), wrongValueAdvice);
    assertFalse(wrongValueAdvice.contains("twips"), wrongValueAdvice);

    String whitespaceAdvice = RuleDescriptions.suggestion(typedWhitespace);
    assertTrue(whitespaceAdvice.contains("删除这些字符"), whitespaceAdvice);
    assertTrue(whitespaceAdvice.contains("顶格"), whitespaceAdvice);
  }

  @Test
  void contentsIndentReportValuesDescribeLeftIndentInWordUnits() {
    CheckResult wrongCharacters = result("TOC-ENTRY-INDENT", "INDENT_MISMATCH",
        "leftChars=0 / left=0 twips", "leftChars=199", Judgement.FAIL);
    CheckResult wrongDistance = result("TOC-ENTRY-INDENT", "INDENT_MISMATCH",
        "leftChars=0 / left=0 twips", "left=720 twips", Judgement.FAIL);

    assertEquals("左侧缩进 0 字符", RuleDescriptions.expectedValue(wrongCharacters));
    assertEquals("左侧缩进 1.99 字符", RuleDescriptions.actualValue(wrongCharacters));
    assertEquals("左侧缩进 0 字符", RuleDescriptions.expectedValue(wrongDistance));
    assertEquals("左侧缩进 1.27 厘米", RuleDescriptions.actualValue(wrongDistance));
  }

  @Test
  void explainsThatWholeParagraphLeftIndentIsNotFirstLineIndent() {
    CheckResult result = result("BODY-INDENT", "INDENT_MISMATCH", "480 twips",
        "0；另有整体左缩进 720 twips", Judgement.FAIL);

    String advice = RuleDescriptions.suggestion(result);
    assertTrue(advice.contains("整体左缩进约为 1.27 厘米"), advice);
    assertTrue(advice.contains("不能替代首行缩进"), advice);
    assertTrue(advice.contains("首行缩进设为 2 字符"), advice);
  }

  @Test
  void givesNumberedSubheadingIndentAdviceWithoutGenericCopy() {
    CheckResult result = result("HEAD-05-INDENT", "INDENT_MISMATCH", "200 chars / 480 twips",
        "0", Judgement.FAIL);

    String advice = RuleDescriptions.suggestion(result);

    assertTrue(advice.contains("该编号标题没有设置首行缩进"), advice);
    assertTrue(advice.contains("首行缩进设为 2 字符"), advice);
    assertTrue(advice.contains("不要在编号前输入空格"), advice);
  }

  @Test
  void distinguishesWrongNumberedSubheadingIndentFromMissingIndent() {
    CheckResult result = result("HEAD-05-INDENT", "INDENT_MISMATCH",
        "firstLineChars=200 / firstLine=480 twips", "firstLine=420 twips", Judgement.FAIL);

    String advice = RuleDescriptions.suggestion(result);

    assertTrue(advice.contains("首行缩进与规范要求的 2 字符不一致"), advice);
    assertFalse(advice.contains("没有设置首行缩进"), advice);
  }

  @Test
  void namesAndExplainsRequiredStructureRulesInChinese() {
    CheckResult section = result("SECTION-REQUIRED", "REQUIRED_SECTION_MISSING");
    CheckResult cover = result("COVER-05", "COVER_FIELD_MISSING");
    CheckResult heading = result("HEAD-01-ALIGNMENT", "HEADING_ALIGNMENT_MISMATCH");

    assertEquals("必需章节", RuleDescriptions.name(section));
    assertEquals("学校论文必须包含此章节，但没有找到", RuleDescriptions.reason(section));
    assertTrue(RuleDescriptions.suggestion(section).contains("补齐"));
    assertEquals("封面必填字段", RuleDescriptions.name(cover));
    assertEquals("封面缺少规范要求的字段", RuleDescriptions.reason(cover));
    assertTrue(RuleDescriptions.suggestion(cover).contains("封面"));
    assertEquals("一级标题没有居中", RuleDescriptions.reason(heading));
    assertTrue(RuleDescriptions.suggestion(heading).contains("一级标题"));
  }

  @Test
  void requiredSectionAdviceNamesTheMissingSection() {
    CheckResult conclusion = result("SECTION-REQUIRED", "REQUIRED_SECTION_MISSING",
        "结论", "未找到", Judgement.FAIL);

    assertEquals("未找到“结论”章节，请补齐第五章“结论”并与目录保持一致后重新检测。",
        RuleDescriptions.suggestion(conclusion));
  }

  @Test
  void appendixAdviceDescribesExistingAppendixWithoutMakingItMandatory() {
    var section = result("SECTION-REQUIRED", "APPENDIX_SECTION_MISSING",
        "附录分项", "未找到", Judgement.FAIL);
    var title = result("SECTION-REQUIRED", "APPENDIX_TITLE_MISSING");
    var contents = result("TOC-REQUIRED-ENTRY", "TOC_REQUIRED_APPENDIX_MISSING");
    var references = result("TOC-REQUIRED-ENTRY", "TOC_REQUIRED_REFERENCES_MISSING");

    assertTrue(RuleDescriptions.reason(section).contains("已有附录总标题"));
    assertTrue(RuleDescriptions.suggestion(section).contains("附录A"));
    assertTrue(RuleDescriptions.suggestion(title).contains("附录A等分项之前"));
    assertTrue(RuleDescriptions.reason(contents).contains("论文已有附录"));
    assertTrue(RuleDescriptions.suggestion(contents).contains("论文已有附录"));
    assertFalse(RuleDescriptions.suggestion(references).contains("附录"));
  }

  @Test
  void headerAdviceExemptsCoverAndCoversAbstractThroughAppendix() {
    String missing = RuleDescriptions.suggestion(result("HEADER-01-MISSING", "HEADER_MISSING"));
    String scopeUnknown = RuleDescriptions.suggestion(result("HEADER-01-SCOPE", "HEADER_SCOPE_UNKNOWN"));

    assertTrue(missing.contains("封面不要求页眉"));
    assertTrue(missing.contains("摘要至附录"));
    assertTrue(missing.contains("附录"));
    assertTrue(scopeUnknown.contains("封面不要求页眉"));
    assertTrue(scopeUnknown.contains("摘要至附录"));
    assertTrue(scopeUnknown.contains("附录"));
    assertFalse(scopeUnknown.contains("正文首章"));
  }

  @Test
  void explainsAppendixTitleBlankLineRulesInChinese() {
    CheckResult missing = result("APP-02-BLANK", "APPENDIX_TITLE_BLANK_LINE_MISSING");
    CheckResult extra = result("APP-02-BLANK", "APPENDIX_TITLE_BLANK_LINE_COUNT_MISMATCH");

    assertEquals("附录标题后没有空一行", RuleDescriptions.reason(missing));
    assertTrue(RuleDescriptions.suggestion(missing).contains("附    录"));
    assertEquals("附录标题后应只空一行", RuleDescriptions.reason(extra));
    assertTrue(RuleDescriptions.suggestion(extra).contains("只保留一个空白段落"));
  }

  @Test
  void unknownReasonDoesNotExposeInternalEnglishCode() {
    CheckResult result = result("BODY-FONT", "UNMAPPED_INTERNAL_REASON");

    String reason = RuleDescriptions.reason(result);
    assertFalse(reason.contains("UNMAPPED_INTERNAL_REASON"));
    assertTrue(reason.matches(".*[\\u4e00-\\u9fff].*"));
  }

  @Test
  void suggestionsForPreviouslyUnmappedRulesAreChineseAndActionable() {
    CheckResult result = result("COVER-03-FONT", "FONT_MISMATCH");

    String suggestion = RuleDescriptions.suggestion(result);
    assertTrue(suggestion.matches(".*[\\u4e00-\\u9fff].*"));
    assertFalse(suggestion.equals("按照学校论文规范修改此处格式，并保持同类内容一致。"));
  }

  @Test
  void headingSpacingAdviceNamesTheRequiredSingleHalfWidthSpace() {
    CheckResult result = result("HEAD-02-SPACING", "HEADING_NUMBER_SPACING_MISMATCH");

    assertTrue(RuleDescriptions.suggestion(result).contains("一个半角空格"));
  }

  @Test
  void englishAbstractSuggestionsFollowEnglishFormattingRules() {
    String titleFont = RuleDescriptions.suggestion(result("EN-01-FONT", "FONT_MISMATCH"));
    String titleBold = RuleDescriptions.suggestion(result("EN-01-BOLD", "BOLD_MISMATCH"));
    String keywordFormat = RuleDescriptions.suggestion(result("EN-03-FORMAT", "ENGLISH_KEYWORD_SEPARATOR_MISMATCH"));

    assertTrue(titleFont.contains("Times New Roman"));
    assertFalse(titleFont.contains("黑体"));
    assertTrue(titleBold.contains("加粗"));
    assertTrue(keywordFormat.contains("分号"));
  }

  @Test
  void englishKeywordCountReasonAndSuggestionAreChinese() {
    CheckResult result = result("EN-03-FORMAT", "ENGLISH_KEYWORD_COUNT_MISMATCH");

    assertEquals("英文关键词应为3至5个", RuleDescriptions.reason(result));
    assertTrue(RuleDescriptions.suggestion(result).contains("3 至 5 个"));
    assertTrue(RuleDescriptions.suggestion(result).contains("分号"));
  }

  @Test
  void englishKeywordBoldErrorExplainsThatOnlyTheLabelShouldBeBold() {
    CheckResult result = result("EN-03-BOLD", "KEYWORD_CONTENT_BOLD", "Keywords 标签单独加粗",
        "冒号及其后的空格被加粗", Judgement.FAIL);

    assertTrue(RuleDescriptions.reason(result).contains("冒号及其后的空格被加粗"));
    assertEquals("“Keywords”标签（含冒号）应保持加粗；请取消冒号后的空格加粗，关键词正文保持常规字重。",
        RuleDescriptions.suggestion(result));
  }

  @Test
  void englishKeywordBoldAdviceKeepsTheColonInsideTheBoldLabel() {
    CheckResult result = result("EN-03-BOLD", "KEYWORD_CONTENT_BOLD", "Keywords 标签单独加粗",
        "冒号和关键词内容都被加粗", Judgement.FAIL);

    assertEquals("“Keywords”标签（含冒号）应保持加粗；取消关键词正文的加粗，冒号后的空格保持常规字重。",
        RuleDescriptions.suggestion(result));
  }

  @Test
  void englishKeywordLabelAdviceKeepsTheColonBoldAndTheFollowingSpaceRegular() {
    CheckResult result = result("EN-03-BOLD", "KEYWORD_LABEL_NOT_BOLD", "true", "false", Judgement.FAIL);

    assertEquals("“Keywords”标签（含冒号）没有加粗", RuleDescriptions.reason(result));
    assertEquals("请将“Keywords”标签（含冒号）加粗；冒号后的空格和关键词正文保持常规字重。",
        RuleDescriptions.suggestion(result));
  }

  @Test
  void englishKeywordContentAdviceRemovesBoldFromContentAndPreservesTheLabel() {
    CheckResult result = result("EN-03-BOLD", "KEYWORD_CONTENT_BOLD", "Keywords 标签单独加粗",
        "关键词内容被加粗", Judgement.FAIL);

    assertEquals("关键词正文被加粗；请取消其加粗，保留“Keywords”标签（含冒号）加粗；冒号后的空格保持常规字重。",
        RuleDescriptions.suggestion(result));
  }

  @Test
  void pageRangeSpacingFindingExplainsTheExactCorrection() {
    CheckResult result = result("REF-04", "REFERENCE_PAGE_RANGE_SPACING",
        "页码范围连接号两侧不留空格", "140- 145", Judgement.FAIL);

    assertEquals("页码范围连接号两侧有多余空格", RuleDescriptions.reason(result));
    assertEquals("请删除页码范围连接号两侧的空格，写成“140-145”这样的格式。",
        RuleDescriptions.suggestion(result));
  }

  @Test
  void blankLineCountAdviceNamesTheAffectedEndMatterSection() {
    CheckResult reference = result("REF-01-BLANK", "REFERENCE_TITLE_BLANK_LINE_COUNT_MISMATCH",
        "空一行", "空两行", Judgement.FAIL);
    CheckResult acknowledgement = result("END-01-BLANK", "ACKNOWLEDGEMENT_TITLE_BLANK_LINE_COUNT_MISMATCH",
        "空一行", "空两行", Judgement.FAIL);

    assertEquals("“参考文献”标题与第一条文献之间只保留一个空白段落。",
        RuleDescriptions.suggestion(reference));
    assertEquals("“致谢”标题与正文之间只保留一个空白段落。",
        RuleDescriptions.suggestion(acknowledgement));
  }

  @Test
  void lowerHeadingAdviceMatchesTheSchoolExampleWithoutRequiringAComma() {
    CheckResult result = result("HEAD-05-MARKER", "LOWER_HEADING_MARKER_MISMATCH");

    assertEquals("三级以下标题编号应写成“（1）标题”，右括号后不要加顿号。",
        RuleDescriptions.suggestion(result));
  }

  @Test
  void headingFontReportNamesAndAdviceIncludeTheSpecificLevel() {
    CheckResult result = new CheckResult("HEADING-FONT", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "二级标题：黑体/Times New Roman", "宋体/Arial", "FONT_MISMATCH", SOURCE);

    assertEquals("二级标题字体", RuleDescriptions.name(result));
    assertEquals("二级标题：中文改为黑体，字母和数字改为 Times New Roman。",
        RuleDescriptions.suggestion(result));
  }

  @Test
  void tableAndReferenceNumberReasonsHaveSpecificChineseText() {
    assertEquals("表格中不能用“同前”“同左”代替内容",
        RuleDescriptions.reason(result("TAB-06", "TABLE_REPEATED_PLACEHOLDER")));
    assertEquals("参考文献编号没有连续排列",
        RuleDescriptions.reason(result("REF-07", "REFERENCE_NUMBER_SEQUENCE_MISMATCH")));
    assertEquals("参考文献编号格式不正确",
        RuleDescriptions.reason(result("REF-07", "REFERENCE_NUMBER_INVALID")));
  }

  @Test
  void referenceNumberSpacingExplainsTheRequiredOrdinarySpace() {
    CheckResult result = result("REF-07", "REFERENCE_NUMBER_SPACING_MISMATCH",
        "编号后一个普通半角空格", "编号后没有空格", Judgement.FAIL);

    assertEquals("参考文献编号与文献内容之间不是一个普通半角空格",
        RuleDescriptions.reason(result));
    assertEquals("参考文献编号后缺少空格；请在编号后补一个普通半角空格，再接作者姓名。",
        RuleDescriptions.suggestion(result));
  }

  @Test
  void referenceNumberSpacingAdviceNamesRepeatedOrdinarySpaces() {
    CheckResult result = result("REF-07", "REFERENCE_NUMBER_SPACING_MISMATCH",
        "编号后一个普通半角空格", "编号后连续 2 个普通半角空格", Judgement.FAIL);

    assertEquals("参考文献编号后有 2 个普通半角空格；请删去多余空格，只保留一个，再接作者姓名。",
        RuleDescriptions.suggestion(result));
  }

  @Test
  void bodyIndentAdviceDistinguishesParagraphIndentFromTypedLeadingWhitespace() {
    CheckResult result = result("BODY-INDENT", "INDENT_TEXT_WHITESPACE",
        "2 chars / 480 twips", "2 chars / 480 twips；文字前有 1 个文字空白", Judgement.FAIL);

    assertEquals("正文段首另有 1 个空白字符，但段落首行缩进符合 2 字符要求。", RuleDescriptions.reason(result));
    assertEquals("正文文字开头有 1 个空白字符，请删除该空白，保留段落首行缩进为 2 字符。",
        RuleDescriptions.suggestion(result));
  }

  @Test
  void indentMismatchAdviceDoesNotTellUsersToDeleteWhitespaceThatWasNotFound() {
    CheckResult result = result("BODY-INDENT", "INDENT_MISMATCH",
        "2 chars / 480 twips", "420 twips", Judgement.FAIL);

    assertEquals("正文段落首行缩进不符合要求", RuleDescriptions.reason(result));
    assertEquals("正文首行缩进约为 0.74 厘米，规范要求 2 字符（约 0.85 厘米）；请在 Word 的“段落”设置中将“特殊格式”设为“首行”，缩进量设为 2 字符。",
        RuleDescriptions.suggestion(result));
  }

  @Test
  void unknownTableQuantityDimensionHasSpecificChineseAdvice() {
    CheckResult result = result("TAB-04", "TABLE_UNIT_DIMENSION_UNKNOWN");

    assertEquals("表头中的量名称无法识别，无法确认所标单位是否匹配",
        RuleDescriptions.reason(result));
    assertTrue(RuleDescriptions.suggestion(result).contains("明确的量名称"));
  }

  @Test
  void uncertainFindingsAreDescribedAsUnconfirmedAndAdviceDoesNotAssumeAnError() {
    List<CheckResult> reviews = List.of(
        result("CITE-01", "CITATION_FORMAT_UNKNOWN", "citation format", null, Judgement.REVIEW),
        result("FIG-02", "FIGURE_LAYOUT_REVIEW", "figure layout", null, Judgement.REVIEW),
        result("FIG-01", "FIGURE_REFERENCE_NEEDS_REVIEW", "figure reference", null, Judgement.REVIEW),
        result("TAB-03", "TABLE_LAYOUT_REVIEW", "table caption location", null, Judgement.REVIEW),
        result("TAB-02", "TABLE_BORDER_EVIDENCE_UNRESOLVED", "three-line table", null, Judgement.REVIEW));

    for (CheckResult review : reviews) {
      assertTrue(RuleDescriptions.reason(review).contains("暂无法确认"), review.reasonCode());
      assertTrue(RuleDescriptions.suggestion(review).contains("请在 Word 中"), review.reasonCode());
    }
    assertTrue(RuleDescriptions.suggestion(reviews.get(2)).contains("只有确认缺少时再补充"));
  }

  @Test
  void citationRangeMismatchHasChineseReasonAndActionableSuggestion() {
    CheckResult result = result("REF-03", "CITATION_RANGE_MISMATCH");

    assertEquals("引用范围中的编号必须都能在文末参考文献中找到对应条目",
        RuleDescriptions.reason(result));
    assertEquals("检查正文中的引用范围，确保每个编号都有对应的文末参考文献。",
        RuleDescriptions.suggestion(result));
  }

  @Test
  void equationParenthesisErrorHasSpecificChineseReasonAndSuggestion() {
    CheckResult result = result("EQ-01", "EQUATION_NUMBER_PARENTHESES_MISSING");

    assertEquals("公式编号没有加圆括号", RuleDescriptions.reason(result));
    assertEquals("给公式编号加圆括号，例如“(3-1)”。", RuleDescriptions.suggestion(result));
  }

  @Test
  void externalEquationLimitationNamesEveryUnreadableInternalProperty() {
    CheckResult result = result("EQ-06", "EQUATION_OBJECT_UNSUPPORTED");

    String reason = RuleDescriptions.reason(result);
    String suggestion = RuleDescriptions.suggestion(result);
    assertTrue(reason.contains("内部字体"));
    assertTrue(reason.contains("变量斜体"));
    assertTrue(reason.contains("上下标"));
    assertTrue(reason.contains("结构"));
    assertTrue(suggestion.contains("内部字体"));
    assertTrue(suggestion.contains("变量斜体"));
    assertTrue(suggestion.contains("上下标"));
    assertTrue(suggestion.contains("结构"));
  }

  @Test
  void continuationCaptionAndTocLineSpacingSuggestionsMatchTheirRules() {
    String continuation = RuleDescriptions.suggestion(result("TAB-03", "TABLE_CONTINUATION_CAPTION_NOT_RIGHT"));
    String tocSpacing = RuleDescriptions.suggestion(result("TOC-01-LINE", "LINE_SPACING_MISMATCH"));

    assertTrue(continuation.contains("右对齐"));
    assertFalse(continuation.contains("居中"));
    assertTrue(tocSpacing.contains("1.5 倍"));
  }

  @Test
  void generatedWordReportUsesChineseRuleNameAndSuggestion() throws Exception {
    Path target = temp.resolve("report.docx");
    var report = new cn.luwneshacha.thesis.inspection.InspectionReport("hash", "school-v1",
        List.of(result("COVER-03-FONT", "FONT_MISMATCH")), List.of());

    new WordReportWriter().write(report, target);

    String document;
    try (ZipFile zip = new ZipFile(target.toFile())) {
      document = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(),
          java.nio.charset.StandardCharsets.UTF_8);
    }
    assertTrue(document.contains("封面个人信息格式"));
    assertTrue(document.contains("姓名、学号、院系、专业年级和指导教师"));
    assertFalse(document.contains("COVER-03-FONT"));
  }

  @Test
  void wordReportDoesNotCountUndeterminedChecksAsPassedOrExposeXmlCodes() throws Exception {
    Path target = temp.resolve("page-number-report.docx");
    var report = new cn.luwneshacha.thesis.inspection.InspectionReport("hash", "school-v1", List.of(
        result("PAGE-04", "PAGE_NUMBER_FORMAT_MISMATCH", "decimal", "upperRoman", Judgement.FAIL),
        result("PAGE-04", "PAGE_NUMBER_SEQUENCE_RESTARTED", "continuous numbering", "restarted at 1", Judgement.FAIL),
        result("EQ-07", "EQUATION_VARIABLES_REVIEW", "variables explained", null, Judgement.REVIEW)), List.of());

    new WordReportWriter().write(report, target);

    String document;
    try (ZipFile zip = new ZipFile(target.toFile())) {
      document = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(),
          java.nio.charset.StandardCharsets.UTF_8);
    }
    assertTrue(document.contains("确定检查项：2 项；通过：0 项；错误：2 项"));
    assertTrue(document.contains("阿拉伯数字页码"));
    assertTrue(document.contains("大写罗马数字"));
    assertFalse(document.contains("decimal"));
    assertFalse(document.contains("upperRoman"));
    assertFalse(document.contains("continuous numbering"));
    assertFalse(document.contains("restarted at 1"));
    assertFalse(document.contains("1 项；错误：2 项"));
    assertFalse(document.contains("人工复核"));
  }

  @Test
  void wordReportTranslatesInternalRuleDescriptionsToChinese() throws Exception {
    Path target = temp.resolve("translated-values-report.docx");
    var report = new cn.luwneshacha.thesis.inspection.InspectionReport("hash", "school-v1", List.of(
        result("TAB-03", "TABLE_CAPTION_BELOW", "caption above table", null, Judgement.FAIL),
        result("FIG-01", "FIGURE_NOT_CITED_IN_BODY", "body reference to figure", "1-1", Judgement.FAIL),
        result("TAB-01", "TABLE_NOT_CITED_IN_BODY", "body reference to table 2-3", null, Judgement.FAIL),
        result("EQ-04", "EQUATION_NOT_REFERENCED", "body reference to equation 3-1", null, Judgement.FAIL),
        result("REF-03", "CITED_REFERENCE_MISSING", "matching reference entry", "4", Judgement.FAIL),
        result("UNMAPPED-RULE", "UNMAPPED_REASON", "body reference to table 4-5", null, Judgement.FAIL)), List.of());

    new WordReportWriter().write(report, target);

    String document;
    try (ZipFile zip = new ZipFile(target.toFile())) {
      document = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(),
          java.nio.charset.StandardCharsets.UTF_8);
    }
    assertTrue(document.contains("表题应位于表格上方"));
    assertTrue(document.contains("正文引用图号"));
    assertTrue(document.contains("正文引用表号 2-3"));
    assertTrue(document.contains("正文引用公式（3-1）"));
    assertTrue(document.contains("正文引用对应的文末条目"));
    assertTrue(document.contains("正文引用表号 4-5"));
    assertFalse(document.contains("caption above table"));
    assertFalse(document.contains("body reference to figure"));
    assertFalse(document.contains("body reference to table"));
    assertFalse(document.contains("body reference to equation"));
    assertFalse(document.contains("matching reference entry"));
    assertFalse(document.contains("body reference to table 4-5"));
  }

  @Test
  void marginReportKeepsEnoughPrecisionToShowTwipDifferences() {
    CheckResult result = result("PAGE-01", "MARGIN_MISMATCH", "1474", "1440", Judgement.FAIL);

    assertEquals("2.6 cm", RuleDescriptions.expectedValue(result));
    assertEquals("2.54 cm (1440 twips)", RuleDescriptions.actualValue(result));
  }

  @Test
  void marginReportDoesNotRoundDistinctTwipValuesToTheSameCentimeters() {
    CheckResult result = result("PAGE-01", "MARGIN_MISMATCH", "1474", "1473", Judgement.FAIL);

    assertEquals("2.6 cm", RuleDescriptions.expectedValue(result));
    assertEquals("2.59821 cm (1473 twips)", RuleDescriptions.actualValue(result));
  }

  @Test
  void marginReportIdentifiesAnExtraGutterSeparatelyFromTheFourMargins() {
    CheckResult result = result("PAGE-01", "MARGIN_GUTTER_MISMATCH", "0", "120", Judgement.FAIL);

    assertEquals("页面设置了额外装订线", RuleDescriptions.reason(result));
    assertEquals("装订线 0 twips", RuleDescriptions.expectedValue(result));
    assertEquals("120 twips", RuleDescriptions.actualValue(result));
    assertEquals("将页面设置中的装订线设为 0；四边边距保持 2.6 cm。",
        RuleDescriptions.suggestion(result));
  }

  @Test
  void lineSpacingReportDoesNotRoundAnIncorrectAutoValueToOnePointFive() {
    CheckResult result = result("BODY-LINE", "LINE_SPACING_MISMATCH", "360 / auto", "359 / auto", Judgement.FAIL);

    assertEquals("359/240 倍行距（XML：359 / auto）", RuleDescriptions.actualValue(result));
  }

  @Test
  void lineSpacingReportUsesTwipsForExactAndAtLeastRules() {
    CheckResult exact = result("BODY-LINE", "LINE_SPACING_MISMATCH", "360 / auto", "360 / exact", Judgement.FAIL);
    CheckResult atLeast = result("BODY-LINE", "LINE_SPACING_MISMATCH", "360 / auto", "361 / atLeast", Judgement.FAIL);

    assertEquals("固定值 18 磅（XML：360 / exact）", RuleDescriptions.actualValue(exact));
    assertEquals("最小值 18.05 磅（XML：361 / atLeast）", RuleDescriptions.actualValue(atLeast));
  }

  @Test
  void genericLineSpacingDisplayDoesNotRoundAutoValues() {
    CheckResult result = result("BODY-LINE", "OTHER_REASON", "360 / auto", "359 / auto", Judgement.FAIL);

    assertEquals("359/240 倍行距", RuleDescriptions.actualValue(result));
  }

  @Test
  void indentationReportDisplaysCharacterHundredthsAsCharacters() {
    CheckResult result = result("BODY-INDENT", "INDENT_MISMATCH", "200", "0", Judgement.FAIL);

    assertEquals("2 字符", RuleDescriptions.expectedValue(result));
    assertEquals("0 字符", RuleDescriptions.actualValue(result));
  }

  @Test
  void indentationReportDisplaysFontDerivedTwipsAndMismatchClearly() {
    CheckResult mismatch = result("BODY-INDENT", "INDENT_MISMATCH",
        "420 twips / no leading text whitespace", "421 twips", Judgement.FAIL);
    CheckResult matching = result("BODY-INDENT", "", "420 twips / no leading text whitespace",
        "420 twips", Judgement.PASS);

    assertEquals("2\u4e2a\u5b57\u7b26\uff08420 twips\uff09", RuleDescriptions.expectedValue(mismatch));
    assertEquals("421 twips\uff08\u8981\u6c42 420 twips\uff0c\u5373 2\u4e2a\u5b57\u7b26\uff09",
        RuleDescriptions.actualValue(mismatch));
    assertEquals("2\u4e2a\u5b57\u7b26\uff08420 twips\uff09", RuleDescriptions.actualValue(matching));
  }

  @Test
  void sizeReportDoesNotOverflowOnHugeXmlValue() {
    CheckResult result = result("BODY-SIZE", "FONT_SIZE_MISMATCH", "24", "999999999999999999999999", Judgement.FAIL);

    assertEquals("499999999999999999999999.5 磅", RuleDescriptions.actualValue(result));
  }

  @Test
  void equationSizeReportDoesNotOverflowOnHugeXmlValue() {
    CheckResult result = result("EQ-02", "FONT_SIZE_MISMATCH", "24", "OMML / 999999999999999999999999", Judgement.FAIL);

    assertEquals("OMML，499999999999999999999999.5 磅", RuleDescriptions.actualValue(result));
  }

  private static CheckResult result(String ruleId, String reasonCode) {
    return result(ruleId, reasonCode, "期望", "实际", Judgement.FAIL);
  }

  private static CheckResult result(String ruleId, String reasonCode, String expected, String actual,
      Judgement judgement) {
    return new CheckResult(ruleId, Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        judgement, expected, actual, reasonCode, SOURCE);
  }
}
