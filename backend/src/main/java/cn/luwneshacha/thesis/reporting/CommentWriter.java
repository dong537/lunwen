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
import java.time.Instant;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
  private static final String STRICT_W = "http://purl.oclc.org/ooxml/wordprocessingml/main";
  private static final String STRICT_COMMENTS_TYPE = "http://purl.oclc.org/ooxml/officeDocument/relationships/comments";
  private static final String W14 = "http://schemas.microsoft.com/office/word/2010/wordml";
  private static final String W15 = "http://schemas.microsoft.com/office/word/2012/wordml";
  private static final String W16CID = "http://schemas.microsoft.com/office/word/2016/wordml/cid";
  private static final String COMMENTS_EXTENDED_TYPE = "http://schemas.microsoft.com/office/2011/relationships/commentsExtended";
  private static final String PEOPLE_TYPE = "http://schemas.microsoft.com/office/2011/relationships/people";
  private static final String COMMENTS_IDS_TYPE = "http://schemas.microsoft.com/office/2016/09/relationships/commentsIds";
  private static final String COMMENTS_EXTENDED_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.commentsExtended+xml";
  private static final String PEOPLE_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.people+xml";
  private static final String COMMENTS_IDS_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.commentsIds+xml";
  private static final String COMMENT_AUTHOR = "论文格式筛查";
  private static final Pattern COVER_FIELD_LABEL = Pattern.compile(
      "^\\s*(学生姓名|学\\s*号|院\\s*系|学\\s*院|专业年级|年级|指导教师|指导老师)\\s*[:：]?");

  public AnnotationReport writeCopy(Path source, Path target, List<CheckResult> results) {
    return writeCopy(source, target, null, results, List.of());
  }

  public AnnotationReport writeCopy(Path source, Path target, String expectedSourceSha256, List<CheckResult> results) {
    return writeCopy(source, target, expectedSourceSha256, results, List.of());
  }

  /**
   * Writes document findings and explicit threaded replies in one package update.
   * Replies are attached to an existing w:comment by its w:id and do not create a
   * document range, which is the OOXML representation for a comment reply.
   */
  public AnnotationReport writeCopy(Path source, Path target, String expectedSourceSha256,
      List<CheckResult> results, List<CommentReply> replies) {
    List<AnnotationItem> rejected = new ArrayList<>();
    try {
      replies = replies == null ? List.of() : List.copyOf(replies);
      byte[] sourceBytes = Files.readAllBytes(source);
      String actualHash = sha256(sourceBytes);
      if (expectedSourceSha256 != null && !expectedSourceSha256.equalsIgnoreCase(actualHash)) {
        for (CheckResult result : results) rejected.add(new AnnotationItem(result.ruleId(), AnnotationStatus.REJECTED, "SOURCE_HASH_MISMATCH", result.source()));
        for (int index = 0; index < replies.size(); index++) {
          rejected.add(new AnnotationItem("COMMENT-THREAD", AnnotationStatus.REJECTED, "SOURCE_HASH_MISMATCH", null));
        }
        return new AnnotationReport(rejected, false);
      }
      Map<String, byte[]> parts = readParts(source);
      Document document = parse(parts.get("word/document.xml"));
      boolean strict = isStrictDocument(document);
      Map<String, Document> documents = new LinkedHashMap<>();
      documents.put("word/document.xml", document);
      PageStartAnchorResolver pageStarts = new PageStartAnchorResolver(document,
          parts.containsKey("word/_rels/document.xml.rels") ? parse(parts.get("word/_rels/document.xml.rels")) : null,
          parts.containsKey("word/styles.xml") ? parse(parts.get("word/styles.xml")) : null);
      int nextId = nextCommentId(parts.get("word/comments.xml"));
      // 保留输入文档已有的批注并追加本次结果。重跑服务时输入通常是原始论文，
      // 因而不会把旧结果带入；若用户明确把带批注文档作为输入，也不能静默删除其批注。
      Document comments = existingCommentsDocument(parts.get("word/comments.xml"), strict);
      Set<String> usedParaIds = new HashSet<>();
      collectParaIds(parts, usedParaIds);
      Document commentsExtended = existingModernPart(parts.get("word/commentsExtended.xml"), "commentsEx");
      Document people = existingModernPart(parts.get("word/people.xml"), "people");
      Document commentsIds = existingCommentIdsPart(parts.get("word/commentsIds.xml"));
      Set<String> usedDurableIds = collectDurableIds(commentsIds);
      boolean needsModernParts = false;
      ensurePerson(people, COMMENT_AUTHOR);
      stripExistingHighlights(document);
      Element commentsRoot = comments.getDocumentElement();
      List<CheckResult> written = new ArrayList<>();
      List<AnnotationItem> writtenItems = new ArrayList<>();
      Map<String, List<CheckResult>> grouped = new LinkedHashMap<>();
      Map<String, Element> initialAnchors = new LinkedHashMap<>();
      Map<String, String> initialAnchorErrors = new LinkedHashMap<>();
      Map<CheckResult, Element> sourceElements = new LinkedHashMap<>();
      Map<CheckResult, PageStartAnchorResolver.Placement> pointPlacements = new LinkedHashMap<>();
      Map<Element, Integer> anchorIds = new IdentityHashMap<>();
      int nextAnchorId = 0;
      for (CheckResult result : results) {
        if (result.source() == null) {
          rejected.add(new AnnotationItem(result.ruleId(), AnnotationStatus.UNSUPPORTED_ANCHOR,
              "MISSING_SOURCE", null));
          continue;
        }
        String partName = result.source().partName();
        if (!parts.containsKey(partName) || !partName.startsWith("word/")) {
          rejected.add(new AnnotationItem(result.ruleId(), AnnotationStatus.UNSUPPORTED_ANCHOR, "PART_NOT_SUPPORTED", result.source()));
          continue;
        }
        Document partDocument = documents.computeIfAbsent(partName, ignored -> {
          try {
            Document parsed = parse(parts.get(partName));
            stripExistingHighlights(parsed);
            return parsed;
          }
          catch (IOException error) { throw new IllegalStateException(error); }
        });
        String key;
        try {
          Element anchor = resolveAnchor(partDocument, result.source());
          if (supportsEmptyPoint(result) && isBlankParagraph(anchor)) {
            var placement = pageStarts.resolve(partName, anchor, result.contextSource());
            pointPlacements.put(result, placement);
            anchor = placement.paragraph();
          }
          Integer anchorId = anchorIds.get(anchor);
          if (anchorId == null) {
            anchorId = nextAnchorId++;
            anchorIds.put(anchor, anchorId);
          }
          String anchorPart = pointPlacements.containsKey(result)
              ? pointPlacements.get(result).anchorSource().partName() : partName;
          key = anchorPart + "|anchor:" + anchorId + (pointPlacements.containsKey(result) ? "|point" : "");
          initialAnchors.putIfAbsent(key, anchor);
          sourceElements.put(result, resolve(partDocument, result.source()));
        } catch (AnchorException error) {
          key = partName + "|path:" + result.source().elementPath();
          initialAnchorErrors.putIfAbsent(key, error.getMessage());
        }
        grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(result);
      }
      // Resolve every anchor before inserting comment markers. Markers change child indexes in
      // nested XML (text boxes, tables), so resolving lazily would make later paths invalid.
      Map<String, Element> anchors = new LinkedHashMap<>();
      Map<String, String> anchorErrors = new LinkedHashMap<>();
      for (Map.Entry<String, List<CheckResult>> entry : grouped.entrySet()) {
        CheckResult first = entry.getValue().get(0);
        try {
          Document partDocument = documents.get(first.source().partName());
          if (initialAnchorErrors.containsKey(entry.getKey()))
            throw new AnchorException(initialAnchorErrors.get(entry.getKey()));
          Element anchor = initialAnchors.get(entry.getKey());
          if (anchor == null) throw new AnchorException("ANCHOR_NOT_PARAGRAPH");
          if ("word/document.xml".equals(first.source().partName())
              && ("PAGE-01".equals(first.ruleId()) || "PAGE-04".equals(first.ruleId()))
              && !pointPlacements.containsKey(first)
              && isBlankParagraph(anchor)) anchor = nearestNonEmptyParagraph(partDocument, anchor);
          anchors.put(entry.getKey(), anchor);
        } catch (AnchorException error) {
          anchorErrors.put(entry.getKey(), error.getMessage());
        }
      }
      // Text highlighting depends on the original run identities. Complete all text groups
      // before a migrated point splits a leading page-break run in the same paragraph.
      var writingOrder = new ArrayList<>(grouped.entrySet());
      writingOrder.sort(java.util.Comparator.comparing(entry -> pointPlacements.containsKey(entry.getValue().getFirst())));
      for (Map.Entry<String, List<CheckResult>> entry : writingOrder) {
        List<CheckResult> group = entry.getValue();
        CheckResult result = group.get(0);
        try {
          if (anchorErrors.containsKey(entry.getKey())) throw new AnchorException(anchorErrors.get(entry.getKey()));
          Element paragraph = anchors.get(entry.getKey());
          String wordNamespace = paragraph.getNamespaceURI();
          if ("tbl".equals(paragraph.getLocalName()) && wordNamespace != null) {
            var nested = paragraph.getElementsByTagNameNS(wordNamespace, "p");
            if (nested.getLength() == 0) throw new AnchorException("TABLE_HAS_NO_PARAGRAPH");
            paragraph = (Element) nested.item(0);
            wordNamespace = paragraph.getNamespaceURI();
          }
          if (!"p".equals(paragraph.getLocalName()) || wordNamespace == null) throw new AnchorException("ANCHOR_NOT_PARAGRAPH");
          String text = paragraph.getTextContent();
          boolean hasDrawing = paragraph.getElementsByTagNameNS(wordNamespace, "drawing").getLength() > 0;
          boolean hasField = paragraph.getElementsByTagNameNS(wordNamespace, "fldSimple").getLength() > 0
              || paragraph.getElementsByTagNameNS(wordNamespace, "instrText").getLength() > 0;
          boolean missingHeaderOnEmptySection = group.stream()
              .anyMatch(item -> "HEADER-01-MISSING".equals(item.ruleId()));
          boolean pointPlacement = pointPlacements.containsKey(result);
          if ((text == null || text.isBlank()) && !hasDrawing && !hasField && !missingHeaderOnEmptySection && !pointPlacement)
            throw new AnchorException("EMPTY_ANCHOR");
          // 只有规则结果能定位到具体运行（w:r）时才着色。段落级规则（首行缩进、行距、空行等）
          // 的错误范围本来就是段落属性，给整段文字着色会制造大面积无关高亮。
          TextRange preciseRange = pointPlacement ? new TextRange(0, 0) : null;
          for (CheckResult item : group) {
            if (pointPlacements.containsKey(item)) continue;
            TextRange candidate = preciseRange(paragraph, sourceElements.get(item), item);
            if (candidate != null && (preciseRange == null
                || candidate.end() - candidate.start() < preciseRange.end() - preciseRange.start())) {
              preciseRange = candidate;
            }
          }
          for (CheckResult item : group)
            if (!pointPlacements.containsKey(item)) highlight(paragraph, item, sourceElements.get(item));
          if (pointPlacement && pointPlacements.get(result).afterLeadingBreak())
            addPointAfterPageBreak(paragraph, nextId, pageStarts.leadingBreak(paragraph));
          else addRange(paragraph, nextId, preciseRange);
          // 一个段落只插入一个批注；颜色取该段落最先发现的错误类型，批注正文会列出其余问题。
          String paraId = nextParaId(usedParaIds);
          commentsRoot.appendChild(comment(comments, nextId, paraId, group, paragraph, pointPlacements));
          appendCommentExtended(commentsExtended, paraId);
          appendCommentId(commentsIds, paraId, nextDurableId(paraId, nextId, usedDurableIds));
          needsModernParts = true;
          for (CheckResult item : group) {
            written.add(item);
            var placement = pointPlacements.get(item);
            writtenItems.add(new AnnotationItem(item.ruleId(), AnnotationStatus.WRITTEN,
                placement == null ? "" : placement.reason(), item.source(),
                placement == null ? null : placement.anchorSource()));
          }
          nextId++;
        } catch (AnchorException error) {
          for (CheckResult item : group) rejected.add(new AnnotationItem(item.ruleId(), AnnotationStatus.UNSUPPORTED_ANCHOR, error.getMessage(), item.source()));
        }
      }
      boolean wroteThread = false;
      for (CommentReply reply : replies) {
        try {
          String parentParaId = commentParaId(comments, reply.parentCommentId());
          if (parentParaId == null) throw new IllegalArgumentException("PARENT_COMMENT_NOT_FOUND");
          String paraId = nextParaId(usedParaIds);
          commentsRoot.appendChild(commentWithText(comments, nextId, paraId, reply.author(), reply.text()));
          appendCommentExtended(commentsExtended, paraId, parentParaId);
          appendCommentId(commentsIds, paraId, nextDurableId(paraId, nextId, usedDurableIds));
          ensurePerson(people, reply.author());
          nextId++;
          wroteThread = true;
          writtenItems.add(new AnnotationItem("COMMENT-THREAD", AnnotationStatus.WRITTEN, "", null));
          needsModernParts = true;
        } catch (IllegalArgumentException error) {
          rejected.add(new AnnotationItem("COMMENT-THREAD", AnnotationStatus.REJECTED,
              error.getMessage() == null ? "THREAD_WRITE_FAILED" : error.getMessage(), null));
        }
      }
      if (written.isEmpty() && !wroteThread) return new AnnotationReport(rejected, false);
      for (Map.Entry<String, Document> entry : documents.entrySet()) parts.put(entry.getKey(), serialize(entry.getValue()));
      parts.put("word/comments.xml", serialize(comments));
      parts.put("word/_rels/document.xml.rels", addRelationship(parts.get("word/_rels/document.xml.rels"), strict));
      if (needsModernParts) {
        parts.put("word/commentsExtended.xml", serialize(commentsExtended));
        parts.put("word/commentsIds.xml", serialize(commentsIds));
        parts.put("word/people.xml", serialize(people));
        parts.put("word/_rels/document.xml.rels", addModernRelationships(parts.get("word/_rels/document.xml.rels")));
        parts.put("[Content_Types].xml", addContentType(parts.get("[Content_Types].xml"), true));
      } else {
        parts.put("[Content_Types].xml", addContentType(parts.get("[Content_Types].xml"), false));
      }
      writeParts(target, parts);
      rejected.addAll(writtenItems);
      return new AnnotationReport(rejected, true);
    } catch (IOException | RuntimeException error) {
      try { if (target != null) Files.deleteIfExists(target); }
      catch (IOException cleanupError) { error.addSuppressed(cleanupError); }
      for (CheckResult result : results) {
        boolean alreadyReported = rejected.stream().anyMatch(item ->
            item.ruleId().equals(result.ruleId()) && java.util.Objects.equals(item.source(), result.source()));
        if (!alreadyReported)
          rejected.add(new AnnotationItem(result.ruleId(), AnnotationStatus.REJECTED, "WRITE_FAILED", result.source()));
      }
      for (int index = 0; index < replies.size(); index++) {
        rejected.add(new AnnotationItem("COMMENT-THREAD", AnnotationStatus.REJECTED, "WRITE_FAILED", null));
      }
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

  private static boolean supportsEmptyPoint(CheckResult result) {
    return result.ruleId().startsWith("HEADER-") || "PAGE-04".equals(result.ruleId())
        || result.ruleId().endsWith("-BLANK");
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

  private static Element resolveAnchor(Document document, SourceAddress address) throws AnchorException {
    Element resolved = resolve(document, address);
    if ("p".equals(resolved.getLocalName())) return resolved;
    Element ancestor = resolved;
    while (ancestor != null && !"p".equals(ancestor.getLocalName())) ancestor = parentElement(ancestor);
    if (ancestor != null) return ancestor;
    Element descendantParagraph = firstParagraphWithMainStoryContent(resolved);
    if (descendantParagraph != null) return descendantParagraph;
    if ("sectPr".equals(resolved.getLocalName())) return nearestNonEmptyParagraphBefore(document, resolved);
    throw new AnchorException("ANCHOR_NOT_PARAGRAPH");
  }

  private static boolean isBlankParagraph(Element paragraph) {
    return paragraph == null || !isInsideTextBox(paragraph) && !hasMainStoryContent(paragraph);
  }

  private static Element firstParagraphWithMainStoryContent(Element root) {
    String namespace = root.getNamespaceURI();
    var paragraphs = root.getElementsByTagNameNS(namespace, "p");
    for (int index = 0; index < paragraphs.getLength(); index++) {
      Element paragraph = (Element) paragraphs.item(index);
      if (!isInsideTextBox(paragraph) && hasMainStoryContent(paragraph)) return paragraph;
    }
    return null;
  }

  private static Element nearestNonEmptyParagraphBefore(Document document, Element sectionProperties)
      throws AnchorException {
    String namespace = document.getDocumentElement().getNamespaceURI();
    var paragraphs = document.getDocumentElement().getElementsByTagNameNS(namespace, "p");
    for (int index = paragraphs.getLength() - 1; index >= 0; index--) {
      Element paragraph = (Element) paragraphs.item(index);
      if ((paragraph.compareDocumentPosition(sectionProperties) & Node.DOCUMENT_POSITION_FOLLOWING) == 0)
        continue;
      if (isInsideTextBox(paragraph)) continue;
      if (hasDirectSectionProperties(paragraph)) break;
      if (hasMainStoryContent(paragraph)) return paragraph;
    }
    throw new AnchorException("EMPTY_ANCHOR");
  }

  /**
   * 节属性通常挂在空的分节段落上。页边距问题应落在该节实际内容附近，
   * 不能统一退回封面第一段，否则用户会误以为封面页边距错误。
   */
  private static Element nearestNonEmptyParagraph(Document document, Element sectionParagraph)
      throws AnchorException {
    String namespace = document.getDocumentElement().getNamespaceURI();
    var paragraphs = document.getDocumentElement().getElementsByTagNameNS(namespace, "p");
    int anchorIndex = -1;
    for (int index = 0; index < paragraphs.getLength(); index++) {
      if (paragraphs.item(index) == sectionParagraph) {
        anchorIndex = index;
        break;
      }
    }
    if (anchorIndex >= 0) {
      // A paragraph's sectPr ends the section before it, never the next one.
      // Stop at the previous section boundary instead of borrowing its text.
      for (int index = anchorIndex - 1; index >= 0; index--) {
        Element paragraph = (Element) paragraphs.item(index);
        if (isInsideTextBox(paragraph)) continue;
        if (hasDirectSectionProperties(paragraph)) break;
        if (hasMainStoryContent(paragraph)) return paragraph;
      }
    }
    throw new AnchorException("EMPTY_ANCHOR");
  }

  private static boolean hasDirectSectionProperties(Element paragraph) {
    Element paragraphProperties = directChild(paragraph, W, "pPr");
    return directChild(paragraphProperties, W, "sectPr") != null;
  }

  private static Element directChild(Element parent, String namespace, String localName) {
    if (parent == null) return null;
    for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
      if (child instanceof Element element && namespace.equals(element.getNamespaceURI())
          && localName.equals(element.getLocalName())) return element;
    }
    return null;
  }

  private static boolean isInsideTextBox(Element element) {
    Node current = element;
    while (current instanceof Element currentElement) {
      if (W.equals(currentElement.getNamespaceURI()) && "txbxContent".equals(currentElement.getLocalName()))
        return true;
      current = current.getParentNode();
    }
    return false;
  }

  private static boolean hasMainStoryContent(Element element) {
    return hasVisibleTextOutsideTextBoxes(element)
        || element.getElementsByTagNameNS(W, "txbxContent").getLength() == 0
            && hasDrawingOutsideTextBoxes(element);
  }

  private static boolean hasVisibleTextOutsideTextBoxes(Node node) {
    if (node instanceof Element element && W.equals(element.getNamespaceURI())
        && "txbxContent".equals(element.getLocalName())) return false;
    if (node.getNodeType() == Node.TEXT_NODE && !node.getNodeValue().isBlank()) return true;
    for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
      if (hasVisibleTextOutsideTextBoxes(child)) return true;
    }
    return false;
  }

  private static boolean hasDrawingOutsideTextBoxes(Node node) {
    if (node instanceof Element element && W.equals(element.getNamespaceURI())) {
      if ("txbxContent".equals(element.getLocalName())) return false;
      if ("drawing".equals(element.getLocalName())) return true;
    }
    for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
      if (hasDrawingOutsideTextBoxes(child)) return true;
    }
    return false;
  }

  private static Element parentElement(Element element) {
    Node parent = element.getParentNode();
    return parent instanceof Element ? (Element) parent : null;
  }

  private static void stripExistingHighlights(Document document) {
    String namespace = document.getDocumentElement().getNamespaceURI();
    var highlights = document.getElementsByTagNameNS(namespace, "highlight");
    while (highlights.getLength() > 0) {
      Node highlight = highlights.item(0);
      Node parent = highlight.getParentNode();
      if (parent != null) parent.removeChild(highlight);
    }
  }

  private static String relationshipPartName(String partName) {
    int slash = partName.lastIndexOf('/');
    String directory = slash < 0 ? "" : partName.substring(0, slash + 1);
    String file = slash < 0 ? partName : partName.substring(slash + 1);
    return directory + "_rels/" + file + ".rels";
  }

  private static void addPointAfterPageBreak(Element paragraph, int id, Element pageBreak) throws AnchorException {
    if (pageBreak == null || !(pageBreak.getParentNode() instanceof Element run)
        || !"r".equals(run.getLocalName()) || run.getParentNode() != paragraph)
      throw new AnchorException("PAGE_START_BOUNDARY_UNAVAILABLE");
    Element suffix = (Element) run.cloneNode(false);
    for (Element child : elementChildren(run)) {
      if ("rPr".equals(child.getLocalName())) suffix.appendChild(child.cloneNode(true));
    }
    boolean hasSuffix = false;
    for (Node node = pageBreak.getNextSibling(); node != null;) {
      Node next = node.getNextSibling();
      suffix.appendChild(node);
      hasSuffix = true;
      node = next;
    }
    Node boundary = run.getNextSibling();
    if (hasSuffix) {
      paragraph.insertBefore(suffix, boundary);
      boundary = suffix;
    }
    addZeroWidthAtParagraphNode(paragraph, id, boundary);
  }

  private static void addRange(Element paragraph, int id, TextRange preciseRange) throws AnchorException {
    if (preciseRange == null) {
      addWholeParagraphRange(paragraph, id);
    } else if (preciseRange.start() == preciseRange.end()) {
      try {
        addZeroWidthRange(paragraph, id, preciseRange.start());
      } catch (AnchorException error) {
        if (!error.getMessage().startsWith("PRECISE_RANGE_COMPLEX")) throw error;
        addZeroWidthAtSafeBoundary(paragraph, id, preciseRange.start());
      }
    } else if (hasCommentRange(paragraph)) {
      addZeroWidthRange(paragraph, id, visibleParagraphText(paragraph).length());
    } else {
      try {
        addTextRange(paragraph, id, preciseRange.start(), preciseRange.end());
      } catch (AnchorException error) {
        if (!error.getMessage().startsWith("PRECISE_RANGE_COMPLEX")) throw error;
        addZeroWidthAtSafeBoundary(paragraph, id, preciseRange.start());
      }
    }
  }

  private static void addZeroWidthAtSafeBoundary(Element paragraph, int id, int offset)
      throws AnchorException {
    int visibleOffset = 0;
    Node insertionPoint = trailingSectionProperties(paragraph);
    for (Element run : visibleRuns(paragraph)) {
      String value = visibleRunText(run);
      int end = visibleOffset + value.length();
      if (offset <= end) {
        Node topLevel = topLevelChild(paragraph, run);
        insertionPoint = offset - visibleOffset <= end - offset ? topLevel : topLevel.getNextSibling();
        break;
      }
      visibleOffset = end;
    }
    if (offset < 0 || offset > visibleParagraphText(paragraph).length())
      throw new AnchorException("SOURCE_RANGE_OUT_OF_BOUNDS");
    addZeroWidthAtParagraphNode(paragraph, id, insertionPoint);
  }

  private static Node topLevelChild(Element paragraph, Node node) {
    Node current = node;
    while (current.getParentNode() != paragraph && current.getParentNode() != null)
      current = current.getParentNode();
    return current.getParentNode() == paragraph ? current : trailingSectionProperties(paragraph);
  }

  private static void addZeroWidthAtParagraphNode(Element paragraph, int id, Node insertionPoint) {
    String wordNamespace = paragraph.getNamespaceURI();
    Element start = rangeMarker(paragraph, "commentRangeStart", id);
    Element end = rangeMarker(paragraph, "commentRangeEnd", id);
    Element referenceRun = paragraph.getOwnerDocument().createElementNS(wordNamespace, "w:r");
    Element reference = paragraph.getOwnerDocument().createElementNS(wordNamespace, "w:commentReference");
    reference.setAttributeNS(wordNamespace, "w:id", String.valueOf(id));
    referenceRun.appendChild(reference);
    paragraph.insertBefore(start, insertionPoint);
    paragraph.insertBefore(end, insertionPoint);
    paragraph.insertBefore(referenceRun, insertionPoint);
  }

  private static TextRange preciseRange(Element paragraph, Element sourceElement,
      CheckResult result) throws AnchorException {
    SourceAddress source = result.source();
    if (sourceElement == null) return null;
    if (source.start() != source.end() && !isTextRangeRule(result.ruleId())) return null;
    if (sourceElement == paragraph) {
      int visibleLength = visibleParagraphText(paragraph).length();
      if (source.start() < 0 || source.end() > visibleLength)
        throw new AnchorException("SOURCE_RANGE_OUT_OF_BOUNDS");
      return new TextRange(source.start(), source.end());
    }
    if ("r".equals(sourceElement.getLocalName()) && isVisibleRun(sourceElement, paragraph)) {
      int visibleLength = visibleParagraphText(paragraph).length();
      if (source.start() < 0 || source.end() > visibleLength)
        throw new AnchorException("SOURCE_RANGE_OUT_OF_BOUNDS");
      return new TextRange(source.start(), source.end());
    }
    return null;
  }

  private static boolean isVisibleRun(Element run, Element paragraph) {
    if (nearestParagraph(run) != paragraph) return false;
    for (Element ancestor = run; ancestor != null && ancestor != paragraph; ancestor = parentElement(ancestor)) {
      if ("del".equals(ancestor.getLocalName()) || "moveFrom".equals(ancestor.getLocalName())) return false;
    }
    return true;
  }

  private static void addZeroWidthRange(Element paragraph, int id, int offset)
      throws AnchorException {
    String wordNamespace = paragraph.getNamespaceURI();
    Node boundary = insertionPointAtOffset(paragraph, offset, false);

    Element start = paragraph.getOwnerDocument().createElementNS(wordNamespace, "w:commentRangeStart");
    start.setAttributeNS(wordNamespace, "w:id", String.valueOf(id));
    Element end = paragraph.getOwnerDocument().createElementNS(wordNamespace, "w:commentRangeEnd");
    end.setAttributeNS(wordNamespace, "w:id", String.valueOf(id));
    Element referenceRun = paragraph.getOwnerDocument().createElementNS(wordNamespace, "w:r");
    Element reference = paragraph.getOwnerDocument().createElementNS(wordNamespace, "w:commentReference");
    reference.setAttributeNS(wordNamespace, "w:id", String.valueOf(id));
    referenceRun.appendChild(reference);
    paragraph.insertBefore(start, boundary);
    paragraph.insertBefore(end, boundary);
    paragraph.insertBefore(referenceRun, end.getNextSibling());
  }

  private static void addTextRange(Element paragraph, int id, int startOffset, int endOffset)
      throws AnchorException {
    int visibleLength = visibleParagraphText(paragraph).length();
    if (startOffset < 0 || endOffset < startOffset || endOffset > visibleLength)
      throw new AnchorException("SOURCE_RANGE_OUT_OF_BOUNDS");
    if (startOffset == endOffset) {
      addZeroWidthRange(paragraph, id, startOffset);
      return;
    }
    if (addPartialHyperlinkRange(paragraph, id, startOffset, endOffset)) return;

    Node endPoint = insertionPointAtOffset(paragraph, endOffset, true);
    Node startPoint = insertionPointAtOffset(paragraph, startOffset, false);
    String wordNamespace = paragraph.getNamespaceURI();
    Element start = rangeMarker(paragraph, "commentRangeStart", id);
    Element end = rangeMarker(paragraph, "commentRangeEnd", id);
    Element referenceRun = paragraph.getOwnerDocument().createElementNS(wordNamespace, "w:r");
    Element reference = paragraph.getOwnerDocument().createElementNS(wordNamespace, "w:commentReference");
    reference.setAttributeNS(wordNamespace, "w:id", String.valueOf(id));
    referenceRun.appendChild(reference);

    paragraph.insertBefore(start, startPoint);
    paragraph.insertBefore(end, endPoint);
    paragraph.insertBefore(referenceRun, end.getNextSibling());
  }

  /** Keep a partial range inside its hyperlink without splitting the link or moving its bookmarks. */
  private static boolean addPartialHyperlinkRange(Element paragraph, int id, int startOffset, int endOffset)
      throws AnchorException {
    Element hyperlink = null;
    Node previousContainer = null;
    int containerStart = 0;
    int hyperlinkStart = 0;
    int offset = 0;
    for (Element run : visibleRuns(paragraph)) {
      int end = offset + visibleRunText(run).length();
      if (end == offset) continue;
      Node container = topLevelChild(paragraph, run);
      if (container != previousContainer) {
        previousContainer = container;
        containerStart = offset;
      }
      if (offset < endOffset && end > startOffset) {
        if (!(container instanceof Element element)
            || !paragraph.getNamespaceURI().equals(element.getNamespaceURI())
            || !"hyperlink".equals(element.getLocalName())) return false;
        if (run.getParentNode() != element) return false;
        if (hyperlink == null) {
          hyperlink = element;
          hyperlinkStart = containerStart;
        } else if (hyperlink != element) {
          return false;
        }
      }
      offset = end;
    }
    if (hyperlink == null || startOffset == hyperlinkStart
        && endOffset == hyperlinkStart + visibleParagraphText(hyperlink).length()) return false;
    addTextRange(hyperlink, id, startOffset - hyperlinkStart, endOffset - hyperlinkStart);
    return true;
  }

  private static Node insertionPointAtOffset(Element paragraph, int offset, boolean endBoundary)
      throws AnchorException {
    int visibleOffset = 0;
    Element lastVisibleRun = null;
    for (Element run : visibleRuns(paragraph)) {
      String runText = visibleRunText(run);
      if (runText.isEmpty()) continue;
      int end = visibleOffset + runText.length();
      if (offset == visibleOffset)
        return topLevelChild(paragraph, run);
      if (offset < end) {
        Node topLevel = topLevelChild(paragraph, run);
        if (topLevel == run) {
          Element suffix = splitRunText(run, offset - visibleOffset);
          return suffix;
        }
        return endBoundary ? topLevel.getNextSibling() : topLevel;
      }
      visibleOffset = end;
      lastVisibleRun = run;
    }
    if (offset == visibleOffset && lastVisibleRun != null)
      return topLevelChild(paragraph, lastVisibleRun).getNextSibling();
    if (offset == 0 && lastVisibleRun == null)
      return trailingSectionProperties(paragraph);
    throw new AnchorException("SOURCE_RANGE_OUT_OF_BOUNDS");
  }

  private static Element rangeMarker(Element paragraph, String localName, int id) {
    Element marker = paragraph.getOwnerDocument().createElementNS(paragraph.getNamespaceURI(), "w:" + localName);
    marker.setAttributeNS(paragraph.getNamespaceURI(), "w:id", String.valueOf(id));
    return marker;
  }

  private static boolean hasCommentRange(Element paragraph) {
    for (Node node = paragraph.getFirstChild(); node != null; node = node.getNextSibling()) {
      if (node instanceof Element element && "commentRangeStart".equals(element.getLocalName())) return true;
    }
    return false;
  }

  private static List<Element> visibleRuns(Element paragraph) {
    List<Element> result = new ArrayList<>();
    collectVisibleRuns(paragraph, paragraph, result);
    return result;
  }

  private static void collectVisibleRuns(Element parent, Element paragraph, List<Element> result) {
    for (Element child : elementChildren(parent)) {
      if ("p".equals(child.getLocalName()) && child != paragraph) continue;
      if ("del".equals(child.getLocalName()) || "moveFrom".equals(child.getLocalName())) continue;
      if ("r".equals(child.getLocalName())) {
        result.add(child);
      } else {
        collectVisibleRuns(child, paragraph, result);
      }
    }
  }

  /** Splits a visible text run at the requested offset, preserving its parent wrapper. */
  private static Element splitRunText(Element run, int offset) throws AnchorException {
    List<Element> prefixNodes = new ArrayList<>();
    List<Element> suffixNodes = new ArrayList<>();
    int visibleOffset = 0;
    for (Node node = run.getFirstChild(); node != null; node = node.getNextSibling()) {
      if (!(node instanceof Element child)) continue;
      if ("rPr".equals(child.getLocalName())) continue;
      if (!"t".equals(child.getLocalName())) throw new AnchorException("PRECISE_RANGE_COMPLEX_RUN");
      String value = child.getTextContent();
      int end = visibleOffset + value.length();
      if (end <= offset) {
        prefixNodes.add(copyTextNode(child, value));
      } else if (visibleOffset >= offset) {
        suffixNodes.add(copyTextNode(child, value));
      } else {
        int split = offset - visibleOffset;
        prefixNodes.add(copyTextNode(child, value.substring(0, split)));
        suffixNodes.add(copyTextNode(child, value.substring(split)));
      }
      visibleOffset = end;
    }
    if (prefixNodes.isEmpty() || suffixNodes.isEmpty() || offset <= 0 || offset >= visibleOffset)
      throw new AnchorException("PRECISE_RANGE_COMPLEX_RUN");
    Element prefix = cloneTextRun(run, prefixNodes);
    Element suffix = cloneTextRun(run, suffixNodes);
    Node parent = run.getParentNode();
    parent.insertBefore(prefix, run);
    parent.insertBefore(suffix, run);
    parent.removeChild(run);
    return suffix;
  }

  private static Element cloneTextRun(Element source, List<Element> textNodes) {
    Element copy = (Element) source.cloneNode(true);
    List<Node> existingTextNodes = new ArrayList<>();
    for (Node node = copy.getFirstChild(); node != null; node = node.getNextSibling()) {
      if (node instanceof Element child && "t".equals(child.getLocalName())) existingTextNodes.add(child);
    }
    existingTextNodes.forEach(copy::removeChild);
    textNodes.forEach(copy::appendChild);
    return copy;
  }

  private static Element copyTextNode(Element source, String value) {
    Element copy = (Element) source.cloneNode(true);
    copy.setTextContent(value);
    if (startsOrEndsWithWhitespace(value))
      copy.setAttributeNS(XMLConstants.XML_NS_URI, "xml:space", "preserve");
    return copy;
  }

  private static void addWholeParagraphRange(Element paragraph, int id) throws AnchorException {
    Document doc = paragraph.getOwnerDocument();
    String wordNamespace = paragraph.getNamespaceURI();
    Element existingEnd = null;
    boolean hasExistingRange = false;
    for (Node node = paragraph.getFirstChild(); node != null; node = node.getNextSibling()) {
      if (node instanceof Element child) {
        if ("commentRangeStart".equals(child.getLocalName())) hasExistingRange = true;
        if ("commentRangeEnd".equals(child.getLocalName()) && existingEnd == null) existingEnd = child;
      }
    }
    if (hasExistingRange && existingEnd != null) {
      // Do not reuse another comment's range. Overlapping ranges are invalid OOXML and
      // leave the new comment without a matching start/end pair. Add a zero-width range
      // at the end of the paragraph so repeated inspections remain independently anchored.
      Element start = doc.createElementNS(wordNamespace, "w:commentRangeStart");
      start.setAttributeNS(wordNamespace, "w:id", String.valueOf(id));
      Element end = doc.createElementNS(wordNamespace, "w:commentRangeEnd");
      end.setAttributeNS(wordNamespace, "w:id", String.valueOf(id));
      Element referenceRun = doc.createElementNS(wordNamespace, "w:r");
      Element reference = doc.createElementNS(wordNamespace, "w:commentReference");
      reference.setAttributeNS(wordNamespace, "w:id", String.valueOf(id));
      referenceRun.appendChild(reference);
      Node insertionPoint = trailingSectionProperties(paragraph);
      paragraph.insertBefore(start, insertionPoint);
      paragraph.insertBefore(end, insertionPoint);
      paragraph.insertBefore(referenceRun, insertionPoint);
      return;
    }
    List<Element> children = elementChildren(paragraph);
    Element firstContent = children.stream().filter(child -> !"pPr".equals(child.getLocalName())).findFirst().orElse(null);
    Element lastContent = null;
    for (Element child : children) {
      if (!"pPr".equals(child.getLocalName()) && !"sectPr".equals(child.getLocalName())) lastContent = child;
    }
    if (firstContent == null || lastContent == null) throw new AnchorException("EMPTY_ANCHOR");
    Element start = doc.createElementNS(wordNamespace, "w:commentRangeStart");
    start.setAttributeNS(wordNamespace, "w:id", String.valueOf(id));
    Element end = doc.createElementNS(wordNamespace, "w:commentRangeEnd");
    end.setAttributeNS(wordNamespace, "w:id", String.valueOf(id));
    paragraph.insertBefore(start, firstContent);
    paragraph.insertBefore(end, lastContent.getNextSibling());
    Element referenceRun = doc.createElementNS(wordNamespace, "w:r");
    Element reference = doc.createElementNS(wordNamespace, "w:commentReference");
    reference.setAttributeNS(wordNamespace, "w:id", String.valueOf(id));
    referenceRun.appendChild(reference);
    paragraph.appendChild(referenceRun);
  }

  private static Node trailingSectionProperties(Element paragraph) {
    for (Node node = paragraph.getLastChild(); node != null; node = node.getPreviousSibling()) {
      if (node instanceof Element element && "sectPr".equals(element.getLocalName())) return element;
    }
    return null;
  }

  private static void highlight(Element paragraph, CheckResult result, Element sourceElement) {
    String ruleId = result.ruleId();
    String color = switch (ruleId) {
      case "BODY-SIZE" -> "yellow";
      case "BODY-FONT" -> "cyan";
      case "BODY-INDENT" -> "green";
      case "BODY-LINE" -> "darkMagenta";
      case "BODY-UNIT" -> "magenta";
      case "ABSTRACT-FONT" -> "cyan";
      case "ABSTRACT-SIZE" -> "yellow";
      case "ABSTRACT-INDENT" -> "green";
      case "ABSTRACT-LINE" -> "darkMagenta";
      case "ZH-01-SPACING", "ZH-02-BLANK", "ZH-04-BLANK", "EN-01-BLANK", "EN-03-BLANK" -> "magenta";
      case "EN-03-FORMAT", "EN-03-ALIGNMENT" -> "magenta";
      case "ABSTRACT-KEYWORD-BOLD", "EN-03-BOLD" -> "green";
      case "ABSTRACT-KEYWORD-FORMAT", "ABSTRACT-KEYWORD-ALIGN" -> "magenta";
      case "PAGE-01" -> "magenta";
      case "PAGE-05" -> "yellow";
      case "PAGE-04" -> "red";
      case "PAGE-03" -> "lightGray";
      case "HEAD-02-BLANK" -> "magenta";
      case "HEADING-LINE" -> "darkMagenta";
      case "TOC-01-BLANK", "TOC-01-SPACING", "TOC-SPACING" -> "magenta";
      case "TOC-02", "TOC-02-ORDER", "TOC-REQUIRED-ENTRY" -> "red";
      case "TOC-01-LINE" -> "darkMagenta";
      case "TOC-ENTRY-FONT" -> "cyan";
      case "TOC-ENTRY-SIZE" -> "yellow";
      case "TOC-ENTRY-LINE" -> "darkMagenta";
      case "TOC-ENTRY-INDENT" -> "green";
      case "TOC-ENTRY-PAGE" -> "red";
      case "HEAD-01-SPACING", "HEAD-02-SPACING", "HEAD-03-SPACING" -> "magenta";
      case "APP-02-SPACING" -> "magenta";
      case "APP-03", "BODY-SCOPE" -> "lightGray";
      case "CITE-01" -> "yellow";
      case "HEAD-03-INDENT", "HEAD-05-INDENT" -> "green";
      case "HEAD-05-FONT" -> "cyan";
      case "HEAD-05-SIZE", "HEAD-05-LINE" -> "yellow";
      case "HEAD-05-MARKER", "COVER-04-CONSISTENCY", "COVER-03-LABEL" -> "magenta";
      case "FIG-01" -> "red";
      case "FIG-03" -> "yellow";
      case "FIG-04" -> "yellow";
      case "FIG-05" -> "yellow";
      case "TAB-02" -> "lightGray";
      case "TAB-03" -> "yellow";
      case "TAB-04" -> "magenta";
      case "TABLE_UNIT_LOCATION_NOT_CAPTURED" -> "magenta";
      case "TAB-07", "TAB-08" -> "magenta";
      case "EQ-01", "EQ-02" -> "yellow";
      case "EQ-03", "EQ-06" -> "lightGray";
      case "EQ-04" -> "red";
      case "EQ-05" -> "yellow";
      case "EQUATION_LAYOUT_REVIEW", "COVER_TITLE_LABEL_AND_VALUE_SHARE_RUN", "COVER_FIELD_LABEL_AND_VALUE_SHARE_RUN" -> "lightGray";
      case "EQUATION_VARIABLES_REVIEW" -> "magenta";
      case "EQUATION_VARIABLES_UNREADABLE", "EQUATION_VARIABLES_MISSING" -> "magenta";
      case "FIG-02" -> "lightGray";
      case "REF-FONT" -> "cyan";
      case "REF-SIZE" -> "yellow";
      case "REF-LINE" -> "darkMagenta";
      case "REF-03" -> "magenta";
      case "REF-02", "REF-04" -> "magenta";
      case "REF-05" -> "red";
      case "REF-07" -> "red";
      case "APP-01" -> "red";
      case "CAPTION-FONT" -> "cyan";
      case "CAPTION-SIZE" -> "yellow";
      case "HEADING-FONT" -> "cyan";
      case "HEADING-SIZE" -> "yellow";
      case "COVER-01-FONT", "COVER-02-FONT", "COVER-03-FONT", "COVER-04-FONT" -> "cyan";
      case "COVER-01-SIZE", "COVER-02-SIZE", "COVER-03-SIZE", "COVER-04-SIZE" -> "yellow";
      case "COVER-01-ALIGNMENT" -> "magenta";
      case "COVER-03-BOLD", "COVER-04-BOLD" -> "green";
      case "ZH-01-FONT", "EN-01-FONT", "TOC-01-FONT", "REF-01-FONT", "END-01-FONT" -> "cyan";
      case "ZH-01-SIZE", "EN-01-SIZE", "TOC-01-SIZE", "REF-01-SIZE", "END-01-SIZE", "EN-01-BOLD" -> "yellow";
      case "ZH-01-ALIGNMENT", "EN-01-ALIGNMENT", "TOC-01-ALIGNMENT", "REF-01-ALIGNMENT", "REF-01-SPACING", "END-01-ALIGNMENT" -> "magenta";
      case "APP-02-FONT" -> "cyan";
      case "APP-02-SIZE" -> "yellow";
      case "APP-02-ALIGNMENT" -> "magenta";
      default -> "lightGray";
    };
    if (sourceElement == null) return;
    if ("r".equals(sourceElement.getLocalName())) {
      // 运行级结果只标记产生结果的运行，包括公式编号所在的 m:r。
      setRunHighlight(sourceElement, color);
      return;
    } else if ("p".equals(sourceElement.getLocalName()) && isTextRangeRule(ruleId)
        && result.source().end() - result.source().start() <= 80) {
      highlightParagraphRange(paragraph, result, color);
    }
  }

  private static boolean isTextRangeRule(String ruleId) {
    return shortTextRule(ruleId) && !ruleId.endsWith("-INDENT") && !ruleId.endsWith("-LINE")
        && !ruleId.endsWith("-ALIGNMENT") && !ruleId.endsWith("-BLANK")
        && !"BODY-SCOPE".equals(ruleId) && !"APP-03".equals(ruleId);
  }

  private static void highlightParagraphRange(Element paragraph, CheckResult result, String color) {
    int rangeStart = result.source().start();
    int rangeEnd = result.source().end();
    if (rangeStart == rangeEnd) return;

    List<Element> paragraphRuns = visibleRuns(paragraph);
    List<TextRunRange> matches = new ArrayList<>();
    int offset = 0;
    int coveredUntil = rangeStart;
    for (Element run : paragraphRuns) {
      String runText = visibleRunText(run);
      int end = offset + runText.length();
      int overlapStart = Math.max(offset, rangeStart);
      int overlapEnd = Math.min(end, rangeEnd);
      if (overlapStart < overlapEnd) {
        if (overlapStart > coveredUntil || !canSplitVisibleRun(run)) return;
        matches.add(new TextRunRange(run, overlapStart - offset, overlapEnd - offset));
        coveredUntil = overlapEnd;
      }
      offset = end;
    }
    // Complex runs (fields, equations, and drawings) cannot be split without changing meaning.
    // Keep the comment, but do not widen a precise text finding to unrelated text.
    if (matches.isEmpty() || coveredUntil < rangeEnd) return;
    for (TextRunRange match : matches) splitAndHighlight(match, color);
  }

  private static boolean canSplitVisibleRun(Element run) {
    for (Node node = run.getFirstChild(); node != null; node = node.getNextSibling()) {
      if (!(node instanceof Element child)) continue;
      if (!run.getNamespaceURI().equals(child.getNamespaceURI())) return false;
      if (switch (child.getLocalName()) {
        case "rPr", "t", "tab", "cr", "br" -> false;
        default -> true;
      }) return false;
    }
    return true;
  }

  private static void splitAndHighlight(TextRunRange match, String color) {
    Element run = match.run();
    List<Element> segments = new ArrayList<>();
    int offset = 0;
    for (Node node = run.getFirstChild(); node != null; node = node.getNextSibling()) {
      if (!(node instanceof Element child) || "rPr".equals(child.getLocalName())) continue;
      switch (child.getLocalName()) {
        case "t" -> offset = appendTextSegments(run, child, match, color, offset, segments);
        case "tab", "cr" -> offset = appendSingleCharacterSegment(run, child, match, color, offset, segments);
        case "br" -> {
          String type = child.getAttributeNS(child.getNamespaceURI(), "type");
          if ("page".equals(type) || "column".equals(type))
            segments.add(runSegment(run, child, null, null));
          else offset = appendSingleCharacterSegment(run, child, match, color, offset, segments);
        }
        default -> { return; }
      }
    }
    Node parent = run.getParentNode();
    for (Element segment : segments) parent.insertBefore(segment, run);
    parent.removeChild(run);
  }

  private static int appendTextSegments(Element source, Element textElement, TextRunRange match,
      String color, int offset, List<Element> segments) {
    String text = textElement.getTextContent();
    int end = offset + text.length();
    int highlightedStart = Math.max(offset, match.start());
    int highlightedEnd = Math.min(end, match.end());
    if (highlightedStart >= highlightedEnd) {
      segments.add(runSegment(source, textElement, text, null));
    } else {
      if (offset < highlightedStart)
        segments.add(runSegment(source, textElement, text.substring(0, highlightedStart - offset), null));
      segments.add(runSegment(source, textElement,
          text.substring(highlightedStart - offset, highlightedEnd - offset), color));
      if (highlightedEnd < end)
        segments.add(runSegment(source, textElement, text.substring(highlightedEnd - offset), null));
    }
    return end;
  }

  private static int appendSingleCharacterSegment(Element source, Element content,
      TextRunRange match, String color, int offset, List<Element> segments) {
    boolean highlighted = offset < match.end() && offset + 1 > match.start();
    segments.add(runSegment(source, content, null, highlighted ? color : null));
    return offset + 1;
  }

  private static Element runSegment(Element source, Element content, String text, String highlight) {
    Document document = source.getOwnerDocument();
    Element segment = document.createElementNS(source.getNamespaceURI(), source.getNodeName());
    var attributes = source.getAttributes();
    for (int index = 0; index < attributes.getLength(); index++)
      segment.setAttributeNodeNS((org.w3c.dom.Attr) attributes.item(index).cloneNode(true));
    for (Node node = source.getFirstChild(); node != null; node = node.getNextSibling()) {
      if (node instanceof Element child && "rPr".equals(child.getLocalName())) {
        segment.appendChild(child.cloneNode(true));
        break;
      }
    }
    Element copiedContent = (Element) content.cloneNode(true);
    if (text != null) {
      copiedContent.setTextContent(text);
      if (startsOrEndsWithWhitespace(text))
        copiedContent.setAttributeNS(XMLConstants.XML_NS_URI, "xml:space", "preserve");
    }
    segment.appendChild(copiedContent);
    if (highlight != null) setRunHighlight(segment, highlight);
    return segment;
  }

  private static boolean startsOrEndsWithWhitespace(String text) {
    return !text.isEmpty() && (Character.isWhitespace(text.codePointAt(0))
        || Character.isSpaceChar(text.codePointAt(0))
        || Character.isWhitespace(text.codePointBefore(text.length()))
        || Character.isSpaceChar(text.codePointBefore(text.length())));
  }

  private static void setRunHighlight(Element run, String color) {
    Document document = run.getOwnerDocument();
    String wordNamespace = document.getDocumentElement().getNamespaceURI();
    Element properties = null;
    Node insertionPoint = run.getFirstChild();
    for (Node node = run.getFirstChild(); node != null; node = node.getNextSibling()) {
      if (node instanceof Element child && "rPr".equals(child.getLocalName())) {
        if (wordNamespace.equals(child.getNamespaceURI())) { properties = child; break; }
        // OMML uses m:rPr for math properties, followed by w:rPr for text formatting.
        if (run.getNamespaceURI().equals(child.getNamespaceURI())) insertionPoint = child.getNextSibling();
      }
    }
    if (properties == null) {
      properties = document.createElementNS(wordNamespace, "w:rPr");
      run.insertBefore(properties, insertionPoint);
    }
    var existingHighlights = properties.getElementsByTagNameNS(wordNamespace, "highlight");
    while (existingHighlights.getLength() > 0) properties.removeChild(existingHighlights.item(0));
    Element mark = document.createElementNS(wordNamespace, "w:highlight");
    mark.setAttributeNS(wordNamespace, "w:val", color);
    properties.appendChild(mark);
  }

  private record TextRange(int start, int end) { }

  private record TextRunRange(Element run, int start, int end) { }

  private static boolean shortTextRule(String ruleId) {
    return ruleId.startsWith("HEADING-") || ruleId.startsWith("HEAD-")
        || ruleId.startsWith("END-") || ruleId.startsWith("CAPTION-")
        || ruleId.startsWith("COVER-") || ruleId.startsWith("APP-02-")
        || ruleId.startsWith("APP-01") || ruleId.startsWith("FIG-") || ruleId.startsWith("TAB-")
        || ruleId.startsWith("EQ-") || ruleId.startsWith("REF-") || ruleId.startsWith("CITE-")
        || ruleId.startsWith("PAGE-") || ruleId.startsWith("ABSTRACT-")
        || ruleId.startsWith("TOC-") || ruleId.startsWith("ZH-") || ruleId.startsWith("EN-")
        || (ruleId.startsWith("BODY-") && !"BODY-INDENT".equals(ruleId));
  }

  private static Element nearestParagraph(Element element) {
    Element current = parentElement(element);
    while (current != null && !"p".equals(current.getLocalName())) current = parentElement(current);
    return current;
  }

  private static String visibleParagraphText(Element paragraph) {
    StringBuilder text = new StringBuilder();
    for (Element run : visibleRuns(paragraph)) text.append(visibleRunText(run));
    return text.toString();
  }

  private static String visibleRunText(Element run) {
    String namespace = run.getNamespaceURI();
    StringBuilder text = new StringBuilder();
    for (Node node = run.getFirstChild(); node != null; node = node.getNextSibling()) {
      if (!(node instanceof Element child) || !namespace.equals(child.getNamespaceURI())) continue;
      switch (child.getLocalName()) {
        case "t" -> text.append(child.getTextContent());
        case "tab" -> text.append('\t');
        case "cr" -> text.append('\n');
        case "br" -> {
          String type = child.getAttributeNS(namespace, "type");
          if (!"page".equals(type) && !"column".equals(type)) text.append('\n');
        }
        default -> { }
      }
    }
    return text.toString();
  }

  private static Element comment(Document comments, int id, String paraId, List<CheckResult> results,
      Element anchorParagraph, Map<CheckResult, PageStartAnchorResolver.Placement> placements) {
    String text = results.stream().map(result -> placementNote(result, placements.get(result))
            + suggestion(result, anchorParagraph)).distinct()
        .collect(java.util.stream.Collectors.joining("\n"));
    return commentWithText(comments, id, paraId, COMMENT_AUTHOR, text);
  }

  private static String placementNote(CheckResult result, PageStartAnchorResolver.Placement placement) {
    if (placement == null) return "";
    if (result.ruleId().startsWith("HEADER-")) {
      return "PLACED_AT_PAGE_START".equals(placement.reason())
          ? "页眉问题，批注放在本节适用页面的页首；共用此页眉的页面也需一并修改。"
          : "此批注对应本节的空白页眉。";
    }
    return "PLACED_AT_PAGE_START".equals(placement.reason())
        ? "问题原位置没有可圈定的文字，批注放在本页页首。"
        : "本批注对应此处的空白位置；未改变原有文字或分页。";
  }

  private static Element commentWithText(Document comments, int id, String paraId, String author, String text) {
    String wordNamespace = comments.getDocumentElement().getNamespaceURI();
    Element c = comments.createElementNS(wordNamespace, "w:comment");
    c.setAttributeNS(wordNamespace, "w:id", String.valueOf(id));
    c.setAttributeNS(wordNamespace, "w:author", author);
    c.setAttributeNS(wordNamespace, "w:date", Instant.now().toString());
    c.setAttributeNS(W15, "w15:paraId", paraId);
    Element p = comments.createElementNS(wordNamespace, "w:p");
    p.setAttributeNS(W14, "w14:paraId", paraId);
    Element r = comments.createElementNS(wordNamespace, "w:r");
    Element t = comments.createElementNS(wordNamespace, "w:t");
    t.setTextContent(text);
    r.appendChild(t); p.appendChild(r); c.appendChild(p);
    return c;
  }

  private static String commentParaId(Document comments, String commentId) {
    var nodes = comments.getDocumentElement().getElementsByTagNameNS("*", "comment");
    for (int index = 0; index < nodes.getLength(); index++) {
      Element comment = (Element) nodes.item(index);
      if (!commentId.equals(comment.getAttributeNS(comment.getNamespaceURI(), "id"))) continue;
      String paraId = attributeByLocalName(comment, "paraId");
      if (paraId != null && !paraId.isBlank()) return paraId;
      var paragraphs = comment.getElementsByTagNameNS("*", "p");
      for (int p = 0; p < paragraphs.getLength(); p++) {
        paraId = attributeByLocalName((Element) paragraphs.item(p), "paraId");
        if (paraId != null && !paraId.isBlank()) return paraId;
      }
      return null;
    }
    return null;
  }

  private static String attributeByLocalName(Element element, String localName) {
    var attributes = element.getAttributes();
    for (int index = 0; index < attributes.getLength(); index++) {
      Node attribute = attributes.item(index);
      if (localName.equals(attribute.getLocalName()) || localName.equals(attribute.getNodeName())) {
        return attribute.getNodeValue();
      }
    }
    return null;
  }

  private static String nextParaId(Set<String> used) {
    long candidate = System.nanoTime() & 0xffffffffL;
    String value = String.format(java.util.Locale.ROOT, "%08X", candidate);
    while (used.contains(value)) value = String.format(java.util.Locale.ROOT, "%08X", (candidate = (candidate + 1) & 0xffffffffL));
    used.add(value);
    return value;
  }

  static void collectParaIds(Map<String, byte[]> parts, Set<String> target) throws IOException {
    for (Map.Entry<String, byte[]> part : parts.entrySet()) {
      if (!part.getKey().endsWith(".xml") || part.getValue() == null || part.getValue().length == 0) continue;
      Document document;
      try {
        document = parse(part.getValue());
      } catch (IOException ignored) {
        // Relationship and custom XML parts can be valid package content without Word
        // paragraph IDs. They do not participate in the paraId namespace.
        continue;
      }
      var elements = document.getElementsByTagNameNS("*", "*");
      for (int index = 0; index < elements.getLength(); index++) {
        Node node = elements.item(index);
        if (!(node instanceof Element element)) continue;
        var attributes = element.getAttributes();
        for (int attrIndex = 0; attrIndex < attributes.getLength(); attrIndex++) {
          Node attribute = attributes.item(attrIndex);
          if ("paraId".equals(attribute.getLocalName()) || attribute.getNodeName().endsWith(":paraId")) {
            String value = attribute.getNodeValue();
            if (value != null && !value.isBlank()) target.add(value.toUpperCase(java.util.Locale.ROOT));
          }
        }
      }
    }
  }

  /** Reads the standard and modern comment parts and resolves threaded parent IDs. */
  public static List<CommentThread> readThreads(Path source) throws IOException {
    Map<String, byte[]> parts = readParts(source);
    byte[] commentsBytes = parts.get("word/comments.xml");
    if (commentsBytes == null || commentsBytes.length == 0) return List.of();

    Map<String, String> parentByPara = new LinkedHashMap<>();
    byte[] extendedBytes = parts.get("word/commentsExtended.xml");
    if (extendedBytes != null && extendedBytes.length > 0) {
      Document extended = parse(extendedBytes);
      var nodes = extended.getDocumentElement().getElementsByTagNameNS(W15, "commentEx");
      for (int index = 0; index < nodes.getLength(); index++) {
        Element entry = (Element) nodes.item(index);
        String paraId = attributeByLocalName(entry, "paraId");
        String parent = attributeByLocalName(entry, "paraIdParent");
        if (paraId != null && !paraId.isBlank() && parent != null && !parent.isBlank()) {
          parentByPara.put(normalizeParaId(paraId), normalizeParaId(parent));
        }
      }
    }

    Map<String, String> durableByPara = new LinkedHashMap<>();
    byte[] idsBytes = parts.get("word/commentsIds.xml");
    if (idsBytes != null && idsBytes.length > 0) {
      Document ids = parse(idsBytes);
      var nodes = ids.getDocumentElement().getElementsByTagNameNS(W16CID, "commentId");
      for (int index = 0; index < nodes.getLength(); index++) {
        Element entry = (Element) nodes.item(index);
        String paraId = attributeByLocalName(entry, "paraId");
        String durableId = attributeByLocalName(entry, "durableId");
        if (paraId != null && !paraId.isBlank() && durableId != null && !durableId.isBlank()) {
          durableByPara.put(normalizeParaId(paraId), durableId);
        }
      }
    }

    Document comments = parse(commentsBytes);
    Map<String, String> commentIdByPara = new LinkedHashMap<>();
    List<Element> commentNodes = new ArrayList<>();
    var nodes = comments.getDocumentElement().getElementsByTagNameNS("*", "comment");
    for (int index = 0; index < nodes.getLength(); index++) {
      Element comment = (Element) nodes.item(index);
      String id = attributeByLocalName(comment, "id");
      String paraId = attributeByLocalName(comment, "paraId");
      if ((paraId == null || paraId.isBlank())) {
        var paragraphs = comment.getElementsByTagNameNS("*", "p");
        for (int p = 0; p < paragraphs.getLength(); p++) {
          paraId = attributeByLocalName((Element) paragraphs.item(p), "paraId");
          if (paraId != null && !paraId.isBlank()) break;
        }
      }
      if (id == null || id.isBlank()) continue;
      commentNodes.add(comment);
      if (paraId != null && !paraId.isBlank()) commentIdByPara.put(normalizeParaId(paraId), id);
    }

    List<CommentThread> threads = new ArrayList<>();
    for (Element comment : commentNodes) {
      String id = attributeByLocalName(comment, "id");
      String paraId = attributeByLocalName(comment, "paraId");
      if (paraId == null || paraId.isBlank()) {
        var paragraphs = comment.getElementsByTagNameNS("*", "p");
        for (int p = 0; p < paragraphs.getLength(); p++) {
          paraId = attributeByLocalName((Element) paragraphs.item(p), "paraId");
          if (paraId != null && !paraId.isBlank()) break;
        }
      }
      String parentParaId = paraId == null ? null : parentByPara.get(normalizeParaId(paraId));
      String parentCommentId = parentParaId == null ? null : commentIdByPara.get(normalizeParaId(parentParaId));
      String author = attributeByLocalName(comment, "author");
      String text = comment.getTextContent() == null ? "" : comment.getTextContent().strip();
      threads.add(new CommentThread(id, paraId, paraId == null ? null : durableByPara.get(normalizeParaId(paraId)),
          parentCommentId, author, text));
    }
    return List.copyOf(threads);
  }

  private static String normalizeParaId(String paraId) {
    return paraId.toUpperCase(java.util.Locale.ROOT);
  }

  private static Document existingModernPart(byte[] bytes, String rootName) throws IOException {
    if (bytes != null && bytes.length > 0) {
      Document document = parse(bytes);
      Element root = document.getDocumentElement();
      if (!W15.equals(root.getNamespaceURI()) || !rootName.equals(root.getLocalName()))
        throw new IOException("invalid " + rootName + " part root");
      return document;
    }
    Document document = newDocument();
    String qualified = "people".equals(rootName) ? "w15:people" : "w15:commentsEx";
    document.appendChild(document.createElementNS(W15, qualified));
    return document;
  }

  private static Document existingCommentIdsPart(byte[] bytes) throws IOException {
    if (bytes != null && bytes.length > 0) {
      Document document = parse(bytes);
      Element root = document.getDocumentElement();
      if (!W16CID.equals(root.getNamespaceURI()) || !"commentsIds".equals(root.getLocalName()))
        throw new IOException("invalid commentsIds part root");
      return document;
    }
    Document document = newDocument();
    document.appendChild(document.createElementNS(W16CID, "w16cid:commentsIds"));
    return document;
  }

  private static Set<String> collectDurableIds(Document commentsIds) {
    Set<String> ids = new HashSet<>();
    var nodes = commentsIds.getDocumentElement().getElementsByTagNameNS(W16CID, "commentId");
    for (int index = 0; index < nodes.getLength(); index++) {
      String value = ((Element) nodes.item(index)).getAttributeNS(W16CID, "durableId");
      if (value != null && !value.isBlank()) ids.add(value.toUpperCase(java.util.Locale.ROOT));
    }
    return ids;
  }

  private static String nextDurableId(String paraId, int commentId, Set<String> used) {
    String seed = paraId + ":" + commentId;
    int attempt = 0;
    while (true) {
      String candidate = durableId(seed + ":" + attempt);
      if (used.add(candidate)) return candidate;
      attempt++;
    }
  }

  /** Word's durableId is serialized as an eight-character hexadecimal 32-bit value. */
  private static String durableId(String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder result = new StringBuilder(8);
      for (int index = 0; index < 4; index++) result.append(String.format("%02X", digest[index] & 0xFF));
      return result.toString();
    } catch (java.security.NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is not available", error);
    }
  }

  private static void appendCommentId(Document commentsIds, String paraId, String durableId) {
    Element entry = commentsIds.createElementNS(W16CID, "w16cid:commentId");
    entry.setAttributeNS(W16CID, "w16cid:paraId", paraId);
    entry.setAttributeNS(W16CID, "w16cid:durableId", durableId);
    commentsIds.getDocumentElement().appendChild(entry);
  }

  private static void ensurePerson(Document people, String author) {
    Element root = people.getDocumentElement();
    var persons = root.getElementsByTagNameNS(W15, "person");
    for (int index = 0; index < persons.getLength(); index++) {
      if (author.equals(((Element) persons.item(index)).getAttributeNS(W15, "author"))) return;
    }
    Element person = people.createElementNS(W15, "w15:person");
    person.setAttributeNS(W15, "w15:author", author);
    root.appendChild(person);
  }

  private static void appendCommentExtended(Document commentsExtended, String paraId) {
    appendCommentExtended(commentsExtended, paraId, null);
  }

  private static void appendCommentExtended(Document commentsExtended, String paraId, String parentParaId) {
    Element entry = commentsExtended.createElementNS(W15, "w15:commentEx");
    entry.setAttributeNS(W15, "w15:paraId", paraId);
    if (parentParaId != null && !parentParaId.isBlank()) entry.setAttributeNS(W15, "w15:paraIdParent", parentParaId);
    entry.setAttributeNS(W15, "w15:done", "0");
    commentsExtended.getDocumentElement().appendChild(entry);
  }

  private static String citationSuggestion(CheckResult result) {
    return switch (result.reasonCode()) {
      case "CITATION_FORMAT_UNKNOWN" -> "这处引用的字体或字号属性未完整读取，请设为小四号 Times New Roman，并设为上标。";
      default -> "引用编号请设为小四号 Times New Roman，并设置为上标。";
    };
  }

  private static String suggestion(CheckResult result, Element anchorParagraph) {
    if ("COVER_SCHOOL_NAME_IMAGE_UNCONFIRMED".equals(result.reasonCode()))
      return "封面图片中可能已包含学校名称；系统无法读取图片文字，暂不判为缺失。";
    if ("CITE-01".equals(result.ruleId())) return citationSuggestion(result);
    if ("REF-07".equals(result.ruleId())
        && "REFERENCE_NUMBER_SPACING_MISMATCH".equals(result.reasonCode()))
      return referenceNumberSpacingSuggestion(result);
    if ("REF-07".equals(result.ruleId())
        && "REFERENCE_NUMBER_SPACING_UNCONFIRMED".equals(result.reasonCode()))
      return "自动编号后的分隔符无法从文档结构确认，请在 Word 中检查是否只保留一个普通半角空格。";
    return switch (result.ruleId()) {
      case "EN-03-BOLD" -> cn.luwneshacha.thesis.report.RuleDescriptions.suggestion(result);
      case "CITE-01-LEGACY" -> "CITATION_FORMAT_UNKNOWN".equals(result.reasonCode())
          ? "未读取到这处引用的完整字体或字号属性，按格式错误处理；改为小四号 Times New Roman 上标。"
          : "引用编号请设为小四号 Times New Roman，并设置为上标。";
      case "PAGE-01" -> marginComment(result);
      case "PAGE-05" -> "页码格式不符合要求；将页脚设为居中，字体改为五号 Times New Roman。";
      case "PAGE-04" -> switch (result.reasonCode()) {
        case "PAGE_NUMBER_IN_FRONT_MATTER" -> "封面、摘要和目录部分不要编写页码。";
        case "PAGE_NUMBER_START_INVALID" -> "页码起始值不是有效整数，请删除该值或改为有效的阿拉伯数字起始值。";
        case "PAGE_NUMBER_SEQUENCE_RESTARTED" -> "取消此处重新编号，保持正文到附录页码连续。";
        default -> "正文到附录的页码请使用阿拉伯数字。";
      };
      case "PAGE-03" -> "请在本章标题段落设置“段前分页”，让章节从新页开始。";
      case "HEADER-01-MISSING", "HEADER-01-TEXT", "HEADER-01-SCOPE" ->
          cn.luwneshacha.thesis.report.RuleDescriptions.suggestion(result);
      case "HEADER-01-FONT" -> "将页眉字体统一改为黑体。";
      case "HEADER-01-SIZE" -> "将页眉字号统一改为五号。";
      case "HEADER-01-ALIGNMENT" -> "将页眉段落设为居中。";
      case "HEAD-02-BLANK" -> "章节标题和正文之间空一行。";
      case "TOC-01-SPACING" -> "目录标题改为“目    录”，中间保留 4 个空格。";
      case "TOC-01-BLANK" -> "目录标题和目录内容之间空一行。";
      case "TOC-01-LINE" -> "目录标题：段落行距调整为 1.5 倍。";
      case "TOC-SPACING" -> cn.luwneshacha.thesis.report.RuleDescriptions.suggestion(result);
      case "TOC-02" -> switch (result.reasonCode()) {
        case "TOC_ENTRY_NOT_FOUND_IN_BODY" -> "请在正文中补上与该目录条目对应的标题，或删除目录中的多余条目。";
        case "BODY_HEADING_NOT_IN_TOC" -> "请在目录中补上该正文标题对应的条目，并更新页码。";
        default -> "请核对目录和正文标题，确保每一项都能对应。";
      };
      case "TOC-02-ORDER" -> "请按正文标题出现的顺序重新生成目录，并更新目录页码。";
      case "TOC-REQUIRED-ENTRY" -> "请在目录中补齐正文各章、参考文献、致谢和附录条目，并填写对应页码。";
      case "HEAD-01-SPACING", "HEAD-02-SPACING", "HEAD-03-SPACING" -> headingSpacingSuggestion(result);
      case "HEAD-03-INDENT" -> "三级标题：首行缩进调整为 2 字符。";
      case "HEAD-05-FONT" -> "三级以下标题：中文改为宋体，字母和数字改为 Times New Roman。";
      case "HEAD-05-MARKER" -> "三级以下标题编号应写成“（1）标题”，右括号后不要加顿号。";
      case "HEAD-05-SIZE" -> "三级以下标题：字号调整为小四号。";
      case "HEAD-05-LINE" -> "三级以下标题：段落行距调整为 1.5 倍。";
      case "HEAD-05-INDENT" -> result.actual() != null && result.actual().contains("存在额外的左缩进")
          ? "三级以下标题还设置了多余的左缩进、右缩进或悬挂缩进；请清除这些额外缩进，只保留首行缩进 2 字符。"
          : "三级以下标题：首行缩进调整为 2 字符。";
      case "BODY-FONT" -> "正文：中文改为宋体，字母和数字改为 Times New Roman。";
      case "BODY-SIZE" -> "正文：字号调整为小四号。";
      case "BODY-INDENT" -> cn.luwneshacha.thesis.report.RuleDescriptions.suggestion(result);
      case "BODY-LINE" -> "正文行距不是 1.5 倍，请调整段落行距。";
      case "BODY-UNIT" -> "同一计量单位全文统一写法，例如统一使用“cm”或统一使用“厘米”。";
      case "BODY-SCOPE" -> "请确认论文包含清晰的正文一级章节标题后重新检查，避免正文被识别为摘要或目录内容。";
      case "ABSTRACT-FONT" -> "摘要字体不符合要求；中文改为宋体，字母和数字改为 Times New Roman。";
      case "ABSTRACT-SIZE" -> "摘要字号不符合要求；改为小四号。";
      case "ABSTRACT-INDENT" -> cn.luwneshacha.thesis.report.RuleDescriptions.suggestion(result);
      case "ABSTRACT-KEYWORD-BOLD" -> "“关键词”标签需要加粗，后面的词不用加粗。";
      case "ABSTRACT-KEYWORD-FORMAT" -> "关键词格式不符合要求；改为 3 至 5 个，用中文冒号引出、中文分号分隔，末尾不加标点。";
      case "ABSTRACT-KEYWORD-ALIGN" -> "中文关键词段落应设为两端对齐。";
      case "REF-FONT" -> "参考文献：中文改为宋体，字母和数字改为 Times New Roman。";
      case "REF-SIZE" -> "参考文献：字号调整为小四号。";
      case "REF-LINE" -> "参考文献行距不是 1.5 倍，请调整段落行距。";
      case "REF-03" -> switch (result.reasonCode()) {
        case "REFERENCE_ORDER_MISMATCH" -> "请按正文第一次出现的先后重新排列文末参考文献。";
        case "REFERENCE_NOT_CITED" -> "这条参考文献未在正文中找到对应引用。";
        case "CITED_REFERENCE_MISSING" -> "正文引用编号在文末参考文献中没有对应条目。";
         case "CITATION_RANGE_REVIEW" -> "这组引用编号或范围不符合规范；改成连续编号，并为每个编号补上对应的文末条目。";
        default -> "请删除重复的参考文献编号，并按正文引用顺序重新排列。";
      };
      case "REF-05" -> "参考文献少于 10 篇，请补充有效文献。";
      case "APP-01" -> switch (result.reasonCode()) {
        case "APPENDIX_SEQUENCE_MISMATCH" -> "附录编号应从 A 开始并按字母连续排列。";
        case "APPENDIX_OBJECT_PREFIX_MISSING" -> "附录中的图、表或公式编号请补上本附录字母。";
        default -> "附录中的图、表或公式编号字母应与本附录一致。";
      };
      case "CAPTION-FONT" -> "图题和表题中文用宋体，字母和数字用 Times New Roman。";
      case "CAPTION-SIZE" -> "图表题字号不符合要求，请按规范调整为五号。";
      case "ABSTRACT-LINE" -> "摘要行距不是 1.5 倍，请调整段落行距。";
      case "ZH-01-SPACING" -> "摘要标题改为“摘  要”，中间保留 4 个空格。";
      case "ZH-02-BLANK", "EN-01-BLANK" -> "摘要标题下方空一行，再开始摘要正文。";
      case "ZH-04-BLANK", "EN-03-BLANK" -> "摘要正文和关键词之间空一行。";
      case "EN-03-FORMAT" -> "ENGLISH_KEYWORD_COUNT_MISMATCH".equals(result.reasonCode())
          ? "英文关键词应为 3 至 5 个，使用分号分隔。"
          : "英文关键词：使用 Keywords: 开头，关键词之间用英文分号（;）分隔。";
      case "EN-03-ALIGNMENT" -> "英文关键词换行时，将新行起始位置对齐到第一个关键词的首字母。";
      case "HEADING-FONT" -> headingFontSuggestion(result);
      case "HEADING-SIZE" -> headingSizeSuggestion(result);
      case "HEADING-LINE" -> cn.luwneshacha.thesis.report.RuleDescriptions.suggestion(result);
      case "COVER-01-FONT" -> "学校名称：改为方正小标宋简体。";
      case "COVER-01-SIZE" -> "学校名称：字号调整为小初号。";
      case "COVER-01-ALIGNMENT" -> "学校名称：设置为居中。";
      case "COVER-02-FONT" -> "论文题目：改为黑体。";
      case "COVER-02-SIZE" -> "论文题目：字号调整为小二号。";
      case "COVER-03-FONT", "COVER-03-SIZE", "COVER-03-BOLD" -> coverFieldSuggestion(result, anchorParagraph);
      case "COVER-04-FONT" -> "封面日期字体应为仿宋，请将日期内容改为仿宋。";
      case "COVER-04-SIZE" -> "封面日期字号应为三号，请将日期字号调整为三号。";
      case "COVER-04-BOLD" -> "封面日期没有加粗，请将日期内容设置为加粗。";
      case "COVER-04-CONSISTENCY" -> "封面上的日期不一致，请统一年月日。";
      case "ZH-01-FONT" -> "摘要标题字体不符合要求；改为三号黑体。";
      case "ZH-01-SIZE" -> "摘要标题字号不符合要求；改为三号。";
      case "ZH-01-ALIGNMENT" -> "摘要标题需要居中。";
      case "EN-01-FONT" -> "ABSTRACT 标题字体应为 Times New Roman。";
      case "EN-01-SIZE" -> "ABSTRACT 标题字号应为三号。";
      case "EN-01-BOLD" -> "ABSTRACT 标题需要加粗。";
      case "EN-01-ALIGNMENT" -> "ABSTRACT 标题需要居中。";
      case "TOC-01-FONT" -> "目录标题字体不符合要求；改为三号黑体。";
      case "TOC-01-SIZE" -> "目录标题字号不符合要求；改为三号。";
      case "TOC-01-ALIGNMENT" -> "目录标题需要居中。";
      case "REF-01-FONT" -> "参考文献标题字体不符合要求；改为三号黑体。";
      case "REF-01-SIZE" -> "参考文献标题字号不符合要求；改为三号。";
      case "REF-01-ALIGNMENT" -> "参考文献标题需要居中。";
      case "REF-01-SPACING" -> "参考文献标题写成“参考文献”，两个词之间不要插入空格。";
      case "END-01-FONT" -> "致谢标题字体改为黑体。";
      case "END-01-SIZE" -> "致谢标题字号调整为三号。";
      case "END-01-ALIGNMENT" -> "致谢标题需要居中。";
      case "END-01-SPACING" -> "致谢标题写成“致    谢”，两个字之间保留 4 个普通空格。";
      case "APP-02-SPACING" -> "附录标题按规范写为“附    录”，中间留 4 个空格。";
      case "APP-03" -> "请确认“附    录”总标题从新页开始；只有确认未另起一页时，再设置段前分页。";
      case "APP-02-FONT" -> "附录标题改为黑体。";
      case "APP-02-SIZE" -> "附录标题字号调整为三号。";
      case "APP-02-ALIGNMENT" -> "附录标题设为居中。";
      case "APP-02-BLANK" -> switch (result.reasonCode()) {
        case "APPENDIX_TITLE_BLANK_LINE_MISSING" -> "在“附    录”标题与附录内容之间保留一个空白段落。";
        case "APPENDIX_TITLE_BLANK_LINE_COUNT_MISMATCH" -> "“附    录”标题后只保留一个空白段落，再开始附录内容。";
        default -> "“附    录”标题下方空一行，再开始附录内容。";
      };
      case "REF-01-BLANK" -> "REFERENCE_TITLE_BLANK_LINE_COUNT_MISMATCH".equals(result.reasonCode())
          ? "“参考文献”标题与第一条文献之间只保留一个空白段落。"
          : "在“参考文献”标题与第一条文献之间保留一个空白段落。";
      case "END-01-BLANK" -> "ACKNOWLEDGEMENT_TITLE_BLANK_LINE_COUNT_MISMATCH".equals(result.reasonCode())
          ? "“致谢”标题与正文之间只保留一个空白段落。"
          : "在“致谢”标题与正文之间保留一个空白段落。";
      case "TAB-01" -> "正文里没有找到这张表的引用，请在相关位置补一句。";
      case "TAB-02" -> {
        if ("TABLE_AUXILIARY_BORDER_REVIEW".equals(result.reasonCode()))
          yield "三线表的辅助线不是必需项；必要时可以保留辅助线，重点保留顶线、表头下横线和底线。";
        if ("TABLE_XML_NUMERIC_INVALID".equals(result.reasonCode()))
          yield "表格的宽度、高度、合并或边框数值无效。请在 Word 中重新设置相关表格属性后再检查。";
        String actual = result.actual();
        if (actual != null && !actual.isBlank())
          yield "表格边框检查：" + actual + "。请保留顶线、表头下横线和底线，只有必要时保留辅助竖线。";
        yield "表格一般采用三线表，请保留顶线、表头下横线和底线；只有必要时保留辅助竖线。";
      }
      case "TAB-03" -> switch (result.reasonCode()) {
        case "TABLE_CAPTION_MISSING" -> "表格缺少表题，请补充表号和题名。";
        case "TABLE_CAPTION_BELOW" -> "表题在表格下方，请移到表格上方。";
        case "TABLE_CAPTION_NUMBER_FORMAT" -> tableCaptionNumberSuggestion(result);
        case "TABLE_CONTINUATION_CAPTION_NOT_RIGHT" -> "续表标记应右对齐，请将“续表”段落设为右对齐。";
        case "TABLE_CONTINUATION_CAPTION_ALIGNMENT_UNKNOWN" -> "无法确认续表标记的对齐方式，请将“续表”段落设为右对齐。";
        case "TABLE_CAPTION_NOT_CENTERED", "TABLE_CAPTION_ALIGNMENT_UNKNOWN" -> "表题应居中显示，请将表题段落设置为居中。";
        default -> "请把表题移到表格上方，并与表格保持紧邻。";
      };
      case "TAB-05" -> "本章表号不连续；从 1 开始按出现顺序连续编号。";
      case "TAB-04" -> switch (result.reasonCode()) {
        case "TABLE_UNIT_DIMENSION_UNKNOWN" -> "请将表头写成明确的量名称，并标注对应单位，例如“长度/mm”或“温度/℃”。";
        case "TABLE_UNITS_MISSING" -> "“" + result.actual() + "”列的单位标注不完整或与参数不符。请核对表头及对应数值的单位；单位不同时分别标注，不用统一写在表顶。";
        default -> "请为缺少单位的数值补充对应单位；不同单位分别标注。";
      };
      case "TAB-06" -> "表格中不要用“同前”“同左”代替内容，请重复写出完整文字。";
      case "TAB-07" -> "表注应放在表格下方并左对齐，请调整这一段。";
      case "TAB-08" -> "续表的表头和原表不一致，请把原表表头完整重复到续表中。";
      case "FIG-01" -> "FIGURE_REFERENCE_NEEDS_REVIEW".equals(result.reasonCode())
          ? "正文图引用不完整；写出明确图号，例如“如图 2-1 所示”，不要只写“如图所示”。"
          : "正文里没有找到这幅图的引用，请在相关位置补一句。";
      case "FIG-03" -> figureCaptionSpacingSuggestion(result);
      case "FIG-05" -> "本章图片编号不连续；从 1 开始按出现顺序连续编号。";
      case "FIG-02" -> "FIGURE_CAPTION_MISSING".equals(result.reasonCode())
          ? "图片下方缺少图号和图题，请补充，例如“图3-1 系统结构图”。"
          : "请将图题居中并放在图片下方。";
      case "FIG-04" -> "IMAGE_XML_NUMERIC_INVALID".equals(result.reasonCode())
          ? "图片宽度或高度的 XML 值无效，请重新插入图片并设置正整数尺寸。"
          : "图片尺寸无效，请重新插入图片并设置有效尺寸。";
      case "EQ-01" -> "EQUATION_NUMBER_PARENTHESES_MISSING".equals(result.reasonCode())
          ? "公式编号请加圆括号，例如“(3-1)”。"
          : "公式必须编号，并按一级标题编排，例如第三章第 1 个公式写作（3-1）。";
      case "EQ-02" -> "公式编号的字体或字号不符合要求，请改为五号 Times New Roman。";
      case "EQ-03" -> "公式整体居中，公式编号右对齐，并与公式保持同一行。";
      case "EQ-04" -> "正文里没有找到这条公式的引用，请在相关位置补充说明。";
      case "EQ-05" -> "EQUATION_NUMBER_SEQUENCE_MISMATCH".equals(result.reasonCode())
          ? "公式编号应按本章连续排列，例如 3-1、3-2、3-3。"
          : "公式编号重复，请检查编号顺序。";
      case "EQ-06" -> "此公式以 MathType、OLE 或图片形式保存，文档 XML 读不到内部字体、变量斜体、上下标和内部结构，因此这部分无法确认。若需要系统检查这些内容，请转换为 Word 原生公式。";
      case "EQ-07" -> "未检测到公式变量的逐项说明，按缺少变量说明处理；在公式下方逐项补充变量含义和单位。";
      case "REF-02" -> "参考文献缺少类型标识，请补充 [M]、[J] 等类别。";
      case "REF-07" -> "参考文献编号应从 1 开始连续排列，请检查跳号或编号格式。";
      case "REF-04" -> referenceFieldsSuggestion(result);
      default -> cn.luwneshacha.thesis.report.RuleDescriptions.suggestion(result);
    };
  }

  private static String coverFieldSuggestion(CheckResult result, Element paragraph) {
    String field = coverFieldName(paragraph);
    String subject = field == null ? "封面基本信息" : field;
    return switch (result.ruleId()) {
      case "COVER-03-FONT" -> subject + "内容字体应为仿宋，请将该字段内容改为仿宋。";
      case "COVER-03-SIZE" -> subject + "字号应为三号，请将该字段字号调整为三号。";
      default -> subject + "内容没有加粗，请选中该字段内容并设置为加粗。";
    };
  }

  private static String coverFieldName(Element paragraph) {
    if (paragraph == null) return null;
    Matcher field = COVER_FIELD_LABEL.matcher(paragraph.getTextContent());
    if (!field.find()) return null;
    return field.group(1).replaceAll("\\s+", "");
  }

  private static String referenceFieldsSuggestion(CheckResult result) {
    String actual = result.actual();
    String marker = "；缺少";
    int missingFieldsStart = actual == null ? -1 : actual.lastIndexOf(marker);
    if (missingFieldsStart < 0) return "这条参考文献字段不完整，请按文献类型补齐实际缺少的字段。";
    String missingFields = actual.substring(missingFieldsStart + marker.length()).strip();
    if (missingFields.isEmpty()) return "这条参考文献字段不完整，请按文献类型补齐实际缺少的字段。";
    return "这条参考文献缺少" + missingFields + "，请补齐。";
  }

  private static String referenceNumberSpacingSuggestion(CheckResult result) {
    String actual = result.actual() == null ? "" : result.actual();
    if (actual.contains("没有空格"))
      return "参考文献编号后缺少空格，请在编号后补一个普通半角空格，再接文献内容。";

    Matcher repeatedSpaces = Pattern.compile("连续 (\\d+) 个普通半角空格").matcher(actual);
    if (repeatedSpaces.find())
      return "参考文献编号后有 " + repeatedSpaces.group(1)
          + " 个普通半角空格，请删除多余空格，只保留一个普通半角空格，再接文献内容。";

    Matcher automaticSeparator = Pattern.compile("suffix=(\\d+), formatTrailing=(\\d+)").matcher(actual);
    if (automaticSeparator.matches()) {
      if ("0".equals(automaticSeparator.group(1)))
        return "自动编号的“编号之后”应设为“空格”；不要使用制表符或不留间隔。";
      if (!"0".equals(automaticSeparator.group(2)))
        return "自动编号格式末尾还带有空格；请删除编号格式里的尾随空格，只保留“编号之后”设置的一个空格。";
    }

    return "参考文献编号后的空白数量或形式不符合要求，请删除原空白，只保留一个普通半角空格，再接文献内容。";
  }

  private static String marginComment(CheckResult result) {
    if ("MARGIN_GUTTER_MISMATCH".equals(result.reasonCode()))
      return "当前分节设置了 " + result.actual() + " twips 装订线；将装订线设为 0，四边边距保持 2.6 cm。";
    if ("MARGIN_GUTTER_UNKNOWN".equals(result.reasonCode()))
      return "未能确认当前分节的装订线设置；将页面装订线设为 0 后重新检查。";
    if ("MARGIN_MISMATCH".equals(result.reasonCode()) && result.actual() != null) {
      try {
        String centimeters = new java.math.BigDecimal(result.actual())
            .multiply(new java.math.BigDecimal("2.54"))
            .divide(new java.math.BigDecimal("1440"), 5, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros().toPlainString();
        return "当前分节页边距为 " + centimeters + " cm（XML：" + result.actual()
            + " twips），请将该分节四边调整为 2.6 cm。";
      } catch (NumberFormatException ignored) {
        // Fall through to the generic message when XML value is unavailable.
      }
    }
    return "当前分节页边距不符合要求，请将该分节四边调整为 2.6 cm。";
  }

  private static String headingSizeSuggestion(CheckResult result) {
    String expected = result.expected();
    return switch (expected) {
      case "32" -> "一级标题：字号调整为三号（16 磅）。";
      case "28" -> "二级标题：字号调整为四号（14 磅）。";
      case "24" -> "三级标题：字号调整为小四号（12 磅）。";
      default -> "本级标题：按标题层级调整字号。";
    };
  }

  private static String headingFontSuggestion(CheckResult result) {
    String expected = result.expected();
    if (expected != null) {
      if (expected.startsWith("一级标题")) return "一级标题：中文改为黑体，字母和数字改为 Times New Roman。";
      if (expected.startsWith("二级标题")) return "二级标题：中文改为黑体，字母和数字改为 Times New Roman。";
      if (expected.startsWith("三级标题")) return "三级标题：中文改为黑体，字母和数字改为 Times New Roman。";
    }
    return "按标题层级设置中文黑体、字母和数字 Times New Roman。";
  }

  private static byte[] addRelationship(byte[] bytes, boolean strict) throws IOException {
    Document doc;
    if (bytes == null) {
      doc = newDocument();
      doc.appendChild(doc.createElementNS(REL, "Relationships"));
    } else {
      doc = parse(bytes);
    }
    Element root = doc.getDocumentElement();
    var existing = root.getElementsByTagNameNS(REL, "Relationship");
    for (int i = 0; i < existing.getLength(); i++) {
      String type = ((Element) existing.item(i)).getAttribute("Type");
      if (COMMENTS_TYPE.equals(type) || STRICT_COMMENTS_TYPE.equals(type)) return serialize(doc);
    }
    int max = 0;
    var nodes = existing;
    for (int i = 0; i < nodes.getLength(); i++) {
      String id = ((Element) nodes.item(i)).getAttribute("Id");
      if (id.startsWith("rId")) try { max = Math.max(max, Integer.parseInt(id.substring(3))); } catch (NumberFormatException ignored) { }
    }
    Element relationship = doc.createElementNS(REL, "Relationship");
    relationship.setAttribute("Id", "rId" + (max + 1));
    relationship.setAttribute("Type", strict ? STRICT_COMMENTS_TYPE : COMMENTS_TYPE);
    relationship.setAttribute("Target", "comments.xml");
    root.appendChild(relationship);
    return serialize(doc);
  }

  private static byte[] addModernRelationships(byte[] bytes) throws IOException {
    Document doc = bytes == null ? newDocument() : parse(bytes);
    if (bytes == null) doc.appendChild(doc.createElementNS(REL, "Relationships"));
    Element root = doc.getDocumentElement();
    boolean hasExtended = false;
    boolean hasPeople = false;
    boolean hasCommentIds = false;
    int max = 0;
    var existing = root.getElementsByTagNameNS(REL, "Relationship");
    for (int index = 0; index < existing.getLength(); index++) {
      Element relation = (Element) existing.item(index);
      String type = relation.getAttribute("Type");
      hasExtended |= COMMENTS_EXTENDED_TYPE.equals(type);
      hasPeople |= PEOPLE_TYPE.equals(type);
      hasCommentIds |= COMMENTS_IDS_TYPE.equals(type);
      String id = relation.getAttribute("Id");
      if (id.startsWith("rId")) {
        try { max = Math.max(max, Integer.parseInt(id.substring(3))); }
        catch (NumberFormatException ignored) { }
      }
    }
    if (!hasExtended) {
      Element relation = doc.createElementNS(REL, "Relationship");
      relation.setAttribute("Id", "rId" + (++max));
      relation.setAttribute("Type", COMMENTS_EXTENDED_TYPE);
      relation.setAttribute("Target", "commentsExtended.xml");
      root.appendChild(relation);
    }
    if (!hasPeople) {
      Element relation = doc.createElementNS(REL, "Relationship");
      relation.setAttribute("Id", "rId" + (++max));
      relation.setAttribute("Type", PEOPLE_TYPE);
      relation.setAttribute("Target", "people.xml");
      root.appendChild(relation);
    }
    if (!hasCommentIds) {
      Element relation = doc.createElementNS(REL, "Relationship");
      relation.setAttribute("Id", "rId" + (++max));
      relation.setAttribute("Type", COMMENTS_IDS_TYPE);
      relation.setAttribute("Target", "commentsIds.xml");
      root.appendChild(relation);
    }
    return serialize(doc);
  }

  private static byte[] addContentType(byte[] bytes) throws IOException {
    return addContentType(bytes, false);
  }

  private static byte[] addContentType(byte[] bytes, boolean modern) throws IOException {
    Document doc = parse(bytes);
    Element root = doc.getDocumentElement();
    var overrides = root.getElementsByTagNameNS(CT, "Override");
    boolean hasComments = false;
    for (int i = 0; i < overrides.getLength(); i++) {
      if ("/word/comments.xml".equals(((Element) overrides.item(i)).getAttribute("PartName"))) {
        hasComments = true;
        break;
      }
    }
    if (!hasComments) {
      Element override = doc.createElementNS(CT, "Override");
      override.setAttribute("PartName", "/word/comments.xml");
      override.setAttribute("ContentType", "application/vnd.openxmlformats-officedocument.wordprocessingml.comments+xml");
      root.appendChild(override);
    }
    if (modern) {
      addOverrideIfMissing(root, "/word/commentsExtended.xml", COMMENTS_EXTENDED_CONTENT_TYPE);
      addOverrideIfMissing(root, "/word/people.xml", PEOPLE_CONTENT_TYPE);
      addOverrideIfMissing(root, "/word/commentsIds.xml", COMMENTS_IDS_CONTENT_TYPE);
    }
    return serialize(doc);
  }

  private static void addOverrideIfMissing(Element root, String partName, String contentType) {
    var overrides = root.getElementsByTagNameNS(CT, "Override");
    for (int index = 0; index < overrides.getLength(); index++) {
      if (partName.equals(((Element) overrides.item(index)).getAttribute("PartName"))) return;
    }
    Document document = root.getOwnerDocument();
    Element override = document.createElementNS(CT, "Override");
    override.setAttribute("PartName", partName);
    override.setAttribute("ContentType", contentType);
    root.appendChild(override);
  }

  private static int nextCommentId(byte[] bytes) throws IOException {
    if (bytes == null) return 0;
    Document comments = parse(bytes);
    String wordNamespace = comments.getDocumentElement().getNamespaceURI();
    var nodes = comments.getDocumentElement().getElementsByTagNameNS(wordNamespace, "comment");
    int max = -1;
    for (int i = 0; i < nodes.getLength(); i++) max = Math.max(max, Integer.parseInt(((Element) nodes.item(i)).getAttributeNS(W, "id")));
    return max + 1;
  }

  private static Document newCommentsDocument(boolean strict) {
    Document doc = newDocument();
    Element root = doc.createElementNS(strict ? STRICT_W : W, "w:comments");
    doc.appendChild(root);
    return doc;
  }

  private static Document existingCommentsDocument(byte[] bytes, boolean strict) throws IOException {
    if (bytes == null || bytes.length == 0) return newCommentsDocument(strict);
    Document document = parse(bytes);
    Element root = document.getDocumentElement();
    String namespace = root.getNamespaceURI();
    if (!"comments".equals(root.getLocalName()) || (!W.equals(namespace) && !STRICT_W.equals(namespace)))
      throw new IOException("invalid comments part root");
    return document;
  }

  private static boolean isStrictDocument(Document document) {
    return STRICT_W.equals(document.getDocumentElement().getNamespaceURI());
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
    for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
      if (!(node instanceof Element child)) continue;
      String localName = child.getLocalName();
      if ("commentRangeStart".equals(localName) || "commentRangeEnd".equals(localName)) continue;
      children.add(child);
    }
    return children;
  }

  private static String figureCaptionSpacingSuggestion(CheckResult result) {
    String actual = result.actual();
    if (actual == null || actual.isBlank())
      return "图号和图题之间没有留空格。请在图号后输入 1 个普通空格，例如“图3-1 系统结构图”。";
    int leadingEnd = 0;
    while (leadingEnd < actual.length() && isCaptionWhitespace(actual.charAt(leadingEnd))) leadingEnd++;
    String leading = actual.substring(0, leadingEnd);
    String content = actual.substring(leadingEnd);
    String labelGapSuggestion = figureLabelGapSuggestion(leading, content);
    if (labelGapSuggestion != null) return labelGapSuggestion;
    Matcher numberMatcher = Pattern.compile("^(图(?:\\d+-\\d+|[A-Z]\\d+))").matcher(content);
    if (!numberMatcher.find())
      return "图题开头或图号格式有误，请删掉图号前多余的间隔，并按“图3-1 系统结构图”的格式书写。";

    String number = numberMatcher.group(1);
    int separatorEnd = number.length();
    while (separatorEnd < content.length() && isCaptionWhitespace(content.charAt(separatorEnd))) separatorEnd++;
    String separator = content.substring(number.length(), separatorEnd);
    String title = content.substring(separatorEnd);
    List<String> issues = new ArrayList<>();
    if (!leading.isEmpty())
      issues.add("图题开头有 " + captionWhitespaceDescription(leading));
    if (separator.isEmpty()) {
      issues.add("“" + number + "”和图题之间没有空格");
    } else if (!" ".equals(separator)) {
      issues.add("“" + number + "”和图题之间有 " + captionWhitespaceDescription(separator)
          + (separator.chars().allMatch(value -> value == ' ')
              ? "，多留了" + (separator.length() - 1) + "格" : "，间隔类型不符合要求"));
    }
    if (issues.isEmpty())
      issues.add("图题格式有误，请检查图号与图题之间是否只留一个普通空格");

    String correction = "";
    if (separator.length() > 1 && separator.chars().allMatch(value -> value == ' '))
      correction = "请删掉多余空格，改成“" + number + " " + title + "”。";
    else if (separator.isEmpty() || !" ".equals(separator))
      correction = "请在图号后只留一个普通空格，改成“" + number + " " + title + "”。";
    if (!leading.isEmpty()) {
      if (" ".equals(separator))
        issues.add("编号和图题之间的一个空格是对的");
      correction += "请删掉图号前的空格，再把整段设为居中。";
    }
    return String.join("；", issues) + "。" + correction;
  }

  private static String figureLabelGapSuggestion(String leading, String content) {
    if (!content.startsWith("图")) return null;
    int numberStart = 1;
    while (numberStart < content.length() && isCaptionWhitespace(content.charAt(numberStart))) numberStart++;
    if (numberStart == 1) return null;
    Matcher numberMatcher = Pattern.compile("^(\\d+-\\d+|[A-Z]\\d+)(.*)$")
        .matcher(content.substring(numberStart));
    if (!numberMatcher.find()) return null;

    String number = numberMatcher.group(1);
    String afterNumber = numberMatcher.group(2);
    int separatorEnd = 0;
    while (separatorEnd < afterNumber.length() && isCaptionWhitespace(afterNumber.charAt(separatorEnd)))
      separatorEnd++;
    String separator = afterNumber.substring(0, separatorEnd);
    String title = afterNumber.substring(separatorEnd);
    String corrected = "图" + number + (title.isEmpty() ? "" : " " + title);
    List<String> issues = new ArrayList<>();
    issues.add("“图”和编号之间多了 " + captionWhitespaceDescription(content.substring(1, numberStart)));
    if (!leading.isEmpty()) issues.add("图题开头还有 " + captionWhitespaceDescription(leading));
    if (separator.isEmpty()) {
      issues.add("编号后没有空格");
    } else if (!" ".equals(separator)) {
      issues.add("编号与图题之间的间隔应改为一个普通空格");
    }
    return String.join("；", issues) + "。请改成“" + corrected + "”。";
  }

  private static String tableCaptionNumberSuggestion(CheckResult result) {
    String actual = result.actual() == null ? "" : result.actual();
    int leadingEnd = 0;
    while (leadingEnd < actual.length() && isCaptionWhitespace(actual.charAt(leadingEnd))) leadingEnd++;
    String leading = actual.substring(0, leadingEnd);
    String content = actual.substring(leadingEnd);
    if (!content.startsWith("表"))
      return "表题“" + actual + "”编号格式不符合要求，请按“表4-1 表题”格式书写。";

    int numberStart = 1;
    while (numberStart < content.length() && isCaptionWhitespace(content.charAt(numberStart))) numberStart++;
    Matcher numberMatcher = Pattern.compile("^(\\d+-\\d+|[A-Z]\\d+)(.*)$")
        .matcher(content.substring(numberStart));
    if (!numberMatcher.find())
      return "表题“" + actual + "”编号格式不符合要求，请按“表4-1 表题”格式书写。";

    String number = numberMatcher.group(1);
    String afterNumber = numberMatcher.group(2);
    int separatorEnd = 0;
    while (separatorEnd < afterNumber.length() && isCaptionWhitespace(afterNumber.charAt(separatorEnd)))
      separatorEnd++;
    String separator = afterNumber.substring(0, separatorEnd);
    String title = afterNumber.substring(separatorEnd);
    String corrected = "表" + number + (title.isEmpty() ? "" : " " + title);
    List<String> issues = new ArrayList<>();
    if (!leading.isEmpty()) issues.add("表题开头有 " + captionWhitespaceDescription(leading));
    if (numberStart > 1) issues.add("“表”和编号之间多了 " + captionWhitespaceDescription(content.substring(1, numberStart)));
    if (separator.isEmpty()) {
      issues.add("“表" + number + "”和表题直接连在一起");
    } else if (!" ".equals(separator)) {
      issues.add("表号与表题之间的间隔应改为一个普通空格");
    }
    if (issues.isEmpty()) issues.add("表题格式不符合要求");
    return String.join("；", issues) + "。请改成“" + corrected + "”。";
  }

  private static boolean isCaptionWhitespace(char value) {
    return Character.isWhitespace(value) || Character.isSpaceChar(value)
        || value == '\u200B' || value == '\u200C' || value == '\u200D'
        || value == '\u2060' || value == '\uFEFF';
  }

  private static String captionWhitespaceDescription(String value) {
    if (value.chars().allMatch(character -> character == ' ')) return value.length() + " 个普通空格";
    if (value.chars().allMatch(character -> character == '\t')) return "制表符";
    if (value.chars().allMatch(character -> character == '\u3000')) return "全角空格";
    if (value.chars().allMatch(character -> character == '\u00a0')) return "不换行空格";
    if (value.chars().allMatch(character -> character == '\u200B' || character == '\u200C'
        || character == '\u200D' || character == '\u2060' || character == '\uFEFF'))
      return value.length() + " 个看不见的空白";
    return "混用的特殊空白（" + value.codePoints().mapToObj(CommentWriter::captionWhitespaceName)
        .collect(java.util.stream.Collectors.joining("、")) + "）";
  }

  private static String captionWhitespaceName(int value) {
    return switch (value) {
      case ' ' -> "普通空格";
      case '\t' -> "制表符";
      case '\u3000' -> "全角空格";
      case '\u00a0' -> "不换行空格";
      default -> "看不见的空白";
    };
  }

  private static String headingSpacingSuggestion(CheckResult result) {
    if ("HEADING_NUMBER_INTERNAL_WHITESPACE".equals(result.reasonCode()))
      return cn.luwneshacha.thesis.report.RuleDescriptions.suggestion(result);
    int expectedSpaces = "HEAD-01-SPACING".equals(result.ruleId()) ? 2 : 1;
    String evidence = result.actual();
    String reason = switch (evidence == null ? "" : evidence) {
      case "没有空格" -> "编号和标题直接连在一起";
      case "制表符" -> "编号和标题之间用了制表符";
      case "全角空格" -> "编号和标题之间用了全角空格";
      case "不换行空格" -> "编号和标题之间用了特殊空格";
      case "看不见的空白" -> "编号和标题之间混入了看不见的空白字符";
      case "混合空白（普通空格、制表符）" -> "编号和标题之间混用了空格和制表符";
      default -> evidence != null && evidence.contains("看不见的空白")
          ? "编号和标题之间混入了看不见的空白字符"
          : evidence != null && evidence.contains("多个普通空格")
          ? "编号和标题之间的普通空格数量不对"
          : "编号和标题之间的间隔不符合要求";
    };
    String recommendation = "请在编号后输入 " + expectedSpaces + " 个普通空格，再写标题。";
    return reason + "，需要留 " + expectedSpaces + " 格。" + recommendation;
  }

  private static String sha256(byte[] bytes) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    catch (Exception e) { throw new IllegalStateException(e); }
  }

  private static final class AnchorException extends Exception {
    AnchorException(String message) { super(message); }
  }
}
