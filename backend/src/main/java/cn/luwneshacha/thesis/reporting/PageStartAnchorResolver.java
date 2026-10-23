package cn.luwneshacha.thesis.reporting;

import cn.luwneshacha.thesis.document.SourceAddress;
import java.net.URI;
import java.util.*;
import org.w3c.dom.*;

/** Maps textless findings to an evidenced page start without borrowing another section's text. */
final class PageStartAnchorResolver {
  record Placement(Element paragraph, SourceAddress anchorSource, String reason, boolean afterLeadingBreak) {}
  private record Section(Element properties, List<Element> paragraphs) {}
  private final String w;
  private final Map<String, String> relationships = new HashMap<>();
  private final Map<String, Element> styles = new HashMap<>();
  private final List<Section> sections = new ArrayList<>();
  private String defaultStyle;

  PageStartAnchorResolver(Document main, Document rels, Document styleDocument) {
    this.w = main.getDocumentElement().getNamespaceURI();
    if (rels != null) for (Element rel : children(rels.getDocumentElement())) {
      if ("External".equals(rel.getAttribute("TargetMode"))) continue;
      String type = rel.getAttribute("Type");
      if (!type.endsWith("/header") && !type.endsWith("/footer")) continue;
      URI target = URI.create("/word/document.xml").resolve(rel.getAttribute("Target")).normalize();
      if (target.getScheme() == null && target.getPath().startsWith("/word/"))
        relationships.put(rel.getAttribute("Id"), target.getPath().substring(1));
    }
    if (styleDocument != null) for (Element style : children(styleDocument.getDocumentElement())) {
      if (!"style".equals(style.getLocalName())) continue;
      styles.put(style.getAttributeNS(w, "styleId"), style);
      if ("paragraph".equals(style.getAttributeNS(w, "type")) && style.hasAttributeNS(w, "default")
          && enabledAttribute(style, "default"))
        defaultStyle = style.getAttributeNS(w, "styleId");
    }
    Element body = child(main.getDocumentElement(), "body");
    List<Element> current = new ArrayList<>();
    if (body != null) collectSections(body, current);
    if (!current.isEmpty() || sections.isEmpty()) sections.add(new Section(null, List.copyOf(current)));
  }

  Placement resolve(String sourcePart, Element originalParagraph, SourceAddress contextSource) {
    if (!"word/document.xml".equals(sourcePart)) {
      Placement header = resolveStory(sourcePart, contextSource);
      return header != null ? header : point(sourcePart, originalParagraph, "PLACED_AT_SOURCE_POINT");
    }
    for (Section section : sections) {
      int at = section.paragraphs().indexOf(originalParagraph);
      if (at < 0) continue;
      Element start = pageStart(section, at);
      Placement placement = start == null ? null : point(sourcePart, start, "PLACED_AT_PAGE_START");
      return placement != null ? placement : point(sourcePart, originalParagraph, "PLACED_AT_SOURCE_POINT");
    }
    return point(sourcePart, originalParagraph, "PLACED_AT_SOURCE_POINT");
  }

