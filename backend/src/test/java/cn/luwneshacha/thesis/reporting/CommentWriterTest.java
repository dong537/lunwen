package cn.luwneshacha.thesis.reporting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.luwneshacha.thesis.document.SourceAddress;
import cn.luwneshacha.thesis.rules.Applicability;
import cn.luwneshacha.thesis.rules.CheckResult;
import cn.luwneshacha.thesis.rules.ExecutionStatus;
import cn.luwneshacha.thesis.rules.Judgement;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommentWriterTest {
  @TempDir Path tempDir;

  @Test
  void writesCommentToDirectBodyParagraphAndLeavesOtherPartsUntouched() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr><w:r><w:t>正文</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("annotated.docx");
    var result = result(List.of(0, 0));

    AnnotationReport report = new CommentWriter().writeCopy(source, target, List.of(result));

    assertEquals(AnnotationStatus.WRITTEN, report.items().get(0).status());
    try (ZipFile zip = new ZipFile(target.toFile())) {
      String document = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(document.contains("commentRangeStart"));
      assertTrue(document.indexOf("w:pPr") < document.indexOf("commentRangeStart"));
      assertTrue(document.contains("commentReference"));
      assertTrue(comments.contains("页边距不符合要求，请按规范调整为 2.6 cm。"));
      assertTrue(zip.getEntry("word/_rels/document.xml.rels") != null);
    }
  }

  @Test
  void rejectsChangedSourceHashBeforeWriting() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>正文</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("annotated.docx");

    var report = new CommentWriter().writeCopy(source, target, "0".repeat(64), List.of(result(List.of(0, 0))));

    assertEquals(AnnotationStatus.REJECTED, report.items().get(0).status());
    assertTrue(Files.notExists(target));
  }

  @Test
  void writesCommentToTableParagraphByStructuralPath() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:tbl><w:tr><w:tc><w:p><w:r><w:t>表格内容</w:t></w:r></w:p></w:tc></w:tr></w:tbl></w:body></w:document>");
    Path target = tempDir.resolve("table-annotated.docx");

    AnnotationReport report = new CommentWriter().writeCopy(source, target, List.of(result(List.of(0, 0, 0, 0, 0))));

    assertEquals(AnnotationStatus.WRITTEN, report.items().get(0).status());
    assertTrue(Files.exists(target));
  }

  @Test
  void writesCommentToDrawingOnlyParagraph() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:drawing><wp:inline xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\"/></w:drawing></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("drawing-annotated.docx");

    AnnotationReport report = new CommentWriter().writeCopy(source, target, List.of(result(List.of(0, 0))));

    assertEquals(AnnotationStatus.WRITTEN, report.items().get(0).status());
    assertTrue(Files.exists(target));
  }

  @Test
  void usesFigureSpecificCommentAndHighlight() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:drawing/></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("figure-annotated.docx");
    CheckResult finding = new CheckResult("FIG-01", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "图号和图题", null, "FIGURE_CAPTION_MISSING", new SourceAddress("word/document.xml", List.of(0, 0), 0, 0));

    new CommentWriter().writeCopy(source, target, List.of(finding));

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String document = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(document.contains("w:val=\"red\""));
      assertTrue(comments.contains("缺少图题"));
    }
  }

  private CheckResult result(List<Integer> path) {
    return new CheckResult("PAGE-01", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "1474", "1418", "MARGIN_MISMATCH",
        new SourceAddress("word/document.xml", path, 0, 2));
  }

  private Path packageWithDocument(String document) throws Exception {
    Path source = tempDir.resolve("source.docx");
    try (var output = Files.newOutputStream(source); var zip = new ZipOutputStream(output)) {
      put(zip, "[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Override PartName=\"/word/document.xml\" ContentType=\"application/xml\"/></Types>");
      put(zip, "word/document.xml", document);
      put(zip, "word/_rels/document.xml.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"/>");
    }
    return source;
  }

  private void put(ZipOutputStream zip, String name, String text) throws Exception {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(text.getBytes(StandardCharsets.UTF_8));
    zip.closeEntry();
  }
}
