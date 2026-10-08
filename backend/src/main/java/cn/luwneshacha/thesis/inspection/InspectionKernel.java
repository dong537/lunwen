package cn.luwneshacha.thesis.inspection;

import cn.luwneshacha.thesis.rules.RuleSet;
import cn.luwneshacha.thesis.document.Docx4jDocumentParser;
import cn.luwneshacha.thesis.document.DocxReader;

import java.nio.file.Files;
import java.nio.file.Path;

public final class InspectionKernel {
  public InspectionReport inspect(Path source, RuleSet rules) {
    if (source == null || rules == null || !Files.isRegularFile(source)
        || !source.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".docx")) {
      throw new InspectionException("INVALID_DOCX", "input must be a .docx file");
    }
    try {
      var raw = new DocxReader().read(source);
      var parsed = new Docx4jDocumentParser().parse(raw);
      var results = new cn.luwneshacha.thesis.rules.RuleEngine().check(parsed, rules);
      return new InspectionReport(raw.sourceSha256(), rules.id(), results, parsed.diagnostics(),
          parsed.tables(), parsed.equations(), parsed.references(), parsed.images(), parsed.pageNumbers(), parsed.chapterBreaks());
    } catch (InspectionException error) { throw error; }
    catch (RuntimeException error) { throw new InspectionException("PARSER_FAILED", "DOCX parsing failed"); }
  }
}
