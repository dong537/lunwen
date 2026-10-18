package cn.luwneshacha.thesis.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
  void rejectsExternalNonHyperlinkRelationshipsWithSingleQuotesAndAttributeWhitespace() throws Exception {
    String relationships = "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
        + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/attachedTemplate\" "
        + "TargetMode = 'eXtErNaL' Target=\"https://example.invalid/\"/></Relationships>";
    Path source = writeDocx("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>", relationships);

    var error = assertThrows(InspectionException.class, () -> new DocxReader().read(source));

    assertEquals("INVALID_DOCX", error.code());
  }

  @Test
  void acceptsStandardExternalHyperlinkRelationshipsWithoutFetchingThem() throws Exception {
    String relationships = "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
        + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink\" "
        + "TargetMode=\"External\" Target=\"https://example.invalid/reference\"/></Relationships>";
    Path source = writeDocx("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>", relationships);

    RawDocx result = new DocxReader().read(source);

    assertTrue(result.parts().containsKey("word/_rels/document.xml.rels"));
  }

  @Test
  void acceptsStrictOoXmlExternalHyperlinkRelationshipsWithoutFetchingThem() throws Exception {
    String relationships = "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
        + "<Relationship Id=\"rId1\" Type=\"http://purl.oclc.org/ooxml/officeDocument/relationships/hyperlink\" "
        + "TargetMode=\"External\" Target=\"https://example.invalid/reference\"/></Relationships>";
    Path source = writeDocx("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>", relationships);

    RawDocx result = new DocxReader().read(source);

    assertTrue(result.parts().containsKey("word/_rels/document.xml.rels"));
  }

  @Test
  void rejectsZipExpansionAboveConfiguredLimit() throws Exception {
    Path source = writeDocx("x".repeat(200));

    var error = assertThrows(InspectionException.class,
        () -> new DocxReader(100).read(source));

    assertEquals("RESOURCE_LIMIT", error.code());
  }

  @Test
  void rejectsCompressedPackageAboveConfiguredLimitBeforeReadingIt() throws Exception {
    Path source = tempDir.resolve("oversized.docx");
    Files.write(source, new byte[8]);

    var error = assertThrows(InspectionException.class,
        () -> new DocxReader(100, 100, 7).read(source));

    assertEquals("RESOURCE_LIMIT", error.code());
  }

  @Test
  void rejectsAggregateZipExpansionAcrossManySmallParts() throws Exception {
    Path source = writeExpandedDocx();

    var error = assertThrows(InspectionException.class, () -> new DocxReader().read(source));

    assertEquals("RESOURCE_LIMIT", error.code());
  }

  private Path writeExpandedDocx() throws Exception {
    Path path = tempDir.resolve("expanded.docx");
    byte[] part = new byte[15 * 1024 * 1024];
    try (var output = Files.newOutputStream(path); var zip = new ZipOutputStream(output)) {
      zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
      zip.write("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>"
          .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      zip.closeEntry();
      for (int index = 0; index < 4; index++) {
        zip.putNextEntry(new ZipEntry("word/media/part" + index + ".bin"));
        zip.write(part);
        zip.closeEntry();
      }
    }
    return path;
  }

  private Path writeDocx(String content) throws Exception {
    return writeDocx(content, null);
  }

  private Path writeDocx(String content, String relationships) throws Exception {
    Path path = tempDir.resolve("sample.docx");
    try (var output = Files.newOutputStream(path); var zip = new ZipOutputStream(output)) {
      zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
      zip.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      zip.closeEntry();
      if (relationships != null) {
        zip.putNextEntry(new ZipEntry("word/_rels/document.xml.rels"));
        zip.write(relationships.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    }
    return path;
  }
}
