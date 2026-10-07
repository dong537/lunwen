package cn.luwneshacha.thesis.document;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
      for (int index = 0; index < finder.results.size(); index++) {
        P paragraph = (P) finder.results.get(index);
        paragraphs.add(parseParagraph(pkg, paragraph, index));
        if (paragraph.getPPr() != null && paragraph.getPPr().getSectPr() != null) {
          sections.add(parseSection(paragraph.getPPr().getSectPr(), sections.size()));
        }
      }
      var body = main.getJaxbElement().getBody();
      if (body.getSectPr() != null) sections.add(parseSection(body.getSectPr(), sections.size()));
      return new ParsedDocument(raw.sourceSha256(), paragraphs, sections, List.of());
    } catch (Exception error) {
      throw new IllegalArgumentException("unable to parse DOCX: " + error.getMessage(), error);
    }
  }

  private ParagraphModel parseParagraph(WordprocessingMLPackage pkg, P paragraph, int index) throws Exception {
    var resolver = pkg.getMainDocumentPart().getPropertyResolver();
    PPr effectivePPr = resolver.getEffectivePPr(paragraph.getPPr());
    PPrBase.Ind ind = effectivePPr == null ? null : effectivePPr.getInd();
    PPrBase.Spacing spacing = effectivePPr == null ? null : effectivePPr.getSpacing();
    var runs = new ArrayList<RunModel>();
    int runIndex = 0;
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
      var source = new SourceAddress("word/document.xml", List.of(index, runIndex++), 0, text.length());
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
        new SourceAddress("word/document.xml", List.of(index), 0, runs.stream().mapToInt(r -> r.text().length()).sum()));
  }

  private SectionModel parseSection(SectPr section, int index) {
    SectPr.PgMar margins = section.getPgMar();
    return new SectionModel(
        margins == null || margins.getTop() == null ? null : margins.getTop().intValue(),
        margins == null || margins.getRight() == null ? null : margins.getRight().intValue(),
        margins == null || margins.getBottom() == null ? null : margins.getBottom().intValue(),
        margins == null || margins.getLeft() == null ? null : margins.getLeft().intValue(),
        new SourceAddress("word/document.xml", List.of(index, 0), 0, 0));
  }
}
