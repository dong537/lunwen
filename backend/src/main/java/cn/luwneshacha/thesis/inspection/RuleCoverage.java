package cn.luwneshacha.thesis.inspection;

/** A rule execution aggregate shown in the inspection report. */
public record RuleCoverage(String module, String ruleId, String status,
    int total, int passed, int failed, int review, String evidence,
    String scope, String applicability, String executionStatus,
    boolean annotationSupported, String annotationLimitation,
    String ruleDescription, String evidenceSource, String inspectionLimitation) {
  public RuleCoverage {
    if (module == null || module.isBlank()) throw new IllegalArgumentException("module is required");
    if (ruleId == null || ruleId.isBlank()) throw new IllegalArgumentException("ruleId is required");
    if (status == null || status.isBlank()) throw new IllegalArgumentException("status is required");
    if (total < 0 || passed < 0 || failed < 0 || review < 0)
      throw new IllegalArgumentException("coverage counts must be non-negative");
    if (passed + failed + review > total)
      throw new IllegalArgumentException("coverage counts exceed total");
    evidence = evidence == null ? "" : evidence;
    scope = scope == null ? "" : scope;
    applicability = applicability == null ? "" : applicability;
    executionStatus = executionStatus == null ? "" : executionStatus;
    annotationLimitation = annotationLimitation == null ? "" : annotationLimitation;
    ruleDescription = ruleDescription == null ? "" : ruleDescription;
    evidenceSource = evidenceSource == null ? "" : evidenceSource;
    inspectionLimitation = inspectionLimitation == null ? "" : inspectionLimitation;
  }

  /** Constructor retained for callers using the coverage metadata fields before the catalog split. */
  public RuleCoverage(String module, String ruleId, String status, int total, int passed,
      int failed, int review, String evidence, String scope, String applicability,
      String executionStatus, boolean annotationSupported, String annotationLimitation) {
    this(module, ruleId, status, total, passed, failed, review, evidence, scope, applicability,
        executionStatus, annotationSupported, annotationLimitation, "", "", "");
  }

  public RuleCoverage(String module, String ruleId, String status, String evidence) {
    this(module, ruleId, status, 0, 0, 0, 0, evidence, "", "", "", true, "", "", "", "");
  }

  /** Backwards-compatible constructor used by report tests and integrations. */
  public RuleCoverage(String module, String ruleId, String status, int total, int passed,
      int failed, int review, String evidence) {
    this(module, ruleId, status, total, passed, failed, review, evidence,
        "", "", "", true, "", "", "", "");
  }
}
