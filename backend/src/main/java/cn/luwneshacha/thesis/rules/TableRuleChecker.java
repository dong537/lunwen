package cn.luwneshacha.thesis.rules;

import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.SourceAddress;
import cn.luwneshacha.thesis.document.TableModel;
import java.util.ArrayList;
import java.util.List;

final class TableRuleChecker {
  List<CheckResult> check(ParsedDocument document, TableModel table, RuleSet rules) {
    var results = new ArrayList<CheckResult>();
    if (table.rows() > 1 && table.columns() > 0 && !hasUnitLabel(table.headerTexts())) {
      results.add(fail("TAB-04", "表头包含量名称和单位", String.join(" / ", table.headerTexts()),
          "TABLE_UNITS_MISSING", table.source()));
    }
    if (table.repeatedPlaceholderText()) {
      results.add(fail("TAB-06", "重复写出完整内容", "同前/同左",
          "TABLE_REPEATED_PLACEHOLDER", table.source()));
    }
    return List.copyOf(results);
  }

  private static boolean hasUnitLabel(List<String> headers) {
    return headers.stream().map(String::trim).anyMatch(text ->
        text.matches(".*[（(][^）)]{1,20}[）)].*"));
  }

  private static CheckResult fail(String ruleId, String expected, String actual,
      String reason, SourceAddress source) {
    return new CheckResult(ruleId, Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, expected, actual, reason, source);
  }
}
