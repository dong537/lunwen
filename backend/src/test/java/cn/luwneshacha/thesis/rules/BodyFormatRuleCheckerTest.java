package cn.luwneshacha.thesis.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.luwneshacha.thesis.document.ImageModel;
import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.RunModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import cn.luwneshacha.thesis.document.TableModel;
import java.util.List;
import org.junit.jupiter.api.Test;

class BodyFormatRuleCheckerTest {
  @Test
  void checksLineSpacingInProseBeginningWithAFigureReference() {
    var chapter = heading("第三章  系统设计",
        new SourceAddress("word/document.xml", List.of(0, 0), 0, 9), null);
    var body = bodyParagraph("图3-1所示的系统由三部分组成。", 1);
    var document = new ParsedDocument("source", List.of(chapter, body), List.of(), List.of());

    var findings = new BodyFormatRuleChecker(new ParagraphFormatChecker())
        .check(document, RuleSet.schoolV1());

    assertTrue(findings.stream().anyMatch(result -> result.ruleId().equals("BODY-LINE")
        && result.judgement() == Judgement.FAIL && result.source().equals(body.source())),
        findings.toString());
  }

  @Test
  void checksLineSpacingInProseBeginningWithATableReference() {
    var chapter = heading("第三章  系统设计",
        new SourceAddress("word/document.xml", List.of(0, 0), 0, 9), null);
    var body = bodyParagraph("表3-1列出了各项参数。", 1);
    var document = new ParsedDocument("source", List.of(chapter, body), List.of(), List.of());

    var findings = new BodyFormatRuleChecker(new ParagraphFormatChecker())
        .check(document, RuleSet.schoolV1());

    assertTrue(findings.stream().anyMatch(result -> result.ruleId().equals("BODY-LINE")
        && result.judgement() == Judgement.FAIL && result.source().equals(body.source())),
        findings.toString());
  }

  @Test
  void excludesParsedFigureCaptionsByParagraphLocationRatherThanCharacterRange() {
    var chapter = heading("第三章  系统设计",
        new SourceAddress("word/document.xml", List.of(0, 0), 0, 9), null);
    var caption = bodyParagraph("图3-1 系统结构", 2);
    var image = new ImageModel(new SourceAddress("word/document.xml", List.of(0, 1), 0, 0),
        new SourceAddress("word/document.xml", List.of(0, 2), 0, 4),
        "图3-1 系统结构", 100, 100, true, true, true);
    var document = new ParsedDocument("source", List.of(chapter, caption), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(image));

    var findings = new BodyFormatRuleChecker(new ParagraphFormatChecker())
        .check(document, RuleSet.schoolV1());

    assertTrue(findings.stream().noneMatch(result -> result.source().equals(caption.source())
        && result.ruleId().startsWith("BODY-")), findings.toString());
  }

