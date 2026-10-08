package cn.luwneshacha.thesis.document;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import jakarta.xml.bind.JAXBElement;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
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
      return new ParsedDocument(raw.sourceSha256(), paragraphs, sections, List.of(),
          new TableParser().parse(documentXml), new EquationParser().parse(documentXml),
          new ReferenceParser().parse(paragraphs), new ImageParser().parse(documentXml), parsePageNumbers(raw),
          parseChapterBreaks(documentXml, pkg, finder.results, paragraphPaths, paragraphs));
    } catch (Exception error) {
      throw new IllegalArgumentException("unable to parse DOCX: " + error.getMessage(), error);
    }
  }

  private List<PageNumberModel> parsePageNumbers(RawDocx raw) {
    var result = new ArrayList<PageNumberModel>();
    for (RawPart part : raw.parts().values()) {
      if (!part.name().startsWith("word/footer") || !part.name().endsWith(".xml")) continue;
      try {
        Element root = parseXml(part.bytes());
        if (!hasPageField(root)) continue;
        Element paragraph = firstElement(root, XmlPartSupport.W, "p");
        Element jc = paragraph == null ? null : firstElement(paragraph, XmlPartSupport.W, "jc");
        boolean centered = jc != null && "center".equals(jc.getAttributeNS(XmlPartSupport.W, "val"));
        Element rFonts = firstElement(root, XmlPartSupport.W, "rFonts");
        Element sz = firstElement(root, XmlPartSupport.W, "sz");
        Integer size = sz == null ? null : parseInt(sz.getAttributeNS(XmlPartSupport.W, "val"));
        String font = rFonts == null ? null : rFonts.getAttributeNS(XmlPartSupport.W, "ascii");
        result.add(new PageNumberModel(new SourceAddress(part.name(), List.of(), 0, 0), true, centered, font, size));
      } catch (Exception error) {
        throw new IllegalArgumentException("unable to parse footer part " + part.name(), error);
      }
    }
    return List.copyOf(result);
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
    var factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes)).getDocumentElement();
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
            properties != null && properties.getB() != null && properties.getB().isVal(), source));
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
        firstLineChars);
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
    var factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    Element root = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml)).getDocumentElement();
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
