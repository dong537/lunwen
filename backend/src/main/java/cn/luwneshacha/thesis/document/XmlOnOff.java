package cn.luwneshacha.thesis.document;

import org.w3c.dom.Element;

/** Exact parser for OOXML ST_OnOff properties. */
final class XmlOnOff {
  private XmlOnOff() {}

  static boolean enabled(Element element) {
    return element != null && attribute(element, "val", true);
  }

  static boolean attribute(Element element, String attributeName, boolean missingValue) {
    if (element == null || !element.hasAttributeNS(XmlPartSupport.W, attributeName)) return missingValue;
    return parse(element.getAttributeNS(XmlPartSupport.W, attributeName),
        element.getLocalName() + "/@" + attributeName);
  }

  static boolean parse(String value, String elementName) {
    return switch (value) {
      case "true", "1", "on" -> true;
      case "false", "0", "off" -> false;
      default -> throw new IllegalArgumentException("invalid ST_OnOff value '" + value
          + "' for w:" + elementName);
    };
  }
}
