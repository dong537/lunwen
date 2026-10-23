package cn.luwneshacha.thesis.report;

import cn.luwneshacha.thesis.inspection.InspectionReport;
import cn.luwneshacha.thesis.inspection.RuleCatalog;
import cn.luwneshacha.thesis.reporting.AnnotationReport;
import cn.luwneshacha.thesis.rules.Judgement;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.LinkedHashMap;
import org.docx4j.jaxb.Context;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.BooleanDefaultTrue;
import org.docx4j.wml.CTBorder;
import org.docx4j.wml.CTShd;
import org.docx4j.wml.CTTblLayoutType;
import org.docx4j.wml.HpsMeasure;
import org.docx4j.wml.JcEnumeration;
import org.docx4j.wml.ObjectFactory;
import org.docx4j.wml.P;
import org.docx4j.wml.STBorder;
import org.docx4j.wml.STLineSpacingRule;
import org.docx4j.wml.STShd;
import org.docx4j.wml.STTblLayoutType;
import org.docx4j.wml.STVerticalJc;
import org.docx4j.wml.SectPr;
import org.docx4j.wml.Tbl;
import org.docx4j.wml.TblBorders;
import org.docx4j.wml.TblWidth;
import org.docx4j.wml.Tr;

/** Generates a compact Chinese summary independently of annotation write outcomes. */
public final class WordReportWriter {
  private static final int[] COLUMN_WIDTHS = {1800, 6000, 1200};
  private final ObjectFactory factory = Context.getWmlObjectFactory();

  public void write(InspectionReport report, Path target) throws IOException {
    write(report, target, null);
  }