  @Test
  void excludesParsedTableAndContinuationCaptionsFromBodyFormatting() {
    var chapter = heading("第三章  系统设计",
        new SourceAddress("word/document.xml", List.of(0, 0), 0, 9), null);
    var caption = bodyParagraph("表3-1 设备参数", 1);
    var continuationCaption = bodyParagraph("续表3-1", 3);
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(0, 2), 0, 0),
        new SourceAddress("word/document.xml", List.of(0, 1), 0, 4),
        "表3-1 设备参数", 2, 2, true, true, true, false, true, false);
    var continuation = new TableModel(new SourceAddress("word/document.xml", List.of(0, 4), 0, 0),
        continuationCaption.source(), "续表3-1", 2, 2, true, true, true, false, true, false);
    var document = new ParsedDocument("source", List.of(chapter, caption, continuationCaption),
        List.of(), List.of(), List.of(table, continuation), List.of(), List.of());

    var findings = new BodyFormatRuleChecker(new ParagraphFormatChecker())
        .check(document, RuleSet.schoolV1());

    assertTrue(findings.stream().noneMatch(result -> result.ruleId().startsWith("BODY-")
        && (result.source().equals(caption.source())
            || result.source().equals(continuationCaption.source()))), findings.toString());
  }

  @Test
  void doesNotExcludeBodyProseWhenAnUnrelatedCaptionHasTheSameText() {
    var chapter = heading("第三章  系统设计",
        new SourceAddress("word/document.xml", List.of(0, 0), 0, 9), null);
    var body = bodyParagraph("图3-1 系统结构", 1);
    var caption = bodyParagraph("图3-1 系统结构", 3);
    var image = new ImageModel(new SourceAddress("word/document.xml", List.of(0, 2), 0, 0),
        caption.source(), "图3-1 系统结构", 100, 100, true, true, true);
    var document = new ParsedDocument("source", List.of(chapter, body, caption), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(image));

    var findings = new BodyFormatRuleChecker(new ParagraphFormatChecker())
        .check(document, RuleSet.schoolV1());

    assertTrue(findings.stream().anyMatch(result -> result.ruleId().equals("BODY-LINE")
        && result.judgement() == Judgement.FAIL && result.source().equals(body.source())),
        findings.toString());
    assertTrue(findings.stream().noneMatch(result -> result.ruleId().equals("BODY-INDENT")
        && result.source().equals(caption.source())), findings.toString());
  }

  @Test
  void doesNotReuseCaptionLocationsFromAnotherXmlPart() {
    var chapter = heading("第三章  系统设计",
        new SourceAddress("word/document.xml", List.of(0, 0), 0, 9), null);
    var body = bodyParagraph("图3-1所示的系统由三部分组成。", 1);
    var image = new ImageModel(new SourceAddress("word/footnotes.xml", List.of(0, 0), 0, 0),
        new SourceAddress("word/footnotes.xml", List.of(0, 1), 0, 4),
        "图3-1 系统结构", 100, 100, true, true, true);
    var document = new ParsedDocument("source", List.of(chapter, body), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(image));

    var findings = new BodyFormatRuleChecker(new ParagraphFormatChecker())
        .check(document, RuleSet.schoolV1());

    assertTrue(findings.stream().anyMatch(result -> result.ruleId().equals("BODY-LINE")
        && result.judgement() == Judgement.FAIL && result.source().equals(body.source())),
        findings.toString());
  }

  @Test
  void excludesCompleteStandaloneContinuationMarkersWithoutTableBindings() {
    var chapter = heading("第三章  系统设计",
        new SourceAddress("word/document.xml", List.of(0, 0), 0, 9), null);
    for (String text : List.of("续表4-7", "续表A1")) {
      var marker = bodyParagraph(text, 1);
      var document = new ParsedDocument("source", List.of(chapter, marker), List.of(), List.of());

      var findings = new BodyFormatRuleChecker(new ParagraphFormatChecker())
          .check(document, RuleSet.schoolV1());

      assertTrue(findings.stream().noneMatch(result -> result.ruleId().startsWith("BODY-")
          && result.source().equals(marker.source())), text + ": " + findings);
    }
  }

  @Test
  void checksProseBeginningWithAContinuationTableReference() {
    var chapter = heading("第三章  系统设计",
        new SourceAddress("word/document.xml", List.of(0, 0), 0, 9), null);
    for (String text : List.of("续表4-7列出了后续参数。", "续表4-7 列出了后续参数。",
        "续表A1中的参数用于校验。")) {
      var body = bodyParagraph(text, 1);
      var document = new ParsedDocument("source", List.of(chapter, body), List.of(), List.of());

      var findings = new BodyFormatRuleChecker(new ParagraphFormatChecker())
          .check(document, RuleSet.schoolV1());

      assertTrue(findings.stream().anyMatch(result -> result.ruleId().equals("BODY-LINE")
          && result.judgement() == Judgement.FAIL && result.source().equals(body.source())),
          text + ": " + findings);
    }
  }

  @Test
  void excludesStandaloneCaptionParagraphsWithExplicitCaptionStyles() {
    var chapter = heading("第三章  系统设计",
        new SourceAddress("word/document.xml", List.of(0, 0), 0, 9), null);
    for (String[] style : List.of(new String[] {"25", "Caption"},
        new String[] {"26", "题注"}, new String[] {"Caption", null})) {
      var source = new SourceAddress("word/document.xml", List.of(0, 1), 0, 10);
      var caption = new ParagraphModel(List.of(new RunModel("图3-1 系统结构", "Times New Roman",
          "宋体", null, 21, false, source)), 0, null, null, null, 240, "auto", source,
          0, null, "center", null, null, null, false, null, null, false, false, false, false,
          null, null, style[0], false, false, style[1]);
      var document = new ParsedDocument("source", List.of(chapter, caption), List.of(), List.of());

      var findings = new BodyFormatRuleChecker(new ParagraphFormatChecker())
          .check(document, RuleSet.schoolV1());

      assertTrue(findings.stream().noneMatch(result -> result.ruleId().startsWith("BODY-")
          && result.source().equals(source)), style[0] + " / " + style[1] + ": " + findings);
    }
  }

  @Test
  void checksOrdinaryBodyProseEvenWhenItAccidentallyUsesCaptionStyle() {
    var chapter = heading("第三章  系统设计",
        new SourceAddress("word/document.xml", List.of(0, 0), 0, 9), null);
    var prose = captionStyledParagraph("系统由传感器和控制器组成。", 1);
    var document = new ParsedDocument("source", List.of(chapter, prose), List.of(), List.of());

    var evaluation = new BodyFormatRuleChecker(new ParagraphFormatChecker())
        .evaluate(document, RuleSet.schoolV1());

    for (String ruleId : List.of("BODY-LINE", "BODY-INDENT", "BODY-FONT", "BODY-SIZE")) {
      assertTrue(evaluation.results().stream().anyMatch(result -> result.ruleId().equals(ruleId)
          && result.source().equals(prose.source())), evaluation.results().toString());
      assertEquals(new RuleRun(ruleId, "BodyFormatRuleChecker", 1, 1, 0, null),
          run(evaluation, ruleId));
    }
    assertEquals(new RuleRun("BODY-SCOPE", "BodyFormatRuleChecker", 1, 1, 0, null),
        run(evaluation, "BODY-SCOPE"));
  }

  @Test
  void keepsUnboundCaptionLikeParagraphUnknownRatherThanClaimingFullBodyCoverage() {
    var chapter = heading("第三章  系统设计",
        new SourceAddress("word/document.xml", List.of(0, 0), 0, 9), null);
    var prose = bodyParagraph("正文内容。", 1);
    var uncertainCaption = captionStyledParagraph("图3-1 系统结构", 2);
    var document = new ParsedDocument("source", List.of(chapter, prose, uncertainCaption),
        List.of(), List.of());

    var evaluation = new BodyFormatRuleChecker(new ParagraphFormatChecker())
        .evaluate(document, RuleSet.schoolV1());

    assertTrue(evaluation.results().stream().noneMatch(result -> result.ruleId().startsWith("BODY-")
        && result.source().equals(uncertainCaption.source())), evaluation.results().toString());
    for (String ruleId : List.of("BODY-LINE", "BODY-INDENT", "BODY-FONT", "BODY-SIZE")) {
      assertEquals(new RuleRun(ruleId, "BodyFormatRuleChecker", 2, 1, 1, null),
          run(evaluation, ruleId));
    }
    assertEquals(new RuleRun("BODY-SCOPE", "BodyFormatRuleChecker", 1, 0, 1, null),
        run(evaluation, "BODY-SCOPE"));
    assertTrue(evaluation.ruleRuns().stream().noneMatch(item -> item.ruleId().startsWith("HEAD-")
        || item.ruleId().startsWith("HEADING-")));
  }

  @Test
  void excludesBoundCaptionStyleWithoutAddingUnknownBodyCandidates() {
    var chapter = heading("第三章  系统设计",
        new SourceAddress("word/document.xml", List.of(0, 0), 0, 9), null);
    var prose = bodyParagraph("正文内容。", 1);
    var caption = captionStyledParagraph("图3-1 系统结构", 3);
    var image = new ImageModel(new SourceAddress("word/document.xml", List.of(0, 2), 0, 0),
        caption.source(), caption.runs().getFirst().text(), 100, 100, true, true, true);
    var document = new ParsedDocument("source", List.of(chapter, prose, caption),
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(image));

    var evaluation = new BodyFormatRuleChecker(new ParagraphFormatChecker())
        .evaluate(document, RuleSet.schoolV1());

    for (String ruleId : List.of("BODY-LINE", "BODY-INDENT", "BODY-FONT", "BODY-SIZE")) {
      assertEquals(new RuleRun(ruleId, "BodyFormatRuleChecker", 1, 1, 0, null),
          run(evaluation, ruleId));
    }
    assertEquals(new RuleRun("BODY-SCOPE", "BodyFormatRuleChecker", 1, 1, 0, null),
        run(evaluation, "BODY-SCOPE"));
    assertTrue(evaluation.results().stream().noneMatch(result -> result.ruleId().startsWith("BODY-")
        && result.source().equals(caption.source())), evaluation.results().toString());
  }

  @Test
  void doesNotExemptBodyStylesThatOnlyContainTheCaptionName() {
    var chapter = heading("第三章  系统设计",
        new SourceAddress("word/document.xml", List.of(0, 0), 0, 9), null);
    for (String style : List.of("Caption reference", "BodyCaption", "题注引用")) {
      var source = new SourceAddress("word/document.xml", List.of(0, 1), 0, 17);
      var body = new ParagraphModel(List.of(new RunModel("图3-1所示的系统由三部分组成。", "Times New Roman",
          "宋体", null, 24, false, source)), 0, null, null, null, 240, "auto", source,
          0, null, "left", null, null, null, false, null, null, false, false, false, false,
          null, null, style, false, false, style);
      var document = new ParsedDocument("source", List.of(chapter, body), List.of(), List.of());

      var findings = new BodyFormatRuleChecker(new ParagraphFormatChecker())
          .check(document, RuleSet.schoolV1());

      assertTrue(findings.stream().anyMatch(result -> result.ruleId().equals("BODY-LINE")
          && result.judgement() == Judgement.FAIL && result.source().equals(source)),
          style + ": " + findings);
    }
  }

  @Test
  void checksFontSizeAndIndentOnCodeLikeBodyParagraphs() {
    var chapterSource = new SourceAddress("word/document.xml", List.of(0, 0), 0, 6);
    var chapter = new ParagraphModel(List.of(new RunModel("第一章 绪论", "Times New Roman", "黑体",
        null, 32, true, chapterSource)), null, null, null, null, 360, "auto", chapterSource,
        null, 0, "center");
    var source = new SourceAddress("word/document.xml", List.of(0, 1), 0, 14);
    var code = new ParagraphModel(List.of(new RunModel("int value = 1;", "Arial", "宋体",
        null, 18, false, source)), 0, null, null, null, 360, "auto", source, 0, null, "left");
    var document = new ParsedDocument("source", List.of(chapter, code), List.of(), List.of());

    var findings = new BodyFormatRuleChecker(new ParagraphFormatChecker())
        .check(document, RuleSet.schoolV1());

    assertTrue(findings.stream().anyMatch(result -> result.ruleId().equals("BODY-INDENT")
        && result.source().equals(source)), findings.toString());
    assertTrue(findings.stream().anyMatch(result -> result.ruleId().equals("BODY-FONT")
        && result.source().equals(source)), findings.toString());
    assertTrue(findings.stream().anyMatch(result -> result.ruleId().equals("BODY-SIZE")
        && result.source().equals(source)), findings.toString());
  }

  @Test
  void leavesAnUnnumberedConclusionHeadingToTheHeadingModule() {
    var chapterSource = new SourceAddress("word/document.xml", List.of(0, 0), 0, 6);
    var chapter = heading("第一章 绪论", chapterSource, null);
    var conclusionSource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 5);
    var conclusion = new ParagraphModel(List.of(new RunModel("结论与展望", "Arial", "宋体",
        null, 18, false, conclusionSource)), 0, null, null, null, 360, "auto", conclusionSource,
        0, 0, "left");
    var document = new ParsedDocument("source", List.of(chapter, conclusion), List.of(), List.of());

    var findings = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(findings.stream().noneMatch(result -> result.source().equals(conclusionSource)
        && result.ruleId().startsWith("BODY-")), findings.toString());
  }

  @Test
  void doesNotApplyHeadingOrBodyFormattingToConclusionWithoutStructureEvidence() {
    var chapterSource = new SourceAddress("word/document.xml", List.of(0, 0), 0, 6);
    var chapter = heading("第一章 绪论", chapterSource, null);
    var blankSource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 0);
    var blank = new ParagraphModel(List.of(new RunModel("", "宋体", "宋体", null,
        24, false, blankSource)), 0, null, null, null, 360, "auto", blankSource,
        0, null, "left");
    var conclusionSource = new SourceAddress("word/document.xml", List.of(0, 2), 0, 5);
    var conclusion = new ParagraphModel(List.of(new RunModel("结论与展望", "Arial", "宋体",
        null, 18, false, conclusionSource)), 0, null, null, null, 360, "auto",
        conclusionSource, null, null, "left");
    var document = new ParsedDocument("source", List.of(chapter, blank, conclusion), List.of(), List.of());

    var findings = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(findings.stream().noneMatch(result -> result.source().equals(conclusionSource)
        && (result.ruleId().startsWith("BODY-") || result.ruleId().startsWith("HEADING-")
            || result.ruleId().startsWith("HEAD-"))), findings.toString());
  }

  @Test
  void treatsConclusionWithTitleStyleAndOutlineEvidenceAsAHeading() {
    var chapterSource = new SourceAddress("word/document.xml", List.of(0, 0), 0, 6);
    var chapter = heading("第一章 绪论", chapterSource, null);
    var conclusionSource = new SourceAddress("word/document.xml", List.of(0, 2), 0, 2);
    var conclusion = new ParagraphModel(List.of(new RunModel("结论", "Cambria", "黑体",
        null, 32, true, conclusionSource)), null, null, null, null, 360, "auto",
        conclusionSource, null, 0, "center", null, null, null, false, null, null,
        true, false, false, false, null, null, "11", false, false, "Title");
    var document = new ParsedDocument("source", List.of(chapter, conclusion), List.of(), List.of());

    var findings = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(findings.stream().noneMatch(result -> result.source().equals(conclusionSource)
        && result.ruleId().startsWith("BODY-")), findings.toString());
  }

  @Test
  void checksNumberBeginningProseInsteadOfTreatingItAsAHeading() {
    var chapterSource = new SourceAddress("word/document.xml", List.of(0, 0), 0, 6);
    var chapter = heading("第一章 绪论", chapterSource, null);
    var bodySource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 14);
    var body = new ParagraphModel(List.of(new RunModel("2024年数据显示", "Arial", "宋体",
        null, 18, false, bodySource)), 0, null, null, null, 360, "auto", bodySource,
        0, null, "left");
    var document = new ParsedDocument("source", List.of(chapter, body), List.of(), List.of());

    var findings = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(findings.stream().anyMatch(result -> result.ruleId().equals("BODY-INDENT")
        && result.source().equals(bodySource)), findings.toString());
    assertTrue(findings.stream().anyMatch(result -> result.ruleId().equals("BODY-FONT")
        && result.source().equals(bodySource)), findings.toString());
    assertTrue(findings.stream().anyMatch(result -> result.ruleId().equals("BODY-SIZE")
        && result.source().equals(bodySource)), findings.toString());
  }

  @Test
  void checksYearMonthVersionTextAsBodyInsteadOfTreatingItAsAHeading() {
    var chapterSource = new SourceAddress("word/document.xml", List.of(0, 0), 0, 6);
    var chapter = heading("第一章 绪论", chapterSource, null);
    var bodySource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 12);
    var body = new ParagraphModel(List.of(new RunModel("2024.10版本更新", "Arial", "宋体",
        null, 18, false, bodySource)), 0, null, null, null, 360, "auto", bodySource,
        0, null, "left");
    var document = new ParsedDocument("source", List.of(chapter, body), List.of(), List.of());

    var findings = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(findings.stream().anyMatch(result -> result.ruleId().equals("BODY-INDENT")
        && result.source().equals(bodySource)), findings.toString());
    assertTrue(findings.stream().noneMatch(result -> result.source().equals(bodySource)
        && result.ruleId().startsWith("HEADING-")), findings.toString());
  }

  @Test
  void leavesReferenceCitationTypographyToTheCitationRule() {
    var chapterSource = new SourceAddress("word/document.xml", List.of(0, 0), 0, 6);
    var chapter = heading("第一章 绪论", chapterSource, null);
    var bodySource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 6);
    var body = new ParagraphModel(List.of(
        new RunModel("正文", "Times New Roman", "Times New Roman", "宋体", null,
            24, null, false, null, new SourceAddress("word/document.xml", List.of(0, 1), 0, 2)),
        new RunModel("[", "Times New Roman", "Times New Roman", "Times New Roman", null,
            15, null, false, "superscript", new SourceAddress("word/document.xml", List.of(0, 1), 2, 3)),
        new RunModel("1", "Times New Roman", "Times New Roman", "Times New Roman", null,
            15, null, false, "superscript", new SourceAddress("word/document.xml", List.of(0, 1), 3, 4)),
        new RunModel("]", "Times New Roman", "Times New Roman", "Times New Roman", null,
            15, null, false, "superscript", new SourceAddress("word/document.xml", List.of(0, 1), 4, 5)),
        new RunModel("。", "Times New Roman", "Times New Roman", "宋体", null,
            24, null, false, null, new SourceAddress("word/document.xml", List.of(0, 1), 5, 6))),
        480, null, null, null, 360, "auto", bodySource, null, null, "left");
    var document = new ParsedDocument("source", List.of(chapter, body), List.of(), List.of());

    var bodyEvaluation = new BodyFormatRuleChecker(new ParagraphFormatChecker())
        .evaluate(document, RuleSet.schoolV1());
    var bodyFindings = bodyEvaluation.results();
    var citationFindings = new CitationRuleModule(new HeadingRuleChecker(new ParagraphFormatChecker()))
        .check(document, RuleSet.schoolV1());

    assertTrue(bodyFindings.stream().noneMatch(result -> result.ruleId().equals("BODY-SIZE")
        && result.source().start() >= 2 && result.source().end() <= 5), bodyFindings.toString());
    assertTrue(bodyFindings.stream().anyMatch(result -> result.ruleId().equals("BODY-SIZE")
        && result.source().end() <= 2), bodyFindings.toString());
    // The three citation runs are owned by CITE-01, not BODY-FONT or BODY-SIZE.
    assertEquals(new RuleRun("BODY-FONT", "BodyFormatRuleChecker", 2, 2, 0, null),
        run(bodyEvaluation, "BODY-FONT"));
    assertEquals(new RuleRun("BODY-SIZE", "BodyFormatRuleChecker", 2, 2, 0, null),
        run(bodyEvaluation, "BODY-SIZE"));
    assertTrue(citationFindings.stream().anyMatch(result -> result.ruleId().equals("CITE-01")
        && result.reasonCode().equals("CITATION_FORMAT_MISMATCH")), citationFindings.toString());
  }

  @Test
  void tableTextNamedReferencesDoesNotEndTheMainBody() {
    var chapterSource = new SourceAddress("word/document.xml", List.of(0, 0), 0, 6);
    var chapter = heading("第一章 绪论", chapterSource, null);
    var tableSource = new SourceAddress("word/document.xml", List.of(0, 1, 0, 0), 0, 4);
    var tableParagraph = new ParagraphModel(List.of(new RunModel("参考文献", null, null, null,
        24, false, tableSource)), null, null, null, null, null, null, tableSource, null, null, null);
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(0, 1), 0, 0),
        null, null, 1, 1, false, false, false, false, false, false);
    var bodySource = new SourceAddress("word/document.xml", List.of(0, 2), 0, 4);
    var body = new ParagraphModel(List.of(new RunModel("正文内容", "Arial", "宋体",
        null, 18, false, bodySource)), 200, null, null, null, 360, "auto", bodySource,
        200, null, "left");
    var document = new ParsedDocument("source", List.of(chapter, tableParagraph, body),
        List.of(), List.of(), List.of(table), List.of(), List.of(), List.of());

    var findings = new BodyFormatRuleChecker(new ParagraphFormatChecker())
        .check(document, RuleSet.schoolV1());

    assertTrue(findings.stream().anyMatch(result -> result.ruleId().equals("BODY-LINE")
        && result.source().equals(bodySource)), findings.toString());
  }

  @Test
  void skipsUnnumberedIntroductionTitleButChecksFollowingBodyText() {
    var introductionSource = new SourceAddress("word/document.xml", List.of(0, 0), 0, 2);
    var introduction = new ParagraphModel(List.of(new RunModel("绪论", "Arial", "宋体",
        null, 32, true, introductionSource)), 0, null, null, null, 360, "auto",
        introductionSource, 0, 0, "center");
    var bodySource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 4);
    var body = new ParagraphModel(List.of(new RunModel("正文内容", "Arial", "宋体",
        null, 24, false, bodySource)), 200, null, null, null, 360, "auto",
        bodySource, 200, null, "left");
    var document = new ParsedDocument("source", List.of(introduction, body), List.of(), List.of());

    var findings = new BodyFormatRuleChecker(new ParagraphFormatChecker())
        .check(document, RuleSet.schoolV1());

    assertTrue(findings.stream().noneMatch(result -> result.source().equals(introductionSource)
        && result.ruleId().startsWith("BODY-")), findings.toString());
    assertTrue(findings.stream().anyMatch(result -> result.ruleId().equals("BODY-LINE")
        && result.source().equals(bodySource)), findings.toString());
  }

  @Test
  void stopsBodyFormattingAtTheStandaloneAppendixTitle() {
    var chapterSource = new SourceAddress("word/document.xml", List.of(0, 0), 0, 6);
    var chapter = heading("第一章 绪论", chapterSource, null);
    var bodySource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 4);
    var body = new ParagraphModel(List.of(new RunModel("正文内容", "Times New Roman", "宋体",
        null, 24, false, bodySource)), 480, null, null, null, 360, "auto", bodySource,
        null, null, "left");
    var appendixTitleSource = new SourceAddress("word/document.xml", List.of(0, 2), 0, 6);
    var appendixTitle = new ParagraphModel(List.of(new RunModel("附    录", "Arial", "宋体",
        null, 24, false, appendixTitleSource)), 0, null, null, null, 240, "auto",
        appendixTitleSource, null, null, "left");
    var appendixBodySource = new SourceAddress("word/document.xml", List.of(0, 3), 0, 5);
    var appendixBody = new ParagraphModel(List.of(new RunModel("附录内容", "Arial", "宋体",
        null, 24, false, appendixBodySource)), 0, null, null, null, 240, "auto",
        appendixBodySource, null, null, "left");
    var document = new ParsedDocument("source", List.of(chapter, body, appendixTitle, appendixBody),
        List.of(), List.of());

    var findings = new BodyFormatRuleChecker(new ParagraphFormatChecker())
        .check(document, RuleSet.schoolV1());

    assertTrue(findings.stream().anyMatch(result -> result.ruleId().equals("BODY-LINE")
        && result.source().equals(bodySource)), findings.toString());
    assertTrue(findings.stream().noneMatch(result -> result.ruleId().startsWith("BODY-")
        && (result.source().equals(appendixTitleSource) || result.source().equals(appendixBodySource))),
        findings.toString());
  }

  @Test
  void reportsReviewWhenThesisSignalsExistButTheMainBodyBoundaryCannotBeFound() {
    var titleSource = new SourceAddress("word/document.xml", List.of(0, 0), 0, 6);
    var title = new ParagraphModel(List.of(new RunModel("摘要", "黑体", "黑体",
        null, 32, true, titleSource)), 0, null, null, null, 360, "auto", titleSource,
        0, null, "center");
    var body = new ParagraphModel(List.of(new RunModel("摘要内容", "Arial", "宋体",
        null, 24, false, new SourceAddress("word/document.xml", List.of(0, 1), 0, 4))),
        200, null, null, null, 360, "auto", new SourceAddress("word/document.xml", List.of(0, 1), 0, 4),
        200, null, "left");

    var findings = new BodyFormatRuleChecker(new ParagraphFormatChecker())
        .check(new ParsedDocument("source", List.of(title, body), List.of(), List.of()), RuleSet.schoolV1());

    assertTrue(findings.stream().anyMatch(result -> result.ruleId().equals("BODY-SCOPE")
        && result.judgement() == Judgement.REVIEW
        && result.reasonCode().equals("BODY_REGION_UNDETERMINED")), findings.toString());
  }

  @Test
  void recordsCandidatesOnlyForActuallyVisitedBodyParagraphsAndRuns() {
    var chapter = heading("第一章 绪论",
        new SourceAddress("word/document.xml", List.of(0, 0), 0, 6), null);
    var body1 = bodyParagraph("正文内容一", 1);
    var body2 = bodyParagraph("正文内容二", 2);
    var document = new ParsedDocument("source", List.of(chapter, body1, body2), List.of(), List.of());
    var checker = new BodyFormatRuleChecker(new ParagraphFormatChecker());

    var evaluation = checker.evaluate(document, RuleSet.schoolV1());

    assertEquals(checker.check(document, RuleSet.schoolV1()), evaluation.results());
    for (String ruleId : List.of("BODY-FONT", "BODY-SIZE", "BODY-INDENT", "BODY-LINE")) {
      assertEquals(new RuleRun(ruleId, "BodyFormatRuleChecker", 2, 2, 0, null),
          run(evaluation, ruleId));
    }
    assertEquals(new RuleRun("BODY-SCOPE", "BodyFormatRuleChecker", 1, 1, 0, null),
        run(evaluation, "BODY-SCOPE"));
    for (String ruleId : List.of("HEAD-EMBEDDED", "HEADING-FONT", "HEADING-SIZE",
        "HEAD-02-SPACING", "HEAD-03-SPACING")) {
      assertEquals(new RuleRun(ruleId, "BodyFormatRuleChecker", 0, 0, 0,
          "NO_EMBEDDED_HEADING_IN_RESOLVED_BODY"), run(evaluation, ruleId));
    }
  }

  @Test
  void leavesEmbeddedHeadingAbsenceUnprovedWhenNumberedSuffixWasNotRecognized() {
    var chapter = heading("第二章 系统设计",
        new SourceAddress("word/document.xml", List.of(0, 0), 0, 8), null);
    var possibleHeading = joinedHeading("2.1 子标题", 1, 28, null);
    var document = new ParsedDocument("source", List.of(chapter, possibleHeading),
        List.of(), List.of());

    var evaluation = new BodyFormatRuleChecker(new ParagraphFormatChecker())
        .evaluate(document, RuleSet.schoolV1());

    assertTrue(evaluation.results().stream().noneMatch(result -> result.ruleId().equals("HEAD-EMBEDDED")));
    assertTrue(evaluation.ruleRuns().stream().noneMatch(item -> item.ruleId().startsWith("HEAD-")
        || item.ruleId().startsWith("HEADING-")));
  }

  @Test
  void recordsOnlyActuallyDetectedEmbeddedHeadingCandidates() {
    var toc2 = contentsEntry("2.1 子标题\t5", 0);
    var toc3 = contentsEntry("2.1.1 小节标题\t5", 1);
    var chapter = heading("第二章 系统设计",
        new SourceAddress("word/document.xml", List.of(0, 2), 0, 8), null);
    var second = joinedHeading("2.1 子标题", 3, 28, null);
    var third = joinedHeading("2.1.1 小节标题", 4, 24, "Times New Roman");
    var document = new ParsedDocument("source", List.of(toc2, toc3, chapter, second, third),
        List.of(), List.of());

    var evaluation = new BodyFormatRuleChecker(new ParagraphFormatChecker())
        .evaluate(document, RuleSet.schoolV1());

    assertEquals(2, evaluation.results().stream()
        .filter(result -> result.ruleId().equals("HEAD-EMBEDDED")).count(),
        evaluation.results().toString());
    assertEquals(new RuleRun("HEAD-EMBEDDED", "BodyFormatRuleChecker", 2, 2, 0, null),
        run(evaluation, "HEAD-EMBEDDED"));
    assertEquals(new RuleRun("HEADING-FONT", "BodyFormatRuleChecker", 2, 1, 1, null),
        run(evaluation, "HEADING-FONT"));
    assertEquals(new RuleRun("HEADING-SIZE", "BodyFormatRuleChecker", 2, 2, 0, null),
        run(evaluation, "HEADING-SIZE"));
    assertEquals(new RuleRun("HEAD-02-SPACING", "BodyFormatRuleChecker", 1, 1, 0, null),
        run(evaluation, "HEAD-02-SPACING"));
    assertEquals(new RuleRun("HEAD-03-SPACING", "BodyFormatRuleChecker", 1, 1, 0, null),
        run(evaluation, "HEAD-03-SPACING"));
    assertTrue(evaluation.results().stream().anyMatch(result -> result.ruleId().equals("HEADING-FONT")
        && result.judgement() == Judgement.REVIEW));
  }

  @Test
  void provesOnlyTheMissingEmbeddedHeadingLevelWhenAnotherLevelWasChecked() {
    var toc2 = contentsEntry("2.1 子标题\t5", 0);
    var chapter = heading("第二章 系统设计",
        new SourceAddress("word/document.xml", List.of(0, 1), 0, 8), null);
    var second = joinedHeading("2.1 子标题", 2, 28, "Times New Roman");
    var document = new ParsedDocument("source", List.of(toc2, chapter, second),
        List.of(), List.of());

    var evaluation = new BodyFormatRuleChecker(new ParagraphFormatChecker())
        .evaluate(document, RuleSet.schoolV1());

    assertEquals(new RuleRun("HEAD-EMBEDDED", "BodyFormatRuleChecker", 1, 1, 0, null),
        run(evaluation, "HEAD-EMBEDDED"));
    assertEquals(new RuleRun("HEAD-02-SPACING", "BodyFormatRuleChecker", 1, 1, 0, null),
        run(evaluation, "HEAD-02-SPACING"));
    assertEquals(new RuleRun("HEAD-03-SPACING", "BodyFormatRuleChecker", 0, 0, 0,
        "NO_EMBEDDED_HEADING_IN_RESOLVED_BODY"), run(evaluation, "HEAD-03-SPACING"));
  }

  @Test
  void preservesUnknownCandidatesWhenFormatEvidenceIsUnresolved() {
    var chapter = heading("第一章 绪论",
        new SourceAddress("word/document.xml", List.of(0, 0), 0, 6), null);
    var source = new SourceAddress("word/document.xml", List.of(0, 1), 0, 4);
    var body = new ParagraphModel(List.of(new RunModel("正文内容", null, null,
        null, null, false, source)), 480, null, null, null, null, "auto", source,
        null, null, "left");
    var document = new ParsedDocument("source", List.of(chapter, body), List.of(), List.of());

    var evaluation = new BodyFormatRuleChecker(new ParagraphFormatChecker())
        .evaluate(document, RuleSet.schoolV1());

    for (String ruleId : List.of("BODY-FONT", "BODY-SIZE", "BODY-LINE")) {
      assertEquals(new RuleRun(ruleId, "BodyFormatRuleChecker", 1, 0, 1, null),
          run(evaluation, ruleId));
    }
    assertEquals(new RuleRun("BODY-INDENT", "BodyFormatRuleChecker", 1, 1, 0, null),
        run(evaluation, "BODY-INDENT"));
  }

  @Test
  void leavesBodyFormattingUnprovedWhenBodyBoundaryIsUnknown() {
    var title = heading("摘 要",
        new SourceAddress("word/document.xml", List.of(0, 0), 0, 3), null);
    var abstractText = bodyParagraph("摘要内容", 1);
    var document = new ParsedDocument("source", List.of(title, abstractText), List.of(), List.of());

    var evaluation = new BodyFormatRuleChecker(new ParagraphFormatChecker())
        .evaluate(document, RuleSet.schoolV1());

    assertEquals(List.of(new RuleRun("BODY-SCOPE", "BodyFormatRuleChecker", 1, 0, 1, null)),
        evaluation.ruleRuns());
    assertTrue(evaluation.results().stream().anyMatch(result -> result.ruleId().equals("BODY-SCOPE")
        && result.judgement() == Judgement.REVIEW));
  }

  @Test
  void doesNotInventACompleteEmptyBodyFromAnEmptyDocument() {
    var document = new ParsedDocument("source", List.of(), List.of(), List.of());

    var evaluation = new BodyFormatRuleChecker(new ParagraphFormatChecker())
        .evaluate(document, RuleSet.schoolV1());

    assertTrue(evaluation.ruleRuns().isEmpty());
  }

  private static RuleRun run(RuleModule.Evaluation evaluation, String ruleId) {
    return evaluation.ruleRuns().stream().filter(item -> item.ruleId().equals(ruleId))
        .findFirst().orElseThrow();
  }

  private static ParagraphModel contentsEntry(String text, int blockIndex) {
    var source = new SourceAddress("word/document.xml", List.of(0, blockIndex), 0, text.length());
    return new ParagraphModel(List.of(new RunModel(text, "Times New Roman", "宋体", null,
        24, false, source)), 0, null, null, null, 360, "auto", source,
        null, null, "left", null, null, null, true);
  }

  private static ParagraphModel joinedHeading(String title, int blockIndex, int size, String asciiFont) {
    String prose = "正文内容。";
    var source = new SourceAddress("word/document.xml", List.of(0, blockIndex),
        0, prose.length() + title.length());
    var proseSource = new SourceAddress("word/document.xml", List.of(0, blockIndex), 0, prose.length());
    var titleSource = new SourceAddress("word/document.xml", List.of(0, blockIndex),
        prose.length(), prose.length() + title.length());
    return new ParagraphModel(List.of(
        new RunModel(prose, "Times New Roman", "宋体", null, 24, false, proseSource),
        new RunModel(title, asciiFont, "黑体", null, size, false, titleSource)),
        480, null, null, null, 360, "auto", source, null, null, "left");
  }

  private static ParagraphModel captionStyledParagraph(String text, int blockIndex) {
    var source = new SourceAddress("word/document.xml", List.of(0, blockIndex), 0, text.length());
    return new ParagraphModel(List.of(new RunModel(text, "Times New Roman", "宋体", null,
        24, false, source)), 0, null, null, null, 240, "auto", source,
        0, null, "left", null, null, null, false, null, null, false, false, false, false,
        null, null, "Caption", false, false, "Caption");
  }

  private static ParagraphModel bodyParagraph(String text, int blockIndex) {
    var source = new SourceAddress("word/document.xml", List.of(0, blockIndex), 0, text.length());
    return new ParagraphModel(List.of(new RunModel(text, "Times New Roman", "宋体", null,
        24, false, source)), 0, null, null, null, 240, "auto", source, 0, null, "left");
  }

  private static ParagraphModel heading(String text, SourceAddress source, String numberingLabel) {
    return new ParagraphModel(List.of(new RunModel(text, "Times New Roman", "黑体", null,
        32, true, source)), null, null, null, null, 360, "auto", source, null, 0,
        "center", numberingLabel);
  }
}
