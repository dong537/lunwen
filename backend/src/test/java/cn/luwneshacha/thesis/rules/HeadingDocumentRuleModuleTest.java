package cn.luwneshacha.thesis.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.RunModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import cn.luwneshacha.thesis.document.TableModel;
import java.util.List;
import org.junit.jupiter.api.Test;

class HeadingDocumentRuleModuleTest {
  private static final RuleSet RULES = RuleSet.schoolV1();

  @Test
  void reportsMissingBlankLineBeforeTheFirstBodyParagraph() {
    var chapter = paragraph("第一章 绪论", List.of(0, 0));
    var body = paragraph("正文内容", List.of(0, 1));

    var finding = module().check(document(chapter, body), RULES).stream()
        .filter(result -> result.reasonCode().equals("CHAPTER_BODY_BLANK_LINE_MISSING"))
        .findFirst().orElseThrow();

    assertEquals("HEAD-02-BLANK", finding.ruleId());
    assertEquals(body.source(), finding.source());
  }

  @Test
  void acceptsExactlyOneBlankParagraphAfterAChapterTitle() {
    var chapter = paragraph("第一章 绪论", List.of(0, 0));
    var blank = paragraph("", List.of(0, 1));
    var body = paragraph("正文内容", List.of(0, 2));

    var findings = module().check(document(chapter, blank, body), RULES);

    assertTrue(findings.stream().noneMatch(result -> result.ruleId().equals("HEAD-02-BLANK")));
  }

