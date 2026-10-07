package cn.luwneshacha.thesis.inspection;

import cn.luwneshacha.thesis.rules.CheckResult;
import java.util.List;

public record InspectionReport(String sourceSha256, String ruleSetId, List<CheckResult> results, List<String> diagnostics) {
  public InspectionReport { results = List.copyOf(results); diagnostics = List.copyOf(diagnostics); }
}
