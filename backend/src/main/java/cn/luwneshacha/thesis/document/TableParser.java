package cn.luwneshacha.thesis.document;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

public final class TableParser {
  private static final Pattern CAPTION = Pattern.compile("^\\s*(?:续表\\s*(?:\\d+[\\-－]\\d+|[A-Z]\\d+)|表\\s*(?:\\d+[\\-－]\\d+|[A-Z]\\d+))");

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
      var columnWidths = XmlPartSupport.children(element).stream()
          .filter(e -> XmlPartSupport.W.equals(e.getNamespaceURI()) && "tblGrid".equals(e.getLocalName()))
          .flatMap(grid -> XmlPartSupport.children(grid).stream())
          .filter(e -> XmlPartSupport.W.equals(e.getNamespaceURI()) && "gridCol".equals(e.getLocalName()))
          .map(e -> integerAttribute(e, "w"))
          .filter(java.util.Objects::nonNull).toList();
      var rowHeights = rows.stream().map(row -> direct(row, "trPr"))
          .map(TableParser::rowHeight).filter(java.util.Objects::nonNull).toList();
      boolean merged = rows.stream().flatMap(row -> XmlPartSupport.children(row).stream())
          .filter(cell -> "tc".equals(cell.getLocalName()))
          .anyMatch(cell -> hasMerge(cell));
      var headerTexts = rows.isEmpty() ? List.<String>of() : XmlPartSupport.children(rows.get(0)).stream()
          .filter(cell -> "tc".equals(cell.getLocalName())).map(XmlPartSupport::text).toList();
      var note = findFollowingNote(element);
      Element borders = first(element, "tblBorders");
      boolean top = border(borders, "top") || (!rows.isEmpty() && borderInRow(element, rows.get(0), "top"));
      boolean middle = border(borders, "insideH") || borderInCells(element, "insideH")
          || borderInInteriorRows(element, rows, "top") || borderInInteriorRows(element, rows, "bottom");
      boolean bottom = border(borders, "bottom") || (!rows.isEmpty() && borderInRow(element, rows.get(rows.size() - 1), "bottom"));
      boolean vertical = border(borders, "left") || border(borders, "right") || border(borders, "insideV")
          || borderInCells(element, "left") || borderInCells(element, "right") || borderInCells(element, "insideV");
      result.add(new TableModel(new SourceAddress("word/document.xml", path, 0, 0),
          caption == null ? null : new SourceAddress("word/document.xml", caption.path, 0, caption.text.length()),
          caption == null ? null : caption.text, rows.size(), columns, top, middle, bottom, vertical,
          captionBefore, false, merged, columnWidths, rowHeights, headerTexts,
          note == null ? null : note.text(), note == null ? null : new SourceAddress("word/document.xml", note.path(), 0, note.text().length()),
          rows.stream().flatMap(row -> XmlPartSupport.children(row).stream())
              .filter(cell -> "tc".equals(cell.getLocalName())).map(XmlPartSupport::text)
              .anyMatch(text -> text.trim().matches("同前|同左"))));
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
  private record Note(String text, List<Integer> path) {}

  private Note findFollowingNote(Element table) {
    var parent = table.getParentNode();
    if (!(parent instanceof Element parentElement)) return null;
    var siblings = XmlPartSupport.children(parentElement);
    int index = siblings.indexOf(table);
    if (index < 0 || index + 1 >= siblings.size()) return null;
    Element next = siblings.get(index + 1);
    if (!XmlPartSupport.W.equals(next.getNamespaceURI()) || !"p".equals(next.getLocalName())) return null;
    String text = XmlPartSupport.text(next).trim();
    return text.matches("^注\\s*[：:].*") ? new Note(text, pathOf(next)) : null;
  }

  private static boolean hasMerge(Element cell) {
    Element properties = direct(cell, "tcPr");
    if (properties == null) return false;
    for (Element child : XmlPartSupport.children(properties)) {
      if (!XmlPartSupport.W.equals(child.getNamespaceURI())) continue;
      if ("gridSpan".equals(child.getLocalName()) || "vMerge".equals(child.getLocalName())) return true;
    }
    return false;
  }

  private static Integer rowHeight(Element trPr) {
    if (trPr == null) return null;
    for (Element child : XmlPartSupport.children(trPr)) {
      if (XmlPartSupport.W.equals(child.getNamespaceURI()) && "trHeight".equals(child.getLocalName()))
        return integerAttribute(child, "val");
    }
    return null;
  }

  private static Integer integerAttribute(Element element, String localName) {
    String value = element.getAttributeNS(XmlPartSupport.W, localName);
    if (value == null || value.isBlank()) value = element.getAttribute(localName);
    try { return value == null || value.isBlank() ? null : Integer.valueOf(value); }
    catch (NumberFormatException ignored) { return null; }
  }

  private static Element direct(Element parent, String localName) {
    if (parent == null) return null;
    for (Element child : XmlPartSupport.children(parent))
      if (XmlPartSupport.W.equals(child.getNamespaceURI()) && localName.equals(child.getLocalName())) return child;
    return null;
  }

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
    if (nodes.getLength() == 0) return false;
    String value = ((Element) nodes.item(0)).getAttributeNS(XmlPartSupport.W, "val");
    return !"nil".equals(value) && !"none".equals(value);
  }

  private static boolean borderInInteriorRows(Element table, List<Element> rows, String side) {
    for (int index = 0; index < rows.size(); index++) {
      if ("top".equals(side) && index == 0) continue;
      if ("bottom".equals(side) && index == rows.size() - 1) continue;
      if (borderInRow(table, rows.get(index), side)) return true;
    }
    return false;
  }

  private static boolean borderInRow(Element table, Element row, String side) {
    var nodes = row.getElementsByTagNameNS(XmlPartSupport.W, "tcBorders");
    for (int index = 0; index < nodes.getLength(); index++) {
      Element borders = (Element) nodes.item(index);
      if (owningTable(borders) == table && owningRow(borders) == row && border(borders, side)) return true;
    }
    return false;
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

  private static Element owningRow(Element element) {
    Node current = element.getParentNode();
    while (current instanceof Element parent) {
      if (XmlPartSupport.W.equals(parent.getNamespaceURI()) && "tr".equals(parent.getLocalName())) return parent;
      current = parent.getParentNode();
    }
    return null;
  }
}
