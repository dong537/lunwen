package cn.luwneshacha.thesis.reporting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import cn.luwneshacha.thesis.document.Docx4jDocumentParser;
import cn.luwneshacha.thesis.document.DocxReader;
import cn.luwneshacha.thesis.document.SourceAddress;
import cn.luwneshacha.thesis.rules.Applicability;
import cn.luwneshacha.thesis.rules.CheckResult;
import cn.luwneshacha.thesis.rules.ExecutionStatus;
import cn.luwneshacha.thesis.rules.Judgement;
import java.nio.file.Path;
import java.util.List;
import org.docx4j.XmlUtils;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.openpackaging.parts.PartName;
import org.docx4j.openpackaging.parts.WordprocessingML.CommentsPart;
import org.docx4j.wml.Comments;
import org.docx4j.wml.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommentWriterReadbackTest {
  private static final String NS = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"";
  @TempDir Path temp;

  @Test
  void tableBorderCommentsReadBackOnlyTheMissingLinesAndSpecificRepairAdvice() throws Exception {
    Path source = validTableDocx();
    var raw = new DocxReader().read(source);
    var anchor = new Docx4jDocumentParser().parse(raw).tables().get(0).source();
    String[][] cases = {
        {"表头下横线", "表头行的下边框"},
        {"顶线、底线", "首行的上边框、末行的下边框"},
        {"顶线、表头下横线、底线", "首行的上边框、表头行的下边框、末行的下边框"}
    };
    for (String reason : List.of("TABLE_BORDER_INVALID", "TABLE_CONTINUATION_BORDER_INVALID")) {
      for (int index = 0; index < cases.length; index++) {
        String missing = cases[index][0];
        var finding = new CheckResult("TAB-02", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
            Judgement.FAIL, missing, "缺少符合规范的" + missing, reason, anchor);
        Path target = temp.resolve(reason + "-" + index + ".docx");

        AnnotationReport report = new CommentWriter().writeCopy(source, target, raw.sourceSha256(), List.of(finding));

        assertEquals(AnnotationStatus.WRITTEN, report.items().get(0).status());
        var threads = CommentWriter.readThreads(target);
        assertEquals(1, threads.size());
        assertEquals("缺少符合规范的" + missing + "。请在 Word 中为" + cases[index][1] + "补上横线。",
            threads.get(0).text());
        var pkg = WordprocessingMLPackage.load(target.toFile());
        var part = (CommentsPart) pkg.getParts().getParts().get(new PartName("/word/comments.xml"));
        assertEquals(1, part.getContents().getComment().size());
      }
    }
  }

  @Test
  void docx4jReadsGeneratedCommentAndMainDocumentRelationship() throws Exception {
    Path source = validDocx();
    var raw = new DocxReader().read(source);
    var parsed = new Docx4jDocumentParser().parse(raw);
    var anchor = parsed.paragraphs().get(0).source();
    var finding = new CheckResult("PAGE-01", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "1474", "1418", "MARGIN_MISMATCH", anchor);
    Path target = temp.resolve("annotated.docx");

    AnnotationReport report = new CommentWriter().writeCopy(source, target, raw.sourceSha256(), List.of(finding));
    var pkg = WordprocessingMLPackage.load(target.toFile());
    var part = pkg.getParts().getParts().get(new PartName("/word/comments.xml"));

    assertEquals(AnnotationStatus.WRITTEN, report.items().get(0).status());
    assertInstanceOf(CommentsPart.class, part);
    Comments comments = ((CommentsPart) part).getContents();
    assertEquals(1, comments.getComment().size());
    assertEquals("\u8bba\u6587\u683c\u5f0f\u7b5b\u67e5", comments.getComment().get(0).getAuthor());
    var relations = pkg.getMainDocumentPart().getRelationshipsPart().getRelationships().getRelationship();
    var commentRelation = relations.stream().filter(r -> r.getType().endsWith("/comments")).findFirst().orElseThrow();
    assertSame(part, pkg.getMainDocumentPart().getRelationshipsPart().getPart(commentRelation));
  }

  @Test
  void refreshesExistingSystemCommentsWithoutDuplicatingRelationship() throws Exception {
    Path source = validDocx();
    var raw = new DocxReader().read(source);
    var anchor = new Docx4jDocumentParser().parse(raw).paragraphs().get(0).source();
    var finding = new CheckResult("PAGE-01", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "1474", "1418", "MARGIN_MISMATCH", anchor);
    Path first = temp.resolve("first.docx");
    Path second = temp.resolve("second.docx");
    new CommentWriter().writeCopy(source, first, List.of(finding));
    var secondRaw = new DocxReader().read(first);
    AnnotationReport report = new CommentWriter().writeCopy(first, second, secondRaw.sourceSha256(), List.of(finding));

    var pkg = WordprocessingMLPackage.load(second.toFile());
    var part = (CommentsPart) pkg.getParts().getParts().get(new PartName("/word/comments.xml"));
    var relations = pkg.getMainDocumentPart().getRelationshipsPart().getRelationships().getRelationship();

    assertEquals(AnnotationStatus.WRITTEN, report.items().get(0).status());
    assertEquals(1, part.getContents().getComment().size());
    assertEquals(1, relations.stream().filter(r -> r.getType().endsWith("/comments")).count());
    try (var zip = new java.util.zip.ZipFile(second.toFile())) {
      String document = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(),
          java.nio.charset.StandardCharsets.UTF_8);
      assertEquals(1, document.split("commentRangeStart", -1).length - 1);
      assertEquals(1, document.split("commentReference", -1).length - 1);
    }
  }

  @Test
  void mergesDifferentErrorsAtSameParagraphIntoOneCommentRange() throws Exception {
    Path source = validDocx();
    var raw = new DocxReader().read(source);
    var anchor = new Docx4jDocumentParser().parse(raw).paragraphs().get(0).source();
    var margin = new CheckResult("PAGE-01", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "1474", "1418", "MARGIN_MISMATCH", anchor);
    var size = new CheckResult("BODY-SIZE", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "24", "32", "SIZE_MISMATCH", anchor);
    Path target = temp.resolve("merged.docx");

    new CommentWriter().writeCopy(source, target, raw.sourceSha256(), List.of(margin, size));

    var pkg = WordprocessingMLPackage.load(target.toFile());
    var comments = ((CommentsPart) pkg.getParts().getParts().get(new PartName("/word/comments.xml"))).getContents();
    assertEquals(1, comments.getComment().size());
    try (var zip = new java.util.zip.ZipFile(target.toFile())) {
      String document = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      assertEquals(1, document.split("commentRangeStart", -1).length - 1);
    }
  }
  private Path validDocx() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body><w:p><w:r><w:t>\u6b63\u6587</w:t></w:r></w:p><w:sectPr/></w:body></w:document>"));
    Path source = temp.resolve("source.docx");
    pkg.save(source.toFile());
    return source;
  }

  private Path validTableDocx() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body><w:p><w:r><w:t>表1-1 测试结果</w:t></w:r></w:p>"
            + "<w:tbl><w:tblPr/><w:tblGrid><w:gridCol w:w=\"2400\"/><w:gridCol w:w=\"2400\"/></w:tblGrid>"
            + "<w:tr><w:tc><w:p><w:r><w:t>项目</w:t></w:r></w:p></w:tc>"
            + "<w:tc><w:p><w:r><w:t>结果</w:t></w:r></w:p></w:tc></w:tr>"
            + "<w:tr><w:tc><w:p><w:r><w:t>甲</w:t></w:r></w:p></w:tc>"
            + "<w:tc><w:p><w:r><w:t>一</w:t></w:r></w:p></w:tc></w:tr></w:tbl>"
            + "<w:sectPr/></w:body></w:document>"));
    Path source = temp.resolve("table-source.docx");
    pkg.save(source.toFile());
    return source;
  }
}
