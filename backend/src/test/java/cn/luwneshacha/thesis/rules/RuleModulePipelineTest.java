package cn.luwneshacha.thesis.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.luwneshacha.thesis.document.ParsedDocument;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuleModulePipelineTest {
  @Test
  void rejectsModuleReturningNullInsteadOfSilentlyDroppingFindings() {
    var pipeline = new RuleModulePipeline(List.of((document, rules) -> null));

    var exception = org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class,
        () -> pipeline.check(new ParsedDocument("sha", List.of(), List.of(), List.of()),
            RuleSet.schoolV1()));

    assertEquals("module findings", exception.getMessage());
  }

  @Test
  void rejectsNullFindingInsteadOfPassingItToReportAndAnnotationStages() {
    var pipeline = new RuleModulePipeline(List.of((document, rules) -> java.util.Arrays.asList((CheckResult) null)));

    var exception = org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class,
        () -> pipeline.check(new ParsedDocument("sha", List.of(), List.of(), List.of()),
            RuleSet.schoolV1()));

    assertEquals("module finding", exception.getMessage());
  }

  @Test
  void runsSemanticModulesInDeclaredOrder() {
    var first = new CheckResult("FIRST", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.PASS, "", "", "", null);
    var second = new CheckResult("SECOND", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.PASS, "", "", "", null);
    var pipeline = new RuleModulePipeline(List.of(
        (document, rules) -> List.of(first),
        (document, rules) -> List.of(second)));

    var results = pipeline.check(new ParsedDocument("sha", List.of(), List.of(), List.of()),
        RuleSet.schoolV1());

    assertEquals(List.of(first, second), results);
  }
}
