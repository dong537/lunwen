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
    if (XmlPartSupport.M.equals(element.getNamespaceURI()) && "oMath".equals(element.getLocalName())) {
      String summary = mathText(element);
      result.add(model(path, "OMML", summary, currentText));
    } else if (XmlPartSupport.W.equals(element.getNamespaceURI()) && "object".equals(element.getLocalName())) {
      result.add(model(path, "OLE", "嵌入公式对象", currentText));
    } else if (XmlPartSupport.W.equals(element.getNamespaceURI()) && "drawing".equals(element.getLocalName())
        && element.getElementsByTagNameNS("http://schemas.openxmlformats.org/drawingml/2006/main", "graphic").getLength() > 0) {
      result.add(model(path, "IMAGE", "图片公式对象", currentText));
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
        matcher.find() ? matcher.group(1) : null, true, !"OMML".equals(type));
  }

  private static String mathText(Element element) {
    var nodes = element.getElementsByTagNameNS(XmlPartSupport.M, "t");
    var text = new StringBuilder();
    for (int i = 0; i < nodes.getLength(); i++) text.append(nodes.item(i).getTextContent());
    return text.toString();
  }
}
