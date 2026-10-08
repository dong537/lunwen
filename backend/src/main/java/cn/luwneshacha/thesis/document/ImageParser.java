package cn.luwneshacha.thesis.document;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

public final class ImageParser {
  private static final String DRAW = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing";
  private static final String DRAW_MAIN = "http://schemas.openxmlformats.org/drawingml/2006/main";
  private static final String PICTURE = "http://schemas.openxmlformats.org/drawingml/2006/picture";
  private static final String VML = "urn:schemas-microsoft-com:vml";
  private static final Pattern CAPTION = Pattern.compile("^\\s*图\\s*(?:\\d+(?:[-－]\\d+)?|[A-Z]\\d+)\\s*\\S.*");

  public List<ImageModel> parse(byte[] xml) {
    return parse(xml, null);
  }

  public List<ImageModel> parse(byte[] xml, byte[] stylesXml) {
    try {
      var result = new ArrayList<ImageModel>();
      Element styles = stylesXml == null ? null : XmlPartSupport.parse(stylesXml).getDocumentElement();
      collect(XmlPartSupport.parse(xml).getDocumentElement(), styles, List.of(), result);
      return List.copyOf(result);
    } catch (Exception e) {
      throw new IllegalArgumentException("unable to parse images", e);
    }
  }

  private void collect(Element element, Element styles, List<Integer> path, List<ImageModel> result) {
    if ("http://schemas.openxmlformats.org/markup-compatibility/2006".equals(element.getNamespaceURI())
        && "Fallback".equals(element.getLocalName())) return;
    boolean drawingPicture = XmlPartSupport.W.equals(element.getNamespaceURI())
        && "drawing".equals(element.getLocalName()) && first(element, PICTURE, "pic") != null;
    boolean vmlPicture = XmlPartSupport.W.equals(element.getNamespaceURI()) && "pict".equals(element.getLocalName())
        && element.getElementsByTagNameNS(VML, "imagedata").getLength() > 0;
    if (drawingPicture || vmlPicture) {
      Element paragraph = ancestor(element, XmlPartSupport.W, "p");
      if (paragraph != null) {
        Element extent = drawingPicture ? first(element, DRAW, "extent") : null;
        long width = extent == null ? 0 : number(extent.getAttribute("cx"));
        long height = extent == null ? 0 : number(extent.getAttribute("cy"));
        long transformWidth = transformSize(element, "cx");
        long transformHeight = transformSize(element, "cy");
        Caption caption = followingCaption(paragraph, styles);
        result.add(new ImageModel(
            new SourceAddress("word/document.xml", pathOf(paragraph), 0, 0),
            caption == null ? null : new SourceAddress("word/document.xml", caption.path(), 0, caption.text().length()),
            caption == null ? null : caption.text(), width, height,
            drawingPicture && element.getElementsByTagNameNS(DRAW, "inline").getLength() > 0,
            centered(paragraph, styles), caption == null ? null : centered(caption.paragraph(), styles),
            transformWidth, transformHeight));
      }
    }
    int index = 0;
    for (Element child : XmlPartSupport.children(element)) {
      var childPath = new ArrayList<>(path);
      childPath.add(index++);
      collect(child, styles, List.copyOf(childPath), result);
    }
  }

  private static Caption followingCaption(Element paragraph, Element styles) {
    Node node = paragraph.getParentNode();
    if (!(node instanceof Element parent)) return null;
    List<Element> siblings = XmlPartSupport.children(parent);
    int index = siblings.indexOf(paragraph);
    if (index < 0 || index + 1 >= siblings.size()) return null;
    Element next = siblings.get(index + 1);
    if (!XmlPartSupport.W.equals(next.getNamespaceURI()) || !"p".equals(next.getLocalName())) return null;
    String text = XmlPartSupport.text(next).trim();
    return CAPTION.matcher(text).matches() ? new Caption(text, pathOf(next), next) : null;
  }

  private static Boolean centered(Element paragraph, Element styles) {
    Element pPr = direct(paragraph, XmlPartSupport.W, "pPr");
    Element jc = pPr == null ? null : direct(pPr, XmlPartSupport.W, "jc");
    if (jc != null) return "center".equals(jc.getAttributeNS(XmlPartSupport.W, "val"));

    Element pStyle = pPr == null ? null : direct(pPr, XmlPartSupport.W, "pStyle");
    String styleId = pStyle == null ? defaultParagraphStyle(styles)
        : pStyle.getAttributeNS(XmlPartSupport.W, "val");
    if (styleId != null) {
      if (styles == null) return null;
      var visited = new HashSet<String>();
      while (styleId != null && !styleId.isBlank() && visited.add(styleId)) {
        Element style = findStyle(styles, styleId);
        if (style == null) return null;
        Element stylePPr = direct(style, XmlPartSupport.W, "pPr");
        Element styleJc = stylePPr == null ? null : direct(stylePPr, XmlPartSupport.W, "jc");
        if (styleJc != null) return "center".equals(styleJc.getAttributeNS(XmlPartSupport.W, "val"));
        Element basedOn = direct(style, XmlPartSupport.W, "basedOn");
        styleId = basedOn == null ? null : basedOn.getAttributeNS(XmlPartSupport.W, "val");
      }
      if (styleId != null) return null;
    }

    Element defaults = styles == null ? null : direct(styles, XmlPartSupport.W, "docDefaults");
    Element defaultPPr = defaults == null ? null : direct(defaults, XmlPartSupport.W, "pPrDefault");
    Element defaultJc = defaultPPr == null ? null : direct(defaultPPr, XmlPartSupport.W, "jc");
    return defaultJc != null && "center".equals(defaultJc.getAttributeNS(XmlPartSupport.W, "val"));
  }

  private static String defaultParagraphStyle(Element styles) {
    if (styles == null) return null;
    for (Element style : XmlPartSupport.children(styles)) {
      if (!XmlPartSupport.W.equals(style.getNamespaceURI()) || !"style".equals(style.getLocalName())
          || !"paragraph".equals(style.getAttributeNS(XmlPartSupport.W, "type"))
          || !"1".equals(style.getAttributeNS(XmlPartSupport.W, "default"))) continue;
      return style.getAttributeNS(XmlPartSupport.W, "styleId");
    }
    return null;
  }

  private static Element findStyle(Element styles, String styleId) {
    for (Element style : XmlPartSupport.children(styles)) {
      if (XmlPartSupport.W.equals(style.getNamespaceURI()) && "style".equals(style.getLocalName())
          && styleId.equals(style.getAttributeNS(XmlPartSupport.W, "styleId"))) return style;
    }
    return null;
  }

  private static Element direct(Element parent, String namespace, String localName) {
    for (Element child : XmlPartSupport.children(parent)) {
      if (namespace.equals(child.getNamespaceURI()) && localName.equals(child.getLocalName())) return child;
    }
    return null;
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

  private static long transformSize(Element drawing, String attribute) {
    var transforms = drawing.getElementsByTagNameNS(DRAW_MAIN, "xfrm");
    if (transforms.getLength() == 0) return 0;
    var extents = ((Element) transforms.item(0)).getElementsByTagNameNS(DRAW_MAIN, "ext");
    if (extents.getLength() == 0) return 0;
    return number(((Element) extents.item(0)).getAttribute(attribute));
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

  private record Caption(String text, List<Integer> path, Element paragraph) {}
}
