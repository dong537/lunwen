package cn.luwneshacha.thesis.rules;

/** Independent evidence that a module assessed the candidates for one rule. */
public record RuleRun(String ruleId, String moduleId, int candidateCount, int checkedCount,
    int unknownCount, String notApplicableReason) {
  public RuleRun {
    if (ruleId == null || ruleId.isBlank() || moduleId == null || moduleId.isBlank())
      throw new IllegalArgumentException("rule and module ids are required");
    if (candidateCount < 0 || checkedCount < 0 || unknownCount < 0
        || (long) checkedCount + unknownCount != candidateCount)
      throw new IllegalArgumentException("rule run counts must reconcile");
    if (candidateCount == 0) {
      if (notApplicableReason == null || notApplicableReason.isBlank())
        throw new IllegalArgumentException("zero candidates require a reason");
    } else if (notApplicableReason != null) {
      throw new IllegalArgumentException("applicable candidates cannot have a not-applicable reason");
    }
  }
}
