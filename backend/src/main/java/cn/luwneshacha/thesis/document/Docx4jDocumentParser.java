package cn.luwneshacha.thesis.document;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import jakarta.xml.bind.JAXBElement;
import org.docx4j.TraversalUtil;
import org.docx4j.TraversalUtil.Callback;
import org.docx4j.XmlUtils;
import org.docx4j.finders.ClassFinder;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.openpackaging.parts.WordprocessingML.MainDocumentPart;
import org.docx4j.wml.P;
import org.docx4j.wml.PPr;
import org.docx4j.wml.PPrBase;
import org.docx4j.wml.R;
import org.docx4j.wml.RFonts;
import org.docx4j.wml.RPr;
import org.docx4j.wml.SectPr;
import org.docx4j.wml.Text;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

public final class Docx4jDocumentParser {
  private static final Pattern PAGE_FIELD = Pattern.compile("(?:^|\\s)PAGE(?=\\s|$)", Pattern.CASE_INSENSITIVE);
  private static final String RELATIONSHIPS = "http://schemas.openxmlformats.org/package/2006/relationships";
  private static final String OFFICE_RELATIONSHIPS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
  private static final String STRICT_OFFICE_RELATIONSHIPS = "http://purl.oclc.org/ooxml/officeDocument/relationships";
  private static final String FOOTER_RELATIONSHIP = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer";
  private static final String STRICT_FOOTER_RELATIONSHIP = "http://purl.oclc.org/ooxml/officeDocument/relationships/footer";

  public ParsedDocument parse(RawDocx raw) {
    Objects.requireNonNull(raw, "raw");
    try {
      WordprocessingMLPackage pkg = WordprocessingMLPackage.load(new ByteArrayInputStream(raw.sourceBytes()));
      MainDocumentPart main = pkg.getMainDocumentPart();
      var paragraphs = new ArrayList<ParagraphModel>();
      var sections = new ArrayList<SectionModel>();
      var finder = new ClassFinder(P.class);
      new TraversalUtil(main.getContent(), finder);
      List<List<Integer>> paragraphPaths = paragraphPaths(raw.part("word/document.xml").bytes());
      if (paragraphPaths.size() != finder.results.size()) {
        throw new IllegalArgumentException("paragraph structure mismatch between XML and docx4j: xml="
            + paragraphPaths.size() + ", docx4j=" + finder.results.size());
      }
      for (int index = 0; index < finder.results.size(); index++) {
        P paragraph = (P) finder.results.get(index);
        List<Integer> path = paragraphPaths.get(index);
        paragraphs.add(parseParagraph(pkg, paragraph, index, path));
        if (paragraph.getPPr() != null && paragraph.getPPr().getSectPr() != null) {
          sections.add(parseSection(paragraph.getPPr().getSectPr(), new SourceAddress("word/document.xml", path, 0, 0)));
        }
      }
      var body = main.getJaxbElement().getBody();
      if (body.getSectPr() != null) {
        SourceAddress anchor = paragraphs.isEmpty() ? new SourceAddress("word/document.xml", List.of(0), 0, 0) : paragraphs.get(0).source();
        sections.add(parseSection(body.getSectPr(), anchor));
      }
      byte[] documentXml = raw.part("word/document.xml").bytes();
      RawPart styles = raw.parts().get("word/styles.xml");
      return new ParsedDocument(raw.sourceSha256(), paragraphs, sections, List.of(),
          new TableParser().parse(documentXml), new EquationParser().parse(documentXml),
          new ReferenceParser().parse(paragraphs), new ImageParser().parse(documentXml, styles == null ? null : styles.bytes()), parsePageNumbers(raw),
          parseChapterBreaks(documentXml, pkg, finder.results, paragraphPaths, paragraphs));
    } catch (Exception error) {
      throw new IllegalArgumentException("unable to parse DOCX: " + error.getMessage(), error);
    }
  }

