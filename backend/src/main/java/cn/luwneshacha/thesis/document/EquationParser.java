package cn.luwneshacha.thesis.document;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.w3c.dom.Element;

public final class EquationParser {
  private static final Pattern NUMBER = Pattern.compile("\\(((?:\\d+[\\-－]\\d+)|(?:[A-Z]\\d+))\\)");

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
    if (XmlPartSupport.M.equals(element.getNamespaceURI()) && "oMathPara".equals(element.getLocalName())) {
      String summary = mathText(element);
      result.add(model(element, paragraphPath, "OMML", summary, currentText,
          numberIndex(element, paragraphPath, currentText)));
    } else if (XmlPartSupport.M.equals(element.getNamespaceURI()) && "oMath".equals(element.getLocalName())
        && !(element.getParentNode() instanceof Element parent
            && XmlPartSupport.M.equals(parent.getNamespaceURI()) && "oMathPara".equals(parent.getLocalName()))) {
      String summary = mathText(element);
      result.add(model(element, paragraphPath, "OMML", summary, currentText,
          numberIndex(element, paragraphPath, currentText)));
    } else if (XmlPartSupport.W.equals(element.getNamespaceURI()) && "object".equals(element.getLocalName())) {
      Element ole = firstDescendant(element, "OLEObject");
      String progId = ole == null ? "" : ole.getAttribute("ProgID");
      if (progId.equalsIgnoreCase("Equation.3") || progId.toLowerCase(java.util.Locale.ROOT).contains("mathtype")) {
        result.add(model(element, paragraphPath, "OLE", "嵌入公式对象", currentText,
            numberIndex(element, paragraphPath, currentText)));
      }
    } else if (XmlPartSupport.W.equals(element.getNamespaceURI()) && "drawing".equals(element.getLocalName())
        && hasPicture(element) && NUMBER.matcher(currentText == null ? "" : currentText).find()) {
        result.add(model(element, paragraphPath, "PICTURE", "图片公式（内部结构不可解析）", currentText,
          numberIndex(element, paragraphPath, currentText)));
    }
    int index = 0;
    for (Element child : XmlPartSupport.children(element)) {
      var childPath = new ArrayList<>(path); childPath.add(index++);
      collect(child, List.copyOf(childPath), currentText, result);
    }
  }

  private EquationModel model(Element equation, List<Integer> path, String type, String summary,
      String paragraphText, int numberIndex) {
    Matcher matcher = NUMBER.matcher(paragraphText == null ? "" : paragraphText);
    String number = null;
    boolean standalone = isStandalone(paragraphText);
    if (standalone) {
      for (int index = 0; matcher.find(); index++) {
        if (index == numberIndex) {
          number = matcher.group(1);
          break;
        }
      }
    }
    if (!"OMML".equals(type)) return new EquationModel(new SourceAddress("word/document.xml", path, 0, summary.length()),
        type, summary, number, standalone, true);
    return new EquationModel(new SourceAddress("word/document.xml", path, 0, summary.length()), type, summary,
        number, standalone, false, nodeTypes(equation), fonts(equation), italicVariables(equation));
  }

  private static Set<String> nodeTypes(Element equation) {
    var result = new LinkedHashSet<String>();
    collectNodeTypes(equation, result);
    return result;
  }

  private static void collectNodeTypes(Element element, Set<String> result) {
    if (XmlPartSupport.M.equals(element.getNamespaceURI())) result.add(element.getLocalName());
    for (Element child : XmlPartSupport.children(element)) collectNodeTypes(child, result);
  }

  private static Set<String> fonts(Element equation) {
    var result = new LinkedHashSet<String>();
    var nodes = equation.getElementsByTagNameNS(XmlPartSupport.W, "rFonts");
    for (int index = 0; index < nodes.getLength(); index++) {
      Element node = (Element) nodes.item(index);
      for (String attribute : List.of("ascii", "hAnsi", "eastAsia", "cs")) {
        String value = node.getAttributeNS(XmlPartSupport.W, attribute);
        if (!value.isBlank()) result.add(value);
      }
    }
    return result;
  }

  private static Boolean italicVariables(Element equation) {
    var styles = equation.getElementsByTagNameNS(XmlPartSupport.M, "sty");
    Boolean result = null;
    for (int index = 0; index < styles.getLength(); index++) {
      String value = ((Element) styles.item(index)).getAttributeNS(XmlPartSupport.M, "val");
      if ("i".equalsIgnoreCase(value)) result = Boolean.TRUE;
      if ("p".equalsIgnoreCase(value)) result = Boolean.FALSE;
    }
    return result;
  }

  private int numberIndex(Element equation, List<Integer> paragraphPath, String paragraphText) {
    Element paragraph = equation.getOwnerDocument().getDocumentElement();
    for (int index : paragraphPath) paragraph = XmlPartSupport.children(paragraph).get(index);
    var units = new ArrayList<Element>();
    collectEquationUnits(paragraph, units, paragraphText);
    return units.indexOf(equation);
  }

  private void collectEquationUnits(Element element, List<Element> units, String paragraphText) {
    if (XmlPartSupport.M.equals(element.getNamespaceURI()) && "oMathPara".equals(element.getLocalName())) {
      units.add(element);
      return;
    }
    if (XmlPartSupport.M.equals(element.getNamespaceURI()) && "oMath".equals(element.getLocalName())) {
      if (!(element.getParentNode() instanceof Element parent
          && XmlPartSupport.M.equals(parent.getNamespaceURI()) && "oMathPara".equals(parent.getLocalName()))) {
        units.add(element);
      }
      return;
    }
    if (XmlPartSupport.W.equals(element.getNamespaceURI()) && "object".equals(element.getLocalName())) {
      Element ole = firstDescendant(element, "OLEObject");
      String progId = ole == null ? "" : ole.getAttribute("ProgID");
      if (progId.equalsIgnoreCase("Equation.3") || progId.toLowerCase(java.util.Locale.ROOT).contains("mathtype")) {
        units.add(element);
        return;
      }
    }
    if (XmlPartSupport.W.equals(element.getNamespaceURI()) && "drawing".equals(element.getLocalName())
        && hasPicture(element) && NUMBER.matcher(paragraphText == null ? "" : paragraphText).find()) {
      units.add(element);
      return;
    }
    for (Element child : XmlPartSupport.children(element)) collectEquationUnits(child, units, paragraphText);
  }

  private static boolean hasPicture(Element drawing) {
    return drawing.getElementsByTagNameNS("http://schemas.openxmlformats.org/drawingml/2006/picture", "pic").getLength() > 0;
  }

  private static boolean isStandalone(String paragraphText) {
    String text = paragraphText == null ? "" : NUMBER.matcher(paragraphText).replaceAll("").trim();
    return text.isBlank();
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
