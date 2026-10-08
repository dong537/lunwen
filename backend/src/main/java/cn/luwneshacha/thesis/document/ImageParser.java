package cn.luwneshacha.thesis.document;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

public final class ImageParser {
  private static final String DRAW = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing";
  private static final Pattern CAPTION = Pattern.compile("^\\s*图\\s*\\d+(?:[-－]\\d+)?\\s+.+");

  public List<ImageModel> parse(byte[] xml) {
    try {
      var result = new ArrayList<ImageModel>();
      collect(XmlPartSupport.parse(xml).getDocumentElement(), List.of(), result);
      return List.copyOf(result);
    } catch (Exception e) {
      throw new IllegalArgumentException("unable to parse images", e);
    }
  }

  private void collect(Element element, List<Integer> path, List<ImageModel> result) {
    if ("http://schemas.openxmlformats.org/markup-compatibility/2006".equals(element.getNamespaceURI())
        && "Fallback".equals(element.getLocalName())) return;
    if (XmlPartSupport.W.equals(element.getNamespaceURI()) && "drawing".equals(element.getLocalName())) {
      Element paragraph = ancestor(element, XmlPartSupport.W, "p");
      if (paragraph != null) {
        Element extent = first(element, DRAW, "extent");
        long width = extent == null ? 0 : number(extent.getAttribute("cx"));
        long height = extent == null ? 0 : number(extent.getAttribute("cy"));
        Caption caption = followingCaption(paragraph);
        result.add(new ImageModel(
            new SourceAddress("word/document.xml", path, 0, 0),
            caption == null ? null : new SourceAddress("word/document.xml", caption.path(), 0, caption.text().length()),
            caption == null ? null : caption.text(), width, height,
            element.getElementsByTagNameNS(DRAW, "inline").getLength() > 0, true));
      }
    }
    int index = 0;
    for (Element child : XmlPartSupport.children(element)) {
      var childPath = new ArrayList<>(path);
      childPath.add(index++);
      collect(child, List.copyOf(childPath), result);
    }
  }

  private static Caption followingCaption(Element paragraph) {
    Node node = paragraph.getParentNode();
    if (!(node instanceof Element parent)) return null;
    List<Element> siblings = XmlPartSupport.children(parent);
    int index = siblings.indexOf(paragraph);
    if (index < 0 || index + 1 >= siblings.size()) return null;
    Element next = siblings.get(index + 1);
    if (!XmlPartSupport.W.equals(next.getNamespaceURI()) || !"p".equals(next.getLocalName())) return null;
    String text = XmlPartSupport.text(next).trim();
    return CAPTION.matcher(text).matches() ? new Caption(text, pathOf(next)) : null;
  }

  private static Element ancestor(Element element, String namespace, String localName) {
    Node current = element.getParentNode();
    while (current instanceof Element parent) {
      if (namespace.equals(parent.getNamespaceURI()) && localName.equals(parent.getLocalName())) return parent;
      current = parent.getParentNode();
    }
    return null;
  }

  private static Element first(Element root, String namespace, String localName) {
    var nodes = root.getElementsByTagNameNS(namespace, localName);
    return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
  }

  private static long number(String value) {
    try { return Long.parseLong(value); } catch (NumberFormatException ignored) { return 0; }
  }

  private static List<Integer> pathOf(Element element) {
    var reversed = new ArrayList<Integer>();
    Node current = element;
    while (current.getParentNode() instanceof Element parent) {
      int index = 0;
      for (Element child : XmlPartSupport.children(parent)) {
        if (child == current) break;
        index++;
      }
      reversed.add(index);
      current = parent;
    }
    java.util.Collections.reverse(reversed);
    return List.copyOf(reversed);
  }

  private record Caption(String text, List<Integer> path) {}
}
