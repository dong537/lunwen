package cn.luwneshacha.thesis.document;

import java.util.Set;

/** Exact validation for OOXML enumeration attributes before JAXB can discard them. */
final class XmlEnum {
  private XmlEnum() {}

  static String value(org.w3c.dom.Element element, String attribute, Set<String> allowed) {
    if (element == null || !element.hasAttributeNS(XmlPartSupport.W, attribute)) return null;
    String value = element.getAttributeNS(XmlPartSupport.W, attribute);
    if (value == null || value.isEmpty() || !allowed.contains(value))
      throw new IllegalArgumentException("invalid w:" + attribute + " value '" + String.valueOf(value) + "'");
    return value;
  }

  static boolean isValid(org.w3c.dom.Element element, String attribute, Set<String> allowed) {
    if (element == null || !element.hasAttributeNS(XmlPartSupport.W, attribute)) return true;
    String value = element.getAttributeNS(XmlPartSupport.W, attribute);
    return value != null && !value.isEmpty() && allowed.contains(value);
  }
}
