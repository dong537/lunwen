package cn.luwneshacha.thesis.document;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

public final class XmlPartSupport {
  static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
  static final String M = "http://schemas.openxmlformats.org/officeDocument/2006/math";
  static final String O = "urn:schemas-microsoft-com:office:office";
  static final String STRICT_W = "http://purl.oclc.org/ooxml/wordprocessingml/main";
  static final String STRICT_M = "http://purl.oclc.org/ooxml/officeDocument/math";
  private static final String DRAW = "http://schemas.openxmlformats.org/drawingml/2006/main";
  private static final String PICTURE = "http://schemas.openxmlformats.org/drawingml/2006/picture";
  private static final String WORD_DRAW = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing";
  private static final String STRICT_DRAW = "http://purl.oclc.org/ooxml/drawingml/main";
  private static final String STRICT_PICTURE = "http://purl.oclc.org/ooxml/drawingml/picture";
  private static final String STRICT_WORD_DRAW = "http://purl.oclc.org/ooxml/drawingml/wordprocessingDrawing";
  private XmlPartSupport() {}

  public static Document parse(byte[] bytes) throws Exception {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    var builder = factory.newDocumentBuilder();
    builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
      @Override public void error(SAXParseException error) throws SAXException { throw error; }
      @Override public void fatalError(SAXParseException error) throws SAXException { throw error; }
    });
    String xml = new String(bytes, StandardCharsets.UTF_8)
        .replace(STRICT_W, W)
        .replace(STRICT_M, M)
        .replace(STRICT_DRAW, DRAW)
        .replace(STRICT_PICTURE, PICTURE)
        .replace(STRICT_WORD_DRAW, WORD_DRAW);
    return builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
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

  static boolean isBlankText(String value) {
    return value != null && value.codePoints().allMatch(codePoint ->
        Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
            || isInvisibleFormattingWhitespace(codePoint));
  }

  static boolean isInvisibleFormattingWhitespace(int codePoint) {
    return codePoint == 0x200B || codePoint == 0x200C || codePoint == 0x200D
        || codePoint == 0x2060 || codePoint == 0xFEFF;
  }
}
