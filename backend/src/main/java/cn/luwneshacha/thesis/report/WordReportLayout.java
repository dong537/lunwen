package cn.luwneshacha.thesis.report;

import java.math.BigInteger;
import java.util.Arrays;
import org.docx4j.jaxb.Context;
import org.docx4j.openpackaging.exceptions.Docx4JException;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.openpackaging.parts.WordprocessingML.FooterPart;
import org.docx4j.wml.BooleanDefaultTrue;
import org.docx4j.wml.CTBorder;
import org.docx4j.wml.CTShd;
import org.docx4j.wml.CTTblLayoutType;
import org.docx4j.wml.HdrFtrRef;
import org.docx4j.wml.HpsMeasure;
import org.docx4j.wml.JcEnumeration;
import org.docx4j.wml.ObjectFactory;
import org.docx4j.wml.P;
import org.docx4j.wml.R;
import org.docx4j.wml.STBorder;
import org.docx4j.wml.STLineSpacingRule;
import org.docx4j.wml.STPageOrientation;
import org.docx4j.wml.STShd;
import org.docx4j.wml.STTblLayoutType;
import org.docx4j.wml.SectPr;
import org.docx4j.wml.Tbl;
import org.docx4j.wml.TblBorders;
import org.docx4j.wml.TblWidth;
import org.docx4j.wml.Tr;

/** Report presentation only: inspection decisions and annotation matching stay in the writer. */
final class WordReportLayout {
  private static final int PAGE_WIDTH = 11906;
  private static final int PAGE_HEIGHT = 16838;
  private static final int MARGIN = 720;
  static final int CONTENT_WIDTH = 10400;
  private static final String ACCENT = "3FB0BF";
  private static final String ACCENT_TEXT = "216B76";
  private static final String TINT = "EAF5F7";
  private static final String BORDER = "D7E2E5";
  private final ObjectFactory factory = Context.getWmlObjectFactory();

  enum TextRole { TITLE, SECTION, BODY, AUXILIARY, TABLE_HEADER, TABLE_BODY, TABLE_AUXILIARY }

  void configurePage(WordprocessingMLPackage pkg) throws Docx4JException {
    var body = pkg.getMainDocumentPart().getJaxbElement().getBody();
    SectPr section = body.getSectPr();
    if (section == null) {
      section = factory.createSectPr();
      body.setSectPr(section);
    }
    var size = new SectPr.PgSz();
    size.setW(BigInteger.valueOf(PAGE_WIDTH));
    size.setH(BigInteger.valueOf(PAGE_HEIGHT));
    size.setOrient(STPageOrientation.PORTRAIT);
    section.setPgSz(size);
    var margins = new SectPr.PgMar();
    margins.setTop(BigInteger.valueOf(MARGIN));
    margins.setBottom(BigInteger.valueOf(MARGIN));
    margins.setLeft(BigInteger.valueOf(MARGIN));
    margins.setRight(BigInteger.valueOf(MARGIN));
    margins.setHeader(BigInteger.valueOf(360));
    margins.setFooter(BigInteger.valueOf(360));
    margins.setGutter(BigInteger.ZERO);
    section.setPgMar(margins);
    section.setTitlePg(null);

    var footer = new FooterPart();
    var footerContent = factory.createFtr();
    P pageNumber = paragraph("- ", TextRole.AUXILIARY);
    pageNumber.getPPr().getJc().setVal(JcEnumeration.CENTER);
    var field = factory.createCTSimpleField();
    field.setInstr(" PAGE ");
    field.setDirty(true);
    field.getContent().add(run("1", TextRole.AUXILIARY));
    pageNumber.getContent().add(factory.createPFldSimple(field));
    pageNumber.getContent().add(run(" -", TextRole.AUXILIARY));
    footerContent.getContent().add(pageNumber);
    footer.setJaxbElement(footerContent);
    var relationship = pkg.getMainDocumentPart().addTargetPart(footer);
    var reference = factory.createFooterReference();
    reference.setId(relationship.getId());
    reference.setType(HdrFtrRef.DEFAULT);
    section.getEGHdrFtrReferences().add(reference);
  }

  P paragraph(String value, TextRole role) {
    P paragraph = factory.createP();
    var properties = factory.createPPr();
    var spacing = factory.createPPrBaseSpacing();
    spacing.setBefore(BigInteger.valueOf(role == TextRole.SECTION ? 200 : 0));
    spacing.setAfter(BigInteger.valueOf(switch (role) {
      case TITLE -> 180;
      case SECTION -> 100;
      case BODY, AUXILIARY -> 60;
      default -> 0;
    }));
    spacing.setLine(BigInteger.valueOf(280));
    spacing.setLineRule(STLineSpacingRule.AUTO);
    properties.setSpacing(spacing);
    var alignment = factory.createJc();
    alignment.setVal(role == TextRole.TITLE ? JcEnumeration.CENTER : JcEnumeration.LEFT);
    properties.setJc(alignment);
    if (role == TextRole.TITLE || role == TextRole.SECTION || role == TextRole.TABLE_HEADER) {
      properties.setKeepNext(new BooleanDefaultTrue());
    }
    if (role == TextRole.SECTION) {
      properties.setShd(shading(TINT));
      var borders = factory.createPPrBasePBdr();
      borders.setTop(border(ACCENT, 8));
      properties.setPBdr(borders);
    }
    paragraph.setPPr(properties);
    String[] lines = (value == null ? "" : value).split("\\R", -1);
    for (int i = 0; i < lines.length; i++) {
      if (i > 0) {
        R lineBreak = factory.createR();
        lineBreak.getContent().add(factory.createBr());
        paragraph.getContent().add(lineBreak);
      }
      paragraph.getContent().add(run(lines[i], role));
    }
    return paragraph;
  }

