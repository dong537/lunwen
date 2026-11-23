package cn.luwneshacha.thesis.inspection;

import cn.luwneshacha.thesis.rules.CheckResult;
import cn.luwneshacha.thesis.rules.Applicability;
import cn.luwneshacha.thesis.rules.ExecutionStatus;
import cn.luwneshacha.thesis.rules.Judgement;
import cn.luwneshacha.thesis.rules.RuleRun;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds the report's coverage summary from the actual results produced in this run. */
public final class RuleCoverageAnalyzer {
  // Keep in sync with the platform's approved zero-candidate reasons.
  private static final Map<String, Set<String>> APPROVED_NOT_APPLICABLE_REASONS = Map.ofEntries(
      Map.entry("COVER-04-CONSISTENCY@CoverRuleChecker", Set.of("SINGLE_COVER_DATE")),
      Map.entry("HEAD-02-SPACING@BodyFormatRuleChecker",
          Set.of("NO_EMBEDDED_HEADING_IN_RESOLVED_BODY")),
      Map.entry("HEAD-03-SPACING@BodyFormatRuleChecker",
          Set.of("NO_EMBEDDED_HEADING_IN_RESOLVED_BODY")),
      Map.entry("HEADING-FONT@BodyFormatRuleChecker",
          Set.of("NO_EMBEDDED_HEADING_IN_RESOLVED_BODY")),
      Map.entry("HEADING-SIZE@BodyFormatRuleChecker",
          Set.of("NO_EMBEDDED_HEADING_IN_RESOLVED_BODY")),
      Map.entry("HEAD-EMBEDDED@BodyFormatRuleChecker",
          Set.of("NO_EMBEDDED_HEADING_IN_RESOLVED_BODY")),
      Map.entry("TOC-SPACING@ContentsRuleChecker", Set.of("NO_NUMBERED_TOC_ENTRIES")),
      Map.entry("EN-TOC-01-BOLD@ContentsRuleChecker", Set.of("NO_ENGLISH_CONTENTS_TITLE")),
      Map.entry("EN-TOC-01-ALIGNMENT@ContentsRuleChecker", Set.of("NO_ENGLISH_CONTENTS_TITLE")),
      Map.entry("EN-TOC-01-FONT@ContentsRuleChecker", Set.of("NO_ENGLISH_CONTENTS_TITLE")),
      Map.entry("EN-TOC-01-SIZE@ContentsRuleChecker", Set.of("NO_ENGLISH_CONTENTS_TITLE")),
      Map.entry("EN-TOC-01-LINE@ContentsRuleChecker", Set.of("NO_ENGLISH_CONTENTS_TITLE")),
      Map.entry("APP-01@AppendixRuleModule",
          Set.of("OPTIONAL_APPENDIX_ABSENT_AFTER_END_MATTER_SCAN")),
      Map.entry("APP-02-BLANK@AppendixRuleModule",
          Set.of("OPTIONAL_APPENDIX_ABSENT_AFTER_END_MATTER_SCAN")),
      Map.entry("APP-03@AppendixRuleModule",
          Set.of("OPTIONAL_APPENDIX_ABSENT_AFTER_END_MATTER_SCAN")),
      Map.entry("APP-02-SPACING@HeadingDocumentRuleModule",
          Set.of("OPTIONAL_APPENDIX_ABSENT_AFTER_END_MATTER_SCAN")),
      Map.entry("APP-02-FONT@HeadingDocumentRuleModule",
          Set.of("OPTIONAL_APPENDIX_ABSENT_AFTER_END_MATTER_SCAN")),
      Map.entry("APP-02-SIZE@HeadingDocumentRuleModule",
          Set.of("OPTIONAL_APPENDIX_ABSENT_AFTER_END_MATTER_SCAN")),
      Map.entry("APP-02-ALIGNMENT@HeadingDocumentRuleModule",
          Set.of("OPTIONAL_APPENDIX_ABSENT_AFTER_END_MATTER_SCAN")));
  /**
   * Legacy result-only view. Callers that need the complete school-rule
   * inventory should use {@link #analyzeWithCatalog(List)}.
   */
  public List<RuleCoverage> analyze(List<CheckResult> results) {
    Map<String, MutableCoverage> grouped = new LinkedHashMap<>();
    for (CheckResult result : results) {
      String module = moduleFor(result.ruleId());
      String key = module + "\u0000" + result.ruleId();
      MutableCoverage coverage = grouped.computeIfAbsent(key,
          ignored -> new MutableCoverage(module, result.ruleId()));
      coverage.total++;
      if (result.judgement() == Judgement.PASS) coverage.passed++;
      else if (result.judgement() == Judgement.FAIL) coverage.failed++;
      else if (result.judgement() == Judgement.REVIEW) coverage.review++;
      if (result.expected() != null && !result.expected().isBlank()) coverage.evidence.add(result.expected());
    }
    List<RuleCoverage> output = new ArrayList<>();
    for (MutableCoverage value : grouped.values()) {
      String status = value.review > 0 ? "部分待确认" : "已执行";
      output.add(new RuleCoverage(value.module, value.ruleId, status, value.total, value.passed,
          value.failed, value.review, String.join("；", value.evidence.stream().limit(3).toList())));
    }
    return List.copyOf(output);
  }

