package cn.luwneshacha.thesis.document;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
import org.docx4j.model.listnumbering.Emulator;
import org.docx4j.model.listnumbering.NumberingState;
import org.docx4j.wml.Br;
import org.docx4j.wml.P;
import org.docx4j.wml.PPr;
import org.docx4j.wml.PPrBase;
import org.docx4j.wml.R;
import org.docx4j.wml.RFonts;
import org.docx4j.wml.RPr;
import org.docx4j.wml.SectPr;
import org.docx4j.wml.STBrType;
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
  private static final String HEADER_RELATIONSHIP = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/header";
  private static final String STRICT_HEADER_RELATIONSHIP = "http://purl.oclc.org/ooxml/officeDocument/relationships/header";
  private static final Set<String> PAGE_NUMBER_FORMATS = Set.of(
      "decimal", "upperRoman", "lowerRoman", "upperLetter", "lowerLetter", "ordinal",
      "cardinalText", "ordinalText", "hex", "chicago", "japaneseCounting", "aiueo", "iroha",
      "decimalFullWidth", "decimalHalfWidth", "japaneseLegal", "japaneseDigitalTenThousand",
      "decimalEnclosedCircle", "decimalFullWidth2", "aiueoFullWidth", "irohaFullWidth",
      "decimalEnclosedFullstop", "decimalEnclosedParen", "decimalEnclosedCircleChinese",
      "koreanDigital", "koreanCounting", "koreanLegal", "koreanDigital2", "hebrew1",
      "arabicAlpha", "arabicAbjad", "hindiVowels", "hindiConsonants", "hindiNumbers",
      "hindiCounting", "thaiLetters", "thaiNumbers", "thaiCounting", "vietnameseCounting",
      "numberInDash", "russianLower", "russianUpper", "chineseCounting", "chineseLegalSimplified",
      "chineseCountingThousand", "taiwaneseCounting", "taiwaneseLegal", "taiwaneseCountingThousand",
      "chineseCounting2", "koreanDigital3", "hebrew2");

  public ParsedDocument parse(RawDocx raw) {
    Objects.requireNonNull(raw, "raw");
    try {
      WordprocessingMLPackage pkg = WordprocessingMLPackage.load(new ByteArrayInputStream(raw.sourceBytes()));
      MainDocumentPart main = pkg.getMainDocumentPart();
      var paragraphs = new ArrayList<ParagraphModel>();
      var sections = new ArrayList<SectionModel>();
      var finder = new ClassFinder(P.class);
      new TraversalUtil(main.getContent(), finder);
      Element xmlRoot = XmlPartSupport.parse(raw.part("word/document.xml").bytes()).getDocumentElement();
      var paragraphPaths = new ArrayList<List<Integer>>();
      var xmlParagraphs = new ArrayList<Element>();
      collectParagraphXml(xmlRoot, List.of(), paragraphPaths, xmlParagraphs);
      RawPart styles = raw.parts().get("word/styles.xml");
      Element stylesRoot = styles == null ? null : XmlPartSupport.parse(styles.bytes()).getDocumentElement();
      boolean evenAndOddHeaders = enabledSetting(raw, "evenAndOddHeaders");
      if (paragraphPaths.size() != finder.results.size()) {
        throw new IllegalArgumentException("paragraph structure mismatch between XML and docx4j: xml="
            + paragraphPaths.size() + ", docx4j=" + finder.results.size());
      }
      NumberingState numberingState = new NumberingState();
      for (int index = 0; index < finder.results.size(); index++) {
        P paragraph = (P) finder.results.get(index);
        List<Integer> path = paragraphPaths.get(index);
        paragraphs.add(parseParagraph(pkg, paragraph, xmlParagraphs.get(index), stylesRoot,
            index, path, numberingState, containsGeneratedTocField(xmlParagraphs.get(index))));
        if (paragraph.getPPr() != null && paragraph.getPPr().getSectPr() != null) {
          sections.add(parseSection(paragraph.getPPr().getSectPr(), directElement(xmlParagraphs.get(index), XmlPartSupport.W, "sectPr"),
              new SourceAddress("word/document.xml", path, 0, 0), false, evenAndOddHeaders));
        }
      }
      var body = main.getJaxbElement().getBody();
      if (body.getSectPr() != null) {
        // A body-level sectPr belongs to the section ending at the end of the
        // document.  Anchor it to the last paragraph so a margin comment is
        // attached to the page that actually owns the section properties.
        SourceAddress anchor = paragraphs.isEmpty() ? new SourceAddress("word/document.xml", List.of(0), 0, 0)
            : paragraphs.get(paragraphs.size() - 1).source();
        Element rawBody = directElement(xmlRoot, XmlPartSupport.W, "body");
        sections.add(parseSection(body.getSectPr(), directElement(rawBody, XmlPartSupport.W, "sectPr"), anchor, true,
            evenAndOddHeaders));
      }
      byte[] documentXml = raw.part("word/document.xml").bytes();
      List<String> diagnostics = DocumentClassifier.classify(documentXml);
      return new ParsedDocument(raw.sourceSha256(), paragraphs, sections, diagnostics,
          new TableParser().parse(documentXml, styles == null ? null : styles.bytes(), pkg),
          new EquationParser().parse(documentXml, styles == null ? null : styles.bytes(), equationThemeFonts(pkg)),
          new ReferenceParser().parse(paragraphs), new ImageParser().parse(documentXml, styles == null ? null : styles.bytes()), parsePageNumbers(raw, pkg),
          parseChapterBreaks(documentXml, pkg, finder.results, paragraphPaths, paragraphs),
          parseHeaders(raw, pkg, evenAndOddHeaders));
    } catch (Exception error) {
      throw new IllegalArgumentException("unable to parse DOCX: " + error.getMessage(), error);
    }
  }

  private List<PageNumberModel> parsePageNumbers(RawDocx raw, WordprocessingMLPackage pkg) {
    var result = new ArrayList<PageNumberModel>();
    Map<FooterKey, List<Integer>> referencedFooterSections = referencedFooterSections(raw);
    for (RawPart part : raw.parts().values()) {
      List<Map.Entry<FooterKey, List<Integer>>> usages = referencedFooterSections.entrySet().stream()
          .filter(entry -> part.name().equals(entry.getKey().partName())).toList();
      if (usages.isEmpty()) continue;
      try {
        Element root = parseXml(part.bytes());
        var paragraphs = root.getElementsByTagNameNS(XmlPartSupport.W, "p");
        for (Map.Entry<FooterKey, List<Integer>> usage : usages) {
          for (int index = 0; index < paragraphs.getLength(); index++) {
            Element paragraph = (Element) paragraphs.item(index);
            if (!hasPageField(paragraph)) continue;
            List<Element> pageRuns = pageFieldResultRuns(paragraph);
            Element paragraphProperties = directElement(paragraph, XmlPartSupport.W, "pPr");
            Element jc = directElement(paragraphProperties, XmlPartSupport.W, "jc");
            Boolean centered = insideTableCell(paragraph) ? null
                : jc == null ? pageNumberAlignmentFromStyles(raw, paragraph)
                    : Boolean.valueOf("center".equals(XmlAlignment.parse(jc)));
            var formatting = new ArrayList<PageNumberModel.RunFormatting>();
            for (Element pageRun : pageRuns) {
              Element runProperties = directElement(pageRun, XmlPartSupport.W, "rPr");
              Element rFonts = directElement(runProperties, XmlPartSupport.W, "rFonts");
              Element sz = directElement(runProperties, XmlPartSupport.W, "sz");
              Integer size = sz == null ? pageNumberSizeFromStyles(raw, paragraph, pageRun)
                  : XmlInteger.parse(sz.getAttributeNS(XmlPartSupport.W, "val"));
              FontResolution fontResolution = fontResolution(rFonts, pkg);
              if (!fontResolution.defined())
                fontResolution = pageNumberFontFromStyles(raw, paragraph, pageRun, pkg);
              formatting.add(new PageNumberModel.RunFormatting(fontResolution.name(), size));
            }
            if (formatting.isEmpty()) {
              Integer size = pageNumberSizeFromStyles(raw, paragraph, null);
              FontResolution fontResolution = pageNumberFontFromStyles(raw, paragraph, null, pkg);
              formatting.add(new PageNumberModel.RunFormatting(fontResolution.name(), size));
            }
            String asciiFont = formatting.isEmpty() ? null : formatting.get(0).asciiFont();
            Integer size = formatting.isEmpty() ? null : formatting.get(0).sizeHalfPoints();
            result.add(new PageNumberModel(new SourceAddress(part.name(), List.of(index), 0, 0), true,
                centered, asciiFont, size, usage.getValue(), formatting, usage.getKey().variant()));
          }
        }
      } catch (Exception error) {
        throw new IllegalArgumentException("unable to parse footer part " + part.name(), error);
      }
    }
    return List.copyOf(result);
  }

  private Map<FooterKey, List<Integer>> referencedFooterSections(RawDocx raw) {
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
      if (!hasReferences) return Map.of();

      Map<String, String> targets = footerRelationshipTargets(raw);
      Map<String, String> effectiveByType = new java.util.HashMap<>();
      var result = new LinkedHashMap<FooterKey, LinkedHashSet<Integer>>();
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
        addEffectiveFooter(result, effectiveByType.get("default"), PageNumberModel.FooterVariant.DEFAULT, index);
        if (XmlOnOff.enabled(titlePage))
          addEffectiveFooter(result, effectiveByType.get("first"), PageNumberModel.FooterVariant.FIRST, index);
        if (evenAndOdd)
          addEffectiveFooter(result, effectiveByType.get("even"), PageNumberModel.FooterVariant.EVEN, index);
      }
      var usages = new LinkedHashMap<FooterKey, List<Integer>>();
      result.forEach((key, indexes) -> usages.put(key, List.copyOf(indexes)));
      return Map.copyOf(usages);
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
    return nodes.getLength() > 0 && XmlOnOff.enabled((Element) nodes.item(0));
  }

  private void addEffectiveFooter(Map<FooterKey, LinkedHashSet<Integer>> result, String partName,
      PageNumberModel.FooterVariant variant, int sectionIndex) {
    if (partName != null)
      result.computeIfAbsent(new FooterKey(partName, variant), ignored -> new LinkedHashSet<>()).add(sectionIndex);
  }

  private boolean insideTableCell(Element element) {
    Node current = element;
    while (current != null) {
      if (XmlPartSupport.W.equals(current.getNamespaceURI()) && "tc".equals(current.getLocalName())) return true;
      current = current.getParentNode();
    }
    return false;
  }

  private static String relationshipId(Element element) {
    String value = element.getAttributeNS(OFFICE_RELATIONSHIPS, "id");
    return value.isBlank() ? element.getAttributeNS(STRICT_OFFICE_RELATIONSHIPS, "id") : value;
  }

  private List<Element> pageFieldResultRuns(Element paragraph) {
    var result = new ArrayList<Element>();
    var simpleFields = paragraph.getElementsByTagNameNS(XmlPartSupport.W, "fldSimple");
    for (int index = 0; index < simpleFields.getLength(); index++) {
      Element field = (Element) simpleFields.item(index);
      if (!PAGE_FIELD.matcher(field.getAttributeNS(XmlPartSupport.W, "instr")).find()) continue;
      var fieldRuns = field.getElementsByTagNameNS(XmlPartSupport.W, "r");
      for (int runIndex = 0; runIndex < fieldRuns.getLength(); runIndex++) {
        Element run = (Element) fieldRuns.item(runIndex);
        if (run.getElementsByTagNameNS(XmlPartSupport.W, "t").getLength() > 0) result.add(run);
      }
    }
    var runs = paragraph.getElementsByTagNameNS(XmlPartSupport.W, "r");
    var instruction = new StringBuilder();
    int fieldStart = -1;
    boolean pageField = false;
    boolean inResult = false;
    boolean legacyPageResultPending = false;
    for (int index = 0; index < runs.getLength(); index++) {
      Element run = (Element) runs.item(index);
      Element fieldChar = firstElement(run, XmlPartSupport.W, "fldChar");
      String fieldCharType = fieldChar == null ? "" : fieldChar.getAttributeNS(XmlPartSupport.W, "fldCharType");
      if ("begin".equals(fieldCharType)) {
        fieldStart = index;
        instruction.setLength(0);
        pageField = false;
        inResult = false;
        legacyPageResultPending = false;
      }
      var instructions = run.getElementsByTagNameNS(XmlPartSupport.W, "instrText");
      for (int part = 0; part < instructions.getLength(); part++) {
        String text = instructions.item(part).getTextContent();
        instruction.append(text);
      }
      if (PAGE_FIELD.matcher(instruction).find()) legacyPageResultPending = true;
      if (fieldStart >= 0 && !inResult) {
        pageField |= PAGE_FIELD.matcher(instruction).find();
      }
      if ("separate".equals(fieldCharType)) {
        inResult = pageField;
        legacyPageResultPending = false;
      }
      boolean hasText = run.getElementsByTagNameNS(XmlPartSupport.W, "t").getLength() > 0;
      if (inResult && hasText) result.add(run);
      else if (fieldStart < 0 && legacyPageResultPending && hasText) {
        result.add(run);
        legacyPageResultPending = false;
      }
      if ("end".equals(fieldCharType)) {
        fieldStart = -1;
        pageField = false;
        inResult = false;
        legacyPageResultPending = false;
      }
    }
    return List.copyOf(result);
  }

  private List<HeaderModel> parseHeaders(RawDocx raw, WordprocessingMLPackage pkg, boolean evenAndOddHeaders) {
    Map<String, Map<HeaderModel.Type, List<Integer>>> referencedHeaderSections =
        referencedHeaderSections(raw, evenAndOddHeaders);
    if (referencedHeaderSections.isEmpty()) return List.of();
    RawPart styles = raw.parts().get("word/styles.xml");
    Element stylesRoot;
    try {
      stylesRoot = styles == null ? null : parseXml(styles.bytes());
    } catch (Exception error) {
      throw new IllegalArgumentException("unable to parse styles for headers", error);
    }
    var result = new ArrayList<HeaderModel>();
    for (RawPart part : raw.parts().values()) {
      Map<HeaderModel.Type, List<Integer>> sectionIndexesByType = referencedHeaderSections.get(part.name());
      if (sectionIndexesByType == null) continue;
      try {
        Element root = parseXml(part.bytes());
        var paragraphs = root.getElementsByTagNameNS(XmlPartSupport.W, "p");
        if (paragraphs.getLength() == 0) {
          result.add(new HeaderModel(new SourceAddress(part.name(), List.of(0), 0, 0), "", null,
              List.of(), sectionIndexesByType));
          continue;
        }
        var allText = new StringBuilder();
        var formatting = new ArrayList<HeaderModel.RunFormatting>();
        Boolean centered = null;
        for (int paragraphIndex = 0; paragraphIndex < paragraphs.getLength(); paragraphIndex++) {
          Element paragraph = (Element) paragraphs.item(paragraphIndex);
          if (paragraphIndex > 0) allText.append('\n');
          allText.append(XmlPartSupport.text(paragraph));
          if (paragraphIndex == 0) {
            Element properties = directElement(paragraph, XmlPartSupport.W, "pPr");
            Element jc = directElement(properties, XmlPartSupport.W, "jc");
            centered = jc == null ? pageNumberAlignmentFromStyles(raw, paragraph)
                : Boolean.valueOf("center".equals(XmlAlignment.parse(jc)));
          }
          var runs = paragraph.getElementsByTagNameNS(XmlPartSupport.W, "r");
          for (int runIndex = 0; runIndex < runs.getLength(); runIndex++) {
            Element run = (Element) runs.item(runIndex);
            String runText = XmlPartSupport.text(run);
            if (runText.isEmpty()) continue;
            Element runProperties = directElement(run, XmlPartSupport.W, "rPr");
            Element fonts = directElement(runProperties, XmlPartSupport.W, "rFonts");
            FontResolution ascii = fontResolution(fonts, pkg);
            String eastAsia = directEastAsiaFont(fonts, pkg);
            if (!ascii.defined())
              ascii = headerFontFromStyles(stylesRoot, raw, paragraph, run, pkg, false);
            if (eastAsia == null)
              eastAsia = headerFontFromStyles(stylesRoot, raw, paragraph, run, pkg, true).name();
            Element size = directElement(runProperties, XmlPartSupport.W, "sz");
            Integer sizeHalfPoints = size == null ? headerSizeFromStyles(stylesRoot, paragraph, run)
                : XmlInteger.parse(size.getAttributeNS(XmlPartSupport.W, "val"));
            formatting.add(new HeaderModel.RunFormatting(ascii.name(), eastAsia, sizeHalfPoints,
                new SourceAddress(part.name(), elementPathFromRoot(root, run), 0, runText.length())));
          }
        }
        String text = allText.toString();
        result.add(new HeaderModel(new SourceAddress(part.name(), List.of(0), 0, text.length()),
            text, centered, formatting, sectionIndexesByType));
      } catch (Exception error) {
        throw new IllegalArgumentException("unable to parse header part " + part.name(), error);
      }
    }
    return List.copyOf(result);
  }

  private Map<String, Map<HeaderModel.Type, List<Integer>>> referencedHeaderSections(
      RawDocx raw, boolean evenAndOddHeaders) {
    try {
      Element document = parseXml(raw.part("word/document.xml").bytes());
      var sections = document.getElementsByTagNameNS(XmlPartSupport.W, "sectPr");
      boolean hasReferences = false;
      for (int index = 0; index < sections.getLength(); index++) {
        for (Element child : XmlPartSupport.children((Element) sections.item(index))) {
          if (XmlPartSupport.W.equals(child.getNamespaceURI()) && "headerReference".equals(child.getLocalName())) {
            hasReferences = true;
            break;
          }
        }
      }
      if (!hasReferences) return Map.of();
      Map<String, String> targets = headerRelationshipTargets(raw);
      Map<HeaderModel.Type, String> effectiveByType = new java.util.EnumMap<>(HeaderModel.Type.class);
      var result = new LinkedHashMap<String, Map<HeaderModel.Type, LinkedHashSet<Integer>>>();
      for (int index = 0; index < sections.getLength(); index++) {
        Element section = (Element) sections.item(index);
        Element titlePage = null;
        for (Element child : XmlPartSupport.children(section)) {
          if (!XmlPartSupport.W.equals(child.getNamespaceURI())) continue;
          if ("headerReference".equals(child.getLocalName())) {
            String typeValue = child.getAttributeNS(XmlPartSupport.W, "type");
            if (typeValue.isBlank()) typeValue = "default";
            HeaderModel.Type type = HeaderModel.Type.fromXml(typeValue);
            String relationshipId = relationshipId(child);
            String target = targets.get(relationshipId);
            if (target == null)
              throw new IllegalArgumentException("header relationship is missing: " + relationshipId);
            effectiveByType.put(type, resolveHeaderPart(raw, relationshipId, target));
          } else if ("titlePg".equals(child.getLocalName())) {
            titlePage = child;
          }
        }
        addEffectiveHeader(result, effectiveByType.get(HeaderModel.Type.DEFAULT), HeaderModel.Type.DEFAULT, index);
        if (XmlOnOff.enabled(titlePage))
          addEffectiveHeader(result, effectiveByType.get(HeaderModel.Type.FIRST), HeaderModel.Type.FIRST, index);
        if (evenAndOddHeaders)
          addEffectiveHeader(result, effectiveByType.get(HeaderModel.Type.EVEN), HeaderModel.Type.EVEN, index);
      }
      var sectionIndexes = new LinkedHashMap<String, Map<HeaderModel.Type, List<Integer>>>();
      result.forEach((partName, types) -> {
        var copiedTypes = new java.util.EnumMap<HeaderModel.Type, List<Integer>>(HeaderModel.Type.class);
        types.forEach((type, indexes) -> copiedTypes.put(type, List.copyOf(indexes)));
        sectionIndexes.put(partName, Map.copyOf(copiedTypes));
      });
      return Map.copyOf(sectionIndexes);
    } catch (Exception error) {
      throw new IllegalArgumentException("unable to resolve referenced header parts", error);
    }
  }

  private Map<String, String> headerRelationshipTargets(RawDocx raw) throws Exception {
    Element relationships = parseXml(raw.part("word/_rels/document.xml.rels").bytes());
    Map<String, String> targets = new java.util.HashMap<>();
    var relationshipNodes = relationships.getElementsByTagNameNS(RELATIONSHIPS, "Relationship");
    for (int index = 0; index < relationshipNodes.getLength(); index++) {
      Element relationship = (Element) relationshipNodes.item(index);
      String type = relationship.getAttribute("Type");
      if ((HEADER_RELATIONSHIP.equals(type) || STRICT_HEADER_RELATIONSHIP.equals(type))
          && !"External".equalsIgnoreCase(relationship.getAttribute("TargetMode"))) {
        targets.put(relationship.getAttribute("Id"), relationship.getAttribute("Target"));
      }
    }
    return targets;
  }

  private String resolveHeaderPart(RawDocx raw, String relationshipId, String target) {
    if (target.isBlank()) throw new IllegalArgumentException("header relationship target is empty: " + relationshipId);
    String path = URI.create(target).getPath();
    if (path == null || path.isBlank())
      throw new IllegalArgumentException("header relationship target is empty: " + relationshipId);
    while (path.startsWith("/")) path = path.substring(1);
    String partName = java.nio.file.Path.of("word/document.xml").getParent().resolve(path).normalize()
        .toString().replace('\\', '/');
    if (!raw.parts().containsKey(partName))
      throw new IllegalArgumentException("header relationship target is missing: " + partName);
    return partName;
  }

  private void addEffectiveHeader(Map<String, Map<HeaderModel.Type, LinkedHashSet<Integer>>> result,
      String partName, HeaderModel.Type type, int sectionIndex) {
    if (partName != null) {
      result.computeIfAbsent(partName, ignored -> new java.util.EnumMap<>(HeaderModel.Type.class))
          .computeIfAbsent(type, ignored -> new LinkedHashSet<>()).add(sectionIndex);
    }
  }

  private String directEastAsiaFont(Element fonts, WordprocessingMLPackage pkg) throws Exception {
    if (fonts == null) return null;
    String explicit = fonts.getAttributeNS(XmlPartSupport.W, "eastAsia");
    if (fonts.hasAttributeNS(XmlPartSupport.W, "eastAsia")) return explicit.isBlank() ? null : explicit;
    String themeValue = fonts.getAttributeNS(XmlPartSupport.W, "eastAsiaTheme");
    if (!fonts.hasAttributeNS(XmlPartSupport.W, "eastAsiaTheme")) return null;
    try {
      var theme = themeValue(themeValue);
      if (theme == null) return null;
      var relationships = pkg.getMainDocumentPart().getRelationshipsPart();
      var relationship = relationships.getRelationshipByType(
          "http://schemas.openxmlformats.org/officeDocument/2006/relationships/settings");
      if (relationship == null) relationship = relationships.getRelationshipByType(
          "http://purl.oclc.org/ooxml/officeDocument/relationships/settings");
      if (relationship == null) return null;
      var settings = (org.docx4j.openpackaging.parts.WordprocessingML.DocumentSettingsPart)
          relationships.getPart(relationship);
      var part = themePart(pkg);
      return part == null ? null : part.getFont(theme, settings.getJaxbElement().getThemeFontLang());
    } catch (Exception ignored) {
      return null;
    }
  }

  private FontResolution headerFontFromStyles(Element styles, RawDocx raw, Element paragraph, Element run,
      WordprocessingMLPackage pkg, boolean eastAsia) throws Exception {
    if (styles == null) return new FontResolution(false, null);
    String property = eastAsia ? "eastAsia" : "ascii";
    for (String styleId : new String[]{runStyleId(run), paragraphStyleId(styles, paragraph)}) {
      Set<String> visited = new HashSet<>();
      Element style = findStyle(styles, styleId);
      while (style != null && visited.add(style.getAttributeNS(XmlPartSupport.W, "styleId"))) {
        Element rPr = directElement(style, XmlPartSupport.W, "rPr");
        Element fonts = directElement(rPr, XmlPartSupport.W, "rFonts");
        if (fonts != null) {
          if (eastAsia) {
            String name = directEastAsiaFont(fonts, pkg);
            if (name != null) return new FontResolution(true, name);
          } else {
            FontResolution name = fontResolution(fonts, pkg);
            if (name.defined()) return name;
          }
          if (fonts.hasAttributeNS(XmlPartSupport.W, property)) {
            String name = fonts.getAttributeNS(XmlPartSupport.W, property);
            if (!name.isBlank()) return new FontResolution(true, name);
          }
        }
        Element basedOn = directElement(style, XmlPartSupport.W, "basedOn");
        style = basedOn == null ? null : findStyle(styles, basedOn.getAttributeNS(XmlPartSupport.W, "val"));
      }
    }
    Element defaults = directElement(styles, XmlPartSupport.W, "docDefaults");
    Element defaultRPr = directElement(defaults, XmlPartSupport.W, "rPrDefault");
    Element defaultRPrProperties = directElement(defaultRPr, XmlPartSupport.W, "rPr");
    Element defaultFonts = directElement(defaultRPrProperties, XmlPartSupport.W, "rFonts");
    if (!eastAsia) return fontResolution(defaultFonts, pkg);
    String name = directEastAsiaFont(defaultFonts, pkg);
    return new FontResolution(name != null, name);
  }

  private Integer headerSizeFromStyles(Element styles, Element paragraph, Element run) {
    if (styles == null) return null;
    for (String styleId : new String[]{runStyleId(run), paragraphStyleId(styles, paragraph)}) {
      Element style = findStyle(styles, styleId);
      Set<String> visited = new HashSet<>();
      while (style != null && visited.add(style.getAttributeNS(XmlPartSupport.W, "styleId"))) {
        Element rPr = directElement(style, XmlPartSupport.W, "rPr");
        Element size = directElement(rPr, XmlPartSupport.W, "sz");
        if (size != null) return XmlInteger.parse(size.getAttributeNS(XmlPartSupport.W, "val"));
        Element basedOn = directElement(style, XmlPartSupport.W, "basedOn");
        style = basedOn == null ? null : findStyle(styles, basedOn.getAttributeNS(XmlPartSupport.W, "val"));
      }
    }
    Element defaults = directElement(styles, XmlPartSupport.W, "docDefaults");
    Element defaultRPr = directElement(defaults, XmlPartSupport.W, "rPrDefault");
    Element defaultRPrProperties = directElement(defaultRPr, XmlPartSupport.W, "rPr");
    Element defaultSize = directElement(defaultRPrProperties, XmlPartSupport.W, "sz");
    return defaultSize == null ? null : XmlInteger.parse(defaultSize.getAttributeNS(XmlPartSupport.W, "val"));
  }

  private static List<Integer> elementPathFromRoot(Element root, Element target) {
    var reversed = new ArrayList<Integer>();
    Node current = target;
    while (current != root) {
      if (!(current instanceof Element))
        throw new IllegalArgumentException("header run is not inside its XML root");
      int elementIndex = 0;
      for (Node sibling = current.getPreviousSibling(); sibling != null; sibling = sibling.getPreviousSibling()) {
        if (sibling instanceof Element) elementIndex++;
      }
      reversed.add(elementIndex);
      current = current.getParentNode();
      if (current == null) throw new IllegalArgumentException("header run is not inside its XML root");
    }
    java.util.Collections.reverse(reversed);
    return List.copyOf(reversed);
  }

  private Boolean pageNumberAlignmentFromStyles(RawDocx raw, Element paragraph) throws Exception {
    RawPart stylesPart = raw.parts().get("word/styles.xml");
    if (stylesPart == null) return null;
    Element styles = parseXml(stylesPart.bytes());
    String styleId = paragraphStyleId(styles, paragraph);
    Element styleJc = styleProperty(styles, styleId, "paragraph", "jc");
    if (styleJc != null) return "center".equals(XmlAlignment.parse(styleJc));
    Element defaults = directElement(styles, XmlPartSupport.W, "docDefaults");
    Element defaultPPr = directElement(defaults, XmlPartSupport.W, "pPrDefault");
    Element defaultPPrProperties = directElement(defaultPPr, XmlPartSupport.W, "pPr");
    Element defaultJc = directElement(defaultPPrProperties, XmlPartSupport.W, "jc");
    return defaultJc == null ? null : "center".equals(XmlAlignment.parse(defaultJc));
  }

  private Integer pageNumberSizeFromStyles(RawDocx raw, Element paragraph, Element pageRun) throws Exception {
    RawPart stylesPart = raw.parts().get("word/styles.xml");
    if (stylesPart == null) return null;
    Element styles = parseXml(stylesPart.bytes());
    Element size = styleProperty(styles, runStyleId(pageRun), "character", "sz");
    if (size == null) size = styleProperty(styles, paragraphStyleId(styles, paragraph), "paragraph", "sz");
    if (size != null) return XmlInteger.parse(size.getAttributeNS(XmlPartSupport.W, "val"));
    Element defaults = directElement(styles, XmlPartSupport.W, "docDefaults");
    Element defaultRPr = directElement(defaults, XmlPartSupport.W, "rPrDefault");
    Element defaultRPrProperties = directElement(defaultRPr, XmlPartSupport.W, "rPr");
    Element defaultSize = directElement(defaultRPrProperties, XmlPartSupport.W, "sz");
    return defaultSize == null ? null : XmlInteger.parse(defaultSize.getAttributeNS(XmlPartSupport.W, "val"));
  }

  private FontResolution pageNumberFontFromStyles(RawDocx raw, Element paragraph, Element pageRun,
      WordprocessingMLPackage pkg) throws Exception {
    RawPart stylesPart = raw.parts().get("word/styles.xml");
    if (stylesPart == null) return new FontResolution(false, null);
    Element styles = parseXml(stylesPart.bytes());
    FontResolution font = fontNameFromStyleChain(styles, runStyleId(pageRun), "character", pkg);
    if (font.defined()) return font;
    font = fontNameFromStyleChain(styles, paragraphStyleId(styles, paragraph), "paragraph", pkg);
    if (font.defined()) return font;
    Element defaults = directElement(styles, XmlPartSupport.W, "docDefaults");
    Element defaultRPr = directElement(defaults, XmlPartSupport.W, "rPrDefault");
    Element defaultRPrProperties = directElement(defaultRPr, XmlPartSupport.W, "rPr");
    Element defaultFonts = directElement(defaultRPrProperties, XmlPartSupport.W, "rFonts");
    return fontResolution(defaultFonts, pkg);
  }

  private FontResolution fontNameFromStyleChain(Element styles, String styleId, String type,
      WordprocessingMLPackage pkg) throws Exception {
    var visited = new HashSet<String>();
    while (styleId != null && !styleId.isBlank() && visited.add(styleId)) {
      Element style = findStyle(styles, styleId);
      if (style == null || !type.equals(style.getAttributeNS(XmlPartSupport.W, "type"))) break;
      Element runProperties = directElement(style, XmlPartSupport.W, "rPr");
      Element fonts = directElement(runProperties, XmlPartSupport.W, "rFonts");
      FontResolution current = fontResolution(fonts, pkg);
      if (current.defined()) return current;
      Element basedOn = directElement(style, XmlPartSupport.W, "basedOn");
      styleId = basedOn == null ? null : basedOn.getAttributeNS(XmlPartSupport.W, "val");
    }
    return new FontResolution(false, null);
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

  private FontResolution fontResolution(Element fonts, WordprocessingMLPackage pkg) throws Exception {
    if (fonts == null) return new FontResolution(false, null);
    String themeValue = fonts.getAttributeNS(XmlPartSupport.W, "asciiTheme");
    if (fonts.hasAttributeNS(XmlPartSupport.W, "asciiTheme"))
      return new FontResolution(true, themeFont(pkg, themeValue(themeValue)));
    String ascii = fonts.getAttributeNS(XmlPartSupport.W, "ascii");
    if (fonts.hasAttributeNS(XmlPartSupport.W, "ascii"))
      return new FontResolution(true, ascii.isBlank() ? null : ascii);
    return new FontResolution(false, null);
  }

  private org.docx4j.wml.STTheme themeValue(String value) {
    try {
      return org.docx4j.wml.STTheme.fromValue(value);
    } catch (IllegalArgumentException error) {
      // Preserve an explicitly invalid theme slot as an unknown font. Rule checks
      // can then report it without aborting inspection of the whole document.
      return null;
    }
  }

  private String themeFont(WordprocessingMLPackage pkg, org.docx4j.wml.STTheme theme) throws Exception {
    var part = themePart(pkg);
    return part == null || theme == null ? null : part.getFontFromTheme(theme);
  }

  private java.util.Map<String, String> equationThemeFonts(WordprocessingMLPackage pkg) throws Exception {
    var fonts = new LinkedHashMap<String, String>();
    for (String slot : List.of("majorAscii", "majorHAnsi", "minorAscii", "minorHAnsi")) {
      var theme = themeValue(slot);
      String font = themeFont(pkg, theme);
      if (font != null && !font.isBlank()) fonts.put(slot, font);
    }
    return Map.copyOf(fonts);
  }

  private String eastAsiaRunFont(RFonts fonts, WordprocessingMLPackage pkg) throws Exception {
    if (fonts == null) return null;
    String explicit = fonts.getEastAsia();
    if (explicit != null && !explicit.isBlank()) return explicit;
    var theme = fonts.getEastAsiaTheme();
    if (theme == null) return null;
    var part = themePart(pkg);
    if (part == null) return null;
    var relationships = pkg.getMainDocumentPart().getRelationshipsPart();
    var relationship = relationships.getRelationshipByType(
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/settings");
    if (relationship == null) relationship = relationships.getRelationshipByType(
        "http://purl.oclc.org/ooxml/officeDocument/relationships/settings");
    if (relationship == null) return null;
    var settings = (org.docx4j.openpackaging.parts.WordprocessingML.DocumentSettingsPart)
        relationships.getPart(relationship);
    return part.getFont(theme, settings.getJaxbElement().getThemeFontLang());
  }

  private String complexRunFont(RFonts fonts, WordprocessingMLPackage pkg) throws Exception {
    if (fonts == null) return null;
    String explicit = fonts.getCs();
    if (explicit != null && !explicit.isBlank()) return explicit;
    var theme = fonts.getCstheme();
    if (theme == null) return null;
    var part = themePart(pkg);
    if (part == null) return null;
    var relationships = pkg.getMainDocumentPart().getRelationshipsPart();
    var relationship = relationships.getRelationshipByType(
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/settings");
    if (relationship == null) relationship = relationships.getRelationshipByType(
        "http://purl.oclc.org/ooxml/officeDocument/relationships/settings");
    if (relationship == null) return null;
    var settings = (org.docx4j.openpackaging.parts.WordprocessingML.DocumentSettingsPart)
        relationships.getPart(relationship);
    return part.getFont(theme, settings.getJaxbElement().getThemeFontLang());
  }

  private org.docx4j.openpackaging.parts.ThemePart themePart(WordprocessingMLPackage pkg) {
    var relationships = pkg.getMainDocumentPart().getRelationshipsPart();
    var relationship = relationships.getRelationshipByType(
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme");
    if (relationship == null) relationship = relationships.getRelationshipByType(
        "http://purl.oclc.org/ooxml/officeDocument/relationships/theme");
    if (relationship == null) return null;
    return (org.docx4j.openpackaging.parts.ThemePart) relationships.getPart(relationship);
  }

  private String runFont(RFonts fonts, boolean highAnsi, WordprocessingMLPackage pkg) throws Exception {
    if (fonts == null) return null;
    String explicit = highAnsi ? fonts.getHAnsi() : fonts.getAscii();
    if (explicit != null && !explicit.isBlank()) return explicit;
    var theme = highAnsi ? fonts.getHAnsiTheme() : fonts.getAsciiTheme();
    if (theme == null) return null;
    return themeFont(pkg, theme);
  }

  private static Element directElement(Element parent, String namespace, String localName) {
    if (parent == null) return null;
    for (Element child : XmlPartSupport.children(parent))
      if (namespace.equals(child.getNamespaceURI()) && localName.equals(child.getLocalName())) return child;
    return null;
  }

  private static Element findStyle(Element styles, String styleId) {
    if (styles == null || styleId == null || styleId.isBlank()) return null;
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
      if (containsGeneratedTocField(elements.get(i))) continue;
      ParagraphModel paragraphModel = paragraphs.get(i);
      boolean automaticChapter = paragraphModel.automaticNumberingActive()
          && Integer.valueOf(0).equals(paragraphModel.outlineLevel())
          && paragraphModel.numberingLabel() != null
          && paragraphModel.numberingLabel().replaceAll("\\s+", "")
              .matches("(?:[0-9]+|第[0-9]+章)");
      String chapterText = automaticChapter && !text.matches("^第[0-9一二三四五六七八九十百千零〇]+章\\s+.*")
          ? "第" + automaticChapterNumber(paragraphModel.numberingLabel()) + "章 " + text : text;
      boolean numberedChapter = chapterText.matches("^第[0-9一二三四五六七八九十百千零〇]+章\\s+.*")
          && !text.matches(".*(?:\\.{2,}|……+)\\s*\\d+\\s*$");
      boolean mainAppendixTitle = text.codePoints()
          .filter(codePoint -> !Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint))
          .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
          .toString().equals("附录")
          || text.replaceAll("\\s+", "").equalsIgnoreCase("APPENDIX");
      if (!numberedChapter && !mainAppendixTitle) continue;
      P paragraph = (P) sourceParagraphs.get(i);
      PPr effectivePPr = resolver.getEffectivePPr(paragraph.getPPr());
      boolean pageBreak = effectivePPr != null && effectivePPr.getPageBreakBefore() != null
          && effectivePPr.getPageBreakBefore().isVal();
      if (i > 0) pageBreak |= startsOnNewPage((P) sourceParagraphs.get(i - 1));
      pageBreak |= startsWithPageBreak(elements.get(i));
      result.add(new HeadingBreakModel(new SourceAddress("word/document.xml", paragraphPaths.get(i), 0, chapterText.length()), chapterText, pageBreak));
    }
    return List.copyOf(result);
  }

  private static String automaticChapterNumber(String numberingLabel) {
    String compact = numberingLabel == null ? "" : numberingLabel.replaceAll("\\s+", "");
    if (compact.startsWith("第") && compact.endsWith("章"))
      return compact.substring(1, compact.length() - 1);
    return compact;
  }

  private static boolean startsOnNewPage(P paragraph) {
    SectPr section = paragraph.getPPr() == null ? null : paragraph.getPPr().getSectPr();
    if (section == null) return false;
    String type = section.getType() == null ? null : section.getType().getVal();
    return type == null || "nextPage".equals(type) || "oddPage".equals(type) || "evenPage".equals(type);
  }

  /**
   * A page break after the heading text starts the following page, not the
   * heading itself.  Only a page break encountered before the first visible
   * text is evidence that the heading starts on a new page.
   */
  private static boolean startsWithPageBreak(Element paragraph) {
    var state = new boolean[]{false, false};
    scanPageBreakOrder(paragraph, state);
    return state[0];
  }

  private static void scanPageBreakOrder(Node node, boolean[] state) {
    if (state[1]) return;
    if (node instanceof Element element
        && XmlPartSupport.W.equals(element.getNamespaceURI())
        && "br".equals(element.getLocalName())
        && "page".equals(element.getAttributeNS(XmlPartSupport.W, "type"))) {
      if (!state[1]) state[0] = true;
      return;
    }
    if (node instanceof Element element
        && XmlPartSupport.W.equals(element.getNamespaceURI())
        && "t".equals(element.getLocalName())
        && !element.getTextContent().isBlank()) {
      state[1] = true;
      return;
    }
    for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling())
      scanPageBreakOrder(child, state);
  }

  private static boolean containsGeneratedTocField(Element paragraph) {
    Element pPr = directElement(paragraph, XmlPartSupport.W, "pPr");
    Element pStyle = directElement(pPr, XmlPartSupport.W, "pStyle");
    if (pStyle != null) {
      String style = pStyle.getAttributeNS(XmlPartSupport.W, "val");
      if (style != null && style.toUpperCase(java.util.Locale.ROOT).startsWith("TOC")) return true;
    }
    var instructions = new StringBuilder();
    var fields = paragraph.getElementsByTagNameNS(XmlPartSupport.W, "instrText");
    for (int index = 0; index < fields.getLength(); index++) instructions.append(' ').append(fields.item(index).getTextContent());
    var simpleFields = paragraph.getElementsByTagNameNS(XmlPartSupport.W, "fldSimple");
    for (int index = 0; index < simpleFields.getLength(); index++)
      instructions.append(' ').append(((Element) simpleFields.item(index)).getAttributeNS(XmlPartSupport.W, "instr"));
    return java.util.regex.Pattern.compile("(?i)\\bTOC\\b").matcher(instructions).find();
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

  private static String normalizeAlignment(String value) {
    if (value == null) return "left";
    return switch (value) {
      case "start" -> "left";
      case "end" -> "right";
      default -> value;
    };
  }

  private static String effectiveLineRule(Element paragraph, Element styles) {
    Element paragraphProperties = directElement(paragraph, XmlPartSupport.W, "pPr");
    Element spacing = directElement(paragraphProperties, XmlPartSupport.W, "spacing");
    if (hasLineRule(spacing)) return spacing.getAttributeNS(XmlPartSupport.W, "lineRule");
    if (styles == null) return null;

    Element styleReference = directElement(paragraphProperties, XmlPartSupport.W, "pStyle");
    String styleId = styleReference == null ? defaultParagraphStyleId(styles)
        : styleReference.getAttributeNS(XmlPartSupport.W, "val");
    Set<String> visited = new HashSet<>();
    while (styleId != null && !styleId.isEmpty() && visited.add(styleId)) {
      Element style = paragraphStyle(styles, styleId);
      if (style == null) break;
      Element styleProperties = directElement(style, XmlPartSupport.W, "pPr");
      Element styleSpacing = directElement(styleProperties, XmlPartSupport.W, "spacing");
      if (hasLineRule(styleSpacing)) return styleSpacing.getAttributeNS(XmlPartSupport.W, "lineRule");
      Element basedOn = directElement(style, XmlPartSupport.W, "basedOn");
      styleId = basedOn == null ? null : basedOn.getAttributeNS(XmlPartSupport.W, "val");
    }

    Element defaults = directElement(styles, XmlPartSupport.W, "docDefaults");
    Element paragraphDefaults = directElement(defaults, XmlPartSupport.W, "pPrDefault");
    Element defaultProperties = directElement(paragraphDefaults, XmlPartSupport.W, "pPr");
    Element defaultSpacing = directElement(defaultProperties, XmlPartSupport.W, "spacing");
    return hasLineRule(defaultSpacing)
        ? defaultSpacing.getAttributeNS(XmlPartSupport.W, "lineRule") : null;
  }

  /**
   * Reads the line value from the same raw OOXML inheritance chain as the line
   * rule.  docx4j's effective PPr can merge a style value from a based-on style
   * over a paragraph style value, which loses the value Word actually applies.
   */
  private static LineSpacingEvidence effectiveLineSpacing(Element paragraph, Element styles) {
    Element paragraphProperties = directElement(paragraph, XmlPartSupport.W, "pPr");
    Element spacing = directElement(paragraphProperties, XmlPartSupport.W, "spacing");
    if (spacing != null && spacing.hasAttributeNS(XmlPartSupport.W, "line"))
      return lineSpacingEvidence(spacing);
    if (styles == null) return defaultLineSpacing();

    Element styleReference = directElement(paragraphProperties, XmlPartSupport.W, "pStyle");
    String styleId = styleReference == null ? defaultParagraphStyleId(styles)
        : styleReference.getAttributeNS(XmlPartSupport.W, "val");
    Set<String> visited = new HashSet<>();
    while (styleId != null && !styleId.isEmpty() && visited.add(styleId)) {
      Element style = paragraphStyle(styles, styleId);
      if (style == null) break;
      Element styleProperties = directElement(style, XmlPartSupport.W, "pPr");
      Element styleSpacing = directElement(styleProperties, XmlPartSupport.W, "spacing");
      if (styleSpacing != null && styleSpacing.hasAttributeNS(XmlPartSupport.W, "line"))
        return lineSpacingEvidence(styleSpacing);
      Element basedOn = directElement(style, XmlPartSupport.W, "basedOn");
      styleId = basedOn == null ? null : basedOn.getAttributeNS(XmlPartSupport.W, "val");
    }

    Element defaults = directElement(styles, XmlPartSupport.W, "docDefaults");
    Element paragraphDefaults = directElement(defaults, XmlPartSupport.W, "pPrDefault");
    Element defaultProperties = directElement(paragraphDefaults, XmlPartSupport.W, "pPr");
    Element defaultSpacing = directElement(defaultProperties, XmlPartSupport.W, "spacing");
    return defaultSpacing != null && defaultSpacing.hasAttributeNS(XmlPartSupport.W, "line")
        ? lineSpacingEvidence(defaultSpacing) : defaultLineSpacing();
  }

  private static LineSpacingEvidence defaultLineSpacing() {
    return new LineSpacingEvidence(240, false);
  }

  private static LineSpacingEvidence lineSpacingEvidence(Element spacing) {
    Integer value = XmlInteger.parse(spacing.getAttributeNS(XmlPartSupport.W, "line"));
    return new LineSpacingEvidence(value, value == null);
  }

  private static boolean hasLineRule(Element spacing) {
    return spacing != null && spacing.hasAttributeNS(XmlPartSupport.W, "lineRule");
  }

  private static String defaultParagraphStyleId(Element styles) {
    var candidates = styles.getElementsByTagNameNS(XmlPartSupport.W, "style");
    for (int index = 0; index < candidates.getLength(); index++) {
      Element style = (Element) candidates.item(index);
      if (!"paragraph".equals(style.getAttributeNS(XmlPartSupport.W, "type"))) continue;
      if (XmlOnOff.attribute(style, "default", false))
        return style.getAttributeNS(XmlPartSupport.W, "styleId");
    }
    return null;
  }

  private static Element paragraphStyle(Element styles, String styleId) {
    var candidates = styles.getElementsByTagNameNS(XmlPartSupport.W, "style");
    for (int index = 0; index < candidates.getLength(); index++) {
      Element style = (Element) candidates.item(index);
      if ("paragraph".equals(style.getAttributeNS(XmlPartSupport.W, "type"))
          && styleId.equals(style.getAttributeNS(XmlPartSupport.W, "styleId"))) return style;
    }
    return null;
  }

  private ParagraphModel parseParagraph(WordprocessingMLPackage pkg, P paragraph, Element xmlParagraph,
      Element stylesRoot, int index, List<Integer> path, NumberingState numberingState,
      boolean generatedTocField) throws Exception {
    var resolver = pkg.getMainDocumentPart().getPropertyResolver();
    PPr effectivePPr = resolver.getEffectivePPr(paragraph.getPPr());
    PPrBase.Ind ind = effectivePPr == null ? null : effectivePPr.getInd();
    Integer outlineLevel = effectivePPr == null || effectivePPr.getOutlineLvl() == null
        || effectivePPr.getOutlineLvl().getVal() == null ? null : XmlInteger.exact(effectivePPr.getOutlineLvl().getVal());
    // Resolve from source XML so an invalid w:jc value cannot be discarded by JAXB.
    String alignment = XmlAlignment.effective(xmlParagraph, stylesRoot);
    PPrBase.Ind directInd = paragraph.getPPr() == null ? null : paragraph.getPPr().getInd();
    boolean directFirstLineTwipsDefined = directInd != null && directInd.getFirstLine() != null;
    boolean directFirstLineCharsDefined = directInd != null && directInd.getFirstLineChars() != null;
    boolean effectiveFirstLineCharsDefined = ind != null && ind.getFirstLineChars() != null;
    boolean firstLineCharsActive = directFirstLineCharsDefined
        || (!directFirstLineTwipsDefined && effectiveFirstLineCharsDefined);
    BigInteger firstLineCharsXml = directFirstLineCharsDefined ? directInd.getFirstLineChars()
        : firstLineCharsActive && ind != null ? ind.getFirstLineChars() : null;
    Integer firstLineChars = firstLineCharsActive ? XmlInteger.exact(firstLineCharsXml) : null;

    boolean directHangingTwipsDefined = directInd != null && directInd.getHanging() != null;
    boolean directHangingCharsDefined = directInd != null && directInd.getHangingChars() != null;
    boolean effectiveHangingCharsDefined = ind != null && ind.getHangingChars() != null;
    boolean hangingCharsActive = directHangingCharsDefined
        || (!directHangingTwipsDefined && effectiveHangingCharsDefined);
    BigInteger hangingCharsXml = directHangingCharsDefined ? directInd.getHangingChars()
        : hangingCharsActive && ind != null ? ind.getHangingChars() : null;
    Integer hangingIndentChars = hangingCharsActive ? XmlInteger.exact(hangingCharsXml) : null;
    Integer hangingIndentTwips = hangingCharsActive || ind == null || ind.getHanging() == null
        ? null : XmlInteger.exact(ind.getHanging());

    boolean directLeftTwipsDefined = directInd != null && directInd.getLeft() != null;
    boolean directLeftCharsDefined = directInd != null && directInd.getLeftChars() != null;
    boolean effectiveLeftCharsDefined = ind != null && ind.getLeftChars() != null;
    boolean leftCharsActive = directLeftCharsDefined
        || (!directLeftTwipsDefined && effectiveLeftCharsDefined);
    BigInteger leftCharsXml = directLeftCharsDefined ? directInd.getLeftChars()
        : leftCharsActive && ind != null ? ind.getLeftChars() : null;
    Integer leftIndentChars = leftCharsActive ? XmlInteger.exact(leftCharsXml) : null;
    Integer leftIndentTwips = leftCharsActive || ind == null || ind.getLeft() == null
        ? null : XmlInteger.exact(ind.getLeft());
    boolean conflictingFirstLineEvidence = firstLineCharsActive && hangingCharsActive
        ? firstLineChars != null && firstLineChars > 0
            && hangingIndentChars != null && hangingIndentChars > 0
        : !firstLineCharsActive && !hangingCharsActive && ind != null
            && ind.getFirstLine() != null && ind.getHanging() != null
            && XmlInteger.exact(ind.getFirstLine()) != null && XmlInteger.exact(ind.getFirstLine()) > 0
            && XmlInteger.exact(ind.getHanging()) != null && XmlInteger.exact(ind.getHanging()) > 0;
    boolean firstLineEvidenceInvalid = conflictingFirstLineEvidence
        || (firstLineCharsActive && firstLineChars == null)
        || (!firstLineCharsActive && ind != null && ind.getFirstLine() != null
            && XmlInteger.exact(ind.getFirstLine()) == null)
        ;
    boolean hangingEvidenceInvalid = conflictingFirstLineEvidence
        || (hangingCharsActive && hangingIndentChars == null)
        || (!hangingCharsActive && hangingIndentTwips == null && ind != null && ind.getHanging() != null)
        ;
    boolean leftEvidenceInvalid = (leftCharsActive && leftIndentChars == null)
        || (!leftCharsActive && leftIndentTwips == null && ind != null && ind.getLeft() != null);
    LineSpacingEvidence lineSpacing = effectiveLineSpacing(xmlParagraph, stylesRoot);
    PPrBase.Spacing spacing = effectivePPr == null ? null : effectivePPr.getSpacing();
    Integer beforeSpacingTwips = spacing == null ? null : XmlInteger.exact(spacing.getBefore());
    Integer afterSpacingTwips = spacing == null ? null : XmlInteger.exact(spacing.getAfter());
    var runs = new ArrayList<RunModel>();
    int[] visibleTextOffset = {0};
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
            .map(content -> visibleRunText(content))
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
        String asciiFont;
        String highAnsiFont;
        String eastAsiaFont;
        String complexFont;
        try {
          asciiFont = runFont(fonts, false, pkg);
          highAnsiFont = runFont(fonts, true, pkg);
          eastAsiaFont = eastAsiaRunFont(fonts, pkg);
          complexFont = complexRunFont(fonts, pkg);
        } catch (Exception error) {
          throw new IllegalArgumentException("unable to resolve run font theme", error);
        }
        int start = visibleTextOffset[0];
        visibleTextOffset[0] += text.length();
        var source = new SourceAddress("word/document.xml", path, start, visibleTextOffset[0]);
        runs.add(new RunModel(text,
            asciiFont,
            highAnsiFont,
            eastAsiaFont,
            complexFont,
            properties == null || properties.getSz() == null ? null : XmlInteger.exact(properties.getSz().getVal()),
            properties == null || properties.getSzCs() == null ? null : XmlInteger.exact(properties.getSzCs().getVal()),
            properties != null && properties.getB() != null && properties.getB().isVal(),
            properties == null || properties.getVertAlign() == null || properties.getVertAlign().getVal() == null
                ? null : properties.getVertAlign().getVal().value(), source));
        return null;
      }
    });
    String numberingLabel = null;
    NumberingSpacingEvidence numberingSpacing = null;
    var numPr = effectivePPr == null ? null : effectivePPr.getNumPr();
    if (numPr == null || numPr.getNumId() == null || numPr.getNumId().getVal() == null) {
      PPrBase.NumPr styleNumPr = numberingFromParagraphStyle(stylesRoot, xmlParagraph);
      if (styleNumPr != null) {
        numPr = styleNumPr;
      }
    }
    if (numPr != null && numPr.getIlvl() == null) {
      PPrBase.NumPr.Ilvl value = new PPrBase.NumPr.Ilvl();
      value.setVal(java.math.BigInteger.ZERO);
      numPr.setIlvl(value);
    }
    PPr numberingPPr = effectivePPr == null ? new PPr() : effectivePPr;
    if (numPr != null) numberingPPr.setNumPr(numPr);
    boolean automaticNumberingActive = numPr != null
        && (numPr.getNumId() == null || numPr.getNumId().getVal() == null
            || numPr.getNumId().getVal().signum() != 0);
    if (automaticNumberingActive) {
      if (numPr.getNumId() != null && numPr.getNumId().getVal() != null)
        Emulator.getNumber(pkg, numberingPPr, numberingState);
      numberingLabel = currentNumberLabel(pkg, numberingPPr, numberingState);
      numberingSpacing = numberingSpacing(pkg, numberingPPr);
    }
    BigInteger firstLineTwipsXml = directInd != null && directInd.getFirstLine() != null
        ? directInd.getFirstLine() : ind == null ? null : ind.getFirstLine();
    Integer firstLineIndentTwips = firstLineCharsActive || firstLineTwipsXml == null
        ? null : XmlInteger.exact(firstLineTwipsXml);
    return new ParagraphModel(runs,
        firstLineIndentTwips,
        leftIndentTwips,
        leftIndentChars,
        ind == null || ind.getRight() == null ? null : XmlInteger.exact(ind.getRight()),
        lineSpacing.twips(),
        effectiveLineRule(xmlParagraph, stylesRoot),
        new SourceAddress("word/document.xml", path, 0, runs.stream().mapToInt(r -> r.text().length()).sum()),
        firstLineChars, outlineLevel, alignment, numberingLabel,
        numberingSpacing == null ? null : numberingSpacing.suffixSpaces(),
        numberingSpacing == null ? null : numberingSpacing.formatTrailingSpaces(),
        generatedTocField, hangingIndentTwips, hangingIndentChars,
        automaticNumberingActive, firstLineEvidenceInvalid, hangingEvidenceInvalid, leftEvidenceInvalid,
        beforeSpacingTwips, afterSpacingTwips, paragraphStyleId(xmlParagraph), lineSpacing.invalid(),
        isInsideTextBox(xmlParagraph), paragraphStyleName(stylesRoot, xmlParagraph));
  }

  private static boolean isInsideTextBox(Element element) {
    Node current = element;
    while (current != null) {
      if (XmlPartSupport.W.equals(current.getNamespaceURI())
          && "txbxContent".equals(current.getLocalName())) return true;
      current = current.getParentNode();
    }
    return false;
  }

  private static String paragraphStyleId(Element xmlParagraph) {
    Element pPr = directElement(xmlParagraph, XmlPartSupport.W, "pPr");
    Element pStyle = directElement(pPr, XmlPartSupport.W, "pStyle");
    if (pStyle == null) return null;
    String value = pStyle.getAttributeNS(XmlPartSupport.W, "val");
    return value.isBlank() ? null : value;
  }

  private static String paragraphStyleName(Element styles, Element xmlParagraph) {
    String styleId = paragraphStyleId(xmlParagraph);
    if (styleId == null) return null;
    Element style = paragraphStyle(styles, styleId);
    Element name = directElement(style, XmlPartSupport.W, "name");
    if (name == null) return null;
    String value = name.getAttributeNS(XmlPartSupport.W, "val");
    return value.isBlank() ? null : value;
  }

  /** Field instructions are metadata, not visible paragraph text. */
  private static String visibleRunText(Object content) {
    if (content instanceof JAXBElement<?> jaxb) {
      String localName = jaxb.getName().getLocalPart();
      if ("instrText".equals(localName) || "delInstrText".equals(localName)) return null;
    }
    Object unwrapped = XmlUtils.unwrap(content);
    String typeName = unwrapped == null ? "" : unwrapped.getClass().getSimpleName();
    if (unwrapped instanceof Br br)
      return br.getType() == STBrType.PAGE || br.getType() == STBrType.COLUMN ? null : "\n";
    if ("Cr".equals(typeName)) return "\n";
    return unwrapped instanceof Text value ? value.getValue()
        : unwrapped instanceof R.Tab ? "\t" : null;
  }

  private NumberingSpacingEvidence numberingSpacing(WordprocessingMLPackage pkg, PPr paragraphProperties) {
    var numPr = paragraphProperties.getNumPr();
    if (numPr == null || numPr.getNumId() == null || numPr.getNumId().getVal() == null) return null;
    var numberingPart = numberingDefinitionsPart(pkg);
    if (numberingPart == null || numberingPart.getJaxbElement() == null) return null;
    var definitions = numberingPart.getJaxbElement();
    var matchingNums = definitions.getNum().stream()
        .filter(candidate -> numPr.getNumId().getVal().equals(candidate.getNumId())).toList();
    if (matchingNums.size() != 1) return null;
    var num = matchingNums.get(0);
    if (num == null || num.getAbstractNumId() == null) return null;
    var matchingAbstractNums = definitions.getAbstractNum().stream()
        .filter(candidate -> num.getAbstractNumId().getVal().equals(candidate.getAbstractNumId())).toList();
    if (matchingAbstractNums.size() != 1) return null;
    var abstractNumbering = matchingAbstractNums.get(0);
    if (abstractNumbering == null) return null;
    if (numPr.getIlvl() == null || numPr.getIlvl().getVal() == null) return null;
    java.math.BigInteger levelNumber = numPr.getIlvl().getVal();
    var overrideDefinitions = num.getLvlOverride() == null
        ? List.<org.docx4j.wml.Numbering.Num.LvlOverride>of()
        : num.getLvlOverride();
    var overrideLevels = overrideDefinitions.stream()
        .filter(override -> levelNumber.equals(override.getIlvl()) && override.getLvl() != null)
        .map(org.docx4j.wml.Numbering.Num.LvlOverride::getLvl).toList();
    var abstractLevels = abstractNumbering.getLvl().stream()
        .filter(candidate -> levelNumber.equals(candidate.getIlvl())).toList();
    if (overrideLevels.size() > 1 || (overrideLevels.isEmpty() && abstractLevels.size() != 1)) return null;
    org.docx4j.wml.Lvl level = overrideLevels.isEmpty() ? abstractLevels.get(0) : overrideLevels.get(0);
    if (level == null || level.getLvlText() == null || level.getLvlText().getVal() == null) return null;
    var suffixValue = level.getSuff();
    String suffix = suffixValue == null || suffixValue.getVal() == null
        ? "tab" : suffixValue.getVal().toString();
    Integer suffixSpaces = "space".equals(suffix) ? Integer.valueOf(1)
        : "nothing".equals(suffix) || "tab".equals(suffix) ? Integer.valueOf(0) : null;
    String format = level.getLvlText().getVal();
    int trailingSpaces = 0;
    for (int index = format.length() - 1; index >= 0; index--) {
      char value = format.charAt(index);
      if (value == ' ') trailingSpaces++;
      else if (Character.isWhitespace(value) || Character.isSpaceChar(value)
          || XmlPartSupport.isInvisibleFormattingWhitespace(value))
        return new NumberingSpacingEvidence(suffixSpaces, null);
      else break;
    }
    return new NumberingSpacingEvidence(suffixSpaces, trailingSpaces);
  }

  /** Word allows a paragraph style (including a basedOn style) to own numPr. */
  private static PPrBase.NumPr numberingFromParagraphStyle(Element styles, Element xmlParagraph) {
    if (styles == null || xmlParagraph == null) return null;
    Element paragraphProperties = directElement(xmlParagraph, XmlPartSupport.W, "pPr");
    Element paragraphStyle = directElement(paragraphProperties, XmlPartSupport.W, "pStyle");
    if (paragraphStyle == null) return null;
    String styleId = paragraphStyle.getAttributeNS(XmlPartSupport.W, "val");
    Set<String> visited = new HashSet<>();
    while (styleId != null && !styleId.isBlank() && visited.add(styleId)) {
      Element style = paragraphStyle(styles, styleId);
      if (style == null) return null;
      Element stylePPr = directElement(style, XmlPartSupport.W, "pPr");
      Element styleNumPr = directElement(stylePPr, XmlPartSupport.W, "numPr");
      if (styleNumPr != null) {
        PPrBase.NumPr result = new PPrBase.NumPr();
        Element ilvl = directElement(styleNumPr, XmlPartSupport.W, "ilvl");
        if (ilvl != null) {
          PPrBase.NumPr.Ilvl value = new PPrBase.NumPr.Ilvl();
          Integer parsed = XmlInteger.parse(ilvl.getAttributeNS(XmlPartSupport.W, "val"));
          value.setVal(parsed == null ? null : java.math.BigInteger.valueOf(parsed));
          result.setIlvl(value);
        }
        // Word permits a style-level numPr to omit ilvl; that means the
        // paragraph uses the list's root level. Keep the value explicit for
        // docx4j's numbering emulator and for exact label extraction.
        if (result.getIlvl() == null) {
          PPrBase.NumPr.Ilvl value = new PPrBase.NumPr.Ilvl();
          value.setVal(java.math.BigInteger.ZERO);
          result.setIlvl(value);
        }
        Element numId = directElement(styleNumPr, XmlPartSupport.W, "numId");
        if (numId != null) {
          PPrBase.NumPr.NumId value = new PPrBase.NumPr.NumId();
          Integer parsed = XmlInteger.parse(numId.getAttributeNS(XmlPartSupport.W, "val"));
          value.setVal(parsed == null ? null : java.math.BigInteger.valueOf(parsed));
          result.setNumId(value);
        }
        return result;
      }
      Element basedOn = directElement(style, XmlPartSupport.W, "basedOn");
      styleId = basedOn == null ? null : basedOn.getAttributeNS(XmlPartSupport.W, "val");
    }
    return null;
  }

  private String currentNumberLabel(WordprocessingMLPackage pkg, PPr paragraphProperties,
      NumberingState numberingState) {
    var numPr = paragraphProperties.getNumPr();
    if (numPr == null || numPr.getNumId() == null || numPr.getNumId().getVal() == null) return null;
    var part = numberingDefinitionsPart(pkg);
    if (part == null) return null;
    var definitions = part.getJaxbElement();
    if (definitions == null) return null;
    var nums = definitions.getNum().stream()
        .filter(candidate -> numPr.getNumId().getVal().equals(candidate.getNumId())).toList();
    if (nums.size() != 1 || nums.get(0).getAbstractNumId() == null) return null;
    var abstractNums = definitions.getAbstractNum().stream()
        .filter(candidate -> nums.get(0).getAbstractNumId().getVal().equals(candidate.getAbstractNumId())).toList();
    if (abstractNums.size() != 1) return null;
    String numberId = numPr.getNumId().getVal().toString();
    if (numPr.getIlvl() == null || numPr.getIlvl().getVal() == null) return null;
    String level = numPr.getIlvl().getVal().toString();
    var instance = part.getInstanceListDefinitions().get(numberId);
    if (instance == null) return null;
    return instance.getCurrentNumberString(level, numberingState);
  }

  private org.docx4j.openpackaging.parts.WordprocessingML.NumberingDefinitionsPart numberingDefinitionsPart(
      WordprocessingMLPackage pkg) {
    var relationships = pkg.getMainDocumentPart().getRelationshipsPart();
    var relationship = relationships.getRelationshipByType(
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering");
    if (relationship == null) relationship = relationships.getRelationshipByType(
        "http://purl.oclc.org/ooxml/officeDocument/relationships/numbering");
    if (relationship == null) return null;
    return (org.docx4j.openpackaging.parts.WordprocessingML.NumberingDefinitionsPart)
        relationships.getPart(relationship);
  }

  private record NumberingSpacingEvidence(Integer suffixSpaces, Integer formatTrailingSpaces) { }
  private record LineSpacingEvidence(Integer twips, boolean invalid) { }
  private record FontResolution(boolean defined, String name) { }
  private record FooterKey(String partName, PageNumberModel.FooterVariant variant) { }

  private SectionModel parseSection(SectPr section, Element rawSection, SourceAddress source, boolean terminal,
      boolean evenAndOddHeaders) {
    SectPr.PgMar margins = section.getPgMar();
    var pageNumberType = section.getPgNumType();
    var pageNumberStartXml = pageNumberType == null ? null : pageNumberType.getStart();
    var pageNumberStart = pageNumberStartXml == null ? null : XmlInteger.exact(pageNumberStartXml);
    var pageNumberStartInvalid = pageNumberStartXml != null && pageNumberStart == null;
    Element rawPageNumber = directElement(rawSection, XmlPartSupport.W, "pgNumType");
    boolean pageNumberFormatInvalid = !XmlEnum.isValid(rawPageNumber, "fmt", PAGE_NUMBER_FORMATS);
    String rawFormat = pageNumberFormatInvalid ? null : XmlEnum.value(rawPageNumber, "fmt", PAGE_NUMBER_FORMATS);
    boolean topMarginInvalid = invalidMargin(rawSection, "top", margins == null ? null : margins.getTop());
    boolean rightMarginInvalid = invalidMargin(rawSection, "right", margins == null ? null : margins.getRight());
    boolean bottomMarginInvalid = invalidMargin(rawSection, "bottom", margins == null ? null : margins.getBottom());
    boolean leftMarginInvalid = invalidMargin(rawSection, "left", margins == null ? null : margins.getLeft());
    return new SectionModel(
        marginValue(rawSection, "top", margins == null ? null : margins.getTop()),
        marginValue(rawSection, "right", margins == null ? null : margins.getRight()),
        marginValue(rawSection, "bottom", margins == null ? null : margins.getBottom()),
        marginValue(rawSection, "left", margins == null ? null : margins.getLeft()),
        margins == null ? null : margins.getGutter() == null ? 0 : XmlInteger.exact(margins.getGutter()),
        source,
        rawFormat != null ? rawFormat : pageNumberType == null || pageNumberType.getFmt() == null ? null : pageNumberType.getFmt().value(),
        pageNumberFormatInvalid,
        pageNumberStart,
        pageNumberStartInvalid,
        terminal,
        topMarginInvalid,
        rightMarginInvalid,
        bottomMarginInvalid,
        leftMarginInvalid,
        XmlOnOff.enabled(directElement(rawSection, XmlPartSupport.W, "titlePg")),
        evenAndOddHeaders);
  }

  private static Integer marginValue(Element rawSection, String side, BigInteger jaxbValue) {
    Element rawMargins = directElement(rawSection, XmlPartSupport.W, "pgMar");
    if (rawMargins != null && rawMargins.hasAttributeNS(XmlPartSupport.W, side))
      return XmlInteger.parse(rawMargins.getAttributeNS(XmlPartSupport.W, side));
    return XmlInteger.exact(jaxbValue);
  }

  private static boolean invalidMargin(Element rawSection, String side, BigInteger jaxbValue) {
    Element rawMargins = directElement(rawSection, XmlPartSupport.W, "pgMar");
    if (rawMargins != null && rawMargins.hasAttributeNS(XmlPartSupport.W, side))
      return XmlInteger.parse(rawMargins.getAttributeNS(XmlPartSupport.W, side)) == null;
    return jaxbValue != null && XmlInteger.exact(jaxbValue) == null;
  }

  private static void collectParagraphXml(Element element, List<Integer> path,
      List<List<Integer>> paths, List<Element> paragraphs) {
    if ("http://schemas.openxmlformats.org/markup-compatibility/2006".equals(element.getNamespaceURI())
        && "Fallback".equals(element.getLocalName())) return;
    if ("http://schemas.openxmlformats.org/wordprocessingml/2006/main".equals(element.getNamespaceURI())
        && "p".equals(element.getLocalName())) {
      paths.add(path);
      paragraphs.add(element);
    }
    int index = 0;
    for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
      if (child instanceof Element childElement) {
        var childPath = new ArrayList<>(path);
        childPath.add(index++);
        collectParagraphXml(childElement, List.copyOf(childPath), paths, paragraphs);
      }
    }
  }
}
