package cn.luwneshacha.thesis.rules;

import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.ReferenceEntryModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class ReferenceRuleChecker {
  List<CheckResult> check(ParsedDocument document, List<ReferenceEntryModel> references,
      Map<String, SourceAddress> firstCitationSources) {
    var results = new ArrayList<CheckResult>();
    int expectedNumber = 1;
    for (ReferenceEntryModel reference : references) {
      try {
        int actualNumber = Integer.parseInt(reference.number());
        if (actualNumber != expectedNumber) {
          results.add(fail("REF-07", String.valueOf(expectedNumber), reference.number(),
              "REFERENCE_NUMBER_SEQUENCE_MISMATCH", reference.source()));
        }
        expectedNumber = actualNumber + 1;
      } catch (NumberFormatException ignored) {
        results.add(fail("REF-07", String.valueOf(expectedNumber), reference.number(),
            "REFERENCE_NUMBER_INVALID", reference.source()));
      }
      if (reference.category() == null) continue;
      var missing = new ArrayList<String>();
      if (blank(reference.authorText())) missing.add("作者");
      if (blank(reference.titleText())) missing.add("题名");
      if (blank(reference.yearText())) missing.add("年份");
      if (requiresSource(reference.category()) && blank(reference.sourceText())) missing.add("来源");
      if (!missing.isEmpty()) {
        results.add(fail("REF-06", "作者、题名、来源、年份等字段齐全", String.join("、", missing),
            "REFERENCE_FIELDS_MISSING", reference.source()));
      }
    }
    return List.copyOf(results);
  }

  private static boolean requiresSource(String category) {
    return switch (category) {
      case "M", "J", "D", "C", "N", "R", "S", "P", "Z" -> true;
      default -> false;
    };
  }

  private static boolean blank(String value) { return value == null || value.isBlank(); }

  private static CheckResult fail(String ruleId, String expected, String actual, String reason, SourceAddress source) {
    return new CheckResult(ruleId, Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, expected, actual, reason, source);
  }
}
