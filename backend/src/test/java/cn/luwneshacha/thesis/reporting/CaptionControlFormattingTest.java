package cn.luwneshacha.thesis.reporting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.luwneshacha.thesis.document.AutomaticNumberingModel;
import cn.luwneshacha.thesis.document.Docx4jDocumentParser;
import cn.luwneshacha.thesis.document.DocxReader;
import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.RunModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import cn.luwneshacha.thesis.rules.CheckResult;
import cn.luwneshacha.thesis.rules.Judgement;
import cn.luwneshacha.thesis.rules.RuleEngine;
import cn.luwneshacha.thesis.rules.RuleSet;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class CaptionControlFormattingTest {
  private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
  private static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
  @TempDir Path temp;

  static Stream<Arguments> layoutControls() {
    return Stream.of(false, true).flatMap(table -> Stream.of(
        Arguments.of(table, "<w:tab/>", 1),
        Arguments.of(table, "<w:br/>", 1),
        Arguments.of(table, "<w:cr/>", 1),
        Arguments.of(table, "<w:tab/><w:br/><w:cr/>", 3)));
  }

  @ParameterizedTest
  @MethodSource("layoutControls")
  void layoutControlsDoNotProduceCaptionCharacterFormatFindings(
      boolean table, String control, int controlLength) throws Exception {
    Path input = fixture(table, run(control, null), caption(table, false, " ", 21));
    var parsed = parse(input);
    var paragraph = captionParagraph(parsed, table);
    assertEquals(20, paragraph.runs().getFirst().fontSizeHalfPoints());
    var controlSource = paragraph.runs().getFirst().source();
    var findings = findings(parsed);
    var formats = findings.stream().filter(r -> r.ruleId().startsWith("CAPTION-")).toList();
    assertFalse(formats.isEmpty());
    assertTrue(formats.stream().allMatch(r -> r.judgement() == Judgement.PASS), formats.toString());
    assertTrue(formats.stream().noneMatch(r -> r.source().equals(controlSource)), formats.toString());
    assertEquals(new SourceAddress("word/document.xml", List.of(0, table ? 2 : 3),
        controlLength, controlLength + 3), paragraph.runs().get(1).source(),
        "控制符仍占原始位置，后续文字的 part、XML 路径和起止偏移必须保持精确");
  }

  @Test
  void captionCommentKeepsRealSequenceNumberingWithoutFalseSizeAdvice() throws Exception {
    Path input = fixture(false, run("<w:tab/>", null), caption(false, true, " ", 21));
    var raw = new DocxReader().read(input);
    var parsed = new Docx4jDocumentParser().parse(raw);
    var relevant = findings(parsed).stream().filter(r -> r.ruleId().startsWith("CAPTION-")
        || r.ruleId().equals("NUMBERING-01")).toList();
    var selected = AnnotationFindings.select(relevant).representatives();
    assertEquals(1, selected.size(), selected.toString());
    assertEquals("NUMBERING-01", selected.getFirst().ruleId());
    Path output = temp.resolve("annotated.docx");
    var report = new CommentWriter().writeCopy(input, output, raw.sourceSha256(), selected);
    assertTrue(report.outputWritten());
    assertTrue(report.items().stream().allMatch(i -> i.status() == AnnotationStatus.WRITTEN));
    var comments = CommentWriter.readThreads(output);
    assertEquals(1, comments.size());
    assertTrue(comments.getFirst().text().contains("自动编号"));
    assertFalse(comments.getFirst().text().contains("字号"));
    var reread = parse(output);
    assertEquals(List.of(AutomaticNumberingModel.Kind.SEQUENCE_FIELD),
        reread.automaticNumbering().stream().map(AutomaticNumberingModel::kind).toList());
    assertEquals(parsed.automaticNumbering(), reread.automaticNumbering());
    assertEquals(text(captionParagraph(parsed, false)), text(captionParagraph(reread, false)));
    assertEquals(captionParagraph(parsed, false).runs(), captionParagraph(reread, false).runs());
    assertEquals(raw.sourceSha256(), new DocxReader().read(input).sourceSha256());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void wrongVisibleTextSizeStillFailsAtItsOriginalOffset(boolean table) throws Exception {
    var parsed = parse(fixture(table, run("<w:tab/><w:br/><w:cr/>", null),
        caption(table, false, " ", 24)));
    var failures = findings(parsed).stream().filter(r -> r.ruleId().equals("CAPTION-SIZE")
        && r.judgement() == Judgement.FAIL).toList();
    assertEquals(4, failures.size(), failures.toString());
    assertTrue(failures.stream().allMatch(r -> r.actual().equals("24")), failures.toString());
    var paragraphPath = List.of(0, table ? 2 : 3);
    assertEquals(List.of(
        new SourceAddress("word/document.xml", paragraphPath, 3, 6),
        new SourceAddress("word/document.xml", paragraphPath, 6, 7),
        new SourceAddress("word/document.xml", paragraphPath, 7, 8),
        new SourceAddress("word/document.xml", paragraphPath, 8, 14)),
        failures.stream().map(CheckResult::source).toList());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void visibleTextWithUnknownEffectiveSizeRemainsReview(boolean table) throws Exception {
    String label = (table ? "表" : "图") + "3-1 系统结构";
    var parsed = parse(fixture(table, run("<w:tab/><w:br/><w:cr/>", null),
        run("<w:t>" + label + "</w:t>", 21)));
    // 本地尝试移除 DOCX 所有字号定义后，docx4j 仍解析为 20。
    // 这里明确验证公开 RuleEngine 输入模型的 null 契约，不冒充解析器未知字号集成测试。
    var unknown = withUnknownCaptionSize(parsed, captionParagraph(parsed, table));
    var paragraph = captionParagraph(unknown, table);
    assertEquals(2, paragraph.runs().size());
    assertEquals(label, paragraph.runs().getLast().text());
    assertNull(paragraph.runs().getLast().fontSizeHalfPoints(),
        "规则输入的有效字号必须真实为 null");
    var sizes = findings(unknown).stream().filter(r -> r.ruleId().equals("CAPTION-SIZE")).toList();
    assertEquals(1, sizes.size(), sizes.toString());
    assertEquals(Judgement.REVIEW, sizes.getFirst().judgement());
    assertEquals("SIZE_UNKNOWN", sizes.getFirst().reasonCode());
    assertEquals(new SourceAddress("word/document.xml", List.of(0, table ? 2 : 3),
        3, 3 + label.length()), sizes.getFirst().source());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void tabUsedAsCaptionSeparatorStillFailsSpacing(boolean table) throws Exception {
    var parsed = parse(fixture(table, "", caption(table, false, "<w:tab/>", 21)));
    assertTrue(findings(parsed).stream().anyMatch(r -> r.ruleId().equals(table ? "TAB-03" : "FIG-03")
        && r.judgement() == Judgement.FAIL && r.reasonCode().endsWith("CAPTION_NUMBER_FORMAT")));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void mixedControlAndVisibleTextRunStillChecksSize(boolean table) throws Exception {
    String label = (table ? "表" : "图") + "3-1 系统结构";
    var parsed = parse(fixture(table, "", run("<w:tab/><w:t>" + label + "</w:t>", 24)));
    assertTrue(findings(parsed).stream().anyMatch(r -> r.ruleId().equals("CAPTION-SIZE")
        && r.judgement() == Judgement.FAIL && r.actual().equals("24")));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void ordinarySpacesAreNotReclassifiedAsLayoutControls(boolean table) throws Exception {
    String label = (table ? "表" : "图") + "3-1";
    String runs = run("<w:t>" + label + "</w:t>", 21)
        + run("<w:t xml:space=\"preserve\"> </w:t>", null)
        + run("<w:t>系统结构</w:t>", 21);
    var parsed = parse(fixture(table, "", runs));
    var failures = findings(parsed).stream().filter(r -> r.ruleId().equals("CAPTION-SIZE")
        && r.judgement() == Judgement.FAIL).toList();
    assertEquals(1, failures.size());
    assertEquals("20", failures.getFirst().actual());
    assertEquals(label.length(), failures.getFirst().source().start());
    assertEquals(label.length() + 1, failures.getFirst().source().end());
  }

  private static List<CheckResult> findings(ParsedDocument parsed) {
    return new RuleEngine().check(parsed, RuleSet.schoolV1());
  }

  private static ParsedDocument withUnknownCaptionSize(ParsedDocument parsed, ParagraphModel caption) {
    var runs = caption.runs().stream().map(run -> new RunModel(run.text(), run.asciiFont(),
        run.highAnsiFont(), run.eastAsiaFont(), run.complexFont(), null, null, run.bold(),
        run.verticalAlignment(), run.source())).toList();
    var unknownCaption = new ParagraphModel(runs, caption.firstLineIndentTwips(), caption.leftIndentTwips(),
        caption.leftIndentChars(), caption.rightIndentTwips(), caption.lineTwips(), caption.lineRule(),
        caption.source(), caption.firstLineChars(), caption.outlineLevel(), caption.alignment(),
        caption.numberingLabel(), caption.numberingSeparatorSpaces(), caption.numberingFormatTrailingSpaces(),
        caption.generatedTocField(), caption.hangingIndentTwips(), caption.hangingIndentChars(),
        caption.automaticNumberingActive(), caption.firstLineEvidenceInvalid(), caption.hangingEvidenceInvalid(),
        caption.leftEvidenceInvalid(), caption.beforeSpacingTwips(), caption.afterSpacingTwips(),
        caption.paragraphStyleId(), caption.lineSpacingEvidenceInvalid(), caption.insideTextBox(),
        caption.paragraphStyleName());
    var paragraphs = parsed.paragraphs().stream().map(p -> p == caption ? unknownCaption : p).toList();
    return new ParsedDocument(parsed.sourceSha256(), paragraphs, parsed.sections(), parsed.diagnostics(),
        parsed.tables(), parsed.equations(), parsed.references(), parsed.images(), parsed.pageNumbers(),
        parsed.chapterBreaks(), parsed.headers(), parsed.automaticNumbering());
  }

  private static ParsedDocument parse(Path input) throws Exception {
    return new Docx4jDocumentParser().parse(new DocxReader().read(input));
  }

  private static ParagraphModel captionParagraph(ParsedDocument parsed, boolean table) {
    var source = table ? parsed.tables().getFirst().captionSource() : parsed.images().getFirst().captionSource();
    return parsed.paragraphs().stream().filter(p -> p.source().elementPath().equals(source.elementPath()))
        .findFirst().orElseThrow();
  }

  private static String text(ParagraphModel p) {
    return p.runs().stream().map(r -> r.text()).reduce("", String::concat);
  }

  private static String run(String content, Integer size) {
    String properties = size == null ? "" : "<w:rPr><w:rFonts w:ascii=\"Times New Roman\""
        + " w:hAnsi=\"Times New Roman\" w:eastAsia=\"宋体\"/><w:sz w:val=\"" + size + "\"/></w:rPr>";
    return "<w:r>" + properties + content + "</w:r>";
  }

  private static String caption(boolean table, boolean automatic, String separator, int size) {
    String prefix = (table ? "表" : "图") + "3-";
    String number = automatic ? run("<w:fldChar w:fldCharType=\"begin\"/>", size)
        + run("<w:instrText xml:space=\"preserve\"> SEQ Figure </w:instrText>", size)
        + run("<w:fldChar w:fldCharType=\"separate\"/>", size)
        + run("<w:t>1</w:t>", size) + run("<w:fldChar w:fldCharType=\"end\"/>", size)
        : run("<w:t>1</w:t>", size);
    String gap = separator.equals(" ") ? "<w:t xml:space=\"preserve\"> </w:t>" : separator;
    return run("<w:t>" + prefix + "</w:t>", size) + number + run(gap, size)
        + run("<w:t>系统结构框图</w:t>", size);
  }

  private Path fixture(boolean table, String prefixRuns, String captionRuns) throws Exception {
    String heading = "<w:p><w:r><w:t>第三章  系统设计</w:t></w:r></w:p>";
    String reference = "<w:p><w:r><w:t>如图3-1和表3-1所示。</w:t></w:r></w:p>";
    String caption = "<w:p><w:pPr><w:pStyle w:val=\"Caption\"/><w:jc w:val=\"center\"/></w:pPr>"
        + prefixRuns + captionRuns + "</w:p>";
    String object = table ? "<w:tbl><w:tblPr/><w:tblGrid><w:gridCol w:w=\"2000\"/><w:gridCol w:w=\"2000\"/></w:tblGrid>"
        + "<w:tr><w:tc><w:p><w:r><w:t>参数</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>值</w:t></w:r></w:p></w:tc></w:tr>"
        + "<w:tr><w:tc><w:p><w:r><w:t>温度</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>20</w:t></w:r></w:p></w:tc></w:tr></w:tbl>"
        : "<w:p><w:r><w:pict><v:shape id=\"figure\" style=\"width:100pt;height:80pt\"><v:imagedata r:id=\"image\"/>"
            + "</v:shape></w:pict></w:r></w:p>";
    String document = "<w:document xmlns:w=\"" + W + "\" xmlns:r=\"" + R
        + "\" xmlns:v=\"urn:schemas-microsoft-com:vml\"><w:body>" + heading + reference
        + (table ? caption + object : object + caption) + "<w:sectPr/></w:body></w:document>";
    String relationshipNs = "http://schemas.openxmlformats.org/package/2006/relationships";
    var parts = Map.of(
        "[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
            + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
            + "<Default Extension=\"png\" ContentType=\"image/png\"/>"
            + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
            + "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/></Types>",
        "_rels/.rels", "<Relationships xmlns=\"" + relationshipNs + "\"><Relationship Id=\"main\" Type=\""
            + R + "/officeDocument\" Target=\"word/document.xml\"/></Relationships>",
        "word/document.xml", document,
        "word/_rels/document.xml.rels", "<Relationships xmlns=\"" + relationshipNs
            + "\"><Relationship Id=\"styles\" Type=\"" + R + "/styles\" Target=\"styles.xml\"/>"
            + "<Relationship Id=\"image\" Type=\"" + R + "/image\" Target=\"media/image.png\"/></Relationships>",
        "word/styles.xml", "<w:styles xmlns:w=\"" + W + "\"><w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\">"
            + "<w:name w:val=\"Normal\"/><w:rPr><w:rFonts w:eastAsia=\"宋体\" w:ascii=\"Times New Roman\"/><w:sz w:val=\"24\"/></w:rPr></w:style>"
            + "<w:style w:type=\"paragraph\" w:styleId=\"Caption\"><w:name w:val=\"caption\"/><w:basedOn w:val=\"Normal\"/>"
            + "<w:rPr><w:sz w:val=\"20\"/></w:rPr></w:style></w:styles>");
    Path input = temp.resolve("source.docx");
    try (var zip = new ZipOutputStream(Files.newOutputStream(input))) {
      for (var part : parts.entrySet()) {
        zip.putNextEntry(new ZipEntry(part.getKey()));
        zip.write(part.getValue().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
      zip.putNextEntry(new ZipEntry("word/media/image.png"));
      zip.write(java.util.Base64.getDecoder().decode(
          "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j7l8AAAAASUVORK5CYII="));
      zip.closeEntry();
    }
    return input;
  }
}