  private List<PageNumberModel> parsePageNumbers(RawDocx raw) {
    var result = new ArrayList<PageNumberModel>();
    Set<String> referencedFooters = referencedFooterParts(raw);
    for (RawPart part : raw.parts().values()) {
      if (!referencedFooters.contains(part.name())) continue;
      try {
        Element root = parseXml(part.bytes());
        var paragraphs = root.getElementsByTagNameNS(XmlPartSupport.W, "p");
        for (int index = 0; index < paragraphs.getLength(); index++) {
          Element paragraph = (Element) paragraphs.item(index);
          if (!hasPageField(paragraph)) continue;
          Element pageRun = pageFieldResultRun(paragraph);
          Element paragraphProperties = directElement(paragraph, XmlPartSupport.W, "pPr");
          Element jc = directElement(paragraphProperties, XmlPartSupport.W, "jc");
          Boolean centered = jc == null ? pageNumberAlignmentFromStyles(raw, paragraph)
              : Boolean.valueOf("center".equals(jc.getAttributeNS(XmlPartSupport.W, "val")));
          Element runProperties = directElement(pageRun, XmlPartSupport.W, "rPr");
          Element rFonts = directElement(runProperties, XmlPartSupport.W, "rFonts");
          Element sz = directElement(runProperties, XmlPartSupport.W, "sz");
          Integer size = sz == null ? pageNumberSizeFromStyles(raw, paragraph, pageRun)
              : parseInt(sz.getAttributeNS(XmlPartSupport.W, "val"));
          String font = fontName(rFonts);
          if (font == null) font = pageNumberFontFromStyles(raw, paragraph, pageRun);
          result.add(new PageNumberModel(new SourceAddress(part.name(), List.of(index), 0, 0), true,
              centered, font, size));
        }
      } catch (Exception error) {
        throw new IllegalArgumentException("unable to parse footer part " + part.name(), error);
      }
    }
    return List.copyOf(result);
  }

  private Set<String> referencedFooterParts(RawDocx raw) {
    try {
      Element document = parseXml(raw.part("word/document.xml").bytes());
      var sections = document.getElementsByTagNameNS(XmlPartSupport.W, "sectPr");
      boolean hasReferences = false;
      for (int index = 0; index < sections.getLength(); index++) {
        for (Element child : XmlPartSupport.children((Element) sections.item(index))) {
          if (XmlPartSupport.W.equals(child.getNamespaceURI()) && "footerReference".equals(child.getLocalName())) {
            hasReferences = true;
            break;
          }
        }
      }
      if (!hasReferences) return Set.of();

      Map<String, String> targets = footerRelationshipTargets(raw);
      Map<String, String> effectiveByType = new java.util.HashMap<>();
      var result = new LinkedHashSet<String>();
      boolean evenAndOdd = enabledSetting(raw, "evenAndOddHeaders");
      for (int index = 0; index < sections.getLength(); index++) {
        Element section = (Element) sections.item(index);
        Element titlePage = null;
        for (Element child : XmlPartSupport.children(section)) {
          if (!XmlPartSupport.W.equals(child.getNamespaceURI())) continue;
          if ("footerReference".equals(child.getLocalName())) {
            String type = child.getAttributeNS(XmlPartSupport.W, "type");
            if (type.isBlank()) type = "default";
            String relationshipId = relationshipId(child);
            String target = targets.get(relationshipId);
            if (target == null)
              throw new IllegalArgumentException("footer relationship is missing: " + relationshipId);
            effectiveByType.put(type, resolveFooterPart(raw, relationshipId, target));
          } else if ("titlePg".equals(child.getLocalName())) {
            titlePage = child;
          }
        }
        addEffectiveFooter(result, effectiveByType.get("default"));
        if (isEnabled(titlePage)) addEffectiveFooter(result, effectiveByType.get("first"));
        if (evenAndOdd) addEffectiveFooter(result, effectiveByType.get("even"));
      }
      return Set.copyOf(result);
    } catch (Exception error) {
      throw new IllegalArgumentException("unable to resolve referenced footer parts", error);
    }
  }

