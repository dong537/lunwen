package cn.luwneshacha.thesis.rules;

import cn.luwneshacha.thesis.document.SourceAddress;

public record CheckResult(
    String ruleId,
    Applicability applicability,
    ExecutionStatus executionStatus,
    Judgement judgement,
    String expected,
    String actual,
    String reasonCode,
    SourceAddress source,
    SourceAddress contextSource) {
  /** The source remains rule evidence; contextSource identifies its applicable document section. */
  public CheckResult(String ruleId, Applicability applicability, ExecutionStatus executionStatus,
      Judgement judgement, String expected, String actual, String reasonCode, SourceAddress source) {
    this(ruleId, applicability, executionStatus, judgement, expected, actual, reasonCode, source, null);
  }
}
