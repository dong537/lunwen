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
  private static final String RELATIONSHIPS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
  private static final String CAPTION_SPACE = "[\\s\\p{Zs}\\u200B\\u200C\\u200D\\u2060\\uFEFF]";
  private static final Pattern CAPTION = Pattern.compile(
      "^" + CAPTION_SPACE + "*图(?=[0-9A-Z]|" + CAPTION_SPACE + ")");
  private static final Pattern CAPTION_NUMBER_ONLY = Pattern.compile(
      "^" + CAPTION_SPACE + "*图" + CAPTION_SPACE
          + "*(?:\\d+[-－]\\d+|[A-Z]\\d+)" + CAPTION_SPACE + "*$");
  private static final Pattern CAPTION_CHAPTER = Pattern.compile(
      "^" + CAPTION_SPACE + "*图" + CAPTION_SPACE + "*(\\d+)[-－]\\d+");

  public List<ImageModel> parse(byte[] xml) {
    return parse(xml, null);
  }

  public List<ImageModel> parse(byte[] xml, byte[] stylesXml) {
    return parseInventory(xml, stylesXml).images();
  }

  public record Inventory(List<ImageModel> images, boolean complete) {
    public Inventory { images = List.copyOf(images); }
  }

  public Inventory parseInventory(byte[] xml, byte[] stylesXml) {
    try {
      var result = new ArrayList<ImageModel>();
      Element styles = stylesXml == null ? null : XmlPartSupport.parse(stylesXml).getDocumentElement();
      var document = XmlPartSupport.parse(xml);
      boolean[] complete = {true};
      collect(document.getDocumentElement(), styles, List.of(), result, complete);
      return new Inventory(result, complete[0] && XmlPartSupport.compatibilityGaps(document).isEmpty());
    } catch (Exception e) {
      throw new IllegalArgumentException("unable to parse images", e);
    }
  }

  private void collect(Element element, Element styles, List<Integer> path, List<ImageModel> result,
      boolean[] complete) {
    if (excludedFromCurrentDocument(element)) return;
    boolean wordElement = XmlPartSupport.W.equals(element.getNamespaceURI());
    boolean drawing = wordElement && "drawing".equals(element.getLocalName());
    boolean pict = wordElement && "pict".equals(element.getLocalName());
    boolean object = wordElement && "object".equals(element.getLocalName());
    boolean drawingPicture = drawing && first(element, PICTURE, "pic") != null;
    boolean drawingChart = drawing && first(element, CHART, "chart") != null;
    boolean vmlMedia = (pict || object) && hasVmlImageMediaReference(element);
    boolean vmlPicture = pict && vmlMedia;
    boolean objectPreview = object && (vmlMedia
        || first(element, PICTURE, "pic") != null || first(element, CHART, "chart") != null);
    // Visio may place its VML preview directly inside w:object. Wrapped
    // previews are collected from their pict/drawing node to avoid duplicates.
    boolean directVmlObject = object && vmlMedia
        && element.getElementsByTagNameNS(XmlPartSupport.W, "pict").getLength() == 0
        && element.getElementsByTagNameNS(XmlPartSupport.W, "drawing").getLength() == 0;
    boolean equationPreview = (drawing || pict || object) && isEquationPreview(element);
    // A shape, SmartArt object or OLE body can consume a page even when it
    // cannot be represented as an ImageModel. Do not certify a complete image
    // inventory merely because every recognized picture was parsed.
    if ((drawing || pict || object)
        && !(drawingPicture || drawingChart || vmlPicture || objectPreview || equationPreview))
      complete[0] = false;
    if ((drawingPicture || drawingChart || vmlPicture || directVmlObject) && !equationPreview) {
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
            transformWidthValue.invalid() || transformHeightValue.invalid(),
            caption != null && caption.fragment(), caption != null && caption.numberAmbiguous(),
            caption == null ? List.of() : caption.candidateChapters()));
      } else complete[0] = false;
    }
    int index = 0;
    for (Element child : XmlPartSupport.children(element)) {
      var childPath = new ArrayList<>(path);
      childPath.add(index++);
      collect(child, styles, List.copyOf(childPath), result, complete);
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
      String text = paragraphOwnText(next);
      if (CAPTION.matcher(text).find())
        return new Caption(text, pathOf(next), next,
            hasTextBoxes(next) && CAPTION_NUMBER_ONLY.matcher(text).matches(), false, List.of());
      if (!XmlPartSupport.isBlankText(text)) return null;
      return textBoxCaption(next);
    }
    return null;
  }

  private static Caption textBoxCaption(Element outerParagraph) {
    Caption candidate = null;
    boolean ambiguous = false;
    var chapters = new ArrayList<String>();
    var boxes = outerParagraph.getElementsByTagNameNS(XmlPartSupport.W, "txbxContent");
    for (int boxIndex = 0; boxIndex < boxes.getLength(); boxIndex++) {
      Element box = (Element) boxes.item(boxIndex);
      if (insideExcludedRevision(box, outerParagraph)) continue;
      var paragraphs = box.getElementsByTagNameNS(XmlPartSupport.W, "p");
      for (int paragraphIndex = 0; paragraphIndex < paragraphs.getLength(); paragraphIndex++) {
        Element innerParagraph = (Element) paragraphs.item(paragraphIndex);
        if (insideExcludedRevision(innerParagraph, box)) continue;
        String text = paragraphOwnText(innerParagraph);
        if (!CAPTION.matcher(text).find()) continue;
        var chapter = CAPTION_CHAPTER.matcher(text);
        if (chapter.find() && !chapters.contains(chapter.group(1))) chapters.add(chapter.group(1));
        if (candidate != null) {
          ambiguous = true;
          continue;
        }
        candidate = new Caption(text, pathOf(innerParagraph), innerParagraph,
            CAPTION_NUMBER_ONLY.matcher(text).matches(), false, List.of());
      }
    }
    return candidate == null || !ambiguous ? candidate
        : new Caption(candidate.text(), candidate.path(), candidate.paragraph(), true, true, chapters);
  }

  private static boolean hasTextBoxes(Element paragraph) {
    return hasVisibleElement(paragraph, "txbxContent");
  }

  private static String paragraphOwnText(Element paragraph) {
    StringBuilder text = new StringBuilder();
    appendOwnText(paragraph, paragraph, text);
    return text.toString();
  }

  private static void appendOwnText(Element root, Element element, StringBuilder text) {
    if (excludedFromCurrentDocument(element)) return;
    if (element != root && XmlPartSupport.W.equals(element.getNamespaceURI())) {
      String local = element.getLocalName();
      if ("p".equals(local) || "pict".equals(local) || "drawing".equals(local)
          || "object".equals(local) || "txbxContent".equals(local)) return;
      if ("t".equals(local)) {
        text.append(element.getTextContent());
        return;
      }
    }
    for (Element child : XmlPartSupport.children(element)) appendOwnText(root, child, text);
  }

  private static boolean hasVmlImageMediaReference(Element element) {
    var images = element.getElementsByTagNameNS(VML, "imagedata");
    for (int index = 0; index < images.getLength(); index++) {
      Element image = (Element) images.item(index);
      if (!image.getAttributeNS(RELATIONSHIPS, "id").isBlank()
          || !image.getAttributeNS(OFFICE, "relid").isBlank()
          || !image.getAttribute("src").isBlank()) return true;
    }
    return false;
  }

  private static boolean isIgnorableBetween(Element element) {
    if (!XmlPartSupport.W.equals(element.getNamespaceURI())) return true;
    String local = element.getLocalName();
    if ("bookmarkStart".equals(local) || "bookmarkEnd".equals(local)
        || "proofErr".equals(local) || "permStart".equals(local) || "permEnd".equals(local)) return true;
    return "p".equals(local) && XmlPartSupport.isBlankText(paragraphOwnText(element))
        && !hasVisibleElement(element, "drawing")
        && !hasVisibleElement(element, "pict")
        && !hasVisibleElement(element, "object");
  }

  private static boolean hasVisibleElement(Element root, String localName) {
    var nodes = root.getElementsByTagNameNS(XmlPartSupport.W, localName);
    for (int index = 0; index < nodes.getLength(); index++) {
      if (!insideExcludedRevision((Element) nodes.item(index), root)) return true;
    }
    return false;
  }

  private static boolean insideExcludedRevision(Element element, Element boundary) {
    Node node = element;
    while (node instanceof Element current && current != boundary) {
      if (excludedFromCurrentDocument(current)) return true;
      node = current.getParentNode();
    }
    return false;
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

  private static boolean excludedFromCurrentDocument(Element element) {
    if (!XmlPartSupport.W.equals(element.getNamespaceURI())) return false;
    String localName = element.getLocalName();
    return "del".equals(localName) || "moveFrom".equals(localName)
        || "delInstrText".equals(localName) || localName.endsWith("PrChange");
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

  private record Caption(String text, List<Integer> path, Element paragraph,
      boolean fragment, boolean numberAmbiguous, List<String> candidateChapters) {}
}