  private Map<String, String> footerRelationshipTargets(RawDocx raw) throws Exception {
    Element relationships = parseXml(raw.part("word/_rels/document.xml.rels").bytes());
    Map<String, String> targets = new java.util.HashMap<>();
    var relationshipNodes = relationships.getElementsByTagNameNS(RELATIONSHIPS, "Relationship");
    for (int index = 0; index < relationshipNodes.getLength(); index++) {
      Element relationship = (Element) relationshipNodes.item(index);
      String type = relationship.getAttribute("Type");
      if ((FOOTER_RELATIONSHIP.equals(type) || STRICT_FOOTER_RELATIONSHIP.equals(type))
          && !"External".equalsIgnoreCase(relationship.getAttribute("TargetMode"))) {
        targets.put(relationship.getAttribute("Id"), relationship.getAttribute("Target"));
      }
    }
    return targets;
  }

  private String resolveFooterPart(RawDocx raw, String relationshipId, String target) {
    if (target.isBlank()) throw new IllegalArgumentException("footer relationship target is empty: " + relationshipId);
    String path = URI.create(target).getPath();
    if (path == null || path.isBlank())
      throw new IllegalArgumentException("footer relationship target is empty: " + relationshipId);
    while (path.startsWith("/")) path = path.substring(1);
    String partName = java.nio.file.Path.of("word/document.xml").getParent().resolve(path).normalize()
        .toString().replace('\\', '/');
    if (!raw.parts().containsKey(partName))
      throw new IllegalArgumentException("footer relationship target is missing: " + partName);
    return partName;
  }

  private boolean enabledSetting(RawDocx raw, String settingName) throws Exception {
    RawPart settings = raw.parts().get("word/settings.xml");
    if (settings == null) return false;
    Element root = parseXml(settings.bytes());
    var nodes = root.getElementsByTagNameNS(XmlPartSupport.W, settingName);
    return nodes.getLength() > 0 && isEnabled((Element) nodes.item(0));
  }

  private boolean isEnabled(Element element) {
    if (element == null) return false;
    String value = element.getAttributeNS(XmlPartSupport.W, "val");
    return value.isBlank() || !("0".equals(value) || "false".equalsIgnoreCase(value) || "off".equalsIgnoreCase(value));
  }

  private void addEffectiveFooter(Set<String> result, String partName) {
    if (partName != null) result.add(partName);
  }

  private static String relationshipId(Element element) {
    String value = element.getAttributeNS(OFFICE_RELATIONSHIPS, "id");
    return value.isBlank() ? element.getAttributeNS(STRICT_OFFICE_RELATIONSHIPS, "id") : value;
  }

  private Element pageFieldResultRun(Element paragraph) {
    var simpleFields = paragraph.getElementsByTagNameNS(XmlPartSupport.W, "fldSimple");
    for (int index = 0; index < simpleFields.getLength(); index++) {
      Element field = (Element) simpleFields.item(index);
      if (!PAGE_FIELD.matcher(field.getAttributeNS(XmlPartSupport.W, "instr")).find()) continue;
      Element run = firstElement(field, XmlPartSupport.W, "r");
      if (run != null && run.getElementsByTagNameNS(XmlPartSupport.W, "t").getLength() > 0) return run;
    }
    var runs = paragraph.getElementsByTagNameNS(XmlPartSupport.W, "r");
    var instruction = new StringBuilder();
    int instructionRun = -1;
    for (int index = 0; index < runs.getLength(); index++) {
      Element run = (Element) runs.item(index);
      var instructions = run.getElementsByTagNameNS(XmlPartSupport.W, "instrText");
      for (int part = 0; part < instructions.getLength(); part++) instruction.append(instructions.item(part).getTextContent());
      if (!PAGE_FIELD.matcher(instruction).find()) continue;
      instructionRun = index;
      if (run.getElementsByTagNameNS(XmlPartSupport.W, "t").getLength() > 0) return run;
      break;
    }
    for (int index = instructionRun + 1; instructionRun >= 0 && index < runs.getLength(); index++) {
      Element run = (Element) runs.item(index);
      if (run.getElementsByTagNameNS(XmlPartSupport.W, "t").getLength() > 0) return run;
    }
    return null;
  }

