package cn.luwneshacha.thesis.document;

import java.util.HashSet;
import java.util.Set;
import org.w3c.dom.Element;

/** Parses w:jc from the source XML without allowing JAXB to hide invalid values. */
final class XmlAlignment {
  private static final Set<String> VALUES = Set.of(
      "left", "center", "right", "both", "start", "end", "distribute",
      "numTab", "highKashida", "lowKashida", "thaiDistribute");

  private XmlAlignment() {}

  static String effective(Element paragraph, Element styles) {
    Element pPr = direct(paragraph, "pPr");
    Element directJc = direct(pPr, "jc");
    if (directJc != null) return parse(directJc);

    Element pStyle = direct(pPr, "pStyle");
    String styleId = pStyle == null ? defaultParagraphStyle(styles) : attr(pStyle, "val");
    Set<String> visited = new HashSet<>();
    while (styles != null && styleId != null && !styleId.isBlank() && visited.add(styleId)) {
      Element style = findStyle(styles, styleId);
      if (style == null) break;
      Element styleJc = direct(direct(style, "pPr"), "jc");
      if (styleJc != null) return parse(styleJc);
      styleId = attr(direct(style, "basedOn"), "val");
    }

    Element defaults = direct(styles, "docDefaults");
    Element defaultJc = direct(direct(direct(defaults, "pPrDefault"), "pPr"), "jc");
    return defaultJc == null ? "left" : parse(defaultJc);
  }

  static String parse(Element jc) {
    String value = attr(jc, "val");
    if (value == null || value.isEmpty() || !VALUES.contains(value))
      throw new IllegalArgumentException("invalid w:jc/@w:val: " + String.valueOf(value));
    return switch (value) {
      case "start" -> "left";
      case "end" -> "right";
      default -> value;
    };
  }

  private static String defaultParagraphStyle(Element styles) {
    if (styles == null) return null;
    for (Element style : XmlPartSupport.children(styles)) {
      if (XmlPartSupport.W.equals(style.getNamespaceURI()) && "style".equals(style.getLocalName())
          && "paragraph".equals(attr(style, "type")) && XmlOnOff.attribute(style, "default", false))
        return attr(style, "styleId");
    }
    return null;
  }

  private static Element findStyle(Element styles, String styleId) {
    if (styles == null) return null;
    for (Element style : XmlPartSupport.children(styles)) {
      if (XmlPartSupport.W.equals(style.getNamespaceURI()) && "style".equals(style.getLocalName())
          && "paragraph".equals(attr(style, "type")) && styleId.equals(attr(style, "styleId"))) return style;
    }
    return null;
  }

  private static Element direct(Element parent, String localName) {
    if (parent == null) return null;
    for (Element child : XmlPartSupport.children(parent))
      if (XmlPartSupport.W.equals(child.getNamespaceURI()) && localName.equals(child.getLocalName())) return child;
    return null;
  }

  private static String attr(Element element, String localName) {
    if (element == null) return null;
    String value = element.getAttributeNS(XmlPartSupport.W, localName);
    return value.isEmpty() && element.hasAttribute(localName) ? element.getAttribute(localName) : value;
  }
}
