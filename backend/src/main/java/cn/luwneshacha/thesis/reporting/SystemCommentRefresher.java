package cn.luwneshacha.thesis.reporting;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Refreshes only inspection-owned comments, while retaining every human discussion thread. */
final class SystemCommentRefresher {
  private static final String CID = "http://schemas.microsoft.com/office/word/2016/wordml/cid";
  private static final String CEX = "http://schemas.microsoft.com/office/word/2018/wordml/cex";
  private SystemCommentRefresher() { }

  record RefreshResult(Set<String> removedCommentIds, Set<String> removedDurableIds) { }

  static RefreshResult refresh(Document comments, Document extended, Document ids, Document people,
      String systemAuthor, List<CommentReply> replies) {
    Map<String, Element> byId = new LinkedHashMap<>();
    Map<String, String> idByPara = new LinkedHashMap<>();
    Map<String, Set<String>> parasById = new LinkedHashMap<>();
    Map<String, Set<String>> neighbors = new LinkedHashMap<>();
    Set<String> protectedIds = new HashSet<>();
    for (Element comment : elements(comments, "comment")) {
      String id = attribute(comment, "id");
      if (id.isBlank()) continue;
      byId.put(id, comment);
      neighbors.put(id, new HashSet<>());
      Set<String> paras = new HashSet<>();
      String commentPara = attribute(comment, "paraId");
      if (!commentPara.isBlank()) paras.add(normalize(commentPara));
      for (Element paragraph : elements(comment, "p")) {
        String para = attribute(paragraph, "paraId");
        if (!para.isBlank()) paras.add(normalize(para));
      }
      parasById.put(id, paras);
      for (String para : paras) {
        String previous = idByPara.putIfAbsent(para, id);
        if (previous != null && !previous.equals(id)) {
          protectedIds.add(previous);
          protectedIds.add(id);
        }
      }
      if (!systemAuthor.equals(attribute(comment, "author"))) protectedIds.add(id);
    }
    for (Element entry : elements(extended, "commentEx")) {
      String parentPara = attribute(entry, "paraIdParent");
      if (parentPara.isBlank()) continue;
      String child = idByPara.get(normalize(attribute(entry, "paraId")));
      String parent = idByPara.get(normalize(parentPara));
      if (child == null || parent == null) {
        // An unresolved parent relationship must not turn an existing discussion into an orphan.
        if (child != null) protectedIds.add(child);
        if (parent != null) protectedIds.add(parent);
      } else {
        neighbors.get(child).add(parent);
        neighbors.get(parent).add(child);
      }
    }
    for (CommentReply reply : replies) protectedIds.add(reply.parentCommentId());
    List<String> pending = new ArrayList<>(protectedIds);
    for (int index = 0; index < pending.size(); index++) {
      for (String neighbor : neighbors.getOrDefault(pending.get(index), Set.of())) {
        if (protectedIds.add(neighbor)) pending.add(neighbor);
      }
    }
    Set<String> removedIds = new HashSet<>();
    Set<String> removedParas = new HashSet<>();
    for (var entry : byId.entrySet()) {
      if (protectedIds.contains(entry.getKey())) continue;
      removedIds.add(entry.getKey());
      removedParas.addAll(parasById.get(entry.getKey()));
      entry.getValue().getParentNode().removeChild(entry.getValue());
    }
    if (removedIds.isEmpty()) return new RefreshResult(Set.of(), Set.of());
    Set<String> removedDurableIds = new HashSet<>();
    for (Element entry : elements(ids, "commentId")) {
      if (CID.equals(entry.getNamespaceURI())
          && removedParas.contains(normalize(entry.getAttributeNS(CID, "paraId")))) {
        String durableId = entry.getAttributeNS(CID, "durableId");
        if (!durableId.isBlank()) removedDurableIds.add(normalize(durableId));
      }
    }
    removeParaEntries(extended, "commentEx", removedParas);
    removeParaEntries(ids, "commentId", removedParas);
    // Ambiguous shared IDs do not prove that the remaining human metadata is inspection-owned.
    for (Element entry : elements(ids, "commentId")) {
      if (CID.equals(entry.getNamespaceURI()))
        removedDurableIds.remove(normalize(entry.getAttributeNS(CID, "durableId")));
    }
    boolean systemAuthorRemains = elements(comments, "comment").stream()
        .anyMatch(comment -> systemAuthor.equals(attribute(comment, "author")));
    if (!systemAuthorRemains) {
      for (Element person : elements(people, "person")) {
        if (systemAuthor.equals(attribute(person, "author"))) person.getParentNode().removeChild(person);
      }
    }
    return new RefreshResult(Set.copyOf(removedIds), Set.copyOf(removedDurableIds));
  }

  static boolean removeExtensibleEntries(Document document, Set<String> removedDurableIds) {
    Element root = document.getDocumentElement();
    if (!CEX.equals(root.getNamespaceURI()) || !"commentsExtensible".equals(root.getLocalName())) return false;
    boolean changed = false;
    for (Element entry : elements(document, "commentExtensible")) {
      if (entry.getParentNode() == root && CEX.equals(entry.getNamespaceURI())
          && removedDurableIds.contains(normalize(entry.getAttributeNS(CEX, "durableId")))) {
        root.removeChild(entry);
        changed = true;
      }
    }
    return changed;
  }

  static boolean removeAnchors(Document document, Set<String> removedIds) {
    boolean changed = false;
    for (String name : List.of("commentRangeStart", "commentRangeEnd", "commentReference")) {
      for (Element marker : elements(document, name)) {
        if (!removedIds.contains(attribute(marker, "id"))) continue;
        String namespace = marker.getNamespaceURI();
        if (!"http://schemas.openxmlformats.org/wordprocessingml/2006/main".equals(namespace)
            && !"http://purl.oclc.org/ooxml/wordprocessingml/main".equals(namespace)) continue;
        Node parent = marker.getParentNode();
        parent.removeChild(marker);
        if ("commentReference".equals(name) && parent instanceof Element run
            && "r".equals(run.getLocalName()) && !hasRunContent(run)) run.getParentNode().removeChild(run);
        changed = true;
      }
    }
    // Historic highlight properties do not record their author. Never guess from their color or range.
    return changed;
  }

  private static boolean hasRunContent(Element run) {
    for (Node node = run.getFirstChild(); node != null; node = node.getNextSibling()) {
      if (node instanceof Element child && !"rPr".equals(child.getLocalName())) return true;
      if (node.getNodeType() == Node.TEXT_NODE && !node.getTextContent().isBlank()) return true;
    }
    return false;
  }

  private static void removeParaEntries(Document document, String name, Set<String> removedParas) {
    for (Element entry : elements(document, name)) {
      if (removedParas.contains(normalize(attribute(entry, "paraId"))))
        entry.getParentNode().removeChild(entry);
    }
  }

  private static String normalize(String value) { return value.toUpperCase(Locale.ROOT); }

  private static String attribute(Element element, String name) {
    var attributes = element.getAttributes();
    for (int index = 0; index < attributes.getLength(); index++) {
      Node attribute = attributes.item(index);
      if (name.equals(attribute.getLocalName()) || name.equals(attribute.getNodeName()))
        return attribute.getNodeValue();
    }
    return "";
  }

  private static List<Element> elements(Document document, String localName) {
    return elements(document.getDocumentElement(), localName);
  }

  private static List<Element> elements(Element root, String localName) {
    List<Element> result = new ArrayList<>();
    var nodes = root.getElementsByTagNameNS("*", localName);
    for (int index = 0; index < nodes.getLength(); index++) result.add((Element) nodes.item(index));
    return result;
  }
}