  private Boolean pageNumberAlignmentFromStyles(RawDocx raw, Element paragraph) throws Exception {
    RawPart stylesPart = raw.parts().get("word/styles.xml");
    if (stylesPart == null) return null;
    Element styles = parseXml(stylesPart.bytes());
    String styleId = paragraphStyleId(styles, paragraph);
    Element styleJc = styleProperty(styles, styleId, "paragraph", "jc");
    if (styleJc != null) return "center".equals(styleJc.getAttributeNS(XmlPartSupport.W, "val"));
    Element defaults = directElement(styles, XmlPartSupport.W, "pPrDefault");
    Element defaultJc = directElement(defaults, XmlPartSupport.W, "jc");
    return defaultJc == null ? null : "center".equals(defaultJc.getAttributeNS(XmlPartSupport.W, "val"));
  }

  private Integer pageNumberSizeFromStyles(RawDocx raw, Element paragraph, Element pageRun) throws Exception {
    RawPart stylesPart = raw.parts().get("word/styles.xml");
    if (stylesPart == null) return null;
    Element styles = parseXml(stylesPart.bytes());
    Element size = styleProperty(styles, runStyleId(pageRun), "character", "sz");
    if (size == null) size = styleProperty(styles, paragraphStyleId(styles, paragraph), "paragraph", "sz");
    if (size != null) return parseInt(size.getAttributeNS(XmlPartSupport.W, "val"));
    Element defaults = directElement(styles, XmlPartSupport.W, "rPrDefault");
    Element defaultSize = directElement(defaults, XmlPartSupport.W, "sz");
    return defaultSize == null ? null : parseInt(defaultSize.getAttributeNS(XmlPartSupport.W, "val"));
  }

  private String pageNumberFontFromStyles(RawDocx raw, Element paragraph, Element pageRun) throws Exception {
    RawPart stylesPart = raw.parts().get("word/styles.xml");
    if (stylesPart == null) return null;
    Element styles = parseXml(stylesPart.bytes());
    String font = fontName(styleProperty(styles, runStyleId(pageRun), "character", "rFonts"));
    if (font != null) return font;
    font = fontName(styleProperty(styles, paragraphStyleId(styles, paragraph), "paragraph", "rFonts"));
    if (font != null) return font;
    Element defaults = directElement(styles, XmlPartSupport.W, "rPrDefault");
    Element defaultFonts = directElement(defaults, XmlPartSupport.W, "rFonts");
    return fontName(defaultFonts);
  }

  private static String runStyleId(Element run) {
    Element rPr = directElement(run, XmlPartSupport.W, "rPr");
    Element rStyle = directElement(rPr, XmlPartSupport.W, "rStyle");
    return rStyle == null ? null : rStyle.getAttributeNS(XmlPartSupport.W, "val");
  }

  private static String paragraphStyleId(Element styles, Element paragraph) {
    Element pPr = directElement(paragraph, XmlPartSupport.W, "pPr");
    Element pStyle = directElement(pPr, XmlPartSupport.W, "pStyle");
    String styleId = pStyle == null ? null : pStyle.getAttributeNS(XmlPartSupport.W, "val");
    if (styleId != null && !styleId.isBlank()) return styleId;
    var candidates = styles.getElementsByTagNameNS(XmlPartSupport.W, "style");
    for (int index = 0; index < candidates.getLength(); index++) {
      Element style = (Element) candidates.item(index);
      if ("paragraph".equals(style.getAttributeNS(XmlPartSupport.W, "type"))
          && "1".equals(style.getAttributeNS(XmlPartSupport.W, "default")))
        return style.getAttributeNS(XmlPartSupport.W, "styleId");
    }
    return null;
  }

