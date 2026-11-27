package cn.luwneshacha.thesis.document;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.w3c.dom.Element;

/** Distinguishes positive header text from empty content and unresolved display objects. */
final class HeaderContentAnalyzer {
  private static final String W = XmlPartSupport.W;
  private final Element styles;

  HeaderContentAnalyzer(Element styles) { this.styles = styles; }

  HeaderModel.DisplayContent analyze(Element header) {
    HeaderModel.DisplayContent content = scan(header, null, false, new FieldState());
    if (content == HeaderModel.DisplayContent.EMPTY
        && !XmlPartSupport.compatibilityGaps(header.getOwnerDocument()).isEmpty())
      return HeaderModel.DisplayContent.UNKNOWN;
    return content;
  }

  private HeaderModel.DisplayContent scan(Element element, Element paragraph, boolean uncertain, FieldState field) {
    String name = element.getLocalName();
    if (W.equals(element.getNamespaceURI())) {
      if (Set.of("del", "moveFrom", "delText", "delInstrText").contains(name) || name.endsWith("PrChange"))
        return HeaderModel.DisplayContent.EMPTY;
      if (name.equals("p")) {
        paragraph = element;
        uncertain |= paragraphAppearanceUnknown(paragraph);
      }
      if (name.equals("r")) uncertain |= runAppearanceUnknown(element, paragraph);
      if (Set.of("pPr", "rPr", "sdtPr", "bookmarkStart", "bookmarkEnd", "proofErr",
          "commentRangeStart", "commentRangeEnd").contains(name)) return HeaderModel.DisplayContent.EMPTY;
      if (name.equals("fldChar")) {
        String type = element.getAttributeNS(W, "fldCharType");
        if (type.equals("begin")) field.depth++;
        else if (type.equals("end") && field.depth > 0) field.depth--;
        return HeaderModel.DisplayContent.UNKNOWN;
      }
      if (name.equals("t")) return XmlPartSupport.isBlankText(element.getTextContent())
          ? HeaderModel.DisplayContent.EMPTY
          : uncertain || field.depth > 0 ? HeaderModel.DisplayContent.UNKNOWN : HeaderModel.DisplayContent.PRESENT;
      // Table style visibility and borders are not resolved by this analyzer.
      if (Set.of("drawing", "pict", "object", "tbl", "fldSimple", "instrText", "sym", "altChunk",
          "contentPart", "subDoc", "footnoteReference", "endnoteReference").contains(name))
        return HeaderModel.DisplayContent.UNKNOWN;
      if (Set.of("tab", "br", "cr", "lastRenderedPageBreak", "commentReference", "permStart", "permEnd",
          "moveFromRangeStart", "moveFromRangeEnd", "moveToRangeStart", "moveToRangeEnd").contains(name))
        return HeaderModel.DisplayContent.EMPTY;
      if (!Set.of("hdr", "p", "r", "hyperlink", "sdt", "sdtContent", "customXml", "smartTag",
          "ins", "moveTo", "dir", "bdo").contains(name)) return HeaderModel.DisplayContent.UNKNOWN;
    } else return HeaderModel.DisplayContent.UNKNOWN;
    HeaderModel.DisplayContent result = uncertain ? HeaderModel.DisplayContent.UNKNOWN
        : HeaderModel.DisplayContent.EMPTY;
    for (Element child : XmlPartSupport.children(element)) {
      HeaderModel.DisplayContent nested = scan(child, paragraph, uncertain, field);
      if (nested == HeaderModel.DisplayContent.PRESENT) return nested;
      if (nested == HeaderModel.DisplayContent.UNKNOWN) result = nested;
    }
    return result;
  }

  private boolean paragraphAppearanceUnknown(Element paragraph) {
    PropertyEvidence evidence = properties(paragraph, null, "pPr");
    if (evidence.unresolved()) return true;
    List<Element> properties = evidence.values();
    for (String side : List.of("top", "left", "bottom", "right", "between", "bar")) {
      for (Element property : properties) {
        Element border = child(child(property, "pBdr"), side);
        if (border == null) continue;
        if (!Set.of("none", "nil").contains(border.getAttributeNS(W, "val"))) return true;
        break;
      }
    }
    return properties.stream().anyMatch(p -> child(p, "numPr") != null || child(p, "shd") != null
        || child(p, "framePr") != null);
  }

  private boolean runAppearanceUnknown(Element run, Element paragraph) {
    PropertyEvidence evidence = properties(paragraph, run, "rPr");
    if (evidence.unresolved()) return true;
    for (Element property : evidence.values()) {
      for (String flag : List.of("vanish", "webHidden", "specVanish"))
        if (XmlOnOff.enabled(child(property, flag))) return true;
      if (child(property, "shd") != null || child(property, "bdr") != null) return true;
      Element highlight = child(property, "highlight");
      Element underline = child(property, "u");
      if (highlight != null && !"none".equals(highlight.getAttributeNS(W, "val"))
          || underline != null && !"none".equals(underline.getAttributeNS(W, "val"))
          || XmlOnOff.enabled(child(property, "strike")) || XmlOnOff.enabled(child(property, "dstrike"))) return true;
    }
    return false;
  }

  private PropertyEvidence properties(Element paragraph, Element run, String kind) {
    var result = new ArrayList<Element>();
    if (run != null) add(result, child(run, kind));
    if (paragraph != null && kind.equals("pPr")) add(result, child(paragraph, kind));
    String runStyle = value(child(child(run, "rPr"), "rStyle"));
    String paragraphStyle = value(child(child(paragraph, "pPr"), "pStyle"));
    if (styles == null) return new PropertyEvidence(result, runStyle != null || paragraphStyle != null);
    boolean unresolved = !addStyleProperties(result, runStyle, kind)
        || !XmlPartSupport.compatibilityGaps(styles.getOwnerDocument()).isEmpty();
    if (paragraphStyle == null) {
      for (Element style : XmlPartSupport.children(styles))
        if ("style".equals(style.getLocalName()) && "paragraph".equals(style.getAttributeNS(W, "type"))
            && XmlOnOff.attribute(style, "default", false)) {
          paragraphStyle = style.getAttributeNS(W, "styleId");
          break;
        }
    }
    unresolved |= !addStyleProperties(result, paragraphStyle, kind);
    add(result, child(child(child(styles, "docDefaults"), kind + "Default"), kind));
    return new PropertyEvidence(result, unresolved);
  }

  private boolean addStyleProperties(List<Element> result, String id, String kind) {
    var visited = new HashSet<String>();
    while (id != null) {
      if (!visited.add(id)) return false;
      Element found = null;
      for (Element style : XmlPartSupport.children(styles))
        if ("style".equals(style.getLocalName()) && id.equals(style.getAttributeNS(W, "styleId"))) {
          found = style;
          break;
        }
      if (found == null) return false;
      add(result, child(found, kind));
      id = value(child(found, "basedOn"));
    }
    return true;
  }

  private record PropertyEvidence(List<Element> values, boolean unresolved) {}
  private static final class FieldState { private int depth; }

  private static void add(List<Element> elements, Element value) { if (value != null) elements.add(value); }
  private static String value(Element element) { return element == null ? null : element.getAttributeNS(W, "val"); }
  private static Element child(Element parent, String name) {
    if (parent == null) return null;
    return XmlPartSupport.children(parent).stream().filter(e -> W.equals(e.getNamespaceURI())
        && name.equals(e.getLocalName())).findFirst().orElse(null);
  }
}
