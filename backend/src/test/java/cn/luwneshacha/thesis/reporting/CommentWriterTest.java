package cn.luwneshacha.thesis.reporting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.luwneshacha.thesis.document.SourceAddress;
import cn.luwneshacha.thesis.rules.Applicability;
import cn.luwneshacha.thesis.rules.CheckResult;
import cn.luwneshacha.thesis.rules.ExecutionStatus;
import cn.luwneshacha.thesis.rules.Judgement;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
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
  void writesSpecificColorAndNaturalSuggestionForCoverFormatError() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>论文题目</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("cover-annotated.docx");
    var finding = new CheckResult("COVER-02-FONT", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "黑体", "宋体", "FONT_MISMATCH",
        new SourceAddress("word/document.xml", List.of(0, 0), 0, 4));

    AnnotationReport report = new CommentWriter().writeCopy(source, target, List.of(finding));

    assertEquals(AnnotationStatus.WRITTEN, report.items().get(0).status());
    try (ZipFile zip = new ZipFile(target.toFile())) {
      String document = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(document.contains("w:val=\"cyan\""));
      assertTrue(comments.contains("论文题目：改为黑体。"));
    }
  }

  @Test
  void usesWordSupportedHighlightValuesForIndentAndLineSpacingErrors() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:p><w:r><w:t>正文段落</w:t></w:r></w:p><w:p><w:r><w:t>摘要行距</w:t></w:r></w:p>"
        + "</w:body></w:document>");
    Path target = tempDir.resolve("valid-highlight-values.docx");
    var findings = List.of(
        new CheckResult("BODY-INDENT", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
            Judgement.FAIL, "480", "0", "INDENT_MISMATCH", new SourceAddress("word/document.xml", List.of(0, 0), 0, 4)),
        new CheckResult("ABSTRACT-LINE", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
            Judgement.FAIL, "360", "240", "LINE_SPACING_MISMATCH", new SourceAddress("word/document.xml", List.of(0, 1), 0, 4)));

    AnnotationReport report = new CommentWriter().writeCopy(source, target, findings);

    assertTrue(report.outputWritten());
    try (ZipFile zip = new ZipFile(target.toFile())) {
      String document = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(document.contains("w:val=\"green\""));
      assertTrue(document.contains("w:val=\"darkMagenta\""));
      assertFalse(document.contains("brightGreen"));
      assertFalse(document.contains("purple"));
    }
  }

  @Test
  void replacesAnExistingRunHighlightInsteadOfWritingDuplicateProperties() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p>"
        + "<w:r><w:rPr><w:highlight w:val=\"cyan\"/><w:b/></w:rPr><w:t>正文内容</w:t></w:r>"
        + "</w:p></w:body></w:document>");
    Path target = tempDir.resolve("single-highlight-property.docx");

    new CommentWriter().writeCopy(source, target, List.of(result(List.of(0, 0))));

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String document = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertEquals(1, Pattern.compile("<w:highlight\\b").matcher(document).results().count());
      assertTrue(document.contains("w:val=\"magenta\""));
    }
  }

  @Test
  void writesTheCurrentUtcTimestampOnNewComments() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>正文</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("dated-annotated.docx");
    Instant before = Instant.now().minusSeconds(1);

    new CommentWriter().writeCopy(source, target, List.of(result(List.of(0, 0))));

    Instant after = Instant.now().plusSeconds(1);
    try (ZipFile zip = new ZipFile(target.toFile())) {
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8);
      var matcher = Pattern.compile("w:date=\"([^\"]+)\"").matcher(comments);
      assertTrue(matcher.find());
      Instant commentDate = Instant.parse(matcher.group(1));
      assertTrue(!commentDate.isBefore(before) && !commentDate.isAfter(after));
    }
  }

  @Test
  void writesAppendixNumberingIssueIntoWordComment() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>图B1 示例</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("appendix-annotated.docx");
    var finding = new CheckResult("APP-01", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "图A", "图B1 示例", "APPENDIX_OBJECT_LETTER_MISMATCH",
        new SourceAddress("word/document.xml", List.of(0, 0), 0, 7));

    AnnotationReport report = new CommentWriter().writeCopy(source, target, List.of(finding));

    assertEquals(AnnotationStatus.WRITTEN, report.items().get(0).status());
    try (ZipFile zip = new ZipFile(target.toFile())) {
      String document = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(document.contains("w:val=\"red\""));
      assertTrue(comments.contains("附录中的图、表或公式编号字母应与本附录一致。"));
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
  void reportsOnlyWriteFailureWhenAnnotatedCopyCannotBeCreated() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>正文</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("missing-directory").resolve("annotated.docx");

    AnnotationReport report = new CommentWriter().writeCopy(source, target, List.of(result(List.of(0, 0))));

    assertFalse(report.outputWritten());
    assertEquals(1, report.items().size());
    assertEquals(AnnotationStatus.REJECTED, report.items().get(0).status());
    assertEquals("WRITE_FAILED", report.items().get(0).reason());
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
        Judgement.FAIL, "body reference to figure", "1-1", "FIGURE_NOT_CITED_IN_BODY", new SourceAddress("word/document.xml", List.of(0, 0), 0, 0));

    new CommentWriter().writeCopy(source, target, List.of(finding));

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String document = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(document.contains("w:val=\"red\""));
      assertTrue(comments.contains("没有找到这幅图的引用"));
    }
  }

  @Test
  void usesAbstractSpecificCommentAndHighlight() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>摘要内容</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("abstract-annotated.docx");
    var finding = new CheckResult("ABSTRACT-SIZE", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "24", "18", "", new SourceAddress("word/document.xml", List.of(0, 0), 0, 4));

    new CommentWriter().writeCopy(source, target, List.of(finding));

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String document = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(document.contains("w:val=\"yellow\""));
      assertTrue(comments.contains("摘要字号应为小四，请检查。"));
    }
  }

  @Test
  void writesNaturalChineseKeywordSuggestions() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>关键词：甲；乙</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("keywords-annotated.docx");
    var anchor = new SourceAddress("word/document.xml", List.of(0, 0), 0, 8);
    var findings = List.of(
        new CheckResult("ABSTRACT-KEYWORD-BOLD", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
            Judgement.FAIL, "true", "false", "KEYWORD_LABEL_NOT_BOLD", anchor),
        new CheckResult("ABSTRACT-KEYWORD-FORMAT", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
            Judgement.FAIL, "3-5 terms", "2 terms", "KEYWORD_LIST_MISMATCH", anchor));

    new CommentWriter().writeCopy(source, target, findings);

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(comments.contains("“关键词”标签需要加粗，后面的词不用加粗。"));
      assertTrue(comments.contains("关键词格式不符合要求，请检查冒号、数量和分号。"));
    }
  }

  @Test
  void writesShortCitationFormattingSuggestion() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>[1]</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("citation-annotated.docx");
    var finding = new CheckResult("CITE-01", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "24 / Times New Roman / superscript", "format mismatch", "CITATION_FORMAT_MISMATCH",
        new SourceAddress("word/document.xml", List.of(0, 0), 0, 3));

    new CommentWriter().writeCopy(source, target, List.of(finding));

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(comments.contains("\u5f15\u7528\u7f16\u53f7\u8bf7\u8bbe\u4e3a\u5c0f\u56db Times New Roman \u4e0a\u6807\u3002"));
    }
  }

  @Test
  void writesEquationReferenceFindingWithItsOwnColorAndComment() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>(3-1)</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("equation-reference-annotated.docx");
    var finding = new CheckResult("EQ-04", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "body reference to equation 3-1", null, "EQUATION_NOT_REFERENCED",
        new SourceAddress("word/document.xml", List.of(0, 0), 0, 5));

    new CommentWriter().writeCopy(source, target, List.of(finding));

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String document = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(document.contains("w:val=\"red\""));
      assertTrue(comments.contains("正文里没有找到这条公式的引用"));
    }
  }

  @Test
  void reportsUnsupportedStoryAnchorWithItsOriginalAddress() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>正文</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("unsupported-annotated.docx");
    var anchor = new SourceAddress("word/footer1.xml", List.of(0, 1), 0, 4);
    var finding = new CheckResult("PAGE-05", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "center", "left", "PAGE_NUMBER_NOT_CENTERED", anchor);

    AnnotationReport report = new CommentWriter().writeCopy(source, target, List.of(finding));

    assertEquals(false, report.outputWritten());
    assertEquals(AnnotationStatus.UNSUPPORTED_ANCHOR, report.items().get(0).status());
    assertEquals("PART_NOT_SUPPORTED", report.items().get(0).reason());
    assertEquals(anchor, report.items().get(0).source());
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
