package cn.luwneshacha.thesis.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.RunModel;
import cn.luwneshacha.thesis.document.SectionModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import cn.luwneshacha.thesis.document.TableModel;
import cn.luwneshacha.thesis.document.ReferenceEntryModel;
import cn.luwneshacha.thesis.document.ImageModel;
import cn.luwneshacha.thesis.document.PageNumberModel;
import cn.luwneshacha.thesis.document.HeadingBreakModel;
import cn.luwneshacha.thesis.document.EquationModel;
import cn.luwneshacha.thesis.document.EquationNumberEvidence;
import cn.luwneshacha.thesis.report.RuleDescriptions;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuleEngineTest {
  @Test
  void skipsMixedSchoolRuleSpecificationDocument() {
    var document = new ParsedDocument("source", List.of(), List.of(),
        List.of("RULE_SPECIFICATION_DOCUMENT"));
    assertTrue(new RuleEngine().check(document, RuleSet.schoolV1()).isEmpty());
  }

  @Test
  void usesWordAutomaticChapterNumberWhenCalculatingTableSequence() {
    var chapterOne = chapterHeading();
    var autoChapter = new ParagraphModel(List.of(new RunModel("软件测试", "Times New Roman", "黑体", null,
        32, false, new SourceAddress("word/document.xml", List.of(0, 2), 0, 4))),
        null, null, null, null, null, null,
        new SourceAddress("word/document.xml", List.of(0, 2), 0, 4), null, 0, "left", "第6章");
    var captionSource = new SourceAddress("word/document.xml", List.of(0, 4), 0, 7);
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(0, 5), 0, 0),
        captionSource, "表6-1 测试表", 2, 2, true, true, true, false, true, false);
    var document = new ParsedDocument("source", List.of(chapterOne, autoChapter,
        paragraph("表6-1 测试表", List.of(0, 4))),
        List.of(new SectionModel(1474, 1474, 1474, 1474, ADDRESS)), List.of(),
        List.of(table), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().noneMatch(result -> result.ruleId().equals("TAB-05")
        && result.source().elementPath().equals(captionSource.elementPath())), results.toString());
  }

  @Test
  void usesChineseWordAutomaticChapterNumberWhenCalculatingTableSequence() {
    var chapterOne = chapterHeading();
    var autoChapter = new ParagraphModel(List.of(new RunModel("软件测试", "Times New Roman", "黑体", null,
        32, false, new SourceAddress("word/document.xml", List.of(0, 2), 0, 4))),
        null, null, null, null, null, null,
        new SourceAddress("word/document.xml", List.of(0, 2), 0, 4), null, 0, "left", "第六章");
    var captionSource = new SourceAddress("word/document.xml", List.of(0, 4), 0, 7);
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(0, 5), 0, 0),
        captionSource, "表6-1 测试表", 2, 2, true, true, true, false, true, false);
    var document = new ParsedDocument("source", List.of(chapterOne, autoChapter,
        paragraph("表6-1 测试表", List.of(0, 4))),
        List.of(new SectionModel(1474, 1474, 1474, 1474, ADDRESS)), List.of(),
        List.of(table), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().noneMatch(result -> result.ruleId().equals("TAB-05")
        && result.source().elementPath().equals(captionSource.elementPath())), results.toString());
  }

  @Test
  void recognizesTableCitationAfterAutomaticNumberedChapterHeading() {
    var chapter = new ParagraphModel(List.of(new RunModel("需求分析", "Times New Roman", "黑体", null,
        32, false, new SourceAddress("word/document.xml", List.of(0, 1), 0, 4))),
        null, null, null, null, null, null,
        new SourceAddress("word/document.xml", List.of(0, 1), 0, 4), null, 0, "left", "3");
    var citation = paragraph("系统用例图如表3-1所示。", List.of(0, 2));
    var captionSource = new SourceAddress("word/document.xml", List.of(0, 3), 0, 9);
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(0, 4), 0, 0),
        captionSource, "表3-1 系统用例规约", 2, 2, true, true, true, false, true, false);
    var document = new ParsedDocument("source", List.of(chapter, citation,
        paragraph("表3-1 系统用例规约", List.of(0, 3))),
        List.of(new SectionModel(1474, 1474, 1474, 1474, ADDRESS)), List.of(),
        List.of(table), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("TAB-01")).findFirst().orElseThrow();

    assertEquals(Judgement.PASS, result.judgement());
  }

  @Test
  void doesNotAcceptTableNumberPrefixAsABodyCitation() {
    var chapter = chapterHeading();
    var citation = paragraph("系统结果如表3-1a所示。", List.of(0, 2));
    var caption = paragraph("表3-1 测试数据", List.of(0, 3));
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(0, 4), 0, 0),
        caption.source(), "表3-1 测试数据", 2, 2, true, true, true, false, true, false);
    var document = new ParsedDocument("source", List.of(chapter, citation, caption),
        List.of(new SectionModel(1474, 1474, 1474, 1474, ADDRESS)), List.of(),
        List.of(table), List.of(), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.reasonCode().equals("TABLE_NOT_CITED_IN_BODY")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
  }

  @Test
  void doesNotAcceptFigureNumberPrefixAsABodyCitation() {
    var chapter = chapterHeading();
    var citation = paragraph("系统结构如图3-1a所示。", List.of(0, 2));
    var caption = paragraph("图3-1 系统结构", List.of(0, 3));
    var image = new ImageModel(new SourceAddress("word/document.xml", List.of(0, 4), 0, 0),
        caption.source(), "图3-1 系统结构", 100, 100, true, true, true);
    var document = new ParsedDocument("source", List.of(chapter, citation, caption),
        List.of(new SectionModel(1474, 1474, 1474, 1474, ADDRESS)), List.of(),
        List.of(), List.of(), List.of(), List.of(image));

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.reasonCode().equals("FIGURE_NOT_CITED_IN_BODY")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
  }

  @Test
  void recognizesReferenceCitationAfterAutomaticNumberedChapterHeading() {
    var chapter = new ParagraphModel(List.of(new RunModel("需求分析", "Times New Roman", "黑体", null,
        32, false, new SourceAddress("word/document.xml", List.of(0, 1), 0, 4))),
        null, null, null, null, null, null,
        new SourceAddress("word/document.xml", List.of(0, 1), 0, 4), null, 0, "left", "第3章");
    var citation = new ParagraphModel(List.of(
        new RunModel("系统设计参考[1]。", "Times New Roman", "宋体", null, 24, false,
            new SourceAddress("word/document.xml", List.of(0, 2), 0, 10))),
        480, null, null, null, 360, "auto",
        new SourceAddress("word/document.xml", List.of(0, 2), 0, 10), 200, null, "left", null);
    var reference = new ReferenceEntryModel(new SourceAddress("word/document.xml", List.of(0, 3), 0, 40),
        "1", "[1] 示例文献[J]", "J", false);
    var document = new ParsedDocument("source", List.of(chapter, citation),
        List.of(new SectionModel(1474, 1474, 1474, 1474, ADDRESS)), List.of(),
        List.of(), List.of(), List.of(reference), List.of(), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.reasonCode().equals("REFERENCE_NOT_CITED")).toList();

    assertTrue(results.isEmpty());
  }

  @Test
  void doesNotTreatZeroPaddedCitationAsTheSameReferenceNumber() {
    var chapter = chapterHeading();
    var citationSource = new SourceAddress("word/document.xml", List.of(0, 2), 0, 11);
    var citation = new ParagraphModel(List.of(
        new RunModel("系统设计参考[01]。", "Times New Roman", "宋体", null, 24, false, citationSource)),
        480, null, null, null, 360, "auto", citationSource, 200, null, "left", null);
    var reference = new ReferenceEntryModel(new SourceAddress("word/document.xml", List.of(0, 3), 0, 20),
        "1", "[1] 示例文献[J]", "J", false);
    var document = new ParsedDocument("source", List.of(chapter, citation),
        List.of(new SectionModel(1474, 1474, 1474, 1474, ADDRESS)), List.of(),
        List.of(), List.of(), List.of(reference), List.of(), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(item -> item.reasonCode().equals("CITED_REFERENCE_MISSING")
        && "01".equals(item.actual())), results.toString());
    assertTrue(results.stream().anyMatch(item -> item.reasonCode().equals("REFERENCE_NOT_CITED")
        && "1".equals(item.actual())), results.toString());
  }

  @Test
  void acceptsCitationRangeWhenEveryNumberHasAReferenceEntryBeyondTheOldLimit() {
    var source = new SourceAddress("word/document.xml", List.of(0, 2), 0, 9);
    var citation = new ParagraphModel(List.of(new RunModel("[1-1001]", "Times New Roman", "宋体",
        null, 24, false, "superscript", source)), 480, null, null, 360, "auto", source, 200);
    var references = java.util.stream.IntStream.rangeClosed(1, 1001)
        .mapToObj(number -> new ReferenceEntryModel(
            new SourceAddress("word/document.xml", List.of(0, number + 10), 0, 12),
            String.valueOf(number), "[" + number + "] 示例[J]", "J", false))
        .toList();
    var document = new ParsedDocument("source", List.of(chapterHeading(), citation), List.of(), List.of(),
        List.of(), List.of(), references);

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().noneMatch(item -> item.reasonCode().equals("CITATION_RANGE_MISMATCH")
        || item.reasonCode().equals("CITATION_RANGE_REVIEW")), results.toString());
    assertTrue(results.stream().noneMatch(item -> item.reasonCode().equals("REFERENCE_NOT_CITED")
        || item.reasonCode().equals("REFERENCE_ORDER_MISMATCH")), results.toString());
  }

  @Test
  void acceptsReferenceCitationNumberBeyondSignedIntRange() {
    var source = new SourceAddress("word/document.xml", List.of(0, 2), 0, 20);
    var citation = new ParagraphModel(List.of(
        new RunModel("Text [2147483648].", "Times New Roman", "宋体", null, 24, false,
            "superscript", source)),
        480, null, null, 360, "auto", source, 200);
    var reference = new ReferenceEntryModel(
        new SourceAddress("word/document.xml", List.of(0, 3), 0, 20),
        "2147483648", "[2147483648] Example[J].", "J", false);
    var document = new ParsedDocument("source", List.of(chapterHeading(), citation), List.of(), List.of(),
        List.of(), List.of(), List.of(reference));

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().noneMatch(item -> item.reasonCode().equals("CITATION_RANGE_MISMATCH")
        || item.reasonCode().equals("CITED_REFERENCE_MISSING")), results.toString());
  }

  @Test
  void rejectsCitationRangeLongerThanTheAvailableReferenceList() {
    var source = new SourceAddress("word/document.xml", List.of(0, 2), 0, 5);
    var citation = new ParagraphModel(List.of(new RunModel("[1-4]", "Times New Roman", "宋体",
        null, 24, false, "superscript", source)), 480, null, null, 360, "auto", source, 200);
    var references = List.of(
        new ReferenceEntryModel(new SourceAddress("word/document.xml", List.of(0, 10), 0, 8),
            "1", "[1] 示例[J]", "J", false),
        new ReferenceEntryModel(new SourceAddress("word/document.xml", List.of(0, 11), 0, 8),
            "2", "[2] 示例[J]", "J", false));
    var document = new ParsedDocument("source", List.of(chapterHeading(), citation), List.of(), List.of(),
        List.of(), List.of(), references);

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.reasonCode().equals("CITATION_RANGE_MISMATCH"))
        .findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
  }
  private static final SourceAddress ADDRESS = new SourceAddress("word/document.xml", List.of(0), 0, 2);

  @Test
  void reportsPassForSchoolV1Formatting() {
    var run = new RunModel("正文A", "Times New Roman", "宋体", null, 24, false, ADDRESS);
    var paragraph = new ParagraphModel(List.of(run), 480, null, null, null, null, ADDRESS, 200);
    var references = new java.util.ArrayList<ReferenceEntryModel>();
    for (int number = 1; number <= 10; number++) {
      references.add(new ReferenceEntryModel(
          new SourceAddress("word/document.xml", List.of(0, 100 + number), 0, 8),
          String.valueOf(number), "[" + number + "] Author. Example[J]. Journal, 2024, 1:1-2.", "J", false,
          "Author", "Example", "Journal", "2024", "1-2"));
    }
    var document = new ParsedDocument("source", List.of(chapterHeading(), paragraph("", List.of(0, 1)), paragraph),
        List.of(new SectionModel(1474, 1474, 1474, 1474, ADDRESS)), List.of(),
        List.of(), List.of(), references);

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().noneMatch(result -> result.ruleId().equals("REF-05")), results.toString());
    assertTrue(results.stream().filter(result -> result.ruleId().equals("PAGE-01")
        || result.ruleId().equals("BODY-FONT") || result.ruleId().equals("BODY-SIZE")
        || result.ruleId().equals("BODY-INDENT")).allMatch(result -> result.judgement() == Judgement.PASS), results.toString());
  }

  @Test
  void reportsFailForWrongMarginAndFontSize() {
    var run = new RunModel("正文A", "Arial", "宋体", null, 22, false, ADDRESS);
    var paragraph = new ParagraphModel(List.of(run), 480, null, null, null, null, ADDRESS, 200);
    var document = new ParsedDocument("source", List.of(chapterHeading(), paragraph),
        List.of(new SectionModel(1418, 1474, 1474, 1474, ADDRESS)), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertEquals(Judgement.FAIL, results.stream().filter(r -> r.ruleId().equals("PAGE-01")).findFirst().orElseThrow().judgement());
    assertTrue(results.stream().anyMatch(r -> r.ruleId().equals("BODY-FONT") && r.judgement() == Judgement.FAIL));
    assertTrue(results.stream().anyMatch(r -> r.ruleId().equals("BODY-SIZE") && r.judgement() == Judgement.FAIL));
  }

  @Test
  void rejectsMarginThatDiffersByOneTwip() {
    var document = new ParsedDocument("source", List.of(chapterHeading()),
        List.of(new SectionModel(1473, 1474, 1474, 1474, ADDRESS)), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("PAGE-01")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("MARGIN_MISMATCH", result.reasonCode());
    assertEquals("1473", result.actual());
  }

  @Test
  void doesNotImposeAnUnspecifiedGutterWidthWhenAllMarginsMatch() {
    var section = new SectionModel(1474, 1474, 1474, 1474, 240, ADDRESS,
        "decimal", false, null, false, false, false);
    var document = new ParsedDocument("source", List.of(chapterHeading()), List.of(section), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("PAGE-01")).findFirst().orElseThrow();

    assertEquals(Judgement.PASS, result.judgement());
  }

  @Test
  void reportsMissingMarginEvidenceWhenDocumentHasNoSections() {
    var document = new ParsedDocument("source", List.of(chapterHeading()), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("PAGE-01")).findFirst().orElseThrow();

    assertEquals(Judgement.REVIEW, result.judgement());
    assertEquals("MARGIN_UNKNOWN", result.reasonCode());
  }

  @Test
  void checksOneAndAHalfLineSpacingForBodyParagraphs() {
    var source = new SourceAddress("word/document.xml", List.of(0, 3), 0, 4);
    var run = new RunModel("正文内容", "Times New Roman", "宋体", null, 24, false, source);
    var wrong = new ParagraphModel(List.of(run), 480, null, null, 240, "auto", source, 200);
    var document = new ParsedDocument("source", List.of(chapterHeading(), wrong), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("BODY-LINE")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("LINE_SPACING_MISMATCH", result.reasonCode());
    assertEquals(source, result.source());
  }

  @Test
  void rejectsLineSpacingThatDiffersByOneTwip() {
    var source = new SourceAddress("word/document.xml", List.of(0, 4), 0, 4);
    var body = new ParagraphModel(List.of(new RunModel("正文", "Times New Roman", "宋体", null,
        24, false, source)), 480, null, null, 359, "auto", source, 200);
    var document = new ParsedDocument("source", List.of(chapterHeading(), body), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("BODY-LINE")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("LINE_SPACING_MISMATCH", result.reasonCode());
  }

  @Test
  void doesNotCheckGlobalUnitNameConsistencyInBody() {
    var firstSource = new SourceAddress("word/document.xml", List.of(0, 3), 0, 5);
    var secondSource = new SourceAddress("word/document.xml", List.of(0, 4), 0, 6);
    var first = new ParagraphModel(List.of(new RunModel("长度为 3cm", "Times New Roman", "宋体",
        null, 24, false, firstSource)), 480, null, null, 360, "auto", firstSource, 200);
    var second = new ParagraphModel(List.of(new RunModel("长度为 4厘米", "Times New Roman", "宋体",
        null, 24, false, secondSource)), 480, null, null, 360, "auto", secondSource, 200);
    var document = new ParsedDocument("source", List.of(chapterHeading(), first, second), List.of(), List.of());

    assertTrue(new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .noneMatch(item -> item.ruleId().equals("BODY-UNIT")));
  }

  @Test
  void checksOneAndAHalfLineSpacingForSecondAndThirdLevelHeadings() {
    var chapterSource = new SourceAddress("word/document.xml", List.of(0, 20), 0, 10);
    var secondLevelSource = new SourceAddress("word/document.xml", List.of(0, 22), 0, 10);
    var thirdLevelSource = new SourceAddress("word/document.xml", List.of(0, 23), 0, 12);
    var chapter = new ParagraphModel(List.of(new RunModel("第二章 研究方法", "Times New Roman", "黑体",
        null, 32, true, chapterSource)), 0, null, null, 240, "auto", chapterSource, 0);
    var secondLevel = new ParagraphModel(List.of(new RunModel("2.1 研究对象", "Times New Roman", "黑体",
        null, 28, true, secondLevelSource)), 0, null, null, 240, "auto", secondLevelSource, 0);
    var thirdLevel = new ParagraphModel(List.of(new RunModel("2.1.1 研究材料", "Times New Roman", "黑体",
        null, 24, true, thirdLevelSource)), 480, null, null, 240, "auto", thirdLevelSource, 200);
    var document = new ParsedDocument("source", List.of(chapter, paragraph("", List.of(0, 21)), secondLevel,
        thirdLevel), List.of(), List.of());

    var lineSpacingResults = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.ruleId().equals("HEADING-LINE")).toList();

    assertEquals(2, lineSpacingResults.size());
    assertEquals(List.of(secondLevelSource, thirdLevelSource),
        lineSpacingResults.stream().map(result -> result.source()).toList());
  }

  @Test
  void rejectsOnePointTwoLineSpacingForThirdLevelHeadings() {
    var source = new SourceAddress("word/document.xml", List.of(0, 23), 0, 12);
    var thirdLevel = new ParagraphModel(List.of(new RunModel("2.1.1 研究材料", "Times New Roman", "黑体",
        null, 24, true, source)), 480, null, null, 0, 288, "auto", source, 0, null, "left");
    var document = new ParsedDocument("source", List.of(chapterHeading(), thirdLevel), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("HEADING-LINE") && item.source().equals(source))
        .findFirst().orElseThrow();
    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("288 / auto", result.actual());
    assertEquals("LINE_SPACING_MISMATCH", result.reasonCode());
  }

  @Test
  void checksLineSpacingForCodeParagraphsInsideTheMainBody() {
    var source = new SourceAddress("word/document.xml", List.of(0, 4), 0, 14);
    var code = new ParagraphModel(List.of(new RunModel("int value = 1;", "Consolas", "宋体", null,
        20, false, source)), 0, null, null, 240, "auto", source, 0);
    var document = new ParsedDocument("source", List.of(chapterHeading(), code), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("BODY-LINE") && source.equals(item.source()))
        .findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("LINE_SPACING_MISMATCH", result.reasonCode());
  }

  @Test
  void doesNotTreatTocChapterEntryAsBodyHeading() {
    var contentsSource = new SourceAddress("word/document.xml", List.of(0, 40), 0, 2);
    var tocSource = new SourceAddress("word/document.xml", List.of(0, 42), 0, 12);
    var bodySource = new SourceAddress("word/document.xml", List.of(0, 44), 0, 8);
    var tocEntry = paragraph("第一章 引言\t1", List.of(0, 42));
    var body = new ParagraphModel(List.of(new RunModel("正文", "Arial", "宋体", null,
        18, false, bodySource)), 0, null, null, 288, "auto", bodySource, 0, null, "left");
    var document = new ParsedDocument("source", List.of(
        paragraph("目 录", List.of(0, 40)), paragraph("", List.of(0, 41)),
        tocEntry, body), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().noneMatch(item -> item.source().equals(tocSource)
        && !item.ruleId().startsWith("TOC-")), results.toString());
    assertTrue(results.stream().noneMatch(item -> item.source().equals(bodySource)
        && (item.ruleId().equals("HEAD-02-BLANK") || item.ruleId().equals("BODY-INDENT"))),
        results.toString());
  }

  @Test
  void marksSecondLevelHeadingLineSpacingForReviewWhenEvidenceIsMissing() {
    var source = new SourceAddress("word/document.xml", List.of(0, 22), 0, 10);
    var secondLevel = new ParagraphModel(List.of(new RunModel("2.1 研究对象", "Times New Roman", "黑体",
        null, 28, true, source)), 0, null, null, null, null, source, 0, null, "left");
    var document = new ParsedDocument("source", List.of(chapterHeading(), secondLevel), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("HEADING-LINE") && item.source().equals(source))
        .findFirst().orElseThrow();

    assertEquals(Judgement.REVIEW, result.judgement());
    assertEquals("LINE_SPACING_UNKNOWN", result.reasonCode());
  }

  @Test
  void failsSecondLevelHeadingWhenLineSpacingIsOnePointTwoTimes() {
    var source = new SourceAddress("word/document.xml", List.of(0, 22), 0, 10);
    var secondLevel = new ParagraphModel(List.of(new RunModel("2.1 研究对象", "Times New Roman", "黑体",
        null, 28, true, source)), 0, null, null, 288, "auto", source, 0, null, "left");
    var document = new ParsedDocument("source", List.of(chapterHeading(), secondLevel), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("HEADING-LINE") && item.source().equals(source))
        .findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("LINE_SPACING_MISMATCH", result.reasonCode());
    assertEquals("288 / auto", result.actual());
  }

  @Test
  void checksFontSizeIndentAndLineSpacingForThirdLevelHeadings() {
    var source = new SourceAddress("word/document.xml", List.of(0, 22), 0, 10);
    var thirdLevel = new ParagraphModel(List.of(new RunModel("2.1.1 小节", "Arial", "宋体",
        null, 18, false, source)), 0, null, null, null, null, source, 0, null, "left");
    var document = new ParsedDocument("source", List.of(chapterHeading(), thirdLevel), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(item -> item.source().equals(source)
        && item.ruleId().equals("HEADING-FONT") && item.judgement() == Judgement.FAIL));
    assertTrue(results.stream().anyMatch(item -> item.source().equals(source)
        && item.ruleId().equals("HEADING-SIZE") && item.judgement() == Judgement.FAIL));
    assertTrue(results.stream().anyMatch(item -> item.source().equals(source)
        && item.ruleId().equals("HEAD-03-INDENT") && item.judgement() == Judgement.FAIL));
    assertTrue(results.stream().anyMatch(item -> item.source().equals(source)
        && item.ruleId().equals("HEADING-LINE") && item.judgement() == Judgement.REVIEW));
  }

  @Test
  void checksOneAndAHalfLineSpacingForContentsTitleButNotOtherNamedTitles() {
    var contentsSource = new SourceAddress("word/document.xml", List.of(0, 30), 0, 5);
    var abstractSource = new SourceAddress("word/document.xml", List.of(0, 31), 0, 2);
    var referencesSource = new SourceAddress("word/document.xml", List.of(0, 32), 0, 4);
    var acknowledgementSource = new SourceAddress("word/document.xml", List.of(0, 33), 0, 6);
    var appendixSource = new SourceAddress("word/document.xml", List.of(0, 34), 0, 6);
    var contents = titleParagraph("目    录", contentsSource);
    var abstractTitle = titleParagraph("摘要", abstractSource);
    var references = titleParagraph("参考文献", referencesSource);
    var acknowledgement = titleParagraph("致    谢", acknowledgementSource);
    var appendix = titleParagraph("附    录", appendixSource);
    var document = new ParsedDocument("source", List.of(contents, abstractTitle, references,
        acknowledgement, appendix), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(result -> result.ruleId().equals("TOC-01-LINE")
        && result.judgement() == Judgement.FAIL));
    assertTrue(results.stream().noneMatch(result -> List.of("ZH-01-LINE", "REF-01-LINE", "END-01-LINE", "APP-02-LINE")
        .contains(result.ruleId())));
  }

  @Test
  void marksContentsTitleLineSpacingForReviewWhenEvidenceIsMissing() {
    var source = new SourceAddress("word/document.xml", List.of(0, 30), 0, 5);
    var contents = new ParagraphModel(List.of(new RunModel("目    录", "Times New Roman", "黑体",
        null, 32, false, source)), 0, null, null, null, null, source, 0, null, "center");
    var document = new ParsedDocument("source", List.of(contents), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("TOC-01-LINE")).findFirst().orElseThrow();

    assertEquals(Judgement.REVIEW, result.judgement());
    assertEquals("LINE_SPACING_UNKNOWN", result.reasonCode());
  }

  @Test
  void checksFontSizeOnWhitespaceRunsInsideContentsTitle() {
    var source = new SourceAddress("word/document.xml", List.of(0, 31), 0, 5);
    var title = new ParagraphModel(List.of(
        new RunModel("目", "Times New Roman", "黑体", null, 32, false, source),
        new RunModel("    ", "Times New Roman", "黑体", null, 24, false, source),
        new RunModel("录", "Times New Roman", "黑体", null, 32, false, source)),
        0, null, null, null, 360, "auto", source, 0, null, "center");
    var document = new ParsedDocument("source", List.of(title), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("TOC-01-SIZE")
            && item.reasonCode().equals("SIZE_MISMATCH"))
        .findFirst().orElse(null);

    assertTrue(result != null);
  }

  @Test
  void checksFontAndSizeOnWhitespaceRunsInsideAbstractParagraphs() {
    var titleSource = new SourceAddress("word/document.xml", List.of(0, 32), 0, 2);
    var bodySource = new SourceAddress("word/document.xml", List.of(0, 34), 0, 4);
    var abstractBody = new ParagraphModel(List.of(
        new RunModel("论", "Times New Roman", "宋体", null, 24, false, bodySource),
        new RunModel(" ", "Times New Roman", "宋体", null, 18, false, bodySource),
        new RunModel("文", "Times New Roman", "宋体", null, 24, false, bodySource)),
        480, null, null, null, 360, "auto", bodySource, 200, null, "both");
    var document = new ParsedDocument("source", List.of(
        titleParagraph("摘    要", titleSource), paragraph("", List.of(0, 33)), abstractBody),
        List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("ABSTRACT-SIZE")
        && item.reasonCode().equals("SIZE_MISMATCH")
        && item.source().equals(bodySource)), results.toString());
  }

  @Test
  void acceptsTwoSpacesAfterChapterNumberButRejectsTwoAfterNumericContentsNumber() {
    var contents = paragraph("目    录", List.of(0, 60));
    var blank = paragraph("", List.of(0, 61));
    var chapterSource = new SourceAddress("word/document.xml", List.of(0, 62), 0, 13);
    var chapter = new ParagraphModel(List.of(new RunModel("第一章  引言……1", "Times New Roman", "宋体",
        null, 24, false, chapterSource)), null, null, null, null, 360, "auto", chapterSource, 200, null, "left");
    var subsection = paragraph("2.1  线性表定义……2", List.of(0, 63));
    var document = new ParsedDocument("source", List.of(contents, blank, chapter, subsection),
        List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.ruleId().equals("TOC-SPACING")).toList();

    assertEquals(1, results.size(), results.toString());
    assertEquals(List.of(0, 63), results.get(0).source().elementPath());
    assertEquals("TOC_NUMBER_SPACING_MISMATCH", results.get(0).reasonCode());
    assertEquals("编号后空 1 格", results.get(0).expected());
  }

  @Test
  void doesNotCheckNumberSpacingForUnnumberedEndMatterContentsEntries() {
    var contents = paragraph("\u76ee\u5f55", List.of(0, 64));
    var blank = paragraph("", List.of(0, 65));
    var entries = List.of(
        paragraph("\u53c2\u8003\u6587\u732e\t12", List.of(0, 66)),
        paragraph("\u81f4\u8c22\t13", List.of(0, 67)),
        paragraph("\u9644\u5f55A\t14", List.of(0, 68)));
    var document = new ParsedDocument("source",
        List.of(contents, blank, entries.get(0), entries.get(1), entries.get(2)), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.ruleId().equals("TOC-SPACING"))
        .toList();

    assertTrue(results.isEmpty(), results.toString());
  }

  @Test
  void checksChapterAndNumericContentsEntriesByTheirActualNumberPrefix() {
    var contents = paragraph("\u76ee\u5f55", List.of(0, 69));
    var blank = paragraph("", List.of(0, 70));
    var chapter = paragraph("\u7b2c\u4e00\u7ae0  \u5f15\u8a00\t1", List.of(0, 71));
    var subsection = paragraph("2.1  \u5b9a\u4e49\t2", List.of(0, 72));
    var document = new ParsedDocument("source", List.of(contents, blank, chapter, subsection), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.ruleId().equals("TOC-SPACING")
            && result.judgement() == Judgement.FAIL)
        .toList();

    assertEquals(1, results.size(), results.toString());
    assertEquals(List.of(0, 72), results.get(0).source().elementPath());
  }

  @Test
  void usesExplicitThreePartNumberBeforeCopiedOutlineLevel() {
    var source = new SourceAddress("word/document.xml", List.of(0, 7), 0, 12);
    var run = new RunModel("7.2.2 \u6267\u884c\u5668\u9a71\u52a8\u6d4b\u8bd5", "Times New Roman", "\u9ed1\u4f53",
        null, 24, true, source);
    // outlineLevel=1 is a common stale value after copying a heading style;
    // the visible three-part number is authoritative.
    var heading = new ParagraphModel(List.of(run), null, null, null, null, 420, "auto", source,
        null, 1, "left");

    var results = new RuleEngine().check(new ParsedDocument("source", List.of(heading), List.of(), List.of()),
        RuleSet.schoolV1());

    assertTrue(results.stream().noneMatch(result -> result.ruleId().equals("HEADING-SIZE")
        && result.expected().equals("28")), results.toString());
    assertTrue(results.stream().anyMatch(result -> result.ruleId().equals("HEAD-03-SPACING")),
        results.toString());
  }

  @Test
  void checksLineSpacingForBodyParagraphWithStaleOutlineLevel() {
    var source = new SourceAddress("word/document.xml", List.of(0, 8), 0, 20);
    var bodyRun = new RunModel("普通正文内容", "Times New Roman", "宋体", null,
        24, false, source);
    // This paragraph inherited outlineLevel=1 from a copied style, but it has
    // no visible heading number and is ordinary body text.
    var body = new ParagraphModel(List.of(bodyRun), null, null, null, null,
        0, "exact", source, null, 1, "left");
    var document = new ParsedDocument("source", List.of(chapterHeading(), body), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("BODY-LINE")
            && item.source().elementPath().equals(source.elementPath()))
        .findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("LINE_SPACING_MISMATCH", result.reasonCode());
    assertEquals("360 / auto", result.expected());
    assertEquals("0 / exact", result.actual());
  }

  @Test
  void doesNotTreatUnlabelledAutomaticNumberingAsAHeading() {
    var source = new SourceAddress("word/document.xml", List.of(0, 9), 0, 20);
    var run = new RunModel("普通正文", "Times New Roman", "宋体", null, 24, false, source);
    var body = new ParagraphModel(List.of(run), null, null, null, null, 360, "auto", source,
        null, 1, "left", null);

    var result = new RuleEngine().check(new ParsedDocument("source", List.of(chapterHeading(), body),
        List.of(), List.of()), RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("BODY-LINE")
            && item.source().elementPath().equals(source.elementPath()))
        .findFirst().orElseThrow();

    assertEquals(Judgement.PASS, result.judgement());
  }

  @Test
  void startsBodyLineSpacingChecksAfterUnnumberedIntroductionHeading() {
    var intro = paragraph("绪论", List.of(0, 10));
    var source = new SourceAddress("word/document.xml", List.of(0, 11), 0, 20);
    var body = new ParagraphModel(List.of(new RunModel("正文段落", "Times New Roman", "宋体", null,
        24, false, source)), 480, null, null, 360, "auto", source, null, null, "left");
    var document = new ParsedDocument("source", List.of(intro, body), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("BODY-LINE")
            && item.source().elementPath().equals(source.elementPath()))
        .findFirst().orElseThrow();

    assertEquals(Judgement.PASS, result.judgement());
  }

  @Test
  void acceptsTwoSpacesAfterFirstLevelContentsNumber() {
    var contents = paragraph("目    录", List.of(0, 70));
    var blank = paragraph("", List.of(0, 71));
    var chapter = paragraph("第一章  引言……1", List.of(0, 72));
    var document = new ParsedDocument("source", List.of(contents, blank, chapter), List.of(), List.of());

    var findings = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("TOC-SPACING"))
        .toList();

    assertTrue(findings.isEmpty(), findings.toString());
  }

  @Test
  void rejectsOneSpaceAfterFirstLevelContentsNumberWithReadableSuggestion() {
    var contents = paragraph("目    录", List.of(0, 70));
    var blank = paragraph("", List.of(0, 71));
    var chapter = paragraph("第一章 引言……1", List.of(0, 72));
    var document = new ParsedDocument("source", List.of(contents, blank, chapter), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("TOC-SPACING"))
        .findFirst().orElseThrow();

    assertEquals("编号后空 2 格", result.expected());
    assertEquals("TOC_NUMBER_SPACING_MISMATCH", result.reasonCode());
    assertEquals("目录编号后空格不符合要求", RuleDescriptions.reason(result));
    assertTrue(RuleDescriptions.suggestion(result).contains("补 1 个"));
    assertTrue(RuleDescriptions.suggestion(result).contains("2 个普通半角空格"));
  }

  @Test
  void rejectsWhitespaceInsideChapterNumberInContentsEntry() {
    var contents = paragraph("目录", List.of(0, 73));
    var blank = paragraph("", List.of(0, 74));
    var chapter = paragraph("第 二章 引言……1", List.of(0, 75));
    var document = new ParsedDocument("source", List.of(contents, blank, chapter), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("TOC-SPACING"))
        .findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("TOC_NUMBER_SPACING_MISMATCH", result.reasonCode());
  }

  @Test
  void contentsChapterSeparatorRequiresExactlyTwoOrdinarySpacesAcrossRuns() {
    for (String separator : List.of("  ", "", " ", "   ", "\u3000", "\u00a0\u00a0",
        "\u202f\u202f", " \u200b ", "\t")) {
      var address = new SourceAddress("word/document.xml", List.of(0, 82), 0, 20);
      var entry = new ParagraphModel(List.of(
          new RunModel("第二章", "Times New Roman", "宋体", null, 24, false, address),
          new RunModel(separator, "Times New Roman", "宋体", null, 24, false, address),
          new RunModel("相关技术介绍\t5", "Times New Roman", "宋体", null, 24, false, address)),
          0, null, null, null, 360, "auto", address, 0, null, "left");
      var document = new ParsedDocument("source", List.of(paragraph("目录", List.of(0, 80)),
          paragraph("", List.of(0, 81)), entry), List.of(), List.of());
      boolean rejected = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
          .anyMatch(result -> result.ruleId().equals("TOC-SPACING") && result.judgement() == Judgement.FAIL);
      assertEquals(!"  ".equals(separator), rejected, "separator=" + separator);
    }
  }

  @Test
  void explainsNonStandardTocSeparatorInsteadOfReportingOnlyZeroSpaces() {
    var contents = paragraph("\u76ee\u5f55", List.of(0, 76));
    var blank = paragraph("", List.of(0, 77));
    var chapter = paragraph("\u7b2c\u4e00\u7ae0\t\u5f15\u8a00\u2026\u20264", List.of(0, 78));
    var document = new ParsedDocument("source", List.of(contents, blank, chapter), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("TOC-SPACING"))
        .findFirst().orElseThrow();

    assertTrue(result.actual().contains("制表符"), result.actual());
  }

  @Test
  void acceptsExactContentsCharacterIndentEvidence() {
    var contents = paragraph("目录", List.of(0, 90));
    var blank = paragraph("", List.of(0, 91));
    var chapterSource = new SourceAddress("word/document.xml", List.of(0, 92), 0, 13);
    var chapter = new ParagraphModel(List.of(new RunModel("第一章 引言……1", "Times New Roman", "宋体",
        null, 24, false, chapterSource)), null, null, 0, null, 360, "auto", chapterSource,
        0, null, "left");
    var levelTwoSource = new SourceAddress("word/document.xml", List.of(0, 93), 0, 13);
    var levelTwo = new ParagraphModel(List.of(new RunModel("2.1 研究方法……2", "Times New Roman", "宋体",
        null, 24, false, levelTwoSource)), null, null, 200, null, 360, "auto", levelTwoSource,
        null, null, "left");
    var levelThreeSource = new SourceAddress("word/document.xml", List.of(0, 94), 0, 13);
    var levelThree = new ParagraphModel(List.of(new RunModel("2.1.1 研究范围……3", "Times New Roman", "宋体",
        null, 24, false, levelThreeSource)), null, null, 400, null, 360, "auto", levelThreeSource,
        null, null, "left");
    var document = new ParsedDocument("source", List.of(contents, blank, chapter, levelTwo, levelThree),
        List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("TOC-ENTRY-INDENT"))
        .toList();

    assertEquals(3, results.size(), results.toString());
    assertTrue(results.stream().allMatch(result -> result.judgement() == Judgement.PASS), results.toString());
  }

  @Test
  void acceptsFlushLeftFirstLevelContentsEntries() {
    var contents = paragraph("目录", List.of(0, 90));
    var blank = paragraph("", List.of(0, 91));
    var source = new SourceAddress("word/document.xml", List.of(0, 92), 0, 17);
    var chapter = new ParagraphModel(List.of(new RunModel("第二章 相关技术介绍……5", "Times New Roman", "宋体",
        null, 24, false, source)), null, null, 0, null, 360, "auto", source, 0, null, "left");
    var document = new ParsedDocument("source", List.of(contents, blank, chapter), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("TOC-ENTRY-INDENT"))
        .toList();

    assertEquals(1, results.size(), results.toString());
    assertEquals(Judgement.PASS, results.get(0).judgement());
  }

  @Test
  void rejectsWrongLeftCharacterIndentEvenWhenTwipEvidenceIsPresent() {
    var contents = paragraph("目录", List.of(0, 93));
    var blank = paragraph("", List.of(0, 94));
    var source = new SourceAddress("word/document.xml", List.of(0, 95), 0, 15);
    var chapter = new ParagraphModel(List.of(new RunModel("第一章 引言……2", "Times New Roman", "宋体",
        null, 24, false, source)), 0, 420, 199, null, null, null, source, 0, null, "left", null);
    var document = new ParsedDocument("source", List.of(contents, blank, chapter), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("TOC-ENTRY-INDENT"))
        .toList();

    assertEquals(1, results.size(), results.toString());
    assertEquals(Judgement.FAIL, results.get(0).judgement());
    assertEquals("INDENT_MISMATCH", results.get(0).reasonCode());
  }

  @Test
  void doesNotTreatVisibleFieldKeywordAsGeneratedTocParagraph() {
    var chapter = chapterHeading();
    var blank = paragraph("", List.of(0, 1));
    var body = paragraph("正文中的 HYPERLINK 一词不代表目录字段。", List.of(0, 2));
    var document = new ParsedDocument("source", List.of(chapter, blank, body), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.source().elementPath().equals(body.source().elementPath())
            && result.ruleId().startsWith("BODY-")).toList();

    assertTrue(results.stream().anyMatch(result -> result.ruleId().equals("BODY-INDENT")
        && result.judgement() == Judgement.FAIL), results.toString());
    assertTrue(results.stream().anyMatch(result -> result.ruleId().equals("BODY-FONT")
        && result.judgement() == Judgement.FAIL), results.toString());
  }

  @Test
  void checksFormattingOfGeneratedTocEntries() {
    var contents = paragraph("目录", List.of(0, 90));
    var blank = paragraph("", List.of(0, 91));
    var source = new SourceAddress("word/document.xml", List.of(0, 92), 0, 14);
    var entry = new ParagraphModel(List.of(new RunModel("第一章  引言……1", "Times New Roman", "宋体",
        null, 24, false, source)), null, null, 0, null, 360, "auto", source, 0, null, "left",
        null, null, null, true);
    var document = new ParsedDocument("source", List.of(contents, blank, entry), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.source().elementPath().equals(source.elementPath()))
        .toList();

    assertTrue(results.stream().noneMatch(result -> result.reasonCode().equals("TOC_NUMBER_SPACING_MISMATCH")),
        results.toString());
    assertTrue(results.stream().noneMatch(result -> result.ruleId().equals("TOC-ENTRY-INDENT")
        && result.judgement() != Judgement.PASS), results.toString());
    assertTrue(results.stream().noneMatch(result -> result.reasonCode().equals("TOC_CHAPTER_INDENT_MISMATCH")),
        results.toString());
  }

  private static ParagraphModel titleParagraph(String text, SourceAddress source) {
    return new ParagraphModel(List.of(new RunModel(text, "Times New Roman", "黑体", null, 32, false, source)),
        0, null, null, 240, "auto", source, 0, null, "center");
  }

  @Test
  void failsRequiredIndentWhenXmlHasNoIndentEvidence() {
    var run = new RunModel("正文", "Times New Roman", "宋体", null, 24, false, ADDRESS);
    var paragraph = new ParagraphModel(List.of(run), null, null, null, null, null, ADDRESS, null);
    var document = new ParsedDocument("source", List.of(chapterHeading(), paragraph),
        List.of(new SectionModel(1474, 1474, 1474, 1474, ADDRESS)), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    var result = results.stream().filter(r -> r.ruleId().equals("BODY-INDENT")).findFirst().orElseThrow();
    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("INDENT_MISMATCH", result.reasonCode());
  }

  @Test
  void doesNotApplyBodyRulesToNumberedHeadingWithoutRequiredSpace() {
    var source = new SourceAddress("word/document.xml", List.of(0, 10), 0, 7);
    var heading = new ParagraphModel(List.of(
        new RunModel("3.6本章小结", "Times New Roman", "黑体", null, 28, true, source)),
        0, null, null, null, null, source, 0);
    var document = new ParsedDocument("source", List.of(chapterHeading(), heading), List.of(), List.of());

    var bodyResults = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.ruleId().startsWith("BODY-")).toList();

    assertTrue(bodyResults.isEmpty());
  }

  @Test
  void doesNotApplyBodyRulesToContinuationTableCaption() {
    var source = new SourceAddress("word/document.xml", List.of(0, 10), 0, 5);
    var continuationCaption = new ParagraphModel(List.of(
        new RunModel("续表4-7", "Times New Roman", "宋体", null, 21, false, source)),
        0, null, null, null, null, source, 0);
    var document = new ParsedDocument("source", List.of(chapterHeading(), continuationCaption), List.of(), List.of());

    var bodyResults = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.ruleId().startsWith("BODY-")).toList();

    assertTrue(bodyResults.isEmpty());
  }

  @Test
  void checksWhitespaceOnlyRunsWhenCheckingHeadingSize() {
    var source = new SourceAddress("word/document.xml", List.of(0, 10), 0, 8);
    var heading = new ParagraphModel(List.of(
        new RunModel("5.1", "Times New Roman", "黑体", null, 28, true, source),
        new RunModel(" ", "Times New Roman", "黑体", null, 32, false, source),
        new RunModel("总体流程", "Times New Roman", "黑体", null, 28, true, source)),
        0, null, null, null, null, source, 0);
    var document = new ParsedDocument("source", List.of(chapterHeading(), heading), List.of(), List.of());

    var sizeFailures = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.ruleId().equals("HEADING-SIZE") && result.judgement() == Judgement.FAIL).toList();

    assertTrue(sizeFailures.stream().anyMatch(result -> result.reasonCode().equals("SIZE_MISMATCH")
        && result.source().equals(source)), sizeFailures.toString());
  }

  @Test
  void failsWhenContinuationTableMissesRequiredThreeLineBorders() {
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(0, 11), 0, 0),
        ADDRESS, "续表4-7", 2, 2, false, true, false, false, true, false);
    var document = new ParsedDocument("source", List.of(), List.of(), List.of(),
        List.of(table), List.of(), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("TAB-02")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("TABLE_CONTINUATION_BORDER_INVALID", result.reasonCode());
  }

  @Test
  void marksPageNumberFontForReviewWhenEffectiveStyleIsMissing() {
    var heading = chapterHeading();
    var page = new PageNumberModel(new SourceAddress("word/footer1.xml", List.of(), 0, 0), true,
        true, null, 21);
    var document = new ParsedDocument("source", List.of(heading), List.of(
        new SectionModel(1474, 1474, 1474, 1474, heading.source(), "decimal", null)), List.of(),
        List.of(), List.of(), List.of(), List.of(), List.of(page));

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("PAGE-05")).findFirst().orElseThrow();

    assertEquals(Judgement.REVIEW, result.judgement());
    assertEquals("PAGE_NUMBER_FONT_UNKNOWN", result.reasonCode());
  }

  @Test
  void marksPageNumberAlignmentForReviewWhenEffectiveStyleIsMissing() {
    var heading = chapterHeading();
    var page = new PageNumberModel(new SourceAddress("word/footer1.xml", List.of(), 0, 0), true,
        null, "Times New Roman", 21);
    var document = new ParsedDocument("source", List.of(heading), List.of(
        new SectionModel(1474, 1474, 1474, 1474, heading.source(), "decimal", null)), List.of(),
        List.of(), List.of(), List.of(), List.of(), List.of(page));

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("PAGE-05")
            && item.reasonCode().equals("PAGE_NUMBER_ALIGNMENT_UNKNOWN")).findFirst().orElseThrow();

    assertEquals(Judgement.REVIEW, result.judgement());
  }

  @Test
  void marksPageNumberSizeForReviewWhenEffectiveStyleIsMissing() {
    var heading = chapterHeading();
    var page = new PageNumberModel(new SourceAddress("word/footer1.xml", List.of(), 0, 0), true,
        true, "Times New Roman", null);
    var document = new ParsedDocument("source", List.of(heading), List.of(
        new SectionModel(1474, 1474, 1474, 1474, heading.source(), "decimal", null)), List.of(),
        List.of(), List.of(), List.of(), List.of(), List.of(page));

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("PAGE-05")).findFirst().orElseThrow();

    assertEquals(Judgement.REVIEW, result.judgement());
    assertEquals("PAGE_NUMBER_SIZE_UNKNOWN", result.reasonCode());
  }

  @Test
  void checksFontAndSizeOfEveryRunInPageNumberFieldResult() {
    var page = new PageNumberModel(new SourceAddress("word/footer1.xml", List.of(), 0, 0), true,
        true, "Times New Roman", 21, List.of(), List.of(
            new PageNumberModel.RunFormatting("Times New Roman", 21),
            new PageNumberModel.RunFormatting("Arial", 22)));
    var heading = chapterHeading();
    var section = new SectionModel(1474, 1474, 1474, 1474, heading.source(), "decimal", null);
    var document = new ParsedDocument("source", List.of(heading), List.of(section), List.of(),
        List.of(), List.of(), List.of(), List.of(), List.of(page));

    var findings = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.ruleId().equals("PAGE-05")).toList();

    assertTrue(findings.stream().anyMatch(result -> result.reasonCode().equals("PAGE_NUMBER_FONT_MISMATCH")
        && result.actual().equals("Arial")), findings.toString());
    assertTrue(findings.stream().anyMatch(result -> result.reasonCode().equals("PAGE_NUMBER_SIZE_MISMATCH")
        && result.actual().equals("22")), findings.toString());
  }

  @Test
  void acceptsTwoCharacterIndentStoredAsTwips() {
    var run = new RunModel("正文", "Times New Roman", "宋体", null, 24, false, ADDRESS);
    var paragraph = new ParagraphModel(List.of(run), 480, null, null, null, null, ADDRESS, null);
    var document = new ParsedDocument("source", List.of(chapterHeading(), paragraph),
        List.of(new SectionModel(1474, 1474, 1474, 1474, ADDRESS)), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(r -> r.ruleId().equals("BODY-INDENT")).findFirst().orElseThrow();

    assertEquals(Judgement.PASS, result.judgement());
  }

  @Test
  void doesNotApplyBodyRulesToReferenceEntriesAndHeadings() {
    var run = new RunModel("[1] Example[M].", "Arial", "黑体", null, 18, false, ADDRESS);
    var heading = new ParagraphModel(List.of(run), 0, null, null, null, null, ADDRESS, 0);
    var document = new ParsedDocument("source", List.of(heading), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().noneMatch(result -> result.ruleId().startsWith("BODY-")));
  }

  @Test
  void doesNotApplyBodyFormattingRulesInsideTableCells() {
    var tableSource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 0);
    var cellSource = new SourceAddress("word/document.xml", List.of(0, 1, 0, 0, 0), 0, 4);
    var run = new RunModel("表格内容", "Arial", "黑体", null, 18, false, cellSource);
    var cellParagraph = new ParagraphModel(List.of(run), 0, null, null, null, null, cellSource, 0);
    var table = new TableModel(tableSource, null, null, 1, 1, true, true, true, false, true, false);
    var document = new ParsedDocument("source", List.of(cellParagraph), List.of(), List.of(), List.of(table),
        List.of(), List.of(), List.of(), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.ruleId().startsWith("BODY-")).toList();

    assertTrue(results.isEmpty());
  }

  @Test
  void appliesBodyRulesOnlyBetweenFirstChapterAndEndMatter() {
    var cover = paragraph("论文题目", List.of(0, 0));
    var abstractTitle = paragraph("摘  要", List.of(0, 1));
    var tocEntry = paragraph("第1章 绪论 ........ 1", List.of(0, 2));
    var firstChapter = paragraph("第1章 绪论", List.of(0, 3));
    var body = paragraph("正文内容", List.of(0, 4));
    var references = paragraph("REFERENCES", List.of(0, 5));
    var referenceText = paragraph("文献条目内容", List.of(0, 6));
    var acknowledgement = paragraph("致谢", List.of(0, 7));
    var afterBody = paragraph("感谢指导老师", List.of(0, 8));
    var document = new ParsedDocument("source", List.of(cover, abstractTitle, tocEntry, firstChapter, body,
        references, referenceText, acknowledgement, afterBody), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.ruleId().startsWith("BODY-") && result.judgement() != Judgement.PASS).toList();

    assertEquals(4, results.size());
    assertTrue(results.stream().allMatch(result -> result.source().elementPath().equals(List.of(0, 4))));
  }

  @Test
  void doesNotEndMainBodyWhenParagraphMentionsAppendixByName() {
    var mention = paragraphWithoutOutline("附录A的实验结果见下文。", List.of(0, 1));
    var followingBody = paragraph("后续正文内容", List.of(0, 2));
    var document = new ParsedDocument("source", List.of(chapterHeading(), mention, followingBody),
        List.of(), List.of());

    var bodyFindings = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.ruleId().startsWith("BODY-")
            && result.source().elementPath().equals(followingBody.source().elementPath()))
        .toList();

    assertTrue(bodyFindings.stream().anyMatch(result -> result.ruleId().equals("BODY-INDENT")),
        bodyFindings.toString());
  }

  private static ParagraphModel paragraph(String text, List<Integer> path) {
    var source = new SourceAddress("word/document.xml", path, 0, text.length());
    var run = new RunModel(text, "Arial", "黑体", null, 18, false, source);
    return new ParagraphModel(List.of(run), 0, null, null, null, null, source, 0);
  }

  private static ParagraphModel paragraphWithoutOutline(String text, List<Integer> path) {
    var source = new SourceAddress("word/document.xml", path, 0, text.length());
    var run = new RunModel(text, "Arial", "宋体", null, 24, false, source);
    return new ParagraphModel(List.of(run), null, null, null, null, null, source, null);
  }

  private static ParagraphModel chapterHeading() {
    var source = new SourceAddress("word/document.xml", List.of(0, 0), 0, 7);
    var run = new RunModel("第1章  绪论", "Times New Roman", "黑体", null, 32, true, source);
    return new ParagraphModel(List.of(run), 0, null, null, null, null, source, 0);
  }

  @Test
  void checksBodyFormattingForDeeplyNestedParagraphsOutsideTables() {
    var body = paragraph("这是嵌套在内容控件中的正文", List.of(0, 1, 0, 0, 0, 0));
    var document = new ParsedDocument("source", List.of(chapterHeading(), body), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(result -> result.ruleId().equals("BODY-INDENT")
        && result.source().elementPath().equals(body.source().elementPath())), results.toString());
  }

  @Test
  void ignoresOneRowXmlFragmentBeforeContinuationCaption() {
    var originalCaption = paragraph("\u88684-3 \u793e\u533a\u5e16\u5b50\u8868", List.of(0, 2));
    var continuationCaption = paragraph("\u7eed\u88684-3", List.of(0, 8));
    var headers = List.of("\u5b57\u6bb5\u540d", "\u5b57\u6bb5\u7c7b\u578b", "\u4e3b\u952e", "\u63cf\u8ff0");
    var original = new TableModel(new SourceAddress("word/document.xml", List.of(0, 3), 0, 0),
        originalCaption.source(), "\u88684-3 \u793e\u533a\u5e16\u5b50\u8868", 5, 4, true, true, true,
        false, true, false, false, List.of(), List.of(), headers, null, null, false, false);
    var fragment = new TableModel(new SourceAddress("word/document.xml", List.of(0, 6), 0, 0),
        null, null, 1, 4, false, false, false, false, false, false, false, List.of(), List.of(),
        List.of("video_url", "varchar(500)", "\u5426", "\u89c6\u9891\u5730\u5740"), null, null, false, false);
    var continuation = new TableModel(new SourceAddress("word/document.xml", List.of(0, 10), 0, 0),
        continuationCaption.source(), "\u7eed\u88684-3", 5, 4, true, true, true, false, true,
        false, false, List.of(), List.of(), headers, null, null, false, false);
    var document = new ParsedDocument("source",
        List.of(chapterHeading(), originalCaption, continuationCaption), List.of(), List.of(),
        List.of(original, fragment, continuation), List.of(), List.of());

    assertTrue(new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .noneMatch(result -> result.source().elementPath().equals(fragment.source().elementPath())
            && (result.reasonCode().equals("TABLE_CAPTION_MISSING")
                || result.reasonCode().equals("TABLE_BORDER_INVALID"))),
        "the XML fragment must not be checked as an independent table");
  }

  @Test
  void allowsAuxiliaryTableLinesWhenRequiredThreeLineBordersExist() {
    var table = new TableModel(ADDRESS, ADDRESS, "表1-1 数据", 3, 2,
        true, true, true, true, true, false);
    var document = new ParsedDocument("source", List.of(), List.of(), List.of(),
        List.of(table), List.of(), List.of(), List.of());

    assertTrue(new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .noneMatch(result -> result.ruleId().equals("TAB-02")));
  }

  @Test
  void checksReferenceFontSizeAndOnePointFiveLineSpacing() {
    var reference = new ParagraphModel(
        List.of(new RunModel("[1] Paper[M].", "Arial", "黑体", null, 22, false, ADDRESS)),
        null, null, null, 480, "auto", ADDRESS, null);
    var entry = new ReferenceEntryModel(ADDRESS, "1", "[1] Paper[M].", "M", false);
    var document = new ParsedDocument("source", List.of(reference), List.of(), List.of(),
        List.of(), List.of(), List.of(entry), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(r -> r.ruleId().equals("REF-FONT") && r.judgement() == Judgement.FAIL));
    assertTrue(results.stream().anyMatch(r -> r.ruleId().equals("REF-SIZE") && r.judgement() == Judgement.FAIL));
    assertTrue(results.stream().anyMatch(r -> r.ruleId().equals("REF-LINE") && r.judgement() == Judgement.FAIL));
  }

  @Test
  void checksCaptionFontAndSizeUsingCaptionParagraphAnchor() {
    var caption = new ParagraphModel(
        List.of(new RunModel("表1-1 数据", "Arial", "黑体", null, 22, false, ADDRESS)),
        null, null, null, null, null, ADDRESS, null);
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(1), 0, 0),
        ADDRESS, "表1-1 数据", 2, 2, true, true, true, false, true, false);
    var document = new ParsedDocument("source", List.of(chapterHeading(), caption), List.of(), List.of(),
        List.of(table), List.of(), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(r -> r.ruleId().equals("CAPTION-FONT") && r.judgement() == Judgement.FAIL));
    assertTrue(results.stream().anyMatch(r -> r.ruleId().equals("CAPTION-SIZE") && r.judgement() == Judgement.FAIL));
  }

  @Test
  void acceptsLatinAndDigitsInTimesNewRomanWithinSongtiCaption() {
    var caption = new ParagraphModel(
        List.of(new RunModel("图3-1 过阻尼响应i(t)", "Times New Roman", "宋体", null, 21, false, ADDRESS)),
        null, null, null, null, null, ADDRESS, null);
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(1), 0, 0),
        ADDRESS, "表1-1 数据", 2, 2, true, true, true, false, true, false);
    var document = new ParsedDocument("source", List.of(caption), List.of(), List.of(),
        List.of(table), List.of(), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("CAPTION-FONT"))
        .findFirst().orElseThrow();

    assertEquals(Judgement.PASS, result.judgement());
    assertEquals("宋体/Times New Roman", result.expected());
  }

  @Test
  void checksOnePointFiveLineSpacingInsideAbstractZone() {
    var heading = new ParagraphModel(List.of(new RunModel("摘要", "Times New Roman", "宋体", null, 24, false, ADDRESS)),
        0, null, null, 360, "auto", ADDRESS, 0);
    var abstractBody = new ParagraphModel(List.of(new RunModel("这是摘要内容。", "Times New Roman", "宋体", null, 24, false, ADDRESS)),
        480, null, null, 480, "auto", new SourceAddress("word/document.xml", List.of(1), 0, 7), 200);
    var keywords = new ParagraphModel(List.of(new RunModel("关键词：格式", "Times New Roman", "宋体", null, 24, false, ADDRESS)),
        0, null, null, 360, "auto", new SourceAddress("word/document.xml", List.of(2), 0, 7), 0);
    var document = new ParsedDocument("source", List.of(heading, abstractBody, keywords), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    var lines = results.stream().filter(r -> r.ruleId().equals("ABSTRACT-LINE")).toList();
    assertEquals(2, lines.size());
    var line = lines.get(0);
    assertEquals(Judgement.FAIL, line.judgement());
    assertEquals(Judgement.PASS, lines.get(1).judgement());
  }

  @Test
  void rejectsFixedPointLineSpacingThatUsesTheSameTwipsAsOnePointFiveLines() {
    var title = paragraph("摘要", List.of(0, 0));
    var body = new ParagraphModel(List.of(new RunModel("摘要正文", "Times New Roman", "宋体", null, 24, false,
        new SourceAddress("word/document.xml", List.of(0, 1), 0, 4))),
        0, null, null, 360, "exact", new SourceAddress("word/document.xml", List.of(0, 1), 0, 4), 0);
    var document = new ParsedDocument("source", List.of(title, body), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("ABSTRACT-LINE"))
        .findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
  }

  @Test
  void acceptsChineseKeywordLineWithBoldLabelAndFourTerms() {
    var title = paragraph("摘要", List.of(0, 0));
    var source = new SourceAddress("word/document.xml", List.of(0, 1), 0, 23);
    var keywords = new ParagraphModel(List.of(
        new RunModel("关键词：", "Times New Roman", "宋体", null, 24, true, source),
        new RunModel("甲；乙；丙；丁", "Times New Roman", "宋体", null, 24, false, source)),
        0, null, null, 360, "auto", source, 200);
    var document = new ParsedDocument("source", List.of(title, keywords), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("ABSTRACT-KEYWORD-BOLD") && item.judgement() == Judgement.PASS));
    assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("ABSTRACT-KEYWORD-FORMAT") && item.judgement() == Judgement.PASS));
  }

  @Test
  void countsInvisibleWordFormattingControlAsTheRequiredBlankParagraph() {
    var title = paragraph("摘要", List.of(0, 0));
    var blank = new ParagraphModel(List.of(new RunModel("\u200B", null, null, null, null, false,
        new SourceAddress("word/document.xml", List.of(0, 1), 0, 1))),
        0, null, null, 360, "auto", new SourceAddress("word/document.xml", List.of(0, 1), 0, 1), null);
    var body = new ParagraphModel(List.of(new RunModel("摘要正文", "Times New Roman", "宋体", null, 24, false,
        new SourceAddress("word/document.xml", List.of(0, 2), 0, 4))),
        480, null, null, 360, "auto", new SourceAddress("word/document.xml", List.of(0, 2), 0, 4), 200);
    var document = new ParsedDocument("source", List.of(title, blank, body), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().noneMatch(item -> item.ruleId().equals("ZH-02-BLANK")
        && item.judgement() == Judgement.FAIL), results.toString());
  }

  @Test
  void rejectsMixedChineseAndAsciiKeywordSeparators() {
    var title = paragraph("摘要", List.of(0, 0));
    var source = new SourceAddress("word/document.xml", List.of(0, 1), 0, 18);
    var keywords = new ParagraphModel(List.of(new RunModel("关键词：甲;乙；丙；丁",
        "Times New Roman", "宋体", null, 24, true, source)),
        0, null, null, 360, "auto", source, 200);
    var document = new ParsedDocument("source", List.of(title, keywords), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("ABSTRACT-KEYWORD-FORMAT"))
        .findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("KEYWORD_LIST_MISMATCH", result.reasonCode());
  }

  @Test
  void rejectsTrailingWhitespaceAfterChineseAbstractTitle() {
    var title = paragraph("摘    要 ", List.of(0, 0));
    var document = new ParsedDocument("source", List.of(title), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("ZH-01-SPACING"))
        .findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("ABSTRACT_TITLE_SPACING_MISMATCH", result.reasonCode());
  }

  @Test
  void detectsChineseAbstractTitleContainingZeroWidthSpaceAsMalformed() {
    var title = paragraph("摘\u200B    要", List.of(0, 0));
    var document = new ParsedDocument("source", List.of(title), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("ZH-01-SPACING"))
        .findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("ABSTRACT_TITLE_SPACING_MISMATCH", result.reasonCode());
  }

  @Test
  void rejectsUnboldChineseKeywordLabelAndInvalidKeywordList() {
    var title = paragraph("摘要", List.of(0, 0));
    var source = new SourceAddress("word/document.xml", List.of(0, 1), 0, 18);
    var keywords = new ParagraphModel(List.of(
        new RunModel("关键词:", "Times New Roman", "宋体", null, 24, false, source),
        new RunModel("甲；乙。", "Times New Roman", "宋体", null, 24, false, source)),
        0, null, null, 360, "auto", source, 200);
    var document = new ParsedDocument("source", List.of(title, keywords), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("ABSTRACT-KEYWORD-BOLD") && item.judgement() == Judgement.FAIL));
    assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("ABSTRACT-KEYWORD-FORMAT") && item.judgement() == Judgement.FAIL));
  }

  @Test
  void rejectsWhitespaceInsertedInsideTheChineseKeywordLabel() {
    var title = paragraph("摘    要", List.of(0, 0));
    var source = new SourceAddress("word/document.xml", List.of(0, 1), 0, 20);
    var keywords = new ParagraphModel(List.of(new RunModel("关 键 词：智能；系统；推荐",
        "Times New Roman", "宋体", null, 24, true, source)),
        0, null, null, 360, "auto", source, 200);
    var document = new ParsedDocument("source", List.of(title, keywords), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("ABSTRACT-KEYWORD-FORMAT"))
        .findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("KEYWORD_SEPARATOR_MISMATCH", result.reasonCode());
  }

  @Test
  void checksChineseAbstractFormattingAndStopsAfterKeywords() {
    var title = paragraph("摘  要", List.of(0, 0));
    var body = new ParagraphModel(List.of(new RunModel("摘要正文", "Arial", "黑体", null, 18, false,
        new SourceAddress("word/document.xml", List.of(0, 1), 0, 4))),
        0, null, null, 480, "auto", new SourceAddress("word/document.xml", List.of(0, 1), 0, 4), 0);
    var keywords = new ParagraphModel(List.of(new RunModel("关键词：格式", "Arial", "黑体", null, 18, false,
        new SourceAddress("word/document.xml", List.of(0, 2), 0, 7))),
        0, null, null, 480, "auto", new SourceAddress("word/document.xml", List.of(0, 2), 0, 7), 0);
    var afterKeywords = paragraph("后续说明", List.of(0, 3));
    var document = new ParsedDocument("source", List.of(title, body, keywords, afterKeywords), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertEquals(2, results.stream().filter(result -> result.ruleId().equals("ABSTRACT-FONT")).count());
    assertEquals(2, results.stream().filter(result -> result.ruleId().equals("ABSTRACT-SIZE")).count());
    assertEquals(2, results.stream().filter(result -> result.ruleId().equals("ABSTRACT-INDENT")).count());
    assertEquals(2, results.stream().filter(result -> result.ruleId().equals("ABSTRACT-LINE")).count());
  }

  @Test
  void doesNotTreatAbstractProseStartingWithKeywordAsTheKeywordList() {
    var title = paragraph("摘  要", List.of(0, 0));
    var proseSource = new SourceAddress("word/document.xml", List.of(0, 2), 0, 17);
    var prose = new ParagraphModel(List.of(new RunModel("关键词语义分析用于检索", "Times New Roman", "宋体", null,
        24, false, proseSource)), 0, null, null, 360, "auto", proseSource, 200);
    var nextSource = new SourceAddress("word/document.xml", List.of(0, 3), 0, 6);
    var next = new ParagraphModel(List.of(new RunModel("后续摘要正文", "Times New Roman", "宋体", null,
        20, false, nextSource)), 0, null, null, 360, "auto", nextSource, 200);
    var document = new ParsedDocument("source", List.of(title, paragraph("", List.of(0, 1)), prose, next),
        List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().noneMatch(item -> item.ruleId().equals("ABSTRACT-KEYWORD-FORMAT")
        && item.source().elementPath().equals(proseSource.elementPath())));
    assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("ABSTRACT-SIZE")
        && item.source().elementPath().equals(nextSource.elementPath())));
  }

  @Test
  void checksTrailingWhitespaceRunWhenCheckingAbstractSize() {
    var title = paragraph("摘  要", List.of(0, 0));
    var source = new SourceAddress("word/document.xml", List.of(0, 1), 0, 8);
    var keywords = new ParagraphModel(List.of(
        new RunModel("关键词：甲；乙；丙", "Times New Roman", "宋体", null, 24, true, source),
        new RunModel(" ", "Times New Roman", "宋体", null, 32, false, source)),
        0, null, null, 360, "auto", source, 200);
    var document = new ParsedDocument("source", List.of(title, keywords), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(result -> result.ruleId().equals("ABSTRACT-SIZE")
        && result.judgement() == Judgement.FAIL
        && result.reasonCode().equals("SIZE_MISMATCH")), results.toString());
  }

  @Test
  void checksNamedSectionTitleFontAndSize() {
    var source = new SourceAddress("word/document.xml", List.of(0, 1), 0, 4);
    var title = new ParagraphModel(List.of(new RunModel("参考文献", "Arial", "宋体", null,
        24, false, source)), 0, null, null, null, null, source, 4);
    var document = new ParsedDocument("source", List.of(chapterHeading(), title), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("REF-01-FONT")
        && item.judgement() == Judgement.FAIL));
    assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("REF-01-SIZE")
        && item.judgement() == Judgement.FAIL));
  }

  @Test
  void doesNotTreatWrongCaseLatinFontAsExactHeadingFormatting() {
    var chapter = chapterHeading();
    var source = new SourceAddress("word/document.xml", List.of(0, 2), 0, 13);
    var paragraph = new ParagraphModel(List.of(new RunModel("English heading", "times new roman", "黑体",
        null, 28, true, source)), 0, null, null, 240, "auto", source, 0, 1, "left");
    var document = new ParsedDocument("source", List.of(chapter, paragraph), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().noneMatch(item -> item.ruleId().equals("HEADING-FONT")
        && item.source().elementPath().equals(source.elementPath())));
  }

  @Test
  void requiresReferencesTitleWithoutInterwordSpaces() {
    for (String titleText : List.of("参考 文献", "参考  文献", "参考　文献")) {
      var source = new SourceAddress("word/document.xml", List.of(0, 1), 0, titleText.length());
      var title = titleParagraph(titleText, source);
      var document = new ParsedDocument("source", List.of(chapterHeading(), title), List.of(), List.of());

      var results = new RuleEngine().check(document, RuleSet.schoolV1());
      var spacing = results.stream().filter(item -> item.ruleId().equals("REF-01-SPACING"))
          .findFirst().orElseThrow(() -> new AssertionError(results.stream()
              .map(item -> item.ruleId() + ":" + item.actual()).toList().toString()));

      assertEquals(Judgement.FAIL, spacing.judgement(), titleText);
      assertEquals("REFERENCE_TITLE_SPACING_MISMATCH", spacing.reasonCode(), titleText);
    }
    var validSource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 4);
    var validTitle = titleParagraph("参考文献", validSource);
    var validDocument = new ParsedDocument("source", List.of(chapterHeading(), validTitle), List.of(), List.of());

    var validSpacing = new RuleEngine().check(validDocument, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("REF-01-SPACING")).findFirst().orElseThrow();

    assertEquals(Judgement.PASS, validSpacing.judgement());
  }

  @Test
  void checksLeadingButIgnoresTrailingWhitespaceOnReferencesTitle() {
    var leading = new RuleEngine().check(new ParsedDocument("source",
        List.of(paragraph(" 参考文献", List.of(0, 1))), List.of(), List.of()), RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("REF-01-SPACING")).findFirst().orElseThrow();
    var trailing = new RuleEngine().check(new ParsedDocument("source",
        List.of(paragraph("参考文献 ", List.of(0, 1))), List.of(), List.of()), RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("REF-01-SPACING")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, leading.judgement());
    assertEquals("REFERENCE_TITLE_SPACING_MISMATCH", leading.reasonCode());
    assertEquals(Judgement.PASS, trailing.judgement());
  }

  @Test
  void checksEnglishAbstractTitleFontAndBold() {
    var source = new SourceAddress("word/document.xml", List.of(0, 1), 0, 8);
    var title = new ParagraphModel(List.of(new RunModel("ABSTRACT", "Arial", "宋体", null,
        32, false, source)), 0, null, null, null, null, source, 8);
    var document = new ParsedDocument("source", List.of(chapterHeading(), title), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("EN-01-FONT")
        && item.judgement() == Judgement.FAIL));
    assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("EN-01-BOLD")
        && item.judgement() == Judgement.FAIL));
  }

  @Test
  void rejectsEnglishAbstractTitleWithNonstandardCapitalization() {
    var source = new SourceAddress("word/document.xml", List.of(0, 1), 0, 8);
    var title = new ParagraphModel(List.of(new RunModel("Abstract", "Times New Roman", "宋体", null,
        32, true, source)), 0, null, null, null, null, source, 8);
    var document = new ParsedDocument("source", List.of(title), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("EN-01-TEXT"))
        .findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("ABSTRACT_TITLE_CASE_MISMATCH", result.reasonCode());
  }

  @Test
  void reportsNamedSectionTitleAlignmentMismatch() {
    var source = new SourceAddress("word/document.xml", List.of(0, 1), 0, 4);
    var title = new ParagraphModel(List.of(new RunModel("参考文献", "Times New Roman", "黑体", null,
        32, false, source)), 0, null, null, null, null, source, 4, null, "left");
    var document = new ParsedDocument("source", List.of(chapterHeading(), title), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("REF-01-ALIGNMENT")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
  }

  @Test
  void checksCoverInstitutionHeadingWithoutUsingTemplateTextAsExpectedValue() {
    var source = new SourceAddress("word/document.xml", List.of(0, 0), 0, 12);
    var title = new ParagraphModel(List.of(new RunModel("本科生毕业设计（论文）", "Arial", "黑体", null,
        32, false, source)), 0, null, null, null, null, source, 12, null, "left");
    var document = new ParsedDocument("source", List.of(title, paragraph("摘  要", List.of(0, 1))), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("COVER-01-FONT")
        && item.judgement() == Judgement.FAIL));
    assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("COVER-01-SIZE")
        && item.judgement() == Judgement.FAIL));
    assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("COVER-01-ALIGNMENT")
        && item.judgement() == Judgement.FAIL));
  }

  @Test
  void recognizesCoverTypeHeadingWithAsciiParentheses() {
    var source = new SourceAddress("word/document.xml", List.of(0, 0), 0, 12);
    var title = new ParagraphModel(List.of(new RunModel("本科生毕业设计(论文)", "Arial", "黑体", null,
        32, false, source)), 0, null, null, null, null, source, 12, null, "left");
    var document = new ParsedDocument("source", List.of(title, paragraph("摘要", List.of(0, 1))), List.of(), List.of());

    assertTrue(new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .anyMatch(item -> item.ruleId().equals("COVER-01-FONT")));
  }

  @Test
  void recognizesCoverTypeHeadingWithIdeographicSpacing() {
    var source = new SourceAddress("word/document.xml", List.of(0, 0), 0, 13);
    var title = new ParagraphModel(List.of(new RunModel("本科生毕业设计　（论文）", "Arial", "黑体", null,
        32, false, source)), 0, null, null, null, null, source, 13, null, "left");
    var document = new ParsedDocument("source", List.of(title, paragraph("摘要", List.of(0, 1))), List.of(), List.of());

    assertTrue(new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .anyMatch(item -> item.ruleId().equals("COVER-01-FONT")));
  }

  @Test
  void doesNotStartCoverParsingFromSchoolAndCoverPhrasesInsideBodyText() {
    var document = new ParsedDocument("source", List.of(
        paragraph("山东石油化工学院的本科生毕业设计（论文）采用案例分析方法。", List.of(0, 1)),
        paragraph("本科生毕业设计（论文）是本科教学的重要环节。", List.of(0, 2)),
        paragraph("指导教师：由正文介绍说明。", List.of(0, 3)),
        chapterHeading()), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().noneMatch(item -> item.ruleId().startsWith("COVER-")), results.toString());
  }

  @Test
  void checksTheSchoolNameImmediatelyBeforeTheCoverTypeHeading() {
    var schoolNameSource = new SourceAddress("word/document.xml", List.of(0, 0), 0, 8);
    var schoolName = new ParagraphModel(List.of(new RunModel("山东石油化工学院", "Arial", "黑体", null,
        32, false, schoolNameSource)), 0, null, null, null, null, schoolNameSource, 8, null, "left");
    var document = new ParsedDocument("source", List.of(schoolName,
        paragraph("本科生毕业设计（论文）", List.of(0, 1)), paragraph("摘要", List.of(0, 2))),
        List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("COVER-01-SIZE")
        && item.source().equals(schoolNameSource) && item.judgement() == Judgement.FAIL));
    assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("COVER-01-ALIGNMENT")
        && item.source().equals(schoolNameSource) && item.judgement() == Judgement.FAIL));
  }

  @Test
  void ignoresFormattingOnlyRunWhenCheckingCoverTypeSize() {
    var schoolSource = new SourceAddress("word/document.xml", List.of(0, 0), 0, 8);
    var typeSource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 12);
    var school = new ParagraphModel(List.of(
        new RunModel("山东石油化工学院", "Arial", "方正小标宋简体", null, 72, false, schoolSource)),
        0, null, null, null, null, schoolSource, 8, null, "center");
    var type = new ParagraphModel(List.of(
        new RunModel(" ", "Arial", "方正小标宋简体", null, 84, false, typeSource),
        new RunModel("本科生毕业设计（论文）", "Arial", "方正小标宋简体", null, 72, false, typeSource)),
        0, null, null, null, null, typeSource, 12, null, "center");
    var document = new ParsedDocument("source", List.of(school, type,
        paragraph("摘要", List.of(0, 2))), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().noneMatch(item -> item.ruleId().equals("COVER-01-SIZE")
        && item.judgement() == Judgement.FAIL), results.toString());
  }

  @Test
  void checksCoverFieldFormattingWithoutExpectingSpecificNamesOrIds() {
    var source = new SourceAddress("word/document.xml", List.of(0, 1), 0, 10);
    var field = new ParagraphModel(List.of(
        new RunModel("学生姓名：", "Times New Roman", "仿宋", null, 32, true, source),
        new RunModel("学生甲", "Times New Roman", "仿宋", null, 32, false, source)),
        0, null, null, null, null, source, 10, null, "left");
    var document = new ParsedDocument("source", List.of(paragraph("本科生毕业设计（论文）", List.of(0, 0)), field,
        paragraph("摘  要", List.of(0, 2))), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("COVER-03-BOLD") && item.judgement() == Judgement.FAIL)
        .findFirst().orElseThrow();

    assertEquals(source, result.source());
  }

  @Test
  void checksCoverFieldLabelFormattingWhenLabelAndValueUseSeparateRuns() {
    var labelSource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 5);
    var valueSource = new SourceAddress("word/document.xml", List.of(0, 1), 5, 7);
    var field = new ParagraphModel(List.of(
        new RunModel("\u5b66\u751f\u59d3\u540d\uff1a", "Arial", "\u9ed1\u4f53", null, 32, true, labelSource),
        new RunModel("\u5f20\u4e09", "Times New Roman", "\u4eff\u5b8b", null, 32, true, valueSource)),
        0, null, null, null, null, labelSource, 10, null, "left");
    var document = new ParsedDocument("source", List.of(
        paragraph("\u672c\u79d1\u751f\u6bd5\u4e1a\u8bbe\u8ba1\uff08\u8bba\u6587\uff09", List.of(0, 0)), field,
        paragraph("\u6458\u8981", List.of(0, 2))), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("COVER-03-FONT")
        && item.source().equals(labelSource) && item.judgement() == Judgement.FAIL),
        "字段标签和字段值分属不同 run 时，标签格式也应按封面字段规则检查");
  }

  @Test
  void acceptsCoverLabelsWithAlignmentWhitespaceInsideChineseFieldNames() {
    var source = new SourceAddress("word/document.xml", List.of(0, 1), 0, 20);
    var field = new ParagraphModel(List.of(
        new RunModel("学    号：9000000001", "Times New Roman", "仿宋", null, 32, true, source)),
        0, null, null, null, null, source, 20, null, "left");
    var document = new ParsedDocument("source", List.of(
        paragraph("本科生毕业设计（论文）", List.of(0, 0)), field,
        paragraph("摘要", List.of(0, 2))), List.of(), List.of());

    assertTrue(new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .noneMatch(item -> item.ruleId().equals("COVER-03-LABEL")),
        "字段名中的排版空格不应被判定为字段名错误");
  }

  @Test
  void rejectsAnUnspecifiedCoverFieldAliasEvenWhenItsValueFormattingIsCorrect() {
    var fieldSource = new SourceAddress("word/document.xml", List.of(0, 3), 0, 8);
    var wrongField = new ParagraphModel(List.of(new RunModel("指导老师：张三", "Times New Roman", "仿宋",
        null, 32, true, fieldSource)), 0, null, null, null, null, fieldSource, 8, null, "left");
    var document = new ParsedDocument("source", List.of(
        paragraph("本科生毕业设计（论文）", List.of(0, 0)),
        paragraph("论文题目：", List.of(0, 1)),
        new ParagraphModel(List.of(new RunModel("课题设计", "Times New Roman", "黑体", null,
            36, false, new SourceAddress("word/document.xml", List.of(0, 2), 0, 4))),
            0, null, null, null, null, new SourceAddress("word/document.xml", List.of(0, 2), 0, 4), 4),
        wrongField,
        paragraph("摘要", List.of(0, 4))), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("COVER-03-LABEL"))
        .findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("COVER_FIELD_LABEL_MISMATCH", result.reasonCode());
    assertEquals(fieldSource, result.source());
  }

  @Test
  void doesNotTreatCoverTitleStartingWithMajorAsTheMajorField() {
    var document = new ParsedDocument("source", List.of(
        paragraph("本科生毕业设计（论文）", List.of(0, 0)),
        paragraph("专业数据处理系统设计", List.of(0, 1)),
        paragraph("摘要", List.of(0, 2))), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("COVER-02-FONT")));
    assertTrue(results.stream().noneMatch(item -> item.ruleId().startsWith("COVER-03-")));
  }

  @Test
  void checksCoverDateFormattingWithoutAssumingTheSubmissionDate() {
    var source = new SourceAddress("word/document.xml", List.of(0, 2), 0, 10);
    var dateSource = new SourceAddress("word/document.xml", List.of(0, 3), 0, 10);
    var date = new ParagraphModel(List.of(new RunModel("2026年6月", "Times New Roman", "宋体", null,
        24, false, dateSource)), 0, null, null, null, null, dateSource, 0, null, "center");
    var document = new ParsedDocument("source", List.of(paragraph("本科生毕业设计（论文）", List.of(0, 0)),
        paragraph("论文题目：", List.of(0, 1)), paragraph("基于样例的研究", List.of(0, 2)), date,
        paragraph("摘要", List.of(0, 4))), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("COVER-04-FONT")
        && item.judgement() == Judgement.FAIL));
    assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("COVER-04-SIZE")
        && item.judgement() == Judgement.FAIL));
    assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("COVER-04-BOLD")
        && item.judgement() == Judgement.FAIL));
    assertTrue(results.stream().noneMatch(item -> item.ruleId().startsWith("COVER-02-")
        && item.source().elementPath().equals(List.of(0, 1))));
  }

  @Test
  void reportsDifferentDatesOnTheCover() {
    var secondSource = new SourceAddress("word/document.xml", List.of(0, 4), 0, 7);
    var firstDate = paragraph("2026年6月", List.of(0, 3));
    var secondDate = paragraph("2026年7月", List.of(0, 4));
    var document = new ParsedDocument("source", List.of(
        paragraph("本科生毕业设计（论文）", List.of(0, 0)),
        paragraph("论文题目：", List.of(0, 1)),
        paragraph("基于样例的研究", List.of(0, 2)), firstDate, secondDate,
        paragraph("摘要", List.of(0, 5))), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("COVER-04-CONSISTENCY")
            && item.judgement() == Judgement.FAIL)
        .findFirst().orElseThrow();

    assertEquals("COVER_DATE_MISMATCH", result.reasonCode());
    assertEquals(secondSource, result.source());
    assertEquals("2026年7月", result.actual());
  }

  @Test
  void requiresExactCoverDateTextAndDoesNotRequireMultipleDates() {
    var firstDate = paragraph("2026年6月", List.of(0, 3));
    var equivalentDate = paragraph("2026-06", List.of(0, 4));
    var document = new ParsedDocument("source", List.of(
        paragraph("本科生毕业设计（论文）", List.of(0, 0)),
        paragraph("论文题目：", List.of(0, 1)),
        paragraph("基于样例的研究", List.of(0, 2)), firstDate, equivalentDate,
        paragraph("摘要", List.of(0, 5))), List.of(), List.of());
    var singleDateDocument = new ParsedDocument("source", List.of(
        paragraph("本科生毕业设计（论文）", List.of(0, 0)),
        paragraph("论文题目：", List.of(0, 1)), firstDate,
        paragraph("摘要", List.of(0, 4))), List.of(), List.of());

    var dateMismatch = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("COVER-04-CONSISTENCY")
            && item.judgement() == Judgement.FAIL)
        .findFirst().orElseThrow();
    assertEquals("COVER_DATE_MISMATCH", dateMismatch.reasonCode());
    assertEquals("2026-06", dateMismatch.actual());
    assertTrue(new RuleEngine().check(singleDateDocument, RuleSet.schoolV1()).stream()
        .noneMatch(item -> item.ruleId().equals("COVER-04-CONSISTENCY")));
  }

  @Test
  void checksCoverFieldContinuationParagraphs() {
    var firstSource = new SourceAddress("word/document.xml", List.of(0, 3), 0, 10);
    var continuationSource = new SourceAddress("word/document.xml", List.of(0, 4), 0, 10);
    var first = new ParagraphModel(List.of(
        new RunModel("院系：信息科学", "Times New Roman", "仿宋", null, 32, true, firstSource)),
        0, null, null, null, null, firstSource, 0, null, "left");
    var continuation = new ParagraphModel(List.of(
        new RunModel("与工程学院", "Arial", "宋体", null, 24, false, continuationSource)),
        0, null, null, null, null, continuationSource, 0, null, "left");
    var document = new ParsedDocument("source", List.of(
        paragraph("本科生毕业设计（论文）", List.of(0, 0)),
        paragraph("论文题目：", List.of(0, 1)),
        paragraph("基于样例的研究", List.of(0, 2)), first, continuation,
        paragraph("摘要", List.of(0, 5))), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("COVER-03-FONT")
        && item.judgement() == Judgement.FAIL && item.source().equals(continuationSource)));
    assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("COVER-03-SIZE")
        && item.judgement() == Judgement.FAIL && item.source().equals(continuationSource)));
    assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("COVER-03-BOLD")
        && item.judgement() == Judgement.FAIL && item.source().equals(continuationSource)));
  }

  @Test
  void recognizesAppendixHeadingWhereTitleFollowsItsLetterWithoutASpace() {
    var appendix = paragraph("附录A名词术语及缩略词", List.of(0, 10));
    var caption = paragraph("图A1 术语关系", List.of(0, 12));
    var imageSource = new SourceAddress("word/document.xml", List.of(0, 11), 0, 0);
    var image = new ImageModel(imageSource, caption.source(), caption.runs().get(0).text(),
        100, 200, true, true, true);
    var document = new ParsedDocument("source", List.of(chapterHeading(), appendix,
        paragraph("图片对象", imageSource.elementPath()), caption), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(image));

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("APP-01")
        && item.expected().equals("A") && item.actual().equals("A")
        && item.judgement() == Judgement.PASS));
    assertTrue(results.stream().noneMatch(item -> item.source().equals(appendix.source())
        && (item.ruleId().equals("APP-02-FONT") || item.ruleId().equals("APP-02-SIZE")
            || item.ruleId().equals("APP-02-ALIGNMENT"))), results.toString());
    assertTrue(results.stream().noneMatch(item -> item.reasonCode().equals("APPENDIX_OBJECT_PREFIX_MISSING")));
    assertTrue(results.stream().noneMatch(item -> item.ruleId().startsWith("END-01-")), results.toString());
    assertTrue(results.stream().noneMatch(item -> item.ruleId().equals("APP-02-SPACING")), results.toString());
  }

  @Test
  void recognizesAppendixHeadingStartingWithAnAmbiguousHanCharacter() {
    var appendixSource = new SourceAddress("word/document.xml", List.of(0, 10), 0, 9);
    var appendix = new ParagraphModel(List.of(new RunModel("附录A中使用的符号", "Arial", "黑体", null,
        18, false, appendixSource)), 0, null, null, null, null, appendixSource, 0, 0);
    var caption = paragraph("图A1 符号说明", List.of(0, 12));
    var imageSource = new SourceAddress("word/document.xml", List.of(0, 11), 0, 0);
    var image = new ImageModel(imageSource, caption.source(), caption.runs().get(0).text(),
        100, 200, true, true, true);
    var document = new ParsedDocument("source", List.of(chapterHeading(), appendix,
        paragraph("图片对象", imageSource.elementPath()), caption), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(image));

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("APP-01")
        && item.expected().equals("A") && item.actual().equals("A")
        && item.judgement() == Judgement.PASS), results.toString());
    assertTrue(results.stream().noneMatch(item -> item.reasonCode().equals("APPENDIX_OBJECT_PREFIX_MISSING")),
        results.toString());
  }

  @Test
  void checksStandaloneAppendixTitleSpacingWithAppendixRule() {
    var valid = new RuleEngine().check(new ParsedDocument("source",
        List.of(paragraph("附    录", List.of(0, 10))), List.of(), List.of()), RuleSet.schoolV1());
    var validSpacing = valid.stream().filter(item -> item.ruleId().equals("APP-02-SPACING"))
        .findFirst().orElseThrow(() -> new AssertionError(valid.toString()));

    assertEquals(Judgement.PASS, validSpacing.judgement(), valid.toString());
    assertTrue(valid.stream().noneMatch(item -> item.ruleId().startsWith("END-01-")), valid.toString());

    var invalid = new RuleEngine().check(new ParsedDocument("source",
        List.of(paragraph("附录", List.of(0, 10))), List.of(), List.of()), RuleSet.schoolV1());
    var invalidSpacing = invalid.stream().filter(item -> item.ruleId().equals("APP-02-SPACING"))
        .findFirst().orElseThrow(() -> new AssertionError(invalid.toString()));

    assertEquals(Judgement.FAIL, invalidSpacing.judgement(), invalid.toString());
    assertTrue(invalid.stream().noneMatch(item -> item.ruleId().startsWith("END-01-")), invalid.toString());
  }

  @Test
  void checksLeadingButIgnoresTrailingWhitespaceOnStandaloneAppendixTitle() {
    var leading = new RuleEngine().check(new ParsedDocument("source",
        List.of(paragraph(" 附    录", List.of(0, 10))), List.of(), List.of()), RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("APP-02-SPACING")).findFirst().orElseThrow();
    var trailing = new RuleEngine().check(new ParsedDocument("source",
        List.of(paragraph("附    录 ", List.of(0, 10))), List.of(), List.of()), RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("APP-02-SPACING")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, leading.judgement());
    assertEquals("APPENDIX_TITLE_SPACING_MISMATCH", leading.reasonCode());
    assertEquals(Judgement.PASS, trailing.judgement());
  }

  @Test
  void acknowledgesChineseTitleWithFourInterwordSpaces() {
    var results = new RuleEngine().check(new ParsedDocument("source",
        List.of(paragraph("致    谢", List.of(0, 10)), paragraph("", List.of(0, 11)),
            paragraph("感谢指导。", List.of(0, 12))), List.of(), List.of()), RuleSet.schoolV1());
    var spacing = results.stream().filter(item -> item.ruleId().equals("END-01-SPACING"))
        .findFirst().orElseThrow(() -> new AssertionError(results.toString()));

    assertEquals(Judgement.PASS, spacing.judgement(), results.toString());
    assertTrue(results.stream().noneMatch(item -> item.ruleId().startsWith("APP-02-")), results.toString());
  }

  @Test
  void checksLeadingButIgnoresTrailingWhitespaceOnAcknowledgementTitle() {
    var leading = new RuleEngine().check(new ParsedDocument("source",
        List.of(paragraph(" 致    谢", List.of(0, 10))), List.of(), List.of()), RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("END-01-SPACING")).findFirst().orElseThrow();
    var trailing = new RuleEngine().check(new ParsedDocument("source",
        List.of(paragraph("致    谢 ", List.of(0, 10))), List.of(), List.of()), RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("END-01-SPACING")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, leading.judgement());
    assertEquals("ACKNOWLEDGEMENT_TITLE_SPACING_MISMATCH", leading.reasonCode());
    assertEquals(Judgement.PASS, trailing.judgement());
  }

  @Test
  void rejectsLowercaseAppendixLetterEvenThoughHeadingCandidateIsRecognized() {
    var appendix = paragraph("附录a 补充材料", List.of(0, 10));
    var caption = paragraph("图a1 补充数据", List.of(0, 12));
    var imageSource = new SourceAddress("word/document.xml", List.of(0, 11), 0, 0);
    var image = new ImageModel(imageSource,
        caption.source(), "图a1 补充数据", 100, 200, true, true);
    var document = new ParsedDocument("source", List.of(chapterHeading(), appendix,
        paragraph("图片对象", imageSource.elementPath()), caption),
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(image));

    var findings = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("APP-01") && item.judgement() == Judgement.FAIL)
        .toList();

    assertTrue(findings.stream().anyMatch(item -> item.reasonCode().equals("APPENDIX_SEQUENCE_MISMATCH")
        && "a".equals(item.actual())));
    assertTrue(findings.stream().anyMatch(item -> item.source().equals(caption.source())
        && item.reasonCode().equals("APPENDIX_OBJECT_PREFIX_MISSING")), findings.toString());
  }

  @Test
  void checksAppendixSequenceAndObjectLetterAgainstAppendixScope() {
    var headingA = paragraph("附录 A 源代码", List.of(0, 10));
    var headingC = paragraph("附录 C 补充材料", List.of(0, 20));
    var caption = paragraph("图B1 系统结构", List.of(0, 12));
    var image = new ImageModel(new SourceAddress("word/document.xml", List.of(0, 11), 0, 0),
        caption.source(), "图B1 系统结构", 100, 200, true, true);
    var document = new ParsedDocument("source", List.of(chapterHeading(), headingA,
        paragraph("图片对象", List.of(0, 11)), caption, headingC),
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(image));

    var results = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("APP-01")).toList();

    assertTrue(results.stream().anyMatch(item -> item.reasonCode().equals("APPENDIX_SEQUENCE_MISMATCH")
        && item.judgement() == Judgement.FAIL));
    assertTrue(results.stream().anyMatch(item -> item.reasonCode().equals("APPENDIX_OBJECT_LETTER_MISMATCH")
        && item.judgement() == Judgement.FAIL && item.source().equals(caption.source())));
    assertTrue(results.stream().anyMatch(item -> item.expected().equals("A") && item.actual().equals("A")
        && item.judgement() == Judgement.PASS));
  }

  @Test
  void acceptsAppendixFigureAndTableNumberFormatsWithoutBodyChapterHyphen() {
    var appendix = paragraph("附录 A 源代码", List.of(0, 2));
    var figureCaption = paragraph("图A1 系统流程", List.of(0, 3));
    var tableCaption = paragraph("表A1 参数列表", List.of(0, 5));
    var image = new ImageModel(new SourceAddress("word/document.xml", List.of(0, 4), 0, 0),
        figureCaption.source(), "图A1 系统流程", 100, 200, true, true, true);
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(0, 6), 0, 0),
        tableCaption.source(), "表A1 参数列表", 2, 2, true, true, true, false, true, false);
    var document = new ParsedDocument("source", List.of(chapterHeading(), appendix, figureCaption, tableCaption),
        List.of(), List.of(), List.of(table), List.of(), List.of(), List.of(image));

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().noneMatch(item -> item.reasonCode().equals("FIGURE_CAPTION_NUMBER_FORMAT")
        || item.reasonCode().equals("TABLE_CAPTION_NUMBER_FORMAT")), results.toString());
    assertEquals(2, results.stream().filter(item -> item.ruleId().equals("APP-01")
        && item.judgement() == Judgement.PASS).count(), results.toString());
  }

  @Test
  void rejectsAppendixFigureNumberingThatSkipsASequenceValue() {
    var appendix = paragraph("附录 A 源代码", List.of(0, 1));
    var firstObject = paragraph("第一幅图", List.of(0, 2));
    var firstCaption = paragraph("图A1 系统流程", List.of(0, 3));
    var secondObject = paragraph("第二幅图", List.of(0, 4));
    var secondCaption = paragraph("图A3 运行结果", List.of(0, 5));
    var firstImage = new ImageModel(firstObject.source(), firstCaption.source(), firstCaption.runs().get(0).text(),
        100, 200, true, true, true);
    var secondImage = new ImageModel(secondObject.source(), secondCaption.source(), secondCaption.runs().get(0).text(),
        100, 200, true, true, true);
    var document = new ParsedDocument("source", List.of(chapterHeading(), appendix, firstObject,
        firstCaption, secondObject, secondCaption), List.of(), List.of(), List.of(), List.of(),
        List.of(), List.of(firstImage, secondImage));

    var results = new RuleEngine().check(document, RuleSet.schoolV1());
    var finding = results.stream()
        .filter(item -> item.reasonCode().equals("APPENDIX_OBJECT_SEQUENCE_MISMATCH"))
        .findFirst().orElseThrow(() -> new AssertionError(results.toString()));

    assertEquals("图A2", finding.expected());
    assertEquals("图A3 运行结果", finding.actual());
    assertEquals(secondCaption.source(), finding.source());
  }

  @Test
  void reportsAppendixEquationSequenceWhenNumberExceedsIntegerRange() {
    var appendix = paragraph("附录 A 公式", List.of(0, 10));
    var equationParagraph = paragraph("(A2147483648)", List.of(0, 11));
    var equation = new cn.luwneshacha.thesis.document.EquationModel(
        equationParagraph.source(), "OMML", "x", "A2147483648", true, false);
    var document = new ParsedDocument("source", List.of(chapterHeading(), appendix,
        equationParagraph), List.of(), List.of(), List.of(), List.of(equation), List.of(), List.of());

    var finding = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.reasonCode().equals("APPENDIX_OBJECT_SEQUENCE_MISMATCH"))
        .findFirst().orElseThrow();

    assertEquals("公式A1", finding.expected());
    assertEquals("A2147483648", finding.actual());
    assertEquals(equation.source(), finding.source());
  }

  @Test
  void recognizesEnglishAppendixHeadingsForLetterSequenceChecks() {
    var document = new ParsedDocument("source", List.of(chapterHeading(),
        paragraph("Appendix A Supplementary material", List.of(0, 10)),
        paragraph("Appendix C Additional material", List.of(0, 20))), List.of(), List.of());

    var appendixChecks = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("APP-01")
            && item.reasonCode().equals("APPENDIX_SEQUENCE_MISMATCH")).toList();

    assertEquals(1, appendixChecks.size());
    assertEquals("B", appendixChecks.get(0).expected());
    assertEquals("C", appendixChecks.get(0).actual());
  }

  @Test
  void doesNotCountFigureReferencesAfterEnglishAppendixAsBodyReferences() {
    var caption = paragraph("图1-1 系统结构图", List.of(0, 2));
    var appendix = paragraph("Appendix A Supplementary material", List.of(0, 4));
    var appendixReference = paragraph("参见图1-1", List.of(0, 5));
    var image = new ImageModel(new SourceAddress("word/document.xml", List.of(0, 3), 0, 0),
        caption.source(), "图1-1 系统结构图", 100, 200, true, true);
    var document = new ParsedDocument("source", List.of(chapterHeading(), caption, appendix, appendixReference),
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(image));

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("FIG-01")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("FIGURE_NOT_CITED_IN_BODY", result.reasonCode());
  }

  @Test
  void appendixSequenceMustStartAtAAndAdvanceByAppearanceOrder() {
    var document = new ParsedDocument("source", List.of(
        chapterHeading(), paragraph("附录 B 源代码", List.of(0, 10)),
        paragraph("附录 C 附加材料", List.of(0, 20))), List.of(), List.of());

    var mismatches = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("APP-01")
            && item.reasonCode().equals("APPENDIX_SEQUENCE_MISMATCH")).toList();

    assertEquals(2, mismatches.size());
    assertEquals(List.of("A", "B"), mismatches.stream().map(CheckResult::expected).toList());
  }

  @Test
  void ignoresAppendixEntriesThatEndWithPageNumberInContents() {
    var contentsTitle = paragraph("目录", List.of(0, 1));
    var contentsEntry = paragraph("附录 B 源代码 88", List.of(0, 2));
    var actualAppendix = paragraph("附录 A 源代码", List.of(0, 20));
    var document = new ParsedDocument("source", List.of(contentsTitle, contentsEntry,
        chapterHeading(), actualAppendix), List.of(), List.of());

    var appendixChecks = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("APP-01")).toList();

    assertEquals(1, appendixChecks.size());
    assertEquals(Judgement.PASS, appendixChecks.get(0).judgement());
    assertEquals("A", appendixChecks.get(0).actual());
  }

  @Test
  void doesNotTreatTabbedTableOfContentsChapterAsARealHeading() {
    var contentsTitle = paragraph("目录", List.of(0, 0));
    var tocSource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 10);
    var tocEntry = new ParagraphModel(List.of(new RunModel("第一章  引言\t1", "Times New Roman", "宋体",
        null, 24, false, tocSource)), 0, null, null, null, null, tocSource, 0, null, "left");
    var document = new ParsedDocument("source", List.of(contentsTitle, tocEntry), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().noneMatch(item -> item.source().equals(tocSource)
        && (item.ruleId().equals("HEADING-SIZE") || item.ruleId().equals("HEADING-FONT")
            || item.ruleId().startsWith("HEAD-01"))));
  }

  @Test
  void checksAppendixEquationReferencesWithinTheSameAppendix() {
    var appendix = paragraph("附录 A 公式", List.of(0, 10));
    var equationParagraph = paragraph("(A1)", List.of(0, 11));
    var reference = paragraph("由式(A1)可得结果。", List.of(0, 12));
    var equation = new cn.luwneshacha.thesis.document.EquationModel(
        equationParagraph.source(), "OMML", "x", "A1", true, false);
    var document = new ParsedDocument("source", List.of(chapterHeading(), appendix,
        equationParagraph, reference), List.of(), List.of(), List.of(), List.of(equation),
        List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("EQ-04")).findFirst().orElseThrow();

    assertEquals(Judgement.PASS, result.judgement(), result.toString());
  }

  @Test
  void acceptsEquationReferenceWrittenBeforeFormulaInTheSameParagraph() {
    var equationParagraph = paragraph("\u5f0f(3-1)\u53ef\u5f97\uff1a\u516c\u5f0f(3-1)", List.of(0, 2));
    var equation = new cn.luwneshacha.thesis.document.EquationModel(
        equationParagraph.source(), "OMML", "x", "3-1", true, false);
    var document = new ParsedDocument("source", List.of(chapterHeading(), equationParagraph),
        List.of(), List.of(), List.of(), List.of(equation), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("EQ-04")).findFirst().orElseThrow();

    assertEquals(Judgement.PASS, result.judgement(), result.toString());
  }
  @Test
  void checksSecondLevelHeadingFontAndSizeWithoutInventingBoldRequirement() {
    var heading = new ParagraphModel(
        List.of(new RunModel("1.1 标题", "Arial", "宋体", null, 24, false, ADDRESS)),
        0, null, null, 360, "auto", ADDRESS, 0);
    var document = new ParsedDocument("source", List.of(heading), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(r -> r.ruleId().equals("HEADING-FONT") && r.judgement() == Judgement.FAIL));
    assertTrue(results.stream().filter(r -> r.ruleId().equals("HEADING-FONT"))
        .allMatch(r -> r.expected().startsWith("二级标题")));
    assertTrue(results.stream().anyMatch(r -> r.ruleId().equals("HEADING-SIZE") && r.judgement() == Judgement.FAIL));
    assertTrue(results.stream().noneMatch(r -> r.ruleId().equals("HEADING-BOLD")));
  }

  @Test
  void doesNotReportBodyLengthForTenOrMoreShortParagraphs() {
    var paragraphs = new java.util.ArrayList<ParagraphModel>();
    paragraphs.add(chapterHeading());
    for (int index = 0; index < 12; index++)
      paragraphs.add(paragraph("a", List.of(0, index + 1)));
    var document = new ParsedDocument("source", paragraphs, List.of(), List.of());

    assertTrue(new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .noneMatch(item -> item.ruleId().equals("LENGTH-01")));
  }

  @Test
  void appliesTheForeignLanguageCharacterMinimumToAnEnglishMajor() {
    var major = paragraph("专业：英语", List.of(0, 0));
    var chapter = chapterHeading();
    var bodySource = new SourceAddress("word/document.xml", List.of(0, 2), 0, 16000);
    var body = new ParagraphModel(List.of(new RunModel("a".repeat(16000), "Times New Roman", "宋体",
        null, 24, false, bodySource)), 480, null, null, 360, "auto", bodySource, 200);
    var document = new ParsedDocument("source", List.of(major, chapter, body), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().noneMatch(item -> item.ruleId().equals("LENGTH-01")));
    assertTrue(results.stream().noneMatch(item -> item.ruleId().equals("LENGTH-02")));
  }

  @Test
  void doesNotReportForeignBodyLength() {
    var major = paragraph("专业：英语", List.of(0, 0));
    var chapter = chapterHeading();
    var bodySource = new SourceAddress("word/document.xml", List.of(0, 2), 0, 12000);
    var body = new ParagraphModel(List.of(new RunModel("a".repeat(12000), "Times New Roman", "宋体",
        null, 24, false, bodySource)), 480, null, null, 360, "auto", bodySource, 200);
    var document = new ParsedDocument("source", List.of(major, chapter, body), List.of(), List.of());

    assertTrue(new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .noneMatch(item -> item.ruleId().equals("LENGTH-02")));
  }

  @Test
  void doesNotEnforceAnUnspecifiedForeignToChineseCharacterRatio() {
    var major = paragraph("专业：英语", List.of(0, 0));
    var chapter = chapterHeading();
    var bodySource = new SourceAddress("word/document.xml", List.of(0, 2), 0, 16000);
    var body = new ParagraphModel(List.of(new RunModel("中".repeat(16000), "Times New Roman", "宋体",
        null, 24, false, bodySource)), 480, null, null, 360, "auto", bodySource, 200);
    var document = new ParsedDocument("source", List.of(major, chapter, body), List.of(), List.of());

    assertTrue(new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .noneMatch(item -> item.ruleId().equals("LENGTH-03")));
  }

  @Test
  void checksDigitsInHeadingAgainstTimesNewRoman() {
    var heading = new ParagraphModel(
        List.of(new RunModel("2.1 标题", "Arial", "黑体", null, 28, false, ADDRESS)),
        0, null, null, 360, "auto", ADDRESS, 1);
    var document = new ParsedDocument("source", List.of(heading), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("HEADING-FONT")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
  }

  @Test
  void checksThirdLevelHeadingIndentAndLowerHeadingFormatting() {
    var thirdSource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 8);
    var lowerSource = new SourceAddress("word/document.xml", List.of(0, 2), 0, 10);
    var third = new ParagraphModel(List.of(new RunModel("2.2.1 标题", "Times New Roman", "黑体", null,
        24, false, thirdSource)), 0, null, null, null, null, thirdSource, 0, null, "left");
    var lower = new ParagraphModel(List.of(new RunModel("（1）、标题", "Times New Roman", "宋体", null,
        24, false, lowerSource)), 0, null, null, null, null, lowerSource, 0, null, "left");
    var document = new ParsedDocument("source", List.of(third, lower), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(r -> r.ruleId().equals("HEAD-03-INDENT")
        && r.judgement() == Judgement.FAIL));
    assertTrue(results.stream().anyMatch(r -> r.ruleId().equals("HEAD-05-INDENT") && r.judgement() == Judgement.FAIL));
  }

  @Test
  void reportsThirdLevelHeadingIndentInTwipsWhenCharacterIndentIsUnavailable() {
    var source = new SourceAddress("word/document.xml", List.of(0, 1), 0, 8);
    var heading = new ParagraphModel(List.of(new RunModel("2.2.1 标题", "Times New Roman", "黑体",
        null, 24, false, source)), 480, null, null, null, null, source, null);
    var document = new ParsedDocument("source", List.of(heading), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());
    assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("HEAD-03-INDENT")
        && item.judgement() == Judgement.PASS));
  }

  @Test
  void usesWordOutlineLevelToClassifyUnnumberedHeading() {
    var source = new SourceAddress("word/document.xml", List.of(0, 12), 0, 5);
    var heading = new ParagraphModel(List.of(
        new RunModel("结论与展望", "Times New Roman", "黑体", null, 32, false, source)),
        0, null, null, null, null, source, 0, 0);
    var document = new ParsedDocument("source", List.of(heading), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().noneMatch(result -> result.ruleId().startsWith("BODY-")));
    assertTrue(results.stream().anyMatch(result -> result.ruleId().equals("HEADING-SIZE")
        && result.judgement() == Judgement.PASS));
  }

  @Test
  void treatsNormalTextWithInheritedOutlineLevelZeroAsBody() {
    var source = new SourceAddress("word/document.xml", List.of(0, 12), 0, 7);
    var body = new ParagraphModel(List.of(
        new RunModel("普通正文内容", "Arial", "楷体", null, 24, false, source)),
        480, null, null, 360, "auto", source, 200, 0, "left");
    var document = new ParsedDocument("source", List.of(chapterHeading(), body), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().noneMatch(result -> result.ruleId().startsWith("HEADING-")
        && result.source().equals(source)));
    assertTrue(results.stream().anyMatch(result -> result.ruleId().equals("BODY-FONT")
        && result.judgement() == Judgement.FAIL && result.source().equals(source)));
  }

  @Test
  void checksLowerLevelHeadingWithItsRequiredDelimiter() {
    var source = new SourceAddress("word/document.xml", List.of(0, 12), 0, 8);
    var heading = new ParagraphModel(List.of(
        new RunModel("（1）、标题", "Times New Roman", "宋体", null, 24, false, source)),
        0, null, null, 360, "auto", source, 200);
    var document = new ParsedDocument("source", List.of(chapterHeading(), heading), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(result -> result.ruleId().equals("HEAD-05-SIZE")
        && result.judgement() == Judgement.PASS), results.toString());
    assertTrue(results.stream().anyMatch(result -> result.ruleId().equals("HEAD-05-MARKER")
        && result.judgement() == Judgement.PASS));
  }

  @Test
  void acceptsLowerLevelHeadingWithoutACommaAfterTheClosingParenthesis() {
    var source = new SourceAddress("word/document.xml", List.of(0, 12), 0, 8);
    var heading = new ParagraphModel(List.of(
        new RunModel("（1）标题", "Times New Roman", "宋体", null, 24, false, source)),
        0, null, null, 360, "auto", source, 200);
    var document = new ParsedDocument("source", List.of(chapterHeading(), heading), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("HEAD-05-MARKER")).findFirst().orElseThrow();

    assertEquals(Judgement.PASS, result.judgement());
  }

  @Test
  void doesNotApplyBodyRulesToARecognizedLowerLevelHeading() {
    var source = new SourceAddress("word/document.xml", List.of(0, 12), 0, 8);
    var heading = new ParagraphModel(List.of(
        new RunModel("（1）标题", "Times New Roman", "宋体", null, 24, false, source)),
        0, null, null, 360, "auto", source, 200);
    var document = new ParsedDocument("source", List.of(chapterHeading(), heading), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.source().equals(source)).toList();

    assertTrue(results.stream().noneMatch(result -> result.ruleId().startsWith("BODY-")), results.toString());
  }

  @Test
  void rejectsLowerLevelHeadingWithTheWrongNumberMarker() {
    var source = new SourceAddress("word/document.xml", List.of(0, 12), 0, 7);
    var heading = new ParagraphModel(List.of(
        new RunModel("（1）.标题", "Times New Roman", "宋体", null, 24, false, source)),
        0, null, null, 360, "auto", source, 200);
    var document = new ParsedDocument("source", List.of(chapterHeading(), heading), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("HEAD-05-MARKER")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("LOWER_HEADING_MARKER_MISMATCH", result.reasonCode());
  }

  @Test
  void doesNotTreatPunctuatedEnumeratedParagraphsAsLowerLevelHeadings() {
    var paragraphs = new java.util.ArrayList<ParagraphModel>();
    paragraphs.add(chapterHeading());
    paragraphs.add(paragraph("本文围绕系统的核心内容包括：", List.of(0, 11)));
    String[] items = {
        "（1）需求分析；", "（2）总体设计；", "（3）模块实现；",
        "（4）系统测试；", "（5）结果分析；", "（6）总结改进。"
    };
    for (int i = 0; i < items.length; i++) {
      var source = new SourceAddress("word/document.xml", List.of(0, 12 + i), 0, items[i].length());
      paragraphs.add(new ParagraphModel(List.of(
          new RunModel(items[i], "Times New Roman", "宋体", null, 24, false, source)),
          480, null, null, 360, "auto", source, 200));
    }
    var document = new ParsedDocument("source", paragraphs, List.of(), List.of());

    assertTrue(new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .noneMatch(result -> result.ruleId().startsWith("HEAD-05-")));
  }

  @Test
  void doesNotTreatParenthesizedBodyListAsLowerLevelHeading() {
    var source = new SourceAddress("word/document.xml", List.of(0, 12), 0, 16);
    var body = new ParagraphModel(List.of(
        new RunModel("结果为（1）项内容", "Times New Roman", "宋体", null, 24, false, source)),
        480, null, null, 360, "auto", source, 200);
    var document = new ParsedDocument("source", List.of(chapterHeading(), body), List.of(), List.of());

    assertTrue(new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .noneMatch(result -> result.ruleId().startsWith("HEAD-05-")));
  }

  @Test
  void usesDedicatedFontRuleForAbstractHeadingWithoutGenericHeadingComment() {
    var source = new SourceAddress("word/document.xml", List.of(0, 12), 0, 8);
    var heading = new ParagraphModel(List.of(
        new RunModel("ABSTRACT", "Times New Roman", "Times New Roman", null, 32, true, source)),
        null, null, null, null, null, source, 0, null, "center");
    var document = new ParsedDocument("source", List.of(heading), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(result -> result.ruleId().equals("EN-01-FONT")));
    assertTrue(results.stream().noneMatch(result -> result.ruleId().equals("HEADING-FONT")));
  }

  @Test
  void reportsCaptionBelowTableAsPositionError() {
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(1), 0, 0),
        ADDRESS, "表1-2 表题", 2, 2, true, true, true, false, false, true);
    var document = new ParsedDocument("source", List.of(), List.of(), List.of(),
        List.of(table), List.of(), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(r -> r.ruleId().equals("TAB-03")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("TABLE_CAPTION_BELOW", result.reasonCode());
  }

  @Test
  void ignoresFrontMatterLayoutTablesWhenCheckingBodyTableRules() {
    var cover = paragraph("封面信息", List.of(0, 0));
    var cellChapterText = paragraph("第一章 绪论", List.of(0, 1, 0, 0));
    var chapter = paragraph("第一章 绪论", List.of(0, 3));
    var layoutTable = new TableModel(new SourceAddress("word/document.xml", List.of(0, 1), 0, 0),
        null, null, 3, 2, false, false, false, true, false, false);
    var document = new ParsedDocument("source", List.of(cover, cellChapterText, chapter), List.of(), List.of(),
        List.of(layoutTable), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().noneMatch(result -> result.ruleId().startsWith("TAB-")));
  }

  @Test
  void treatsBorderlessEquationNumberLayoutAsFormulaAndKeepsCheckingDataTables() {
    var chapterSource = new SourceAddress("word/document.xml", List.of(0, 0), 0, 7);
    var chapter = new ParagraphModel(List.of(new RunModel("第三章 结果", "Times New Roman", "黑体",
        null, 32, true, chapterSource)), 0, null, null, null, null, chapterSource, 0);
    var blank = paragraph("", List.of(0, 1));
    var formulaParagraphSource = new SourceAddress("word/document.xml", List.of(0, 2, 0, 0, 0), 0, 0);
    var numberSource = new SourceAddress("word/document.xml", List.of(0, 2, 0, 1, 0), 0, 5);
    var formulaParagraph = new ParagraphModel(List.of(), 0, null, null, null, null,
        formulaParagraphSource, 0, null, "center");
    var numberParagraph = new ParagraphModel(List.of(new RunModel("(3-1)", "Times New Roman",
        "Times New Roman", null, 21, false, numberSource)), 0, null, null, null, null,
        numberSource, 0, null, "right");
    var bodyReference = paragraph("由公式（3-1）可知。", List.of(0, 3));
    var equationTableSource = new SourceAddress("word/document.xml", List.of(0, 2), 0, 0);
    var equationTable = new TableModel(equationTableSource, null, null, 1, 2,
        false, false, false, false, false, false, false, List.of(), List.of(),
        List.of("", "(3-1)"), null, null, false);
    var dataTableSource = new SourceAddress("word/document.xml", List.of(0, 4), 0, 0);
    var dataTable = new TableModel(dataTableSource, null, null, 2, 2,
        false, false, false, false, false, false, false, List.of(), List.of(),
        List.of("参数", "数值"), null, null, false);
    var styledTableSource = new SourceAddress("word/document.xml", List.of(0, 5), 0, 0);
    var styledTable = new TableModel(styledTableSource, null, null, 1, 2,
        false, false, false, false, false, false, false, List.of(), List.of(),
        List.of("", "(3-2)"), null, null, false, true);
    var equation = new cn.luwneshacha.thesis.document.EquationModel(
        new SourceAddress("word/document.xml", List.of(0, 2, 0, 0, 0, 0), 0, 1),
        "OMML", "x", "3-1", true, false, java.util.Set.of("oMath"),
        java.util.Set.of(), true, numberSource);
    var styledFormulaSource = new SourceAddress("word/document.xml", List.of(0, 5, 0, 0, 0, 0), 0, 1);
    var styledNumberSource = new SourceAddress("word/document.xml", List.of(0, 5, 0, 1, 0), 0, 5);
    var styledFormulaParagraph = new ParagraphModel(List.of(), 0, null, null, null, null,
        new SourceAddress("word/document.xml", List.of(0, 5, 0, 0, 0), 0, 0), 0, null, "center");
    var styledNumberParagraph = new ParagraphModel(List.of(new RunModel("(3-2)", "Times New Roman",
        "Times New Roman", null, 21, false, styledNumberSource)), 0, null, null, null, null,
        styledNumberSource, 0, null, "right");
    var styledEquation = new cn.luwneshacha.thesis.document.EquationModel(styledFormulaSource,
        "OMML", "y", "3-2", true, false, java.util.Set.of("oMath"),
        java.util.Set.of(), true, styledNumberSource);
    var document = new ParsedDocument("source", List.of(chapter, blank, formulaParagraph,
        numberParagraph, bodyReference, styledFormulaParagraph, styledNumberParagraph), List.of(), List.of(),
        List.of(equationTable, dataTable, styledTable), List.of(equation, styledEquation), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().noneMatch(result -> result.ruleId().startsWith("TAB-")
        && result.source().elementPath().equals(equationTableSource.elementPath())), results.toString());
    assertTrue(results.stream().anyMatch(result -> result.ruleId().equals("TAB-03")
        && result.source().elementPath().equals(dataTableSource.elementPath())), results.toString());
    assertTrue(results.stream().anyMatch(result -> result.ruleId().equals("TAB-03")
        && result.source().elementPath().equals(styledTableSource.elementPath())), results.toString());
    assertEquals(Judgement.PASS, results.stream().filter(result -> result.ruleId().equals("EQ-02"))
        .findFirst().orElseThrow().judgement());
    assertEquals(Judgement.PASS, results.stream().filter(result -> result.ruleId().equals("EQ-03"))
        .findFirst().orElseThrow().judgement());
  }

  @Test
  void reportsUnparenthesizedEquationNumberAsAParenthesesError() {
    var chapter = chapterHeading();
    var numberSource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 3);
    var equation = new cn.luwneshacha.thesis.document.EquationModel(
        new SourceAddress("word/document.xml", List.of(0, 1, 0), 0, 1), "OMML", "x", "1-1",
        true, false, java.util.Set.of("oMath"), java.util.Set.of(), true, numberSource);
    var equationNumber = new ParagraphModel(List.of(new RunModel("1-1", "Times New Roman",
        "Times New Roman", null, 21, false, numberSource)), 0, null, null, null, null,
        numberSource, 0, null, "right");
    var reference = paragraph("由公式1-1可知。", List.of(0, 2));
    var document = new ParsedDocument("source", List.of(chapter, equationNumber, reference), List.of(), List.of(),
        List.of(), List.of(equation), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> "EQUATION_NUMBER_PARENTHESES_MISSING".equals(item.reasonCode()))
        .findFirst().orElseThrow();

    assertEquals("EQ-01", result.ruleId());
    assertEquals(Judgement.FAIL, result.judgement());
  }

  @Test
  void acceptsFullWidthParenthesesForEquationNumber() {
    var results = checkEquationNumber("（1-1）");

    assertTrue(results.stream().noneMatch(item -> "EQUATION_NUMBER_PARENTHESES_MISSING".equals(item.reasonCode())));
  }

  @Test
  void rejectsWhitespaceInsideEquationNumberParentheses() {
    var results = checkEquationNumber("( 1-1 )");
    var result = results.stream()
        .filter(item -> "EQUATION_NUMBER_PARENTHESES_MISSING".equals(item.reasonCode()))
        .findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertTrue(results.stream().noneMatch(item -> item.ruleId().equals("EQ-02")
        && item.judgement() == Judgement.PASS), results.toString());
  }

  @Test
  void checksEquationNumberFormattingAtItsXmlSourceRange() {
    var paragraphSource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 9);
    var numberSource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 3);
    var equation = new cn.luwneshacha.thesis.document.EquationModel(
        new SourceAddress("word/document.xml", List.of(0, 1, 0), 0, 1), "OMML", "x", "1-1",
        true, false, java.util.Set.of("oMath"), java.util.Set.of(), true, numberSource);
    var paragraph = new ParagraphModel(List.of(new RunModel("1-1 (1-1)", "Times New Roman",
        "宋体", null, 21, false, paragraphSource)), 0, null, null, null, null,
        paragraphSource, 0, null, "right");
    var document = new ParsedDocument("source", List.of(chapterHeading(), paragraph), List.of(), List.of(),
        List.of(), List.of(equation), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("EQ-02")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("EQUATION_NUMBER_FORMAT_MISMATCH", result.reasonCode());
  }

  @Test
  void rejectsMismatchedEquationNumberParentheses() {
    var result = checkEquationNumber("（1-1)").stream()
        .filter(item -> "EQUATION_NUMBER_PARENTHESES_MISSING".equals(item.reasonCode()))
        .findFirst().orElseThrow();

    assertEquals("EQ-01", result.ruleId());
    assertEquals(Judgement.FAIL, result.judgement());
  }

  private static List<CheckResult> checkEquationNumber(String numberText) {
    var numberSource = new SourceAddress("word/document.xml", List.of(0, 1), 0, numberText.length());
    var equation = new cn.luwneshacha.thesis.document.EquationModel(
        new SourceAddress("word/document.xml", List.of(0, 1, 0), 0, 1), "OMML", "x", "1-1",
        true, false, java.util.Set.of("oMath"), java.util.Set.of(), true, numberSource);
    var numberParagraph = new ParagraphModel(List.of(new RunModel(numberText, "Times New Roman",
        "Times New Roman", null, 21, false, numberSource)), 0, null, null, null, null,
        numberSource, 0, null, "right");
    var document = new ParsedDocument("source", List.of(chapterHeading(), numberParagraph), List.of(), List.of(),
        List.of(), List.of(equation), List.of());

    return new RuleEngine().check(document, RuleSet.schoolV1());
  }

  @Test
  void requiresChineseKeywordsToBeJustified() {
    var title = paragraph("摘    要", List.of(0, 0));
    var abstractText = paragraph("本文介绍研究内容。", List.of(0, 2));
    var source = new SourceAddress("word/document.xml", List.of(0, 4), 0, 14);
    var keywords = new ParagraphModel(List.of(new RunModel("关键词：智能；系统；推荐", "Times New Roman", "宋体",
        null, 24, true, source)), 0, null, null, 360, "auto", source, 200, null, "left");
    var document = new ParsedDocument("source", List.of(title, paragraph("", List.of(0, 1)), abstractText,
        paragraph("", List.of(0, 3)), keywords), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("ABSTRACT-KEYWORD-ALIGN"))
        .findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("KEYWORD_ALIGNMENT_MISMATCH", result.reasonCode());
  }

  @Test
  void reportsReviewWhenChineseKeywordBoldPropertyIsMissingFromEffectiveStyles() {
    var title = paragraph("摘    要", List.of(0, 10));
    var source = new SourceAddress("word/document.xml", List.of(0, 12), 0, 12);
    var keywords = new ParagraphModel(List.of(new RunModel("关键词：智能；系统；推荐", "Times New Roman", "宋体",
        null, 24, null, source)), 0, null, null, 360, "auto", source, 200, null, "both");
    var document = new ParsedDocument("source", List.of(title, paragraph("", List.of(0, 11)), keywords), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("ABSTRACT-KEYWORD-BOLD"))
        .findFirst().orElseThrow();

    assertEquals(Judgement.REVIEW, result.judgement());
    assertEquals("BOLD_UNKNOWN", result.reasonCode());
  }

  @Test
  void checksCoverFieldFormattingWhenLabelAndValueShareOneRun() {
    var source = new SourceAddress("word/document.xml", List.of(0, 1), 0, 12);
    var field = new ParagraphModel(List.of(new RunModel("\u5B66\u751F\u59D3\u540D\uFF1A\u5F20\u4E09", "仿宋", "仿宋", null,
        32, true, source)), 0, null, null, null, null, source, 0, null, "left");
    var document = new ParsedDocument("source", List.of(paragraph("\u672C\u79D1\u751F\u6BD5\u4E1A\u8BBE\u8BA1\uFF08\u8BBA\u6587\uFF09", List.of(0, 0)), field,
        paragraph("\u6458 \u8981", List.of(0, 2))), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("COVER-03-FONT")
        && item.judgement() == Judgement.PASS), results.toString());
    assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("COVER-03-BOLD")
        && item.judgement() == Judgement.PASS));
    assertTrue(results.stream().noneMatch(item -> item.reasonCode().contains("SHARE_RUN")));
  }

  @Test
  void reportsMissingTableUnitsAndRepeatedPlaceholderText() {
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(0, 4), 0, 0),
        ADDRESS, "表1-1 数据", 2, 2, true, true, true, false, true, false,
        false, List.of(1200, 1200), List.of(360, 360), List.of("数量", "结果"), null, null, true);
    var document = new ParsedDocument("source", List.of(), List.of(), List.of(),
        List.of(table), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());
    assertTrue(results.stream().anyMatch(r -> r.ruleId().equals("TAB-04")
        && r.reasonCode().equals("TABLE_UNITS_MISSING")
        && r.judgement() == Judgement.FAIL));
    assertTrue(results.stream().anyMatch(r -> r.ruleId().equals("TAB-06")
        && r.reasonCode().equals("TABLE_REPEATED_PLACEHOLDER")));
  }

  @Test
  void rejectsCenteredTableNoteBecauseNotesBelongBelowTheTableAtLeft() {
    var chapter = chapterHeading();
    var caption = paragraph("表1-1 数据", List.of(0, 1));
    var tableSource = new SourceAddress("word/document.xml", List.of(0, 2), 0, 0);
    var noteSource = new SourceAddress("word/document.xml", List.of(0, 3), 0, 6);
    var note = new ParagraphModel(List.of(new RunModel("注：说明", "Times New Roman", "宋体", null,
        21, false, noteSource)), 0, null, null, 360, "auto", noteSource, 0, null, "center");
    var table = new TableModel(tableSource, caption.source(), "表1-1 数据", 2, 2,
        true, true, true, false, true, false, false, List.of(1000, 1000), List.of(),
        List.of("项目", "结果"), "注：说明", noteSource, false);
    var document = new ParsedDocument("source", List.of(chapter, caption, note), List.of(), List.of(),
        List.of(table), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("TAB-07")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("TABLE_NOTE_NOT_LEFT_ALIGNED", result.reasonCode());
  }

  @Test
  void acceptsLeftAlignedTableNote() {
    var chapter = chapterHeading();
    var caption = paragraph("表1-1 数据", List.of(0, 1));
    var tableSource = new SourceAddress("word/document.xml", List.of(0, 2), 0, 0);
    var noteSource = new SourceAddress("word/document.xml", List.of(0, 3), 0, 6);
    var note = new ParagraphModel(List.of(new RunModel("注：说明", "Times New Roman", "宋体", null,
        21, false, noteSource)), 0, null, null, 360, "auto", noteSource, 0, null, "left");
    var table = new TableModel(tableSource, caption.source(), "表1-1 数据", 2, 2,
        true, true, true, false, true, false, false, List.of(1000, 1000), List.of(),
        List.of("项目", "结果"), "注：说明", noteSource, false);
    var document = new ParsedDocument("source", List.of(chapter, caption, note), List.of(), List.of(),
        List.of(table), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().noneMatch(item -> item.ruleId().equals("TAB-07")));
  }

  @Test
  void rejectsSplitContinuationTableWhenItsHeaderDoesNotRepeatTheOriginal() {
    var originalCaption = paragraph("表1-1 测试数据", List.of(0, 1));
    var continuationCaption = paragraph("续表1-1", List.of(0, 3));
    var original = new TableModel(new SourceAddress("word/document.xml", List.of(0, 2), 0, 0),
        originalCaption.source(), "表1-1 测试数据", 2, 2, true, true, true, false, true, false,
        false, List.of(), List.of(), List.of("项目", "要求"), null, null, false);
    var continuation = new TableModel(new SourceAddress("word/document.xml", List.of(0, 4), 0, 0),
        continuationCaption.source(), "续表1-1", 2, 2, true, true, true, false, true, false,
        false, List.of(), List.of(), List.of("项目", "测试结果"), null, null, false);
    var document = new ParsedDocument("source", List.of(chapterHeading(), originalCaption, continuationCaption),
        List.of(), List.of(), List.of(original, continuation), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("TAB-08")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("TABLE_CONTINUATION_HEADER_MISMATCH", result.reasonCode());
  }

  @Test
  void acceptsSplitContinuationTableWhenItsHeaderRepeatsTheOriginal() {
    var originalCaption = paragraph("表1-1 测试数据", List.of(0, 1));
    var continuationCaption = paragraph("续表1-1", List.of(0, 3));
    var original = new TableModel(new SourceAddress("word/document.xml", List.of(0, 2), 0, 0),
        originalCaption.source(), "表1-1 测试数据", 2, 2, true, true, true, false, true, false,
        false, List.of(), List.of(), List.of("项目", "要求"), null, null, false);
    var continuation = new TableModel(new SourceAddress("word/document.xml", List.of(0, 4), 0, 0),
        continuationCaption.source(), "续表1-1", 2, 2, true, true, true, false, true, false,
        false, List.of(), List.of(), List.of("项目", "要求"), null, null, false);
    var document = new ParsedDocument("source", List.of(chapterHeading(), originalCaption, continuationCaption),
        List.of(), List.of(), List.of(original, continuation), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().noneMatch(item -> item.ruleId().equals("TAB-08")));
  }

  @Test
  void checksHeaderOfOneRowContinuationTable() {
    var originalCaption = paragraph("表1-1 测试数据", List.of(0, 1));
    var continuationCaption = paragraph("续表1-1", List.of(0, 3));
    var original = new TableModel(new SourceAddress("word/document.xml", List.of(0, 2), 0, 0),
        originalCaption.source(), "表1-1 测试数据", 2, 2, true, true, true, false, true, false,
        false, List.of(), List.of(), List.of("项目", "要求"), null, null, false);
    var continuation = new TableModel(new SourceAddress("word/document.xml", List.of(0, 4), 0, 0),
        continuationCaption.source(), "续表1-1", 1, 2, true, true, true, false, true, false,
        false, List.of(), List.of(), List.of("项目", "测试结果"), null, null, false);
    var document = new ParsedDocument("source", List.of(chapterHeading(), originalCaption, continuationCaption),
        List.of(), List.of(), List.of(original, continuation), List.of(), List.of());

    CheckResult result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("TAB-08")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("TABLE_CONTINUATION_HEADER_MISMATCH", result.reasonCode());
  }

  @Test
  void rejectsContinuationHeaderThatDiffersByWhitespace() {
    var originalCaption = paragraph("表1-1 测试数据", List.of(0, 1));
    var continuationCaption = paragraph("续表1-1", List.of(0, 3));
    var original = new TableModel(new SourceAddress("word/document.xml", List.of(0, 2), 0, 0),
        originalCaption.source(), "表1-1 测试数据", 2, 2, true, true, true, false, true, false,
        false, List.of(), List.of(), List.of("项目", "要求"), null, null, false);
    var continuation = new TableModel(new SourceAddress("word/document.xml", List.of(0, 4), 0, 0),
        continuationCaption.source(), "续表1-1", 2, 2, true, true, true, false, true, false,
        false, List.of(), List.of(), List.of("项 目", "要求"), null, null, false);
    var document = new ParsedDocument("source", List.of(chapterHeading(), originalCaption, continuationCaption),
        List.of(), List.of(), List.of(original, continuation), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("TAB-08")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("TABLE_CONTINUATION_HEADER_MISMATCH", result.reasonCode());
  }

  @Test
  void rejectsContinuationCaptionWithoutChapterAndSequenceNumber() {
    var continuationCaption = paragraph("续表1", List.of(0, 1));
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(0, 2), 0, 0),
        continuationCaption.source(), "续表1", 2, 2, true, true, true, false, true, false);
    var document = new ParsedDocument("source", List.of(chapterHeading(), continuationCaption), List.of(), List.of(),
        List.of(table), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("TAB-03")
            && item.reasonCode().equals("TABLE_CONTINUATION_NUMBER_FORMAT"))
        .findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("TABLE_CONTINUATION_NUMBER_FORMAT", result.reasonCode());
    assertEquals("续表标记只能写“续表”加编号，例如“续表4-1”", RuleDescriptions.reason(result));
    assertEquals("删去续表编号后的文字，仅保留“续表”加编号，例如“续表4-1”。",
        RuleDescriptions.suggestion(result));
  }

  @Test
  void reportsMissingReferenceFieldsAndNumberGap() {
    var first = new ReferenceEntryModel(new SourceAddress("word/document.xml", List.of(0, 1), 0, 20),
        "1", "[1] 作者. 书名[M]. 出版社，2024.", "M", false,
        "作者", "书名", "出版社", "2024", null);
    var third = new ReferenceEntryModel(new SourceAddress("word/document.xml", List.of(0, 2), 0, 12),
        "3", "[3] 只有题名[M].", "M", false,
        null, "只有题名", null, null, null);
    var document = new ParsedDocument("source", List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of(first, third));

    var results = new RuleEngine().check(document, RuleSet.schoolV1());
    assertTrue(results.stream().noneMatch(r -> r.ruleId().equals("REF-06")));
    assertTrue(results.stream().anyMatch(r -> r.ruleId().equals("REF-07")
        && r.reasonCode().equals("REFERENCE_NUMBER_SEQUENCE_MISMATCH")));
  }

  @Test
  void referenceSequenceRemainsBasedOnPositionAndRejectsLeadingZeroes() {
    var first = new ReferenceEntryModel(new SourceAddress("word/document.xml", List.of(0, 1), 0, 8),
        "1", "[1] 文献一[M].", "M", false);
    var skipped = new ReferenceEntryModel(new SourceAddress("word/document.xml", List.of(0, 2), 0, 8),
        "3", "[3] 文献三[M].", "M", false);
    var padded = new ReferenceEntryModel(new SourceAddress("word/document.xml", List.of(0, 3), 0, 9),
        "04", "[04] 文献四[M].", "M", false);

    var results = new ReferenceRuleChecker().check(List.of(first, skipped, padded));

    assertTrue(results.stream().anyMatch(result -> result.actual().equals("3")
        && result.expected().equals("2")
        && result.reasonCode().equals("REFERENCE_NUMBER_SEQUENCE_MISMATCH")));
    assertTrue(results.stream().anyMatch(result -> result.actual().equals("04")
        && result.expected().equals("3")
        && result.reasonCode().equals("REFERENCE_NUMBER_INVALID")));
  }

  @Test
  void acceptsChineseAcknowledgementTitleWithFourInterwordSpaces() {
    var heading = paragraph("致    谢", List.of(0, 1));
    var body = paragraph("感谢老师的指导。", List.of(0, 2));
    var document = new ParsedDocument("source", List.of(heading, paragraph("", List.of(0, 1, 1)), body),
        List.of(), List.of(), List.of(), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("END-01-SPACING")).findFirst().orElseThrow();
    assertEquals(Judgement.PASS, result.judgement());
    assertEquals("", result.reasonCode());
  }

  @Test
  void acceptsManualLineBreakParagraphAfterAcknowledgementTitle() {
    var heading = paragraph("致    谢", List.of(0, 1));
    var document = new ParsedDocument("source", List.of(heading, paragraph("\n", List.of(0, 1, 1)),
        paragraph("感谢老师的指导。", List.of(0, 2))), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().noneMatch(item -> item.reasonCode().equals("ACKNOWLEDGEMENT_TITLE_BLANK_LINE_MISSING")
        || item.reasonCode().equals("ACKNOWLEDGEMENT_TITLE_BLANK_LINE_COUNT_MISMATCH")), results.toString());
  }

  @Test
  void rejectsChineseAcknowledgementTitleWithoutFourInterwordSpaces() {
    var heading = paragraph("致谢", List.of(0, 1));
    var document = new ParsedDocument("source", List.of(heading, paragraph("", List.of(0, 1, 1)),
        paragraph("感谢老师的指导。", List.of(0, 2))),
        List.of(), List.of(), List.of(), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("END-01-SPACING")).findFirst().orElseThrow();
    assertEquals(Judgement.FAIL, result.judgement(), result.toString());
    assertEquals("ACKNOWLEDGEMENT_TITLE_SPACING_MISMATCH", result.reasonCode());
  }

  @Test
  void rejectsEnglishAcknowledgementAliasAsNonconformingTitleText() {
    var heading = paragraph("ACKNOWLEDGMENTS", List.of(0, 1));
    var document = new ParsedDocument("source", List.of(heading), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("END-01-SPACING")
        && item.judgement() == Judgement.FAIL));
  }

  @Test
  void rejectsEnglishAppendixAliasAsNonconformingTitleText() {
    var heading = paragraph("APPENDIX", List.of(0, 1));
    var document = new ParsedDocument("source", List.of(heading), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("APP-02-SPACING")
        && item.judgement() == Judgement.FAIL));
  }

  @Test
  void doesNotImposeUnspecifiedLineSpacingOnAcknowledgementTitle() {
    var heading = new ParagraphModel(List.of(new RunModel("致    谢", "Times New Roman", "黑体", null, 32, false, ADDRESS)),
        0, null, null, 240, "auto", ADDRESS, 0);
    var document = new ParsedDocument("source", List.of(heading, paragraph("", List.of(0, 1, 1))),
        List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().noneMatch(item -> item.ruleId().equals("END-01-LINE")));

  }

  @Test
  void acceptsBlankParagraphAfterAbstractTitle() {
    var title = paragraph("摘要", List.of(0, 0));
    var blank = paragraph("", List.of(0, 1));
    var body = new ParagraphModel(List.of(new RunModel("这是摘要正文。", "Times New Roman", "宋体", null, 24, false,
        new SourceAddress("word/document.xml", List.of(0, 2), 0, 7))),
        480, null, null, 360, "auto", new SourceAddress("word/document.xml", List.of(0, 2), 0, 7), 200);
    var document = new ParsedDocument("source", List.of(title, blank, body), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().noneMatch(item -> item.reasonCode().equals("ABSTRACT_TITLE_BLANK_LINE_MISSING")));
  }

  @Test
  void acceptsIdeographicSpaceParagraphAsBlankLineAfterAbstractTitle() {
    var title = paragraph("摘要", List.of(0, 0));
    var blank = paragraph("\u3000", List.of(0, 1));
    var body = paragraph("这是摘要正文。", List.of(0, 2));
    var document = new ParsedDocument("source", List.of(title, blank, body), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().noneMatch(item -> item.reasonCode().equals("ABSTRACT_TITLE_BLANK_LINE_MISSING")));
  }

  @Test
  void rejectsMultipleBlankParagraphsAfterAbstractTitle() {
    var title = paragraph("摘要", List.of(0, 0));
    var body = paragraph("这是摘要正文。", List.of(0, 3));
    var document = new ParsedDocument("source", List.of(title, paragraph("", List.of(0, 1)),
        paragraph("", List.of(0, 2)), body), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(item -> item.reasonCode().equals("ABSTRACT_TITLE_BLANK_LINE_COUNT_MISMATCH")));
  }

  @Test
  void rejectsMultipleBlankParagraphsBeforeAbstractKeywords() {
    var title = paragraph("摘要", List.of(0, 0));
    var abstractText = paragraph("摘要正文。", List.of(0, 1));
    var keywords = paragraph("关键词：格式；检查；规则", List.of(0, 4));
    var document = new ParsedDocument("source", List.of(title, abstractText, paragraph("", List.of(0, 2)),
        paragraph("", List.of(0, 3)), keywords), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(item -> item.reasonCode().equals("ABSTRACT_KEYWORD_BLANK_LINE_COUNT_MISMATCH")));
  }

  @Test
  void rejectsMultipleBlankParagraphsAfterContentsTitle() {
    var contents = paragraph("目    录", List.of(0, 0));
    var entry = paragraph("第一章 引言……1", List.of(0, 3));
    var document = new ParsedDocument("source", List.of(contents, paragraph("", List.of(0, 1)),
        paragraph("", List.of(0, 2)), entry), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(item -> item.reasonCode().equals("TOC_TITLE_BLANK_LINE_COUNT_MISMATCH")));
  }

  @Test
  void rejectsMultipleBlankParagraphsAfterChapterTitle() {
    var chapter = chapterHeading();
    var body = paragraph("正文内容。", List.of(0, 3));
    var document = new ParsedDocument("source", List.of(chapter, paragraph("", List.of(0, 1)),
        paragraph("", List.of(0, 2)), body), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(item -> item.reasonCode().equals("CHAPTER_BODY_BLANK_LINE_COUNT_MISMATCH")));
  }

  @Test
  void doesNotImposeAChineseAbstractLengthLimit() {
    var title = titleParagraph("摘要", new SourceAddress("word/document.xml", List.of(0, 40), 0, 2));
    String text = "中".repeat(301);
    var source = new SourceAddress("word/document.xml", List.of(0, 41), 0, text.length());
    var body = new ParagraphModel(List.of(new RunModel(text, "Times New Roman", "宋体", null,
        24, false, source)), 480, null, null, 360, "auto", source, 200, null, "both");
    var document = new ParsedDocument("source", List.of(title, body), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().noneMatch(result -> result.ruleId().equals("ZH-06-LENGTH")));
  }

  @Test
  void doesNotImposeAnEnglishAbstractWordLimit() {
    var titleSource = new SourceAddress("word/document.xml", List.of(0, 50), 0, 8);
    var title = new ParagraphModel(List.of(new RunModel("ABSTRACT", "Times New Roman", "", null,
        32, true, titleSource)), 0, null, null, null, null, titleSource, 0, null, "center");
    var blank = paragraph("", List.of(0, 51));
    String text = String.join(" ", java.util.Collections.nCopies(251, "word"));
    var source = new SourceAddress("word/document.xml", List.of(0, 52), 0, text.length());
    var body = new ParagraphModel(List.of(new RunModel(text, "Times New Roman", "", null,
        24, false, source)), 480, null, null, 360, "auto", source, 200, null, "left");
    var document = new ParsedDocument("source", List.of(title, blank, body), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().noneMatch(result -> result.ruleId().equals("EN-06-LENGTH")));
  }

  @Test
  void requiresEnglishKeywordLabelToBeBold() {
    var titleSource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 8);
    var keywordSource = new SourceAddress("word/document.xml", List.of(0, 3), 0, 24);
    var title = new ParagraphModel(List.of(new RunModel("ABSTRACT", "Times New Roman", "", null, 32, true, titleSource)),
        0, null, null, 360, "auto", titleSource, 0, null, "center");
    var blank = paragraph("", List.of(0, 2));
    var keywords = new ParagraphModel(List.of(
        new RunModel("Keywords", "Times New Roman", "", null, 24, false, keywordSource),
        new RunModel(": data; format; check", "Times New Roman", "", null, 24, false, keywordSource)),
        480, null, null, 360, "auto", keywordSource, 200, null, "left");
    var document = new ParsedDocument("source", List.of(title, blank, keywords), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("EN-03-BOLD")).findFirst();
    assertTrue(result.isPresent());
    assertEquals(Judgement.FAIL, result.orElseThrow().judgement());
    assertEquals("KEYWORD_LABEL_NOT_BOLD", result.orElseThrow().reasonCode());
  }

  @Test
  void acceptsTheFullWidthColonUsedByTheSchoolEnglishKeywordExample() {
    var titleSource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 8);
    var keywordSource = new SourceAddress("word/document.xml", List.of(0, 3), 0, 30);
    var title = new ParagraphModel(List.of(new RunModel("ABSTRACT", "Times New Roman", "", null,
        32, true, titleSource)), 0, null, null, null, null, titleSource, 0, null, "center");
    var blank = paragraph("", List.of(0, 2));
    var keywords = new ParagraphModel(List.of(
        new RunModel("Keywords", "Times New Roman", "", null, 24, true, keywordSource),
        new RunModel("：alpha；beta；gamma", "Times New Roman", "", null, 24, false, keywordSource)),
        480, null, null, 360, "auto", keywordSource, 200, null, "left");
    var document = new ParsedDocument("source", List.of(title, blank, keywords), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("EN-03-FORMAT")).findFirst().orElseThrow();

    assertEquals(Judgement.PASS, result.judgement());
  }

  @Test
  void rejectsMixedEnglishKeywordSeparators() {
    var titleSource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 8);
    var keywordSource = new SourceAddress("word/document.xml", List.of(0, 3), 0, 30);
    var title = new ParagraphModel(List.of(new RunModel("ABSTRACT", "Times New Roman", "", null,
        32, true, titleSource)), 0, null, null, null, null, titleSource, 0, null, "center");
    var blank = paragraph("", List.of(0, 2));
    var keywords = new ParagraphModel(List.of(new RunModel(
        "Keywords: alpha; beta；gamma", "Times New Roman", "", null, 24, true, keywordSource)),
        480, null, null, 360, "auto", keywordSource, 200, null, "left");
    var document = new ParsedDocument("source", List.of(title, blank, keywords), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("EN-03-FORMAT")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("ENGLISH_KEYWORD_SEPARATOR_MISMATCH", result.reasonCode());
  }

  @Test
  void doesNotEnforceUnspecifiedEnglishKeywordCountLimit() {
    var titleSource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 8);
    var keywordSource = new SourceAddress("word/document.xml", List.of(0, 3), 0, 96);
    var title = new ParagraphModel(List.of(new RunModel("ABSTRACT", "Times New Roman", "", null,
        32, true, titleSource)), 0, null, null, null, null, titleSource, 0, null, "center");
    var blank = paragraph("", List.of(0, 2));
    var keywords = new ParagraphModel(List.of(new RunModel(
        "Keywords: alpha; beta; gamma; delta; epsilon; zeta", "Times New Roman", "", null,
        24, true, keywordSource)), 480, null, null, 360, "auto", keywordSource, 200, null, "left");
    var document = new ParsedDocument("source", List.of(title, blank, keywords), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("EN-03-FORMAT")).toList();

    assertEquals(1, results.size(), results.toString());
    assertEquals(Judgement.PASS, results.get(0).judgement());
  }

  @Test
  void rejectsConflictingPhysicalAndCharacterIndentProperties() {
    var run = new RunModel("正文", "Times New Roman", "宋体", null, 24, false, ADDRESS);
    var paragraph = new ParagraphModel(List.of(run), 480, null, null, null, 360, "auto",
        ADDRESS, 199, null, "left", null, null, null);
    var document = new ParsedDocument("source", List.of(chapterHeading(), paragraph),
        List.of(new SectionModel(1474, 1474, 1474, 1474, ADDRESS)), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("BODY-INDENT")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("INDENT_MISMATCH", result.reasonCode());
  }

  @Test
  void acceptsMatchingPhysicalAndCharacterIndentProperties() {
    var run = new RunModel("正文", "Times New Roman", "宋体", null, 24, false, ADDRESS);
    var paragraph = new ParagraphModel(List.of(run), 480, null, null, null, 360, "auto",
        ADDRESS, 200, null, "left", null, null, null);
    var document = new ParsedDocument("source", List.of(chapterHeading(), paragraph),
        List.of(new SectionModel(1474, 1474, 1474, 1474, ADDRESS)), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("BODY-INDENT")).findFirst().orElseThrow();

    assertEquals(Judgement.PASS, result.judgement());
  }

  @Test
  void failsWhenPageMarginValueIsAbsent() {
    var section = new SectionModel(null, 1474, 1474, 1474, ADDRESS);
    var document = new ParsedDocument("source", List.of(), List.of(section), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("PAGE-01")).findFirst().orElseThrow();

    assertEquals(Judgement.REVIEW, result.judgement());
    assertEquals("MARGIN_UNKNOWN", result.reasonCode());
  }

  @Test
  void marksLineSpacingForReviewWhenEffectiveStyleIsMissing() {
    var source = new SourceAddress("word/document.xml", List.of(0, 1), 0, 4);
    var body = new ParagraphModel(List.of(new RunModel("正文内容", "Times New Roman", "宋体", null,
        24, false, source)), 480, null, null, null, "auto", source, 200, null);
    var document = new ParsedDocument("source", List.of(chapterHeading(), body), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("BODY-LINE")).findFirst().orElseThrow();

    assertEquals(Judgement.REVIEW, result.judgement());
    assertEquals("LINE_SPACING_UNKNOWN", result.reasonCode());
  }

  @Test
  void marksFontForReviewWhenEffectiveStyleIsMissing() {
    var source = new SourceAddress("word/document.xml", List.of(0, 1), 0, 2);
    var body = new ParagraphModel(List.of(new RunModel("正文", "Times New Roman", null, null,
        24, false, source)), 480, null, null, 360, "auto", source, 200, null);
    var document = new ParsedDocument("source", List.of(chapterHeading(), body), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("BODY-FONT")).findFirst().orElseThrow();

    assertEquals(Judgement.REVIEW, result.judgement());
    assertEquals("FONT_UNKNOWN", result.reasonCode());
  }

  @Test
  void marksFontSizeForReviewWhenEffectiveStyleIsMissing() {
    var source = new SourceAddress("word/document.xml", List.of(0, 1), 0, 2);
    var body = new ParagraphModel(List.of(new RunModel("正文", "Times New Roman", "宋体", null,
        null, false, source)), 480, null, null, 360, "auto", source, 200, null);
    var document = new ParsedDocument("source", List.of(chapterHeading(), body), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("BODY-SIZE")).findFirst().orElseThrow();

    assertEquals(Judgement.REVIEW, result.judgement());
    assertEquals("SIZE_UNKNOWN", result.reasonCode());
  }

  @Test
  void rejectsLeadingTextWhitespaceOnTopOfTheRequiredParagraphIndent() {
    var source = new SourceAddress("word/document.xml", List.of(0, 2), 0, 6);
    var body = new ParagraphModel(List.of(new RunModel("  正文内容", "Times New Roman", "宋体", null,
        24, false, source)), 480, null, null, null, null, source, null);
    var document = new ParsedDocument("source", List.of(chapterHeading(), body), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("BODY-INDENT")
            && item.source().elementPath().equals(source.elementPath()))
        .findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("INDENT_TEXT_WHITESPACE", result.reasonCode());
  }

  @Test
  void rejectsInvisibleLeadingTextWhitespaceOnTopOfTheRequiredParagraphIndent() {
    var source = new SourceAddress("word/document.xml", List.of(0, 2), 0, 6);
    var body = new ParagraphModel(List.of(new RunModel("\u200B正文内容", "Times New Roman", "宋体", null,
        24, false, source)), 480, null, null, null, null, source, null);
    var document = new ParsedDocument("source", List.of(chapterHeading(), body), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("BODY-INDENT")
            && item.source().elementPath().equals(source.elementPath()))
        .findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("INDENT_TEXT_WHITESPACE", result.reasonCode());
  }

  @Test
  void ignoresParagraphContainingOnlyInvisibleFormattingWhitespaceForBodyIndent() {
    var source = new SourceAddress("word/document.xml", List.of(0, 2), 0, 1);
    var blank = new ParagraphModel(List.of(new RunModel("\u200B", "Times New Roman", "宋体", null,
        24, false, source)), null, null, null, null, null, source, null);
    var document = new ParsedDocument("source", List.of(chapterHeading(), blank), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().noneMatch(item -> item.ruleId().equals("BODY-INDENT")
        && item.source().elementPath().equals(source.elementPath())));
  }

  @Test
  void rejectsPhysicalIndentThatDoesNotExactlyMatchTheRequiredTwoCharacterWidth() {
    var run = new RunModel("正文", "Times New Roman", "宋体", null, 24, false, ADDRESS);
    var paragraph = new ParagraphModel(List.of(run), 420, null, null, null, null, ADDRESS, null);
    var document = new ParsedDocument("source", List.of(chapterHeading(), paragraph),
        List.of(new SectionModel(1474, 1474, 1474, 1474, ADDRESS)), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(r -> r.ruleId().equals("BODY-INDENT")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
  }

  @Test
  void acceptsExactTwoCharacterPhysicalIndentForTenPointFivePointText() {
    var run = new RunModel("正文", "Times New Roman", "宋体", null, 21, false, ADDRESS);
    var paragraph = new ParagraphModel(List.of(run), 420, null, null, null, null, ADDRESS, null);
    var document = new ParsedDocument("source", List.of(chapterHeading(), paragraph),
        List.of(new SectionModel(1474, 1474, 1474, 1474, ADDRESS)), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("BODY-INDENT")).findFirst().orElseThrow();

    assertEquals(Judgement.PASS, result.judgement());
    assertTrue(result.expected().contains("420 twips"), result.expected());
  }

  @Test
  void acceptsExactTwoCharacterPhysicalIndentForTwelvePointText() {
    var run = new RunModel("正文", "Times New Roman", "宋体", null, 24, false, ADDRESS);
    var paragraph = new ParagraphModel(List.of(run), 480, null, null, null, null, ADDRESS, null);
    var document = new ParsedDocument("source", List.of(chapterHeading(), paragraph),
        List.of(new SectionModel(1474, 1474, 1474, 1474, ADDRESS)), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("BODY-INDENT")).findFirst().orElseThrow();

    assertEquals(Judgement.PASS, result.judgement());
    assertTrue(result.expected().contains("480 twips"), result.expected());
  }

  @Test
  void rejectsPhysicalIndentOneTwipAwayFromTheExactCharacterWidth() {
    var run = new RunModel("正文", "Times New Roman", "宋体", null, 21, false, ADDRESS);
    var paragraph = new ParagraphModel(List.of(run), 421, null, null, null, null, ADDRESS, null);
    var document = new ParsedDocument("source", List.of(chapterHeading(), paragraph),
        List.of(new SectionModel(1474, 1474, 1474, 1474, ADDRESS)), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("BODY-INDENT")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
  }

  @Test
  void usesCharacterIndentWhenWordStoresBothCharacterAndTwipValues() {
    var run = new RunModel("正文", "Times New Roman", "宋体", null, 24, false, ADDRESS);
    var paragraph = new ParagraphModel(List.of(run), 420, null, null, null, null, ADDRESS, 200);
    var document = new ParsedDocument("source", List.of(chapterHeading(), paragraph),
        List.of(new SectionModel(1474, 1474, 1474, 1474, ADDRESS)), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("BODY-INDENT")).findFirst().orElseThrow();

    assertEquals(Judgement.PASS, result.judgement());
  }

  @Test
  void rejectsContinuationCaptionUsingFullWidthHyphen() {
    var caption = paragraph("续表1－1", List.of(0, 1));
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(0, 2), 0, 0),
        caption.source(), "续表1－1", 2, 2, true, true, true, false, true, false);
    var document = new ParsedDocument("source", List.of(chapterHeading(), caption), List.of(), List.of(),
        List.of(table), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.reasonCode().equals("TABLE_CONTINUATION_NUMBER_FORMAT"))
        .findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
  }

  @Test
  void rejectsPhysicalIndentThatIsOnlyNearAValidEncoding() {
    var run = new RunModel("正文", "Times New Roman", "宋体", null, 24, false, ADDRESS);
    var paragraph = new ParagraphModel(List.of(run), 421, null, null, null, null, ADDRESS, null);
    var document = new ParsedDocument("source", List.of(chapterHeading(), paragraph),
        List.of(new SectionModel(1474, 1474, 1474, 1474, ADDRESS)), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(r -> r.ruleId().equals("BODY-INDENT")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
  }

  @Test
  void rejectsCharacterIndentThatIsNotExactlyTwoCharacters() {
    var run = new RunModel("正文", "Times New Roman", "宋体", null, 24, false, ADDRESS);
    var paragraph = new ParagraphModel(List.of(run), null, null, null, null, null, ADDRESS, 199);
    var document = new ParsedDocument("source", List.of(chapterHeading(), paragraph),
        List.of(new SectionModel(1474, 1474, 1474, 1474, ADDRESS)), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(r -> r.ruleId().equals("BODY-INDENT")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
  }

  @Test
  void headingIndentUsesTheSameExactSemanticEncodingsAsBodyIndent() {
    var source = new SourceAddress("word/document.xml", List.of(0, 4), 0, 4);
    var heading = new ParagraphModel(List.of(new RunModel("（1）标题", "Times New Roman", "宋体",
        null, 24, false, source)), 421, null, null, null, null, source, null);
    var document = new ParsedDocument("source", List.of(heading), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(r -> r.ruleId().equals("HEAD-05-INDENT")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
  }

  @Test
  void checksFontSizeAndIndentForAutomaticallyNumberedThirdLevelHeading() {
    var source = new SourceAddress("word/document.xml", List.of(0, 91), 0, 4);
    var generated = new ParagraphModel(List.of(new RunModel("研究内容", "Arial", "宋体", null,
        18, false, source)), 0, null, null, null, null, null, source, 0, 2, "left", "2.2.2");
    var document = new ParsedDocument("source", List.of(chapterHeading(), paragraph("", List.of(0, 90)), generated), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(item -> item.source().equals(source)
        && item.ruleId().equals("HEADING-FONT") && item.judgement() == Judgement.FAIL));
    assertTrue(results.stream().anyMatch(item -> item.source().equals(source)
        && item.ruleId().equals("HEADING-SIZE") && item.judgement() == Judgement.FAIL));
    assertTrue(results.stream().anyMatch(item -> item.source().equals(source)
        && item.ruleId().equals("HEAD-03-INDENT") && item.judgement() == Judgement.FAIL));
  }

  @Test
  void requiresOneBlankLineAfterReferencesTitle() {
    var heading = paragraph("参考文献", List.of(0, 1));
    var body = paragraph("[1] 文献[M]. 出版社", List.of(0, 2));
    var reference = new ReferenceEntryModel(body.source(), "1", body.runs().get(0).text(), "M", false);
    var document = new ParsedDocument("source", List.of(chapterHeading(), heading, body), List.of(), List.of(),
        List.of(), List.of(), List.of(reference), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("REF-01-BLANK")).toList();
    assertEquals(1, results.size());
    assertEquals("REFERENCE_TITLE_BLANK_LINE_MISSING", results.get(0).reasonCode());
  }

  @Test
  void acceptsExactlyOneBlankLineAfterReferencesTitle() {
    var heading = paragraph("参考文献", List.of(0, 1));
    var body = paragraph("[1] 文献[M]. 出版社", List.of(0, 3));
    var reference = new ReferenceEntryModel(body.source(), "1", body.runs().get(0).text(), "M", false);
    var document = new ParsedDocument("source", List.of(chapterHeading(), heading,
        paragraph("", List.of(0, 2)), body), List.of(), List.of(), List.of(), List.of(), List.of(reference), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("REF-01-BLANK")).toList();
    assertTrue(results.isEmpty(), results.toString());
  }

  @Test
  void rejectsMultipleBlankLinesAfterReferencesTitle() {
    var heading = paragraph("参考文献", List.of(0, 1));
    var body = paragraph("[1] 文献[M]. 出版社", List.of(0, 4));
    var reference = new ReferenceEntryModel(body.source(), "1", body.runs().get(0).text(), "M", false);
    var document = new ParsedDocument("source", List.of(chapterHeading(), heading,
        paragraph("", List.of(0, 2)), paragraph("", List.of(0, 3)), body), List.of(), List.of(),
        List.of(), List.of(), List.of(reference), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("REF-01-BLANK")).findFirst().orElseThrow();
    assertEquals("REFERENCE_TITLE_BLANK_LINE_COUNT_MISMATCH", result.reasonCode());
  }

  @Test
  void requiresOneBlankLineAfterAcknowledgementsTitle() {
    var heading = paragraph("致谢", List.of(0, 1));
    var body = paragraph("感谢老师的指导。", List.of(0, 2));
    var document = new ParsedDocument("source", List.of(heading, body), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("END-01-BLANK")).findFirst().orElseThrow();
    assertEquals("ACKNOWLEDGEMENT_TITLE_BLANK_LINE_MISSING", result.reasonCode());
  }

  @Test
  void acceptsOneBlankLineAfterAcknowledgementsTitle() {
    var heading = paragraph("致谢", List.of(0, 1));
    var body = paragraph("感谢老师的指导。", List.of(0, 3));
    var document = new ParsedDocument("source", List.of(heading, paragraph("", List.of(0, 2)), body),
        List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("END-01-BLANK")).toList();
    assertTrue(results.isEmpty(), results.toString());
  }

  @Test
  void acceptsTableReferenceInBody() {
    var body = paragraph("详细数据见表1-1。", List.of(0, 1));
    var caption = paragraph("表1-1 数据", List.of(0, 2));
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(0, 3), 0, 0),
        caption.source(), "表1-1 数据", 2, 2, true, true, true, false, true, false);
    var document = new ParsedDocument("source", List.of(chapterHeading(), body, caption), List.of(), List.of(),
        List.of(table), List.of(), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("TAB-01")).findFirst().orElseThrow();
    assertEquals(Judgement.PASS, result.judgement());
  }

  @Test
  void doesNotTreatLongerTableNumberAsReferenceToItsPrefix() {
    var body = paragraph("详细数据见表1-1-0。", List.of(0, 1));
    var caption = paragraph("表1-1 数据", List.of(0, 2));
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(0, 3), 0, 0),
        caption.source(), "表1-1 数据", 2, 2, true, true, true, false, true, false);
    var document = new ParsedDocument("source", List.of(chapterHeading(), body, caption), List.of(), List.of(),
        List.of(table), List.of(), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("TAB-01")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("TABLE_NOT_CITED_IN_BODY", result.reasonCode());
  }

  @Test
  void doesNotCountMalformedTableCaptionPrefixAsAValidTableNumber() {
    var body = paragraph("详细数据见表1-1。", List.of(0, 1));
    var caption = paragraph("表1-1-0 数据", List.of(0, 2));
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(0, 3), 0, 0),
        caption.source(), "表1-1-0 数据", 2, 2, true, true, true, false, true, false);
    var document = new ParsedDocument("source", List.of(chapterHeading(), body, caption), List.of(), List.of(),
        List.of(table), List.of(), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().noneMatch(item -> item.ruleId().equals("TAB-01")
        && item.judgement() == Judgement.PASS));
    assertTrue(results.stream().anyMatch(item -> item.reasonCode().equals("TABLE_CAPTION_NUMBER_FORMAT")));
  }

  @Test
  void malformedTableCaptionDoesNotShiftTheNextValidTableSequence() {
    var malformed = paragraph("表 1-1 错误格式", List.of(0, 2));
    var valid = paragraph("表1-1 正常表题", List.of(0, 4));
    var firstTable = new TableModel(new SourceAddress("word/document.xml", List.of(0, 3), 0, 0),
        malformed.source(), "表 1-1 错误格式", 2, 2, true, true, true, false, true, false);
    var secondTable = new TableModel(new SourceAddress("word/document.xml", List.of(0, 5), 0, 0),
        valid.source(), "表1-1 正常表题", 2, 2, true, true, true, false, true, false);
    var document = new ParsedDocument("source",
        List.of(chapterHeading(), malformed, valid), List.of(), List.of(),
        List.of(firstTable, secondTable), List.of(), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(item -> item.reasonCode().equals("TABLE_CAPTION_NUMBER_FORMAT")));
    assertTrue(results.stream().noneMatch(item -> item.reasonCode().equals("TABLE_NUMBER_SEQUENCE_MISMATCH")));
  }

  @Test
  void rejectsZeroPaddedTableSequenceNumber() {
    var caption = paragraph("表1-01 零填充表号", List.of(0, 2));
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(0, 3), 0, 0),
        caption.source(), "表1-01 零填充表号", 2, 2, true, true, true, false, true, false);
    var document = new ParsedDocument("source", List.of(chapterHeading(), caption), List.of(), List.of(),
        List.of(table), List.of(), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.reasonCode().equals("TABLE_NUMBER_SEQUENCE_MISMATCH")).findFirst().orElseThrow();

    assertEquals("1-1", result.expected());
    assertTrue(result.actual().startsWith("1-01"), result.actual());
  }

  @Test
  void doesNotMatchTableReferenceAfterChangingHyphenWidth() {
    var body = paragraph("详细数据见表1－1。", List.of(0, 1));
    var caption = paragraph("表1-1 数据", List.of(0, 2));
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(0, 3), 0, 0),
        caption.source(), "表1-1 数据", 2, 2, true, true, true, false, true, false);
    var document = new ParsedDocument("source", List.of(chapterHeading(), body, caption), List.of(), List.of(),
        List.of(table), List.of(), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("TAB-01")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("TABLE_NOT_CITED_IN_BODY", result.reasonCode());
  }

  @Test
  void requiresTableCaptionToBeCentered() {
    var body = paragraph("详见表1-1。", List.of(0, 1));
    var captionSource = new SourceAddress("word/document.xml", List.of(0, 2), 0, 8);
    var caption = new ParagraphModel(
        List.of(new RunModel("表1-1 数据", "宋体", "宋体", null, 21, false, captionSource)),
        0, null, null, 360, "auto", captionSource, 0, null, "left");
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(0, 3), 0, 0),
        captionSource, "表1-1 数据", 2, 2, true, true, true, false, true, false);
    var document = new ParsedDocument("source", List.of(chapterHeading(), body, caption), List.of(), List.of(),
        List.of(table), List.of(), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("TAB-03")
            && item.reasonCode().equals("TABLE_CAPTION_NOT_CENTERED"))
        .findFirst().orElseThrow();
    assertEquals(Judgement.FAIL, result.judgement());
  }

  @Test
  void ignoresTableNumberInTocWhenCheckingBodyReference() {
    var toc = paragraph("表4-1 数据........................12", List.of(0, 1));
    var caption = paragraph("表4-1 数据", List.of(0, 3));
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(0, 4), 0, 0),
        caption.source(), "表4-1 数据", 2, 2, true, true, true, false, true, false);
    var document = new ParsedDocument("source",
        List.of(paragraph("目录", List.of(0, 0)), toc, chapterHeading(), caption), List.of(), List.of(),
        List.of(table), List.of(), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("TAB-01")).findFirst().orElseThrow();
    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("TABLE_NOT_CITED_IN_BODY", result.reasonCode());
  }

  @Test
  void reportsFigureCaptionWithoutChapterNumberOrSeparatorSpace() {
    var image = new ImageModel(new SourceAddress("word/document.xml", List.of(1), 0, 0),
        ADDRESS, "图1 示例", 100, 200, true, true, true);
    var document = new ParsedDocument("source", List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(image));

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("FIG-03")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("FIGURE_CAPTION_NUMBER_FORMAT", result.reasonCode());
  }

  @Test
  void rejectsFigureCaptionWithMoreThanOneSeparatorSpace() {
    var image = new ImageModel(new SourceAddress("word/document.xml", List.of(1), 0, 0),
        ADDRESS, "图3-1  系统结构图", 100, 200, true, true, true);
    var document = new ParsedDocument("source", List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(image));

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("FIG-03")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("FIGURE_CAPTION_NUMBER_FORMAT", result.reasonCode());
  }

  @Test
  void reportsTableCaptionWithoutChapterNumberOrSeparatorSpace() {
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(1), 0, 0),
        ADDRESS, "表1 数据", 2, 2, true, true, true, false, true, false);
    var document = new ParsedDocument("source", List.of(), List.of(), List.of(),
        List.of(table), List.of(), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("TAB-03")
            && item.reasonCode().equals("TABLE_CAPTION_NUMBER_FORMAT")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("TABLE_CAPTION_NUMBER_FORMAT", result.reasonCode());
  }

  @Test
  void rejectsTableCaptionWithMoreThanOneSeparatorSpace() {
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(1), 0, 0),
        ADDRESS, "表3-1  测试数据", 2, 2, true, true, true, false, true, false);
    var document = new ParsedDocument("source", List.of(), List.of(), List.of(),
        List.of(table), List.of(), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("TAB-03")
            && item.reasonCode().equals("TABLE_CAPTION_NUMBER_FORMAT"))
        .findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
  }

  @Test
  void tableCaptionRequiresAnExactAsciiSeparatorAndNoOuterWhitespace() {
    for (String caption : List.of(" 表3-1 标题", "表3-1　标题", "表3－1 标题")) {
      var table = new TableModel(new SourceAddress("word/document.xml", List.of(1), 0, 0),
          ADDRESS, caption, 2, 2, true, true, true, false, true, false);
      var document = new ParsedDocument("source", List.of(), List.of(), List.of(),
          List.of(table), List.of(), List.of(), List.of());

      assertTrue(new RuleEngine().check(document, RuleSet.schoolV1()).stream()
          .anyMatch(item -> item.reasonCode().equals("TABLE_CAPTION_NUMBER_FORMAT")), caption);
    }
  }

  @Test
  void doesNotInferMissingUnitsFromADataCaptionWithoutQuantitativeHeaderEvidence() {
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(1), 0, 0),
        ADDRESS, "表1-1 数据", 2, 2, true, true, true, false, true, false);
    var document = new ParsedDocument("source", List.of(), List.of(), List.of(),
        List.of(table), List.of(), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().noneMatch(item -> item.ruleId().equals("TAB-04")));
  }

  @Test
  void doesNotRequireUnitsForDescriptiveTables() {
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(1), 0, 0),
        ADDRESS, "表3-1 用例规约", 4, 2, true, true, true, false, true, false,
        false, List.of(), List.of(), List.of("项目", "内容"), null, null, false);
    var document = new ParsedDocument("source", List.of(), List.of(), List.of(),
        List.of(table), List.of(), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().noneMatch(item -> item.ruleId().equals("TAB-04")));
  }

  @Test
  void rejectsQuantitativeColumnsWithoutAnExplicitUnit() {
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(1), 0, 0),
        ADDRESS, "表3-1 测量结果", 4, 2, true, true, true, false, true, false,
        false, List.of(), List.of(), List.of("指标", "测量值"), null, null, false);
    var document = new ParsedDocument("source", List.of(), List.of(), List.of(),
        List.of(table), List.of(), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("TAB-04")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("TABLE_UNITS_MISSING", result.reasonCode());
  }

  @Test
  void doesNotTreatDescriptiveParenthesesAsMeasurementUnits() {
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(1), 0, 0),
        ADDRESS, "表3-1 测量结果", 4, 2, true, true, true, false, true, false,
        false, List.of(), List.of(), List.of("指标", "测量值（结果）"), null, null, false);
    var document = new ParsedDocument("source", List.of(), List.of(), List.of(),
        List.of(table), List.of(), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("TAB-04")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("TABLE_UNITS_MISSING", result.reasonCode());
  }

  @Test
  void skipsMultiRowOneColumnCodeListingTables() {
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(1), 0, 0),
        null, null, 8, 1, false, false, false, true, false, false,
        false, List.of(), List.of(), List.of("void setup() { digitalWrite(PIN, HIGH); }"), null, null, false);
    var document = new ParsedDocument("source", List.of(), List.of(), List.of(),
        List.of(table), List.of(), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().noneMatch(item -> item.ruleId().startsWith("TAB-")), results.toString());
  }

  @Test
  void rejectsUnitWhenHeaderQuantityDimensionCannotBeVerified() {
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(1), 0, 0),
        ADDRESS, "表3-1 测量结果", 4, 2, true, true, true, false, true, false,
        false, List.of(), List.of(), List.of("指标", "测量值（ms）"), null, null, false);
    var document = new ParsedDocument("source", List.of(), List.of(), List.of(),
        List.of(table), List.of(), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("TAB-04")
        && item.reasonCode().equals("TABLE_UNIT_DIMENSION_UNKNOWN")), results.toString());
  }

  @Test
  void acceptsDifferentUnitsWrittenInTheirRespectiveHeaders() {
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(1), 0, 0),
        ADDRESS, "表3-2 测量结果", 4, 4, true, true, true, false, true, false,
        false, List.of(), List.of(), List.of("功率密度/W·cm²", "辐照时间/s", "显微硬度/HV", "硬化层深/mm"),
        null, null, false);
    var document = new ParsedDocument("source", List.of(), List.of(), List.of(),
        List.of(table), List.of(), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().noneMatch(item -> item.ruleId().equals("TAB-04")),
        () -> results.stream().map(item -> item.ruleId() + ":" + item.reasonCode()).toList().toString());
  }

  @Test
  void treatsSummaryAsBodyEvenWhenWordOutlineMarksItAsLevelOne() {
    var source = new SourceAddress("word/document.xml", List.of(0, 8), 0, 2);
    var summary = new ParagraphModel(List.of(new RunModel("总结", "Times New Roman", "黑体", null,
        32, false, source)), 0, null, null, null, 360, "auto", source, 0, 0, "left");
    var document = new ParsedDocument("source", List.of(summary), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().noneMatch(item -> item.source().elementPath().equals(source.elementPath())
        && (item.ruleId().startsWith("HEADING-") || item.ruleId().startsWith("HEAD-"))),
        () -> results.stream().map(item -> item.ruleId() + ":" + item.reasonCode()).toList().toString());
  }

  @Test
  void rejectsWhitespaceInsideSecondLevelNumberInsteadOfTreatingItAsValidSeparation() {
    var source = new SourceAddress("word/document.xml", List.of(0, 9), 0, 10);
    var heading = new ParagraphModel(List.of(new RunModel("5. 6 按键模块", "Times New Roman", "黑体", null,
        28, false, source)), null, null, null, null, 420, "auto", source, null, null, "left");
    var document = new ParsedDocument("source", List.of(heading), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    var result = results.stream().filter(item -> item.ruleId().equals("HEAD-02-SPACING"))
        .findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("HEADING_NUMBER_INTERNAL_WHITESPACE", result.reasonCode());
    assertEquals("5. 6", result.actual());
    assertEquals("5.6", result.expected());
  }

  @Test
  void recognizesExactOneSpaceAfterSecondAndThirdLevelHeadingNumbers() {
    for (String text : List.of("5.6 按键模块", "5.6.1 按键驱动")) {
      int level = text.chars().filter(character -> character == '.').toArray().length + 1;
      var source = new SourceAddress("word/document.xml", List.of(0, 9), 0, text.length());
      var heading = new ParagraphModel(List.of(new RunModel(text, "Times New Roman", "黑体", null,
          level == 2 ? 28 : 24, false, source)), null, null, null, null, 360, "auto", source,
          null, null, "left");

      var result = new RuleEngine().check(new ParsedDocument("source", List.of(heading), List.of(), List.of()),
          RuleSet.schoolV1()).stream().filter(item -> item.ruleId().equals("HEAD-0" + level + "-SPACING"))
          .findFirst().orElseThrow();

      assertEquals(Judgement.PASS, result.judgement(), text);
      assertEquals("1", result.actual(), text);
    }
  }

  @Test
  void unnumberedSummaryIsNeitherNumberedChapterNorBodyButFollowingTextIsChecked() {
    for (String title : List.of("总结", "总    结", "总\u3000结", "总\u00a0结")) {
      var source = new SourceAddress("word/document.xml", List.of(0, 20), 0, title.length());
      var summary = new ParagraphModel(List.of(new RunModel(title, "Times New Roman", "黑体", null,
          32, true, source)), 0, null, null, null, 360, "auto", source, 0, 0, "center");
      var body = paragraph("本研究完成了系统设计，仍需继续改进。", List.of(0, 21));
      var document = new ParsedDocument("source", List.of(chapterHeading(), paragraph("", List.of(0, 19)),
          summary, body), List.of(), List.of());
      var results = new RuleEngine().check(document, RuleSet.schoolV1());
      assertTrue(results.stream().noneMatch(r -> r.source().elementPath().equals(source.elementPath())
          && (r.ruleId().startsWith("HEAD") || r.ruleId().startsWith("BODY"))), title);
      assertTrue(results.stream().anyMatch(r -> r.source().elementPath().equals(body.source().elementPath())
          && r.ruleId().equals("BODY-INDENT")), title);
      assertTrue(results.stream().noneMatch(r -> r.source().elementPath().equals(body.source().elementPath())
          && r.ruleId().equals("HEAD-02-BLANK")), title);
    }
  }

  @Test
  void describesTheActualWhitespaceBetweenHeadingNumberAndTitle() {
    var source = new SourceAddress("word/document.xml", List.of(0, 9), 0, 10);
    var findings = List.of("5.1功能模块", "5.1\t功能模块", "5.1\u3000功能模块", "5.1\u00a0功能模块")
        .stream().map(text -> {
          var heading = new ParagraphModel(List.of(new RunModel(text, "Times New Roman", "黑体", null,
              28, false, source)), null, null, null, null, 420, "auto", source, null, null, "left");
          return new RuleEngine().check(new ParsedDocument("source", List.of(heading), List.of(), List.of()),
              RuleSet.schoolV1()).stream().filter(item -> item.ruleId().equals("HEAD-02-SPACING"))
              .findFirst().orElseThrow();
        }).toList();

    assertEquals(List.of("没有空格", "制表符", "全角空格", "不换行空格"),
        findings.stream().map(CheckResult::actual).toList());
  }

  @Test
  void identifiesInvisibleWhitespaceBetweenHeadingNumberAndTitle() {
    var source = new SourceAddress("word/document.xml", List.of(0, 9), 0, 10);
    var heading = new ParagraphModel(List.of(new RunModel("5.1\u200b功能模块", "Times New Roman", "黑体", null,
        28, false, source)), null, null, null, null, 420, "auto", source, null, null, "left");

    var result = new RuleEngine().check(new ParsedDocument("source", List.of(heading), List.of(), List.of()),
        RuleSet.schoolV1()).stream().filter(item -> item.ruleId().equals("HEAD-02-SPACING"))
        .findFirst().orElseThrow();

    assertEquals("1 个看不见的空白", result.actual());
    assertEquals(Judgement.FAIL, result.judgement());
  }

  @Test
  void checksAutomaticHeadingSpacingFromNumberingSuffixAndParagraphText() {
    var source = new SourceAddress("word/document.xml", List.of(0, 9), 0, 3);
    var heading = new ParagraphModel(List.of(new RunModel(" 标题", "Times New Roman", "黑体", null,
        32, true, source)), null, null, null, null, null, null, source, null, 0, "left", "1", 1, 0);
    var document = new ParsedDocument("source", List.of(heading), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("HEAD-01-SPACING")).findFirst().orElseThrow();

    assertEquals(Judgement.PASS, result.judgement());
  }

  @Test
  void rejectsAutomaticHeadingWhenNumberingSuffixIsATab() {
    var source = new SourceAddress("word/document.xml", List.of(0, 9), 0, 4);
    var heading = new ParagraphModel(List.of(new RunModel("  标题", "Times New Roman", "黑体", null,
        32, true, source)), null, null, null, null, null, null, source, null, 0, "left", "1", null, 0);
    var document = new ParsedDocument("source", List.of(heading), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("HEAD-01-SPACING")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("HEADING_NUMBER_SPACING_MISMATCH", result.reasonCode());
  }

  @Test
  void rejectsNonBreakingSpaceAsAutomaticHeadingSeparator() {
    var source = new SourceAddress("word/document.xml", List.of(0, 9), 0, 3);
    var heading = new ParagraphModel(List.of(new RunModel("\u00a0标题", "Times New Roman", "黑体", null,
        32, true, source)), null, null, null, null, null, null, source, null, 0, "left", "1", 1, 1);
    var document = new ParsedDocument("source", List.of(heading), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("HEAD-01-SPACING")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("HEADING_NUMBER_SPACING_MISMATCH", result.reasonCode());
  }

  @Test
  void detectsChapterHeadingWithUnicodeWhitespaceInsideItsNumber() {
    var source = new SourceAddress("word/document.xml", List.of(0, 9), 0, 12);
    var heading = new ParagraphModel(List.of(new RunModel("第　一章  引言", "Times New Roman", "黑体",
        null, 32, true, source)), 0, null, null, null, null, source, 0);
    var document = new ParsedDocument("source", List.of(heading), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());
    var result = results.stream().filter(item -> item.ruleId().equals("HEAD-01-SPACING"))
        .findFirst().orElseThrow(() -> new AssertionError(results.stream()
            .map(item -> item.ruleId() + ":" + item.reasonCode()).toList().toString()));

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("HEADING_NUMBER_SPACING_MISMATCH", result.reasonCode());
  }

  @Test
  void doesNotRequireBlankLineAfterChapterEntriesInTocWithoutDotLeaders() {
    var tocTitle = paragraph("目录", List.of(0, 1));
    var tocChapter = paragraph("第二章 相关技术介绍5", List.of(0, 2));
    var tocSection = paragraph("2.1 STM32微控制器技术5", List.of(0, 3));
    var bodyChapter = chapterHeading();
    var blank = paragraph("", List.of(0, 5));
    var bodySection = paragraph("2.1 STM32微控制器技术", List.of(0, 6));
    var document = new ParsedDocument("source", List.of(tocTitle, tocChapter, tocSection,
        bodyChapter, blank, bodySection), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().noneMatch(item -> item.ruleId().equals("HEAD-02-BLANK")),
        () -> results.stream().map(item -> item.ruleId() + ":" + item.reasonCode()).toList().toString());
  }

  @Test
  void doesNotTreatChapterDescriptionSentenceAsChapterHeading() {
    var structureSentence = paragraph("第七章为系统测试，对系统功能进行验证。", List.of(0, 20));
    var bodyChapter = chapterHeading();
    var blank = paragraph("", List.of(0, 22));
    var bodySection = paragraph("2.1 相关技术", List.of(0, 23));
    var document = new ParsedDocument("source", List.of(structureSentence, bodyChapter, blank, bodySection),
        List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().noneMatch(item -> item.ruleId().equals("HEAD-02-BLANK")),
        () -> results.stream().map(item -> item.ruleId() + ":" + item.reasonCode()).toList().toString());
  }

  @Test
  void doesNotTreatChapterReferenceSentenceAsChapterHeading() {
    var sentence = paragraph("第七章的测试结果显示，所有功能均正常。", List.of(0, 20));
    var bodyChapter = chapterHeading();
    var blank = paragraph("", List.of(0, 22));
    var body = paragraph("正文内容", List.of(0, 23));
    var document = new ParsedDocument("source", List.of(sentence, bodyChapter, blank, body), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().noneMatch(item -> item.ruleId().equals("HEAD-02-BLANK")),
        () -> results.stream().map(item -> item.ruleId() + ":" + item.reasonCode()).toList().toString());
  }

  @Test
  void recognizesChapterHeadingWhoseTitleStartsWithACommonVerbWhenHeadingFormattingConfirmsIt() {
    var headingSource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 10);
    var heading = new ParagraphModel(List.of(new RunModel("第三章 为系统测试", "Times New Roman", "黑体",
        null, 32, true, headingSource)), null, null, null, null, null, headingSource, null, 0, null);
    var followingBody = paragraph("正文内容", List.of(0, 2));
    var document = new ParsedDocument("source", List.of(heading, followingBody),
        List.of(), List.of());

    var bodyFindings = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.ruleId().equals("BODY-INDENT")
            && result.source().elementPath().equals(followingBody.source().elementPath()))
        .toList();

    assertEquals(1, bodyFindings.size(), bodyFindings.toString());
    assertEquals(Judgement.FAIL, bodyFindings.get(0).judgement());
  }

  @Test
  void doesNotTreatNumberedTextInsideTableAsHeading() {
    var chapter = chapterHeading();
    var tableSource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 0);
    var tableParagraphSource = new SourceAddress("word/document.xml", List.of(0, 1, 0), 0, 12);
    var tableParagraph = new ParagraphModel(List.of(new RunModel("3.1 测试步骤", "Times New Roman",
        "宋体", null, 24, false, tableParagraphSource)), null, null, null, null, null,
        tableParagraphSource, null);
    var table = new TableModel(tableSource, null, null, 1, 1, true, true, true, false, false, false,
        false, List.of(), List.of(), List.of(), null, null, false);
    var document = new ParsedDocument("source", List.of(chapter, tableParagraph), List.of(), List.of(),
        List.of(table), List.of(), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().noneMatch(item -> item.source().elementPath().equals(tableParagraphSource.elementPath())
        && (item.ruleId().startsWith("HEADING-") || item.ruleId().startsWith("HEAD-02")
            || item.ruleId().equals("BODY-INDENT"))),
        () -> results.stream().map(item -> item.ruleId() + ":" + item.reasonCode()).toList().toString());
  }

  @Test
  void ignoresBorderedOneCellCodeListingsAsThesisTables() {
    var tableSource = new SourceAddress("word/document.xml", List.of(0, 20), 0, 0);
    var table = new TableModel(tableSource, null, null, 1, 1, true, false, true,
        true, false, false, false, List.of(), List.of(),
        List.of("@Controller public class Demo { return service.find(); }"), null, null,
        false, false);
    var document = new ParsedDocument("source", List.of(chapterHeading()), List.of(), List.of(),
        List.of(table), List.of(), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().noneMatch(item -> item.ruleId().startsWith("TAB-")));
  }

  @Test
  void checksBodyFormattingOnCodeListingParagraphs() {
    var source = new SourceAddress("word/document.xml", List.of(0, 4), 0, 42);
    var code = paragraph("if (heartRateStable == 0) { return; } // code", source.elementPath());
    var document = new ParsedDocument("source", List.of(chapterHeading(), code), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("BODY-INDENT")
        && item.source().elementPath().equals(source.elementPath())));
    assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("BODY-FONT")
        && item.source().elementPath().equals(source.elementPath())));
    assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("BODY-SIZE")
        && item.source().elementPath().equals(source.elementPath())));
  }

  @Test
  void checksBodyFormattingOnStandaloneCodeLines() {
    var source = new SourceAddress("word/document.xml", List.of(0, 5), 0, 24);
    var code = paragraph("heartRateStable = heartRateRaw;", source.elementPath());
    var document = new ParsedDocument("source", List.of(chapterHeading(), code), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("BODY-INDENT")
        && item.source().elementPath().equals(source.elementPath())));
    assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("BODY-FONT")
        && item.source().elementPath().equals(source.elementPath())));
    assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("BODY-SIZE")
        && item.source().elementPath().equals(source.elementPath())));
  }

  @Test
  void checksBodyFormattingOnCodeCommentsAndNestedMethodCalls() {
    var commentSource = new SourceAddress("word/document.xml", List.of(0, 7), 0, 20);
    var callSource = new SourceAddress("word/document.xml", List.of(0, 8), 0, 60);
    var comment = paragraph("// 邮箱验证逻辑", commentSource.elementPath());
    var call = paragraph("user.setToken(UUID.randomUUID().toString());", callSource.elementPath());
    var document = new ParsedDocument("source", List.of(chapterHeading(), comment, call), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    for (SourceAddress source : List.of(commentSource, callSource)) {
      assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("BODY-INDENT")
          && item.source().elementPath().equals(source.elementPath())));
      assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("BODY-FONT")
          && item.source().elementPath().equals(source.elementPath())));
      assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("BODY-SIZE")
          && item.source().elementPath().equals(source.elementPath())));
    }
  }

  @Test
  void checksBodyFormattingOnCodeDeclarationsAndContinuationLines() {
    var declarationSource = new SourceAddress("word/document.xml", List.of(0, 9), 0, 40);
    var continuationSource = new SourceAddress("word/document.xml", List.of(0, 10), 0, 20);
    var declaration = paragraph("ReadUserDTO currentUser = userService.getCurrent(); // 获取当前用户", declarationSource.elementPath());
    var continuation = paragraph(".orElseThrow(() -> new IllegalStateException(\"资源不存在\"));", continuationSource.elementPath());
    var document = new ParsedDocument("source", List.of(chapterHeading(), declaration, continuation), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    for (SourceAddress source : List.of(declarationSource, continuationSource)) {
      assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("BODY-INDENT")
          && item.source().elementPath().equals(source.elementPath())));
      assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("BODY-FONT")
          && item.source().elementPath().equals(source.elementPath())));
      assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("BODY-SIZE")
          && item.source().elementPath().equals(source.elementPath())));
    }
  }

  @Test
  void checksIndentOnBodyParagraphsThatContainMathematicalBraces() {
    var source = new SourceAddress("word/document.xml", List.of(0, 6), 0, 32);
    var body = paragraph("当 x 属于集合 {x | x > 0} 时，继续计算。", source.elementPath());
    var document = new ParsedDocument("source", List.of(chapterHeading(), body), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("BODY-INDENT")
            && item.source().elementPath().equals(source.elementPath()))
        .findFirst();

    assertTrue(result.isPresent());
    assertEquals(Judgement.FAIL, result.orElseThrow().judgement());
  }

  @Test
  void reportsMissingFigureNumberWithinChapter() {
    var firstSource = new SourceAddress("word/document.xml", List.of(0, 3), 0, 0);
    var firstCaption = new SourceAddress("word/document.xml", List.of(0, 4), 0, 12);
    var secondSource = new SourceAddress("word/document.xml", List.of(0, 6), 0, 0);
    var secondCaption = new SourceAddress("word/document.xml", List.of(0, 7), 0, 12);
    var images = List.of(
        new cn.luwneshacha.thesis.document.ImageModel(firstSource, firstCaption, "图1-1 第一幅图", 1, 1, true, true, true),
        new cn.luwneshacha.thesis.document.ImageModel(secondSource, secondCaption, "图1-3 第三幅图", 1, 1, true, true, true));
    var document = new ParsedDocument("source", List.of(chapterHeading(),
        paragraph("图1-1 第一幅图", List.of(0, 4)), paragraph("图1-3 第三幅图", List.of(0, 7))),
        List.of(), List.of(), List.of(), List.of(), List.of(), images);

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("FIG-05") && item.judgement() == Judgement.FAIL).findFirst();

    assertTrue(result.isPresent());
    assertEquals("图1-2", result.orElseThrow().expected());
    assertEquals("图1-3 第三幅图", result.orElseThrow().actual());
    assertEquals(Judgement.FAIL, result.orElseThrow().judgement());
  }

  @Test
  void acceptsContinuousFigureNumbersWithinChapter() {
    var images = List.of(
        new cn.luwneshacha.thesis.document.ImageModel(new SourceAddress("word/document.xml", List.of(0, 3), 0, 0),
            new SourceAddress("word/document.xml", List.of(0, 4), 0, 12), "图1-1 第一幅图", 1, 1, true, true, true),
        new cn.luwneshacha.thesis.document.ImageModel(new SourceAddress("word/document.xml", List.of(0, 6), 0, 0),
            new SourceAddress("word/document.xml", List.of(0, 7), 0, 12), "图1-2 第二幅图", 1, 1, true, true, true));
    var document = new ParsedDocument("source", List.of(chapterHeading(),
        paragraph("图1-1 第一幅图", List.of(0, 4)), paragraph("图1-2 第二幅图", List.of(0, 7))),
        List.of(), List.of(), List.of(), List.of(), List.of(), images);

    var results = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("FIG-05")).toList();

    assertEquals(2, results.size());
    assertTrue(results.stream().allMatch(item -> item.judgement() == Judgement.PASS), results.toString());
  }

  @Test
  void rejectsZeroPaddedFigureSequenceNumber() {
    var image = new cn.luwneshacha.thesis.document.ImageModel(
        new SourceAddress("word/document.xml", List.of(0, 3), 0, 0),
        new SourceAddress("word/document.xml", List.of(0, 4), 0, 12), "图1-01 零填充图号",
        1, 1, true, true, true);
    var document = new ParsedDocument("source", List.of(chapterHeading(),
        paragraph("图1-01 零填充图号", List.of(0, 4))), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(image));

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.reasonCode().equals("FIGURE_NUMBER_SEQUENCE_MISMATCH")).findFirst().orElseThrow();

    assertEquals("图1-1", result.expected());
    assertTrue(result.actual().startsWith("图1-01"), result.actual());
  }

  @Test
  void malformedFigureSeparatorDoesNotAdvanceTheValidFigureSequence() {
    var images = List.of(
        new cn.luwneshacha.thesis.document.ImageModel(new SourceAddress("word/document.xml", List.of(0, 3), 0, 0),
            new SourceAddress("word/document.xml", List.of(0, 4), 0, 12), "图1－1 错误分隔符", 1, 1, true, true, true),
        new cn.luwneshacha.thesis.document.ImageModel(new SourceAddress("word/document.xml", List.of(0, 6), 0, 0),
            new SourceAddress("word/document.xml", List.of(0, 7), 0, 12), "图1-1 第一幅图", 1, 1, true, true, true));
    var document = new ParsedDocument("source", List.of(chapterHeading(),
        paragraph("图1－1 错误分隔符", List.of(0, 4)), paragraph("图1-1 第一幅图", List.of(0, 7))),
        List.of(), List.of(), List.of(), List.of(), List.of(), images);

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(result -> result.reasonCode().equals("FIGURE_CAPTION_NUMBER_FORMAT")));
    assertTrue(results.stream().noneMatch(result -> result.ruleId().equals("FIG-05")
        && result.judgement() == Judgement.FAIL));
  }

  @Test
  void spacingOnlyFigureCaptionErrorDoesNotCascadeIntoSequenceErrors() {
    var first = new ImageModel(new SourceAddress("word/document.xml", List.of(0, 3), 0, 0),
        new SourceAddress("word/document.xml", List.of(0, 4), 0, 12), "鍥?-1  涓や釜绌烘牸", 1, 1, true, true, true);
    var second = new ImageModel(new SourceAddress("word/document.xml", List.of(0, 6), 0, 0),
        new SourceAddress("word/document.xml", List.of(0, 7), 0, 12), "鍥?-2 姝ｅ父鍥鹃", 1, 1, true, true, true);
    var document = new ParsedDocument("source", List.of(chapterHeading(),
        paragraph("鍥?-1  涓や釜绌烘牸", List.of(0, 4)), paragraph("鍥?-2 姝ｅ父鍥鹃", List.of(0, 7))),
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(first, second));

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(item -> item.reasonCode().equals("FIGURE_CAPTION_NUMBER_FORMAT")));
    assertTrue(results.stream().noneMatch(item -> item.ruleId().equals("FIG-05")));
  }

  @Test
  void ignoresFormattingWhitespaceBeforeFigureNumberWhenCheckingCaptionFormatAndSequence() {
    var first = new ImageModel(new SourceAddress("word/document.xml", List.of(0, 3), 0, 0),
        new SourceAddress("word/document.xml", List.of(0, 4), 0, 12), "     图1-1 正常图题", 1, 1, true, true, true);
    var second = new ImageModel(new SourceAddress("word/document.xml", List.of(0, 6), 0, 0),
        new SourceAddress("word/document.xml", List.of(0, 7), 0, 12), "图1-2 后续图题", 1, 1, true, true, true);
    var document = new ParsedDocument("source", List.of(chapterHeading(),
        paragraph("     图1-1 正常图题", List.of(0, 4)), paragraph("图1-2 后续图题", List.of(0, 7))),
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(first, second));

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().noneMatch(item -> item.ruleId().equals("FIG-03")
        && item.judgement() == Judgement.FAIL), results.toString());
    var sequenceResults = results.stream().filter(item -> item.ruleId().equals("FIG-05")).toList();
    assertEquals(2, sequenceResults.size());
    assertTrue(sequenceResults.stream().allMatch(item -> item.judgement() == Judgement.PASS), results.toString());
  }

  @Test
  void malformedTableSeparatorDoesNotAdvanceTheValidTableSequence() {
    var firstCaption = new SourceAddress("word/document.xml", List.of(0, 2), 0, 12);
    var secondCaption = new SourceAddress("word/document.xml", List.of(0, 4), 0, 12);
    var tables = List.of(
        new TableModel(new SourceAddress("word/document.xml", List.of(0, 3), 0, 0),
            firstCaption, "表1－1 错误分隔符", 2, 2, true, true, true, false, true, false),
        new TableModel(new SourceAddress("word/document.xml", List.of(0, 5), 0, 0),
            secondCaption, "表1-1 第一张表", 2, 2, true, true, true, false, true, false));
    var document = new ParsedDocument("source", List.of(chapterHeading(),
        paragraph("表1－1 错误分隔符", List.of(0, 2)), paragraph("表1-1 第一张表", List.of(0, 4))),
        List.of(), List.of(), tables, List.of(), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(result -> result.reasonCode().equals("TABLE_CAPTION_NUMBER_FORMAT")));
    assertTrue(results.stream().noneMatch(result -> result.ruleId().equals("TAB-05")));
  }

  @Test
  void doesNotTreatUnspecifiedEquationTypographyAsSchoolFormatErrors() {
    var equation = new cn.luwneshacha.thesis.document.EquationModel(ADDRESS, "OMML", "x", null,
        false, false, java.util.Set.of(), java.util.Set.of("Cambria Math"), false);
    var document = new ParsedDocument("source", List.of(chapterHeading()), List.of(), List.of(),
        List.of(), List.of(equation), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("EQ-08") || item.ruleId().equals("EQ-09")).toList();

    assertTrue(results.isEmpty());
  }

  @Test
  void ignoresUnknownOmmlExtensionsWithoutJudgingThemAsErrors() {
    var equation = new cn.luwneshacha.thesis.document.EquationModel(ADDRESS, "OMML", "x", "3-1",
        true, false, java.util.Set.of("oMath", "futureMathNode"), java.util.Set.of(), null);
    var document = new ParsedDocument("source", List.of(chapterHeading(), paragraph("公式（3-1）", List.of(0, 1))),
        List.of(), List.of(), List.of(), List.of(equation), List.of());

    assertTrue(new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .noneMatch(item -> item.ruleId().equals("EQ-08")));
  }

  @Test
  void checksOmmlEquationNumberFromItsMathRunInsteadOfReportingItMissing() {
    var paragraphSource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 0);
    var numberRunSource = new SourceAddress("word/document.xml", List.of(0, 1, 0, 0, 2), 0, 5);
    var paragraph = new ParagraphModel(List.of(), 0, null, null, null, null,
        paragraphSource, 0, null, "center");
    var equation = new EquationModel(paragraphSource, "OMML", "x=1", "3-1", true, false,
        java.util.Set.of("oMath", "eqArr"), java.util.Set.of(), null, null, null,
        java.util.Set.of("x"), true,
        new EquationNumberEvidence("(3-1)", true, "Cambria Math", null, numberRunSource));
    var document = new ParsedDocument("source", List.of(chapterHeading(), paragraph), List.of(), List.of(),
        List.of(), List.of(equation), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().noneMatch(item -> item.reasonCode().equals("EQUATION_NUMBER_MISSING")
        || item.reasonCode().equals("EQUATION_NUMBER_PARENTHESES_MISSING")), results.toString());
    var format = results.stream().filter(item -> item.ruleId().equals("EQ-02")).findFirst().orElseThrow();
    assertEquals(Judgement.FAIL, format.judgement());
    assertTrue(format.actual().contains("Cambria Math"));
    assertTrue(format.actual().contains("未明确指定字号"));
    var alignment = results.stream().filter(item -> item.ruleId().equals("EQ-03")).findFirst().orElseThrow();
    assertEquals(Judgement.REVIEW, alignment.judgement());
    assertEquals("EQUATION_ALIGNMENT_UNKNOWN", alignment.reasonCode());
    assertTrue(alignment.actual().contains("未读取到公式号的右对齐证据"));
  }

  @Test
  void excludesOmmlNumberedEquationLayoutTableFromThesisTableRules() {
    var tableSource = new SourceAddress("word/document.xml", List.of(0, 6), 0, 0);
    var formulaSource = new SourceAddress("word/document.xml", List.of(0, 6, 0, 0, 0, 0), 0, 1);
    var numberSource = new SourceAddress("word/document.xml", List.of(0, 6, 0, 1, 0, 1, 0), 0, 5);
    var table = new TableModel(tableSource, null, null, 1, 2,
        false, false, false, false, false, false, false, List.of(), List.of(),
        List.of("", "(3-3)"), null, null, false);
    var equation = new EquationModel(formulaSource, "OMML", "x=1", "3-3", true, false,
        java.util.Set.of("oMath", "eqArr"), java.util.Set.of(), null, null, null,
        java.util.Set.of("x"), true,
        new EquationNumberEvidence("(3-3)", true, "Times New Roman", 21, numberSource));
    var document = new ParsedDocument("source", List.of(chapterHeading()), List.of(), List.of(),
        List.of(table), List.of(equation), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().noneMatch(result -> result.ruleId().startsWith("TAB-")
        && result.source().elementPath().equals(tableSource.elementPath())), results.toString());
  }

  @Test
  void acceptsStandardOmmlPropertyNodesAsReadableStructure() {
    var equation = new cn.luwneshacha.thesis.document.EquationModel(ADDRESS, "OMML", "x", "3-1",
        true, false, java.util.Set.of("oMath", "f", "num", "den", "argPr", "argSz", "scr",
            "naryPr", "limLoc", "grow", "subHide"), java.util.Set.of(), null);
    var document = new ParsedDocument("source", List.of(chapterHeading(), paragraph("公式（3-1）", List.of(0, 1))),
        List.of(), List.of(), List.of(), List.of(equation), List.of());

    assertTrue(new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .noneMatch(item -> item.ruleId().equals("EQ-08")));
  }

  @Test
  void doesNotTreatImageAspectRatioAsSchoolFormatError() {
    var image = new cn.luwneshacha.thesis.document.ImageModel(ADDRESS,
        new SourceAddress("word/document.xml", List.of(0, 1), 0, 10), "图1-1 示例", 100, 200,
        true, true, true, 200, 200);
    var document = new ParsedDocument("source", List.of(chapterHeading(), paragraph("图1-1 示例", List.of(0, 1))),
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(image));

    var results = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("FIG-04")).toList();

    assertTrue(results.isEmpty());
  }

  @Test
  void countsACompositeFigureCaptionOnlyOnce() {
    var captionSource = new SourceAddress("word/document.xml", List.of(0, 2), 0, 8);
    var first = new ImageModel(new SourceAddress("word/document.xml", List.of(0, 1, 0), 0, 0),
        captionSource, "图1-1 组合图", 100, 100, true, true, true);
    var second = new ImageModel(new SourceAddress("word/document.xml", List.of(0, 1, 1), 0, 0),
        captionSource, "图1-1 组合图", 100, 100, true, true, true);
    var document = new ParsedDocument("source", List.of(chapterHeading(),
        paragraph("图1-1 组合图", List.of(0, 2))), List.of(), List.of(), List.of(), List.of(),
        List.of(), List.of(first, second));

    var results = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("FIG-05")).toList();

    assertEquals(1, results.size());
    assertEquals(Judgement.PASS, results.get(0).judgement());
  }

  @Test
  void doesNotTreatMissingImageXmlSizeAsSchoolFormatError() {
    var image = new cn.luwneshacha.thesis.document.ImageModel(ADDRESS,
        new SourceAddress("word/document.xml", List.of(0, 1), 0, 10), "图3-1 示例", 0, 0,
        true, true, true, 0, 0);
    var document = new ParsedDocument("source", List.of(chapterHeading()), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(image));

    var results = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("FIG-04")).toList();

    assertTrue(results.isEmpty());
  }

  @Test
  void recognizesCommonChineseTableReferencePhrases() {
    var body = paragraph("如表3-1所示，系统测试结果如下。", List.of(0, 1));
    var captionSource = new SourceAddress("word/document.xml", List.of(0, 2), 0, 8);
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(0, 3), 0, 0),
        captionSource, "表3-1 测试结果", 2, 2, true, true, true, false, true, false);
    var document = new ParsedDocument("source", List.of(chapterHeading(), body), List.of(), List.of(),
        List.of(table), List.of(), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("TAB-01")).findFirst().orElseThrow();

    assertEquals(Judgement.PASS, result.judgement());
  }

  @Test
  void checksEquationNumberFontAndSizeAcrossRuns() {
    var paragraphSource = new SourceAddress("word/document.xml", List.of(0), 0, 5);
    var equationSource = new SourceAddress("word/document.xml", List.of(0, 0), 0, 1);
    var paragraph = new ParagraphModel(List.of(
        new RunModel("(3-", "Times New Roman", "宋体", null, 20, false,
            new SourceAddress("word/document.xml", List.of(0), 0, 3)),
        new RunModel("1)", "Arial", "宋体", null, 22, false,
            new SourceAddress("word/document.xml", List.of(0), 3, 5))),
        0, null, null, null, null, paragraphSource, 5);
    var equation = new cn.luwneshacha.thesis.document.EquationModel(
        equationSource, "OMML", "x", "3-1", true, false, java.util.Set.of("oMath"),
        java.util.Set.of(), null, paragraphSource);
    var document = new ParsedDocument("source", List.of(paragraph), List.of(), List.of(),
        List.of(), List.of(equation), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("EQ-02")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("EQUATION_NUMBER_FORMAT_MISMATCH", result.reasonCode());
  }

  @Test
  void passesEquationNumberWithRequiredEffectiveFormatting() {
    var paragraphSource = new SourceAddress("word/document.xml", List.of(0), 0, 5);
    var equationSource = new SourceAddress("word/document.xml", List.of(0, 0), 0, 1);
    var paragraph = new ParagraphModel(List.of(new RunModel("(3-1)", "Times New Roman", "宋体", null,
        21, false, paragraphSource)),
        0, null, null, null, null, paragraphSource, 5);
    var equation = new cn.luwneshacha.thesis.document.EquationModel(
        equationSource, "OMML", "x", "3-1", true, false, java.util.Set.of("oMath"),
        java.util.Set.of(), null, paragraphSource);
    var document = new ParsedDocument("source", List.of(paragraph), List.of(), List.of(),
        List.of(), List.of(equation), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("EQ-02")).findFirst().orElseThrow();

    assertEquals(Judgement.PASS, result.judgement());
  }

  @Test
  void keepsEquationRuleIdsAlignedWithSchoolMatrix() {
    var chapterSource = new SourceAddress("word/document.xml", List.of(0, 0), 0, 6);
    var referenceSource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 12);
    var equationParagraphSource = new SourceAddress("word/document.xml", List.of(0, 2), 0, 5);
    var equationSource = new SourceAddress("word/document.xml", List.of(0, 2, 0), 0, 1);
    var chapter = new ParagraphModel(List.of(new RunModel("第1章 绪论", "Times New Roman", "黑体", null,
        32, true, chapterSource)), 0, null, null, null, null, chapterSource, 0);
    var reference = new ParagraphModel(List.of(new RunModel("由式(3-1)可知", "Times New Roman", "宋体", null,
        24, false, referenceSource)), 480, null, null, 360, "auto", referenceSource, 12);
    var equationParagraph = new ParagraphModel(List.of(new RunModel("(3-1)", "Times New Roman", "宋体", null,
        20, false, equationParagraphSource)), 0, null, null, null, null, equationParagraphSource, 5);
    var equation = new cn.luwneshacha.thesis.document.EquationModel(
        equationSource, "OMML", "x", "3-1", true, false);
    var document = new ParsedDocument("source", List.of(chapter, reference, equationParagraph), List.of(), List.of(),
        List.of(), List.of(equation), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertEquals(Judgement.FAIL, results.stream().filter(item -> item.ruleId().equals("EQ-03"))
        .findFirst().orElseThrow().judgement());
    assertEquals(Judgement.PASS, results.stream().filter(item -> item.ruleId().equals("EQ-04"))
        .findFirst().orElseThrow().judgement());
    assertEquals(Judgement.FAIL, results.stream().filter(item -> item.ruleId().equals("EQ-07"))
        .findFirst().orElseThrow().judgement());
  }

  @Test
  void doesNotCountSpacedOrMismatchedParenthesesAsEquationCitation() {
    var equationParagraphSource = new SourceAddress("word/document.xml", List.of(0, 2), 0, 5);
    var citationSource = new SourceAddress("word/document.xml", List.of(0, 3), 0, 30);
    var equation = new cn.luwneshacha.thesis.document.EquationModel(
        new SourceAddress("word/document.xml", List.of(0, 2, 0), 0, 1),
        "OMML", "x", "3-1", true, false);
    var equationParagraph = new ParagraphModel(List.of(new RunModel("(3-1)", "Times New Roman", "宋体",
        null, 21, false, equationParagraphSource)), 0, null, null, null, null, equationParagraphSource, 0);
    var citation = new ParagraphModel(List.of(new RunModel("由公式（ 3-1 ）可知，公式(3-1）可得。",
        "Times New Roman", "宋体", null, 24, false, citationSource)),
        480, null, null, 360, "auto", citationSource, 200);
    var document = new ParsedDocument("source", List.of(chapterHeading(), equationParagraph, citation),
        List.of(), List.of(), List.of(), List.of(equation), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("EQ-04")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("EQUATION_NOT_REFERENCED", result.reasonCode());
  }

  @Test
  void rejectsSameParagraphEquationNumberWithoutRightAlignmentEvidence() {
    var paragraphSource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 5);
    var equation = new cn.luwneshacha.thesis.document.EquationModel(
        new SourceAddress("word/document.xml", List.of(0, 1, 0), 0, 1), "OMML", "x", "3-1", true, false);
    var paragraph = new ParagraphModel(List.of(new RunModel("(3-1)", "Times New Roman", "宋体", null,
        21, false, paragraphSource)), 0, null, null, null, null, paragraphSource, 5, null, "right");
    var document = new ParsedDocument("source", List.of(chapterHeading(), paragraph), List.of(), List.of(),
        List.of(), List.of(equation), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("EQ-03")).findFirst().orElseThrow();

    assertEquals(Judgement.REVIEW, result.judgement());
    assertEquals("EQUATION_ALIGNMENT_UNKNOWN", result.reasonCode());
  }

  @Test
  void doesNotAcceptCenteredEquationAndNumberInSameParagraphAsRightAligned() {
    var chapter = chapterHeading();
    var equationParagraphSource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 12);
    var equation = new cn.luwneshacha.thesis.document.EquationModel(
        new SourceAddress("word/document.xml", List.of(0, 1, 0), 0, 1), "OMML", "", "3-1", true, false,
        java.util.Set.of(), java.util.Set.of(), null,
        new SourceAddress("word/document.xml", List.of(0, 1), 6, 11));
    var paragraph = new ParagraphModel(List.of(new RunModel("      (3-1)", "Times New Roman", "宋体", null,
        21, false, equationParagraphSource)), 0, null, null, null, null,
        equationParagraphSource, 0, null, "center");
    var document = new ParsedDocument("source", List.of(chapter, paragraph), List.of(), List.of(),
        List.of(), List.of(equation), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("EQ-03")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("EQUATION_ALIGNMENT_MISMATCH", result.reasonCode());
  }

  @Test
  void doesNotTreatSpacesBeforeEquationNumberAsRightAlignmentEvidence() {
    var chapter = chapterHeading();
    var equationParagraphSource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 11);
    var equation = new cn.luwneshacha.thesis.document.EquationModel(
        new SourceAddress("word/document.xml", List.of(0, 1, 0), 0, 1), "OMML", "x", "3-1", true, false,
        java.util.Set.of(), java.util.Set.of(), null,
        new SourceAddress("word/document.xml", List.of(0, 1), 6, 11));
    var paragraph = new ParagraphModel(List.of(new RunModel("      (3-1)", "Times New Roman", "宋体", null,
        21, false, equationParagraphSource)), 0, null, null, null, null,
        equationParagraphSource, 0, null, "center");
    var document = new ParsedDocument("source", List.of(chapter, paragraph), List.of(), List.of(),
        List.of(), List.of(equation), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("EQ-03")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("EQUATION_ALIGNMENT_MISMATCH", result.reasonCode());
  }

  @Test
  void rejectsCenteredSameParagraphFormulaWithNumberAfterTabWithoutTabStopEvidence() {
    var chapter = chapterHeading();
    var equationParagraphSource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 6);
    var numberSource = new SourceAddress("word/document.xml", List.of(0, 1), 1, 6);
    var equation = new cn.luwneshacha.thesis.document.EquationModel(
        new SourceAddress("word/document.xml", List.of(0, 1, 0), 0, 1), "OMML", "x", "3-1", true, false,
        java.util.Set.of(), java.util.Set.of(), null, numberSource);
    var paragraph = new ParagraphModel(List.of(new RunModel("\t(3-1)", "Times New Roman", "宋体", null,
        21, false, equationParagraphSource)), 0, null, null, null, null,
        equationParagraphSource, 0, null, "center");
    var document = new ParsedDocument("source", List.of(chapter, paragraph), List.of(), List.of(),
        List.of(), List.of(equation), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("EQ-03")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement(), result.toString());
    assertEquals("EQUATION_ALIGNMENT_MISMATCH", result.reasonCode());
  }

  @Test
  void acceptsCenteredEquationWithNumberRightAlignedByVerifiedTabStop() {
    var chapter = chapterHeading();
    var paragraphSource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 6);
    var numberSource = new SourceAddress("word/document.xml", List.of(0, 1), 1, 6);
    var equation = new EquationModel(new SourceAddress("word/document.xml", List.of(0, 1, 0), 0, 1),
        "OMML", "x", "3-1", true, false, java.util.Set.of(), java.util.Set.of(), null,
        numberSource, null, java.util.Set.of(), false, null, true);
    var paragraph = new ParagraphModel(List.of(new RunModel("\t(3-1)", "Times New Roman", "瀹嬩綋", null,
        21, false, paragraphSource)), 0, null, null, null, null, paragraphSource, 0, null, "center");
    var document = new ParsedDocument("source", List.of(chapter, paragraph), List.of(), List.of(),
        List.of(), List.of(equation), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("EQ-03")).findFirst().orElseThrow();

    assertEquals(Judgement.PASS, result.judgement());
  }

  @Test
  void acceptsTableReferenceWhenWordSplitsTheNumberWithWhitespaceAroundHyphen() {
    var body = paragraph("管理员操作如表6- 2所示：", List.of(0, 1));
    var caption = paragraph("表6-2 管理员测试", List.of(0, 2));
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(0, 3), 0, 0),
        caption.source(), "表6-2 管理员测试", 2, 4, true, true, true, false, true, false);
    var document = new ParsedDocument("source", List.of(chapterHeading(), body, caption), List.of(), List.of(),
        List.of(table), List.of(), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("TAB-01")).findFirst().orElseThrow();

    assertEquals(Judgement.PASS, result.judgement());
    assertEquals("6-2", result.actual());
  }

  @Test
  void rejectsVariableExplanationWhenEquationVariablesCannotBeRead() {
    var chapter = chapterHeading();
    var equationParagraphSource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 5);
    var equation = new cn.luwneshacha.thesis.document.EquationModel(
        new SourceAddress("word/document.xml", List.of(0, 1, 0), 0, 0), "OMML", "", "3-1", true, false,
        java.util.Set.of(), java.util.Set.of(), null, equationParagraphSource, "式中：z0为位移；θ0为入射角。");
    var paragraph = new ParagraphModel(List.of(new RunModel("(3-1)", "Times New Roman", "宋体", null,
        21, false, equationParagraphSource)), 0, null, null, null, null, equationParagraphSource, 0);
    var document = new ParsedDocument("source", List.of(chapter, paragraph), List.of(), List.of(),
        List.of(), List.of(equation), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("EQ-07")).findFirst().orElseThrow();

    assertEquals(Judgement.REVIEW, result.judgement());
    assertEquals("EQUATION_VARIABLES_UNREADABLE", result.reasonCode());
  }

  @Test
  void doesNotAssumeAnExplanationCoversEveryEquationVariable() {
    var chapter = chapterHeading();
    var equationSource = new SourceAddress("word/document.xml", List.of(0, 1, 0), 0, 1);
    var equationParagraphSource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 5);
    var variablesSource = new SourceAddress("word/document.xml", List.of(0, 2), 0, 18);
    var equationParagraph = new ParagraphModel(List.of(new RunModel("(3-1)", "Times New Roman", "宋体", null,
        20, false, equationParagraphSource)), 0, null, null, null, null, equationParagraphSource, 5);
    var variables = new ParagraphModel(List.of(new RunModel("式中：L0为长度，ρ为密度。", "Times New Roman", "宋体", null,
        24, false, variablesSource)), 480, null, null, 360, "auto", variablesSource, 200);
    var equation = new cn.luwneshacha.thesis.document.EquationModel(
        equationSource, "OMML", "x", "3-1", true, false);
    var document = new ParsedDocument("source", List.of(chapter, equationParagraph, variables), List.of(), List.of(),
        List.of(), List.of(equation), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("EQ-07")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("EQUATION_VARIABLES_MISSING", result.reasonCode());
    assertTrue(result.actual().contains("x"));
  }

  @Test
  void requiresAnExplanationForUnicodeLetterLikeEquationVariables() {
    var chapter = chapterHeading();
    var equationSource = new SourceAddress("word/document.xml", List.of(0, 1, 0), 0, 1);
    var equation = new cn.luwneshacha.thesis.document.EquationModel(
        equationSource, "OMML", "ℓ", "3-1", true, false);
    var document = new ParsedDocument("source", List.of(chapter), List.of(), List.of(),
        List.of(), List.of(equation), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("EQ-07")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("EQUATION_VARIABLES_MISSING", result.reasonCode());
    assertTrue(result.actual().contains("ℓ"));
  }

  @Test
  void passesOnlyWhenEachReadableEquationVariableHasAnExplicitDefinition() {
    var equationSource = new SourceAddress("word/document.xml", List.of(0, 1, 0), 0, 1);
    var equationParagraphSource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 5);
    var equation = new cn.luwneshacha.thesis.document.EquationModel(equationSource, "OMML",
        "x+y+θ0", "3-1", true, false, java.util.Set.of(), java.util.Set.of(), null,
        equationParagraphSource, "式中：x为长度；y表示宽度；θ0为入射角。");
    var document = new ParsedDocument("source", List.of(chapterHeading()), List.of(), List.of(),
        List.of(), List.of(equation), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("EQ-07")).findFirst().orElseThrow();

    assertEquals(Judgement.PASS, result.judgement());
    assertEquals("x、y、θ0", result.actual());
  }

  @Test
  void checksStructuredEquationSymbolsInsteadOfFlattenedMathText() {
    var source = new SourceAddress("word/document.xml", List.of(0, 1, 0), 0, 1);
    var equation = new cn.luwneshacha.thesis.document.EquationModel(source, "OMML",
        "sin x + BreakfastRate", "3-1", true, false, java.util.Set.of(), java.util.Set.of(),
        null, new SourceAddress("word/document.xml", List.of(0, 1), 0, 5),
        "式中：x为变量；BreakfastRate为测量值。", java.util.Set.of("x", "BreakfastRate"), true);
    var document = new ParsedDocument("source", List.of(chapterHeading()), List.of(), List.of(),
        List.of(), List.of(equation), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("EQ-07")).findFirst().orElseThrow();

    assertEquals(Judgement.PASS, result.judgement());
    assertEquals("BreakfastRate、x", result.actual());
  }

  @Test
  void reportsMissingSubscriptSymbolWhenItHasNoExplanation() {
    var source = new SourceAddress("word/document.xml", List.of(0, 1, 0), 0, 1);
    var equation = new cn.luwneshacha.thesis.document.EquationModel(source, "OMML", "x_i", "3-1",
        true, false, java.util.Set.of(), java.util.Set.of(), null,
        new SourceAddress("word/document.xml", List.of(0, 1), 0, 5), "式中：x为自变量。",
        java.util.Set.of("x", "i"), true);
    var document = new ParsedDocument("source", List.of(chapterHeading()), List.of(), List.of(),
        List.of(), List.of(equation), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("EQ-07")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("EQUATION_VARIABLES_MISSING", result.reasonCode());
    assertTrue(result.actual().contains("i"));
  }

  @Test
  void acceptsOneDefinitionForACompleteStructuredSubscriptAndSuperscriptToken() {
    var equationSource = new SourceAddress("word/document.xml", List.of(0, 1, 0), 0, 1);
    var equation = new cn.luwneshacha.thesis.document.EquationModel(equationSource, "OMML", "x_i^2", "3-1",
        true, false, java.util.Set.of(), java.util.Set.of(), null,
        new SourceAddress("word/document.xml", List.of(0, 1), 0, 5),
        "\u5f0f\u4e2d\uff1ax\u1d62\u00b2\u4e3a\u7b2c i \u4e2a\u53c2\u6570\uff0c",
        java.util.Set.of("x_i^2"), true, null, null, null, null,
        java.util.List.of(new cn.luwneshacha.thesis.document.EquationVariable("x", "i", "2", "x_i^2",
            equationSource)));
    var document = new ParsedDocument("source", List.of(chapterHeading()), List.of(), List.of(),
        List.of(), List.of(equation), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("EQ-07")).findFirst().orElseThrow();

    assertEquals(Judgement.PASS, result.judgement());
  }

  @Test
  void recognizesChineseEnumerationCommaBetweenEquationVariableDefinitions() {
    var equationSource = new SourceAddress("word/document.xml", List.of(0, 1, 0), 0, 1);
    var equationParagraphSource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 5);
    var equation = new cn.luwneshacha.thesis.document.EquationModel(equationSource, "OMML",
        "x+y", "3-1", true, false, java.util.Set.of(), java.util.Set.of(), null,
        equationParagraphSource, "式中：x为长度、y为宽度。");
    var document = new ParsedDocument("source", List.of(chapterHeading()), List.of(), List.of(),
        List.of(), List.of(equation), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("EQ-07")).findFirst().orElseThrow();

    assertEquals(Judgement.PASS, result.judgement());
  }

  @Test
  void reportsUnreferencedEquationUnderEq04AndDuplicateNumberUnderEq05() {
    var equationParagraphSource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 5);
    var firstEquation = new cn.luwneshacha.thesis.document.EquationModel(
        new SourceAddress("word/document.xml", List.of(0, 1, 0), 0, 1), "OMML", "x", "3-1", true, false);
    var secondEquation = new cn.luwneshacha.thesis.document.EquationModel(
        new SourceAddress("word/document.xml", List.of(0, 1, 1), 0, 1), "OMML", "y", "3-1", true, false);
    var chapter = chapterHeading();
    var equationParagraph = new ParagraphModel(List.of(new RunModel("(3-1)", "Times New Roman", "宋体", null,
        20, false, equationParagraphSource)), 0, null, null, null, null, equationParagraphSource, 5);
    var document = new ParsedDocument("source", List.of(chapter, equationParagraph), List.of(), List.of(),
        List.of(), List.of(firstEquation, secondEquation), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("EQ-04")
        && item.judgement() == Judgement.FAIL && item.reasonCode().equals("EQUATION_NOT_REFERENCED")));
    assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("EQ-05")
        && item.judgement() == Judgement.FAIL && item.reasonCode().equals("EQUATION_NUMBER_DUPLICATE")));
  }

  @Test
  void rejectsEquationNumberThatDoesNotMatchItsChapter() {
    var chapterSource = new SourceAddress("word/document.xml", List.of(0, 0), 0, 9);
    var equationParagraphSource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 5);
    var chapter = new ParagraphModel(List.of(new RunModel("第三章 设计的主体内容", "Times New Roman", "黑体",
        null, 32, true, chapterSource)), 0, null, null, null, null, chapterSource, 0);
    var equationParagraph = new ParagraphModel(List.of(new RunModel("(2-1)", "Times New Roman", "宋体",
        null, 21, false, equationParagraphSource)), 0, null, null, null, null, equationParagraphSource, 0);
    var equation = new cn.luwneshacha.thesis.document.EquationModel(
        new SourceAddress("word/document.xml", List.of(0, 1, 0), 0, 1), "OMML", "x", "2-1", true, false);
    var document = new ParsedDocument("source", List.of(chapter, equationParagraph), List.of(), List.of(),
        List.of(), List.of(equation), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("EQ-05")
            && item.reasonCode().equals("EQUATION_NUMBER_SEQUENCE_MISMATCH"))
        .findFirst().orElseThrow();

    assertEquals("3-1", result.expected());
    assertEquals("2-1", result.actual());
  }

  @Test
  void rejectsZeroPaddedEquationSequenceNumber() {
    var equationParagraphSource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 7);
    var equationParagraph = new ParagraphModel(List.of(new RunModel("(1-01)", "Times New Roman", "宋体",
        null, 21, false, equationParagraphSource)), 0, null, null, null, null, equationParagraphSource, 0);
    var equation = new cn.luwneshacha.thesis.document.EquationModel(
        new SourceAddress("word/document.xml", List.of(0, 1, 0), 0, 1), "OMML", "x", "1-01", true, false);
    var document = new ParsedDocument("source", List.of(chapterHeading(), equationParagraph), List.of(), List.of(),
        List.of(), List.of(equation), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.reasonCode().equals("EQUATION_NUMBER_SEQUENCE_MISMATCH")).findFirst().orElseThrow();

    assertEquals("1-1", result.expected());
    assertEquals("1-01", result.actual());
  }

  @Test
  void putsOpaqueEquationObjectsUnderAuxiliaryEq06() {
    var equation = new cn.luwneshacha.thesis.document.EquationModel(
        new SourceAddress("word/document.xml", List.of(0, 0), 0, 1), "OLE", "embedded equation", "3-1", true, true);
    var paragraphSource = new SourceAddress("word/document.xml", List.of(0), 0, 5);
    var paragraph = new ParagraphModel(List.of(new RunModel("(3-1)", "Times New Roman", "宋体", null,
        20, false, paragraphSource)), 0, null, null, null, null, paragraphSource, 5);
    var document = new ParsedDocument("source", List.of(paragraph), List.of(), List.of(),
        List.of(), List.of(equation), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("EQ-06")).findFirst().orElseThrow();

    assertEquals(Judgement.REVIEW, result.judgement());
    assertEquals("EQUATION_OBJECT_UNSUPPORTED", result.reasonCode());
  }

  @Test
  void reportsUncitedTableInBody() {
    var caption = paragraph("表1-1 数据", List.of(0, 2));
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(0, 3), 0, 0),
        caption.source(), "表1-1 数据", 2, 2, true, true, true, false, true, false);
    var document = new ParsedDocument("source", List.of(chapterHeading(), caption), List.of(), List.of(),
        List.of(table), List.of(), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("TAB-01")).findFirst().orElseThrow();
    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("TABLE_NOT_CITED_IN_BODY", result.reasonCode());
  }

  @Test
  void reportsUncitedLogicalTableOnlyOnceWhenItHasContinuationPages() {
    var captionSource = new SourceAddress("word/document.xml", List.of(0, 2), 0, 2);
    var continuationCaptionSource = new SourceAddress("word/document.xml", List.of(0, 5), 0, 3);
    var caption = paragraph("表6-6 数据", List.of(0, 2));
    var continuationCaption = paragraph("续表6-6", List.of(0, 5));
    var firstPage = new TableModel(new SourceAddress("word/document.xml", List.of(0, 3), 0, 0),
        captionSource, "表6-6 数据", 2, 2, true, true, true, false, true, false);
    var continuation = new TableModel(new SourceAddress("word/document.xml", List.of(0, 6), 0, 0),
        continuationCaptionSource, "续表6-6", 2, 2, true, true, true, false, true, false);
    var document = new ParsedDocument("source",
        List.of(chapterHeading(), caption, continuationCaption), List.of(), List.of(),
        List.of(firstPage, continuation), List.of(), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("TAB-01")).toList();
    assertEquals(1, results.size());
    assertEquals(Judgement.FAIL, results.get(0).judgement());
  }

  @Test
  void failsWhenTableMissesRequiredThreeLineBorders() {
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(0, 3), 0, 0),
        ADDRESS, "表1-1 数据", 2, 2, false, false, false, false, true, false);
    var document = new ParsedDocument("source", List.of(), List.of(), List.of(),
        List.of(table), List.of(), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("TAB-02")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("TABLE_BORDER_INVALID", result.reasonCode());
    assertTrue(result.actual().contains("缺少符合规范的顶线、表头下横线、底线"));
  }

  @Test
  void reviewsTableBorderCheckWhenConditionalStyleEvidenceIsUnresolved() {
    var table = new TableModel(ADDRESS, ADDRESS, "表1-1 数据", 2, 2,
        true, true, true, false, true, false, false, List.of(), List.of(), List.of(),
        null, null, false, true, null, false, false);
    var document = new ParsedDocument("source", List.of(), List.of(), List.of(),
        List.of(table), List.of(), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("TAB-02")).findFirst().orElse(null);

    assertTrue(result != null);
    assertEquals(Judgement.REVIEW, result.judgement());
    assertEquals("TABLE_BORDER_EVIDENCE_UNRESOLVED", result.reasonCode());
  }

  @Test
  void reportsFigurePlacementAsReviewWhenCaptionExists() {
    var image = new ImageModel(new SourceAddress("word/document.xml", List.of(1), 0, 0),
        ADDRESS, "图1-1 示例", 100, 200, true, true);
    var document = new ParsedDocument("source", List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(image));

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(r -> r.ruleId().equals("FIG-02")).findFirst().orElseThrow();

    assertEquals(Judgement.REVIEW, result.judgement());
    assertEquals("FIGURE_LAYOUT_REVIEW", result.reasonCode());
  }

  @Test
  void reportsFigurePlacementPassWhenParserHasVerifiedInlineCenteredLayout() {
    var image = new ImageModel(new SourceAddress("word/document.xml", List.of(1), 0, 0),
        ADDRESS, "图3-1 示例", 100, 200, true, false);
    var document = new ParsedDocument("source", List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(image));

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("FIG-02")).findFirst().orElseThrow();

    assertEquals(Judgement.PASS, result.judgement());
  }

  @Test
  void doesNotRequireImageParagraphToBeCenteredWhenCaptionIsCentered() {
    var image = new ImageModel(new SourceAddress("word/document.xml", List.of(1), 0, 0),
        ADDRESS, "图3-1 示例", 100, 200, true, false, true);
    var document = new ParsedDocument("source", List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(image));

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("FIG-02")).findFirst().orElseThrow();

    assertEquals(Judgement.PASS, result.judgement());
  }

  @Test
  void ignoresCoverLogoWhenCheckingFigureCaptions() {
    var coverParagraph = paragraph("cover", List.of(0, 1));
    var chapter = paragraph("第一章 绪论", List.of(0, 2));
    var image = new ImageModel(coverParagraph.source(), null, null, 100, 200, true, true);
    var document = new ParsedDocument("source", List.of(coverParagraph, chapter), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(image));

    var figureResults = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().startsWith("FIG-")).toList();

    assertTrue(figureResults.isEmpty());
  }

  @Test
  void checksThatCaptionedFiguresAreReferencedInBodyText() {
    var bodySource = new SourceAddress("word/document.xml", List.of(0, 2), 0, 18);
    var captionSource = new SourceAddress("word/document.xml", List.of(0, 3), 0, 9);
    var body = paragraph("系统结构如图1-1所示。", List.of(0, 2));
    var caption = paragraph("图1-1 系统结构图", List.of(0, 3));
    var image = new ImageModel(new SourceAddress("word/document.xml", List.of(0, 4), 0, 0),
        captionSource, "图1-1 系统结构图", 100, 200, true, true);
    var document = new ParsedDocument("source", List.of(chapterHeading(), body, caption), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(image));

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("FIG-01")).findFirst().orElseThrow();

    assertEquals(Judgement.PASS, result.judgement());
    assertEquals(bodySource.elementPath(), body.source().elementPath());
  }

  @Test
  void doesNotTreatLongerFigureNumberAsReferenceToItsPrefix() {
    var body = paragraph("系统结构如图1-10所示。", List.of(0, 2));
    var caption = paragraph("图1-1 系统结构图", List.of(0, 3));
    var image = new ImageModel(new SourceAddress("word/document.xml", List.of(0, 4), 0, 0),
        caption.source(), "图1-1 系统结构图", 100, 200, true, true);
    var document = new ParsedDocument("source", List.of(chapterHeading(), body, caption), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(image));

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("FIG-01")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("FIGURE_NOT_CITED_IN_BODY", result.reasonCode());
  }

  @Test
  void zeroWidthWhitespaceInAppendixHeadingStillEndsBodyReferenceScan() {
    var caption = paragraph("图1-1 系统结构图", List.of(0, 2));
    var appendix = paragraph("附\u200B录\u00A0A 补充材料", List.of(0, 4));
    var appendixReference = paragraph("参见图1-1", List.of(0, 5));
    var image = new ImageModel(new SourceAddress("word/document.xml", List.of(0, 3), 0, 0),
        caption.source(), "图1-1 系统结构图", 100, 200, true, true);
    var document = new ParsedDocument("source", List.of(chapterHeading(), caption, appendix, appendixReference),
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(image));

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("FIG-01")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("FIGURE_NOT_CITED_IN_BODY", result.reasonCode());
  }

  @Test
  void malformedFigureCaptionDoesNotShiftTheNextValidFigureSequence() {
    var malformed = paragraph("图 1-1 错误格式", List.of(0, 2));
    var valid = paragraph("图1-1 正常图题", List.of(0, 4));
    var firstImage = new ImageModel(new SourceAddress("word/document.xml", List.of(0, 3, 0), 0, 0),
        malformed.source(), "图 1-1 错误格式", 100, 200, true, true);
    var secondImage = new ImageModel(new SourceAddress("word/document.xml", List.of(0, 5, 0), 0, 0),
        valid.source(), "图1-1 正常图题", 100, 200, true, true);
    var document = new ParsedDocument("source", List.of(chapterHeading(), malformed, valid),
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(firstImage, secondImage));

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(item -> item.reasonCode().equals("FIGURE_CAPTION_NUMBER_FORMAT")));
    assertTrue(results.stream().noneMatch(item -> item.reasonCode().equals("FIGURE_NUMBER_SEQUENCE_MISMATCH")));
  }

  @Test
  void checksFigureTableAndEquationSequencesForChapterNumbersBeyondIntRange() {
    String chapterNumber = "2147483648";
    var chapter = paragraph("第" + chapterNumber + "章 超大章节号", List.of(0, 0));
    String figureCaptionText = "图" + chapterNumber + "-2 Figure";
    var figureCaption = paragraph(figureCaptionText, List.of(0, 4));
    var image = new ImageModel(new SourceAddress("word/document.xml", List.of(0, 3), 0, 0),
        figureCaption.source(), figureCaptionText, 100, 100, true, true, true);
    String tableCaptionText = "表" + chapterNumber + "-2 Table";
    var tableCaption = paragraph(tableCaptionText, List.of(0, 6));
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(0, 7), 0, 0),
        tableCaption.source(), tableCaptionText, 2, 2, true, true, true, false, true, false);
    var equation = new cn.luwneshacha.thesis.document.EquationModel(
        new SourceAddress("word/document.xml", List.of(0, 2), 0, 0), "OMML", "x",
        chapterNumber + "-2", true, false);
    var document = new ParsedDocument("source", List.of(chapter, figureCaption, tableCaption),
        List.of(), List.of(), List.of(table), List.of(equation), List.of(), List.of(image), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(item -> item.reasonCode().equals("FIGURE_NUMBER_SEQUENCE_MISMATCH")
        && item.expected().equals("图" + chapterNumber + "-1")), results.toString());
    assertTrue(results.stream().anyMatch(item -> item.reasonCode().equals("TABLE_NUMBER_SEQUENCE_MISMATCH")
        && item.expected().equals(chapterNumber + "-1")), results.toString());
    assertTrue(results.stream().anyMatch(item -> item.reasonCode().equals("EQUATION_NUMBER_SEQUENCE_MISMATCH")
        && item.expected().equals(chapterNumber + "-1")), results.toString());
  }

  @Test
  void doesNotMatchFigureReferenceAfterChangingHyphenWidth() {
    var body = paragraph("系统结构如图1－1所示。", List.of(0, 2));
    var caption = paragraph("图1-1 系统结构图", List.of(0, 3));
    var image = new ImageModel(new SourceAddress("word/document.xml", List.of(0, 4), 0, 0),
        caption.source(), "图1-1 系统结构图", 100, 200, true, true);
    var document = new ParsedDocument("source", List.of(chapterHeading(), body, caption), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(image));

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("FIG-01")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("FIGURE_NOT_CITED_IN_BODY", result.reasonCode());
  }

  @Test
  void acceptsExplicitFigureNumberEvenWhenReferenceWordingHasAnExtraCharacter() {
    var body = paragraph("系统运行总体流程如图6-1下所示。", List.of(0, 2));
    var caption = paragraph("图6-1 总体流程图", List.of(0, 3));
    var image = new ImageModel(new SourceAddress("word/document.xml", List.of(0, 4), 0, 0),
        caption.source(), "图6-1 总体流程图", 100, 200, true, true);
    var document = new ParsedDocument("source", List.of(chapterHeading(), body, caption), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(image));

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("FIG-01")).findFirst().orElseThrow();

    assertEquals(Judgement.PASS, result.judgement());
  }

  @Test
  void reportsCaptionedFigureThatIsNotReferencedInBody() {
    var caption = paragraph("图2-1 系统结构图", List.of(0, 2));
    var image = new ImageModel(new SourceAddress("word/document.xml", List.of(0, 3), 0, 0),
        caption.source(), "图2-1 系统结构图", 100, 200, true, true);
    var document = new ParsedDocument("source", List.of(chapterHeading(), caption), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(image));

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("FIG-01")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("FIGURE_NOT_CITED_IN_BODY", result.reasonCode());
  }

  @Test
  void reviewsGenericFigureReferenceWithoutAssumingWhichFigureItMeans() {
    var body = paragraph("系统工作人员总体流程如图所示。", List.of(0, 1));
    var caption = paragraph("图2-1 工作人员流程图", List.of(0, 2));
    var image = new ImageModel(new SourceAddress("word/document.xml", List.of(0, 3), 0, 0),
        caption.source(), "图2-1 工作人员流程图", 100, 200, true, true);
    var document = new ParsedDocument("source", List.of(chapterHeading(), body, caption), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(image));

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("FIG-01")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("FIGURE_REFERENCE_NEEDS_REVIEW", result.reasonCode());
  }

  @Test
  void doesNotLetNumberedFigureReferenceInSameParagraphHideAnUncitedFigure() {
    var body = paragraph("前文提到如图所示，具体见图2-1。", List.of(0, 1));
    var caption = paragraph("图1-1 工作人员流程图", List.of(0, 2));
    var image = new ImageModel(new SourceAddress("word/document.xml", List.of(0, 3), 0, 0),
        caption.source(), "图1-1 工作人员流程图", 100, 200, true, true);
    var document = new ParsedDocument("source", List.of(chapterHeading(), body, caption), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(image));

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("FIG-01")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("FIGURE_NOT_CITED_IN_BODY", result.reasonCode());
  }

  @Test
  void reportsAnImageWhoseFigureCaptionCannotBeLocated() {
    var image = new ImageModel(new SourceAddress("word/document.xml", List.of(0, 2), 0, 0),
        null, null, 100, 200, true, true);
    var document = new ParsedDocument("source", List.of(chapterHeading()), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(image));

    var results = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().startsWith("FIG-")).toList();

    assertEquals(1, results.size());
    assertEquals("FIGURE_CAPTION_MISSING", results.get(0).reasonCode());
    assertEquals(Judgement.FAIL, results.get(0).judgement());
  }

  @Test
  void stillRequiresFigureCaptionForCodeScreenshotAfterCodeIntroduction() {
    var introduction = paragraph("核心代码如下所示。", List.of(0, 1));
    var image = new ImageModel(new SourceAddress("word/document.xml", List.of(0, 2), 0, 0),
        null, null, 100, 200, true, true);
    var document = new ParsedDocument("source", List.of(chapterHeading(), introduction), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(image));

    var figureResults = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().startsWith("FIG-")).toList();

    assertEquals(1, figureResults.size());
    assertEquals("FIGURE_CAPTION_MISSING", figureResults.get(0).reasonCode());
    assertEquals(Judgement.FAIL, figureResults.get(0).judgement());
  }

  @Test
  void reportsTooFewReferencesAndUsesReferenceOrderRuleForDuplicates() {
    var first = new ReferenceEntryModel(ADDRESS, "1", "[1] A[M].", "M", false);
    var duplicate = new ReferenceEntryModel(new SourceAddress("word/document.xml", List.of(1), 0, 1),
        "1", "[1] B[J].", "J", false);
    var document = new ParsedDocument("source", List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of(first, duplicate), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(r -> r.ruleId().equals("REF-05") && r.judgement() == Judgement.FAIL));
    assertTrue(results.stream().anyMatch(r -> r.ruleId().equals("REF-03") && r.judgement() == Judgement.FAIL));
  }

  @Test
  void reportsCitationPositionButStillRegistersBracketedNumberBeforeMoreText() {
    var bodySource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 30);
    var body = new ParagraphModel(List.of(
        new RunModel("Array [1] stores values", "Times New Roman", "宋体", null, 24, false, "superscript", bodySource)),
        480, null, null, 360, "auto", bodySource, 200);
    var document = new ParsedDocument("source", List.of(chapterHeading(), body), List.of(), List.of());

    var citationResults = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(r -> r.ruleId().equals("CITE-01") || r.ruleId().equals("REF-03")).toList();

    assertTrue(citationResults.stream().anyMatch(r -> r.reasonCode().equals("CITATION_POSITION_MISMATCH")));
    assertTrue(citationResults.stream().noneMatch(r -> r.reasonCode().equals("REFERENCE_NOT_CITED")));
  }

  @Test
  void acceptsFigureReferenceSeparatedByNonBreakingSpace() {
    var body = paragraph("系统结构见图\u00a02-1。", List.of(0, 1));
    var caption = paragraph("图2-1 系统结构图", List.of(0, 2));
    var image = new ImageModel(new SourceAddress("word/document.xml", List.of(0, 3), 0, 0),
        caption.source(), "图2-1 系统结构图", 100, 200, true, true);
    var document = new ParsedDocument("source", List.of(chapterHeading(), body, caption), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(image));

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("FIG-01")).findFirst().orElseThrow();

    assertEquals(Judgement.PASS, result.judgement());
  }

  @Test
  void acceptsCitationBeforeChineseSentencePunctuation() {
    var source = new SourceAddress("word/document.xml", List.of(0, 1), 0, 20);
    var body = new ParagraphModel(List.of(
        new RunModel("正文引用[1]\u3002", "Times New Roman", "宋体", null, 24, false, "superscript", source)),
        480, null, null, 360, "auto", source, 200);
    var document = new ParsedDocument("source", List.of(chapterHeading(), body), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(r -> r.ruleId().equals("CITE-01")).findFirst().orElseThrow();

    assertEquals(Judgement.PASS, result.judgement());
  }

  @Test
  void rejectsNonCanonicalFontNameCaseForCitation() {
    var source = new SourceAddress("word/document.xml", List.of(0, 1), 0, 12);
    var body = new ParagraphModel(List.of(
        new RunModel("正文引用[1]。", "times new roman", "宋体", null, 24, false, "superscript", source)),
        480, null, null, 360, "auto", source, 200);
    var document = new ParsedDocument("source", List.of(chapterHeading(), body), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("CITE-01")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("CITATION_FORMAT_MISMATCH", result.reasonCode());
  }

  @Test
  void rejectsNonCanonicalCaseForCitationSuperscriptEnum() {
    var source = new SourceAddress("word/document.xml", List.of(0, 1), 0, 12);
    var body = new ParagraphModel(List.of(
        new RunModel("正文引用[1]。", "Times New Roman", "宋体", null, 24, false, "SUPERSCRIPT", source)),
        480, null, null, 360, "auto", source, 200);
    var document = new ParsedDocument("source", List.of(chapterHeading(), body), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("CITE-01")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("CITATION_FORMAT_MISMATCH", result.reasonCode());
  }

  @Test
  void rejectsZeroWidthCharactersInsideCitationBrackets() {
    var source = new SourceAddress("word/document.xml", List.of(0, 1), 0, 12);
    var body = new ParagraphModel(List.of(
        new RunModel("正文引用[\u200B1]。", "Times New Roman", "宋体", null, 24, false, "superscript", source)),
        480, null, null, 360, "auto", source, 200);
    var document = new ParsedDocument("source", List.of(chapterHeading(), body), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("CITE-01")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("CITATION_FORMAT_MISMATCH", result.reasonCode());
  }

  @Test
  void acceptsMultipleCitationsWithWordSpacingBeforeChineseSentencePunctuation() {
    var source = new SourceAddress("word/document.xml", List.of(0, 1), 0, 24);
    var body = new ParagraphModel(List.of(
        new RunModel("正文引用[1] [2] \u3002", "Times New Roman", "宋体", null, 24, false, "superscript", source)),
        480, null, null, 360, "auto", source, 200);
    var document = new ParsedDocument("source", List.of(chapterHeading(), body), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(r -> r.ruleId().equals("CITE-01")).findFirst().orElseThrow();

    assertEquals(Judgement.PASS, result.judgement());
  }

  @Test
  void requiresBodyCitationsToBeSuperscriptAndIgnoresReferenceListNumbers() {
    var bodySource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 20);
    var body = new ParagraphModel(List.of(
        new RunModel("这是一句正文[1]。", "Times New Roman", "宋体", null, 24, false, bodySource)),
        480, null, null, 360, "auto", bodySource, 200);
    var referenceHeading = paragraph("参考文献", List.of(0, 2));
    var referenceSource = new SourceAddress("word/document.xml", List.of(0, 3), 0, 20);
    var reference = new ParagraphModel(List.of(
        new RunModel("[1] 文献[M]. 出版社", "Times New Roman", "宋体", null, 24, false, referenceSource)),
        0, null, null, 360, "auto", referenceSource, 0);
    var document = new ParsedDocument("source", List.of(chapterHeading(), body, referenceHeading, reference),
        List.of(), List.of(), List.of(), List.of(),
        List.of(new ReferenceEntryModel(referenceSource, "1", "[1] 文献[M]. 出版社", "M", false)), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertEquals(1, results.stream().filter(r -> r.ruleId().equals("CITE-01")
        && r.judgement() == Judgement.FAIL).count());
    assertEquals(bodySource, results.stream().filter(r -> r.ruleId().equals("CITE-01")).findFirst().orElseThrow().source());
  }

  @Test
  void acceptsSuperscriptCitationSplitAcrossRuns() {
    var bodySource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 20);
    var citation = new ParagraphModel(List.of(
        new RunModel("正文", "Times New Roman", "宋体", null, 24, false, bodySource),
        new RunModel("[", "Times New Roman", "宋体", null, 24, false, "superscript", bodySource),
        new RunModel("1", "Times New Roman", "宋体", null, 24, false, "superscript", bodySource),
        new RunModel("]", "Times New Roman", "宋体", null, 24, false, "superscript", bodySource)),
        480, null, null, 360, "auto", bodySource, 200);
    var document = new ParsedDocument("source", List.of(chapterHeading(), citation), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(r -> r.ruleId().equals("CITE-01")).findFirst().orElseThrow();

    assertEquals(Judgement.PASS, result.judgement());
    assertEquals("小四号 Times New Roman / 上标", result.expected());
  }

  @Test
  void rejectsWhitespaceInsideCitationBrackets() {
    var source = new SourceAddress("word/document.xml", List.of(0, 1), 0, 20);
    var citation = new ParagraphModel(List.of(new RunModel("正文[ 1 ]。", "Times New Roman", "宋体",
        null, 24, false, "superscript", source)), 480, null, null, 360, "auto", source, 200);
    var document = new ParsedDocument("source", List.of(chapterHeading(), citation), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("CITE-01")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("CITATION_FORMAT_MISMATCH", result.reasonCode());
  }

  @Test
  void rejectsNonBreakingSpaceInsideCitationBrackets() {
    var source = new SourceAddress("word/document.xml", List.of(0, 1), 0, 20);
    var citation = new ParagraphModel(List.of(new RunModel("正文[\u16801]。", "Times New Roman", "宋体",
        null, 24, false, "superscript", source)), 480, null, null, 360, "auto", source, 200);
    var document = new ParsedDocument("source", List.of(chapterHeading(), citation), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("CITE-01")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("CITATION_FORMAT_MISMATCH", result.reasonCode());
  }

  @Test
  void rejectsTabAsChapterTitleSeparatorInsteadOfCountingItAsASpace() {
    var source = new SourceAddress("word/document.xml", List.of(0, 1), 0, 12);
    var heading = new ParagraphModel(List.of(new RunModel("第一章\t引言", "Times New Roman", "黑体",
        null, 32, true, source)), 0, null, null, null, null, source, 0);
    var document = new ParsedDocument("source", List.of(heading), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("HEAD-01-SPACING")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
  }

  @Test
  void failsMissingVariableExplanationForOpaqueOleEquation() {
    var equation = new cn.luwneshacha.thesis.document.EquationModel(
        new SourceAddress("word/document.xml", List.of(0, 1, 0), 0, 1),
        "OLE", "嵌入公式对象", "3-1", true, true);
    var equationParagraphSource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 5);
    var equationParagraph = new ParagraphModel(List.of(new RunModel("(3-1)", "Times New Roman", "宋体", null,
        20, false, equationParagraphSource)), 0, null, null, null, null, equationParagraphSource, 5);
    var document = new ParsedDocument("source", List.of(chapterHeading(), equationParagraph), List.of(), List.of(),
        List.of(), List.of(equation), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("EQ-07")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
  }

  @Test
  void doesNotAssumeExplanationCoversEveryVariableInPictureEquation() {
    var equation = new cn.luwneshacha.thesis.document.EquationModel(
        new SourceAddress("word/document.xml", List.of(0, 1, 0), 0, 1),
        "PICTURE", "图片公式（内部结构不可解析）", "3-1", true, true);
    var equationParagraphSource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 5);
    var explanationSource = new SourceAddress("word/document.xml", List.of(0, 2), 0, 16);
    var equationParagraph = new ParagraphModel(List.of(new RunModel("(3-1)", "Times New Roman", "宋体", null,
        20, false, equationParagraphSource)), 0, null, null, null, null, equationParagraphSource, 5);
    var explanation = new ParagraphModel(List.of(new RunModel("式中：x为长度", "Times New Roman", "宋体", null,
        24, false, explanationSource)), 480, null, null, 360, "auto", explanationSource, 200);
    var document = new ParsedDocument("source", List.of(chapterHeading(), equationParagraph, explanation),
        List.of(), List.of(), List.of(), List.of(equation), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("EQ-07")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
  }

  @Test
  void failsVariableCheckWhenEquationTextCannotBeRead() {
    var equation = new cn.luwneshacha.thesis.document.EquationModel(
        new SourceAddress("word/document.xml", List.of(0, 1, 0), 0, 1),
        "OMML", "", "3-1", true, false);
    var equationParagraphSource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 5);
    var equationParagraph = new ParagraphModel(List.of(new RunModel("(3-1)", "Times New Roman", "宋体", null,
        21, false, equationParagraphSource)), 0, null, null, null, null, equationParagraphSource, 5);
    var document = new ParsedDocument("source", List.of(chapterHeading(), equationParagraph), List.of(), List.of(),
        List.of(), List.of(equation), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("EQ-07")).findFirst().orElseThrow();

    assertEquals(Judgement.REVIEW, result.judgement());
    assertEquals("EQUATION_VARIABLES_UNREADABLE", result.reasonCode());
  }

  @Test
  void doesNotPassEquationWithUppercaseGreekVariableWithoutExplanation() {
    var equation = new cn.luwneshacha.thesis.document.EquationModel(
        new SourceAddress("word/document.xml", List.of(0, 1, 0), 0, 1),
        "OMML", "Δ", "3-1", true, false);
    var equationParagraphSource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 5);
    var equationParagraph = new ParagraphModel(List.of(new RunModel("(3-1)", "Times New Roman", "宋体", null,
        21, false, equationParagraphSource)), 0, null, null, null, null, equationParagraphSource, 0);
    var document = new ParsedDocument("source", List.of(chapterHeading(), equationParagraph), List.of(), List.of(),
        List.of(), List.of(equation), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("EQ-07")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
  }

  @Test
  void rejectsSuperscriptCitationWithTheWrongFontSize() {
    var source = new SourceAddress("word/document.xml", List.of(0, 1), 0, 20);
    var citation = new ParagraphModel(List.of(
        new RunModel("正文[1]。", "Times New Roman", "宋体", null, 21, false, "superscript", source)),
        480, null, null, 360, "auto", source, 200);
    var document = new ParsedDocument("source", List.of(chapterHeading(), citation), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(r -> r.ruleId().equals("CITE-01")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("CITATION_FORMAT_MISMATCH", result.reasonCode());
  }

  @Test
  void recognizesChapterHeadingWithoutSpaceAndReportsItsSpacingError() {
    var source = new SourceAddress("word/document.xml", List.of(0, 1), 0, 10);
    var heading = new ParagraphModel(List.of(new RunModel(
        "\u7b2c\u4e00\u7ae0\u5f15\u8a00", "Times New Roman", "黑体", null, 32, true, source)),
        0, null, null, null, null, source, 0);
    var body = new ParagraphModel(List.of(new RunModel(
        "\u8fd9\u662f\u6b63\u6587\u6bb5\u843d", "Times New Roman", "宋体", null, 24, false,
        new SourceAddress("word/document.xml", List.of(0, 2), 0, 7))),
        480, null, null, 360, "auto",
        new SourceAddress("word/document.xml", List.of(0, 2), 0, 7), 200);
    var document = new ParsedDocument("source", List.of(heading, body), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(r -> r.ruleId().equals("HEAD-01-SPACING")
        && r.reasonCode().equals("HEADING_NUMBER_SPACING_MISMATCH")
        && r.judgement() == Judgement.FAIL));
    assertTrue(results.stream().anyMatch(r -> r.ruleId().equals("BODY-INDENT")
        && r.source().elementPath().equals(List.of(0, 2))));
  }

  @Test
  void recognizesAndRejectsWhitespaceInsertedInsideChapterNumber() {
    var source = new SourceAddress("word/document.xml", List.of(0, 1), 0, 10);
    var heading = new ParagraphModel(List.of(new RunModel("第 二 章 引言", "Times New Roman", "黑体",
        null, 32, true, source)), 0, null, null, null, null, source, 0);
    var document = new ParsedDocument("source", List.of(heading), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("HEAD-01-SPACING"))
        .findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("HEADING_NUMBER_SPACING_MISMATCH", result.reasonCode());
  }

  @Test
  void failsEquationNumberUsingTenPointInsteadOfFiveSize() {
    var source = new SourceAddress("word/document.xml", List.of(0), 0, 5);
    var equation = new cn.luwneshacha.thesis.document.EquationModel(
        new SourceAddress("word/document.xml", List.of(0, 0), 0, 1), "OMML", "x", "3-1", true, false,
        java.util.Set.of("oMath"), java.util.Set.of(), null, source);
    var paragraph = new ParagraphModel(List.of(new RunModel("(3-1)", "Times New Roman", "宋体", null,
        20, false, source)), 0, null, null, null, null, source, 5);
    var document = new ParsedDocument("source", List.of(paragraph), List.of(), List.of(),
        List.of(), List.of(equation), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("EQ-02")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("EQUATION_NUMBER_FORMAT_MISMATCH", result.reasonCode());
  }

  @Test
  void rejectsNonCanonicalFontNameCaseForEquationNumber() {
    var source = new SourceAddress("word/document.xml", List.of(0), 0, 5);
    var equation = new cn.luwneshacha.thesis.document.EquationModel(
        new SourceAddress("word/document.xml", List.of(0, 0), 0, 1), "OMML", "x", "3-1", true, false,
        java.util.Set.of("oMath"), java.util.Set.of(), null, source);
    var paragraph = new ParagraphModel(List.of(new RunModel("(3-1)", "times new roman", "宋体", null,
        21, false, source)), 0, null, null, null, null, source, 5);
    var document = new ParsedDocument("source", List.of(paragraph), List.of(), List.of(),
        List.of(), List.of(equation), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("EQ-02")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("EQUATION_NUMBER_FORMAT_MISMATCH", result.reasonCode());
  }

  @Test
  void rejectsEquationNumberUsingFullWidthHyphen() {
    var source = new SourceAddress("word/document.xml", List.of(0, 1), 0, 5);
    var equation = new cn.luwneshacha.thesis.document.EquationModel(
        new SourceAddress("word/document.xml", List.of(0, 1, 0), 0, 1), "OMML", "x", "3－1", true, false);
    var paragraph = new ParagraphModel(List.of(new RunModel("(3－1)", "Times New Roman", "宋体", null,
        21, false, source)), 0, null, null, null, null, source, 0);
    var document = new ParsedDocument("source", List.of(chapterHeading(), paragraph), List.of(), List.of(),
        List.of(), List.of(equation), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.reasonCode().equals("EQUATION_NUMBER_PATTERN_MISMATCH"))
        .findFirst().orElseThrow();

    assertEquals("EQ-01", result.ruleId());
    assertEquals(Judgement.FAIL, result.judgement());
  }

  @Test
  void doesNotMatchFormulaCitationAfterChangingItsHyphenWidth() {
    var equationSource = new SourceAddress("word/document.xml", List.of(0, 2, 0), 0, 1);
    var equationParagraphSource = new SourceAddress("word/document.xml", List.of(0, 2), 0, 5);
    var citationSource = new SourceAddress("word/document.xml", List.of(0, 3), 0, 18);
    var equation = new cn.luwneshacha.thesis.document.EquationModel(
        equationSource, "OMML", "x", "3-1", true, false, java.util.Set.of("oMath"),
        java.util.Set.of(), null, equationParagraphSource);
    var equationParagraph = new ParagraphModel(List.of(new RunModel("(3-1)", "Times New Roman", "宋体", null,
        21, false, equationParagraphSource)), 0, null, null, null, null, equationParagraphSource, 0);
    var citation = new ParagraphModel(List.of(new RunModel("由公式（3－1）可得。", "Times New Roman", "宋体", null,
        24, false, citationSource)), 480, null, null, 360, "auto", citationSource, 200);
    var document = new ParsedDocument("source", List.of(chapterHeading(), equationParagraph, citation),
        List.of(), List.of(), List.of(), List.of(equation), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("EQ-04")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("EQUATION_NOT_REFERENCED", result.reasonCode());
  }

  @Test
  void doesNotAcceptEquationNumberPrefixAsAFormulaCitation() {
    var equationSource = new SourceAddress("word/document.xml", List.of(0, 2, 0), 0, 1);
    var equationParagraphSource = new SourceAddress("word/document.xml", List.of(0, 2), 0, 5);
    var citationSource = new SourceAddress("word/document.xml", List.of(0, 3), 0, 18);
    var equation = new cn.luwneshacha.thesis.document.EquationModel(
        equationSource, "OMML", "x", "3-1", true, false, java.util.Set.of("oMath"),
        java.util.Set.of(), null, equationParagraphSource);
    var equationParagraph = new ParagraphModel(List.of(new RunModel("(3-1)", "Times New Roman", "宋体", null,
        21, false, equationParagraphSource)), 0, null, null, null, null, equationParagraphSource, 0);
    var citation = paragraph("由式(3-1)a可知。", List.of(0, 3));
    var document = new ParsedDocument("source", List.of(chapterHeading(), equationParagraph, citation),
        List.of(), List.of(), List.of(), List.of(equation), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.reasonCode().equals("EQUATION_NOT_REFERENCED")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
  }

  @Test
  void checksAsciiDigitsAgainstRequiredLatinFont() {
    var source = new SourceAddress("word/document.xml", List.of(0), 0, 3);
    var paragraph = new ParagraphModel(List.of(new RunModel("123", "Arial", "宋体", null,
        24, false, source)), 480, null, null, 360, "auto", source, 200);
    var document = new ParsedDocument("source", List.of(chapterHeading(), paragraph("正文", List.of(0, 1)), paragraph),
        List.of(new SectionModel(1474, 1474, 1474, 1474, source)), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("BODY-FONT")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("FONT_MISMATCH", result.reasonCode());
  }

  @Test
  void acceptsWordLegacyAliasesForChineseFonts() {
    var source = new SourceAddress("word/document.xml", List.of(0), 0, 2);
    var run = new RunModel("正文", "Times New Roman", "SimSun", null,
        24, false, source);
    var paragraph = new ParagraphModel(List.of(run), 480, null, null, 360, "auto", source, 200);
    var document = new ParsedDocument("source", List.of(chapterHeading(), paragraph),
        List.of(new SectionModel(1474, 1474, 1474, 1474, source)), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("BODY-FONT")).findFirst().orElseThrow();

    assertEquals(Judgement.PASS, result.judgement());
  }

  @Test
  void checksHighAnsiCharactersAgainstTheirOwnFontSlot() {
    var source = new SourceAddress("word/document.xml", List.of(0, 2), 0, 2);
    var run = new RunModel("Aé", "Times New Roman", "Arial", "宋体", null,
        24, false, null, source);
    var paragraph = new ParagraphModel(List.of(run), 480, null, null, 360, "auto", source, 200);
    var document = new ParsedDocument("source", List.of(chapterHeading(), paragraph),
        List.of(new SectionModel(1474, 1474, 1474, 1474, source)), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("BODY-FONT")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("FONT_MISMATCH", result.reasonCode());
    assertTrue(result.actual().contains("hAnsi=Arial"), result.actual());
  }

  @Test
  void checksGreekLettersAgainstTheirHighAnsiFontSlot() {
    var source = new SourceAddress("word/document.xml", List.of(0, 2), 0, 2);
    var run = new RunModel("αβ", "Times New Roman", "Arial", "宋体", null,
        24, false, null, source);
    var paragraph = new ParagraphModel(List.of(run), 480, null, null, 360, "auto", source, 200);
    var document = new ParsedDocument("source", List.of(chapterHeading(), paragraph),
        List.of(new SectionModel(1474, 1474, 1474, 1474, source)), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("BODY-FONT")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("FONT_MISMATCH", result.reasonCode());
    assertTrue(result.actual().contains("hAnsi=Arial"), result.actual());
  }

  @Test
  void checksArabicLettersAgainstTheirComplexScriptFontSlot() {
    var source = new SourceAddress("word/document.xml", List.of(0, 2), 0, 6);
    var run = new RunModel("العربية", "Times New Roman", "Times New Roman", "宋体", "Arial",
        24, false, null, source);
    var paragraph = new ParagraphModel(List.of(run), 480, null, null, 360, "auto", source, 200);
    var document = new ParsedDocument("source", List.of(chapterHeading(), paragraph),
        List.of(new SectionModel(1474, 1474, 1474, 1474, source)), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("BODY-FONT")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("FONT_MISMATCH", result.reasonCode());
    assertTrue(result.actual().contains("cs=Arial"), result.actual());
  }

  @Test
  void requiresReferencesToMatchFirstAppearanceOrderAndBeCited() {
    var citationSource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 30);
    var citation = new ParagraphModel(List.of(
        new RunModel("正文引用[2,1]。", "Times New Roman", "宋体", null, 24, false, citationSource)),
        480, null, null, 360, "auto", citationSource, 200);
    var firstReferenceSource = new SourceAddress("word/document.xml", List.of(0, 3), 0, 15);
    var secondReferenceSource = new SourceAddress("word/document.xml", List.of(0, 4), 0, 15);
    var unusedReferenceSource = new SourceAddress("word/document.xml", List.of(0, 5), 0, 15);
    var document = new ParsedDocument("source", List.of(chapterHeading(), citation), List.of(), List.of(),
        List.of(), List.of(), List.of(
            new ReferenceEntryModel(firstReferenceSource, "1", "[1] A[M].", "M", false),
            new ReferenceEntryModel(secondReferenceSource, "2", "[2] B[J].", "J", false),
            new ReferenceEntryModel(unusedReferenceSource, "3", "[3] C[J].", "J", false)), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(r -> r.ruleId().equals("REF-03") && r.judgement() == Judgement.FAIL).toList();

    assertEquals(List.of(firstReferenceSource, secondReferenceSource, unusedReferenceSource),
        results.stream().map(r -> r.source()).toList());
  }

  @Test
  void doesNotShiftCitationOrderWhenAnUnnumberedReferenceIsPresent() {
    var citationSource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 20);
    var citation = new ParagraphModel(List.of(
        new RunModel("正文引用[1,2]。", "Times New Roman", "宋体", null, 24, false, "superscript", citationSource)),
        480, null, null, 360, "auto", citationSource, 200);
    var references = List.of(
        new ReferenceEntryModel(new SourceAddress("word/document.xml", List.of(0, 3), 0, 12),
            null, "无编号文献[M].", "M", false),
        new ReferenceEntryModel(new SourceAddress("word/document.xml", List.of(0, 4), 0, 12),
            "1", "[1] A[M].", "M", false),
        new ReferenceEntryModel(new SourceAddress("word/document.xml", List.of(0, 5), 0, 12),
            "2", "[2] B[J].", "J", false));
    var document = new ParsedDocument("source", List.of(chapterHeading(), citation), List.of(), List.of(),
        List.of(), List.of(), references, List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(result -> result.ruleId().equals("REF-07")
        && result.reasonCode().equals("REFERENCE_NUMBER_INVALID")));
    assertTrue(results.stream().noneMatch(result -> result.reasonCode().equals("REFERENCE_ORDER_MISMATCH")));
  }

  @Test
  void rejectsUnsupportedCitationSeparatorsWithoutCascadingUncitedReferenceErrors() {
    var citationSource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 20);
    var citation = new ParagraphModel(List.of(
        new RunModel("Text [1;2].", "Times New Roman", "宋体", null, 24, false, citationSource)),
        480, null, null, 360, "auto", citationSource, 200);
    var firstReference = new ReferenceEntryModel(new SourceAddress("word/document.xml", List.of(0, 3), 0, 10),
        "1", "[1] A[M].", "M", false);
    var secondReference = new ReferenceEntryModel(new SourceAddress("word/document.xml", List.of(0, 4), 0, 10),
        "2", "[2] B[J].", "J", false);
    var document = new ParsedDocument("source", List.of(chapterHeading(), citation), List.of(), List.of(),
        List.of(), List.of(), List.of(firstReference, secondReference), List.of());

    var referenceResults = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(r -> r.ruleId().equals("REF-03")).toList();

    assertEquals(1, referenceResults.size());
    assertEquals(Judgement.FAIL, referenceResults.get(0).judgement());
    assertEquals("CITATION_RANGE_MISMATCH", referenceResults.get(0).reasonCode());
  }

  @Test
  void reportsIndependentUncitedEntriesAlongsideMalformedCitationGroups() {
    var validSource = new SourceAddress("word/document.xml", List.of(0, 2), 0, 8);
    var malformedCitation = paragraph("Text [1;2].", List.of(0, 1));
    var validCitation = new ParagraphModel(List.of(
        new RunModel("Text [1].", "Times New Roman", "宋体", null, 24, false, "superscript", validSource)),
        480, null, null, 360, "auto", validSource, 200);
    var firstReference = new ReferenceEntryModel(new SourceAddress("word/document.xml", List.of(0, 3), 0, 10),
        "1", "[1] A[M].", "M", false);
    var secondReference = new ReferenceEntryModel(new SourceAddress("word/document.xml", List.of(0, 4), 0, 10),
        "2", "[2] B[J].", "J", false);
    var document = new ParsedDocument("source", List.of(chapterHeading(), malformedCitation, validCitation),
        List.of(), List.of(), List.of(), List.of(), List.of(firstReference, secondReference), List.of());

    var referenceResults = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(r -> r.ruleId().equals("REF-03") && r.judgement() == Judgement.FAIL).toList();

    assertTrue(referenceResults.stream().anyMatch(r -> r.reasonCode().equals("CITATION_RANGE_MISMATCH")),
        referenceResults.toString());
    assertTrue(referenceResults.stream().anyMatch(r -> r.reasonCode().equals("REFERENCE_NOT_CITED")
        && "2".equals(r.actual())));
  }

  @Test
  void acceptsRightAlignedContinuationCaptionAndValidContinuationBorders() {
    var source = new SourceAddress("word/document.xml", List.of(0, 5), 0, 5);
    var caption = new ParagraphModel(List.of(new RunModel("续表4-1", "Times New Roman", "宋体", null,
        21, false, source)), null, null, null, null, null, "auto", source, null, null, "right");
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(0, 6), 0, 0),
        source, "续表4-1", 3, 5, true, true, true, false, true, false);
    var document = new ParsedDocument("source", List.of(chapterHeading(), caption), List.of(), List.of(),
        List.of(table), List.of(), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("TAB-03")
        && item.judgement() == Judgement.PASS && "right".equals(item.expected())), results.toString());
    assertTrue(results.stream().noneMatch(item -> item.ruleId().equals("TAB-02")
        || item.reasonCode().equals("TABLE_CAPTION_NOT_CENTERED")
        || item.reasonCode().equals("TABLE_CONTINUATION_CAPTION_NOT_RIGHT")), results.toString());
  }

  @Test
  void reportsMissingBlankLineWhenReferenceTitleIsLastParagraph() {
    var heading = paragraph("参考文献", List.of(0, 1));
    var document = new ParsedDocument("source", List.of(chapterHeading(), heading), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("REF-01-BLANK"))
        .findFirst().orElseThrow();

    assertEquals(Judgement.REVIEW, result.judgement());
    assertEquals("REFERENCE_TITLE_BLANK_LINE_MISSING", result.reasonCode());
  }

  @Test
  void rejectsLeftAlignedContinuationCaptionButKeepsRegularCaptionCentered() {
    var continuationSource = new SourceAddress("word/document.xml", List.of(0, 5), 0, 5);
    var continuationCaption = new ParagraphModel(List.of(new RunModel("续表4-1", "Times New Roman", "宋体", null,
        21, false, continuationSource)), null, null, null, null, null, "auto", continuationSource, null, null, "left");
    var continuationTable = new TableModel(new SourceAddress("word/document.xml", List.of(0, 6), 0, 0),
        continuationSource, "续表4-1", 3, 5, true, true, true, false, true, false);
    var continuationDocument = new ParsedDocument("source", List.of(chapterHeading(), continuationCaption), List.of(), List.of(),
        List.of(continuationTable), List.of(), List.of(), List.of());

    var continuationResult = new RuleEngine().check(continuationDocument, RuleSet.schoolV1()).stream()
        .filter(item -> item.reasonCode().equals("TABLE_CONTINUATION_CAPTION_NOT_RIGHT"))
        .findFirst().orElseThrow();
    assertEquals("right", continuationResult.expected());

    var regularSource = new SourceAddress("word/document.xml", List.of(0, 5), 0, 10);
    var regularCaption = new ParagraphModel(List.of(new RunModel("表4-1 测试表", "Times New Roman", "宋体", null,
        21, false, regularSource)), null, null, null, null, null, "auto", regularSource, null, null, "center");
    var regularTable = new TableModel(new SourceAddress("word/document.xml", List.of(0, 6), 0, 0),
        regularSource, "表4-1 测试表", 3, 5, true, true, true, false, true, false);
    var regularDocument = new ParsedDocument("source", List.of(chapterHeading(), regularCaption), List.of(), List.of(),
        List.of(regularTable), List.of(), List.of(), List.of());
    assertTrue(new RuleEngine().check(regularDocument, RuleSet.schoolV1()).stream()
        .noneMatch(item -> item.reasonCode().equals("TABLE_CAPTION_NOT_CENTERED")));
  }

  @Test
  void rejectsContinuationCaptionWithTextAfterItsNumber() {
    for (String captionText : List.of("续表4-1 补充标题", "续表 4-1", " 续表4-1")) {
      var source = new SourceAddress("word/document.xml", List.of(0, 5), 0, captionText.length());
      var caption = new ParagraphModel(List.of(new RunModel(captionText, "Times New Roman", "宋体", null,
          21, false, source)), 0, null, null, null, "right", source, null);
      var table = new TableModel(new SourceAddress("word/document.xml", List.of(0, 6), 0, 0),
          source, captionText, 3, 5, true, true, true, false, true, false);
      var document = new ParsedDocument("source", List.of(caption), List.of(), List.of(),
          List.of(table), List.of(), List.of(), List.of());

      var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
          .filter(item -> item.reasonCode().equals("TABLE_CONTINUATION_NUMBER_FORMAT"))
          .findFirst().orElseThrow(() -> new AssertionError("Accepted malformed continuation: " + captionText));

      assertEquals(Judgement.FAIL, result.judgement());
    }
  }

  @Test
  void acceptsAppendixContinuationNumberWithoutAdditionalCaptionText() {
    var source = new SourceAddress("word/document.xml", List.of(0, 5), 0, 4);
    var caption = new ParagraphModel(List.of(new RunModel("续表A1", "Times New Roman", "宋体", null,
        21, false, source)), 0, null, null, null, "right", source, null);
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(0, 6), 0, 0),
        source, "续表A1", 3, 5, true, true, true, false, true, false);
    var document = new ParsedDocument("source", List.of(caption), List.of(), List.of(),
        List.of(table), List.of(), List.of(), List.of());

    assertTrue(new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .noneMatch(item -> item.reasonCode().equals("TABLE_CONTINUATION_NUMBER_FORMAT")));
  }

  @Test
  void recognizesSlashUnitsWrittenOnTheSecondHeaderLine() {
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(0, 6), 0, 0),
        ADDRESS, "表4-1 测试结果", 3, 5, true, true, true, false, true, false,
        false, List.of(), List.of(), List.of("功率密度/W·cm²", "辐照时间/s", "显微硬度/HV", "硬化层深/mm"),
        null, null, false);
    var document = new ParsedDocument("source", List.of(), List.of(), List.of(),
        List.of(table), List.of(), List.of(), List.of());

    assertTrue(new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .noneMatch(item -> item.ruleId().equals("TAB-04")));
  }

  @Test
  void requiresAtLeastTenReferenceEntries() {
    var references = new java.util.ArrayList<ReferenceEntryModel>();
    for (int number = 1; number <= 9; number++) {
      references.add(new ReferenceEntryModel(
          new SourceAddress("word/document.xml", List.of(0, 100 + number), 0, 8),
          String.valueOf(number), "[" + number + "] Example[J].", "J", false));
    }
    var document = new ParsedDocument("source", List.of(chapterHeading()), List.of(), List.of(),
        List.of(), List.of(), references, List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("REF-05")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("REFERENCE_COUNT_TOO_LOW", result.reasonCode());
  }

  @Test
  void reportsMissingReferenceListWhenThereAreNoEntries() {
    var document = new ParsedDocument("source", List.of(chapterHeading()), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("REF-05")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("0", result.actual());
    assertEquals("REFERENCE_COUNT_TOO_LOW", result.reasonCode());
  }

  @Test
  void doesNotApplyAcknowledgementSpacingRuleToAppendixTitle() {
    var document = new ParsedDocument("source", List.of(
        chapterHeading(), paragraph("附录", List.of(0, 200))), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().noneMatch(item -> item.reasonCode() != null
        && item.reasonCode().startsWith("ACKNOWLEDGEMENT_TITLE_")));
  }

  @Test
  void expandsNumberRangesWhenCheckingReferenceOrder() {
    var citationSource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 20);
    var citation = new ParagraphModel(List.of(
        new RunModel("Text [1-2].", "Times New Roman", "宋体", null, 24, false, citationSource)),
        480, null, null, 360, "auto", citationSource, 200);
    var firstReference = new ReferenceEntryModel(new SourceAddress("word/document.xml", List.of(0, 3), 0, 10),
        "1", "[1] A[M].", "M", false);
    var secondReference = new ReferenceEntryModel(new SourceAddress("word/document.xml", List.of(0, 4), 0, 10),
        "2", "[2] B[J].", "J", false);
    var document = new ParsedDocument("source", List.of(chapterHeading(), citation), List.of(), List.of(),
        List.of(), List.of(), List.of(firstReference, secondReference), List.of());

    var referenceProblems = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(r -> r.ruleId().equals("REF-03") && r.judgement() != Judgement.PASS).toList();

    assertTrue(referenceProblems.isEmpty());
  }

  @Test
  void acceptsCommaSeparatedReferencesInFirstAppearanceOrder() {
    var source = new SourceAddress("word/document.xml", List.of(0, 1), 0, 20);
    var citation = new ParagraphModel(List.of(
        new RunModel("Text [1,2].", "Times New Roman", "宋体", null, 24, false, source)),
        480, null, null, 360, "auto", source, 200);
    var firstReference = new ReferenceEntryModel(
        new SourceAddress("word/document.xml", List.of(0, 3), 0, 10), "1", "[1] A[M].", "M", false);
    var secondReference = new ReferenceEntryModel(
        new SourceAddress("word/document.xml", List.of(0, 4), 0, 10), "2", "[2] B[J].", "J", false);
    var document = new ParsedDocument("source", List.of(chapterHeading(), citation), List.of(), List.of(),
        List.of(), List.of(), List.of(firstReference, secondReference), List.of());

    assertTrue(new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .noneMatch(result -> result.ruleId().equals("REF-03") && result.judgement() != Judgement.PASS));
  }

  @Test
  void doesNotReadArbitraryBracketedTextAsAReferenceCitation() {
    var source = new SourceAddress("word/document.xml", List.of(0, 1), 0, 20);
    var body = new ParagraphModel(List.of(
        new RunModel("正文中的标记[abc]不代表文献编号。", "Times New Roman", "宋体", null, 24, false, source)),
        480, null, null, 360, "auto", source, 200);
    var document = new ParsedDocument("source", List.of(chapterHeading(), body), List.of(), List.of());

    assertTrue(new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .noneMatch(result -> result.ruleId().equals("REF-03")));
  }

  @Test
  void checksPageNumberFooterEvidence() {
    var page = new PageNumberModel(new SourceAddress("word/footer1.xml", List.of(), 0, 0), true,
        false, "Arial", 22);
    var document = new ParsedDocument("source", List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(), List.of(page));

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    var scope = results.stream().filter(r -> r.reasonCode().equals("PAGE_NUMBER_SCOPE_UNKNOWN"))
        .findFirst().orElseThrow();
    assertEquals(Judgement.REVIEW, scope.judgement());
    assertTrue(results.stream().noneMatch(r -> r.ruleId().equals("PAGE-05")), results.toString());
  }

  @Test
  void ignoresRomanNumberingBeforeTheMainBodyStarts() {
    var abstractHeading = paragraph("摘要", List.of(0, 0));
    var chapterHeading = paragraph("第一章 绪论", List.of(0, 1));
    var abstractSection = new SectionModel(1474, 1474, 1474, 1474,
        abstractHeading.source(), "upperRoman", 1);
    var bodySection = new SectionModel(1474, 1474, 1474, 1474,
        chapterHeading.source(), "decimal", null);
    var bodyPage = new PageNumberModel(new SourceAddress("word/footer1.xml", List.of(), 0, 0), true,
        true, "Times New Roman", 21, List.of(1));
    var document = new ParsedDocument("source", List.of(abstractHeading, chapterHeading),
        List.of(abstractSection, bodySection), List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of(bodyPage));

    assertTrue(new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .noneMatch(result -> result.ruleId().equals("PAGE-04")));
  }

  @Test
  void rejectsRomanNumberingInTheMainBodySection() {
    var chapterHeading = chapterHeading();
    var bodySection = new SectionModel(1474, 1474, 1474, 1474,
        chapterHeading.source(), "upperRoman", 1);
    var document = new ParsedDocument("source", List.of(chapterHeading), List.of(bodySection), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("PAGE-04")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("PAGE_NUMBER_FORMAT_MISMATCH", result.reasonCode());
  }

  @Test
  void failsWhenMainBodySectionHasNoPageNumberField() {
    var heading = chapterHeading();
    var body = new ParagraphModel(List.of(new RunModel("正文内容", "Times New Roman", "宋体", null,
        24, false, new SourceAddress("word/document.xml", List.of(0, 1), 0, 4))),
        480, null, null, null, null, new SourceAddress("word/document.xml", List.of(0, 1), 0, 4), 200);
    var section = new SectionModel(1474, 1474, 1474, 1474, heading.source(), "decimal", null);
    var document = new ParsedDocument("source", List.of(heading, body), List.of(section), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.reasonCode().equals("PAGE_NUMBER_MISSING_IN_MAIN_BODY"))
        .findFirst().orElseThrow();

    assertEquals("PAGE-04", result.ruleId());
    assertEquals(Judgement.FAIL, result.judgement());
  }

  @Test
  void ignoresCoverInformationTablesBeforeTheMainBodyStarts() {
    var cover = paragraph("学生姓名：张三", List.of(0, 0));
    var chapter = chapterHeading();
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(0, 0), 0, 0),
        null, null, 3, 2, false, false, false, false, false, false);
    var document = new ParsedDocument("source", List.of(cover, chapter), List.of(), List.of(),
        List.of(table), List.of(), List.of(), List.of(), List.of());

    assertTrue(new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .noneMatch(result -> result.reasonCode().equals("TABLE_CAPTION_MISSING")
            || result.reasonCode().equals("TABLE_CAPTION_BELOW")
            || result.reasonCode().equals("TABLE_NOT_CITED_IN_BODY")));
  }

  @Test
  void failsWhenAnyLaterMainBodySectionHasNoPageNumberField() {
    var heading = chapterHeading();
    var body = paragraph("正文内容", List.of(0, 1));
    var first = new SectionModel(1474, 1474, 1474, 1474, heading.source(), "decimal", null);
    var later = new SectionModel(1474, 1474, 1474, 1474, body.source(), "decimal", null);
    var document = new ParsedDocument("source", List.of(heading, body), List.of(first, later), List.of());

    var findings = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.reasonCode().equals("PAGE_NUMBER_MISSING_IN_MAIN_BODY")).toList();

    assertEquals(2, findings.size());
    assertTrue(findings.stream().allMatch(item -> item.ruleId().equals("PAGE-04")));
  }

  @Test
  void checksTerminalSectionPropertiesForMissingMainBodyPageNumbers() {
    var heading = chapterHeading();
    var body = paragraph("姝ｆ枃鍐呭", List.of(0, 1));
    var contentSection = new SectionModel(1474, 1474, 1474, 1474,
        heading.source(), "decimal", null);
    var terminalSection = new SectionModel(1474, 1474, 1474, 1474,
        new SourceAddress("word/document.xml", List.of(0, 2), 0, 0), "decimal", null, true);
    var bodyPage = new PageNumberModel(new SourceAddress("word/footer1.xml", List.of(), 0, 0), true,
        true, "Times New Roman", 21, List.of(0));
    var document = new ParsedDocument("source", List.of(heading, body),
        List.of(contentSection, terminalSection), List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of(bodyPage));

    assertTrue(new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .anyMatch(result -> result.reasonCode().equals("PAGE_NUMBER_MISSING_IN_MAIN_BODY")
            && result.source().equals(terminalSection.source())));
  }

  @Test
  void findsMainBodySectionAfterContentsChapterEntries() {
    var contents = paragraph("鐩綍", List.of(0, 0));
    var contentsChapter = paragraph("绗竴绔?寮曡█\t1", List.of(0, 1));
    var actualChapter = paragraph("绗竴绔?寮曡█", List.of(0, 4));
    var contentsSection = new SectionModel(1474, 1474, 1474, 1474,
        new SourceAddress("word/document.xml", List.of(0, 2), 0, 0), "upperRoman", 1);
    var mainBodySection = new SectionModel(1474, 1474, 1474, 1474,
        new SourceAddress("word/document.xml", List.of(0, 5), 0, 0), "decimal", null);
    var page = new PageNumberModel(new SourceAddress("word/footer2.xml", List.of(), 0, 0), true,
        true, "Times New Roman", 21, List.of(1));
    var document = new ParsedDocument("source", List.of(contents, contentsChapter, actualChapter),
        List.of(contentsSection, mainBodySection), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(page));

    var findings = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.reasonCode().equals("PAGE_NUMBER_MISSING_IN_MAIN_BODY")).toList();

    assertTrue(findings.isEmpty());
  }

  @Test
  void rejectsNonCanonicalCaseForPageNumberFormat() {
    var heading = chapterHeading();
    var section = new SectionModel(1474, 1474, 1474, 1474, heading.source(), "DECIMAL", null);
    var document = new ParsedDocument("source", List.of(heading), List.of(section), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("PAGE-04")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("PAGE_NUMBER_FORMAT_MISMATCH", result.reasonCode());
  }

  @Test
  void checksPageNumberStyleOnlyForMainBodySections() {
    var abstractHeading = paragraph("摘要", List.of(0, 0));
    var chapterHeading = paragraph("第一章 绪论", List.of(0, 1));
    var abstractSection = new SectionModel(1474, 1474, 1474, 1474,
        abstractHeading.source(), "upperRoman", 1);
    var bodySection = new SectionModel(1474, 1474, 1474, 1474,
        chapterHeading.source(), "decimal", null);
    var frontFooterPage = new PageNumberModel(
        new SourceAddress("word/footer1.xml", List.of(0), 0, 0), true, false, "Arial", 22, List.of(0));
    var bodyFooterPage = new PageNumberModel(
        new SourceAddress("word/footer2.xml", List.of(0), 0, 0), true, false, "Arial", 22, List.of(1));
    var document = new ParsedDocument("source", List.of(abstractHeading, chapterHeading),
        List.of(abstractSection, bodySection), List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of(frontFooterPage, bodyFooterPage));

    var results = new RuleEngine().check(document, RuleSet.schoolV1());
    var pageStyleFindings = results.stream().filter(result -> result.ruleId().equals("PAGE-05")).toList();
    var frontMatterFinding = results.stream().filter(result -> result.reasonCode()
        .equals("PAGE_NUMBER_IN_FRONT_MATTER")).findFirst().orElseThrow();

    assertEquals(3, pageStyleFindings.size());
    assertTrue(pageStyleFindings.stream().allMatch(result -> result.source().partName().equals("word/document.xml")));
    assertTrue(pageStyleFindings.stream().allMatch(result -> result.source().elementPath()
        .equals(chapterHeading.source().elementPath())));
    assertEquals(abstractSection.source(), frontMatterFinding.source());
  }

  @Test
  void doesNotTreatChapterEntryInsideAContentsTableAsTheMainBodyStart() {
    var abstractHeading = paragraph("摘要", List.of(0, 0));
    var contentsChapter = paragraph("第一章 绪论", List.of(0, 1, 0));
    var chapterHeading = paragraph("第一章 绪论", List.of(0, 2));
    var contentsTable = new TableModel(new SourceAddress("word/document.xml", List.of(0, 1), 0, 0),
        null, null, 1, 1, false, false, false, false, true, false);
    var abstractSection = new SectionModel(1474, 1474, 1474, 1474,
        contentsChapter.source(), "upperRoman", 1);
    var bodySection = new SectionModel(1474, 1474, 1474, 1474,
        chapterHeading.source(), "decimal", null);
    var bodyPage = new PageNumberModel(new SourceAddress("word/footer1.xml", List.of(), 0, 0), true,
        true, "Times New Roman", 21, List.of(1));
    var document = new ParsedDocument("source", List.of(abstractHeading, contentsChapter, chapterHeading),
        List.of(abstractSection, bodySection), List.of(), List.of(contentsTable), List.of(), List.of(),
        List.of(), List.of(bodyPage));

    assertTrue(new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .noneMatch(result -> result.ruleId().equals("PAGE-04")));
  }

  @Test
  void rejectsAnExplicitPageNumberRestartAfterTheMainBodyHasStarted() {
    var chapterHeading = chapterHeading();
    var bodyParagraph = paragraph("正文内容", List.of(0, 1));
    var firstSection = new SectionModel(1474, 1474, 1474, 1474,
        chapterHeading.source(), "decimal", 1);
    var restartedSection = new SectionModel(1474, 1474, 1474, 1474,
        bodyParagraph.source(), "decimal", 1);
    var document = new ParsedDocument("source", List.of(chapterHeading, bodyParagraph),
        List.of(firstSection, restartedSection), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.reasonCode().equals("PAGE_NUMBER_SEQUENCE_RESTARTED"))
        .findFirst().orElseThrow();

    assertEquals("PAGE-04", result.ruleId());
    assertEquals(Judgement.FAIL, result.judgement());
  }

  @Test
  void reviewsLaterPageNumberStartThatCannotBeComparedWithoutRenderedPageCount() {
    var chapterHeading = chapterHeading();
    var bodyParagraph = paragraph("姝ｆ枃鍐呭", List.of(0, 1));
    var firstSection = new SectionModel(1474, 1474, 1474, 1474,
        chapterHeading.source(), "decimal", null);
    var laterSection = new SectionModel(1474, 1474, 1474, 1474,
        bodyParagraph.source(), "decimal", 2);
    var document = new ParsedDocument("source", List.of(chapterHeading, bodyParagraph),
        List.of(firstSection, laterSection), List.of());

    var finding = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.reasonCode().equals("PAGE_NUMBER_SEQUENCE_CONTINUITY_UNCONFIRMED"))
        .findFirst().orElseThrow();

    assertTrue(finding.actual().startsWith("起始值 2"), finding.actual());
    assertEquals(Judgement.REVIEW, finding.judgement());
  }

  @Test
  void rejectsFirstMainBodySectionStartingAtPageNumberOtherThanOne() {
    var heading = chapterHeading();
    var section = new SectionModel(1474, 1474, 1474, 1474, heading.source(), "decimal", 2);
    var document = new ParsedDocument("source", List.of(heading), List.of(section), List.of());

    var finding = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.reasonCode().equals("PAGE_NUMBER_START_MISMATCH"))
        .findFirst().orElseThrow();

    assertEquals("1", finding.expected());
    assertEquals("2", finding.actual());
    assertEquals(Judgement.FAIL, finding.judgement());
  }

  @Test
  void rejectsLaterChapterWithoutAnExplicitPageBreak() {
    var firstChapter = new HeadingBreakModel(ADDRESS, "第一章 绪论", false);
    var chapter = new HeadingBreakModel(ADDRESS, "第二章 测试", false);
    var document = new ParsedDocument("source", List.<ParagraphModel>of(), List.<SectionModel>of(), List.of(),
        List.<TableModel>of(), List.<cn.luwneshacha.thesis.document.EquationModel>of(),
        List.<ReferenceEntryModel>of(), List.<ImageModel>of(), List.<PageNumberModel>of(), List.of(firstChapter, chapter));

    var results = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(r -> r.ruleId().equals("PAGE-03")).toList();

    assertEquals(1, results.size());
    assertEquals("CHAPTER_PAGE_BREAK_REVIEW", results.get(0).reasonCode());
    assertEquals(Judgement.REVIEW, results.get(0).judgement());
    assertEquals(chapter.source(), results.get(0).source());
  }

  @Test
  void acceptsLaterChapterWithAnExplicitPageBreak() {
    var chapter = new HeadingBreakModel(ADDRESS, "第二章 测试", true);
    var document = new ParsedDocument("source", List.<ParagraphModel>of(), List.<SectionModel>of(), List.of(),
        List.<TableModel>of(), List.<cn.luwneshacha.thesis.document.EquationModel>of(),
        List.<ReferenceEntryModel>of(), List.<ImageModel>of(), List.<PageNumberModel>of(), List.of(chapter));

    assertTrue(new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .noneMatch(result -> result.reasonCode().equals("CHAPTER_PAGE_BREAK_MISSING")));
  }
}
