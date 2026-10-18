package cn.luwneshacha.thesis.inspection;

import cn.luwneshacha.thesis.rules.CheckResult;
import cn.luwneshacha.thesis.document.EquationModel;
import cn.luwneshacha.thesis.document.ReferenceEntryModel;
import cn.luwneshacha.thesis.document.TableModel;
import cn.luwneshacha.thesis.document.ImageModel;
import cn.luwneshacha.thesis.document.PageNumberModel;
import cn.luwneshacha.thesis.document.HeadingBreakModel;
import java.util.List;

public record InspectionReport(String sourceSha256, String ruleSetId, List<CheckResult> results, List<String> diagnostics,
    List<TableModel> tables, List<EquationModel> equations, List<ReferenceEntryModel> references,
    List<ImageModel> images, List<PageNumberModel> pageNumbers, List<HeadingBreakModel> chapterBreaks,
    List<RuleCoverage> ruleCoverage, List<UncheckedRegion> uncheckedRegions) {
  public InspectionReport {
    results = List.copyOf(results); diagnostics = List.copyOf(diagnostics);
    tables = List.copyOf(tables); equations = List.copyOf(equations); references = List.copyOf(references);
    images = List.copyOf(images);
    pageNumbers = List.copyOf(pageNumbers);
    chapterBreaks = List.copyOf(chapterBreaks);
    ruleCoverage = List.copyOf(ruleCoverage);
    uncheckedRegions = List.copyOf(uncheckedRegions);
  }

  public InspectionReport(String sourceSha256, String ruleSetId, List<CheckResult> results, List<String> diagnostics) {
    this(sourceSha256, ruleSetId, results, diagnostics, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
  }

  public InspectionReport(String sourceSha256, String ruleSetId, List<CheckResult> results, List<String> diagnostics,
      List<TableModel> tables, List<EquationModel> equations, List<ReferenceEntryModel> references) {
    this(sourceSha256, ruleSetId, results, diagnostics, tables, equations, references, List.of(), List.of(), List.of(), List.of(), List.of());
  }

  public InspectionReport(String sourceSha256, String ruleSetId, List<CheckResult> results, List<String> diagnostics,
      List<TableModel> tables, List<EquationModel> equations, List<ReferenceEntryModel> references, List<ImageModel> images) {
    this(sourceSha256, ruleSetId, results, diagnostics, tables, equations, references, images, List.of(), List.of(), List.of(), List.of());
  }

}
