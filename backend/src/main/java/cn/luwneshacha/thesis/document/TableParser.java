package cn.luwneshacha.thesis.document;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

public final class TableParser {
  private static final Pattern CAPTION = Pattern.compile("^\\s*表\\s*\\d+[\\-－]\\d+");

  public List<TableModel> parse(byte[] xml) {
    try {
      var result = new ArrayList<TableModel>();
      collect(XmlPartSupport.parse(xml).getDocumentElement(), List.of(), result);
      return List.copyOf(result);
    } catch (Exception e) { throw new IllegalArgumentException("unable to parse tables", e); }
  }

  private void collect(Element element, List<Integer> path, List<TableModel> result) {
    if (XmlPartSupport.W.equals(element.getNamespaceURI()) && "tbl".equals(element.getLocalName())) {
      var caption = findCaption(element);
      boolean captionBefore = caption != null;
      if (caption == null) caption = findFollowingCaption(element);
      var rows = XmlPartSupport.children(element).stream().filter(e -> "tr".equals(e.getLocalName())).toList();
      int columns = rows.stream().mapToInt(row -> (int) XmlPartSupport.children(row).stream().filter(e -> "tc".equals(e.getLocalName())).count()).max().orElse(0);
      Element borders = first(element, "tblBorders");
      boolean top = border(borders, "top") || borderInCells(element, "top");
      boolean middle = border(borders, "insideH") || borderInCells(element, "insideH");
      boolean bottom = border(borders, "bottom") || borderInCells(element, "bottom");
      boolean vertical = border(borders, "left") || border(borders, "right") || border(borders, "insideV")
          || borderInCells(element, "left") || borderInCells(element, "right") || borderInCells(element, "insideV");
      result.add(new TableModel(new SourceAddress("word/document.xml", path, 0, 0),
          caption == null ? null : new SourceAddress("word/document.xml", caption.path, 0, caption.text.length()),
          caption == null ? null : caption.text, rows.size(), columns, top, middle, bottom, vertical,
          captionBefore, false));
    }
    int index = 0;
    for (Element child : XmlPartSupport.children(element)) {
      var childPath = new ArrayList<>(path); childPath.add(index++);
      collect(child, List.copyOf(childPath), result);
    }
  }

  private Caption findCaption(Element table) {
    var parent = table.getParentNode();
    if (!(parent instanceof Element parentElement)) return null;
    Element previous = null;
    for (var child : XmlPartSupport.children(parentElement)) {
      if (child == table) break;
      previous = child;
    }
    if (previous == null || !"p".equals(previous.getLocalName())) return null;
    String text = XmlPartSupport.text(previous);
    if (!CAPTION.matcher(text).find()) return null;
    return new Caption(text, pathOf(previous));
  }

  private Caption findFollowingCaption(Element table) {
    var parent = table.getParentNode();
    if (!(parent instanceof Element parentElement)) return null;
    var siblings = XmlPartSupport.children(parentElement);
    int index = siblings.indexOf(table);
    if (index < 0 || index + 1 >= siblings.size()) return null;
    Element next = siblings.get(index + 1);
    if (!XmlPartSupport.W.equals(next.getNamespaceURI()) || !"p".equals(next.getLocalName())) return null;
    String text = XmlPartSupport.text(next);
    if (!CAPTION.matcher(text).find()) return null;
    return new Caption(text, pathOf(next));
  }

  private record Caption(String text, List<Integer> path) {}

  private static List<Integer> pathOf(Element element) {
    var reversed = new ArrayList<Integer>();
    Node current = element;
    while (current.getParentNode() instanceof Element parent) {
      int index = 0;
      for (Element child : XmlPartSupport.children(parent)) { if (child == current) break; index++; }
      reversed.add(index); current = parent;
    }
    java.util.Collections.reverse(reversed); return List.copyOf(reversed);
  }
  private static Element first(Element root, String local) {
    for (Element child : XmlPartSupport.children(root)) {
      if (!XmlPartSupport.W.equals(child.getNamespaceURI()) || !"tblPr".equals(child.getLocalName())) continue;
      for (Element property : XmlPartSupport.children(child)) {
        if (XmlPartSupport.W.equals(property.getNamespaceURI()) && local.equals(property.getLocalName())) return property;
      }
    }
    return null;
  }
  private static boolean border(Element borders, String side) {
    if (borders == null) return false;
    var nodes = borders.getElementsByTagNameNS(XmlPartSupport.W, side);
    return nodes.getLength() > 0 && !"nil".equals(((Element) nodes.item(0)).getAttributeNS(XmlPartSupport.W, "val"));
  }

  private static boolean borderInCells(Element table, String side) {
    var nodes = table.getElementsByTagNameNS(XmlPartSupport.W, "tcBorders");
    for (int i = 0; i < nodes.getLength(); i++) {
      Element borders = (Element) nodes.item(i);
      if (owningTable(borders) != table) continue;
      if (border(borders, side)) return true;
    }
    return false;
  }

  private static Element owningTable(Element element) {
    Node current = element.getParentNode();
    while (current instanceof Element parent) {
      if (XmlPartSupport.W.equals(parent.getNamespaceURI()) && "tbl".equals(parent.getLocalName())) return parent;
      current = parent.getParentNode();
    }
    return null;
  }
}
