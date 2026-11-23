package cn.luwneshacha.thesis.rules;

import cn.luwneshacha.thesis.document.ParsedDocument;
import java.util.List;

/**
 * A semantic rule module.  A module owns one document region or relation and
 * returns only findings it can prove from the parsed DOCX evidence.
 */
@FunctionalInterface
interface RuleModule {
  List<CheckResult> check(ParsedDocument document, RuleSet rules);

  /** Modules without candidate evidence stay unknown instead of inheriting a pass. */
  default Evaluation evaluate(ParsedDocument document, RuleSet rules) {
    return new Evaluation(check(document, rules), List.of());
  }

  record Evaluation(List<CheckResult> results, List<RuleRun> ruleRuns) {}
}
