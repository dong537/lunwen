package cn.luwneshacha.thesis.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.luwneshacha.thesis.document.HeaderModel;
import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.RunModel;
import cn.luwneshacha.thesis.document.SectionModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HeaderMetadataCoverScopeTest {
  private static final String REQUIRED = "山东石油化工学院本科毕业设计（论文）";

  @Test
  void recognizesCoverFromMetadataAndFollowingAbstractWhenTypeHeadingVaries() {
    var document = document(List.of(section(7, false), section(9, true)), List.of(header(1)),
        "毕业生本科设计（论文）", "题    目：传统文化融入幼儿园区域活动现状与策略研究",
        "学生姓名：徐冰", "学    号：2105101126", "院    系：教育与现代媒体学院教育系",
        "指导教师：李慧", "2025年 6 月 4 日", "", "摘    要", "摘要正文");

    assertTrue(new HeaderRuleModule().check(document, RuleSet.schoolV1()).isEmpty());
  }

  @Test
  void doesNotExemptMetadataCoverWithoutTitleLabel() {
    var document = document(List.of(section(7, false), section(9, true)), List.of(header(1)),
        "毕业生本科设计（论文）", "传统文化融入幼儿园区域活动现状与策略研究",
        "学生姓名：徐冰", "学    号：2105101126", "院    系：教育与现代媒体学院教育系",
        "指导教师：李慧", "2025年 6 月 4 日", "", "摘    要", "摘要正文");

    var result = new HeaderRuleModule().check(document, RuleSet.schoolV1());

    assertEquals(1, result.size());
    assertEquals("HEADER_MISSING", result.getFirst().reasonCode());
    assertEquals(source(7), result.getFirst().source());
  }

  @Test
  void doesNotExemptMetadataCoverWithoutStudentIdOrDate() {
    for (String missing : List.of("学    号：2105101126", "2025年 6 月 4 日")) {
      List<String> texts = new ArrayList<>(List.of("毕业生本科设计（论文）",
          "题    目：传统文化融入幼儿园区域活动现状与策略研究",
          "学生姓名：徐冰", "学    号：2105101126", "院    系：教育与现代媒体学院教育系",
          "指导教师：李慧", "2025年 6 月 4 日", "", "摘    要", "摘要正文"));
      texts.set(missing.startsWith("学") ? 3 : 6, "");
      var document = document(List.of(section(7, false), section(9, true)), List.of(header(1)), texts);

      var result = new HeaderRuleModule().check(document, RuleSet.schoolV1());

      assertEquals(1, result.size(), missing);
      assertEquals("HEADER_MISSING", result.getFirst().reasonCode(), missing);
      assertEquals(source(7), result.getFirst().source(), missing);
    }
  }

  @Test
  void doesNotCombineCoverFieldsAcrossSections() {
    var document = document(List.of(section(4, false), section(7, false), section(9, true)),
        List.of(), "毕业生本科设计（论文）",
        "题    目：传统文化融入幼儿园区域活动现状与策略研究",
        "学生姓名：徐冰", "学    号：2105101126", "院    系：教育与现代媒体学院教育系",
        "指导教师：李慧", "2025年 6 月 4 日", "", "摘    要", "摘要正文");

    var result = new HeaderRuleModule().check(document, RuleSet.schoolV1());

    assertEquals(3, result.size());
    assertTrue(result.stream().allMatch(finding -> finding.reasonCode().equals("HEADER_MISSING")), result.toString());
  }

  @Test
  void neverExemptsBodyFieldsAfterRecognizedChapterStart() {
    var document = document(List.of(section(7, false), section(9, true)), List.of(header(1)),
        "第一章 绪论", "题目：学生姓名：学号：日期：",
        "学生姓名：徐冰", "学    号：2105101126", "院    系：教育与现代媒体学院教育系",
        "指导教师：李慧", "2025年 6 月 4 日", "", "正文", "正文内容");

    var result = new HeaderRuleModule().check(document, RuleSet.schoolV1());

    assertEquals(1, result.size());
    assertEquals("HEADER_MISSING", result.getFirst().reasonCode());
    assertEquals(source(7), result.getFirst().source());
  }

  @Test
  void stillChecksAbstractWhenRecognizedCoverHasNoHeader() {
    var document = document(List.of(section(7, false), section(9, true)), List.of(header(0)),
        "毕业生本科设计（论文）", "题    目：传统文化融入幼儿园区域活动现状与策略研究",
        "学生姓名：徐冰", "学    号：2105101126", "院    系：教育与现代媒体学院教育系",
        "指导教师：李慧", "2025年 6 月 4 日", "", "摘    要", "摘要正文");

    var result = new HeaderRuleModule().check(document, RuleSet.schoolV1());

    assertEquals(1, result.size());
    assertEquals("HEADER_MISSING", result.getFirst().reasonCode());
    assertEquals(source(9), result.getFirst().source());
  }

  @Test
  void doesNotExemptUnfilledCoverLabels() {
    for (int emptyIndex : List.of(1, 2, 3)) {
      List<String> texts = new ArrayList<>(List.of("毕业生本科设计（论文）", "题目：论文题名",
          "学生姓名：学生", "学号：123456", "2026年6月", "", "摘    要", "摘要正文"));
      texts.set(emptyIndex, texts.get(emptyIndex).split("：")[0] + "：    ");
      var document = document(List.of(section(5, false), section(7, true)), List.of(header(1)), texts);

      var result = new HeaderRuleModule().check(document, RuleSet.schoolV1());

      assertEquals(1, result.size(), texts.toString());
      assertEquals(source(5), result.getFirst().source());
    }
  }

  @Test
  void doesNotExemptMetadataWithoutSeparateInitialSection() {
    var document = document(List.of(section(7, true)), List.of(), "毕业生本科设计（论文）",
        "题目：论文题名", "学生姓名：学生", "学号：123456", "2026年6月", "", "摘要", "摘要正文");

    var result = new HeaderRuleModule().check(document, RuleSet.schoolV1());

    assertEquals(1, result.size());
    assertEquals("HEADER_MISSING", result.getFirst().reasonCode());
  }

  @Test
  void doesNotExemptMetadataWithoutFollowingStructuralBoundary() {
    var document = document(List.of(section(5, false), section(7, true)), List.of(header(1)),
        "毕业生本科设计（论文）", "题目：论文题名", "学生姓名：学生", "学号：123456", "2026年6月", "",
        "未识别的普通段落", "普通内容");

    var result = new HeaderRuleModule().check(document, RuleSet.schoolV1());

    assertEquals(1, result.size());
    assertEquals(source(5), result.getFirst().source());
  }

  @Test
  void coversWholeInitialSectionWhenDateIsNotItsLastContent() {
    var document = document(List.of(section(6, false), section(8, true)), List.of(header(1)),
        "毕业生本科设计（论文）", "题目：论文题名", "学生姓名：学生", "学号：123456", "2026年6月",
        "指导教师：教师", "", "目录", "第一章 绪论 1");

    assertTrue(new HeaderRuleModule().check(document, RuleSet.schoolV1()).isEmpty());
  }

  @Test
  void doesNotExemptOrdinaryPrefaceContainingFilledStudentFields() {
    for (String introduction : List.of("前言", "本论文毕业设计的信息如下。", "")) {
      var document = document(List.of(section(5, false), section(7, true)), List.of(header(1)),
          introduction, "题目：论文题名", "学生姓名：学生", "学号：123456", "2026年6月", "",
          "摘要", "摘要正文");

      var result = new HeaderRuleModule().check(document, RuleSet.schoolV1());

      assertEquals(1, result.size(), introduction);
      assertEquals(source(5), result.getFirst().source());
    }
  }

  @Test
  void doesNotUseTypeTextAfterStudentFieldsAsCoverEvidence() {
    var document = document(List.of(section(5, false), section(7, true)), List.of(header(1)),
        "题目：论文题名", "学生姓名：学生", "学号：123456", "毕业生本科设计（论文）", "2026年6月", "",
        "摘要", "摘要正文");

    var result = new HeaderRuleModule().check(document, RuleSet.schoolV1());

    assertEquals(1, result.size());
    assertEquals(source(5), result.getFirst().source());
  }

  private ParsedDocument document(List<SectionModel> sections, List<HeaderModel> headers,
      String... texts) {
    return document(sections, headers, List.of(texts));
  }

  private ParsedDocument document(List<SectionModel> sections, List<HeaderModel> headers,
      List<String> texts) {
    var paragraphs = new ArrayList<ParagraphModel>();
    for (int index = 0; index < texts.size(); index++) {
      String text = texts.get(index);
      SourceAddress source = source(index);
      paragraphs.add(new ParagraphModel(List.of(new RunModel(text, "Times New Roman", "宋体",
          null, 24, false, source)), null, null, null, null, null, null,
          source, null, null, "left", null));
    }
    return new ParsedDocument("sha", paragraphs, sections, List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(), headers);
  }

  private HeaderModel header(int sectionIndex) {
    SourceAddress source = new SourceAddress("word/header1.xml", List.of(0), 0, REQUIRED.length());
    return new HeaderModel(source, REQUIRED, true,
        List.of(new HeaderModel.RunFormatting("黑体", "黑体", 21, source)),
        Map.of(HeaderModel.Type.DEFAULT, List.of(sectionIndex)));
  }

  private SectionModel section(int sourceIndex, boolean terminal) {
    return new SectionModel(1474, 1474, 1474, 1474, source(sourceIndex), null, null, terminal);
  }

  private static SourceAddress source(int index) {
    return new SourceAddress("word/document.xml", List.of(0, index), 0, 0);
  }
}
