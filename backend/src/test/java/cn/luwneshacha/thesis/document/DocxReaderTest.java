package cn.luwneshacha.thesis.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.luwneshacha.thesis.inspection.InspectionException;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocxReaderTest {
  @TempDir Path tempDir;

  @Test
  void indexesRawPartsAndHashesTheSource() throws Exception {
    Path source = writeDocx("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>");

    RawDocx result = new DocxReader().read(source);

    assertEquals(64, result.sourceSha256().length());
    assertEquals(64, result.part("[Content_Types].xml").sha256().length());
  }

  @Test
  void rejectsDoctypeBeforeParsingXml() throws Exception {
    Path source = writeDocx("<!DOCTYPE foo><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>");

    var error = assertThrows(InspectionException.class, () -> new DocxReader().read(source));

    assertEquals("INVALID_DOCX", error.code());
  }

  @Test
  void rejectsZipExpansionAboveConfiguredLimit() throws Exception {
    Path source = writeDocx("x".repeat(200));

    var error = assertThrows(InspectionException.class,
        () -> new DocxReader(100).read(source));

    assertEquals("RESOURCE_LIMIT", error.code());
  }

  private Path writeDocx(String content) throws Exception {
    Path path = tempDir.resolve("sample.docx");
    try (var output = Files.newOutputStream(path); var zip = new ZipOutputStream(output)) {
      zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
      zip.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    return path;
  }
}