  /**
   * Builds a complete coverage view from the catalog and result stream alone.
   * An absent result remains unknown until independent rule-run evidence exists.
   */
  public List<RuleCoverage> analyzeWithCatalog(List<CheckResult> results) {
    return analyzeWithCatalog(results, List.of());
  }

  /** Every catalog rule needs independent candidate evidence before it can show a terminal status. */
  public List<RuleCoverage> analyzeWithCatalog(List<CheckResult> results, List<RuleRun> ruleRuns) {
    Map<String, MutableCoverage> grouped = new LinkedHashMap<>();
    for (RuleCatalog.Entry entry : RuleCatalog.entries()) {
      grouped.put(entry.ruleId(), new MutableCoverage(entry.module(), entry.ruleId(), entry));
    }
    for (CheckResult result : results == null ? List.<CheckResult>of() : results) {
      if (result == null || result.ruleId() == null || result.ruleId().isBlank()) continue;
      RuleCatalog.Entry entry = catalogEntry(result.ruleId());
      MutableCoverage coverage = grouped.computeIfAbsent(result.ruleId(),
          ignored -> new MutableCoverage(moduleFor(result.ruleId()), result.ruleId(), entry));
      coverage.record(result);
    }

    Map<String, RuleRun> byOwner = new LinkedHashMap<>();
    Set<String> duplicateRules = new HashSet<>();
    for (RuleRun run : ruleRuns) {
      String key = run.ruleId() + "@" + run.moduleId();
      if (byOwner.putIfAbsent(key, run) != null) duplicateRules.add(run.ruleId());
    }
    for (MutableCoverage coverage : grouped.values()) {
      if (coverage.catalog == null || duplicateRules.contains(coverage.ruleId)) continue;
      List<String> owners = RuleCatalog.ownerModuleIds(coverage.ruleId);
      if (owners.isEmpty()) continue;
      boolean complete = true;
      boolean hasCandidates = false;
      for (String owner : owners) {
        RuleRun run = byOwner.get(coverage.ruleId + "@" + owner);
        if (run == null || run.unknownCount() != 0
            || run.checkedCount() != run.candidateCount()
            || (run.candidateCount() == 0
                && !APPROVED_NOT_APPLICABLE_REASONS.getOrDefault(coverage.ruleId + "@" + owner, Set.of())
                    .contains(run.notApplicableReason()))) {
          complete = false;
          break;
        }
        hasCandidates |= run.candidateCount() > 0;
      }
      if (complete && !(hasCandidates && coverage.onlyNotApplicableResults()))
        coverage.recordRunEvidence(hasCandidates);
    }

    List<RuleCoverage> output = new ArrayList<>(grouped.size());
    for (MutableCoverage value : grouped.values()) output.add(value.toCoverage());
    return List.copyOf(output);
  }

  private static RuleCatalog.Entry catalogEntry(String ruleId) {
    for (RuleCatalog.Entry entry : RuleCatalog.entries()) {
      if (entry.ruleId().equals(ruleId)) return entry;
    }
    return null;
  }

  private static String moduleFor(String ruleId) {
    RuleCatalog.RuleSpec catalog = RuleCatalog.find(ruleId);
    if (catalog != null) return catalog.module();
    if (ruleId == null) return "未登记规则";
    return "未登记规则";
  }

  private static final class MutableCoverage {
    private final String module;
    private final String ruleId;
    private final RuleCatalog.Entry catalog;
    private int total;
    private int passed;
    private int failed;
    private int review;
    private int executed;
    private int skipped;
    private int unsupported;
    private int errored;
    private boolean runEvidenceComplete;
    private boolean runHasCandidates;
    private final java.util.LinkedHashSet<String> evidence = new java.util.LinkedHashSet<>();
    private final java.util.LinkedHashSet<String> applicabilityValues = new java.util.LinkedHashSet<>();

    private MutableCoverage(String module, String ruleId) {
      this(module, ruleId, null);
    }

    private MutableCoverage(String module, String ruleId, RuleCatalog.Entry catalog) {
      this.module = module;
      this.ruleId = ruleId;
      this.catalog = catalog;
    }

