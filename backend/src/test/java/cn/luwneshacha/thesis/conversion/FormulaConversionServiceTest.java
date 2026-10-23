package cn.luwneshacha.thesis.conversion;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FormulaConversionServiceTest {
  @TempDir Path temp;

  @Test
  void rejectsUnconvertedOleEvenWhenOmmlCountIsSufficient() throws Exception {
    String ole = "<w:object><o:OLEObject ProgID=\"Equation.3\"/></w:object>";
    Path source = docx(ole);
    Path converted = docx("<m:oMath><m:r><m:t>x</m:t></m:r></m:oMath>" + ole);
    assertThrows(IllegalStateException.class,
        () -> FormulaConversionService.validateConversion(source, converted));
  }

  @Test
  void rejectsChangedExistingFormulaDespiteMatchingFormulaCount() throws Exception {
    Path source = docx("<m:oMath><m:r><m:t>x+y</m:t></m:r></m:oMath>"
        + "<w:object><o:OLEObject ProgID=\"Equation.3\"/></w:object>");
    Path converted = docx("<m:oMath><m:r><m:t>x-y</m:t></m:r></m:oMath>"
        + "<m:oMath><m:r><m:t>z</m:t></m:r></m:oMath>");
    assertThrows(IllegalStateException.class,
        () -> FormulaConversionService.validateConversion(source, converted));
  }

  @Test
  void acceptsExistingFormulaWithChangedRunBoundaries() throws Exception {
    Path source = docx("<m:oMath><m:r><m:t>x+y</m:t></m:r></m:oMath>"
        + "<w:object><o:OLEObject ProgID=\"Equation.3\"/></w:object>");
    Path converted = docx("<m:oMath><m:r><m:t>x</m:t></m:r><m:r><m:t>+y</m:t></m:r></m:oMath>"
        + "<m:oMath><m:r><m:t>z</m:t></m:r></m:oMath>");
    assertDoesNotThrow(() -> FormulaConversionService.validateConversion(source, converted));
  }

  @Test
  void rejectsConversionThatLosesOpaqueFormulaEvidence() throws Exception {
    Path source = docx("<w:object><o:OLEObject ProgID=\"Equation.3\"/></w:object>");
    Path converted = docx("");

    assertThrows(IllegalStateException.class,
        () -> FormulaConversionService.validateConversion(source, converted));
  }

  @Test
  void acceptsConversionWithEquivalentOmmlEvidence() throws Exception {
    Path source = docx("<w:object><o:OLEObject ProgID=\"Equation.3\"/></w:object>");
    Path converted = docx("<m:oMathPara><m:oMath><m:r><m:t>x</m:t></m:r></m:oMath></m:oMathPara>");

    assertDoesNotThrow(() -> FormulaConversionService.validateConversion(source, converted));
  }

  @Test
  void rejectsEmptyOmmlNodeAsSuccessfulFormulaConversion() throws Exception {
    Path source = docx("<w:object><o:OLEObject ProgID=\"Equation.3\"/></w:object>");
    Path converted = docx("<m:oMathPara><m:oMath/></m:oMathPara>");

    assertThrows(IllegalStateException.class,
        () -> FormulaConversionService.validateConversion(source, converted));
  }

  @Test
  void requiresPreservingExistingOmmlAlongsideConvertedOleFormulas() throws Exception {
    Path source = docx("<m:oMath><m:r><m:t>x</m:t></m:r></m:oMath>"
        + "<w:object><o:OLEObject ProgID=\"Equation.3\"/></w:object>");
    Path converted = docx("<m:oMath><m:r><m:t>x</m:t></m:r></m:oMath>");

    assertThrows(IllegalStateException.class,
        () -> FormulaConversionService.validateConversion(source, converted));
  }

  private Path docx(String body) throws Exception {
    Path file = temp.resolve("formula-" + java.util.UUID.randomUUID() + ".docx");
    try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
      zip.putNextEntry(new ZipEntry("word/document.xml"));
      zip.write(("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""
          + " xmlns:m=\"http://schemas.openxmlformats.org/officeDocument/2006/math\""
          + " xmlns:o=\"urn:schemas-microsoft-com:office:office\">"
          + "<w:body>" + body + "</w:body></w:document>").getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    return file;
  }
}
