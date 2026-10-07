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
  void appendsToExistingCommentsWithoutDuplicatingRelationship() throws Exception {
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
    assertEquals(2, part.getContents().getComment().size());
    assertEquals(1, relations.stream().filter(r -> r.getType().endsWith("/comments")).count());
  }
  private Path validDocx() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body><w:p><w:r><w:t>\u6b63\u6587</w:t></w:r></w:p><w:sectPr/></w:body></w:document>"));
    Path source = temp.resolve("source.docx");
    pkg.save(source.toFile());
    return source;
  }
}
