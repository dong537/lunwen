package cn.luwneshacha.thesis.document;

import static cn.luwneshacha.thesis.document.AutomaticNumberingModel.Kind.LISTNUM_FIELD;
import static cn.luwneshacha.thesis.document.AutomaticNumberingModel.Kind.NUMBERED_LIST;
import static cn.luwneshacha.thesis.document.AutomaticNumberingModel.Kind.SEQUENCE_FIELD;
import static cn.luwneshacha.thesis.document.AutomaticNumberingModel.Kind.UNKNOWN_LIST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;

class AutomaticNumberingParserTest {
  private static final String W = XmlPartSupport.W;
  private static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
  private static final String NS = "xmlns:w=\"" + W + "\" xmlns:r=\"" + R
      + "\" xmlns:v=\"urn:schemas-microsoft-com:vml\""
      + " xmlns:mc=\"http://schemas.openxmlformats.org/markup-compatibility/2006\"";
  private static final String STYLES = "<w:styles " + NS + ">"
      + "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"/>"
      + "<w:style w:type=\"paragraph\" w:styleId=\"Base\"><w:pPr><w:numPr>"
      + "<w:numId w:val=\"1\"/></w:numPr></w:pPr></w:style>"
      + "<w:style w:type=\"paragraph\" w:styleId=\"Derived\"><w:basedOn w:val=\"Base\"/>"
      + "<w:pPr><w:numPr><w:ilvl w:val=\"1\"/></w:numPr></w:pPr></w:style></w:styles>";
  private static final String NUMBERING = "<w:numbering " + NS + ">"
      + "<w:abstractNum w:abstractNumId=\"1\">" + level(0, "decimal", "%1.")
      + level(1, "decimal", "%1.%2") + "</w:abstractNum>"
      + "<w:abstractNum w:abstractNumId=\"2\">" + level(0, "bullet", "•") + "</w:abstractNum>"
      + "<w:abstractNum w:abstractNumId=\"3\">" + level(0, "none", "") + "</w:abstractNum>"
      + "<w:num w:numId=\"1\"><w:abstractNumId w:val=\"1\"/></w:num>"
      + "<w:num w:numId=\"2\"><w:abstractNumId w:val=\"2\"/></w:num>"
      + "<w:num w:numId=\"3\"><w:abstractNumId w:val=\"3\"/></w:num></w:numbering>";

  @TempDir Path temp;

  @Test
  void identifiesDirectInheritedAndMultilevelListsInsideBodyTablesAndTextBoxes() throws Exception {
    String body = paragraph("<w:numPr><w:numId w:val=\"1\"/></w:numPr>", "标题")
        + paragraph("<w:pStyle w:val=\"Derived\"/>", "继承多级编号")
        + "<w:tbl><w:tr><w:tc>" + paragraph(numPr(1), "表格编号") + "</w:tc></w:tr></w:tbl>"
        + "<w:p><w:r><w:pict><v:shape><v:textbox><w:txbxContent>"
        + paragraph(numPr(1), "文本框编号")
        + "</w:txbxContent></v:textbox></v:shape></w:pict></w:r></w:p>";
    RawDocx raw = fixture(body, Map.of(), "");

    List<AutomaticNumberingModel> evidence = parse(raw);

    assertEquals(List.of(NUMBERED_LIST, NUMBERED_LIST, NUMBERED_LIST, NUMBERED_LIST),
        evidence.stream().map(AutomaticNumberingModel::kind).toList());
    assertEquals(List.of("标题", "继承多级编号", "表格编号", "文本框编号"),
        evidence.stream().map(item -> anchoredText(raw, item.source())).toList());
    assertTrue(evidence.stream().allMatch(item -> item.source().start() == 0 && item.source().end() == 0));
  }

  @Test
  void excludesDisabledInheritedListsBulletsUnusedDefinitionsAndOrdinaryFields() throws Exception {
    String body = paragraph("<w:pStyle w:val=\"Derived\"/>" + numPr(0), "[1] 手工编号")
        + paragraph(numPr(2), "项目符号") + paragraph(numPr(3), "无编号格式")
        + paragraph("", "第一章  手工标题")
        + "<w:p>" + simple("PAGE") + simple("NUMPAGES") + simple("SECTIONPAGES")
        + simple("TOC \\o &quot;1-3&quot;") + simple("REF SEQ") + simple("PAGEREF LISTNUM") + "</w:p>";

    assertTrue(parse(fixture(body, Map.of(), "")).isEmpty());
  }

