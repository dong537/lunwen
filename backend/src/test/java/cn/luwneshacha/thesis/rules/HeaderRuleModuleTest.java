package cn.luwneshacha.thesis.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.luwneshacha.thesis.document.HeaderModel;
import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.RunModel;
import cn.luwneshacha.thesis.document.SectionModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HeaderRuleModuleTest {
  private static final SourceAddress BODY = new SourceAddress("word/document.xml", List.of(0), 0, 6);
  private static final SourceAddress SECTION = new SourceAddress("word/document.xml", List.of(0), 0, 0);

  @Test
  void acceptsRequiredHeaderForBodySection() {
    var result = new HeaderRuleModule().check(document(header("山东石油化工学院本科毕业设计（论文）", true,
        "黑体", "黑体", 21)), RuleSet.schoolV1());

    assertTrue(result.isEmpty(), result.toString());
  }

  @Test
  void rejectsHeaderTextFontSizeAndAlignment() {
    var result = new HeaderRuleModule().check(document(header("山东石油化工学院毕业设计", false,
        "宋体", "宋体", 24)), RuleSet.schoolV1());

    assertEquals(4, result.size());
    assertTrue(result.stream().anyMatch(item -> item.reasonCode().equals("HEADER_TEXT_MISMATCH")));
    assertTrue(result.stream().anyMatch(item -> item.reasonCode().equals("HEADER_FONT_MISMATCH")));
    assertTrue(result.stream().anyMatch(item -> item.reasonCode().equals("HEADER_SIZE_MISMATCH")));
    assertTrue(result.stream().anyMatch(item -> item.reasonCode().equals("HEADER_NOT_CENTERED")));
  }

  @Test
  void reportsMissingHeaderForBodySection() {
    var result = new HeaderRuleModule().check(document(null), RuleSet.schoolV1());

    assertEquals("HEADER_MISSING", result.get(0).reasonCode());
    assertEquals(Judgement.FAIL, result.get(0).judgement());
  }

  @Test
  void doesNotRequireHeaderForRecognizedCoverSection() {
    SectionModel cover = section(1, false, false, false);
    SectionModel body = section(3, true, false, false);
    HeaderModel bodyHeader = header("山东石油化工学院本科毕业设计（论文）", true,
        "黑体", "黑体", 21, HeaderModel.Type.DEFAULT, List.of(1));

    var result = new HeaderRuleModule().check(documentWithText(List.of(bodyHeader), List.of(cover, body),
        "本科生毕业设计（论文）", "2026年6月", "第一章 绪论", "正文"), RuleSet.schoolV1());

    assertTrue(result.isEmpty(), result.toString());
  }

  @Test
  void ignoresHeaderUsedOnlyByRecognizedCoverSection() {
    SectionModel cover = section(1, false, false, false);
    SectionModel body = section(3, true, false, false);
    HeaderModel coverHeader = header("word/header-cover.xml", "学校名称错误", true,
        "黑体", "黑体", 21, HeaderModel.Type.DEFAULT, List.of(0));
    HeaderModel bodyHeader = header("word/header-body.xml", "山东石油化工学院本科毕业设计（论文）", true,
        "黑体", "黑体", 21, HeaderModel.Type.DEFAULT, List.of(1));

    var result = new HeaderRuleModule().check(documentWithText(List.of(coverHeader, bodyHeader),
        List.of(cover, body), "本科生毕业设计（论文）", "2026年6月", "第一章 绪论", "正文"),
        RuleSet.schoolV1());

    assertTrue(result.isEmpty(), result.toString());
  }

  @Test
  void reportsMissingHeaderWhenTheImplicitSectionHasNoHeaderReference() {
    var result = new HeaderRuleModule().check(document(null, List.of()), RuleSet.schoolV1());

    assertEquals("HEADER_MISSING", result.get(0).reasonCode());
    assertEquals(Judgement.FAIL, result.get(0).judgement());
    assertEquals(BODY, result.get(0).source());
  }

  @Test
  void checksHeaderWhenBodyStartCannotBeLocated() {
    var result = new HeaderRuleModule().check(documentWithoutBodyChapter(), RuleSet.schoolV1());

    assertEquals(1, result.size());
    assertEquals("HEADER_MISSING", result.get(0).reasonCode());
    assertEquals(Judgement.FAIL, result.get(0).judgement());
  }

  @Test
  void checksEverySectionWithoutMappingTheBodyStart() {
    SourceAddress beforeBody = new SourceAddress("word/document.xml", List.of(0), 0, 0);
    SourceAddress bodyStart = new SourceAddress("word/document.xml", List.of(2), 0, 6);
    SectionModel section = new SectionModel(1474, 1474, 1474, 1474, beforeBody,
        null, null, false);
    HeaderModel header = header("山东石油化工学院本科毕业设计（论文）", true,
        "黑体", "黑体", 21);

    var result = new HeaderRuleModule().check(document(header, bodyStart, List.of(section)), RuleSet.schoolV1());

    assertTrue(result.isEmpty(), result.toString());
  }

  @Test
  void reportsReviewWhenSectionSourceIsUnavailable() {
    SectionModel section = new SectionModel(1474, 1474, 1474, 1474, null,
        null, null, false);
    HeaderModel header = header("山东石油化工学院本科毕业设计（论文）", true,
        "黑体", "黑体", 21);

    var result = new HeaderRuleModule().check(document(header, List.of(section)), RuleSet.schoolV1());

    assertEquals(1, result.size());
    assertEquals("HEADER_SCOPE_UNKNOWN", result.get(0).reasonCode());
    assertEquals(Judgement.REVIEW, result.get(0).judgement());
  }

  @Test
  void returnsReviewForUnresolvedHeaderAlignmentFontsAndSize() {
    HeaderModel header = new HeaderModel(new SourceAddress("word/header1.xml", List.of(0), 0, 0),
        "山东石油化工学院本科毕业设计（论文）", null,
        List.of(new HeaderModel.RunFormatting(null, null, null,
            new SourceAddress("word/header1.xml", List.of(0, 0), 0, 0))),
        Map.of(HeaderModel.Type.DEFAULT, List.of(0)));

    var result = new HeaderRuleModule().check(document(header), RuleSet.schoolV1());

    assertEquals(3, result.size());
    assertTrue(result.stream().allMatch(item -> item.judgement() == Judgement.REVIEW), result.toString());
    assertTrue(result.stream().anyMatch(item -> item.reasonCode().equals("HEADER_ALIGNMENT_UNKNOWN")));
    assertTrue(result.stream().anyMatch(item -> item.reasonCode().equals("HEADER_FONT_UNKNOWN")));
    assertTrue(result.stream().anyMatch(item -> item.reasonCode().equals("HEADER_SIZE_UNKNOWN")));
  }

  @Test
  void reportsMissingFirstPageHeaderEvenWhenDefaultHeaderExists() {
    SectionModel section = bodySection(true, false);
    HeaderModel defaultHeader = header("山东石油化工学院本科毕业设计（论文）", true,
        "黑体", "黑体", 21, HeaderModel.Type.DEFAULT);

    var result = new HeaderRuleModule().check(document(defaultHeader, List.of(section)), RuleSet.schoolV1());

    assertEquals(1, result.size());
    assertEquals("HEADER_MISSING", result.get(0).reasonCode());
    assertTrue(result.get(0).actual().contains("first"));
  }

  @Test
  void reportsMissingEvenPageHeaderWhenEvenAndOddHeadersAreEnabled() {
    SectionModel section = bodySection(false, true);
    HeaderModel defaultHeader = header("山东石油化工学院本科毕业设计（论文）", true,
        "黑体", "黑体", 21, HeaderModel.Type.DEFAULT);

    var result = new HeaderRuleModule().check(document(defaultHeader, List.of(section)), RuleSet.schoolV1());

    assertEquals(1, result.size());
    assertEquals("HEADER_MISSING", result.get(0).reasonCode());
    assertTrue(result.get(0).actual().contains("even"));
  }

  @Test
  void doesNotRequireDisabledFirstOrEvenPageHeaderVariants() {
    SectionModel section = bodySection(false, false);
    HeaderModel defaultHeader = header("山东石油化工学院本科毕业设计（论文）", true,
        "黑体", "黑体", 21, HeaderModel.Type.DEFAULT);

    var result = new HeaderRuleModule().check(document(defaultHeader, List.of(section)), RuleSet.schoolV1());

    assertTrue(result.isEmpty(), result.toString());
  }

  @Test
  void doesNotRequireEnabledFirstAndEvenHeaderVariantsUsedOnlyByCoverSection() {
    SectionModel cover = section(1, false, true, true);
    SectionModel body = section(3, true, false, false);
    HeaderModel defaultHeader = header("山东石油化工学院本科毕业设计（论文）", true,
        "黑体", "黑体", 21, HeaderModel.Type.DEFAULT, List.of(0, 1));

    var result = new HeaderRuleModule().check(documentWithText(List.of(defaultHeader),
        List.of(cover, body), "本科生毕业设计（论文）", "2026年6月", "第一章 绪论", "正文"),
        RuleSet.schoolV1());

    assertTrue(result.isEmpty(), result.toString());
  }

  @Test
  void stillChecksFirstSectionWhenThereIsNoCoverContent() {
    var result = new HeaderRuleModule().check(documentWithText(List.of(),
        List.of(section(1, false, false, false), section(3, true, false, false)),
        "摘    要", "摘要正文", "第一章 绪论", "正文"), RuleSet.schoolV1());

    assertEquals(2, result.size());
    assertEquals(List.of(1), result.get(0).source().elementPath());
    assertTrue(result.stream().allMatch(item -> item.reasonCode().equals("HEADER_MISSING")));
  }

  @Test
  void stillRequiresAbstractHeaderAfterSeparateCoverSection() {
    HeaderModel bodyHeader = header("山东石油化工学院本科毕业设计（论文）", true,
        "黑体", "黑体", 21, HeaderModel.Type.DEFAULT, List.of(2));
    var result = new HeaderRuleModule().check(documentWithText(List.of(bodyHeader),
        List.of(section(1, false, false, false), section(3, false, false, false),
            section(5, true, false, false)),
        "本科生毕业设计（论文）", "2026年6月", "摘    要", "摘要正文", "第一章 绪论", "正文"),
        RuleSet.schoolV1());

    assertEquals(1, result.size());
    assertEquals("HEADER_MISSING", result.get(0).reasonCode());
    assertEquals(List.of(3), result.get(0).source().elementPath());
  }

  @Test
  void stillRequiresContentsHeaderAfterSeparateCoverSection() {
    var result = new HeaderRuleModule().check(documentWithText(List.of(),
        List.of(section(1, false, false, false), section(3, true, false, false)),
        "本科生毕业设计（论文）", "2026年6月", "目    录", "第一章 绪论\t1"), RuleSet.schoolV1());

    assertEquals(1, result.size());
    assertEquals(List.of(3), result.get(0).source().elementPath());
  }

  @Test
  void sharedCoverAndAbstractSectionStillRequiresDefaultHeader() {
    var result = new HeaderRuleModule().check(documentWithText(List.of(),
        List.of(section(3, true, true, false)),
        "本科生毕业设计（论文）", "2026年6月", "摘    要", "摘要正文"), RuleSet.schoolV1());

    assertEquals(1, result.size());
    assertEquals("HEADER_MISSING", result.get(0).reasonCode());
    assertTrue(result.get(0).actual().contains("default"), result.toString());
  }

  @Test
  void sharedSectionExemptsOnlyItsCoverFirstPageVariant() {
    HeaderModel defaultHeader = header("山东石油化工学院本科毕业设计（论文）", true,
        "黑体", "黑体", 21, HeaderModel.Type.DEFAULT, List.of(0));
    var result = new HeaderRuleModule().check(documentWithText(List.of(defaultHeader),
        List.of(section(3, true, true, false)),
        "本科生毕业设计（论文）", "2026年6月", "ABSTRACT", "Abstract text"), RuleSet.schoolV1());

    assertTrue(result.isEmpty(), result.toString());
  }

  @Test
  void sharedCoverSectionStillRequiresEnabledEvenPageHeader() {
    HeaderModel defaultHeader = header("山东石油化工学院本科毕业设计（论文）", true,
        "黑体", "黑体", 21, HeaderModel.Type.DEFAULT, List.of(0));
    var result = new HeaderRuleModule().check(documentWithText(List.of(defaultHeader),
        List.of(section(3, true, true, true)),
        "本科生毕业设计（论文）", "2026年6月", "摘    要", "摘要正文"), RuleSet.schoolV1());

    assertEquals(1, result.size());
    assertTrue(result.get(0).actual().contains("even"), result.toString());
  }

  @Test
  void checksCoverHeaderWhenLaterContentInheritsIt() {
    HeaderModel inheritedHeader = header("学校名称错误", true, "黑体", "黑体", 21,
        HeaderModel.Type.DEFAULT, List.of(0, 1));
    var result = new HeaderRuleModule().check(documentWithText(List.of(inheritedHeader),
        List.of(section(1, false, false, false), section(3, true, false, false)),
        "本科生毕业设计（论文）", "2026年6月", "摘    要", "摘要正文"), RuleSet.schoolV1());

    assertEquals(1, result.size());
    assertEquals("HEADER_TEXT_MISMATCH", result.get(0).reasonCode());
    assertEquals(inheritedHeader.source(), result.get(0).source());
  }

  @Test
  void coverWordsInBodyDoNotSuppressHeaderChecking() {
    var result = new HeaderRuleModule().check(documentWithText(List.of(),
        List.of(section(2, true, true, false)),
        "第一章 绪论", "本科生毕业设计（论文）", "学生姓名：示例"), RuleSet.schoolV1());

    assertEquals(2, result.size());
    assertTrue(result.stream().anyMatch(item -> item.actual().contains("first")));
  }

  @Test
  void recognizesCoverFromSchoolAndMultipleFieldLabelsWhenTypeHeadingIsMissing() {
    var result = new HeaderRuleModule().check(documentWithText(List.of(),
        List.of(section(3, false, false, false), section(5, true, false, false)),
        "山东石油化工学院", "学生姓名：张三", "学 号：22061181010", "2026年6月",
        "摘    要", "摘要正文"), RuleSet.schoolV1());

    assertEquals(1, result.size());
    assertEquals(List.of(5), result.get(0).source().elementPath());
  }

  @Test
  void doesNotRequireHeaderForImplicitSectionContainingOnlyCover() {
    var result = new HeaderRuleModule().check(documentWithText(List.of(), List.of(),
        "山东石油化工学院", "本科生毕业设计（论文）", "学生姓名：张三", "2026年6月"),
        RuleSet.schoolV1());

    assertTrue(result.isEmpty(), result.toString());
  }

  @Test
  void doesNotRequireHeaderForTerminalSectionContainingOnlyCover() {
    var result = new HeaderRuleModule().check(documentWithText(List.of(),
        List.of(section(3, true, true, true)), "山东石油化工学院", "本科生毕业设计（论文）",
        "学生姓名：张三", "2026年6月"), RuleSet.schoolV1());

    assertTrue(result.isEmpty(), result.toString());
  }

  @Test
  void missingImplicitSectionHeaderIsAnchoredAfterTheCover() {
    var result = new HeaderRuleModule().check(documentWithText(List.of(), List.of(),
        "本科生毕业设计（论文）", "2026年6月", "摘    要", "摘要正文"), RuleSet.schoolV1());

    assertEquals(1, result.size());
    assertEquals("HEADER_MISSING", result.get(0).reasonCode());
    assertEquals(List.of(2), result.get(0).source().elementPath());
  }

  @Test
  void coverHeadingWithoutDateOrSectionBoundaryDoesNotExemptFollowingProse() {
    var result = new HeaderRuleModule().check(documentWithText(List.of(),
        List.of(section(2, true, false, false)), "本科生毕业设计（论文）", "学生姓名：张三",
        "本系统通过传感器采集环境数据，完成数据分析、设备控制和状态预警。"), RuleSet.schoolV1());

    assertEquals(1, result.size());
    assertEquals("HEADER_MISSING", result.get(0).reasonCode());
    assertEquals(List.of(2), result.get(0).source().elementPath());
  }

  @Test
  void unrecognizedAbstractHeadingAfterCoverDoesNotExemptImplicitSection() {
    var result = new HeaderRuleModule().check(documentWithText(List.of(), List.of(),
        "本科生毕业设计（论文）", "学生姓名：张三", "内容摘要",
        "本论文研究基于传感器的鱼缸环境调节方法。"), RuleSet.schoolV1());

    assertEquals(1, result.size());
    assertEquals("HEADER_MISSING", result.get(0).reasonCode());
    assertEquals(List.of(2), result.get(0).source().elementPath());
  }

  private ParsedDocument documentWithText(List<HeaderModel> headers, List<SectionModel> sections,
      String... texts) {
    var paragraphs = new java.util.ArrayList<ParagraphModel>();
    for (int index = 0; index < texts.length; index++) {
      SourceAddress source = new SourceAddress("word/document.xml", List.of(index), 0, texts[index].length());
      paragraphs.add(new ParagraphModel(List.of(new RunModel(texts[index], "Times New Roman", "宋体",
          null, 24, false, source)), null, null, null, null, null, null, source, null, null, "left", null));
    }
    return new ParsedDocument("sha", paragraphs, sections, List.of(), List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of(), headers);
  }

  private ParsedDocument document(HeaderModel header) {
    return document(header, List.of(new SectionModel(1474, 1474, 1474, 1474, SECTION,
        null, null, true)));
  }

  private ParsedDocument document(HeaderModel header, List<SectionModel> sections) {
    return document(header, BODY, sections);
  }

  private ParsedDocument document(HeaderModel header, SourceAddress bodySource, List<SectionModel> sections) {
    return document(header == null ? List.of() : List.of(header), bodySource, sections);
  }

  private ParsedDocument document(List<HeaderModel> headers, SourceAddress bodySource, List<SectionModel> sections) {
    var chapter = new ParagraphModel(List.of(new RunModel("第一章 绪论", "Times New Roman", "宋体",
        null, 32, false, bodySource)), null, null, null, null, null, null, bodySource, null, 0, "left", null);
    return new ParsedDocument("sha", List.of(chapter), sections, List.of(), List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of(), headers);
  }

  private ParsedDocument documentWithoutBodyChapter() {
    var paragraph = new ParagraphModel(List.of(new RunModel("普通正文", "Times New Roman", "宋体",
        null, 24, false, BODY)), null, null, null, null, null, null, BODY, null, 0, "left", null);
    return new ParsedDocument("sha", List.of(paragraph),
        List.of(new SectionModel(1474, 1474, 1474, 1474, SECTION, null, null, true)),
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
  }

  private HeaderModel header(String text, boolean centered, String ascii, String eastAsia, int size) {
    return header(text, centered, ascii, eastAsia, size, HeaderModel.Type.DEFAULT);
  }

  private HeaderModel header(String text, boolean centered, String ascii, String eastAsia, int size,
      HeaderModel.Type type) {
    return header(text, centered, ascii, eastAsia, size, type, List.of(0));
  }

  private HeaderModel header(String text, boolean centered, String ascii, String eastAsia, int size,
      HeaderModel.Type type, List<Integer> applicableSections) {
    return header("word/header1.xml", text, centered, ascii, eastAsia, size, type, applicableSections);
  }

  private HeaderModel header(String partName, String text, boolean centered, String ascii, String eastAsia, int size,
      HeaderModel.Type type, List<Integer> applicableSections) {
    SourceAddress source = new SourceAddress(partName, List.of(0), 0, text.length());
    return new HeaderModel(source, text, centered,
        List.of(new HeaderModel.RunFormatting(ascii, eastAsia, size,
            new SourceAddress(partName, List.of(0, 0), 0, text.length()))),
        Map.of(type, applicableSections));
  }

  private SectionModel bodySection(boolean firstPage, boolean evenPage) {
    return new SectionModel(1474, 1474, 1474, 1474, 0, SECTION, null, false, null, false,
        true, false, firstPage, evenPage);
  }

  private SectionModel section(int sourceIndex, boolean terminal, boolean firstPage, boolean evenPage) {
    SourceAddress source = new SourceAddress("word/document.xml", List.of(sourceIndex), 0, 0);
    return new SectionModel(1474, 1474, 1474, 1474, 0, source, null, false, null, false,
        terminal, false, firstPage, evenPage);
  }
}
