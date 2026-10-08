package cn.luwneshacha.thesis.document;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

final class XmlPartSupport {
  static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
  static final String M = "http://schemas.openxmlformats.org/officeDocument/2006/math";
  private XmlPartSupport() {}

  static Document parse(byte[] bytes) throws Exception {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
  }

  static List<Element> children(Element element) {
    var result = new ArrayList<Element>();
    for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling())
      if (node instanceof Element child) result.add(child);
    return result;
  }

  static String text(Element element) {
    var value = new StringBuilder();
    var nodes = element.getElementsByTagNameNS(W, "t");
    for (int i = 0; i < nodes.getLength(); i++) value.append(nodes.item(i).getTextContent());
    return value.toString();
  }
}
