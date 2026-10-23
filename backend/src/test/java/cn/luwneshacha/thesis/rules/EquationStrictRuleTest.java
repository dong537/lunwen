package cn.luwneshacha.thesis.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.luwneshacha.thesis.document.EquationModel;
import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.RunModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

class EquationStrictRuleTest {
  private static final SourceAddress CHAPTER = new SourceAddress("word/document.xml", List.of(0, 0), 0, 8);

  @Test
  void doesNotUseAnotherVariableSuffixAsTheDefinition() {
    for (String other : List.of("max", "ax", "αx", "x_x", "x^x", "1x")) {
      var result = checkVariableExplanation("式中：" + other + "为长度。");
      assertEquals(Judgement.FAIL, result.judgement(), other);
      assertEquals("x", result.actual(), other);
    }
  }

  @Test
  void acceptsVariableDefinitionAfterChineseIntroductoryText() {
    assertEquals(Judgement.PASS, checkVariableExplanation("式中：变量x为长度。").judgement());
    assertEquals(Judgement.PASS, checkVariableExplanation("式中：x为长度。").judgement());
  }

  private CheckResult checkVariableExplanation(String explanation) {
    var source = new SourceAddress("word/document.xml", List.of(0, 1), 0, 1);
    var equation = new EquationModel(source, "OMML", "x", "1-1", true, false,
        java.util.Set.of(), java.util.Set.of(), null, null, explanation,
        java.util.Set.of("x"), true);
    return check(equation, paragraph("由式(1-1)可知。", List.of(0, 2))).stream()
        .filter(item -> item.ruleId().equals("EQ-07")).findFirst().orElseThrow();
  }

  @Test
  void incompleteOmmlNumberFormattingIsNotAnEstablishedError() {
    assertEquals(Judgement.REVIEW, checkNumberEvidence("Times New Roman", null).judgement());
    assertEquals(Judgement.REVIEW, checkNumberEvidence(null, 21).judgement());
    assertEquals(Judgement.REVIEW, checkNumberEvidence(null, null).judgement());
  }

  @Test
  void knownNumberMismatchStillFailsWhenAnotherPropertyIsUnknown() {
    assertEquals(Judgement.FAIL, checkNumberEvidence("Arial", null).judgement());
    assertEquals(Judgement.FAIL, checkNumberEvidence(null, 20).judgement());
  }

  private CheckResult checkNumberEvidence(String font, Integer size) {
    var source = new SourceAddress("word/document.xml", List.of(0, 1, 0), 0, 5);
    var equation = new EquationModel(source, "OMML", "x", "1-1", true, false,
        java.util.Set.of(), java.util.Set.of(), null, null, null, java.util.Set.of("x"), true,
        new cn.luwneshacha.thesis.document.EquationNumberEvidence("(1-1)", true, font, size, source));
    return check(equation, paragraph("由式(1-1)可知。", List.of(0, 2))).stream()
        .filter(item -> item.ruleId().equals("EQ-02")).findFirst().orElseThrow();
  }

  @Test
  void unknownFirstNumberRunDoesNotHideLaterWrongFont() {
    var source = new SourceAddress("word/document.xml", List.of(0, 1), 0, 5);
    var number = new ParagraphModel(List.of(
        new RunModel("(", null, null, null, null, false, source),
        new RunModel("1-1)", "Arial", "宋体", null, 21, false, source)),
        0, null, null, null, null, source, 0);
    var equation = new EquationModel(source, "OMML", "x", "1-1", true, false,
        java.util.Set.of(), java.util.Set.of(), null, source);
    var result = new RuleEngine().check(new ParsedDocument("source", List.of(chapter(), number),
        List.of(), List.of(), List.of(), List.of(equation), List.of()), RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("EQ-02")).findFirst().orElseThrow();
    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("EQUATION_NUMBER_FORMAT_MISMATCH", result.reasonCode());
  }

  @Test
  void acceptsEquationCitationImmediatelyFollowedByChineseProse() {
    var result = check(equation("1-1", List.of(0, 1, 0)),
        paragraph("由公式（1-1）可得结果。", List.of(0, 2))).stream()
        .filter(item -> item.ruleId().equals("EQ-04")).findFirst().orElseThrow();
    assertEquals(Judgement.PASS, result.judgement());
  }

  @Test
  void rejectsSpacesAndMixedParenthesesInEquationCitation() {
    var equation = equation("1-1", List.of(0, 1, 0));
    var citation = paragraph("\u7531\u516c\u5f0f\uff08 1-1\uff09\u53ef\u77e5\uff0c\u516c\u5f0f(1-1\uff09\u53ef\u5f97\u3002", List.of(0, 2));
    var result = check(equation, citation).stream().filter(item -> item.ruleId().equals("EQ-04")).findFirst().orElseThrow();
    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("EQUATION_NOT_REFERENCED", result.reasonCode());
  }

