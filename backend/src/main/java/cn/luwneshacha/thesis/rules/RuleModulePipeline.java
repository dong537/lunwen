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

  List<String> registeredModuleIds() {
    return modules.stream().map(module -> module.getClass().getSimpleName()).toList();
  }

  List<CheckResult> check(ParsedDocument document, RuleSet rules) {
    return checkWithExecution(document, rules).results();
  }

  Evaluation checkWithExecution(ParsedDocument document, RuleSet rules) {
    Objects.requireNonNull(document, "document");
    Objects.requireNonNull(rules, "rules");
    var findings = new ArrayList<CheckResult>();
    var runs = new ArrayList<RuleRun>();
    var completedModuleIds = new ArrayList<String>();
    for (RuleModule module : modules) {
      RuleModule.Evaluation evaluated = Objects.requireNonNull(module.evaluate(document, rules),
          "module evaluation");
      List<CheckResult> moduleFindings = Objects.requireNonNull(evaluated.results(),
          "module findings");
      for (CheckResult finding : moduleFindings) {
        findings.add(Objects.requireNonNull(finding, "module finding"));
      }
      for (RuleRun run : Objects.requireNonNull(evaluated.ruleRuns(), "module rule runs")) {
        RuleRun evidence = Objects.requireNonNull(run, "module rule run");
        if (!module.getClass().getSimpleName().equals(evidence.moduleId()))
          throw new IllegalStateException("module returned evidence for another module");
        runs.add(evidence);
      }
      completedModuleIds.add(module.getClass().getSimpleName());
    }
    return new Evaluation(findings, completedModuleIds, runs);
  }

  record Evaluation(List<CheckResult> results, List<String> completedModuleIds, List<RuleRun> ruleRuns) {
    Evaluation {
      results = List.copyOf(results);
      completedModuleIds = List.copyOf(completedModuleIds);
      ruleRuns = List.copyOf(ruleRuns);
    }
  }
}
