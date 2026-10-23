package cn.luwneshacha.thesis.rules;

import static org.junit.jupiter.api.Assertions.*;

import cn.luwneshacha.thesis.document.Docx4jDocumentParser;
import cn.luwneshacha.thesis.document.DocxReader;
import cn.luwneshacha.thesis.inspection.RuleCatalog;
import java.nio.file.Path;
import java.util.List;
import org.docx4j.XmlUtils;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.openpackaging.parts.WordprocessingML.NumberingDefinitionsPart;
import org.docx4j.wml.Document;
import org.docx4j.wml.Numbering;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AutomaticNumberingPolicyTest {
  private static final String NS = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"";
  @TempDir Path temp;

  @ParameterizedTest
  @ValueSource(strings = {"space", "tab", "nothing"})
  void prohibitsAutomaticHeadingListAndReferencesRegardlessOfSeparator(String suffix) throws Exception {
    Path input = fixture(suffix,
        paragraph("<w:outlineLvl w:val=\"0\"/>" + numPr(1), " 引言")
            + paragraph(numPr(2), "正文列表内容")
            + paragraph("", "已有研究[1]。")
            + paragraph("", "参考文献")
            + paragraph(numPr(3), "作者. 文献题名[J]. 期刊, 2024, 1(1): 1-3."));
    var parsed = new Docx4jDocumentParser().parse(new DocxReader().read(input));
    var results = new RuleEngine().check(parsed, RuleSet.schoolV1());
    var findings = results.stream().filter(r -> r.ruleId().equals("NUMBERING-01")).toList();

    assertEquals(3, findings.size(), "即使编号后空格正确，全文自动编号仍必须报告");
    for (int i = 0; i < findings.size(); i++) {
      var result = findings.get(i);
      assertEquals(Judgement.FAIL, result.judgement());
      assertEquals("AUTOMATIC_NUMBERING_FORBIDDEN", result.reasonCode());
      assertEquals(parsed.paragraphs().get(List.of(0, 1, 4).get(i)).source().elementPath(),
          result.source().elementPath());
      assertNotNull(RuleCatalog.find(result.ruleId()));
    }
    assertFalse(results.stream().anyMatch(r -> r.ruleId().equals("HEAD-01-SPACING")));
    assertFalse(results.stream().anyMatch(r -> r.reasonCode().startsWith("REFERENCE_NUMBER_SPACING")),
        "不能一边要求禁用自动编号，一边建议修改它的分隔符");
    assertEquals("1", parsed.references().getFirst().number(), "保留自动编号用于文献核对");
    assertFalse(results.stream().anyMatch(r -> r.reasonCode().equals("REFERENCE_NUMBER_SEQUENCE_MISMATCH")));
    assertTrue(results.stream().anyMatch(r -> r.ruleId().equals("HEADING-SIZE")
        && r.judgement() == Judgement.FAIL), "禁止编号不能遮蔽独立标题格式错误");
  }

  @Test
  void retainsManualNumberSpacingChecksEvenWhenUnusedNumberingDefinitionsExist() throws Exception {
    Path input = fixture("space", paragraph("", "第一章  引言")
        + paragraph("", "参考文献")
        + paragraph("", "[1]作者. 文献题名[J]. 期刊, 2024, 1(1): 1-3."));
    var results = new RuleEngine().check(
        new Docx4jDocumentParser().parse(new DocxReader().read(input)), RuleSet.schoolV1());
    assertFalse(results.stream().anyMatch(r -> r.ruleId().equals("NUMBERING-01")
        && r.judgement() != Judgement.PASS));
    assertTrue(results.stream().anyMatch(r -> r.ruleId().equals("HEAD-01-SPACING")
        && r.judgement() == Judgement.PASS));
    assertTrue(results.stream().anyMatch(r -> r.reasonCode().equals("REFERENCE_NUMBER_SPACING_MISMATCH")
        && r.judgement() == Judgement.FAIL));
  }

  private Path fixture(String suffix, String body) throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body>" + body + "</w:body></w:document>"));
    var numbering = new NumberingDefinitionsPart();
    var definitions = new StringBuilder();
    for (int id = 1; id <= 3; id++) {
      String label = id == 1 ? "第%1章" : id == 3 ? "[%1]" : "%1.";
      definitions.append("<w:abstractNum w:abstractNumId=\"").append(id)
          .append("\"><w:lvl w:ilvl=\"0\"><w:start w:val=\"1\"/><w:numFmt w:val=\"decimal\"/>")
          .append("<w:lvlText w:val=\"").append(label).append("\"/><w:suff w:val=\"")
          .append(suffix).append("\"/></w:lvl></w:abstractNum>");
    }
    for (int id = 1; id <= 3; id++) definitions.append("<w:num w:numId=\"").append(id)
        .append("\"><w:abstractNumId w:val=\"").append(id).append("\"/></w:num>");
    numbering.setJaxbElement((Numbering) XmlUtils.unmarshalString(
        "<w:numbering " + NS + ">" + definitions + "</w:numbering>"));
    pkg.getMainDocumentPart().addTargetPart(numbering);
    Path file = temp.resolve("numbering-" + suffix + ".docx");
    pkg.save(file.toFile());
    return file;
  }

  @Test
  void retainsLiteralHeadingAndReferenceSpacingWhenTheListIsOnlyABullet() throws Exception {
    Path input = fixture("space", paragraph("", "第一章  引言")
        + paragraph("<w:outlineLvl w:val=\"1\"/>" + numPr(2), "1.1标题")
        + paragraph("", "参考文献")
        + paragraph(numPr(3), "[1]作者. 文献题名[J]. 期刊, 2024, 1(1): 1-3."));
    var pkg = WordprocessingMLPackage.load(input.toFile());
    var definitions = pkg.getMainDocumentPart().getNumberingDefinitionsPart().getJaxbElement();
    for (int index : List.of(1, 2)) {
      var level = definitions.getAbstractNum().get(index).getLvl().getFirst();
      level.getNumFmt().setVal(org.docx4j.wml.NumberFormat.BULLET);
      level.getLvlText().setVal("•");
    }
    pkg.save(input.toFile());
    var results = new RuleEngine().check(
        new Docx4jDocumentParser().parse(new DocxReader().read(input)), RuleSet.schoolV1());
    assertFalse(results.stream().anyMatch(r -> r.ruleId().equals("NUMBERING-01")));
    assertTrue(results.stream().anyMatch(r -> r.ruleId().equals("HEAD-02-SPACING")
        && r.judgement() == Judgement.FAIL), "纯项目符号不能遮蔽实际手写标题的缺空格问题");
    assertTrue(results.stream().anyMatch(r -> r.reasonCode().equals("REFERENCE_NUMBER_SPACING_MISMATCH")),
        "纯项目符号不能遮蔽实际手写文献编号的缺空格问题");
  }

  private static String numPr(int id) {
    return "<w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"" + id + "\"/></w:numPr>";
  }

  private static String paragraph(String properties, String text) {
    return "<w:p><w:pPr>" + properties + "</w:pPr><w:r><w:rPr>"
        + "<w:rFonts w:eastAsia=\"宋体\" w:ascii=\"Times New Roman\"/><w:sz w:val=\"24\"/>"
        + "</w:rPr><w:t xml:space=\"preserve\">" + text + "</w:t></w:r></w:p>";
  }
}
