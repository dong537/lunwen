package cn.luwneshacha.thesis.reporting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.luwneshacha.thesis.document.Docx4jDocumentParser;
import cn.luwneshacha.thesis.document.DocxReader;
import cn.luwneshacha.thesis.rules.Applicability;
import cn.luwneshacha.thesis.rules.CheckResult;
import cn.luwneshacha.thesis.rules.ExecutionStatus;
import cn.luwneshacha.thesis.rules.Judgement;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;
import org.docx4j.XmlUtils;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommentThreadRoundTripTest {
  private static final String NS = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"";

  @TempDir Path temp;

  @Test
  void writesExplicitReplyAndResolvesItsParentAcrossModernParts() throws Exception {
    Path source = validDocx();
    var raw = new DocxReader().read(source);
    var anchor = new Docx4jDocumentParser().parse(raw).paragraphs().get(0).source();
    var finding = new CheckResult("BODY-FONT", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "宋体", "Arial", "FONT_MISMATCH", anchor);
    Path rootOutput = temp.resolve("root.docx");
    assertTrue(new CommentWriter().writeCopy(source, rootOutput, List.of(finding)).outputWritten());

    Path replyOutput = temp.resolve("reply.docx");
    AnnotationReport report = new CommentWriter().writeCopy(rootOutput, replyOutput, null, List.of(),
        List.of(new CommentReply("reviewer", "Please use the required font.", "0")));
    assertTrue(report.outputWritten());

    List<CommentThread> threads = new CommentWriter().readThreads(replyOutput);
    assertEquals(2, threads.size());
    CommentThread root = threads.get(0);
    CommentThread reply = threads.get(1);
    assertEquals("0", root.commentId());
    assertNull(root.parentCommentId());
    assertEquals("1", reply.commentId());
    assertEquals("0", reply.parentCommentId());
    assertEquals("reviewer", reply.author());
    assertEquals("Please use the required font.", reply.text());
    assertNotNull(reply.paraId());
    assertFalse(reply.durableId().isBlank());

    try (ZipFile zip = new ZipFile(replyOutput.toFile())) {
      String extended = new String(zip.getInputStream(zip.getEntry("word/commentsExtended.xml")).readAllBytes());
      assertTrue(extended.contains("paraIdParent"));
      assertTrue(extended.contains(reply.paraId()));
      assertNotNull(zip.getEntry("word/commentsIds.xml"));
      assertNotNull(zip.getEntry("word/people.xml"));
    }
  }

  private Path validDocx() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body><w:p><w:r><w:t>text</w:t></w:r></w:p><w:sectPr/></w:body></w:document>"));
    Path source = temp.resolve("source.docx");
    pkg.save(source.toFile());
    return source;
  }
}
