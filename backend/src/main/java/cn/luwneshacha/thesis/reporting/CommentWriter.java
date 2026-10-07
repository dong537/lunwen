package cn.luwneshacha.thesis.reporting;

import cn.luwneshacha.thesis.document.SourceAddress;
import cn.luwneshacha.thesis.rules.CheckResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
      for (CheckResult result : results) {
        if (!"word/document.xml".equals(result.source().partName())) {
          rejected.add(new AnnotationItem(result.ruleId(), AnnotationStatus.UNSUPPORTED_ANCHOR, "PART_NOT_SUPPORTED"));
          continue;
        }
        try {
          Element paragraph = resolve(document, result.source());
          if (!"p".equals(paragraph.getLocalName()) || !W.equals(paragraph.getNamespaceURI())) throw new AnchorException("ANCHOR_NOT_PARAGRAPH");
          String text = paragraph.getTextContent();
          if (text == null || text.isBlank()) throw new AnchorException("EMPTY_ANCHOR");
          addRange(paragraph, nextId);
          commentsRoot.appendChild(comment(comments, nextId, result));
          written.add(result);
          rejected.add(new AnnotationItem(result.ruleId(), AnnotationStatus.WRITTEN, ""));
          nextId++;
        } catch (AnchorException error) {
          rejected.add(new AnnotationItem(result.ruleId(), AnnotationStatus.UNSUPPORTED_ANCHOR, error.getMessage()));
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
    if (children.isEmpty()) throw new AnchorException("EMPTY_ANCHOR");
    Document doc = paragraph.getOwnerDocument();
    Element start = doc.createElementNS(W, "w:commentRangeStart");
    start.setAttributeNS(W, "w:id", String.valueOf(id));
    Element end = doc.createElementNS(W, "w:commentRangeEnd");
    end.setAttributeNS(W, "w:id", String.valueOf(id));
    paragraph.insertBefore(start, children.get(0));
    paragraph.insertBefore(end, children.get(children.size() - 1).getNextSibling());
    Element referenceRun = doc.createElementNS(W, "w:r");
    Element reference = doc.createElementNS(W, "w:commentReference");
    reference.setAttributeNS(W, "w:id", String.valueOf(id));
    referenceRun.appendChild(reference);
    paragraph.appendChild(referenceRun);
  }

  private static Element comment(Document comments, int id, CheckResult result) {
    Element c = comments.createElementNS(W, "w:comment");
    c.setAttributeNS(W, "w:id", String.valueOf(id));
    c.setAttributeNS(W, "w:author", "论文格式筛查");
    c.setAttributeNS(W, "w:date", "2026-01-01T00:00:00Z");
    Element p = comments.createElementNS(W, "w:p");
    Element r = comments.createElementNS(W, "w:r");
    Element t = comments.createElementNS(W, "w:t");
    t.setTextContent("修改意见：规则 " + result.ruleId() + "，期望 " + result.expected() + "，实际 " + result.actual());
    r.appendChild(t); p.appendChild(r); c.appendChild(p);
    return c;
  }

  private static byte[] addRelationship(byte[] bytes) throws IOException {
    Document doc = parse(bytes);
    Element root = doc.getDocumentElement();
    int max = 0;
    var nodes = root.getElementsByTagNameNS(REL, "Relationship");
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
      var output = new ByteArrayOutputStream();
      var transformer = TransformerFactory.newInstance().newTransformer();
      transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
      transformer.setOutputProperty(OutputKeys.INDENT, "no");
      transformer.transform(new DOMSource(doc), new StreamResult(output));
      return output.toByteArray();
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
