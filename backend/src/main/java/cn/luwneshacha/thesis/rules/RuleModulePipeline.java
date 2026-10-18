package cn.luwneshacha.thesis.rules;

import cn.luwneshacha.thesis.document.ParsedDocument;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic orchestration for semantic modules.
 *
 * <p>The pipeline deliberately does not deduplicate findings: two modules may
 * legitimately report two different rules at the same source.  It only owns
 * ordering and defensive list handling, leaving rule semantics in modules.</p>
 */
final class RuleModulePipeline {
  private final List<RuleModule> modules;

  RuleModulePipeline(List<RuleModule> modules) {
    this.modules = List.copyOf(Objects.requireNonNull(modules, "modules"));
  }

  List<CheckResult> check(ParsedDocument document, RuleSet rules) {
    Objects.requireNonNull(document, "document");
    Objects.requireNonNull(rules, "rules");
    var findings = new ArrayList<CheckResult>();
    for (RuleModule module : modules) {
      List<CheckResult> moduleFindings = Objects.requireNonNull(module.check(document, rules),
          "module findings");
      for (CheckResult finding : moduleFindings) {
        findings.add(Objects.requireNonNull(finding, "module finding"));
      }
    }
    return List.copyOf(findings);
  }
}
