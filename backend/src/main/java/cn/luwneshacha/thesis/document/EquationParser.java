package cn.luwneshacha.thesis.document;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.w3c.dom.Element;

public final class EquationParser {
  private static final Pattern NUMBER = Pattern.compile("\\((\\d+[\\-－]\\d+)\\)");

  public List<EquationModel> parse(byte[] xml) {
    try {
      var result = new ArrayList<EquationModel>();
      collect(XmlPartSupport.parse(xml).getDocumentElement(), List.of(), null, result);
      return List.copyOf(result);
    } catch (Exception e) { throw new IllegalArgumentException("unable to parse equations", e); }
  }

  private void collect(Element element, List<Integer> path, String paragraphText, List<EquationModel> result) {
    String currentText = "p".equals(element.getLocalName()) && XmlPartSupport.W.equals(element.getNamespaceURI())
        ? XmlPartSupport.text(element) : paragraphText;
    List<Integer> paragraphPath = "p".equals(element.getLocalName()) && XmlPartSupport.W.equals(element.getNamespaceURI())
        ? path : null;
    if (paragraphPath == null) paragraphPath = findParagraphPath(element, path);
    if (XmlPartSupport.M.equals(element.getNamespaceURI()) && "oMath".equals(element.getLocalName())) {
      String summary = mathText(element);
      result.add(model(paragraphPath, "OMML", summary, currentText));
    } else if (XmlPartSupport.W.equals(element.getNamespaceURI()) && "object".equals(element.getLocalName())) {
      Element ole = firstDescendant(element, "OLEObject");
      String progId = ole == null ? "" : ole.getAttribute("ProgID");
      if (progId.equalsIgnoreCase("Equation.3") || progId.toLowerCase(java.util.Locale.ROOT).contains("mathtype")) {
        result.add(model(paragraphPath, "OLE", "嵌入公式对象", currentText));
      }
    }
    int index = 0;
    for (Element child : XmlPartSupport.children(element)) {
      var childPath = new ArrayList<>(path); childPath.add(index++);
      collect(child, List.copyOf(childPath), currentText, result);
    }
  }

  private EquationModel model(List<Integer> path, String type, String summary, String paragraphText) {
    Matcher matcher = NUMBER.matcher(paragraphText == null ? "" : paragraphText);
    return new EquationModel(new SourceAddress("word/document.xml", path, 0, summary.length()), type, summary,
        matcher.find() ? matcher.group(1) : null, isStandalone(paragraphText), !"OMML".equals(type));
  }

  private static boolean isStandalone(String paragraphText) {
    String text = paragraphText == null ? "" : paragraphText.trim();
    return text.isBlank() || NUMBER.matcher(text).matches();
  }

  private static List<Integer> findParagraphPath(Element element, List<Integer> path) {
    var result = new ArrayList<Integer>(path);
    Element current = element;
    while (current != null && !(XmlPartSupport.W.equals(current.getNamespaceURI()) && "p".equals(current.getLocalName()))) {
      if (!(current.getParentNode() instanceof Element parent)) return path;
      int index = 0;
      for (Element child : XmlPartSupport.children(parent)) {
        if (child == current) break;
        index++;
      }
      if (!result.isEmpty()) result.remove(result.size() - 1);
      current = parent;
    }
    return current == null ? path : result;
  }

  private static Element firstDescendant(Element root, String localName) {
    var nodes = root.getElementsByTagNameNS("urn:schemas-microsoft-com:office:office", localName);
    return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
  }

  private static String mathText(Element element) {
    var nodes = element.getElementsByTagNameNS(XmlPartSupport.M, "t");
    var text = new StringBuilder();
    for (int i = 0; i < nodes.getLength(); i++) text.append(nodes.item(i).getTextContent());
    return text.toString();
  }
}
