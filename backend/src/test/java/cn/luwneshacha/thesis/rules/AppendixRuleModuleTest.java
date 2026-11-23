package cn.luwneshacha.thesis.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.luwneshacha.thesis.document.EquationModel;
import cn.luwneshacha.thesis.document.ImageModel;
import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.RunModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import cn.luwneshacha.thesis.document.TableModel;
import cn.luwneshacha.thesis.document.TableParser;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class AppendixRuleModuleTest {
  private static final String NO_APPENDIX_REASON = "OPTIONAL_APPENDIX_ABSENT_AFTER_END_MATTER_SCAN";

  @Test
  void recordsNotApplicableOnlyAfterScanningCompleteEndMatterWithoutAnAppendix() {
    var evaluation = new AppendixRuleModule().evaluate(completeDocumentWithoutAppendix(), RuleSet.schoolV1());

    for (String ruleId : List.of("APP-01", "APP-02-BLANK", "APP-03"))
      assertEquals(new RuleRun(ruleId, "AppendixRuleModule", 0, 0, 0, NO_APPENDIX_REASON),
          run(evaluation, ruleId));
    assertTrue(evaluation.results().isEmpty());
  }

  @Test
  void doesNotClaimNoAppendixWhenAnAppendixHeadingIsMalformed() {
    var document = new ParsedDocument("sha", List.of(
        paragraph("\u7b2c\u4e00\u7ae0 \u7eea\u8bba", List.of(0, 1)),
        paragraph("\u53c2\u8003\u6587\u732e", List.of(0, 2)),
        paragraph("\u81f4\u8c22", List.of(0, 3)),
        paragraph("\u9644\u5f55a \u8865\u5145\u6750\u6599", List.of(0, 4))),
        List.of(), List.of());

    var evaluation = new AppendixRuleModule().evaluate(document, RuleSet.schoolV1());

    assertTrue(evaluation.ruleRuns().stream().noneMatch(run -> NO_APPENDIX_REASON.equals(
        run.notApplicableReason())));
  }

  @Test
  void doesNotClaimNoAppendixWithUnresolvedFloatingContent() {
    var original = completeDocumentWithoutAppendix();
    var floating = new ImageModel(new SourceAddress("word/document.xml", List.of(0, 5), 0, 0),
        null, null, 100, 100, false, null, null);
    var document = new ParsedDocument(original.sourceSha256(), original.paragraphs(),
        original.sections(), original.diagnostics(), List.of(), List.of(), List.of(),
        List.of(floating));

    var evaluation = new AppendixRuleModule().evaluate(document, RuleSet.schoolV1());

    assertTrue(evaluation.ruleRuns().stream().noneMatch(run -> NO_APPENDIX_REASON.equals(
        run.notApplicableReason())));
  }

  @Test
  void doesNotClaimNoAppendixWhenTableFloatingEvidenceIsUnavailable() {
    var original = completeDocumentWithoutAppendix();
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(0, 2, 0), 0, 0),
        null, null, 2, 2, true, true, true, false, false, false);
    var document = new ParsedDocument(original.sourceSha256(), original.paragraphs(),
        original.sections(), original.diagnostics(), List.of(table), List.of(), List.of(), List.of());

    var evaluation = new AppendixRuleModule().evaluate(document, RuleSet.schoolV1());

    assertTrue(evaluation.ruleRuns().stream().noneMatch(run -> NO_APPENDIX_REASON.equals(
        run.notApplicableReason())));
  }

  @Test
  void doesNotClaimNoAppendixForAnUnreconciledSyntheticDocument() {
    var original = completeDocumentWithoutAppendix();
    var document = new ParsedDocument(original.sourceSha256(), original.paragraphs(),
        original.sections(), original.diagnostics(), original.tables(), original.equations(),
        original.references(), original.images(), original.pageNumbers(), original.chapterBreaks(),
        original.headers(), original.automaticNumbering());

    assertFalse(document.tableInventoryComplete());
    assertFalse(AppendixRuleModule.hasProvenOptionalAppendixAbsent(document));
  }

  @Test
  void doesNotClaimNoAppendixForFloatingNestedOrTextBoxTablesEvenWithReconciledInventory() {
    String cell = "<w:tr><w:tc><w:p/></w:tc></w:tr>";
    TableModel floating = parsedTable("<w:tbl><w:tblPr><w:tblpPr w:tblpX=\"0\"/></w:tblPr>"
        + cell + "</w:tbl>", 0);
    TableModel nested = parsedTable("<w:tbl>" + cell
        + "<w:tr><w:tc><w:p/><w:tbl>" + cell + "</w:tbl></w:tc></w:tr></w:tbl>", 1);
    TableModel inTextBox = parsedTable("<w:p><w:r><w:drawing><w:txbxContent><w:tbl>"
        + cell + "</w:tbl></w:txbxContent></w:drawing></w:r></w:p>", 0);

    assertEquals(TableModel.FlowPosition.FLOATING, floating.flowPosition());
    assertEquals(TableModel.FlowPosition.UNKNOWN, nested.flowPosition());
    assertEquals(TableModel.FlowPosition.UNKNOWN, inTextBox.flowPosition());
    assertFalse(AppendixRuleModule.hasProvenOptionalAppendixAbsent(withVerifiedTable(floating)));
    assertFalse(AppendixRuleModule.hasProvenOptionalAppendixAbsent(withVerifiedTable(nested)));
    assertFalse(AppendixRuleModule.hasProvenOptionalAppendixAbsent(withVerifiedTable(inTextBox)));
  }

  @Test
  void doesNotClaimNoAppendixWhenAnInlineTableFollowsAcknowledgements() {
    String cell = "<w:tr><w:tc><w:p/></w:tc></w:tr>";
    TableModel table = parsedTable("<w:p/><w:p/><w:p/><w:tbl>" + cell + "</w:tbl>", 0);

    assertEquals(TableModel.FlowPosition.INLINE_BODY, table.flowPosition());
    assertFalse(AppendixRuleModule.hasProvenOptionalAppendixAbsent(withVerifiedTable(table)));
  }

  @Test
  void doesNotClaimNoAppendixWhenTheParserReportedAContentGap() {
    var original = completeDocumentWithoutAppendix();
    var document = new ParsedDocument(original.sourceSha256(), original.paragraphs(),
        original.sections(), List.of("unparsed document content"));

    var evaluation = new AppendixRuleModule().evaluate(document, RuleSet.schoolV1());

    assertTrue(evaluation.ruleRuns().stream().noneMatch(run -> NO_APPENDIX_REASON.equals(
        run.notApplicableReason())));
  }

  @Test
  void doesNotClaimNoAppendixFromOutOfOrderEndMatterEvidence() {
    var document = new ParsedDocument("sha", List.of(
        paragraph("\u7b2c\u4e00\u7ae0 \u7eea\u8bba", List.of(0, 1)),
        paragraph("\u53c2\u8003\u6587\u732e", List.of(0, 4)),
        paragraph("\u81f4\u8c22", List.of(0, 3))), List.of(), List.of());

    var evaluation = new AppendixRuleModule().evaluate(document, RuleSet.schoolV1());

    assertTrue(evaluation.ruleRuns().stream().noneMatch(run -> NO_APPENDIX_REASON.equals(
        run.notApplicableReason())));
  }

  @Test
  void doesNotInventAppendixEvidenceForADocumentWithoutAnAppendix() {
    var document = new ParsedDocument("sha", List.of(paragraph("正文内容", List.of(0, 1))),
        List.of(), List.of());

    var evaluation = new AppendixRuleModule().evaluate(document, RuleSet.schoolV1());

    assertTrue(evaluation.ruleRuns().isEmpty());
  }

  private static ParsedDocument completeDocumentWithoutAppendix() {
    return new ParsedDocument("sha", List.of(
        paragraph("\u7b2c\u4e00\u7ae0 \u7eea\u8bba", List.of(0, 1)),
        paragraph("\u53c2\u8003\u6587\u732e", List.of(0, 2)),
        paragraph("\u81f4\u8c22", List.of(0, 3)),
        paragraph("\u611f\u8c22\u6307\u5bfc\u8001\u5e08", List.of(0, 4))),
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of(), List.of(), true);
  }

  private static TableModel parsedTable(String bodyXml, int tableIndex) {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + bodyXml + "</w:body></w:document>";
    return new TableParser().parse(xml.getBytes(StandardCharsets.UTF_8)).get(tableIndex);
  }

  private static ParsedDocument withVerifiedTable(TableModel table) {
    var original = completeDocumentWithoutAppendix();
    return new ParsedDocument(original.sourceSha256(), original.paragraphs(), original.sections(),
        original.diagnostics(), List.of(table), original.equations(), original.references(),
        original.images(), original.pageNumbers(), original.chapterBreaks(), original.headers(),
        original.automaticNumbering(), true);
  }

  @Test
  void recordsBlankLineAndExplicitPageBreakCandidatesForAnAppendixTitle() {
    var title = paragraph("附    录", List.of(0, 1));
    var blank = paragraph("", List.of(0, 2));
    var body = paragraph("附录A 名词术语及缩略词", List.of(0, 3));
    var document = new ParsedDocument("sha", List.of(title, blank, body), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of(new cn.luwneshacha.thesis.document.HeadingBreakModel(title.source(), titleText(title), true)));

    var evaluation = new AppendixRuleModule().evaluate(document, RuleSet.schoolV1());

    assertEquals(new RuleRun("APP-02-BLANK", "AppendixRuleModule", 1, 1, 0, null),
        run(evaluation, "APP-02-BLANK"));
    assertEquals(new RuleRun("APP-03", "AppendixRuleModule", 1, 1, 0, null),
        run(evaluation, "APP-03"));
    assertTrue(evaluation.ruleRuns().stream().noneMatch(run -> NO_APPENDIX_REASON.equals(
        run.notApplicableReason())));
  }

  @Test
  void keepsMissingLayoutAndDocumentEndAsUnknown() {
    var title = paragraph("附    录", List.of(0, 1));
    var document = new ParsedDocument("sha", List.of(title), List.of(), List.of());

    var evaluation = new AppendixRuleModule().evaluate(document, RuleSet.schoolV1());

    assertEquals(new RuleRun("APP-02-BLANK", "AppendixRuleModule", 1, 0, 1, null),
        run(evaluation, "APP-02-BLANK"));
    assertEquals(new RuleRun("APP-03", "AppendixRuleModule", 1, 0, 1, null),
        run(evaluation, "APP-03"));
  }

  @Test
  void failsWhenMainAppendixTitleIsNotFollowedByABlankParagraph() {
    var title = paragraph("附    录", List.of(0, 1));
    var body = paragraph("附录A 名词术语及缩略词", List.of(0, 2));
    var document = new ParsedDocument("sha", List.of(title, body), List.of(), List.of());

    var finding = new AppendixRuleModule().check(document, RuleSet.schoolV1()).stream()
        .filter(result -> "APP-02-BLANK".equals(result.ruleId())).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, finding.judgement());
    assertEquals("APPENDIX_TITLE_BLANK_LINE_MISSING", finding.reasonCode());
    assertEquals(body.source(), finding.source());
  }

  @Test
  void acceptsExactlyOneBlankParagraphAfterMainAppendixTitle() {
    var title = paragraph("附    录", List.of(0, 1));
    var blank = paragraph("", List.of(0, 2));
    var body = paragraph("附录A 名词术语及缩略词", List.of(0, 3));
    var document = new ParsedDocument("sha", List.of(title, blank, body), List.of(), List.of());

    assertTrue(new AppendixRuleModule().check(document, RuleSet.schoolV1()).stream()
        .noneMatch(result -> "APP-02-BLANK".equals(result.ruleId())),
        "exactly one blank paragraph must pass");
  }

  @Test
  void requiresAppendixTitleToStartOnANewPageWhenXmlEvidenceIsMissing() {
    var title = paragraph("附    录", List.of(0, 1));
    var blank = paragraph("", List.of(0, 2));
    var body = paragraph("附录A 名词术语及缩略词", List.of(0, 3));
    var document = new ParsedDocument("sha", List.of(title, blank, body), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of(new cn.luwneshacha.thesis.document.HeadingBreakModel(title.source(), titleText(title), false)));

    var finding = new AppendixRuleModule().check(document, RuleSet.schoolV1()).stream()
        .filter(result -> "APPENDIX_PAGE_BREAK_REVIEW".equals(result.reasonCode()))
        .findFirst().orElseThrow();

    assertEquals(Judgement.REVIEW, finding.judgement());
    assertEquals(title.source(), finding.source());
  }

  @Test
  void acceptsAppendixTitleWhenXmlEvidenceShowsAForcedPageBreak() {
    var title = paragraph("附    录", List.of(0, 1));
    var blank = paragraph("", List.of(0, 2));
    var body = paragraph("附录A 名词术语及缩略词", List.of(0, 3));
    var document = new ParsedDocument("sha", List.of(title, blank, body), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of(new cn.luwneshacha.thesis.document.HeadingBreakModel(title.source(), titleText(title), true)));

    assertTrue(new AppendixRuleModule().check(document, RuleSet.schoolV1()).stream()
        .noneMatch(result -> result.reasonCode().equals("APPENDIX_PAGE_BREAK_REVIEW")
            || result.reasonCode().equals("APPENDIX_PAGE_BREAK_MISSING")),
        "explicit page-break evidence should satisfy the appendix page rule");
  }

  @Test
  void reportsExtraBlankParagraphsAfterMainAppendixTitle() {
    var title = paragraph("附    录", List.of(0, 1));
    var firstBlank = paragraph("", List.of(0, 2));
    var secondBlank = paragraph("\u00A0", List.of(0, 3));
    var body = paragraph("附录A 名词术语及缩略词", List.of(0, 4));
    var document = new ParsedDocument("sha", List.of(title, firstBlank, secondBlank, body),
        List.of(), List.of());

    var finding = new AppendixRuleModule().check(document, RuleSet.schoolV1()).stream()
        .filter(result -> "APP-02-BLANK".equals(result.ruleId())).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, finding.judgement());
    assertEquals("APPENDIX_TITLE_BLANK_LINE_COUNT_MISMATCH", finding.reasonCode());
    assertEquals("2", finding.actual());
  }

  @Test
  void doesNotApplyMainAppendixBlankLineRuleToLetterHeading() {
    var appendix = paragraph("附录 A 名词术语及缩略词", List.of(0, 1));
    var body = paragraph("正文内容", List.of(0, 2));
    var document = new ParsedDocument("sha", List.of(appendix, body), List.of(), List.of());

    assertTrue(new AppendixRuleModule().check(document, RuleSet.schoolV1()).stream()
        .noneMatch(result -> "APP-02-BLANK".equals(result.ruleId())),
        "letter headings are not the main appendix title");
  }

  @Test
  void doesNotTreatAppendixTitleInsideATableAsAnAppendixHeading() {
    var appendix = paragraph("附录A 名词术语及缩略词", List.of(0, 1, 0, 0));
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(0, 1), 0, 0),
        null, null, 1, 1, false, false, false, false, false, false);
    var document = new ParsedDocument("source", List.of(appendix), List.of(), List.of(),
        List.of(table), List.of(), List.of(), List.of());

    var findings = new AppendixRuleModule().check(document, RuleSet.schoolV1());

    assertTrue(findings.stream().noneMatch(result -> "APP-01".equals(result.ruleId())),
        findings.toString());
  }

  @Test
  void checksAppendixLetterSequenceInDocumentOrder() {
    var appendixB = paragraph("附录 B 源代码", List.of(0, 1));
    var appendixC = paragraph("附录 C 补充材料", List.of(0, 2));
    var document = new ParsedDocument("sha", List.of(appendixB, appendixC),
        List.of(), List.of());

    var evaluation = new AppendixRuleModule().evaluate(document, RuleSet.schoolV1());
    var findings = evaluation.results().stream()
        .filter(finding -> "APP-01".equals(finding.ruleId())).toList();

    assertEquals(List.of("A", "B"), findings.stream().map(CheckResult::expected).toList());
    assertEquals(List.of("B", "C"), findings.stream().map(CheckResult::actual).toList());
    assertTrue(findings.stream().allMatch(finding ->
        "APPENDIX_SEQUENCE_MISMATCH".equals(finding.reasonCode())
            && finding.judgement() == Judgement.FAIL));
    assertEquals(new RuleRun("APP-01", "AppendixRuleModule", 2, 2, 0, null),
        run(evaluation, "APP-01"));
  }

  @Test
  void checksAppendixPrefixesAndIndependentNumberSequencesByObjectType() {
    var appendix = paragraph("附录 A 源代码", List.of(0, 1));
    var tableCaption = paragraph("表B1 参数列表", List.of(0, 2));
    var firstFigureCaption = paragraph("图A1 系统流程", List.of(0, 3));
    var secondFigureCaption = paragraph("图A3 运行结果", List.of(0, 4));
    var firstEquationParagraph = paragraph("(A1)", List.of(0, 5));
    var secondEquationParagraph = paragraph("(A3)", List.of(0, 6));
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(0, 2, 0), 0, 0),
        tableCaption.source(), tableCaption.runs().get(0).text(),
        2, 2, true, true, true, false, true, false);
    var firstFigure = new ImageModel(firstFigureCaption.source(), firstFigureCaption.source(),
        firstFigureCaption.runs().get(0).text(), 100, 200, true, true, true);
    var secondFigure = new ImageModel(secondFigureCaption.source(), secondFigureCaption.source(),
        secondFigureCaption.runs().get(0).text(), 100, 200, true, true, true);
    var firstEquation = new EquationModel(firstEquationParagraph.source(), "OMML", "x", "A1", true, false);
    var secondEquation = new EquationModel(secondEquationParagraph.source(), "OMML", "x", "A3", true, false);
    var document = new ParsedDocument("sha", List.of(appendix, tableCaption, firstFigureCaption,
        secondFigureCaption, firstEquationParagraph, secondEquationParagraph), List.of(), List.of(),
        List.of(table), List.of(firstEquation, secondEquation), List.of(),
        List.of(firstFigure, secondFigure));

    var findings = new AppendixRuleModule().check(document, RuleSet.schoolV1());

    assertTrue(findings.stream().anyMatch(finding ->
        "APPENDIX_OBJECT_LETTER_MISMATCH".equals(finding.reasonCode())), findings.toString());
    var tablePrefix = findings.stream().filter(finding ->
        "APPENDIX_OBJECT_LETTER_MISMATCH".equals(finding.reasonCode())).findFirst().orElseThrow();
    assertEquals("表A", tablePrefix.expected());
    assertEquals("表B1 参数列表", tablePrefix.actual());
    assertEquals(tableCaption.source(), tablePrefix.source());

    var figureSequence = findings.stream().filter(finding ->
        "APPENDIX_OBJECT_SEQUENCE_MISMATCH".equals(finding.reasonCode())
            && finding.source().equals(secondFigureCaption.source())).findFirst().orElseThrow();
    assertEquals("图A2", figureSequence.expected());
    assertEquals("图A3 运行结果", figureSequence.actual());

    var equationSequence = findings.stream().filter(finding ->
        "APPENDIX_OBJECT_SEQUENCE_MISMATCH".equals(finding.reasonCode())
            && finding.source().equals(secondEquationParagraph.source())).findFirst().orElseThrow();
    assertEquals("公式A2", equationSequence.expected());
    assertEquals("A3", equationSequence.actual());
  }

  @Test
  void ambiguousTextBoxFigureNumbersAreNotHardFailuresInAnAppendix() {
    var appendix = paragraph("附录 A 源代码", List.of(0, 1));
    var imageParagraph = paragraph("", List.of(0, 2));
    var candidate = paragraph("图A1 第一候选", List.of(0, 3, 0, 0));
    var image = new ImageModel(new SourceAddress("word/document.xml", List.of(0, 2), 0, 0),
        candidate.source(), "图A1 第一候选", 100, 200, false, null, true,
        0, 0, false, false, true, true);
    var document = new ParsedDocument("sha", List.of(appendix, imageParagraph, candidate), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(image));

    var findings = new AppendixRuleModule().check(document, RuleSet.schoolV1());

    assertTrue(findings.stream().anyMatch(finding ->
        "APPENDIX_FIGURE_NUMBER_AMBIGUOUS".equals(finding.reasonCode())
            && finding.judgement() == Judgement.REVIEW
            && finding.source().equals(candidate.source())), findings.toString());
    assertFalse(findings.stream().anyMatch(finding -> finding.judgement() == Judgement.FAIL), findings.toString());
  }

  @Test
  void appendixFigureSequenceAfterAnAmbiguousCaptionIsReviewedOnlyInThatAppendix() {
    var appendixA = paragraph("附录 A 源代码", List.of(0, 1));
    var ambiguousCaption = paragraph("图A1 第一候选", List.of(0, 3, 0, 0));
    var laterCaption = paragraph("图A2 后续图", List.of(0, 5));
    var appendixB = paragraph("附录 B 数据", List.of(0, 6));
    var unaffectedCaption = paragraph("图B2 跳号图", List.of(0, 8));
    var ambiguous = new ImageModel(new SourceAddress("word/document.xml", List.of(0, 2), 0, 0),
        ambiguousCaption.source(), "图A1 第一候选", 100, 200, false, null, true,
        0, 0, false, false, true, true);
    var later = new ImageModel(new SourceAddress("word/document.xml", List.of(0, 4), 0, 0),
        laterCaption.source(), "图A2 后续图", 100, 200, true, true, true);
    var unaffected = new ImageModel(new SourceAddress("word/document.xml", List.of(0, 7), 0, 0),
        unaffectedCaption.source(), "图B2 跳号图", 100, 200, true, true, true);
    var document = new ParsedDocument("sha", List.of(appendixA, paragraph("", List.of(0, 2)),
        ambiguousCaption, paragraph("", List.of(0, 4)), laterCaption, appendixB,
        paragraph("", List.of(0, 7)), unaffectedCaption), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(ambiguous, later, unaffected));

    var evaluation = new AppendixRuleModule().evaluate(document, RuleSet.schoolV1());
    var findings = evaluation.results();

    assertTrue(findings.stream().anyMatch(finding -> finding.source().equals(laterCaption.source())
        && "APPENDIX_FIGURE_SEQUENCE_UNRESOLVED_AFTER_AMBIGUOUS_CAPTION".equals(finding.reasonCode())
        && finding.judgement() == Judgement.REVIEW), findings.toString());
    assertTrue(findings.stream().anyMatch(finding -> finding.source().equals(unaffectedCaption.source())
        && "APPENDIX_OBJECT_SEQUENCE_MISMATCH".equals(finding.reasonCode())
        && finding.judgement() == Judgement.FAIL), findings.toString());
    assertEquals(new RuleRun("APP-01", "AppendixRuleModule", 5, 3, 2, null),
        run(evaluation, "APP-01"));
  }

  @Test
  void doesNotClaimAppendixFigureSequenceWasCheckedWhenNumberCannotBeParsed() {
    var appendix = paragraph("附录 A 源代码", List.of(0, 1));
    var firstCaption = paragraph("图A1题", List.of(0, 2));
    var nextCaption = paragraph("图A3题", List.of(0, 3));
    var first = new ImageModel(firstCaption.source(), firstCaption.source(),
        firstCaption.runs().getFirst().text(), 100, 200, true, true, true);
    var next = new ImageModel(nextCaption.source(), nextCaption.source(),
        nextCaption.runs().getFirst().text(), 100, 200, true, true, true);
    var document = new ParsedDocument("sha", List.of(appendix, firstCaption, nextCaption),
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(first, next));

    var evaluation = new AppendixRuleModule().evaluate(document, RuleSet.schoolV1());

    assertEquals(new RuleRun("APP-01", "AppendixRuleModule", 3, 1, 2, null),
        run(evaluation, "APP-01"));
  }

  @Test
  void checksFigureSequenceWhenAppendixLetterIsSeparatedByASpace() {
    var appendix = paragraph("附录 A 源代码", List.of(0, 1));
    var firstFigureCaption = paragraph("图 A1 系统流程", List.of(0, 2));
    var thirdFigureCaption = paragraph("图 A3 运行结果", List.of(0, 3));
    var firstFigure = new ImageModel(firstFigureCaption.source(), firstFigureCaption.source(),
        firstFigureCaption.runs().get(0).text(), 100, 200, true, true, true);
    var thirdFigure = new ImageModel(thirdFigureCaption.source(), thirdFigureCaption.source(),
        thirdFigureCaption.runs().get(0).text(), 100, 200, true, true, true);
    var document = new ParsedDocument("sha",
        List.of(appendix, firstFigureCaption, thirdFigureCaption), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(firstFigure, thirdFigure));

    var findings = new AppendixRuleModule().check(document, RuleSet.schoolV1());
    var finding = findings.stream()
        .filter(result -> "APPENDIX_OBJECT_SEQUENCE_MISMATCH".equals(result.reasonCode())
            && result.source().equals(thirdFigureCaption.source()))
        .findFirst();

    assertTrue(finding.isPresent(), findings.toString());
    assertEquals("图A2", finding.orElseThrow().expected());
    assertEquals("图 A3 运行结果", finding.orElseThrow().actual());
  }

  @Test
  void continuationTableDoesNotAdvanceAppendixTableSequence() {
    var appendix = paragraph("附录 A 源代码", List.of(0, 1));
    var firstCaption = paragraph("表A1 参数", List.of(0, 2));
    var continuationCaption = paragraph("续表A1", List.of(0, 4));
    var secondCaption = paragraph("表A2 结果", List.of(0, 6));
    var firstTable = table(firstCaption, "表A1 参数", 3);
    var continuationTable = table(continuationCaption, "续表A1", 2);
    var secondTable = table(secondCaption, "表A2 结果", 2);
    var document = new ParsedDocument("source",
        List.of(appendix, firstCaption, continuationCaption, secondCaption), List.of(), List.of(),
        List.of(firstTable, continuationTable, secondTable), List.of(), List.of());

    var findings = new AppendixRuleModule().check(document, RuleSet.schoolV1());

    assertFalse(findings.stream().anyMatch(finding ->
        "APPENDIX_OBJECT_SEQUENCE_MISMATCH".equals(finding.reasonCode())), findings.toString());
  }

  private static ParagraphModel paragraph(String text, List<Integer> path) {
    var source = new SourceAddress("word/document.xml", path, 0, text.length());
    return new ParagraphModel(List.of(new RunModel(text, "Times New Roman", "宋体", null,
        24, false, source)), 480, null, null, 360, "auto", source, 2, null, "left");
  }

  private static RuleRun run(RuleModule.Evaluation evaluation, String ruleId) {
    return evaluation.ruleRuns().stream().filter(item -> item.ruleId().equals(ruleId))
        .findFirst().orElseThrow();
  }

  private static String titleText(ParagraphModel paragraph) {
    return paragraph.runs().stream().map(RunModel::text).reduce("", String::concat);
  }

  private static TableModel table(ParagraphModel caption, String text, int rows) {
    return new TableModel(new SourceAddress("word/document.xml",
        List.of(0, caption.source().elementPath().get(1) + 1), 0, 0),
        caption.source(), text, rows, 2, true, true, true, false, true, false);
  }
}
