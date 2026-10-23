package cn.luwneshacha.thesis.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.luwneshacha.thesis.document.EquationLayoutEvidence;
import cn.luwneshacha.thesis.document.EquationModel;
import cn.luwneshacha.thesis.document.EquationNumberEvidence;
import cn.luwneshacha.thesis.document.EquationObjectEvidence;
import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.RunModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DocumentObjectRuleModuleTest {
  private static final SourceAddress SOURCE =
      new SourceAddress("word/document.xml", List.of(0), 0, 5);

  @Test
  void equationModuleOwnsEquationCheckerFindings() {
    var equation = new EquationModel(SOURCE, "omml", "x", "bad", true, false);
    var document = new ParsedDocument("sha", List.of(), List.of(), List.of(), List.of(),
        List.of(equation), List.of());

    var findings = new EquationRuleModule().check(document, RuleSet.schoolV1());

    assertTrue(findings.stream().anyMatch(f -> "EQ-01".equals(f.ruleId())));
  }

  @Test
  void equationModuleOwnsEquationReferenceFindings() {
    var equationSource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 1);
    var equation = new EquationModel(equationSource, "omml", "x", "1-1", true, false);
    var document = new ParsedDocument("sha", List.of(
        paragraph("第1章 绪论", List.of(0, 0)),
        paragraph("公式(1-1)给出计算结果。", List.of(0, 2))), List.of(), List.of(), List.of(),
        List.of(equation), List.of());

    var findings = new EquationRuleModule().check(document, RuleSet.schoolV1());

    assertTrue(findings.stream().anyMatch(f -> "EQ-04".equals(f.ruleId())
        && f.judgement() == Judgement.PASS));
  }

  @Test
  void equationModuleOwnsEachObjectLevelFindingWithoutDuplicates() {
    var formulaSource = new SourceAddress("word/document.xml", List.of(0, 1, 0, 0), 0, 1);
    var numberSource = new SourceAddress("word/document.xml", List.of(0, 1, 1), 0, 5);
    var equation = new EquationModel(formulaSource, "OMML", "x", "1-1", true, false,
        Set.of("oMath"), Set.of("Times New Roman"), null, numberSource, "式中x为变量",
        Set.of("x"), true,
        new EquationNumberEvidence("(1-1)", true, "Times New Roman", 21, numberSource),
        null,
        new EquationLayoutEvidence("OMML_NUMBER_ARRAY", true, true, true, numberSource),
        EquationObjectEvidence.omml(), List.of());
    var document = new ParsedDocument("sha", List.of(
        paragraph("第1章 绪论", List.of(0, 0)),
        paragraph("x=1", List.of(0, 1)),
        paragraph("(1-1)", List.of(0, 2)),
        paragraph("由公式(1-1)可得结果。", List.of(0, 3))),
        List.of(), List.of(), List.of(), List.of(equation), List.of());

    var findings = new EquationRuleModule().check(document, RuleSet.schoolV1());

    for (String ruleId : List.of("EQ-02", "EQ-03", "EQ-04", "EQ-07")) {
      assertEquals(1, findings.stream().filter(f -> ruleId.equals(f.ruleId())).count(), ruleId);
    }
  }

  @Test
  void ruleEngineDoesNotDuplicateEquationObjectFindingsOwnedByModule() {
    var formulaSource = new SourceAddress("word/document.xml", List.of(0, 1, 0, 0), 0, 1);
    var numberSource = new SourceAddress("word/document.xml", List.of(0, 1, 1), 0, 5);
    var equation = new EquationModel(formulaSource, "OMML", "x", "1-1", true, false,
        Set.of("oMath"), Set.of("Times New Roman"), null, numberSource, "式中x为变量",
        Set.of("x"), true,
        new EquationNumberEvidence("(1-1)", true, "Times New Roman", 21, numberSource),
        null,
        new EquationLayoutEvidence("OMML_NUMBER_ARRAY", true, true, true, numberSource),
        EquationObjectEvidence.omml(), List.of());
    var document = new ParsedDocument("sha", List.of(
        paragraph("第1章 绪论", List.of(0, 0)),
        paragraph("x=1", List.of(0, 1)),
        paragraph("(1-1)", List.of(0, 2)),
        paragraph("由公式(1-1)可得结果。", List.of(0, 3))),
        List.of(), List.of(), List.of(), List.of(equation), List.of());

    var findings = new RuleEngine().check(document, RuleSet.schoolV1());

    for (String ruleId : List.of("EQ-02", "EQ-03", "EQ-04", "EQ-07")) {
      assertEquals(1, findings.stream().filter(f -> ruleId.equals(f.ruleId())).count(), ruleId);
    }
  }

  @Test
  void imageModuleDoesNotInventFindingsWhenImageEvidenceIsValid() {
    var document = new ParsedDocument("sha", List.of(), List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of());

    assertTrue(new ImageRuleChecker().check(document, RuleSet.schoolV1()).isEmpty());
  }

  private static ParagraphModel paragraph(String text, List<Integer> path) {
    var source = new SourceAddress("word/document.xml", path, 0, text.length());
    return new ParagraphModel(List.of(new RunModel(text, "Times New Roman", "宋体", null,
        24, false, source)), 480, null, null, 360, "auto", source, 2, null, "left");
  }
}