  @Test
  void preservesUnknownListEvidenceInsteadOfAssumingNumberingIsAbsent() throws Exception {
    String body = paragraph(numPr(99), "缺少编号定义")
        + paragraph("<w:numPr><w:ilvl w:val=\"0\"/></w:numPr>", "缺少编号ID");

    assertEquals(List.of(UNKNOWN_LIST, UNKNOWN_LIST), parse(fixture(body, Map.of(), "")).stream()
        .map(AutomaticNumberingModel::kind).toList());
  }

  @Test
  void recognizesSimpleAndSplitNestedFieldsWithoutReadingResultTextAsInstructions() throws Exception {
    String body = "<w:p>" + simple("SEQ Figure \\* ARABIC") + "</w:p>"
        + "<w:p>" + begin() + instruction(" LIS") + instruction("TNUM LegalDefault ")
        + separate() + text("1") + end() + "</w:p>"
        + "<w:p>" + begin() + instruction(" IF ") + begin() + instruction(" SE")
        + instruction("Q Table ") + separate() + text("1") + end()
        + instruction(" = 1 &quot;yes&quot; &quot;no&quot; ") + separate() + text("SEQ is visible prose")
        + instruction(" SEQ this_is_not_an_instruction ") + end() + "</w:p>"
        + "<w:p>" + text("SEQ Figure LISTNUM") + "</w:p>";

    assertEquals(List.of(SEQUENCE_FIELD, LISTNUM_FIELD, SEQUENCE_FIELD),
        parse(fixture(body, Map.of(), "")).stream().map(AutomaticNumberingModel::kind).toList());
  }

  @Test
  void ignoresDeletedNumberingAndFallbackBranches() throws Exception {
    String body = paragraph("<w:pPrChange w:id=\"1\" w:author=\"test\"><w:pPr>"
        + numPr(1) + "</w:pPr></w:pPrChange>", "已删除的编号属性")
        + "<w:p><w:del w:id=\"2\" w:author=\"test\">" + simple("SEQ Removed") + "</w:del>"
        + "<w:r><w:delInstrText>SEQ Removed</w:delInstrText></w:r>"
        + "<mc:AlternateContent><mc:Choice Requires=\"v\">" + text("现行内容")
        + "</mc:Choice><mc:Fallback>" + simple("SEQ Fallback") + "</mc:Fallback></mc:AlternateContent></w:p>";

    assertTrue(parse(fixture(body, Map.of(), "")).isEmpty());
  }

  @Test
  void scansOnlyReferencedHeadersFootersAndIndividualFootnotesAndEndnotes() throws Exception {
    String body = "<w:p>" + text("正文")
        + "<w:r><w:footnoteReference w:id=\"1\"/><w:endnoteReference w:id=\"2\"/></w:r></w:p>"
        + "<w:sectPr><w:headerReference w:type=\"default\" r:id=\"h1\"/>"
        + "<w:footerReference w:type=\"default\" r:id=\"f1\"/></w:sectPr>";
    Map<String, String> extra = Map.of(
        "word/header-used.xml", "<w:hdr " + NS + ">" + paragraph(numPr(1), "页眉编号") + "</w:hdr>",
        "word/footer-used.xml", "<w:ftr " + NS + "><w:p>" + simple("SEQ Footer") + "</w:p></w:ftr>",
        "word/header-ghost.xml", "<w:hdr " + NS + ">" + paragraph(numPr(1), "未使用页眉") + "</w:hdr>",
        "word/footnotes.xml", "<w:footnotes " + NS + "><w:footnote w:id=\"1\">"
            + paragraph(numPr(1), "使用的脚注") + "</w:footnote><w:footnote w:id=\"99\">"
            + paragraph(numPr(1), "未使用脚注") + "</w:footnote></w:footnotes>",
        "word/endnotes.xml", "<w:endnotes " + NS + "><w:endnote w:id=\"2\"><w:p>"
            + simple("LISTNUM") + "</w:p></w:endnote><w:endnote w:id=\"99\">"
            + paragraph(numPr(1), "未使用尾注") + "</w:endnote></w:endnotes>");
    String relationships = relation("h1", "header", "header-used.xml")
        + relation("f1", "footer", "footer-used.xml")
        + relation("ghost", "header", "header-ghost.xml")
        + relation("notes", "footnotes", "footnotes.xml")
        + relation("endnotes", "endnotes", "endnotes.xml");
    RawDocx raw = fixture(body, extra, relationships);

    List<AutomaticNumberingModel> evidence = parse(raw);

    assertEquals(4, evidence.size());
    assertEquals(Set.of("word/header-used.xml", "word/footer-used.xml", "word/footnotes.xml", "word/endnotes.xml"),
        evidence.stream().map(item -> item.source().partName()).collect(java.util.stream.Collectors.toSet()));
    assertEquals(2, evidence.stream().filter(item -> item.kind() == NUMBERED_LIST).count());
    evidence.forEach(item -> anchoredText(raw, item.source()));
  }

