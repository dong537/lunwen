package cn.luwneshacha.platform.report;

import java.io.IOException;
import java.nio.file.Path;

@FunctionalInterface
public interface PdfSummaryReportGenerator {
  void write(byte[] auditJson, PdfSummaryReportWriter.Metadata metadata, Path target) throws IOException;
}