  private static Element styleProperty(Element styles, String styleId, String type, String property) {
    var visited = new HashSet<String>();
    while (styleId != null && !styleId.isBlank() && visited.add(styleId)) {
      Element style = findStyle(styles, styleId);
      if (style == null || !type.equals(style.getAttributeNS(XmlPartSupport.W, "type"))) return null;
      Element properties = directElement(style, XmlPartSupport.W, "rPr");
      if ("jc".equals(property)) properties = directElement(style, XmlPartSupport.W, "pPr");
      Element value = directElement(properties, XmlPartSupport.W, property);
      if (value != null) return value;
      Element basedOn = directElement(style, XmlPartSupport.W, "basedOn");
      styleId = basedOn == null ? null : basedOn.getAttributeNS(XmlPartSupport.W, "val");
    }
    return null;
  }

  private static String fontName(Element fonts) {
    if (fonts == null) return null;
    String ascii = fonts.getAttributeNS(XmlPartSupport.W, "ascii");
    if (ascii.isBlank()) ascii = fonts.getAttributeNS(XmlPartSupport.W, "hAnsi");
    return ascii.isBlank() ? null : ascii;
  }

  private static Element directElement(Element parent, String namespace, String localName) {
    if (parent == null) return null;
    for (Element child : XmlPartSupport.children(parent))
      if (namespace.equals(child.getNamespaceURI()) && localName.equals(child.getLocalName())) return child;
    return null;
  }

  private static Element findStyle(Element styles, String styleId) {
    var candidates = styles.getElementsByTagNameNS(XmlPartSupport.W, "style");
    for (int i = 0; i < candidates.getLength(); i++) {
      Element candidate = (Element) candidates.item(i);
      if (styleId.equals(candidate.getAttributeNS(XmlPartSupport.W, "styleId"))) return candidate;
    }
    return null;
  }

  private static boolean hasPageField(Element root) {
    var instructions = new StringBuilder();
    var complexFields = root.getElementsByTagNameNS(XmlPartSupport.W, "instrText");
    for (int i = 0; i < complexFields.getLength(); i++) instructions.append(complexFields.item(i).getTextContent());
    var simpleFields = root.getElementsByTagNameNS(XmlPartSupport.W, "fldSimple");
    for (int i = 0; i < simpleFields.getLength(); i++) {
      instructions.append(' ').append(((Element) simpleFields.item(i)).getAttributeNS(XmlPartSupport.W, "instr"));
    }
    return PAGE_FIELD.matcher(instructions).find();
  }

  private List<HeadingBreakModel> parseChapterBreaks(byte[] xml, WordprocessingMLPackage pkg,
      List<Object> sourceParagraphs, List<List<Integer>> paragraphPaths, List<ParagraphModel> paragraphs) throws Exception {
    var result = new ArrayList<HeadingBreakModel>();
    var elements = new ArrayList<Element>();
    collectParagraphElements(parseXml(xml), elements);
    if (elements.size() != sourceParagraphs.size() || elements.size() != paragraphPaths.size())
      throw new IllegalArgumentException("paragraph structure mismatch while reading chapter breaks");
    var resolver = pkg.getMainDocumentPart().getPropertyResolver();
    for (int i = 0; i < elements.size(); i++) {
      String text = paragraphs.get(i).runs().stream().map(RunModel::text).reduce("", String::concat).trim();
      if (!text.matches("^第[0-9一二三四五六七八九十百千零〇]+章\\s+.*")
          || text.matches(".*(?:\\.{2,}|……+)\\s*\\d+\\s*$")) continue;
      P paragraph = (P) sourceParagraphs.get(i);
      PPr effectivePPr = resolver.getEffectivePPr(paragraph.getPPr());
      boolean pageBreak = effectivePPr != null && effectivePPr.getPageBreakBefore() != null
          && effectivePPr.getPageBreakBefore().isVal();
      if (i > 0) pageBreak |= startsOnNewPage((P) sourceParagraphs.get(i - 1));
      var breaks = elements.get(i).getElementsByTagNameNS(XmlPartSupport.W, "br");
      for (int j = 0; j < breaks.getLength(); j++) {
        if ("page".equals(((Element) breaks.item(j)).getAttributeNS(XmlPartSupport.W, "type"))) pageBreak = true;
      }
      result.add(new HeadingBreakModel(new SourceAddress("word/document.xml", paragraphPaths.get(i), 0, text.length()), text, pageBreak));
    }
    return List.copyOf(result);
  }

