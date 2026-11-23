package cn.luwneshacha.thesis.inspection;

import cn.luwneshacha.thesis.rules.RuleSet;
import cn.luwneshacha.thesis.document.Docx4jDocumentParser;
import cn.luwneshacha.thesis.document.DocxReader;
import cn.luwneshacha.thesis.rendering.RenderedLayoutEvidence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class InspectionKernel {
  public InspectionReport inspect(Path source, RuleSet rules) {
    return inspect(source, rules, null);
  }

  public InspectionReport inspect(Path source, RuleSet rules, RenderedLayoutEvidence layout) {
    if (source == null || rules == null || !Files.isRegularFile(source)
        || !source.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".docx")) {
      throw new InspectionException("INVALID_DOCX", "input must be a .docx file");
    }
    try {
      var raw = new DocxReader().read(source);
      var parsed = new Docx4jDocumentParser().parse(raw);
      if (parsed.diagnostics().contains(cn.luwneshacha.thesis.document.DocumentClassifier.RULE_SPECIFICATION_DOCUMENT)) {
        throw new InspectionException("RULE_SPECIFICATION_DOCUMENT",
            "上传文件是学校论文格式规范文档，不能作为待检测论文；请上传需要筛查的论文 DOCX 文件");
      }
      var coverageAnalyzer = new DocumentCoverageAnalyzer();
      var preliminaryCoverage = coverageAnalyzer.analyze(raw, parsed, List.of(), List.of());
      if (requiresGlobalCompatibilityStop(preliminaryCoverage.uncheckedRegions())) {
        // When a compatibility branch changes the text or effective style,
        // checking the docx4j interpretation could create false hard errors.
        // Keep a report with explicit gaps, but do not run school rules.
        var execution = RuleExecutionEvidence.from(List.of(), List.of());
        return new InspectionReport(raw.sourceSha256(), rules.id(), List.of(), parsed.diagnostics(),
            parsed.tables(), parsed.equations(), parsed.references(), parsed.images(),
            parsed.pageNumbers(), parsed.chapterBreaks(), preliminaryCoverage.rules(),
            preliminaryCoverage.uncheckedRegions(), execution,
            layout == null ? null : layout.provenance());
      }
      // Rendered coordinates refer to the exact source bytes. LibreOffice formula
      // conversion can change the DOCX before parsing; never map its geometry here.
      var matchedLayout = layout != null && layout.matchesSource(parsed.sourceSha256()) ? layout : null;
      var checked = new cn.luwneshacha.thesis.rules.RuleEngine(matchedLayout,
          hasUntrustedFooter(preliminaryCoverage.uncheckedRegions()))
          .checkWithExecution(parsed, rules);
      var results = checked.results();
      var execution = RuleExecutionEvidence.from(checked.completedModuleIds(), checked.ruleRuns());
      var coverage = coverageAnalyzer.analyze(raw, parsed, results, execution.ruleRuns());
      return new InspectionReport(raw.sourceSha256(), rules.id(), results, parsed.diagnostics(),
          parsed.tables(), parsed.equations(), parsed.references(), parsed.images(), parsed.pageNumbers(), parsed.chapterBreaks(),
          coverage.rules(), coverage.uncheckedRegions(),
          execution, layout == null ? null : layout.provenance());
    } catch (InspectionException error) { throw error; }
    catch (RuntimeException error) {
      if (isUnmappableCompatibility(error))
        throw new InspectionException("COMPATIBILITY_CONTENT_UNSUPPORTED",
            "文档中的兼容内容无法准确定位。请在 Word 或 WPS 中另存为标准 DOCX 后重新检查。", error);
      throw new InspectionException("PARSER_FAILED", "DOCX parsing failed", error);
    }
  }

  private static boolean isUnmappableCompatibility(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      String message = cause.getMessage();
      if (message != null && (message.startsWith("cannot safely map compatibility paragraph")
          || message.startsWith("unable to parse DOCX: cannot safely map compatibility paragraph")))
        return true;
    }
    return false;
  }

  static boolean requiresGlobalCompatibilityStop(List<UncheckedRegion> regions) {
    return regions.stream().anyMatch(region -> region.kind() == CoverageKind.NOT_INSPECTED
        && region.region().startsWith("兼容内容分支")
        && !isFooterPart(region.partName()));
  }

  static boolean hasUntrustedFooter(List<UncheckedRegion> regions) {
    return regions.stream().anyMatch(region -> region.kind() == CoverageKind.NOT_INSPECTED
        && region.region().contains("兼容内容分支") && isFooterPart(region.partName()));
  }

  private static boolean isFooterPart(String partName) {
    return partName.matches("word/footer[^/]*[.]xml");
  }
}