    private void record(CheckResult result) {
      total++;
      boolean applicable = result.applicability() != Applicability.NOT_APPLICABLE;
      if (applicable && result.judgement() == Judgement.PASS) passed++;
      else if (applicable && result.judgement() == Judgement.FAIL) failed++;
      else if (applicable && result.judgement() == Judgement.REVIEW) review++;
      if (result.expected() != null && !result.expected().isBlank()) evidence.add(result.expected());
      if (result.applicability() != null) applicabilityValues.add(applicabilityLabel(result.applicability()));
      if (result.executionStatus() == ExecutionStatus.EXECUTED) executed++;
      else if (result.executionStatus() == ExecutionStatus.SKIPPED) skipped++;
      else if (result.executionStatus() == ExecutionStatus.UNSUPPORTED) unsupported++;
      else if (result.executionStatus() == ExecutionStatus.ERROR) errored++;
    }

    private void recordRunEvidence(boolean hasCandidates) {
      runEvidenceComplete = true;
      runHasCandidates = hasCandidates;
    }

    private boolean onlyNotApplicableResults() {
      return total > 0 && applicabilityValues.equals(Set.of("不适用"));
    }

    private RuleCoverage toCoverage() {
      String status;
      String executionStatus;
      if (total == 0) {
        status = runEvidenceComplete
            ? (runHasCandidates ? "已执行且无错误" : "不适用") : "未产生结果";
        executionStatus = runEvidenceComplete ? "已执行" : "无结果证据";
      } else {
        executionStatus = executionStatusLabel();
        status = judgementStatus();
      }

      if (total > 0) {
        if (applicabilityValues.equals(java.util.Set.of("不适用"))) {
          status = "不适用";
        } else if (applicabilityValues.contains("适用性未知")) {
          if (failed > 0) status = "已执行并发现问题，适用性未知";
          else if (review > 0) status = "证据不足，适用性未知";
          else status = "适用性未知";
        }
        if (catalog != null && !runEvidenceComplete) {
          if (failed > 0) status = "已发现问题，检查范围未确认";
          else if (review > 0) status = "证据不足，检查范围未确认";
          else if (applicabilityValues.contains("适用性未知"))
            status = "适用性及检查范围未确认";
          else if (applicabilityValues.equals(java.util.Set.of("不适用")))
            status = "已判断部分内容不适用，检查范围未确认";
          else status = "有检查结果，检查范围未确认";
        }
      }
      String scope = catalog == null ? "目录未登记；适用范围待核实" : catalog.scope();
      String applicability = applicabilityValues.isEmpty()
          ? (runEvidenceComplete ? (runHasCandidates ? "适用" : "不适用")
              : (catalog == null ? "本次结果已执行；适用范围待补充"
                  : "已登记；本次结果流无此规则记录，是否执行无法确认"))
          : String.join("、", applicabilityValues);
      String evidenceSource = catalog == null ? "规则依据尚未登记；请核对学校规范和实现来源" : catalog.evidence();
      String description = catalog == null
          ? "本次检查产生了此编号，但规则目录尚未登记。请确认编号有效，并补充规则说明、适用范围、依据和检查限制。"
          : catalog.spec().description();
      boolean annotationSupported = catalog != null && catalog.annotationSupported();
      String limitation = catalog == null
          ? "规则目录未登记，批注支持状态待确认；请以本次批注写入清单为准"
          : catalog.annotationLimitation();
      return new RuleCoverage(module, ruleId, status, total, passed, failed, review,
          String.join("；", evidence.stream().limit(3).toList()), scope, applicability,
          executionStatus, annotationSupported,
          limitation, description, evidenceSource,
          catalog == null ? "规则目录未登记，检查边界和规范依据尚未核实" : catalog.inspectionLimitation());
    }

    private String judgementStatus() {
      if (review > 0 && failed > 0) return "已执行并发现问题，部分待确认";
      if (review > 0) return "证据不足，待确认";
      if (failed > 0) return "已执行并发现问题";
      if (executed == 0 && (unsupported > 0 || skipped > 0 || errored > 0))
        return unsupported > 0 ? "证据不足，未支持当前文档" : "未执行完成";
      return "已执行且无错误";
    }

    private String executionStatusLabel() {
      if (executed == 0 && (unsupported > 0 || skipped > 0 || errored > 0))
        return unsupported > 0 ? "不支持" : (errored > 0 ? "执行失败" : "已跳过");
      if (unsupported > 0 || skipped > 0 || errored > 0) return "部分执行";
      return "已执行";
    }

    private static String applicabilityLabel(Applicability applicability) {
      return switch (applicability) {
        case APPLICABLE -> "适用";
        case NOT_APPLICABLE -> "不适用";
        case UNKNOWN -> "适用性未知";
      };
    }
  }
}