  private static boolean startsOnNewPage(P paragraph) {
    SectPr section = paragraph.getPPr() == null ? null : paragraph.getPPr().getSectPr();
    if (section == null) return false;
    String type = section.getType() == null ? null : section.getType().getVal();
    return type == null || "nextPage".equals(type) || "oddPage".equals(type) || "evenPage".equals(type);
  }

  private static void collectParagraphElements(Element element, List<Element> paragraphs) {
    if ("http://schemas.openxmlformats.org/markup-compatibility/2006".equals(element.getNamespaceURI())
        && "Fallback".equals(element.getLocalName())) return;
    if (XmlPartSupport.W.equals(element.getNamespaceURI()) && "p".equals(element.getLocalName())) paragraphs.add(element);
    for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling())
      if (child instanceof Element childElement) collectParagraphElements(childElement, paragraphs);
  }

  private static Element parseXml(byte[] bytes) throws Exception {
    return XmlPartSupport.parse(bytes).getDocumentElement();
  }

  private static Element firstElement(Element root, String namespace, String local) {
    var nodes = root.getElementsByTagNameNS(namespace, local);
    return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
  }

  private static Integer parseInt(String value) { try { return Integer.valueOf(value); } catch (NumberFormatException ignored) { return null; } }

  private ParagraphModel parseParagraph(WordprocessingMLPackage pkg, P paragraph, int index, List<Integer> path) throws Exception {
    var resolver = pkg.getMainDocumentPart().getPropertyResolver();
    PPr effectivePPr = resolver.getEffectivePPr(paragraph.getPPr());
    PPrBase.Ind ind = effectivePPr == null ? null : effectivePPr.getInd();
    Integer outlineLevel = effectivePPr == null || effectivePPr.getOutlineLvl() == null
        || effectivePPr.getOutlineLvl().getVal() == null ? null : effectivePPr.getOutlineLvl().getVal().intValue();
    var jc = effectivePPr == null ? null : effectivePPr.getJc();
    String alignment = jc == null || jc.getVal() == null ? "left" : jc.getVal().value();
    PPrBase.Ind directInd = paragraph.getPPr() == null ? null : paragraph.getPPr().getInd();
    Integer firstLineChars = null;
    if (directInd != null && directInd.getFirstLine() != null) {
      if (directInd.getFirstLineChars() != null) firstLineChars = directInd.getFirstLineChars().intValue();
    } else if (ind != null && ind.getFirstLineChars() != null) {
      firstLineChars = ind.getFirstLineChars().intValue();
    } else if (directInd != null && directInd.getFirstLineChars() != null) {
      firstLineChars = directInd.getFirstLineChars().intValue();
    }
    PPrBase.Spacing spacing = effectivePPr == null ? null : effectivePPr.getSpacing();
    var runs = new ArrayList<RunModel>();
    TraversalUtil.visit(paragraph, new Callback() {
      @Override public void walkJAXBElements(Object element) {
        List<Object> children = getChildren(element);
        if (children == null) return;
        for (Object child : children) {
          apply(child);
          if (shouldTraverse(child)) walkJAXBElements(child);
        }
      }

      @Override public List<Object> getChildren(Object element) {
        return TraversalUtil.getChildrenImpl(element);
      }

      @Override public boolean shouldTraverse(Object element) {
        if (element instanceof JAXBElement<?> jaxb) {
          String localName = jaxb.getName().getLocalPart();
          if ("del".equals(localName) || "moveFrom".equals(localName)) return false;
        }
        Object unwrapped = XmlUtils.unwrap(element);
        String typeName = unwrapped.getClass().getSimpleName();
        if ("RunDel".equals(typeName) || "RunMoveFrom".equals(typeName)) return false;
        return unwrapped == paragraph || !(unwrapped instanceof P);
      }

      @Override public List<Object> apply(Object element) {
        Object unwrapped = XmlUtils.unwrap(element);
        if (!(unwrapped instanceof R run)) return null;
        String text = run.getContent().stream()
            .map(XmlUtils::unwrap)
            .filter(Text.class::isInstance)
            .map(Text.class::cast)
            .map(Text::getValue)
            .filter(Objects::nonNull)
            .reduce("", String::concat);
        if (text.isEmpty()) return null;
        RPr properties;
        try {
          properties = resolver.getEffectiveRPr(run.getRPr(), paragraph.getPPr());
        } catch (Exception error) {
          throw new IllegalArgumentException("unable to resolve run formatting", error);
        }
        RFonts fonts = properties == null ? null : properties.getRFonts();
        var source = new SourceAddress("word/document.xml", path, 0, text.length());
        runs.add(new RunModel(text,
            fonts == null ? null : fonts.getAscii() != null ? fonts.getAscii() : fonts.getHAnsi(),
            fonts == null ? null : fonts.getEastAsia(),
            fonts == null ? null : fonts.getCs(),
            properties == null || properties.getSz() == null ? null : properties.getSz().getVal().intValue(),
            properties != null && properties.getB() != null && properties.getB().isVal(),
            properties == null || properties.getVertAlign() == null || properties.getVertAlign().getVal() == null
                ? null : properties.getVertAlign().getVal().value(), source));
        return null;
      }
    });
    return new ParagraphModel(runs,
        ind == null || ind.getFirstLine() == null ? null : ind.getFirstLine().intValue(),
        ind == null || ind.getLeft() == null ? null : ind.getLeft().intValue(),
        ind == null || ind.getRight() == null ? null : ind.getRight().intValue(),
        spacing == null || spacing.getLine() == null ? null : spacing.getLine().intValue(),
        spacing == null || spacing.getLineRule() == null ? null : spacing.getLineRule().value(),
        new SourceAddress("word/document.xml", path, 0, runs.stream().mapToInt(r -> r.text().length()).sum()),
        firstLineChars, outlineLevel, alignment);
  }

  private SectionModel parseSection(SectPr section, SourceAddress source) {
    SectPr.PgMar margins = section.getPgMar();
    return new SectionModel(
        margins == null || margins.getTop() == null ? null : margins.getTop().intValue(),
        margins == null || margins.getRight() == null ? null : margins.getRight().intValue(),
        margins == null || margins.getBottom() == null ? null : margins.getBottom().intValue(),
        margins == null || margins.getLeft() == null ? null : margins.getLeft().intValue(),
        source,
        section.getPgNumType() == null || section.getPgNumType().getFmt() == null ? null : section.getPgNumType().getFmt().value(),
        section.getPgNumType() == null || section.getPgNumType().getStart() == null ? null : section.getPgNumType().getStart().intValue());
  }

  private static List<List<Integer>> paragraphPaths(byte[] xml) throws Exception {
    Element root = XmlPartSupport.parse(xml).getDocumentElement();
    var paths = new ArrayList<List<Integer>>();
    collectParagraphPaths(root, List.of(), paths);
    return List.copyOf(paths);
  }

  private static void collectParagraphPaths(Element element, List<Integer> path, List<List<Integer>> paths) {
    if ("http://schemas.openxmlformats.org/markup-compatibility/2006".equals(element.getNamespaceURI())
        && "Fallback".equals(element.getLocalName())) return;
    if ("http://schemas.openxmlformats.org/wordprocessingml/2006/main".equals(element.getNamespaceURI())
        && "p".equals(element.getLocalName())) paths.add(path);
    int index = 0;
    for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
      if (child instanceof Element childElement) {
        var childPath = new ArrayList<>(path);
        childPath.add(index++);
        collectParagraphPaths(childElement, List.copyOf(childPath), paths);
      }
    }
  }
}