  private Placement resolveStory(String sourcePart, SourceAddress contextSource) {
    Map<String, String> effective = new LinkedHashMap<>();
    String kind = sourcePart.contains("/header") ? "headerReference" : "footerReference";
    for (int sectionIndex = 0; sectionIndex < sections.size(); sectionIndex++) {
      Section section = sections.get(sectionIndex);
      for (Element reference : children(section.properties())) {
        if (!kind.equals(reference.getLocalName())) continue;
        String id = reference.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id");
        if (id.isEmpty()) id = reference.getAttributeNS("http://purl.oclc.org/ooxml/officeDocument/relationships", "id");
        effective.put(reference.getAttributeNS(w, "type"), relationships.get(id));
      }
      for (var entry : effective.entrySet()) {
        if (!sourcePart.equals(entry.getValue()) || section.paragraphs().isEmpty()) continue;
        int scopedParagraph = -1;
        if (contextSource != null) {
          for (int i = 0; i < section.paragraphs().size(); i++) {
            if (address("word/document.xml", section.paragraphs().get(i)).elementPath().equals(contextSource.elementPath())) {
              scopedParagraph = i; break;
            }
          }
          if (!"word/document.xml".equals(contextSource.partName()) || scopedParagraph < 0) continue;
        }
        // A header/footer part can serve several pages. Place one comment on the first
        // evidenced applicable page; a distinct empty terminal section must stay distinct.
        if ("even".equals(entry.getKey())) continue; // Physical page parity needs layout evidence.
        boolean differentFirst = enabled(child(section.properties(), "titlePg"));
        if ("first".equals(entry.getKey()) && !differentFirst) continue;
        int preferred = Math.max(0, scopedParagraph);
        if (contextSource == null && sectionIndex == 0 && "default".equals(entry.getKey())) {
          for (int i = 0; i < section.paragraphs().size(); i++) {
            String text = visibleText(section.paragraphs().get(i)).replaceAll("[\\s\\p{Zs}]+", "");
            if (text.equals("摘要") || text.equalsIgnoreCase("ABSTRACT")) { preferred = i; break; }
          }
        }
        Element candidate = pageStart(section, preferred);
        if (differentFirst && "default".equals(entry.getKey())
            && (candidate == null || candidate == section.paragraphs().getFirst())) {
          candidate = null;
          for (int i = 1; i < section.paragraphs().size(); i++) {
            if (startsPage(section.paragraphs().get(i), section.paragraphs().get(i - 1))) {
              candidate = section.paragraphs().get(i); break;
            }
          }
        }
        if (candidate != null) {
          Placement placement = point("word/document.xml", candidate, "PLACED_AT_PAGE_START");
          if (placement != null) return placement;
        }
      }
    }
    return null;
  }

  private Element pageStart(Section section, int through) {
    if (section.paragraphs().isEmpty()) return null;
    Element type = child(section.properties(), "type");
    boolean newPage = type == null || !Set.of("continuous", "nextColumn").contains(type.getAttributeNS(w, "val"));
    Element result = newPage ? section.paragraphs().getFirst() : null;
    for (int i = 0; i <= through; i++) {
      Element paragraph = section.paragraphs().get(i);
      if (startsPage(paragraph, i == 0 ? null : section.paragraphs().get(i - 1))) result = paragraph;
    }
    if (result != null) {
      int from = section.paragraphs().indexOf(result);
      for (int i = from + 1; i <= through; i++) {
        if (!visibleText(section.paragraphs().get(i)).isBlank()
            || contentNodes(section.paragraphs().get(i)).stream().anyMatch(this::object)) return null;
      }
    }
    return result;
  }

  private boolean startsPage(Element paragraph, Element previous) {
    Element properties = child(paragraph, "pPr");
    Element direct = child(properties, "pageBreakBefore");
    if (direct != null && enabled(direct)) return true;
    if (direct == null) {
      Element styleId = child(properties, "pStyle");
      String id = styleId == null ? defaultStyle : styleId.getAttributeNS(w, "val");
      Set<String> visited = new HashSet<>();
      while (id != null && visited.add(id)) {
        Element style = styles.get(id);
        Element pageBreak = child(child(style, "pPr"), "pageBreakBefore");
        if (pageBreak != null) { if (enabled(pageBreak)) return true; break; }
        Element basedOn = child(style, "basedOn");
        id = basedOn == null ? null : basedOn.getAttributeNS(w, "val");
      }
    }
    return leadingPageBreak(paragraph) || previous != null && trailingPageBreak(previous);
  }

  private boolean leadingPageBreak(Element paragraph) {
    for (Element node : contentNodes(paragraph)) {
      if (pageBreak(node)) return true;
      if (isText(node) && !node.getTextContent().isEmpty() || object(node)) return false;
    }
    return false;
  }

  private boolean trailingPageBreak(Element paragraph) {
    List<Element> nodes = contentNodes(paragraph);
    for (int i = nodes.size() - 1; i >= 0; i--) {
      Element node = nodes.get(i);
      if (pageBreak(node)) return true;
      if (isText(node) && !node.getTextContent().isEmpty() || object(node)) return false;
    }
    return false;
  }

