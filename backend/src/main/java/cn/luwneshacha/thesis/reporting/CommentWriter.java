package cn.luwneshacha.thesis.reporting;

import cn.luwneshacha.thesis.document.SourceAddress;
import cn.luwneshacha.thesis.rules.CheckResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

public final class CommentWriter {
  private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
  private static final String REL = "http://schemas.openxmlformats.org/package/2006/relationships";
  private static final String CT = "http://schemas.openxmlformats.org/package/2006/content-types";
  private static final String COMMENTS_TYPE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/comments";

  public AnnotationReport writeCopy(Path source, Path target, List<CheckResult> results) {
    return writeCopy(source, target, null, results);
  }

  public AnnotationReport writeCopy(Path source, Path target, String expectedSourceSha256, List<CheckResult> results) {
    List<AnnotationItem> rejected = new ArrayList<>();
    try {
      byte[] sourceBytes = Files.readAllBytes(source);
      String actualHash = sha256(sourceBytes);
      if (expectedSourceSha256 != null && !expectedSourceSha256.equalsIgnoreCase(actualHash)) {
        for (CheckResult result : results) rejected.add(new AnnotationItem(result.ruleId(), AnnotationStatus.REJECTED, "SOURCE_HASH_MISMATCH"));
        return new AnnotationReport(rejected, false);
      }
      Map<String, byte[]> parts = readParts(source);
      Document document = parse(parts.get("word/document.xml"));
      int nextId = nextCommentId(parts.get("word/comments.xml"));
      Document comments = parts.containsKey("word/comments.xml") ? parse(parts.get("word/comments.xml")) : newCommentsDocument();
      Element commentsRoot = comments.getDocumentElement();
      List<CheckResult> written = new ArrayList<>();
      Map<String, List<CheckResult>> grouped = new LinkedHashMap<>();
      for (CheckResult result : results) {
        if (!"word/document.xml".equals(result.source().partName())) {
          rejected.add(new AnnotationItem(result.ruleId(), AnnotationStatus.UNSUPPORTED_ANCHOR, "PART_NOT_SUPPORTED"));
          continue;
        }
        grouped.computeIfAbsent(result.source().elementPath().toString(), ignored -> new ArrayList<>()).add(result);
      }
      for (List<CheckResult> group : grouped.values()) {
        CheckResult result = group.get(0);
        try {
          Element paragraph = resolve(document, result.source());
          if ("tbl".equals(paragraph.getLocalName()) && W.equals(paragraph.getNamespaceURI())) {
            var nested = paragraph.getElementsByTagNameNS(W, "p");
            if (nested.getLength() == 0) throw new AnchorException("TABLE_HAS_NO_PARAGRAPH");
            paragraph = (Element) nested.item(0);
          }
          if (!"p".equals(paragraph.getLocalName()) || !W.equals(paragraph.getNamespaceURI())) throw new AnchorException("ANCHOR_NOT_PARAGRAPH");
          String text = paragraph.getTextContent();
          if (text == null || text.isBlank()) throw new AnchorException("EMPTY_ANCHOR");
          addRange(paragraph, nextId);
          // 一个段落只插入一个批注；颜色取该段落最先发现的错误类型，批注正文会列出其余问题。
          highlight(paragraph, result.ruleId());
          commentsRoot.appendChild(comment(comments, nextId, group));
          for (CheckResult item : group) {
            written.add(item);
            rejected.add(new AnnotationItem(item.ruleId(), AnnotationStatus.WRITTEN, ""));
          }
          nextId++;
        } catch (AnchorException error) {
          for (CheckResult item : group) rejected.add(new AnnotationItem(item.ruleId(), AnnotationStatus.UNSUPPORTED_ANCHOR, error.getMessage()));
        }
      }
      if (written.isEmpty()) return new AnnotationReport(rejected, false);
      parts.put("word/document.xml", serialize(document));
      parts.put("word/comments.xml", serialize(comments));
      parts.put("word/_rels/document.xml.rels", addRelationship(parts.get("word/_rels/document.xml.rels")));
      parts.put("[Content_Types].xml", addContentType(parts.get("[Content_Types].xml")));
      writeParts(target, parts);
      return new AnnotationReport(rejected, true);
    } catch (IOException | RuntimeException error) {
      for (CheckResult result : results) rejected.add(new AnnotationItem(result.ruleId(), AnnotationStatus.REJECTED, "WRITE_FAILED"));
      return new AnnotationReport(rejected, false);
    }
  }