  @Test
  void rejectsMixedParenthesesInTheEquationNumberItself() {
    var paragraphSource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 5);
    var equation = new EquationModel(new SourceAddress("word/document.xml", List.of(0, 1, 0), 0, 1),
        "OMML", "x", "1-1", true, false, java.util.Set.of(), java.util.Set.of(), null,
        new SourceAddress("word/document.xml", List.of(0, 1), 0, 5));
    var paragraph = new ParagraphModel(List.of(new RunModel("(1-1\uff09", "Times New Roman", "宋体", null,
        21, false, paragraphSource)), 480, null, null, 360, "auto", paragraphSource, 2, null, "center");
    var document = new ParsedDocument("source", List.of(chapter(), paragraph), List.of(), List.of(),
        List.of(), List.of(equation), List.of());
    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("EQ-01")).findFirst().orElseThrow();
    assertEquals(Judgement.FAIL, result.judgement());
  }

  @Test
  void readsNumberSourceRangesUsingParagraphOffsetsAcrossRuns() {
    var paragraphSource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 24);
    var numberSource = new SourceAddress("word/document.xml", List.of(0, 1), 6, 11);
    var paragraph = new ParagraphModel(List.of(
        new RunModel("prefix", "Times New Roman", "宋体", null, 21, false,
            new SourceAddress("word/document.xml", List.of(0, 1), 0, 6)),
        new RunModel("(3-1) trailing text", "Times New Roman", "宋体", null, 21, false,
            new SourceAddress("word/document.xml", List.of(0, 1), 6, 24))),
        0, null, null, null, 360, "auto", paragraphSource, 0, null, "center");
    var equation = new EquationModel(new SourceAddress("word/document.xml", List.of(0, 1, 0), 0, 1),
        "OLE", "embedded equation", "3-1", true, true, java.util.Set.of(), java.util.Set.of(), null,
        numberSource);

    var findings = new EquationRuleChecker().check(new ParsedDocument("source", List.of(paragraph),
        List.of(), List.of(), List.of(), List.of(equation), List.of()), equation, RuleSet.schoolV1());

    assertEquals(0, findings.stream().filter(result -> result.ruleId().equals("EQ-01")
        && result.judgement() == Judgement.FAIL).count(), findings.toString());
  }

  @Test
  void checksScriptedVariableAsAWholeTokenInsteadOfSeparateLettersAndDigits() {
    var source = new SourceAddress("word/document.xml", List.of(0, 1, 0), 0, 5);
    var equation = new EquationModel(source, "OMML", "x_i^2", "1-1", true, false,
        java.util.Set.of(), java.util.Set.of(), null, null, null, java.util.Set.of("x", "i"), true,
        null, null, null, null,
        List.of(new cn.luwneshacha.thesis.document.EquationVariable("x", "i", "2", "x_i^2", source)));
    var document = new ParsedDocument("source", List.of(chapter(), equationParagraph()), List.of(), List.of(),
        List.of(), List.of(equation), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("EQ-07")).findFirst().orElseThrow();

    assertEquals("x_i^2", result.actual());
  }

  @Test
  void usesParagraphAlignmentWhenOmmlArrayHasNoExplicitAlignmentEvidence() {
    var formulaSource = new SourceAddress("word/document.xml", List.of(0, 1, 0, 0), 0, 1);
    var numberSource = new SourceAddress("word/document.xml", List.of(0, 1, 1, 0), 0, 5);
    var numberEvidence = new cn.luwneshacha.thesis.document.EquationNumberEvidence(
        "(1-1)", true, "Times New Roman", 21, numberSource);
    var equation = new EquationModel(formulaSource, "OMML", "x", "1-1", true, false,
        java.util.Set.of("oMath"), java.util.Set.of(), null, numberSource, null,
        java.util.Set.of("x"), true, numberEvidence, null,
        new cn.luwneshacha.thesis.document.EquationLayoutEvidence(
            "OMML_NUMBER_ARRAY", null, null, false, numberSource),
        cn.luwneshacha.thesis.document.EquationObjectEvidence.omml(), List.of());
    var formulaParagraph = new ParagraphModel(List.of(), 0, null, null, null, null,
        new SourceAddress("word/document.xml", List.of(0, 1, 0), 0, 0), 0, null, "center");
    var numberParagraph = new ParagraphModel(List.of(new RunModel("(1-1)", "Times New Roman",
        "Times New Roman", null, 21, false, numberSource)), 0, null, null, null, null,
        new SourceAddress("word/document.xml", List.of(0, 1, 1), 0, 5), 0, null, "right");
    var results = new RuleEngine().check(new ParsedDocument("source",
        List.of(chapter(), formulaParagraph, numberParagraph), List.of(), List.of(), List.of(),
        List.of(equation), List.of()), RuleSet.schoolV1());

    var alignment = results.stream().filter(item -> item.ruleId().equals("EQ-03")).findFirst().orElseThrow();
    assertEquals(Judgement.PASS, alignment.judgement());
  }

  @Test
  void trustsExplicitLayoutAlignmentWithoutParagraphAlignmentProperties() {
    var formulaSource = new SourceAddress("word/document.xml", List.of(0, 1, 0, 0), 0, 1);
    var numberSource = new SourceAddress("word/document.xml", List.of(0, 1, 1, 0), 0, 5);
    var equation = new EquationModel(formulaSource, "OMML", "x", "1-1", true, false,
        java.util.Set.of("oMath"), java.util.Set.of(), null, numberSource, null,
        java.util.Set.of("x"), true,
        new cn.luwneshacha.thesis.document.EquationNumberEvidence(
            "(1-1)", true, "Times New Roman", 21, numberSource), null,
        new cn.luwneshacha.thesis.document.EquationLayoutEvidence(
            "BORDERLESS_TABLE", true, true, true, numberSource),
        cn.luwneshacha.thesis.document.EquationObjectEvidence.omml(), List.of());
    var formulaParagraph = new ParagraphModel(List.of(), 0, null, null, null, null,
        new SourceAddress("word/document.xml", List.of(0, 1, 0), 0, 0), 0, null, null);
    var results = new RuleEngine().check(new ParsedDocument("source",
        List.of(chapter(), formulaParagraph), List.of(), List.of(), List.of(),
        List.of(equation), List.of()), RuleSet.schoolV1());

    var alignment = results.stream().filter(item -> item.ruleId().equals("EQ-03")).findFirst().orElseThrow();
    assertEquals(Judgement.PASS, alignment.judgement());
  }

  @Test
  void equationModuleOwnsDuplicateSequenceAndUnsupportedObjectChecks() {
    var first = new EquationModel(new SourceAddress("word/document.xml", List.of(0, 1, 0), 0, 1),
        "OMML", "x", "1-1", true, false);
    var duplicate = new EquationModel(new SourceAddress("word/document.xml", List.of(0, 2, 0), 0, 1),
        "OLE", "embedded equation", "1-1", true, true);
    var document = new ParsedDocument("source", List.of(chapter()), List.of(), List.of(), List.of(),
        List.of(first, duplicate), List.of());

    var findings = new EquationRuleModule().check(document, RuleSet.schoolV1());

    assertEquals(List.of("EQUATION_NUMBER_DUPLICATE", "EQUATION_NUMBER_SEQUENCE_MISMATCH",
        "EQUATION_OBJECT_UNSUPPORTED"), findings.stream()
        .filter(item -> item.ruleId().equals("EQ-05") || item.ruleId().equals("EQ-06"))
        .map(CheckResult::reasonCode).toList());
    var sequence = findings.stream()
        .filter(item -> item.reasonCode().equals("EQUATION_NUMBER_SEQUENCE_MISMATCH"))
        .findFirst().orElseThrow();
    assertEquals("1-2", sequence.expected());
    assertEquals("1-1", sequence.actual());
    assertEquals(Judgement.REVIEW, findings.stream()
        .filter(item -> item.reasonCode().equals("EQUATION_OBJECT_UNSUPPORTED"))
        .findFirst().orElseThrow().judgement());
  }

  @Test
  void contentsChapterEntryDoesNotChangeEquationChapterContext() {
    var contentsEntry = paragraph("第一章 绪论……1", List.of(0, 0));
    var equation = new EquationModel(new SourceAddress("word/document.xml", List.of(0, 1, 0), 0, 1),
        "OMML", "x", "3-1", true, false);
    var document = new ParsedDocument("source", List.of(contentsEntry), List.of(), List.of(), List.of(),
        List.of(equation), List.of());

    var findings = new EquationRuleModule().check(document, RuleSet.schoolV1());

    assertEquals(0, findings.stream()
        .filter(item -> item.reasonCode().equals("EQUATION_NUMBER_SEQUENCE_MISMATCH")).count());
  }

  private List<CheckResult> check(EquationModel equation, ParagraphModel citation) {
    var document = new ParsedDocument("source", List.of(chapter(), equationParagraph(), citation),
        List.of(), List.of(), List.of(), List.of(equation), List.of());
    return new RuleEngine().check(document, RuleSet.schoolV1());
  }

  private static EquationModel equation(String number, List<Integer> path) {
    return new EquationModel(new SourceAddress("word/document.xml", path, 0, 1),
        "OMML", "x", number, true, false);
  }

  private static ParagraphModel chapter() {
    return paragraph("\u7b2c1\u7ae0 \u7eea\u8bba", List.of(0, 0));
  }

  private static ParagraphModel equationParagraph() {
    return paragraph("(1-1)", List.of(0, 1));
  }

  private static ParagraphModel paragraph(String text, List<Integer> path) {
    var source = new SourceAddress("word/document.xml", path, 0, text.length());
    return new ParagraphModel(List.of(new RunModel(text, "Times New Roman", "宋体", null, 21, false, source)),
        480, null, null, 360, "auto", source, 2, null, "left");
  }
}
