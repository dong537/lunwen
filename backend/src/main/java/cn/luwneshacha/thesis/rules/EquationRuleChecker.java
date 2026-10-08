package cn.luwneshacha.thesis.rules;

import cn.luwneshacha.thesis.document.EquationModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.SourceAddress;
import java.util.ArrayList;
import java.util.List;

final class EquationRuleChecker {
  List<CheckResult> check(ParsedDocument document, EquationModel equation, RuleSet rules) {
    if (!"OMML".equals(equation.type())) return List.of();
    var results = new ArrayList<CheckResult>();
    if (equation.fonts().stream().anyMatch(font -> !"Times New Roman".equalsIgnoreCase(font))) {
      results.add(fail("EQ-08", "Times New Roman", String.join(", ", equation.fonts()),
          "EQUATION_FONT_MISMATCH", equation.source()));
    }
    if (Boolean.FALSE.equals(equation.italicVariables())) {
      results.add(fail("EQ-09", "变量使用斜体", "显式设置为正体",
          "EQUATION_VARIABLE_NOT_ITALIC", equation.source()));
    }
    return List.copyOf(results);
  }

  private static CheckResult fail(String ruleId, String expected, String actual,
      String reason, SourceAddress source) {
    return new CheckResult(ruleId, Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, expected, actual, reason, source);
  }
}
