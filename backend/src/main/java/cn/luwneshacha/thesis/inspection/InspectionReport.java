package cn.luwneshacha.thesis.inspection;

import cn.luwneshacha.thesis.rules.CheckResult;
import cn.luwneshacha.thesis.document.EquationModel;
import cn.luwneshacha.thesis.document.ReferenceEntryModel;
import cn.luwneshacha.thesis.document.TableModel;
import java.util.List;

public record InspectionReport(String sourceSha256, String ruleSetId, List<CheckResult> results, List<String> diagnostics,
    List<TableModel> tables, List<EquationModel> equations, List<ReferenceEntryModel> references) {
  public InspectionReport {
    results = List.copyOf(results); diagnostics = List.copyOf(diagnostics);
    tables = List.copyOf(tables); equations = List.copyOf(equations); references = List.copyOf(references);
  }

  public InspectionReport(String sourceSha256, String ruleSetId, List<CheckResult> results, List<String> diagnostics) {
    this(sourceSha256, ruleSetId, results, diagnostics, List.of(), List.of(), List.of());
  }
}