  /** The annotation argument is retained for existing callers; it is not report content. */
  public void write(InspectionReport report, Path target, AnnotationReport annotationReport) throws IOException {
    Path destination = target.toAbsolutePath();
    Files.createDirectories(destination.getParent());
    Path temp = Files.createTempFile(destination.getParent(), "thesis-report-", ".tmp");
    try {
      WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
      configurePage(pkg);
      var body = pkg.getMainDocumentPart().getContent();
      body.add(paragraph("论文格式检查汇总", true, JcEnumeration.CENTER, true));
      long failures = report.results().stream().filter(result -> result.judgement() == Judgement.FAIL).count();
      long reviews = report.results().stream().filter(result -> result.judgement() == Judgement.REVIEW).count();
      body.add(paragraph("明确问题总数：" + failures + " 项", false, JcEnumeration.LEFT, false));
      body.add(paragraph("待人工确认总数：" + reviews + " 项（不计入问题数量）", false, JcEnumeration.LEFT, false));

      var counts = new LinkedHashMap<String, Long>();
      report.results().stream().filter(result -> result.judgement() == Judgement.FAIL)
          .forEach(result -> counts.merge(result.ruleId(), 1L, Long::sum));
      Tbl table = table();
      table.getContent().add(row(true, "检查模块", "中文规范要求", "问题数量"));
      if (counts.isEmpty()) {
        table.getContent().add(row(false, "—", "未发现可确认的格式问题", "0"));
      } else {
        counts.forEach((ruleId, count) -> {
          var rule = ruleId == null ? null : RuleCatalog.find(ruleId);
          table.getContent().add(row(false, rule == null ? "其他检查项" : rule.module(),
              RuleSummaryDescriptions.requirement(ruleId), count.toString()));
        });
      }
      body.add(table);
      pkg.save(temp.toFile());
      Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (Exception error) {
      try {
        Files.deleteIfExists(temp);
      } catch (IOException cleanupError) {
        error.addSuppressed(cleanupError);
      }
      if (error instanceof IOException io) throw io;
      throw new IOException("cannot generate Word report", error);
    }
  }

  private void configurePage(WordprocessingMLPackage pkg) {
    SectPr section = factory.createSectPr();
    var pageSize = new SectPr.PgSz();
    pageSize.setW(BigInteger.valueOf(11906));
    pageSize.setH(BigInteger.valueOf(16838));
    section.setPgSz(pageSize);
    var margins = new SectPr.PgMar();
    margins.setTop(BigInteger.valueOf(1440));
    margins.setBottom(BigInteger.valueOf(1440));
    margins.setLeft(BigInteger.valueOf(1440));
    margins.setRight(BigInteger.valueOf(1440));
    margins.setHeader(BigInteger.valueOf(720));
    margins.setFooter(BigInteger.valueOf(720));
    margins.setGutter(BigInteger.ZERO);
    section.setPgMar(margins);
    pkg.getMainDocumentPart().getJaxbElement().getBody().setSectPr(section);
  }

  private P paragraph(String value, boolean bold, JcEnumeration alignment, boolean title) {
    P paragraph = factory.createP();
    var properties = factory.createPPr();
    var spacing = factory.createPPrBaseSpacing();
    spacing.setBefore(BigInteger.ZERO);
    spacing.setAfter(BigInteger.valueOf(title ? 240 : 80));
    spacing.setLine(BigInteger.valueOf(280));
    spacing.setLineRule(STLineSpacingRule.AUTO);
    properties.setSpacing(spacing);
    var justification = factory.createJc();
    justification.setVal(alignment);
    properties.setJc(justification);
    if (title) {
      var style = factory.createPPrBasePStyle();
      style.setVal("Title");
      properties.setPStyle(style);
      properties.setKeepNext(new BooleanDefaultTrue());
    }
    paragraph.setPPr(properties);
    var run = factory.createR();
    var text = factory.createText();
    text.setValue(value);
    text.setSpace("preserve");
    run.getContent().add(text);
    var runProperties = factory.createRPr();
    var fonts = factory.createRFonts();
    fonts.setEastAsia(title ? "黑体" : "宋体");
    fonts.setAscii("Times New Roman");
    fonts.setHAnsi("Times New Roman");
    runProperties.setRFonts(fonts);
    HpsMeasure size = factory.createHpsMeasure();
    size.setVal(BigInteger.valueOf(title ? 32 : 22));
    runProperties.setSz(size);
    runProperties.setSzCs(size);
    var color = factory.createColor();
    color.setVal("000000");
    runProperties.setColor(color);
    if (bold) runProperties.setB(new BooleanDefaultTrue());
    run.setRPr(runProperties);
    paragraph.getContent().add(run);
    return paragraph;
  }

  private Tbl table() {
    Tbl table = factory.createTbl();
    var properties = factory.createTblPr();
    properties.setTblW(width(Arrays.stream(COLUMN_WIDTHS).sum()));
    var layout = new CTTblLayoutType();
    layout.setType(STTblLayoutType.FIXED);
    properties.setTblLayout(layout);
    var borders = new TblBorders();
    borders.setTop(border());
    borders.setBottom(border());
    borders.setLeft(border());
    borders.setRight(border());
    borders.setInsideH(border());
    borders.setInsideV(border());
    properties.setTblBorders(borders);
    table.setTblPr(properties);
    var grid = factory.createTblGrid();
    for (int columnWidth : COLUMN_WIDTHS) {
      var column = factory.createTblGridCol();
      column.setW(BigInteger.valueOf(columnWidth));
      grid.getGridCol().add(column);
    }
    table.setTblGrid(grid);
    return table;
  }

  private Tr row(boolean header, String... values) {
    Tr row = factory.createTr();
    if (header) {
      var properties = factory.createTrPr();
      properties.getCnfStyleOrDivIdOrGridBefore().add(
          factory.createCTTrPrBaseTblHeader(new BooleanDefaultTrue()));
      row.setTrPr(properties);
    }
    for (int index = 0; index < values.length; index++) {
      var cell = factory.createTc();
      var properties = factory.createTcPr();
      properties.setTcW(width(COLUMN_WIDTHS[index]));
      var margins = factory.createTcMar();
      margins.setTop(width(100));
      margins.setBottom(width(100));
      margins.setLeft(width(100));
      margins.setRight(width(100));
      properties.setTcMar(margins);
      var alignment = factory.createCTVerticalJc();
      alignment.setVal(STVerticalJc.CENTER);
      properties.setVAlign(alignment);
      if (header) {
        CTShd shading = factory.createCTShd();
        shading.setVal(STShd.CLEAR);
        shading.setFill("E7ECF3");
        properties.setShd(shading);
      }
      cell.setTcPr(properties);
      P paragraph = paragraph(values[index], header,
          header || index != 1 ? JcEnumeration.CENTER : JcEnumeration.LEFT, false);
      paragraph.getPPr().getSpacing().setAfter(BigInteger.ZERO);
      cell.getContent().add(paragraph);
      row.getContent().add(cell);
    }
    return row;
  }

  private CTBorder border() {
    CTBorder border = factory.createCTBorder();
    border.setVal(STBorder.SINGLE);
    border.setColor("D9D9D9");
    border.setSz(BigInteger.valueOf(4));
    return border;
  }

  private TblWidth width(long value) {
    TblWidth width = factory.createTblWidth();
    width.setW(BigInteger.valueOf(value));
    width.setType(TblWidth.TYPE_DXA);
    return width;
  }
}



