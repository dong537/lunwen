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
    SourceAddress source) {}
