package cn.luwneshacha.thesis.inspection;

import cn.luwneshacha.thesis.rules.RuleSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InspectionKernelSmokeTest {
  @TempDir Path tempDir;

  @Test
  void rejectsNonDocxBeforeParsing() throws Exception {
    Path input = tempDir.resolve("input.txt");
    Files.writeString(input, "not a docx");

    var error = assertThrows(
        InspectionException.class,
        () -> new InspectionKernel().inspect(input, RuleSet.schoolV1()));

    assertEquals("INVALID_DOCX", error.code());
  }
}