  @Test
  void blankParagraphInsideATableDoesNotCountAsTheRequiredChapterGap() {
    var chapter = paragraph("第一章 绪论", List.of(0, 0));
    var tableParagraph = paragraph("", List.of(0, 1, 0));
    var body = paragraph("正文内容", List.of(0, 2));
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(0, 1), 0, 0),
        null, null, 1, 1, false, false, false, false, true, false);
    var document = new ParsedDocument("source", List.of(chapter, tableParagraph, body),
        List.of(), List.of(), List.of(table), List.of(), List.of(), List.of());

    var finding = module().check(document, RULES).stream()
        .filter(result -> result.reasonCode().equals("CHAPTER_BODY_BLANK_LINE_MISSING"))
        .findFirst().orElseThrow();

    assertEquals(body.source(), finding.source());
  }

  @Test
  void doesNotReportAnUnresolvedBlankLineWhenDocumentEndsWithAChapterTitle() {
    var chapter = paragraph("第一章 绪论", List.of(0, 0));

    var findings = module().check(document(chapter), RULES);

    assertTrue(findings.stream().noneMatch(result -> result.ruleId().equals("HEAD-02-BLANK")));
  }

  @Test
  void doesNotClassifyChapterSynopsisOrTemperatureRangeAsHeadings() {
    var checker = new HeadingRuleChecker(new ParagraphFormatChecker());

    assertEquals(0, checker.headingLevel(paragraph(
        "第一章是绪论，主要讲述研究背景及意义。", List.of(0, 0))));
    assertEquals(0, checker.headingLevel(paragraph("25.1℃~25.5℃", List.of(0, 1))));
    assertEquals(1, checker.headingLevel(paragraph("第一章  绪论", List.of(0, 2))));
  }

  @Test
  void doesNotClassifyYearMonthVersionTextAsAHeading() {
    var checker = new HeadingRuleChecker(new ParagraphFormatChecker());

    assertEquals(0, checker.headingLevel(paragraph("2024.10版本更新情况", List.of(0, 0))));
  }

  @Test
  void doesNotTreatMalformedManualContentsEntryAsASecondLevelBodyHeading() {
    var malformedSource = new SourceAddress("word/document.xml", List.of(0, 2), 0, 20);
    var malformed = new ParagraphModel(List.of(new RunModel("2.1 研究背景", "Arial", "黑体",
        null, 28, false, malformedSource)), 200, null, null, null, 360, "auto",
        malformedSource, 200, null, "left");
    var bodySource = new SourceAddress("word/document.xml", List.of(0, 3), 0, 20);
    var body = new ParagraphModel(List.of(new RunModel("2.1 研究背景", "Times New Roman", "黑体",
        null, 28, true, bodySource)), 0, null, null, null, 360, "auto", bodySource,
        0, null, "left", null, null, null, false, null, null, false,
        false, false, false, null, null, "Heading2");
    var document = document(paragraph("目录", List.of(0, 0)), paragraph("", List.of(0, 1)), malformed, body);
    assertTrue(module().isTocEntryParagraph(document, 2, malformed, "2.1 研究背景"));
    var findings = module().check(document, RULES);

    assertTrue(findings.stream().noneMatch(result -> result.source().equals(malformedSource)
        && result.ruleId().startsWith("HEAD-")), findings.toString());
  }

  @Test
  void doesNotClassifyUnnumberedConclusionAsHeadingWithoutOutlineEvidence() {
    var conclusion = paragraph("结论与展望", List.of(0, 0));
    var checker = new HeadingRuleChecker(new ParagraphFormatChecker());

    assertEquals(0, checker.headingLevel(conclusion));
    assertTrue(module().check(document(conclusion), RULES).stream()
        .noneMatch(result -> result.ruleId().startsWith("HEAD-")
            || result.ruleId().startsWith("HEADING-")));
  }

  @Test
  void checksFormattingOfAnUnnumberedIntroductionHeadingWhenItHasHeadingEvidence() {
    var source = new SourceAddress("word/document.xml", List.of(0, 0), 0, 2);
    var introduction = new ParagraphModel(List.of(new RunModel("绪论", "Arial", "宋体",
        null, 24, false, source)), 0, null, null, null, 360, "auto", source,
        0, 0, "center");

    var findings = module().check(document(introduction), RULES);

    assertTrue(findings.stream().anyMatch(result -> result.ruleId().equals("HEADING-FONT")
        && result.judgement() == Judgement.FAIL), findings.toString());
    assertTrue(findings.stream().anyMatch(result -> result.ruleId().equals("HEADING-SIZE")
        && result.judgement() == Judgement.FAIL), findings.toString());
  }

  @Test
  void anchorsOnlyWhitespaceInsertedInsideAHeadingNumber() {
    String text = "5. 6 \u6807\u9898";
    var source = new SourceAddress("word/document.xml", List.of(0, 0), 0, text.length());
    var heading = new ParagraphModel(List.of(new RunModel(text, "Times New Roman", "榛戜綋",
        null, 28, false, source)), null, null, null, null, 360, "auto", source, null, null, "left");

    var finding = module().check(document(heading), RULES).stream()
        .filter(result -> result.ruleId().equals("HEAD-02-SPACING")).findFirst().orElseThrow();

    assertEquals(2, finding.source().start());
    assertEquals(3, finding.source().end());
  }

  @Test
  void anchorsOnlyTheExcessOrWrongHeadingSeparatorCharacter() {
    for (var example : List.of(
        new SpacingExample("2.1  \u529f\u80fd\u6a21\u5757", 4, 5),
        new SpacingExample("2.1\u3000\u529f\u80fd\u6a21\u5757", 3, 4),
        new SpacingExample("2.1\t\u529f\u80fd\u6a21\u5757", 3, 4))) {
      var heading = paragraph(example.text(), List.of(0, 0));

      var finding = module().check(document(heading), RULES).stream()
          .filter(result -> result.ruleId().equals("HEAD-02-SPACING")).findFirst().orElseThrow();

      assertEquals(example.start(), finding.source().start(), example.text());
      assertEquals(example.end(), finding.source().end(), example.text());
    }
  }

  @Test
  void missingHeadingSeparatorDoesNotHighlightTheWholeHeading() {
    var heading = paragraph("2.1\u529f\u80fd\u6a21\u5757", List.of(0, 0));

    var finding = module().check(document(heading), RULES).stream()
        .filter(result -> result.ruleId().equals("HEAD-02-SPACING")).findFirst().orElseThrow();

    assertEquals(finding.source().start(), finding.source().end());
  }

  @Test
  void headingWhitespaceAnchorAccountsForTrimmedLeadingText() {
    var heading = paragraph(" 2.1  \u529f\u80fd\u6a21\u5757", List.of(0, 0));

    var finding = module().check(document(heading), RULES).stream()
        .filter(result -> result.ruleId().equals("HEAD-02-SPACING")).findFirst().orElseThrow();

    assertEquals(5, finding.source().start());
    assertEquals(6, finding.source().end());
  }

  @Test
  void endMatterTitleSpacingFindingsPointOnlyAtTheOffendingWhitespace() {
    var reference = paragraph("\u53c2\u8003 \u6587\u732e", List.of(0, 0));
    var acknowledgement = paragraph("\u81f4\t  \u8c22", List.of(0, 1));
    var appendix = paragraph("\u9644\u3000\u3000\u5f55", List.of(0, 2));

    var findings = module().check(document(reference, acknowledgement, appendix), RULES);

    var referenceFinding = findings.stream().filter(result -> result.ruleId().equals("REF-01-SPACING"))
        .findFirst().orElseThrow();
    var acknowledgementFinding = findings.stream().filter(result -> result.ruleId().equals("END-01-SPACING"))
        .findFirst().orElseThrow();
    var appendixFinding = findings.stream().filter(result -> result.ruleId().equals("APP-02-SPACING"))
        .findFirst().orElseThrow();

    assertEquals(2, referenceFinding.source().start());
    assertEquals(3, referenceFinding.source().end());
    assertEquals(1, acknowledgementFinding.source().start());
    assertEquals(2, acknowledgementFinding.source().end());
    assertEquals(1, appendixFinding.source().start());
    assertEquals(2, appendixFinding.source().end());
  }

  @Test
  void missingRequiredEndMatterTitleSpacesDoNotHighlightTheWholeTitle() {
    var acknowledgement = paragraph("\u81f4\u8c22", List.of(0, 0));
    var appendix = paragraph("\u9644\u5f55", List.of(0, 1));

    var findings = module().check(document(acknowledgement, appendix), RULES);

    for (String ruleId : List.of("END-01-SPACING", "APP-02-SPACING")) {
      var finding = findings.stream().filter(result -> result.ruleId().equals(ruleId))
          .findFirst().orElseThrow();
      assertEquals(finding.source().start(), finding.source().end(), ruleId);
    }
  }

  private record SpacingExample(String text, int start, int end) { }

  @Test
  void ignoresLeadingLineBreakRunWhenCheckingEnglishAbstractTitle() {
    var source = new SourceAddress("word/document.xml", List.of(0, 0), 0, 9);
    var title = new ParagraphModel(List.of(
        new RunModel("\n", "Arial", "宋体", null, 24, false, source),
        new RunModel("ABSTRACT", "Times New Roman", "Times New Roman", null, 32, true,
            new SourceAddress("word/document.xml", List.of(0, 0), 1, 9))),
        0, null, null, null, 360, "auto", source, 0, null, "center");

    var findings = module().check(document(title), RULES);

    assertTrue(findings.stream().noneMatch(result ->
        List.of("EN-01-TEXT", "EN-01-FONT", "EN-01-SIZE", "EN-01-BOLD").contains(result.ruleId())
            && result.judgement() == Judgement.FAIL), findings.toString());
  }

  @Test
  void classifiesUnnumberedConclusionByOutlineLevel() {
    var checker = new HeadingRuleChecker(new ParagraphFormatChecker());

    assertEquals(1, checker.headingLevel(paragraphWithOutline("结论与展望", 0)));
    assertEquals(1, checker.headingLevel(paragraphWithOutline("结论与展望", 1)));
    assertEquals(1, checker.headingLevel(paragraphWithOutline("结论与展望", 2)));
  }

  @Test
  void keepsExplicitlyNumberedConclusionAsFirstLevelHeading() {
    var checker = new HeadingRuleChecker(new ParagraphFormatChecker());

    assertEquals(1, checker.headingLevel(paragraph("第五章 结论与展望", List.of(0, 0))));
  }

  @Test
  void requiresCenteredAlignmentForAFirstLevelHeading() {
    var heading = new ParagraphModel(List.of(new RunModel("第一章 绪论", "Times New Roman", "黑体",
        null, 32, true, new SourceAddress("word/document.xml", List.of(0, 0), 0, 6))),
        null, null, null, null, 360, "auto", new SourceAddress("word/document.xml", List.of(0, 0), 0, 6),
        0, 0, "left");

    var findings = module().check(document(heading), RULES);

    assertTrue(findings.stream().anyMatch(result -> result.ruleId().equals("HEAD-01-ALIGNMENT")),
        findings.toString());
    assertTrue(findings.stream().anyMatch(result -> result.ruleId().equals("HEAD-01-ALIGNMENT")
        && result.judgement() == Judgement.FAIL && "center".equals(result.expected())), findings.toString());
  }

  @Test
  void reportsReviewWhenAcknowledgementAlignmentEvidenceIsMissing() {
    var source = new SourceAddress("word/document.xml", List.of(0, 0), 0, 7);
    var run = new RunModel("致    谢", "Times New Roman", "黑体", null, 32, true, source);
    var title = new ParagraphModel(List.of(run), 0, null, null, null, 360, "auto", source,
        0, null, null);

    var finding = module().check(document(title), RULES).stream()
        .filter(result -> result.ruleId().equals("END-01-ALIGNMENT"))
        .findFirst().orElseThrow();

    assertEquals(Judgement.REVIEW, finding.judgement());
  }

  @Test
  void ignoresTrailingWhitespaceAfterReferenceTitleWhenCheckingInternalSpacing() {
    var source = new SourceAddress("word/document.xml", List.of(0, 0), 0, 6);
    var title = new ParagraphModel(List.of(
        new RunModel("参考文献", "Times New Roman", "黑体", null, 32, true,
            new SourceAddress("word/document.xml", List.of(0, 0), 0, 4)),
        new RunModel(" ", "Arial", "宋体", null, 24, false,
            new SourceAddress("word/document.xml", List.of(0, 0), 4, 5))),
        0, null, null, null, 360, "auto", source, 0, null, "center");

    var findings = module().check(document(title), RULES);

    assertTrue(findings.stream().noneMatch(result -> result.ruleId().equals("REF-01-SPACING")
        && result.judgement() == Judgement.FAIL), findings.toString());
    assertTrue(findings.stream().noneMatch(result -> result.ruleId().equals("REF-01-FONT")
        && result.source().start() == 4), findings.toString());
  }

  @Test
  void checksLowerLevelHeadingIndentAgainstTheNormative420TwipsValue() {
    var source = new SourceAddress("word/document.xml", List.of(0, 0), 0, 10);
    var exact = new ParagraphModel(List.of(new RunModel("（1）、小标题", "Times New Roman", "宋体",
        null, 24, false, source)), 420, null, null, null, 360, "auto", source, null, null, "left");
    var wrong = new ParagraphModel(List.of(new RunModel("（1）、小标题", "Times New Roman", "宋体",
        null, 24, false, source)), 480, null, null, null, 360, "auto", source, null, null, "left");

    var exactFindings = module().check(document(exact), RULES);
    var wrongFindings = module().check(document(wrong), RULES);

    assertTrue(exactFindings.stream().noneMatch(result -> result.ruleId().equals("HEAD-05-INDENT")
        && result.judgement() == Judgement.FAIL), exactFindings.toString());
    assertTrue(wrongFindings.stream().anyMatch(result -> result.ruleId().equals("HEAD-05-INDENT")
        && result.judgement() == Judgement.FAIL), wrongFindings.toString());
  }

  @Test
  void doesNotTreatAcknowledgementTextInsideATableAsASectionTitle() {
    var tableCell = paragraph("致谢", List.of(0, 1, 0, 0));
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(0, 1), 0, 0),
        null, null, 1, 1, false, false, false, false, false, false);
    var document = new ParsedDocument("source", List.of(tableCell), List.of(), List.of(),
        List.of(table), List.of(), List.of(), List.of());

    var findings = module().check(document, RULES);

    assertTrue(findings.stream().noneMatch(result -> result.ruleId().startsWith("END-01-")),
        findings.toString());
  }

  private static HeadingDocumentRuleModule module() {
    var formatChecker = new ParagraphFormatChecker();
    return new HeadingDocumentRuleModule(new HeadingRuleChecker(formatChecker), formatChecker);
  }

  private static ParsedDocument document(ParagraphModel... paragraphs) {
    return new ParsedDocument("source", List.of(paragraphs), List.of(), List.of());
  }

  private static ParagraphModel paragraph(String text, List<Integer> path) {
    var source = new SourceAddress("word/document.xml", path, 0, text.length());
    var run = new RunModel(text, "Times New Roman", "黑体", null, 32, true, source);
    return new ParagraphModel(List.of(run), 0, null, null, null, null, source, 0);
  }

  private static ParagraphModel paragraphWithOutline(String text, int outlineLevel) {
    var source = new SourceAddress("word/document.xml", List.of(0, 0), 0, text.length());
    var run = new RunModel(text, "Times New Roman", "黑体", null, 32, true, source);
    return new ParagraphModel(List.of(run), null, null, null, null, null, null, source,
        null, outlineLevel, null);
  }
}
