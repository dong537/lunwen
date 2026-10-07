package cn.luwneshacha.thesis.inspection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

public final class InspectionKernel {
  public InspectionReport inspect(Path source, RuleSet rules) {
    if (source == null || rules == null || !Files.isRegularFile(source)
        || !source.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".docx")) {
      throw new InspectionException("INVALID_DOCX", "input must be a .docx file");
    }
    try (ZipFile ignored = new ZipFile(source.toFile())) {
      throw new UnsupportedOperationException("parsing is implemented in the next task");
    } catch (IOException error) {
      throw new InspectionException("INVALID_DOCX", "input is not a readable DOCX package");
    }
  }
}