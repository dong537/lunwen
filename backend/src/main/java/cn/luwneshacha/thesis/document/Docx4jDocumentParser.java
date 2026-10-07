package cn.luwneshacha.thesis.document;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.docx4j.TraversalUtil;
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
      for (int index = 0; index < finder.results.size(); index++) {
        P paragraph = (P) finder.results.get(index);
        List<Integer> path = index < paragraphPaths.size() ? paragraphPaths.get(index) : List.of(index);
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
      return new ParsedDocument(raw.sourceSha256(), paragraphs, sections, List.of());
    } catch (Exception error) {
      throw new IllegalArgumentException("unable to parse DOCX: " + error.getMessage(), error);
    }
  }

  private ParagraphModel parseParagraph(WordprocessingMLPackage pkg, P paragraph, int index, List<Integer> path) throws Exception {
    var resolver = pkg.getMainDocumentPart().getPropertyResolver();
    PPr effectivePPr = resolver.getEffectivePPr(paragraph.getPPr());
    PPrBase.Ind ind = effectivePPr == null ? null : effectivePPr.getInd();
    PPrBase.Ind directInd = paragraph.getPPr() == null ? null : paragraph.getPPr().getInd();
    Integer firstLineChars = null;
    if (ind != null && ind.getFirstLineChars() != null) firstLineChars = ind.getFirstLineChars().intValue();
    else if (directInd != null && directInd.getFirstLineChars() != null) firstLineChars = directInd.getFirstLineChars().intValue();
    PPrBase.Spacing spacing = effectivePPr == null ? null : effectivePPr.getSpacing();
    var runs = new ArrayList<RunModel>();
    for (Object child : paragraph.getContent()) {
      Object unwrapped = XmlUtils.unwrap(child);
      if (!(unwrapped instanceof R run)) continue;
      String text = run.getContent().stream()
          .map(XmlUtils::unwrap)
          .filter(Text.class::isInstance)
          .map(Text.class::cast)
          .map(Text::getValue)
          .filter(Objects::nonNull)
          .reduce("", String::concat);
      RPr properties = resolver.getEffectiveRPr(run.getRPr(), paragraph.getPPr());
      RFonts fonts = properties == null ? null : properties.getRFonts();
      var source = new SourceAddress("word/document.xml", path, 0, text.length());
      runs.add(new RunModel(text,
          fonts == null ? null : fonts.getAscii(),
          fonts == null ? null : fonts.getEastAsia(),
          fonts == null ? null : fonts.getCs(),
          properties == null || properties.getSz() == null ? null : properties.getSz().getVal().intValue(),
          properties != null && properties.getB() != null && properties.getB().isVal(), source));
    }
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
        source);
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
