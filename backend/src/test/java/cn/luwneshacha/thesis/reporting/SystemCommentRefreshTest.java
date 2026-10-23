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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SystemCommentRefreshTest {
  private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
  private static final String W14 = "http://schemas.microsoft.com/office/word/2010/wordml";
  private static final String W15 = "http://schemas.microsoft.com/office/word/2012/wordml";
  private static final String CID = "http://schemas.microsoft.com/office/word/2016/wordml/cid";
  private static final String CEX = "http://schemas.microsoft.com/office/word/2018/wordml/cex";
  @TempDir Path temp;

  @Test
  void refreshesSystemCommentsEvenWhenNoNewIssuesRemainAndPreservesHumanDiscussion() throws Exception {
    Path source = fixture();
    byte[] original = Files.readAllBytes(source);
    Path output = temp.resolve("refreshed.docx");

    AnnotationReport report = new CommentWriter().writeCopy(source, output, List.of());

    assertTrue(report.outputWritten(), "removing stale system findings must produce the clean copy");
    assertTrue(report.items().isEmpty());
    assertEquals(List.of("1", "2", "3"), CommentWriter.readThreads(output).stream()
        .map(CommentThread::commentId).toList());
    assertEquals("2", CommentWriter.readThreads(output).get(2).parentCommentId());
    assertFalse(part(output, "word/commentsExtended.xml").contains("000000A0"));
    assertFalse(part(output, "word/commentsIds.xml").contains("000000A0"));
    assertTrue(part(output, "word/commentsIds.xml").contains("000000A3"));
    assertTrue(part(output, "word/people.xml").contains("人工老师"));
    assertTrue(part(output, "word/people.xml").contains("论文格式筛查"));
    assertFalse(part(output, "word/document.xml").contains("w:id=\"0\""));
    assertTrue(part(output, "word/document.xml").contains("w:id=\"1\""));
    assertTrue(part(output, "word/document.xml").contains("w:val=\"cyan\""),
        "legacy highlights have no reliable ownership marker and must be retained");
    assertTrue(java.util.Arrays.equals(original, Files.readAllBytes(source)), "never change the input file");
  }

  @Test
  void resolvesOriginalRunPathsBeforeRemovingOldMarkersAndDoesNotAccumulateSystemComments() throws Exception {
    Path source = fixture();
    Path first = temp.resolve("first.docx");
    var finding = new CheckResult("BODY-SIZE", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "24", "22", "SIZE_MISMATCH",
        new SourceAddress("word/document.xml", List.of(0, 0, 1), 0, 3));

    AnnotationReport report = new CommentWriter().writeCopy(source, first, List.of(finding));

    assertTrue(report.outputWritten());
    assertEquals(AnnotationStatus.WRITTEN, report.items().getFirst().status());
    assertEquals(4, CommentWriter.readThreads(first).size());
    assertFalse(CommentWriter.readThreads(first).stream().anyMatch(thread -> "旧的误报".equals(thread.text())));
    assertTrue(part(first, "word/document.xml").contains("w:val=\"yellow\""),
        "human highlights elsewhere remain unchanged");
    assertTrue(part(first, "word/document.xml").contains("w:val=\"cyan\""),
        "an existing highlight must not be replaced by an inspection color");
    Path second = temp.resolve("second.docx");
    var paragraphFinding = new CheckResult("BODY-INDENT", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "2字符", "0", "INDENT_MISMATCH",
        new SourceAddress("word/document.xml", List.of(0, 0), 0, 3));
    assertTrue(new CommentWriter().writeCopy(first, second, List.of(paragraphFinding)).outputWritten());
    assertEquals(4, CommentWriter.readThreads(second).size());
  }

  @Test
  void preservesAnExplicitReplyTargetBeforeRefreshingSystemComments() throws Exception {
    Path source = fixture();
    Path output = temp.resolve("reply.docx");

    AnnotationReport report = new CommentWriter().writeCopy(source, output, null, List.of(),
        List.of(new CommentReply("指导老师", "请保留这条讨论", "0")));

    assertTrue(report.outputWritten());
    assertEquals(5, CommentWriter.readThreads(output).size());
    assertEquals("0", CommentWriter.readThreads(output).getLast().parentCommentId());
  }

  @Test
  void removesStaleSystemAnchorsInOtherStoriesAndKeepsOtherPartsByteIdentical() throws Exception {
    Map<String, String> parts = baseParts();
    parts.put("word/document.xml", "<w:document xmlns:w=\"" + W + "\"><w:body>"
        + paragraph("0", "正文", "cyan") + "</w:body></w:document>");
    parts.put("word/footer1.xml", "<w:ftr xmlns:w=\"" + W + "\">" + paragraph("0", "页脚", "yellow") + "</w:ftr>");
    parts.put("word/header1.xml", "<w:hdr xmlns:w=\"" + W + "\">" + paragraph("1", "人工页眉", "green") + "</w:hdr>");
    parts.put("word/styles.xml", "<w:styles xmlns:w=\"" + W + "\"/>");
    Path source = zip(parts);
    Path output = temp.resolve("stories.docx");

    assertTrue(new CommentWriter().writeCopy(source, output, List.of()).outputWritten());

    assertFalse(part(output, "word/footer1.xml").contains("commentRange"));
    assertFalse(part(output, "word/footer1.xml").contains("commentReference"));
    assertTrue(part(output, "word/footer1.xml").contains("w:val=\"yellow\""));
    assertEquals(part(source, "word/header1.xml"), part(output, "word/header1.xml"));
    assertEquals(part(source, "word/styles.xml"), part(output, "word/styles.xml"));
  }

  @Test
  void refreshesExtensibleMetadataOnlyForRemovedSystemComments() throws Exception {
    Map<String, String> parts = baseParts();
    parts.put("word/document.xml", "<w:document xmlns:w=\"" + W + "\"><w:body>"
        + paragraph("0", "旧错误", "cyan") + paragraph("1", "人工注", "yellow")
        + paragraph("2", "待讨论", "green") + "</w:body></w:document>");
    parts.put("word/commentsExtensible.xml", extensibleMetadata());
    parts.put("word/_rels/document.xml.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
        + "<Relationship Id=\"rIdCex\" Type=\"http://schemas.microsoft.com/office/2018/08/relationships/commentsExtensible\""
        + " Target=\"commentsExtensible.xml\"/></Relationships>");
    Path source = zip(parts);
    Path output = temp.resolve("extensible.docx");

    assertTrue(new CommentWriter().writeCopy(source, output, List.of()).outputWritten());

    String metadata = part(output, "word/commentsExtensible.xml");
    assertFalse(metadata.contains("w16cex:durableId=\"11111110\""),
        "the deleted comment must not leave an orphaned durable-id metadata entry");
    assertTrue(metadata.contains("w16cex:durableId=\"11111111\""), "keep human metadata");
    assertTrue(metadata.contains("w16cex:durableId=\"11111112\""), "keep the parent of a human reply");
    assertTrue(metadata.contains("w16cex:durableId=\"11111113\""), "keep human reply metadata");
    assertTrue(metadata.contains("w16cex:durableId=\"FFFFFFFF\""), "keep unidentified metadata");
    assertTrue(metadata.contains("custom:durableId=\"11111110\""), "keep unknown extension entries");
    assertTrue(part(output, "word/_rels/document.xml.rels").contains("rIdCex"));
    assertEquals(List.of("1", "2", "3"), CommentWriter.readThreads(output).stream()
        .map(CommentThread::commentId).toList());
  }

  @Test
  void preservesExtensibleMetadataForAnExplicitReplyTargetAndUnknownEntries() throws Exception {
    Map<String, String> parts = baseParts();
    parts.put("word/document.xml", "<w:document xmlns:w=\"" + W + "\"><w:body>"
        + paragraph("0", "待回复", "cyan") + "</w:body></w:document>");
    parts.put("word/commentsExtensible.xml", extensibleMetadata());
    Path source = zip(parts);
    Path output = temp.resolve("extensible-reply.docx");

    assertTrue(new CommentWriter().writeCopy(source, output, null, List.of(),
        List.of(new CommentReply("指导老师", "保留这条讨论", "0"))).outputWritten());

    assertEquals(part(source, "word/commentsExtensible.xml"), part(output, "word/commentsExtensible.xml"),
        "when no metadata is removed, preserve the original part byte-for-byte");
    assertEquals("0", CommentWriter.readThreads(output).getLast().parentCommentId());
  }

  @Test
  void preservesExtensibleMetadataWhenTheDurableIdAlsoBelongsToAKeptComment() throws Exception {
    Map<String, String> parts = baseParts();
    parts.put("word/document.xml", "<w:document xmlns:w=\"" + W + "\"><w:body>"
        + paragraph("0", "旧错误", "cyan") + "</w:body></w:document>");
    parts.put("word/commentsIds.xml", parts.get("word/commentsIds.xml").replace("11111111", "11111110"));
    parts.put("word/commentsExtensible.xml", extensibleMetadata());
    Path source = zip(parts);
    Path output = temp.resolve("extensible-shared-id.docx");

    assertTrue(new CommentWriter().writeCopy(source, output, List.of()).outputWritten());

    assertEquals(part(source, "word/commentsExtensible.xml"), part(output, "word/commentsExtensible.xml"),
        "ambiguous shared durable ids cannot justify removing human metadata");
  }

  private static String extensibleMetadata() {
    return "<w16cex:commentsExtensible xmlns:w16cex=\"" + CEX + "\" xmlns:custom=\"urn:custom-comments\">"
        + "<w16cex:commentExtensible w16cex:durableId=\"11111110\"/>"
        + "<w16cex:commentExtensible w16cex:durableId=\"11111111\"/>"
        + "<w16cex:commentExtensible w16cex:durableId=\"11111112\"/>"
        + "<w16cex:commentExtensible w16cex:durableId=\"11111113\"/>"
        + "<w16cex:commentExtensible w16cex:durableId=\"FFFFFFFF\"/>"
        + "<custom:commentExtensible custom:durableId=\"11111110\"/>"
        + "</w16cex:commentsExtensible>";
  }

  private Path fixture() throws Exception {
    Map<String, String> parts = baseParts();
    parts.put("word/document.xml", "<w:document xmlns:w=\"" + W + "\"><w:body>"
        + paragraph("0", "旧错误", "cyan") + paragraph("1", "人工注", "yellow")
        + paragraph("2", "待讨论", "green") + "</w:body></w:document>");
    return zip(parts);
  }

  private Map<String, String> baseParts() {
    Map<String, String> parts = new LinkedHashMap<>();
    parts.put("[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
        + "<Default Extension=\"xml\" ContentType=\"application/xml\"/></Types>");
    parts.put("word/_rels/document.xml.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"/>");
    parts.put("word/comments.xml", "<w:comments xmlns:w=\"" + W + "\" xmlns:w14=\"" + W14 + "\">"
        + comment("0", "论文格式筛查", "000000A0", "旧的误报")
        + comment("1", "人工老师", "000000A1", "人工意见")
        + comment("2", "论文格式筛查", "000000A2", "需要保留的讨论")
        + comment("3", "人工老师", "000000A3", "人工回复") + "</w:comments>");
    parts.put("word/commentsExtended.xml", "<w15:commentsEx xmlns:w15=\"" + W15 + "\">"
        + "<w15:commentEx w15:paraId=\"000000A0\"/><w15:commentEx w15:paraId=\"000000A1\"/>"
        + "<w15:commentEx w15:paraId=\"000000A2\"/><w15:commentEx w15:paraId=\"000000A3\" w15:paraIdParent=\"000000A2\"/>"
        + "</w15:commentsEx>");
    parts.put("word/commentsIds.xml", "<w16cid:commentsIds xmlns:w16cid=\"" + CID + "\">"
        + "<w16cid:commentId w16cid:paraId=\"000000A0\" w16cid:durableId=\"11111110\"/>"
        + "<w16cid:commentId w16cid:paraId=\"000000A1\" w16cid:durableId=\"11111111\"/>"
        + "<w16cid:commentId w16cid:paraId=\"000000A2\" w16cid:durableId=\"11111112\"/>"
        + "<w16cid:commentId w16cid:paraId=\"000000A3\" w16cid:durableId=\"11111113\"/>"
        + "</w16cid:commentsIds>");
    parts.put("word/people.xml", "<w15:people xmlns:w15=\"" + W15 + "\">"
        + "<w15:person w15:author=\"论文格式筛查\"/><w15:person w15:author=\"人工老师\"/></w15:people>");
    return parts;
  }

  private static String paragraph(String id, String text, String color) {
    return "<w:p><w:commentRangeStart w:id=\"" + id + "\"/><w:r><w:rPr><w:highlight w:val=\"" + color
        + "\"/></w:rPr><w:t>" + text + "</w:t></w:r><w:commentRangeEnd w:id=\"" + id
        + "\"/><w:r><w:commentReference w:id=\"" + id + "\"/></w:r></w:p>";
  }

  private static String comment(String id, String author, String para, String text) {
    return "<w:comment w:id=\"" + id + "\" w:author=\"" + author + "\"><w:p w14:paraId=\""
        + para + "\"><w:r><w:t>" + text + "</w:t></w:r></w:p></w:comment>";
  }

  private Path zip(Map<String, String> parts) throws Exception {
    Path source = temp.resolve("source-" + System.nanoTime() + ".docx");
    try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(source))) {
      for (var entry : parts.entrySet()) {
        zip.putNextEntry(new ZipEntry(entry.getKey()));
        zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    }
    return source;
  }

  private static String part(Path file, String name) throws Exception {
    try (ZipFile zip = new ZipFile(file.toFile())) {
      return new String(zip.getInputStream(zip.getEntry(name)).readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
