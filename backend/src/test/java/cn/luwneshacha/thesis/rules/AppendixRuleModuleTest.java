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
import java.util.List;
import org.junit.jupiter.api.Test;

class AppendixRuleModuleTest {
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

    var findings = new AppendixRuleModule().check(document, RuleSet.schoolV1()).stream()
        .filter(finding -> "APP-01".equals(finding.ruleId())).toList();

    assertEquals(List.of("A", "B"), findings.stream().map(CheckResult::expected).toList());
    assertEquals(List.of("B", "C"), findings.stream().map(CheckResult::actual).toList());
    assertTrue(findings.stream().allMatch(finding ->
        "APPENDIX_SEQUENCE_MISMATCH".equals(finding.reasonCode())
            && finding.judgement() == Judgement.FAIL));
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

  private static String titleText(ParagraphModel paragraph) {
    return paragraph.runs().stream().map(RunModel::text).reduce("", String::concat);
  }

  private static TableModel table(ParagraphModel caption, String text, int rows) {
    return new TableModel(new SourceAddress("word/document.xml",
        List.of(0, caption.source().elementPath().get(1) + 1), 0, 0),
        caption.source(), text, rows, 2, true, true, true, false, true, false);
  }
}