  @Test
  void ignoresInactiveFirstAndEvenPageStoriesAndDeletedNoteReferences() throws Exception {
    String body = "<w:p>" + text("正文")
        + "<w:del w:id=\"1\" w:author=\"test\"><w:r><w:footnoteReference w:id=\"1\"/></w:r></w:del></w:p>"
        + "<w:sectPr><w:headerReference w:type=\"first\" r:id=\"first\"/>"
        + "<w:footerReference w:type=\"even\" r:id=\"even\"/></w:sectPr>";
    Map<String, String> extra = Map.of(
        "word/header-first.xml", "<w:hdr " + NS + ">" + paragraph(numPr(1), "未启用首页页眉") + "</w:hdr>",
        "word/footer-even.xml", "<w:ftr " + NS + ">" + paragraph(numPr(1), "未启用偶数页页脚") + "</w:ftr>",
        "word/footnotes.xml", "<w:footnotes " + NS + "><w:footnote w:id=\"1\">"
            + paragraph(numPr(1), "仅被删除修订引用的脚注") + "</w:footnote></w:footnotes>");

    assertTrue(parse(fixture(body, extra, relation("first", "header", "header-first.xml")
        + relation("even", "footer", "footer-even.xml")
        + relation("notes", "footnotes", "footnotes.xml"))).isEmpty());
  }

  @Test
  void treatsConflictingLevelOverridesAsUnknownEvidence() throws Exception {
    String numbering = NUMBERING.replace(
        "<w:num w:numId=\"1\"><w:abstractNumId w:val=\"1\"/></w:num>",
        "<w:num w:numId=\"1\"><w:abstractNumId w:val=\"1\"/>"
            + "<w:lvlOverride w:ilvl=\"0\">" + level(0, "bullet", "•") + "</w:lvlOverride>"
            + "<w:lvlOverride w:ilvl=\"0\">" + level(0, "decimal", "%1.") + "</w:lvlOverride></w:num>");

    assertEquals(List.of(UNKNOWN_LIST), parse(fixture(paragraph(numPr(1), "冲突编号定义"),
        Map.of("word/numbering.xml", numbering), "")).stream().map(AutomaticNumberingModel::kind).toList());
  }

  @Test
  void resolvesStyleAssociatedLevelOnlyWhenTheParagraphActuallyReferencesNumbering() throws Exception {
    String styles = "<w:styles " + NS + ">"
        + "<w:style w:type=\"paragraph\" w:styleId=\"HeadingTwo\"><w:pPr><w:numPr>"
        + "<w:ilvl w:val=\"0\"/><w:numId w:val=\"1\"/></w:numPr></w:pPr></w:style>"
        + "<w:style w:type=\"paragraph\" w:styleId=\"UnusedStyleLink\"/></w:styles>";
    String numbering = "<w:numbering " + NS + "><w:abstractNum w:abstractNumId=\"1\">"
        + level(0, "bullet", "•")
        + level(1, "decimal", "%1.%2").replace("</w:lvl>", "<w:pStyle w:val=\"HeadingTwo\"/></w:lvl>")
        + level(2, "decimal", "%1.%2.%3").replace("</w:lvl>", "<w:pStyle w:val=\"UnusedStyleLink\"/></w:lvl>")
        + "</w:abstractNum><w:num w:numId=\"1\"><w:abstractNumId w:val=\"1\"/></w:num></w:numbering>";
    RawDocx raw = fixture(paragraph("<w:pStyle w:val=\"HeadingTwo\"/>", "二级标题编号")
        + paragraph("<w:pStyle w:val=\"UnusedStyleLink\"/>", "未使用编号的样式"),
        Map.of("word/styles.xml", styles, "word/numbering.xml", numbering), "");

    List<AutomaticNumberingModel> evidence = parse(raw);

    assertEquals(List.of(NUMBERED_LIST), evidence.stream().map(AutomaticNumberingModel::kind).toList());
    assertEquals("二级标题编号", anchoredText(raw, evidence.get(0).source()));
  }