  private static Map<String, byte[]> readParts(Path source) throws IOException {
    Map<String, byte[]> parts = new LinkedHashMap<>();
    try (ZipFile zip = new ZipFile(source.toFile())) {
      var entries = zip.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        if (!entry.isDirectory()) parts.put(entry.getName(), zip.getInputStream(entry).readAllBytes());
      }
    }
    return parts;
  }

  private static void writeParts(Path target, Map<String, byte[]> parts) throws IOException {
    Files.deleteIfExists(target);
    try (var output = Files.newOutputStream(target); var zip = new ZipOutputStream(output)) {
      for (var part : parts.entrySet()) {
        zip.putNextEntry(new ZipEntry(part.getKey()));
        zip.write(part.getValue());
        zip.closeEntry();
      }
    }
  }

  private static Element resolve(Document document, SourceAddress address) throws AnchorException {
    Element current = document.getDocumentElement();
    for (Integer index : address.elementPath()) {
      List<Element> children = elementChildren(current);
      if (index < 0 || index >= children.size()) throw new AnchorException("PATH_OUT_OF_RANGE");
      current = children.get(index);
    }
    return current;
  }

  private static void addRange(Element paragraph, int id) throws AnchorException {
    List<Element> children = elementChildren(paragraph);
    Element firstContent = children.stream().filter(child -> !"pPr".equals(child.getLocalName())).findFirst().orElse(null);
    Element lastContent = null;
    for (Element child : children) if (!"pPr".equals(child.getLocalName())) lastContent = child;
    if (firstContent == null || lastContent == null) throw new AnchorException("EMPTY_ANCHOR");
    Document doc = paragraph.getOwnerDocument();
    Element start = doc.createElementNS(W, "w:commentRangeStart");
    start.setAttributeNS(W, "w:id", String.valueOf(id));
    Element end = doc.createElementNS(W, "w:commentRangeEnd");
    end.setAttributeNS(W, "w:id", String.valueOf(id));
    paragraph.insertBefore(start, firstContent);
    paragraph.insertBefore(end, lastContent.getNextSibling());
    Element referenceRun = doc.createElementNS(W, "w:r");
    Element reference = doc.createElementNS(W, "w:commentReference");
    reference.setAttributeNS(W, "w:id", String.valueOf(id));
    referenceRun.appendChild(reference);
    paragraph.appendChild(referenceRun);
  }

  private static void highlight(Element paragraph, String ruleId) {
    String color = switch (ruleId) {
      case "BODY-SIZE" -> "yellow";
      case "BODY-FONT" -> "cyan";
      case "BODY-INDENT" -> "brightGreen";
      case "PAGE-01" -> "magenta";
      default -> "lightGray";
    };
    Document doc = paragraph.getOwnerDocument();
    var runs = paragraph.getElementsByTagNameNS(W, "r");
    for (int i = 0; i < runs.getLength(); i++) {
      Element run = (Element) runs.item(i);
      Element properties = null;
      for (Node node = run.getFirstChild(); node != null; node = node.getNextSibling()) {
        if (node instanceof Element child && "rPr".equals(child.getLocalName())) { properties = child; break; }
      }
      if (properties == null) { properties = doc.createElementNS(W, "w:rPr"); run.insertBefore(properties, run.getFirstChild()); }
      Element mark = doc.createElementNS(W, "w:highlight");
      mark.setAttributeNS(W, "w:val", color);
      properties.appendChild(mark);
    }
  }

  private static Element comment(Document comments, int id, List<CheckResult> results) {
    Element c = comments.createElementNS(W, "w:comment");
    c.setAttributeNS(W, "w:id", String.valueOf(id));
    c.setAttributeNS(W, "w:author", "\u8bba\u6587\u683c\u5f0f\u7b5b\u67e5");
    c.setAttributeNS(W, "w:date", "2026-01-01T00:00:00Z");
    Element p = comments.createElementNS(W, "w:p");
    Element r = comments.createElementNS(W, "w:r");
    Element t = comments.createElementNS(W, "w:t");
    t.setTextContent(results.stream().map(CommentWriter::suggestion).distinct().collect(java.util.stream.Collectors.joining("\n")));
    r.appendChild(t); p.appendChild(r); c.appendChild(p);
    return c;
  }

  private static String suggestion(CheckResult result) {
    return switch (result.ruleId()) {
      case "PAGE-01" -> "页边距不符合要求，请按规范调整为 2.6 cm。";
      case "BODY-FONT" -> "字体不符合要求，请检查中文字体和英文字体。";
      case "BODY-SIZE" -> "字号不符合要求，请按规范调整。";
      case "BODY-INDENT" -> "首行缩进不符合要求，请调整为 2 字符。";
      case "TAB-01" -> "表格缺少表题，请补充表号和题名。";
      case "TAB-02" -> "表题位置需要复核，请确认是否位于表格上方。";
      case "TAB-03" -> "表格边框可能不符合三线表要求，请检查横线和竖线。";
      case "EQ-01" -> "公式缺少编号，请按章节补充公式号。";
      case "EQ-03" -> "公式编号重复，请检查编号顺序。";
      case "EQ-04" -> "公式对象已识别，具体格式请在 Word 中复核。";
      case "REF-02" -> "参考文献缺少类型标识，请补充 [M]、[J] 等类别。";
      case "REF-04" -> "参考文献编号重复，请检查编号。";
      default -> "格式不符合要求，请核对学校规范。";
    };
  }

  private static byte[] addRelationship(byte[] bytes) throws IOException {
    Document doc = parse(bytes);
    Element root = doc.getDocumentElement();
    var existing = root.getElementsByTagNameNS(REL, "Relationship");
    for (int i = 0; i < existing.getLength(); i++) {
      if (COMMENTS_TYPE.equals(((Element) existing.item(i)).getAttribute("Type"))) return serialize(doc);
    }
    int max = 0;
    var nodes = existing;
    for (int i = 0; i < nodes.getLength(); i++) {
      String id = ((Element) nodes.item(i)).getAttribute("Id");
      if (id.startsWith("rId")) try { max = Math.max(max, Integer.parseInt(id.substring(3))); } catch (NumberFormatException ignored) { }
    }
    Element relationship = doc.createElementNS(REL, "Relationship");
    relationship.setAttribute("Id", "rId" + (max + 1));
    relationship.setAttribute("Type", COMMENTS_TYPE);
    relationship.setAttribute("Target", "comments.xml");
    root.appendChild(relationship);
    return serialize(doc);
  }

  private static byte[] addContentType(byte[] bytes) throws IOException {
    Document doc = parse(bytes);
    Element root = doc.getDocumentElement();
    var overrides = root.getElementsByTagNameNS(CT, "Override");
    for (int i = 0; i < overrides.getLength(); i++) {
      if ("/word/comments.xml".equals(((Element) overrides.item(i)).getAttribute("PartName"))) return serialize(doc);
    }
    Element override = doc.createElementNS(CT, "Override");
    override.setAttribute("PartName", "/word/comments.xml");
    override.setAttribute("ContentType", "application/vnd.openxmlformats-officedocument.wordprocessingml.comments+xml");
    root.appendChild(override);
    return serialize(doc);
  }

  private static int nextCommentId(byte[] bytes) throws IOException {
    if (bytes == null) return 0;
    var nodes = parse(bytes).getDocumentElement().getElementsByTagNameNS(W, "comment");
    int max = -1;
    for (int i = 0; i < nodes.getLength(); i++) max = Math.max(max, Integer.parseInt(((Element) nodes.item(i)).getAttributeNS(W, "id")));
    return max + 1;
  }

  private static Document newCommentsDocument() {
    Document doc = newDocument();
    Element root = doc.createElementNS(W, "w:comments");
    doc.appendChild(root);
    return doc;
  }

  private static Document parse(byte[] bytes) throws IOException {
    try {
      var factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    } catch (Exception error) { throw new IOException("invalid XML part", error); }
  }

  private static Document newDocument() {
    try { return DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument(); }
    catch (Exception e) { throw new IllegalStateException(e); }
  }

  private static byte[] serialize(Document doc) throws IOException {
    try {
      var transformer = TransformerFactory.newInstance().newTransformer();
      transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
      transformer.setOutputProperty(OutputKeys.INDENT, "no");
      transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
      var output = new StringWriter();
      transformer.transform(new DOMSource(doc), new StreamResult(output));
      return output.toString().getBytes(StandardCharsets.UTF_8);
    } catch (Exception error) { throw new IOException("cannot serialize XML", error); }
  }

  private static List<Element> elementChildren(Element element) {
    List<Element> children = new ArrayList<>();
    for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) if (node instanceof Element child) children.add(child);
    return children;
  }

  private static String sha256(byte[] bytes) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    catch (Exception e) { throw new IllegalStateException(e); }
  }

  private static final class AnchorException extends Exception {
    AnchorException(String message) { super(message); }
  }
}
