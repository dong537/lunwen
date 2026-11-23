package cn.luwneshacha.thesis.rules;

import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.SourceAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Checks collection-wide reference constraints that are independent of entry formatting. */
final class ReferenceCollectionRuleModule implements RuleModule {
  @Override
  public List<CheckResult> check(ParsedDocument document, RuleSet rules) {
    var results = new ArrayList<CheckResult>();
    var referenceNumbers = new HashSet<String>();
    if (document.references().size() < 10) {
      SourceAddress source = document.references().isEmpty()
          ? document.paragraphs().stream().findFirst().map(paragraph -> paragraph.source()).orElse(null)
          : document.references().get(0).source();
      results.add(result("REF-05", "10", String.valueOf(document.references().size()),
          "REFERENCE_COUNT_TOO_LOW", source));
    }
    for (var reference : document.references()) {
      String number = reference.number();
      if (number != null && !number.isBlank() && !referenceNumbers.add(number))
        results.add(result("REF-03", "unique", number, "REFERENCE_NUMBER_DUPLICATE", reference.source()));
      if (reference.category() == null)
        results.add(result("REF-02", "M/J/D/C/N/R/S/P/Z/EB/OL", null,
            "REFERENCE_CATEGORY_MISSING", reference.source()));
    }
    return List.copyOf(results);
  }

  @Override
  public Evaluation evaluate(ParsedDocument document, RuleSet rules) {
    var findings = check(document, rules);
    var references = document.references();
    if (references.isEmpty()) return new Evaluation(findings, List.of());
    int uncertainEntries = (int) references.stream().filter(reference -> reference.needsReview()).count();
    int uncertainNumbers = (int) references.stream()
        .filter(reference -> reference.needsReview() || reference.number() == null
            || reference.number().isBlank())
        .count();
    String moduleId = getClass().getSimpleName();
    return new Evaluation(findings, List.of(
        new RuleRun("REF-05", moduleId, 1, uncertainEntries == 0 ? 1 : 0,
            uncertainEntries == 0 ? 0 : 1, null),
        new RuleRun("REF-02", moduleId, references.size(),
            references.size() - uncertainEntries, uncertainEntries, null),
        new RuleRun("REF-03", moduleId, references.size(),
            references.size() - uncertainNumbers, uncertainNumbers, null)));
  }

  private static CheckResult result(String ruleId, String expected, String actual, String reason,
      SourceAddress source) {
    return new CheckResult(ruleId, Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, expected, actual, reason, source);
  }
}