  private List<AutomaticNumberingModel> parse(RawDocx raw) {
    return new Docx4jDocumentParser().parse(raw).automaticNumbering();
  }

  private RawDocx fixture(String body, Map<String, String> extra, String relationships) throws Exception {
    Map<String, String> parts = new LinkedHashMap<>();
    parts.put("word/document.xml", "<w:document " + NS + "><w:body>" + body + "</w:body></w:document>");
    parts.put("word/styles.xml", STYLES);
    parts.put("word/numbering.xml", NUMBERING);
    parts.putAll(extra);
    StringBuilder types = new StringBuilder("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
        + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
        + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>");
    for (String name : parts.keySet()) {
      String type = name.contains("header") ? "header" : name.contains("footer") ? "footer"
          : name.substring(name.lastIndexOf('/') + 1, name.length() - 4);
      types.append("<Override PartName=\"/").append(name)
          .append("\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.")
          .append(type.equals("document") ? "document.main" : type).append("+xml\"/>");
    }
    parts.put("[Content_Types].xml", types.append("</Types>").toString());
    parts.put("_rels/.rels", relationships(relation("main", "officeDocument", "word/document.xml")));
    parts.put("word/_rels/document.xml.rels", relationships(relation("styles", "styles", "styles.xml")
        + relation("numbering", "numbering", "numbering.xml") + relationships));
    Path file = Files.createTempFile(temp, "numbering-", ".docx");
    try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
      for (var entry : parts.entrySet()) {
        zip.putNextEntry(new ZipEntry(entry.getKey()));
        zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    }
    return new DocxReader().read(file);
  }

  private static String anchoredText(RawDocx raw, SourceAddress source) {
    try {
      Element node = XmlPartSupport.parse(raw.part(source.partName()).bytes()).getDocumentElement();
      for (int index : source.elementPath()) node = XmlPartSupport.children(node).get(index);
      assertEquals("p", node.getLocalName());
      return XmlPartSupport.text(node);
    } catch (Exception error) {
      throw new AssertionError("unresolvable numbering anchor: " + source, error);
    }
  }

  private static String paragraph(String properties, String text) {
    return "<w:p><w:pPr>" + properties + "</w:pPr>" + text(text) + "</w:p>";
  }
  private static String numPr(int id) { return "<w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"" + id + "\"/></w:numPr>"; }
  private static String level(int level, String format, String label) {
    return "<w:lvl w:ilvl=\"" + level + "\"><w:start w:val=\"1\"/><w:numFmt w:val=\""
        + format + "\"/><w:lvlText w:val=\"" + label + "\"/></w:lvl>";
  }
  private static String text(String text) { return "<w:r><w:t>" + text + "</w:t></w:r>"; }
  private static String simple(String instruction) { return "<w:fldSimple w:instr=\"" + instruction + "\">" + text("1") + "</w:fldSimple>"; }
  private static String instruction(String instruction) { return "<w:r><w:instrText xml:space=\"preserve\">" + instruction + "</w:instrText></w:r>"; }
  private static String begin() { return "<w:r><w:fldChar w:fldCharType=\"begin\"/></w:r>"; }
  private static String separate() { return "<w:r><w:fldChar w:fldCharType=\"separate\"/></w:r>"; }
  private static String end() { return "<w:r><w:fldChar w:fldCharType=\"end\"/></w:r>"; }
  private static String relation(String id, String type, String target) {
    return "<Relationship Id=\"" + id + "\" Type=\"" + R + "/" + type + "\" Target=\"" + target + "\"/>";
  }
  private static String relationships(String values) {
    return "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" + values + "</Relationships>";
  }
}
