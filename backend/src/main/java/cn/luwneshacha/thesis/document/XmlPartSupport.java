package cn.luwneshacha.thesis.document;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
  private static final String MC = "http://schemas.openxmlformats.org/markup-compatibility/2006";
  static final String STRICT_W = "http://purl.oclc.org/ooxml/wordprocessingml/main";
  static final String STRICT_M = "http://purl.oclc.org/ooxml/officeDocument/math";
  private static final String DRAW = "http://schemas.openxmlformats.org/drawingml/2006/main";
  private static final String PICTURE = "http://schemas.openxmlformats.org/drawingml/2006/picture";
  private static final String WORD_DRAW = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing";
  private static final String STRICT_DRAW = "http://purl.oclc.org/ooxml/drawingml/main";
  private static final String STRICT_PICTURE = "http://purl.oclc.org/ooxml/drawingml/picture";
  private static final String STRICT_WORD_DRAW = "http://purl.oclc.org/ooxml/drawingml/wordprocessingDrawing";
  private static final String WPS = "http://schemas.microsoft.com/office/word/2010/wordprocessingShape";
  private static final String WPG = "http://schemas.microsoft.com/office/word/2010/wordprocessingGroup";
  private static final String W14_DRAW = "http://schemas.microsoft.com/office/word/2010/wordprocessingDrawing";
  private static final String VML = "urn:schemas-microsoft-com:vml";
  private static final String WORD10 = "urn:schemas-microsoft-com:office:word";
  private static final Map<String, Set<String>> PURE_GRAPHIC_ELEMENTS = Map.of(
      W, Set.of("drawing", "pict"),
      WORD_DRAW, Set.of("anchor", "cNvGraphicFramePr", "docPr", "effectExtent", "extent",
          "posOffset", "positionH", "positionV", "simplePos", "wrapNone"),
      DRAW, Set.of("avLst", "chExt", "chOff", "effectRef", "ext", "fillRef",
          "fontRef", "graphic", "graphicData", "headEnd", "ln", "lnRef", "off",
          "prstDash", "prstGeom", "schemeClr", "solidFill", "srgbClr", "tailEnd", "xfrm"),
      WPS, Set.of("bodyPr", "cNvCnPr", "cNvPr", "cNvSpPr", "spPr", "style", "wsp"),
      WPG, Set.of("cNvGrpSpPr", "grpSpPr", "wgp"),
      W14_DRAW, Set.of("pctHeight", "pctWidth", "sizeRelH", "sizeRelV"),
      VML, Set.of("fill", "group", "imagedata", "line", "stroke"),
      WORD10, Set.of("wrap"),
      O, Set.of("lock"));
  private static final String COMPATIBILITY_GAPS = XmlPartSupport.class.getName() + ".compatibilityGaps";
  private static final Set<String> SUPPORTED_CHOICE_NAMESPACES = Set.of(
      W, M, O, DRAW, PICTURE, WORD_DRAW,
      "http://schemas.openxmlformats.org/drawingml/2006/chart",
      "urn:schemas-microsoft-com:vml");
  private XmlPartSupport() {}

  /** A branch whose visible form cannot be proved from the source XML alone. */
  public record CompatibilityGap(List<Integer> elementPath, String reason,
                                 boolean textBoxOnly, boolean graphicOnly) {
    public CompatibilityGap(List<Integer> elementPath, String reason) {
      this(elementPath, reason, false, false);
    }

    public CompatibilityGap {
      elementPath = List.copyOf(elementPath);
      Objects.requireNonNull(reason, "reason");
      if (textBoxOnly && graphicOnly) throw new IllegalArgumentException("ambiguous compatibility gap kind");
    }
  }

  public static Document parse(byte[] bytes) throws Exception {
    Document document = parseWithoutCompatibilitySelection(bytes);
    var gaps = new ArrayList<CompatibilityGap>();
    selectCompatibilityBranches(document.getDocumentElement(), List.of(), gaps);
    document.setUserData(COMPATIBILITY_GAPS, List.copyOf(gaps), null);
    return document;
  }

  /** Read the source XML without discarding alternative content needed for evidence checks. */
  public static Document parseWithoutCompatibilitySelection(byte[] bytes) throws Exception {
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

  public static List<CompatibilityGap> compatibilityGaps(Document document) {
    Objects.requireNonNull(document, "document");
    Object value = document.getUserData(COMPATIBILITY_GAPS);
    if (value == null) return List.of();
    if (!(value instanceof List<?> entries))
      throw new IllegalStateException("invalid compatibility gap metadata");
    var result = new ArrayList<CompatibilityGap>();
    for (Object entry : entries) {
      if (!(entry instanceof CompatibilityGap gap))
        throw new IllegalStateException("invalid compatibility gap entry");
      result.add(gap);
    }
    return List.copyOf(result);
  }

  private static void selectCompatibilityBranches(Element element, List<Integer> path,
      List<CompatibilityGap> gaps) {
    // Deleted text and former paragraph properties do not participate in the
    // visible document. Their unused alternatives must not create gaps.
    if (W.equals(element.getNamespaceURI()) && ("del".equals(element.getLocalName())
        || "moveFrom".equals(element.getLocalName())
        || "delInstrText".equals(element.getLocalName())
        || element.getLocalName().endsWith("PrChange"))) return;
    if (isAlternateContent(element)) chooseBranch(element, path, gaps);
    int index = 0;
    for (Element child : children(element)) {
      var childPath = new ArrayList<>(path);
      childPath.add(index++);
      selectCompatibilityBranches(child, List.copyOf(childPath), gaps);
    }
  }

  private static void chooseBranch(Element alternate, List<Integer> path,
      List<CompatibilityGap> gaps) {
    List<Element> branches = children(alternate);
    Element selected = null;
    Element fallback = null;
    boolean earlierUnknownChoice = false;
    boolean malformed = false;
    boolean sawFallback = false;
    int choiceCount = 0;
    for (Element branch : branches) {
      if (!MC.equals(branch.getNamespaceURI())) {
        malformed = true;
        continue;
      }
      if ("Choice".equals(branch.getLocalName())) {
        choiceCount++;
        if (sawFallback) malformed = true;
        if (selected != null) continue;
        if (supportedChoice(branch)) selected = branch;
        else earlierUnknownChoice = true;
      } else if ("Fallback".equals(branch.getLocalName())) {
        if (fallback != null) malformed = true;
        else fallback = branch;
        sawFallback = true;
      } else malformed = true;
    }
    if (choiceCount == 0) malformed = true;
    if (selected == null) selected = fallback;
    if (malformed || earlierUnknownChoice || selected == null) {
      String reason = selected == null ? "兼容内容没有可确定的显示分支"
          : malformed ? "兼容内容的分支结构不完整，无法确认实际显示内容"
          : "兼容内容有更早的版本分支，无法确认 Word 或 WPS 实际显示哪一份内容";
      gaps.add(new CompatibilityGap(path, reason, isTextBoxOnlyAlternative(alternate),
          isGraphicOnlyAlternative(alternate)));
      // A fallback or later choice is only our local interpretation. When a
      // newer client may display an earlier choice, judging this candidate
      // would produce a false hard error, so suppress the whole alternative.
      selected = null;
    }
    for (Element branch : branches) {
      if (branch == selected) continue;
      if (!MC.equals(branch.getNamespaceURI())
          || !("Choice".equals(branch.getLocalName()) || "Fallback".equals(branch.getLocalName()))) {
        // Keep an element at this position so source paths of later siblings
        // still refer to the original ZIP XML.
        alternate.replaceChild(alternate.getOwnerDocument().createElementNS(MC, "mc:Ignored"), branch);
      } else {
        while (branch.hasChildNodes()) branch.removeChild(branch.getFirstChild());
      }
    }
  }

  private static boolean supportedChoice(Element choice) {
    String requires = choice.getAttribute("Requires");
    if (requires == null || requires.isBlank()) return false;
    for (String prefix : requires.trim().split("\\s+")) {
      String namespace = choice.lookupNamespaceURI(prefix);
      if (namespace == null || !SUPPORTED_CHOICE_NAMESPACES.contains(namespace)) return false;
    }
    return true;
  }

  /** A drawing may contain alternative text-box bodies without changing its outer paragraph text. */
  static boolean isTextBoxOnlyAlternative(Element alternate) {
    if (!isAlternateContent(alternate)) return false;
    boolean inRun = false;
    boolean inParagraph = false;
    for (Node node = alternate.getParentNode(); node instanceof Element ancestor; node = node.getParentNode()) {
      if (!W.equals(ancestor.getNamespaceURI())) continue;
      if (ancestor.getLocalName().endsWith("Pr")) return false;
      if ("r".equals(ancestor.getLocalName())) inRun = true;
      if ("p".equals(ancestor.getLocalName())) inParagraph = true;
    }
    if (!inRun || !inParagraph) return false;
    boolean sawBranch = false;
    for (Element branch : children(alternate)) {
      if (!MC.equals(branch.getNamespaceURI())
          || !("Choice".equals(branch.getLocalName()) || "Fallback".equals(branch.getLocalName())))
        return false;
      sawBranch = true;
      if (branch.getElementsByTagNameNS(W, "txbxContent").getLength() == 0
          || hasNonTextBoxWordContent(branch)) return false;
    }
    return sawBranch;
  }

  /** A known graphical payload cannot change the ordinary text in its enclosing paragraph. */
  static boolean isGraphicOnlyAlternative(Element alternate) {
    if (!isAlternateContent(alternate) || !(alternate.getParentNode() instanceof Element run)
        || !W.equals(run.getNamespaceURI()) || !"r".equals(run.getLocalName())
        || !(run.getParentNode() instanceof Element paragraph)
        || !W.equals(paragraph.getNamespaceURI()) || !"p".equals(paragraph.getLocalName())
        || !onlyWhitespaceAroundElements(alternate)) return false;
    int choices = 0;
    boolean fallback = false;
    for (Element branch : children(alternate)) {
      if (!MC.equals(branch.getNamespaceURI()) || !onlyWhitespaceAroundElements(branch)) return false;
      List<Element> payload = children(branch);
      if (payload.size() != 1) return false;
      Element graphic = payload.get(0);
      if ("Choice".equals(branch.getLocalName()) && !fallback
          && !branch.getAttribute("Requires").isBlank()) {
        choices++;
        if (!hasOnlyChild(graphic, W, "drawing", WORD_DRAW, "anchor")
            || graphic.getElementsByTagNameNS(DRAW, "graphic").getLength() != 1
            || graphic.getElementsByTagNameNS(DRAW, "graphicData").getLength() != 1
            || !hasSupportedShapePayload(graphic)) return false;
      } else if ("Fallback".equals(branch.getLocalName()) && !fallback) {
        fallback = true;
        if (!hasSupportedVmlPayload(graphic)) return false;
      } else return false;
      if (!pureGraphicTree(graphic)) return false;
    }
    return choices > 0 && fallback;
  }

  private static boolean hasSupportedShapePayload(Element drawing) {
    Element graphicData = (Element) drawing.getElementsByTagNameNS(DRAW, "graphicData").item(0);
    List<Element> payload = children(graphicData);
    if (payload.size() != 1) return false;
    Element shape = payload.get(0);
    if (WPS.equals(shape.getNamespaceURI()) && "wsp".equals(shape.getLocalName())) return true;
    return WPG.equals(shape.getNamespaceURI()) && "wgp".equals(shape.getLocalName())
        && shape.getElementsByTagNameNS(WPS, "wsp").getLength() > 0;
  }

  private static boolean hasSupportedVmlPayload(Element pict) {
    if (!W.equals(pict.getNamespaceURI()) || !"pict".equals(pict.getLocalName())) return false;
    List<Element> payload = children(pict);
    if (payload.size() != 1) return false;
    Element shape = payload.get(0);
    if (VML.equals(shape.getNamespaceURI()) && "line".equals(shape.getLocalName())) return true;
    return VML.equals(shape.getNamespaceURI()) && "group".equals(shape.getLocalName())
        && shape.getElementsByTagNameNS(VML, "line").getLength() > 0;
  }

  private static boolean hasOnlyChild(Element element, String namespace, String localName,
      String childNamespace, String childName) {
    List<Element> children = children(element);
    return namespace.equals(element.getNamespaceURI()) && localName.equals(element.getLocalName())
        && children.size() == 1 && childNamespace.equals(children.get(0).getNamespaceURI())
        && childName.equals(children.get(0).getLocalName());
  }

  private static boolean pureGraphicTree(Element element) {
    if (!PURE_GRAPHIC_ELEMENTS.getOrDefault(element.getNamespaceURI(), Set.of())
        .contains(element.getLocalName())) return false;
    for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
      if (child instanceof Element nested) {
        if (!pureGraphicTree(nested)) return false;
      } else if ((child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE)
          && !child.getTextContent().isBlank()
          && !geometryNumber(element, child.getTextContent())) return false;
      else if (child.getNodeType() != Node.TEXT_NODE && child.getNodeType() != Node.CDATA_SECTION_NODE
          && child.getNodeType() != Node.COMMENT_NODE) return false;
    }
    return true;
  }

  private static boolean onlyWhitespaceAroundElements(Element element) {
    for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
      if (child instanceof Element || child.getNodeType() == Node.COMMENT_NODE) continue;
      if ((child.getNodeType() != Node.TEXT_NODE && child.getNodeType() != Node.CDATA_SECTION_NODE)
          || !child.getTextContent().isBlank()) return false;
    }
    return true;
  }

  private static boolean geometryNumber(Element element, String content) {
    String namespace = element.getNamespaceURI();
    String name = element.getLocalName();
    return ((WORD_DRAW.equals(namespace) && "posOffset".equals(name))
        || (W14_DRAW.equals(namespace) && ("pctWidth".equals(name) || "pctHeight".equals(name))))
        && content.strip().matches("-?[0-9]+");
  }

  private static boolean hasNonTextBoxWordContent(Element element) {
    if (W.equals(element.getNamespaceURI())) {
      if ("txbxContent".equals(element.getLocalName())) return false;
      if (!Set.of("drawing", "pict", "r").contains(element.getLocalName())) return true;
    }
    if (M.equals(element.getNamespaceURI())) return true;
    for (Element child : children(element))
      if (hasNonTextBoxWordContent(child)) return true;
    return false;
  }

  private static boolean isAlternateContent(Element element) {
    return MC.equals(element.getNamespaceURI())
        && "AlternateContent".equals(element.getLocalName());
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