  Tbl table(int... widths) {
    int total = Arrays.stream(widths).sum();
    if (widths.length == 0 || Arrays.stream(widths).anyMatch(width -> width <= 0)
        || total > PAGE_WIDTH - 2 * MARGIN) {
      throw new IllegalArgumentException("report table columns must fit the page");
    }
    Tbl table = factory.createTbl();
    var properties = factory.createTblPr();
    properties.setTblW(width(total));
    var layout = new CTTblLayoutType();
    layout.setType(STTblLayoutType.FIXED);
    properties.setTblLayout(layout);
    var borders = new TblBorders();
    borders.setTop(border(BORDER, 4));
    borders.setBottom(border(BORDER, 4));
    borders.setLeft(border(BORDER, 4));
    borders.setRight(border(BORDER, 4));
    borders.setInsideH(border(BORDER, 4));
    borders.setInsideV(border(BORDER, 4));
    properties.setTblBorders(borders);
    table.setTblPr(properties);
    var grid = factory.createTblGrid();
    for (int value : widths) {
      var column = factory.createTblGridCol();
      column.setW(BigInteger.valueOf(value));
      grid.getGridCol().add(column);
    }
    table.setTblGrid(grid);
    return table;
  }

  void addHeader(Tbl table, String... values) {
    addRow(table, TextRole.TABLE_HEADER, values);
  }

  void addRow(Tbl table, TextRole role, String... values) {
    var columns = table.getTblGrid().getGridCol();
    if (values.length != columns.size()) {
      throw new IllegalArgumentException("table row width does not match its grid");
    }
    boolean header = role == TextRole.TABLE_HEADER;
    Tr row = factory.createTr();
    if (header) {
      var properties = factory.createTrPr();
      properties.getCnfStyleOrDivIdOrGridBefore().add(
          factory.createCTTrPrBaseTblHeader(new BooleanDefaultTrue()));
      row.setTrPr(properties);
    }
    for (int i = 0; i < values.length; i++) {
      var cell = factory.createTc();
      var properties = factory.createTcPr();
      properties.setTcW(width(columns.get(i).getW().intValueExact()));
      var margins = factory.createTcMar();
      margins.setTop(width(80));
      margins.setBottom(width(80));
      margins.setLeft(width(100));
      margins.setRight(width(100));
      properties.setTcMar(margins);
      if (header) properties.setShd(shading(TINT));
      cell.setTcPr(properties);
      cell.getContent().add(paragraph(values[i], role));
      row.getContent().add(cell);
    }
    table.getContent().add(row);
  }

  private R run(String value, TextRole role) {
    R run = factory.createR();
    var text = factory.createText();
    text.setValue(value);
    text.setSpace("preserve");
    run.getContent().add(text);
    var properties = factory.createRPr();
    var fonts = factory.createRFonts();
    fonts.setEastAsia(role == TextRole.TITLE || role == TextRole.SECTION ? "黑体" : "宋体");
    fonts.setAscii("Times New Roman");
    fonts.setHAnsi("Times New Roman");
    properties.setRFonts(fonts);
    int halfPoints = switch (role) {
      case TITLE -> 36;
      case SECTION -> 24;
      case AUXILIARY, TABLE_AUXILIARY -> 18;
      case TABLE_HEADER -> 20;
      default -> 21;
    };
    var size = new HpsMeasure();
    size.setVal(BigInteger.valueOf(halfPoints));
    properties.setSz(size);
    properties.setSzCs(size);
    if (role == TextRole.TITLE || role == TextRole.SECTION || role == TextRole.TABLE_HEADER) {
      properties.setB(new BooleanDefaultTrue());
    }
    var color = factory.createColor();
    color.setVal(switch (role) {
      case SECTION, TABLE_HEADER -> ACCENT_TEXT;
      case AUXILIARY, TABLE_AUXILIARY -> "595959";
      default -> "202020";
    });
    properties.setColor(color);
    run.setRPr(properties);
    return run;
  }

  private CTShd shading(String fill) {
    var shading = new CTShd();
    shading.setVal(STShd.CLEAR);
    shading.setColor("auto");
    shading.setFill(fill);
    return shading;
  }

  private CTBorder border(String color, int size) {
    var border = new CTBorder();
    border.setVal(STBorder.SINGLE);
    border.setColor(color);
    border.setSz(BigInteger.valueOf(size));
    border.setSpace(BigInteger.ZERO);
    return border;
  }

  private TblWidth width(int value) {
    TblWidth width = factory.createTblWidth();
    width.setType(TblWidth.TYPE_DXA);
    width.setW(BigInteger.valueOf(value));
    return width;
  }
}
