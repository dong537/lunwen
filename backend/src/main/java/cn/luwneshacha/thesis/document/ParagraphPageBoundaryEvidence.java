package cn.luwneshacha.thesis.document;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.w3c.dom.Element;

/** Source pagination evidence only; absence never asserts a rendered page number. */
public record ParagraphPageBoundaryEvidence(Boundary beforeFirstVisibleContent, Boundary anywhereInParagraph,
    Boundary beforeLastVisibleContent) {
  public ParagraphPageBoundaryEvidence(Boundary beforeFirstVisibleContent, Boundary anywhereInParagraph) {
    this(beforeFirstVisibleContent, anywhereInParagraph, beforeFirstVisibleContent);
  }
  public enum Boundary { ABSENT, PRESENT, UNKNOWN }
  private static final String W = XmlPartSupport.W;

  static ParagraphPageBoundaryEvidence parse(Element paragraph, Element styles) {
    var scan = new Scan(pageBreakBefore(paragraph, styles));
    scan.walk(paragraph, paragraph);
    List<Integer> path = new ArrayList<>();
    for (Element current = paragraph; current.getParentNode() instanceof Element parent; current = parent)
      path.addFirst(XmlPartSupport.children(parent).indexOf(current));
    if (XmlPartSupport.compatibilityGaps(paragraph.getOwnerDocument()).stream()
        .anyMatch(gap -> intersectingPaths(path, gap.elementPath())))
      return new ParagraphPageBoundaryEvidence(merge(scan.before, Boundary.UNKNOWN), merge(scan.anywhere, Boundary.UNKNOWN),
          merge(scan.beforeLast, Boundary.UNKNOWN));
    return new ParagraphPageBoundaryEvidence(scan.before, scan.anywhere, scan.beforeLast);
  }

  private static Boundary pageBreakBefore(Element paragraph, Element styles) {
    Element direct = child(child(paragraph, "pPr"), "pageBreakBefore");
    if (direct != null) return onOff(direct);
    Element styleReference = child(child(paragraph, "pPr"), "pStyle");
    String id = styleReference == null ? null : styleReference.getAttributeNS(W, "val");
    if (styles == null) return id == null ? Boundary.ABSENT : Boundary.UNKNOWN;
    if (id == null) for (Element style : XmlPartSupport.children(styles))
      if ("style".equals(style.getLocalName()) && "paragraph".equals(style.getAttributeNS(W, "type"))
          && XmlOnOff.attribute(style, "default", false)) { id = style.getAttributeNS(W, "styleId"); break; }
    var visited = new HashSet<String>();
    while (id != null) {
      if (!visited.add(id)) return Boundary.UNKNOWN;
      Element found = null;
      for (Element style : XmlPartSupport.children(styles))
        if ("style".equals(style.getLocalName()) && id.equals(style.getAttributeNS(W, "styleId"))) {
          found = style; break;
        }
      if (found == null) return Boundary.UNKNOWN;
      Element value = child(child(found, "pPr"), "pageBreakBefore");
      if (value != null) return onOff(value);
      Element basedOn = child(found, "basedOn");
      id = basedOn == null ? null : basedOn.getAttributeNS(W, "val");
    }
    Element defaults = child(child(child(styles, "docDefaults"), "pPrDefault"), "pPr");
    Element value = child(defaults, "pageBreakBefore");
    return value == null ? Boundary.ABSENT : onOff(value);
  }

  private static Boundary onOff(Element value) {
    String flag = value.getAttributeNS(W, "val");
    if (!flag.isEmpty() && !Set.of("true", "false", "1", "0", "on", "off").contains(flag)) return Boundary.UNKNOWN;
    return XmlOnOff.enabled(value) ? Boundary.PRESENT : Boundary.ABSENT;
  }

  private static final class Scan {
    private Boundary before;
    private Boundary anywhere;
    private Boundary beforeLast;
    private boolean seenVisibleContent;
    private Scan(Boundary initial) { before = initial; anywhere = initial; beforeLast = initial; }
    private void visible() { beforeLast = anywhere; seenVisibleContent = true; }
    private void boundary(Boundary evidence) {
      anywhere = merge(anywhere, evidence);
      if (!seenVisibleContent) before = merge(before, evidence);
    }
    private void walk(Element element, Element paragraph) {
      if (!W.equals(element.getNamespaceURI())) { boundary(Boundary.UNKNOWN); visible(); return; }
      String name = element.getLocalName();
      if (Set.of("del", "moveFrom", "delText", "delInstrText", "pPr", "rPr").contains(name)
          || name.endsWith("PrChange") || name.equals("p") && element != paragraph) return;
      if (Set.of("drawing", "pict", "object", "txbxContent").contains(name)) { visible(); return; }
      if (name.equals("br") && element.getAttributeNS(W, "type").equals("page")) boundary(Boundary.PRESENT);
      else if (name.equals("lastRenderedPageBreak")) boundary(Boundary.UNKNOWN);
      else if (name.equals("t") && !XmlPartSupport.isBlankText(element.getTextContent())) visible();
      else if (Set.of("altChunk", "contentPart", "subDoc").contains(name)) boundary(Boundary.UNKNOWN);
      for (Element nested : XmlPartSupport.children(element)) walk(nested, paragraph);
    }
  }

  private static Boundary merge(Boundary left, Boundary right) {
    if (left == Boundary.PRESENT || right == Boundary.PRESENT) return Boundary.PRESENT;
    return left == Boundary.UNKNOWN || right == Boundary.UNKNOWN ? Boundary.UNKNOWN : Boundary.ABSENT;
  }

  private static boolean intersectingPaths(List<Integer> left, List<Integer> right) {
    int shared = Math.min(left.size(), right.size());
    return left.subList(0, shared).equals(right.subList(0, shared));
  }

  private static Element child(Element parent, String name) {
    if (parent == null) return null;
    return XmlPartSupport.children(parent).stream().filter(e -> W.equals(e.getNamespaceURI())
        && name.equals(e.getLocalName())).findFirst().orElse(null);
  }
}
