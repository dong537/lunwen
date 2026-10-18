package cn.luwneshacha.thesis.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.Docx4jDocumentParser;
import cn.luwneshacha.thesis.document.DocxReader;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.RunModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import cn.luwneshacha.thesis.document.TableModel;
import cn.luwneshacha.thesis.inspection.RuleCatalog;
import java.util.List;
import java.nio.file.Path;
import org.docx4j.XmlUtils;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContentsRuleCheckerTest {
  private static final SourceAddress TITLE_SOURCE = source(0);

  @Test
  void reportsContentsEntryWithoutMatchingBodyHeading() {
    var document = document(
        paragraph("目    录", 0, "黑体", 32, "center"),
        paragraph("", 1, "宋体", 24, "left"),
        paragraph("第一章  引言……1", 2, "宋体", 24, "left"),
        paragraph("第一章 绪论", 3, "黑体", 32, "left"));

    var finding = new ContentsRuleChecker(new ParagraphFormatChecker()).check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.ruleId().equals("TOC-02"))
        .findFirst().orElseThrow();

    assertEquals("TOC_ENTRY_NOT_FOUND_IN_BODY", finding.reasonCode());
    assertEquals(source(2), finding.source());
  }

  @Test
  void reportsBodyHeadingThatIsMissingFromContents() {
    var document = document(
        paragraph("目    录", 0, "黑体", 32, "center"),
        paragraph("", 1, "宋体", 24, "left"),
        paragraph("第一章  引言……1", 2, "宋体", 24, "left"),
        paragraph("第一章 引言", 3, "黑体", 32, "left"),
        paragraph("1.1 研究背景", 4, "黑体", 28, "left"));

    var finding = new ContentsRuleChecker(new ParagraphFormatChecker()).check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.ruleId().equals("TOC-02"))
        .findFirst().orElseThrow();

    assertEquals("BODY_HEADING_NOT_IN_TOC", finding.reasonCode());
    assertEquals(source(4), finding.source());
  }

  @Test
  void doesNotTreatChapterDescriptionsOrDecimalTableValuesAsHeadingsAndMatchesSummary() {
    var document = document(
        paragraph("目    录", 0, "黑体", 32, "center"),
        paragraph("", 1, "宋体", 24, "left"),
        paragraph("第一章 绪论……1", 2, "宋体", 24, "left"),
        paragraph("总结……48", 3, "宋体", 24, "left"),
        paragraph("第一章  绪论", 4, "黑体", 32, "left"),
        paragraph("第一章是绪论，主要讲述研究背景及意义。", 5, "宋体", 24, "left"),
        paragraph("25.1℃~25.5℃", List.of(0, 556, 3, 4, 1)),
        paragraph("2.1 测试用例", List.of(0, 556, 4, 4, 1)),
        paragraph("总    结", 7, "黑体", 32, "left"));
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(0, 556), 0, 0),
        null, null, 2, 1, false, false, false, false, true, false);
    document = new ParsedDocument("source", document.paragraphs(), List.of(), List.of(),
        List.of(table), List.of(), List.of(), List.of());

    var findings = new ContentsRuleChecker(new ParagraphFormatChecker()).check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.ruleId().equals("TOC-02"))
        .toList();

    assertTrue(findings.isEmpty(), findings.toString());
  }

  @Test
  void reportsContentsOrderMismatchWhenAllTitlesExistInWrongOrder() {
    var document = document(
        paragraph("目    录", 0, "黑体", 32, "center"),
        paragraph("", 1, "宋体", 24, "left"),
        paragraph("第一章  引言……1", 2, "宋体", 24, "left"),
        paragraph("第二章  方法……2", 3, "宋体", 24, "left"),
        paragraph("第一章 引言", 4, "黑体", 32, "left"),
        paragraph("第二章 方法", 5, "黑体", 32, "left"));

    // Reorder only the body headings to make the relationship check observable.
    var reordered = document(
        paragraph("目    录", 0, "黑体", 32, "center"),
        paragraph("", 1, "宋体", 24, "left"),
        paragraph("第一章  引言……1", 2, "宋体", 24, "left"),
        paragraph("第二章  方法……2", 3, "宋体", 24, "left"),
        paragraph("第二章 方法", 6, "黑体", 32, "left"),
        paragraph("第一章 引言", 7, "黑体", 32, "left"));

    assertTrue(new ContentsRuleChecker(new ParagraphFormatChecker()).check(reordered, RuleSet.schoolV1()).stream()
        .anyMatch(result -> result.ruleId().equals("TOC-02-ORDER")
            && result.reasonCode().equals("TOC_BODY_ORDER_MISMATCH")));
    assertTrue(new ContentsRuleChecker(new ParagraphFormatChecker()).check(document, RuleSet.schoolV1()).stream()
        .noneMatch(result -> result.ruleId().equals("TOC-02-ORDER")));
  }

  @Test
  void emitsOneSetOfTitleFindingsForContentsTitle() {
    var document = document(
        paragraph("目    录", 0, "黑体", 32, "center"),
        paragraph("", 1, "宋体", 24, "left"));

    var findings = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.source().equals(TITLE_SOURCE)
            && result.ruleId().startsWith("TOC-01-"))
        .toList();

    assertEquals(4, findings.size(), findings.toString());
  }

  @Test
  void ignoresLeadingLineBreakRunWhenCheckingContentsTitleTextAndFormatting() {
    var titleSource = source(0);
    var title = new ParagraphModel(List.of(
        new RunModel("\n", "Arial", "宋体", null, 24, false, titleSource),
        new RunModel("目    录", "Times New Roman", "黑体", null, 32, false, titleSource)),
        0, null, null, null, 360, "auto", titleSource, 0, null, "center");
    var document = document(title, paragraph("", 1, "宋体", 24, "left"));

    var failures = new ContentsRuleChecker(new ParagraphFormatChecker()).check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.ruleId().startsWith("TOC-01-") && result.judgement() == Judgement.FAIL)
        .toList();

    assertTrue(failures.isEmpty(), failures.toString());
  }

  @Test
  void reportsReviewInsteadOfFailureWhenContentsTitleAlignmentIsUnknown() {
    var document = document(paragraph("目录", 0, "黑体", 32, null));

    var finding = new ContentsRuleChecker(new ParagraphFormatChecker()).check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.reasonCode().equals("ALIGNMENT_UNKNOWN"))
        .findFirst().orElseThrow();

    assertEquals(Judgement.REVIEW, finding.judgement());
  }

  @Test
  void acceptsManualLineBreaksThatCreateOneBlankLineAfterContentsTitle() {
    var document = document(
        paragraph("目    录\n\n", 0, "黑体", 32, "center"),
        tocEntry("摘要……1", 1, "宋体", 24, 0, null));

    var findings = new ContentsRuleChecker(new ParagraphFormatChecker()).check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.ruleId().equals("TOC-01-SPACING")
            || result.ruleId().equals("TOC-01-BLANK"))
        .toList();

    assertTrue(findings.isEmpty(), findings.toString());
  }

  @Test
  void acceptsFourSpacesWhenManualLineBreaksSurroundContentsTitle() {
    var document = document(
        paragraph("\n目    录\n\n", 0, "黑体", 32, "center"),
        tocEntry("摘要……1", 1, "宋体", 24, 0, null));

    var findings = new ContentsRuleChecker(new ParagraphFormatChecker()).check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.ruleId().equals("TOC-01-SPACING")
            || result.ruleId().equals("TOC-01-BLANK"))
        .toList();

    assertTrue(findings.isEmpty(), findings.toString());
  }

  @Test
  void doesNotTreatOneManualLineBreakAsABlankLineAfterContentsTitle() {
    var document = document(
        paragraph("目    录\n", 0, "黑体", 32, "center"),
        tocEntry("摘要……1", 1, "宋体", 24, 0, null));

    var finding = new ContentsRuleChecker(new ParagraphFormatChecker()).check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.ruleId().equals("TOC-01-BLANK"))
        .findFirst().orElseThrow();

    assertEquals("TOC_TITLE_BLANK_LINE_MISSING", finding.reasonCode());
  }

  @Test
  void registersEveryEnglishContentsTitleFindingInTheCoverageCatalog() {
    var document = document(paragraph("CONTENTS", 0, "Times New Roman", 32, "center"));

    var unregisteredIds = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.source().equals(source(0)))
        .map(CheckResult::ruleId)
        .filter(ruleId -> RuleCatalog.find(ruleId) == null)
        .distinct()
        .toList();

    assertTrue(unregisteredIds.isEmpty(), "uncatalogued rule ids: " + unregisteredIds);
  }

  @Test
  void checksNumericContentsSeparatorWhenTheEntryUsesOrdinarySpaces() {
    var document = document(
        paragraph("目    录", 0, "黑体", 32, "center"),
        paragraph("", 1, "宋体", 24, "left"),
        paragraph("2.1  线性表的定义……2", 2, "宋体", 24, "left"));

    var finding = new ContentsRuleChecker(new ParagraphFormatChecker()).check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.ruleId().equals("TOC-SPACING"))
        .findFirst().orElseThrow();

    assertEquals("TOC_NUMBER_SPACING_MISMATCH", finding.reasonCode());
    assertEquals("编号后空 1 格", finding.expected());
  }

  @Test
  void acceptsOneOrdinarySpaceAfterNumericContentsNumberWithoutATab() {
    var document = document(
        paragraph("目录", 0, "黑体", 32, "center"),
        paragraph("", 1, "宋体", 24, "left"),
        paragraph("2.1 线性表的定义……2", 2, "宋体", 24, "left"));

    assertTrue(new ContentsRuleChecker(new ParagraphFormatChecker()).check(document, RuleSet.schoolV1()).stream()
        .noneMatch(result -> result.ruleId().equals("TOC-SPACING")),
        "one ordinary space should be accepted");
  }

  @Test
  void acceptsFlushLeftFirstLevelChapterWithTwoOrdinarySeparators() {
    var document = document(
        paragraph("目录", 0, "黑体", 32, "center"),
        paragraph("", 1, "宋体", 24, "left"),
        tocEntry("第一章  引言……1", 2, "宋体", 24, 0, null));

    var findings = new ContentsRuleChecker(new ParagraphFormatChecker()).check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.source().elementPath().equals(source(2).elementPath())
            && (result.ruleId().equals("TOC-SPACING") || result.ruleId().equals("TOC-ENTRY-INDENT"))
            && result.judgement() != Judgement.PASS)
        .toList();

    assertTrue(findings.isEmpty(), findings.toString());
  }

  @Test
  void rejectsThreeOrdinarySpacesAfterFirstLevelChapterNumber() {
    var document = document(
        paragraph("目录", 0, "黑体", 32, "center"),
        paragraph("", 1, "宋体", 24, "left"),
        tocEntry("第一章   引言……1", 2, "宋体", 24, 200, null));

    var finding = new ContentsRuleChecker(new ParagraphFormatChecker()).check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.ruleId().equals("TOC-SPACING"))
        .findFirst().orElseThrow();

    assertEquals("编号后空 2 格", finding.expected());
    assertEquals("TOC_NUMBER_SPACING_MISMATCH", finding.reasonCode());
    assertEquals(5, finding.source().start());
    assertEquals(6, finding.source().end());
  }

  @Test
  void preservesTextOffsetsBeforeTheChapterSeparator() {
    String text = "\n  第一章   引言……1";
    var document = document(
        paragraph("目录", 0, "黑体", 32, "center"),
        paragraph("", 1, "宋体", 24, "left"),
        tocEntry(text, 2, "宋体", 24, 200, null));

    var finding = new ContentsRuleChecker(new ParagraphFormatChecker()).check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.ruleId().equals("TOC-SPACING"))
        .findFirst().orElseThrow();

    assertEquals(8, finding.source().start());
    assertEquals(9, finding.source().end());
    assertEquals(" ", text.substring(finding.source().start(), finding.source().end()));
  }

  @Test
  void requiresTwoOrdinarySpacesForChaptersWithoutRelaxingSubsectionSpacing() {
    for (String separator : List.of("", " ", "   ", "\u3000", "\t", " \u00a0", "  \u200b")) {
      var document = document(
          paragraph("目录", 0, "黑体", 32, "center"),
          paragraph("", 1, "宋体", 24, "left"),
          tocEntry("第一章" + separator + "引言……1", 2, "宋体", 24, 200, null));
      var finding = new ContentsRuleChecker(new ParagraphFormatChecker()).check(document, RuleSet.schoolV1()).stream()
          .filter(result -> result.ruleId().equals("TOC-SPACING"))
          .findFirst().orElseThrow(() -> new AssertionError("accepted separator: " + separator));
      assertEquals("编号后空 2 格", finding.expected());
      assertEquals(Judgement.FAIL, finding.judgement());
    }
  }

  @Test
  void acceptsChapterSeparatorSplitAcrossRuns() {
    var entrySource = source(2);
    var entry = new ParagraphModel(List.of(
        new RunModel("第一章 ", "Times New Roman", "宋体", null, 24, false,
            new SourceAddress("word/document.xml", entrySource.elementPath(), 0, 4)),
        new RunModel(" 引言……1", "Times New Roman", "宋体", null, 24, false,
            new SourceAddress("word/document.xml", entrySource.elementPath(), 4, 10))),
        null, null, 200, null, 360, "auto", entrySource, null, null, "left");
    var findings = new ContentsRuleChecker(new ParagraphFormatChecker()).check(
        document(paragraph("目录", 0, "黑体", 32, "center"), entry), RuleSet.schoolV1());
    assertTrue(findings.stream().noneMatch(result -> result.ruleId().equals("TOC-SPACING")), findings.toString());
  }

  @Test
  void doesNotTreatManualLineBreakAfterChapterNumberAsTheRequiredSpace() {
    String text = "第一章\n 引言……1";
    var document = document(
        paragraph("目录", 0, "黑体", 32, "center"),
        paragraph("", 1, "宋体", 24, "left"),
        tocEntry(text, 2, "宋体", 24, 200, null));

    var finding = new ContentsRuleChecker(new ParagraphFormatChecker()).check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.ruleId().equals("TOC-SPACING"))
        .findFirst().orElseThrow();

    assertEquals(3, finding.source().start());
    assertEquals(5, finding.source().end());
    assertEquals("\n ", text.substring(finding.source().start(), finding.source().end()));
  }

  @Test
  void anchorsSpecialSeparatorWithoutIncludingTheTitle() {
    String text = "2.1\u3000研究背景……1";
    var document = document(
        paragraph("目录", 0, "黑体", 32, "center"),
        paragraph("", 1, "宋体", 24, "left"),
        tocEntry(text, 2, "宋体", 24, 200, null));

    var finding = new ContentsRuleChecker(new ParagraphFormatChecker()).check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.ruleId().equals("TOC-SPACING"))
        .findFirst().orElseThrow();

    assertEquals("\u3000", text.substring(finding.source().start(), finding.source().end()));
  }

  @Test
  void checksContentsEntryFontSizeLineSpacingIndentAndPageNumber() {
    var document = document(
        paragraph("目    录", 0, "黑体", 32, "center"),
        paragraph("", 1, "宋体", 24, "left"),
        tocEntry("第一章 引言……1", 2, "宋体", 24, 0, null),
        tocEntry("2.1 研究背景……2", 3, "宋体", 24, 200, null),
        tocEntry("2.2 研究意义……3", 5, "宋体", 24, 200, 480),
        tocEntry("2.1.1 研究范围……3", 4, "宋体", 24, 400, null));

    var findings = new ContentsRuleChecker(new ParagraphFormatChecker()).check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.ruleId().startsWith("TOC-ENTRY-")
            && result.judgement() == Judgement.FAIL)
        .toList();

    assertTrue(findings.isEmpty(), findings.toString());
  }

  @Test
  void acceptsExactLevelIndentWhenItIsEncodedAsLeftIndentInsteadOfFirstLineIndent() {
    var levelTwoSource = source(2);
    var levelThreeSource = source(3);
    var levelTwo = new ParagraphModel(List.of(new RunModel("2.1 研究背景……2", "Times New Roman",
        "宋体", null, 24, false, levelTwoSource)), null, 420, 200, null, 360, "auto",
        levelTwoSource, null, null, "left");
    var levelThree = new ParagraphModel(List.of(new RunModel("2.1.1 研究范围……3", "Times New Roman",
        "宋体", null, 24, false, levelThreeSource)), null, 840, 400, null, 360, "auto",
        levelThreeSource, null, null, "left");
    var document = document(paragraph("目    录", 0, "黑体", 32, "center"),
        paragraph("", 1, "宋体", 24, "left"), levelTwo, levelThree);

    var findings = new ContentsRuleChecker(new ParagraphFormatChecker())
        .check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.ruleId().equals("TOC-ENTRY-INDENT"))
        .toList();

    assertEquals(2, findings.size(), findings.toString());
    assertTrue(findings.stream().allMatch(result -> result.judgement() == Judgement.PASS),
        findings.toString());
  }

  @Test
  void acceptsFirstLevelContentsEntryWithoutIndent() {
    var document = document(
        paragraph("目录", 0, "黑体", 32, "center"),
        paragraph("", 1, "宋体", 24, "left"),
        tocEntry("第一章 引言……1", 2, "宋体", 24, 0, null));

    var finding = new ContentsRuleChecker(new ParagraphFormatChecker()).check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.ruleId().equals("TOC-ENTRY-INDENT"))
        .findFirst().orElseThrow();

    assertEquals(Judgement.PASS, finding.judgement());
    assertEquals("leftChars=0 / left=0 twips", finding.expected());
  }

  @Test
  void acceptsFirstLevelContentsEntryWithExactLeftIndent() {
    var source = source(2);
    var entry = new ParagraphModel(List.of(new RunModel("第一章 引言……1", "Times New Roman",
        "宋体", null, 24, false, source)), null, 0, null, null, 360, "auto", source,
        null, null, "left");
    var document = document(paragraph("目    录", 0, "黑体", 32, "center"),
        paragraph("", 1, "宋体", 24, "left"), entry);

    var finding = new ContentsRuleChecker(new ParagraphFormatChecker()).check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.source().equals(source) && result.ruleId().equals("TOC-ENTRY-INDENT"))
        .findFirst().orElseThrow();

    assertEquals(Judgement.PASS, finding.judgement());
  }

  @Test
  void doesNotTreatFirstLineIndentAsContentsEntryLeftIndent() {
    var source = source(2);
    var entry = new ParagraphModel(List.of(new RunModel("2.1 研究背景……2", "Times New Roman",
        "宋体", null, 24, false, source)), 480, null, null, null, 360, "auto", source,
        null, null, "left");
    var document = document(paragraph("目    录", 0, "黑体", 32, "center"),
        paragraph("", 1, "宋体", 24, "left"), entry);

    var finding = new ContentsRuleChecker(new ParagraphFormatChecker()).check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.source().equals(source) && result.ruleId().equals("TOC-ENTRY-INDENT"))
        .findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, finding.judgement());
    assertEquals("INDENT_MISMATCH", finding.reasonCode());
    assertEquals("left=0 twips", finding.actual());
  }

  @Test
  void rejectsFirstLevelLeftIndentValuesThatAreNotExact() {
    var document = document(
        paragraph("目    录", 0, "黑体", 32, "center"),
        paragraph("", 1, "宋体", 24, "left"),
        tocEntry("第一章 引言……1", 2, "宋体", 24, 201, null),
        tocEntry("第二章 方法……2", 3, "宋体", 24, null, 420));

    var findings = new ContentsRuleChecker(new ParagraphFormatChecker()).check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.ruleId().equals("TOC-ENTRY-INDENT")
            && result.judgement() == Judgement.FAIL)
        .toList();

    assertEquals(2, findings.size(), findings.toString());
    assertTrue(findings.stream().allMatch(result -> result.reasonCode().equals("INDENT_MISMATCH")),
        findings.toString());
  }

  @Test
  void reportsContentsEntryFontAndIndentMismatch() {
    var document = document(
        paragraph("目    录", 0, "黑体", 32, "center"),
        paragraph("", 1, "宋体", 24, "left"),
        tocEntry("2.1 研究背景……2", 2, "黑体", 28, 0, null));

    var findings = new ContentsRuleChecker(new ParagraphFormatChecker()).check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.judgement() == Judgement.FAIL)
        .toList();

    assertTrue(findings.stream().anyMatch(result -> result.ruleId().equals("TOC-ENTRY-FONT")), findings.toString());
    assertTrue(findings.stream().anyMatch(result -> result.ruleId().equals("TOC-ENTRY-SIZE")), findings.toString());
    assertTrue(findings.stream().anyMatch(result -> result.ruleId().equals("TOC-ENTRY-INDENT")), findings.toString());
  }

  @Test
  void rejectsTwipOnlyValuesThatDoNotMatchTheExactIndentContract() {
    var document = document(
        paragraph("目    录", 0, "黑体", 32, "center"),
        paragraph("", 1, "宋体", 24, "left"),
        tocEntry("2.1 研究背景……2", 2, "宋体", 24, null, 420),
        tocEntry("2.1.1 研究范围……3", 3, "宋体", 24, null, 900));

    var findings = new ContentsRuleChecker(new ParagraphFormatChecker()).check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.ruleId().equals("TOC-ENTRY-INDENT"))
        .toList();

    assertEquals(2, findings.size(), findings.toString());
    assertTrue(findings.stream().allMatch(result -> result.judgement() == Judgement.FAIL),
        findings.toString());
    assertTrue(findings.stream().allMatch(result -> result.reasonCode().equals("INDENT_MISMATCH")),
        findings.toString());
  }

  @Test
  void rejectsIncorrectContentsEntryLineSpacing() {
    var source = source(2);
    var entry = new ParagraphModel(List.of(new RunModel("2.1 研究背景……2", "Times New Roman",
        "宋体", null, 24, false, source)), 200, null, null, null, 359, "auto", source,
        200, null, "left");
    var document = document(paragraph("目    录", 0, "黑体", 32, "center"),
        paragraph("", 1, "宋体", 24, "left"), entry);

    var finding = new ContentsRuleChecker(new ParagraphFormatChecker()).check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.ruleId().equals("TOC-ENTRY-LINE"))
        .findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, finding.judgement());
    assertEquals("LINE_SPACING_MISMATCH", finding.reasonCode());
  }

  @Test
  void checksGeneratedEntryFormattingEvenWhenItsFontAndPageNumberAreWrong() {
    var source = source(2);
    var entry = new ParagraphModel(List.of(new RunModel("2.1 研究背景", "Arial", "黑体",
        null, 28, false, source)), 200, null, null, null, 360, "auto", source,
        null, null, "left", null, null, null, true);
    var document = document(paragraph("目    录", 0, "黑体", 32, "center"),
        paragraph("", 1, "宋体", 24, "left"), entry);

    var findings = new ContentsRuleChecker(new ParagraphFormatChecker()).check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.source().equals(source) && result.judgement() == Judgement.FAIL)
        .toList();

    assertTrue(findings.stream().anyMatch(result -> result.ruleId().equals("TOC-ENTRY-FONT")), findings.toString());
    assertTrue(findings.stream().anyMatch(result -> result.ruleId().equals("TOC-ENTRY-SIZE")), findings.toString());
    var indent = new ContentsRuleChecker(new ParagraphFormatChecker()).check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.source().equals(source) && result.ruleId().equals("TOC-ENTRY-INDENT"))
        .findFirst().orElseThrow();
    assertEquals(Judgement.FAIL, indent.judgement());
    assertEquals("INDENT_MISMATCH", indent.reasonCode());
    assertTrue(findings.stream().anyMatch(result -> result.ruleId().equals("TOC-ENTRY-PAGE")), findings.toString());
  }

  @Test
  void checksStyledManualEntryEvenWhenItsFormattingAndPageNumberAreWrong() {
    var source = source(2);
    var entry = tocEntry("2.1 研究背景", 2, "黑体", 28, 200, null, "TOC2");
    var document = document(paragraph("目录", 0, "黑体", 32, "center"),
        paragraph("", 1, "宋体", 24, "left"), entry);

    var findings = new ContentsRuleChecker(new ParagraphFormatChecker()).check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.source().equals(source) && result.judgement() == Judgement.FAIL)
        .toList();

    assertTrue(findings.stream().anyMatch(result -> result.ruleId().equals("TOC-ENTRY-FONT")), findings.toString());
    assertTrue(findings.stream().anyMatch(result -> result.ruleId().equals("TOC-ENTRY-SIZE")), findings.toString());
    assertTrue(findings.stream().anyMatch(result -> result.ruleId().equals("TOC-ENTRY-PAGE")), findings.toString());
  }

  @Test
  void keepsMalformedManualEntryInsideContentsWhenBodyHasMatchingHeading() {
    var malformedSource = source(2);
    var malformed = new ParagraphModel(List.of(new RunModel("2.1 研究背景", "Arial", "黑体",
        null, 28, false, malformedSource)), 200, null, null, null, 360, "auto",
        malformedSource, 200, null, "left");
    var body = tocEntry("2.1 研究背景", 4, "黑体", 28, null, null, "Heading2");
    var normalEntry = tocEntry("2.2 研究意义……3", 3, "宋体", 24, 200, null);
    var document = document(
        paragraph("目录", 0, "黑体", 32, "center"),
        paragraph("", 1, "宋体", 24, "left"),
        malformed,
        normalEntry,
        body);

    var findings = new ContentsRuleChecker(new ParagraphFormatChecker())
        .check(document, RuleSet.schoolV1()).stream().toList();

    assertTrue(findings.stream().anyMatch(result -> result.source().equals(malformedSource)
        && result.ruleId().equals("TOC-ENTRY-FONT") && result.judgement() == Judgement.FAIL),
        findings.toString());
    assertTrue(findings.stream().anyMatch(result -> result.source().equals(malformedSource)
        && result.ruleId().equals("TOC-ENTRY-SIZE") && result.judgement() == Judgement.FAIL),
        findings.toString());
    assertTrue(findings.stream().anyMatch(result -> result.source().equals(malformedSource)
        && result.ruleId().equals("TOC-ENTRY-PAGE")
        && result.reasonCode().equals("TOC_PAGE_NUMBER_MISSING")), findings.toString());
    assertTrue(findings.stream().noneMatch(result -> result.ruleId().equals("TOC-02")
        && result.reasonCode().equals("BODY_HEADING_NOT_IN_TOC")), findings.toString());
  }

  @Test
  void reportsContentsEntryWithMissingOrNonNumericPageNumber() {
    var document = document(
        paragraph("目    录", 0, "黑体", 32, "center"),
        paragraph("", 1, "宋体", 24, "left"),
        tocEntry("第一章  引言", 2, "宋体", 24, 0, null),
        tocEntry("2.1 研究背景……第二页", 3, "宋体", 24, 200, null));

    var findings = new ContentsRuleChecker(new ParagraphFormatChecker()).check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.ruleId().equals("TOC-ENTRY-PAGE") && result.judgement() == Judgement.FAIL)
        .toList();

    assertEquals(2, findings.size(), findings.toString());
  }

  @Test
  void reportsRequiredBodySectionsMissingFromAnOtherwiseDetectedContentsPage() {
    var document = document(
        paragraph("目录", 0, "黑体", 32, "center"),
        paragraph("", 1, "宋体", 24, "left"),
        paragraph("第一章 绪论", 2, "黑体", 32, "center"),
        paragraph("参考文献", 3, "黑体", 32, "center"),
        paragraph("致谢", 4, "黑体", 32, "center"),
        paragraph("附录", 5, "黑体", 32, "center"));

    var findings = new ContentsRuleChecker(new ParagraphFormatChecker())
        .check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.ruleId().equals("TOC-REQUIRED-ENTRY"))
        .toList();

    assertEquals(4, findings.size(), findings.toString());
    assertTrue(findings.stream().allMatch(result -> result.judgement() == Judgement.FAIL), findings.toString());
  }

  @Test
  void checksManualContentsAbstractEntryWithoutPageNumber() {
    var document = document(
        paragraph("目    录", 0, "黑体", 32, "center"),
        paragraph("", 1, "宋体", 24, "left"),
        tocEntry("摘要", 2, "宋体", 24, 0, null),
        paragraph("摘要", 3, "黑体", 32, "center"),
        paragraph("第一章 引言", 4, "黑体", 32, "left"));

    var pageFinding = new ContentsRuleChecker(new ParagraphFormatChecker())
        .check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.ruleId().equals("TOC-ENTRY-PAGE")
            && result.source().equals(source(2)))
        .findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, pageFinding.judgement());
    assertEquals("TOC_PAGE_NUMBER_MISSING", pageFinding.reasonCode());
  }

  @Test
  void checksNumberedContentsEntryWhenManualBreakSeparatesTitleAndPage() {
    var entry = tocEntry("2.1 研究背景……\n2", 2, "宋体", 24, 200, null);
    var document = document(
        paragraph("目录", 0, "黑体", 32, "center"),
        paragraph("", 1, "宋体", 24, "left"),
        entry,
        paragraph("2.1 研究背景", 3, "黑体", 28, "left"));

    var pageFinding = new ContentsRuleChecker(new ParagraphFormatChecker())
        .check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.ruleId().equals("TOC-ENTRY-PAGE")
            && result.source().equals(source(2)))
        .findFirst().orElseThrow();

    assertEquals(Judgement.PASS, pageFinding.judgement());
    assertEquals("2", pageFinding.actual());
  }

  @Test
  void ignoresFirstLineIndentOnFirstLevelEntryWithCorrectLeftIndent() {
    var source = source(2);
    var entry = new ParagraphModel(List.of(new RunModel("第一章  引言……1", "Times New Roman",
        "宋体", null, 24, false, source)), 480, 0, null, null, 360, "auto", source,
        200, null, "left");
    var document = document(paragraph("目    录", 0, "黑体", 32, "center"),
        paragraph("", 1, "宋体", 24, "left"), entry);

    var finding = new ContentsRuleChecker(new ParagraphFormatChecker()).check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.ruleId().equals("TOC-ENTRY-INDENT"))
        .findFirst().orElseThrow();

    assertEquals(Judgement.PASS, finding.judgement());
  }

  @Test
  void checksContentsIndentUsingExactTwipsWhenCharacterEvidenceIsAbsent() {
    var document = document(
        paragraph("目    录", 0, "黑体", 32, "center"),
        paragraph("", 1, "宋体", 24, "left"),
        tocEntry("2.1 定义……2", 2, "宋体", 24, null, 480),
        tocEntry("2.1.1 子项……2", 3, "宋体", 24, null, 960));

    var findings = new ContentsRuleChecker(new ParagraphFormatChecker()).check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.ruleId().equals("TOC-ENTRY-INDENT"))
        .toList();

    assertEquals(2, findings.size());
    assertTrue(findings.stream().allMatch(result -> result.judgement() == Judgement.PASS),
        findings.toString());
  }

  @Test
  void rejectsContentsIndentTwipsThatDifferFromTheExactExpectedValue() {
    var document = document(
        paragraph("目    录", 0, "黑体", 32, "center"),
        paragraph("", 1, "宋体", 24, "left"),
        tocEntry("2.1 定义……2", 2, "宋体", 24, null, 420));

    var finding = new ContentsRuleChecker(new ParagraphFormatChecker()).check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.ruleId().equals("TOC-ENTRY-INDENT"))
        .findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, finding.judgement());
    assertEquals("INDENT_MISMATCH", finding.reasonCode());
    assertTrue(finding.expected().contains("480 twips"), finding.expected());
    assertTrue(finding.actual().contains("420"), finding.actual());
  }

  @Test
  void doesNotCountCarriageReturnAsAnOrdinaryChapterSpace() {
    var document = document(paragraph("目录", 0, "黑体", 32, "center"),
        tocEntry("第一章\r 引言……1", 2, "宋体", 24, 200, null));
    var finding = new ContentsRuleChecker(new ParagraphFormatChecker()).check(document, RuleSet.schoolV1())
        .stream().filter(result -> result.ruleId().equals("TOC-SPACING")).findFirst().orElseThrow();
    assertEquals(Judgement.FAIL, finding.judgement());
    assertEquals(3, finding.source().start());
    assertEquals(5, finding.source().end());
  }

  @Test
  void checksNumericContentsSpacingAcrossWordBreakElements(@TempDir Path directory) throws Exception {
    for (String number : List.of("2.1", "2.1.1")) {
      for (String breakXml : List.of("<w:br/>", "<w:cr/>")) {
        for (String separator : List.of("", " ", "  ")) {
          var parsed = parseContentsRuns("<w:r><w:t xml:space=\"preserve\">" + number + separator
              + "研究</w:t>" + breakXml + "<w:t>背景……1</w:t></w:r>", directory);
          var findings = new ContentsRuleChecker(new ParagraphFormatChecker()).check(parsed, RuleSet.schoolV1())
              .stream().filter(result -> result.ruleId().equals("TOC-SPACING")).toList();
          assertEquals(" ".equals(separator) ? 0 : 1, findings.size(), number + " separator=" + separator);
          if (!findings.isEmpty()) {
            var finding = findings.get(0);
            int offset = number.length() + (separator.isEmpty() ? 0 : 1);
            assertEquals(offset, finding.source().start());
            assertEquals(offset + (separator.isEmpty() ? 0 : 1), finding.source().end());
          }
        }
        var parsed = parseContentsRuns("<w:r><w:t>" + number + "</w:t>" + breakXml
            + "<w:t>研究背景……1</w:t></w:r>", directory);
        var finding = new ContentsRuleChecker(new ParagraphFormatChecker()).check(parsed, RuleSet.schoolV1())
            .stream().filter(result -> result.ruleId().equals("TOC-SPACING")).findFirst().orElseThrow();
        assertEquals(number.length(), finding.source().start());
        assertEquals(number.length() + 1, finding.source().end());
      }
    }
  }

  @Test
  void detectsUnicodeWhitespaceInsideNumbersAcrossWordRuns(@TempDir Path directory) throws Exception {
    for (String space : List.of(" ", "\u3000", "\u00a0", "\u202f", "\u200b")) {
      var parsed = parseContentsRuns("<w:r><w:t>2.</w:t></w:r><w:r><w:t xml:space=\"preserve\">"
          + space + "</w:t></w:r><w:r><w:t>1 研究</w:t><w:br/><w:t>背景……1</w:t></w:r>", directory);
      var finding = new ContentsRuleChecker(new ParagraphFormatChecker()).check(parsed, RuleSet.schoolV1())
          .stream().filter(result -> result.ruleId().equals("TOC-SPACING")).findFirst().orElseThrow();
      assertEquals("章节序号内部不夹空格", finding.expected());
      assertEquals(2, finding.source().start());
      assertEquals(3, finding.source().end());
      assertEquals("2." + space + "1", finding.actual());
      assertTrue(cn.luwneshacha.thesis.report.RuleDescriptions.suggestion(finding).contains("改为“2.1”"));
    }
  }

  private static ParsedDocument parseContentsRuns(String runsXml, Path directory) throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
            + "<w:p><w:r><w:t>目    录</w:t></w:r></w:p><w:p/>"
            + "<w:p><w:pPr><w:pStyle w:val=\"TOC2\"/></w:pPr>" + runsXml + "</w:p>"
            + "</w:body></w:document>"));
    Path file = directory.resolve("contents-breaks.docx");
    pkg.save(file.toFile());
    return new Docx4jDocumentParser().parse(new DocxReader().read(file));
  }

  private static ParsedDocument document(ParagraphModel... paragraphs) {
    return new ParsedDocument("source", List.of(paragraphs), List.of(), List.of());
  }

  private static ParagraphModel paragraph(String text, int index, String eastAsiaFont,
      int size, String alignment) {
    var source = source(index);
    return new ParagraphModel(List.of(new RunModel(text, "Times New Roman", eastAsiaFont,
        null, size, false, source)), 0, null, null, null, 360, "auto", source, 0, null, alignment);
  }

  private static ParagraphModel paragraph(String text, List<Integer> path) {
    var source = new SourceAddress("word/document.xml", path, 0, text.length());
    return new ParagraphModel(List.of(new RunModel(text, "Times New Roman", "宋体",
        null, 24, false, source)), 0, null, null, null, 360, "auto", source, 0, null, "left");
  }

  private static ParagraphModel tocEntry(String text, int index, String eastAsiaFont, int size,
      Integer leftIndentChars, Integer leftIndentTwips) {
    return tocEntry(text, index, eastAsiaFont, size, leftIndentChars, leftIndentTwips, null);
  }

  private static ParagraphModel tocEntry(String text, int index, String eastAsiaFont, int size,
      Integer leftIndentChars, Integer leftIndentTwips, String styleId) {
    var source = source(index);
    return styleId == null
        ? new ParagraphModel(List.of(new RunModel(text, "Times New Roman", eastAsiaFont,
            null, size, false, source)), null, leftIndentTwips,
            leftIndentChars, null, 360, "auto", source, null, null, "left")
        : new ParagraphModel(List.of(new RunModel(text, "Times New Roman", eastAsiaFont,
            null, size, false, source)), null, leftIndentTwips,
            leftIndentChars, null, 360, "auto", source, null, null, "left",
            null, null, null, false, null, null, false,
            false, false, false, null, null, styleId);
  }

  private static SourceAddress source(int index) {
    return new SourceAddress("word/document.xml", List.of(0, index), 0, 20);
  }
}
