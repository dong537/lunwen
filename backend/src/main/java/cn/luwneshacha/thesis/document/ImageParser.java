package cn.luwneshacha.thesis.document;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;
import java.math.BigInteger;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

public final class ImageParser {
  private static final String DRAW = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing";
  private static final String DRAW_MAIN = "http://schemas.openxmlformats.org/drawingml/2006/main";
  private static final String PICTURE = "http://schemas.openxmlformats.org/drawingml/2006/picture";
  private static final String CHART = "http://schemas.openxmlformats.org/drawingml/2006/chart";
  private static final String VML = "urn:schemas-microsoft-com:vml";
  private static final String OFFICE = "urn:schemas-microsoft-com:office:office";
  private static final Pattern CAPTION = Pattern.compile(
      "^[\\s\\p{Zs}\\u200B\\u200C\\u200D\\u2060\\uFEFF]*图(?=[0-9A-Z\\s\\p{Zs}])");

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
    boolean drawingChart = XmlPartSupport.W.equals(element.getNamespaceURI())
        && "drawing".equals(element.getLocalName()) && first(element, CHART, "chart") != null;
    boolean vmlPicture = XmlPartSupport.W.equals(element.getNamespaceURI()) && "pict".equals(element.getLocalName())
        && element.getElementsByTagNameNS(VML, "imagedata").getLength() > 0;
    if ((drawingPicture || drawingChart || vmlPicture) && !isEquationPreview(element)) {
      Element paragraph = ancestor(element, XmlPartSupport.W, "p");
      if (paragraph != null) {
        Element extent = drawingPicture || drawingChart ? first(element, DRAW, "extent") : null;
        NumericSize widthValue = extent == null ? NumericSize.missing() : number(extent, "cx");
        NumericSize heightValue = extent == null ? NumericSize.missing() : number(extent, "cy");
        NumericSize transformWidthValue = transformSize(element, "cx");
        NumericSize transformHeightValue = transformSize(element, "cy");
        Caption caption = followingCaption(paragraph, styles);
        result.add(new ImageModel(
            new SourceAddress("word/document.xml", pathOf(paragraph), 0, 0),
            caption == null ? null : new SourceAddress("word/document.xml", caption.path(), 0, caption.text().length()),
            caption == null ? null : caption.text(), widthValue.value(), heightValue.value(),
            element.getElementsByTagNameNS(DRAW, "inline").getLength() > 0,
            centered(paragraph, styles), caption == null ? null : centered(caption.paragraph(), styles),
            transformWidthValue.value(), transformHeightValue.value(),
            widthValue.invalid() || heightValue.invalid(),
            transformWidthValue.invalid() || transformHeightValue.invalid()));
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
    if (index < 0) return null;
    for (int i = index + 1; i < siblings.size(); i++) {
      Element next = siblings.get(i);
      if (isIgnorableBetween(next)) continue;
      if (!XmlPartSupport.W.equals(next.getNamespaceURI()) || !"p".equals(next.getLocalName())) return null;
      String text = XmlPartSupport.text(next);
      return CAPTION.matcher(text).find() ? new Caption(text, pathOf(next), next) : null;
    }
    return null;
  }

  private static boolean isIgnorableBetween(Element element) {
    if (!XmlPartSupport.W.equals(element.getNamespaceURI())) return true;
    String local = element.getLocalName();
    if ("bookmarkStart".equals(local) || "bookmarkEnd".equals(local)
        || "proofErr".equals(local) || "permStart".equals(local) || "permEnd".equals(local)) return true;
    return "p".equals(local) && XmlPartSupport.isBlankText(XmlPartSupport.text(element))
        && element.getElementsByTagNameNS(XmlPartSupport.W, "drawing").getLength() == 0
        && element.getElementsByTagNameNS(XmlPartSupport.W, "pict").getLength() == 0;
  }

  private static Boolean centered(Element paragraph, Element styles) {
    Element pPr = direct(paragraph, XmlPartSupport.W, "pPr");
    Element jc = pPr == null ? null : direct(pPr, XmlPartSupport.W, "jc");
    if (jc != null) return "center".equals(XmlAlignment.parse(jc));

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
        if (styleJc != null) return "center".equals(XmlAlignment.parse(styleJc));
        Element basedOn = direct(style, XmlPartSupport.W, "basedOn");
        styleId = basedOn == null ? null : basedOn.getAttributeNS(XmlPartSupport.W, "val");
      }
      if (styleId != null) return null;
    }

    Element defaults = styles == null ? null : direct(styles, XmlPartSupport.W, "docDefaults");
    Element defaultPPr = defaults == null ? null : direct(defaults, XmlPartSupport.W, "pPrDefault");
    Element defaultJc = defaultPPr == null ? null : direct(defaultPPr, XmlPartSupport.W, "jc");
    return defaultJc != null && "center".equals(XmlAlignment.parse(defaultJc));
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

  private static boolean isEquationPreview(Element candidate) {
    Element owner = ancestorOrSelf(candidate, XmlPartSupport.W, "object");
    if (owner != null && containsEquationOleObject(owner)) return true;
    Element drawing = ancestorOrSelf(candidate, XmlPartSupport.W, "drawing");
    return drawing != null && containsEquationOleObject(drawing);
  }

  private static boolean containsEquationOleObject(Element container) {
    var objects = container.getElementsByTagNameNS("*", "OLEObject");
    for (int index = 0; index < objects.getLength(); index++) {
      Element oleObject = (Element) objects.item(index);
      String progId = oleObject.getAttribute("ProgID");
      if (progId.isBlank()) progId = oleObject.getAttributeNS(OFFICE, "ProgID");
      if (isEquationProgId(progId)) return true;
    }
    var drawingOleObjects = container.getElementsByTagNameNS("*", "oleObj");
    for (int index = 0; index < drawingOleObjects.getLength(); index++) {
      String progId = ((Element) drawingOleObjects.item(index)).getAttribute("progId");
      if (isEquationProgId(progId)) return true;
    }
    return false;
  }

  private static boolean isEquationProgId(String progId) {
    if (progId == null || progId.isBlank()) return false;
    String normalized = progId.toLowerCase(java.util.Locale.ROOT);
    return normalized.startsWith("equation") || normalized.contains("mathtype")
        || normalized.contains("mathcad") || normalized.contains("wps.math");
  }

  private static Element ancestorOrSelf(Element element, String namespace, String localName) {
    if (namespace.equals(element.getNamespaceURI()) && localName.equals(element.getLocalName())) return element;
    return ancestor(element, namespace, localName);
  }

  private static NumericSize number(Element element, String attribute) {
    if (element == null || !element.hasAttribute(attribute)) return NumericSize.missing();
    String value = element.getAttribute(attribute);
    try {
      BigInteger parsed = new BigInteger(value.trim());
      return parsed.signum() > 0 && parsed.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) <= 0
          ? new NumericSize(parsed.longValue(), false)
          : new NumericSize(0, true);
    } catch (NumberFormatException ignored) {
      return new NumericSize(0, true);
    }
  }

  private static NumericSize transformSize(Element drawing, String attribute) {
    var transforms = drawing.getElementsByTagNameNS(DRAW_MAIN, "xfrm");
    if (transforms.getLength() == 0) return NumericSize.missing();
    var extents = ((Element) transforms.item(0)).getElementsByTagNameNS(DRAW_MAIN, "ext");
    if (extents.getLength() == 0) return NumericSize.missing();
    return number((Element) extents.item(0), attribute);
  }

  private record NumericSize(long value, boolean invalid) {
    private static NumericSize missing() { return new NumericSize(0, false); }
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