  private boolean pageBreak(Element node) {
    return w.equals(node.getNamespaceURI()) && ("lastRenderedPageBreak".equals(node.getLocalName())
        || "br".equals(node.getLocalName()) && "page".equals(node.getAttributeNS(w, "type")));
  }

  private boolean isText(Element node) { return "t".equals(node.getLocalName()); }
  private boolean object(Element node) { return Set.of("drawing", "pict", "object").contains(node.getLocalName()); }
  private String visibleText(Element paragraph) {
    return contentNodes(paragraph).stream().filter(this::isText).map(Node::getTextContent)
        .collect(java.util.stream.Collectors.joining());
  }
  private List<Element> contentNodes(Element root) {
    List<Element> result = new ArrayList<>();
    for (Element node : children(root)) {
      if (Set.of("pPr", "rPr", "txbxContent", "del", "moveFrom").contains(node.getLocalName())) continue;
      result.add(node);
      if (!object(node)) result.addAll(contentNodes(node));
    }
    return result;
  }

  private void collectSections(Element root, List<Element> current) {
    for (Element element : children(root)) {
      if (!w.equals(element.getNamespaceURI())) continue;
      if (Set.of("txbxContent", "del", "moveFrom").contains(element.getLocalName())) continue;
      if ("p".equals(element.getLocalName())) {
        current.add(element);
        Element section = child(child(element, "pPr"), "sectPr");
        if (section != null) { sections.add(new Section(section, List.copyOf(current))); current.clear(); }
      } else if ("sectPr".equals(element.getLocalName())) {
        sections.add(new Section(element, List.copyOf(current))); current.clear();
      } else collectSections(element, current);
    }
  }

  private Placement point(String part, Element paragraph, String reason) {
    Element afterBreak = "PLACED_AT_PAGE_START".equals(reason) ? leadingBreak(paragraph) : null;
    if (afterBreak != null && (!(afterBreak.getParentNode() instanceof Element run)
        || !"r".equals(run.getLocalName()) || run.getParentNode() != paragraph)) {
      // Reject this candidate so the caller retains the actual original location.
      return null;
    }
    int offset = 0;
    if (afterBreak != null) for (Element node : contentNodes(paragraph)) {
      if (node == afterBreak) break;
      if (w.equals(node.getNamespaceURI()) && ("tab".equals(node.getLocalName())
          || "cr".equals(node.getLocalName())
          || "br".equals(node.getLocalName()) && !Set.of("page", "column").contains(node.getAttributeNS(w, "type")))) offset++;
    }
    SourceAddress source = address(part, paragraph);
    return new Placement(paragraph, new SourceAddress(part, source.elementPath(), offset, offset), reason, afterBreak != null);
  }

  /** Re-read the live boundary after text highlighting may have replaced its original run. */
  Element leadingBreak(Element paragraph) {
    Element last = null;
    for (Element node : contentNodes(paragraph)) {
      if (pageBreak(node)) last = node;
      else if (isText(node) && !node.getTextContent().isEmpty() || object(node)) break;
    }
    return last;
  }

  private SourceAddress address(String part, Element paragraph) {
    List<Integer> path = new ArrayList<>();
    Node current = paragraph;
    while (current.getParentNode() instanceof Element parent) {
      path.addFirst(children(parent).indexOf(current));
      current = parent;
    }
    return new SourceAddress(part, path, 0, 0);
  }
  private boolean enabled(Element element) { return element != null && enabledAttribute(element, "val"); }
  private boolean enabledAttribute(Element element, String name) {
    String value = element.getAttributeNS(w, name);
    return value.isEmpty() || Set.of("1", "true", "on").contains(value);
  }
  private Element child(Element parent, String name) {
    return children(parent).stream().filter(e -> w.equals(e.getNamespaceURI()) && name.equals(e.getLocalName())).findFirst().orElse(null);
  }
  private static List<Element> children(Element parent) {
    List<Element> result = new ArrayList<>();
    if (parent != null) for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
      if (node instanceof Element element) result.add(element);
    return result;
  }
}
