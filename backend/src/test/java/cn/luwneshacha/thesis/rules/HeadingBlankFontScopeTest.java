package cn.luwneshacha.thesis.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.luwneshacha.thesis.document.Docx4jDocumentParser;
import cn.luwneshacha.thesis.document.DocxReader;
import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.RunModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import java.nio.file.Path;
import java.util.List;
import org.docx4j.XmlUtils;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class HeadingBlankFontScopeTest {
  private static final RuleSet RULES = RuleSet.schoolV1();
  private static final String FONT = "HEAD-02-BLANK-FONT";
  private static final String SIZE = "HEAD-02-BLANK-SIZE";
  @TempDir Path temp;

  @ParameterizedTest
  @ValueSource(strings = {"paragraphMark", "emptyRunWithParagraphMark", "whitespaceRun",
      "unicodeWhitespaceRun", "manualBreakRun", "mixedTitleAndBreakRun"})
  void acceptsCorrectChineseBlankLineTypographyInRealDocxRegardlessOfUnusedLatinFont(
      String carrier) throws Exception {
    String properties = "<w:rPr><w:rFonts w:ascii='黑体' w:hAnsi='黑体' w:eastAsia='黑体'/>"
        + "<w:sz w:val='32'/></w:rPr>";
    String title = "<w:p><w:r><w:t xml:space='preserve'>摘    要</w:t></w:r></w:p>";
    String blank = switch (carrier) {
      case "paragraphMark" -> "<w:p><w:pPr>" + properties + "</w:pPr></w:p>";
      case "emptyRunWithParagraphMark" -> "<w:p><w:pPr>" + properties
          + "</w:pPr><w:r>" + properties + "</w:r></w:p>";
      case "whitespaceRun" -> "<w:p><w:r>" + properties
          + "<w:t xml:space='preserve'> </w:t></w:r></w:p>";
      case "unicodeWhitespaceRun" -> "<w:p><w:r>" + properties
          + "<w:t xml:space='preserve'> &#160;　</w:t></w:r></w:p>";
      case "manualBreakRun" -> "<w:p><w:r><w:t xml:space='preserve'>摘    要</w:t></w:r>"
          + "<w:r>" + properties + "<w:br/><w:br/></w:r></w:p>";
      case "mixedTitleAndBreakRun" -> "<w:p><w:r>" + properties
          + "<w:t xml:space='preserve'>摘    要</w:t><w:br/><w:br/></w:r></w:p>";
      default -> throw new IllegalArgumentException(carrier);
    };
    if (carrier.endsWith("BreakRun")) title = "";
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document xmlns:w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'>"
            + "<w:body>" + title + blank
            + "<w:p><w:r><w:t>摘要正文</w:t></w:r></w:p><w:sectPr/></w:body></w:document>"));
    Path file = temp.resolve(carrier + ".docx");
    pkg.save(file.toFile());
    var parsed = new Docx4jDocumentParser().parse(new DocxReader().read(file));
    if (carrier.equals("paragraphMark")) assertTrue(parsed.paragraphs().get(1).runs().isEmpty());
    var blankParagraph = parsed.paragraphs().get(carrier.endsWith("BreakRun") ? 0 : 1);
    var blankFormat = blankParagraph.runs().isEmpty()
        ? blankParagraph.blankParagraphMarkFormat() : blankParagraph.runs().getLast();
    assertNotNull(blankFormat);
    assertEquals("黑体", blankFormat.eastAsiaFont());
    assertEquals("黑体", blankFormat.asciiFont());
    assertEquals(32, blankFormat.fontSizeHalfPoints());

    var evaluation = new RuleEngine().checkWithExecution(parsed, RULES);

    assertJudgement(evaluation.results(), FONT, Judgement.PASS);
    assertJudgement(evaluation.results(), SIZE, Judgement.PASS);
    assertEquals("黑体", finding(evaluation.results(), FONT).expected());
    assertEquals(new RuleRun(FONT, "HeadingDocumentRuleModule", 1, 1, 0, null),
        evaluation.ruleRuns().stream().filter(run -> FONT.equals(run.ruleId()))
            .findFirst().orElseThrow());
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"黑体", "Arial"})
  void doesNotRequireAnUnusedLatinFontForASpaceOnlyBlankLine(String asciiFont) {
    var blank = paragraph(" ", asciiFont, "黑体", 32, 1);

    var findings = check(blank);

    assertJudgement(findings, FONT, Judgement.PASS);
    assertJudgement(findings, SIZE, Judgement.PASS);
  }

  @Test
  void retainsReviewWhenTheRequiredChineseBlankFontIsUnknown() {
    var findings = check(paragraph(" ", null, null, 32, 1));

    assertJudgement(findings, FONT, Judgement.REVIEW);
    assertEquals("FIRST_LEVEL_BLANK_LINE_FONT_UNKNOWN", finding(findings, FONT).reasonCode());
    assertJudgement(findings, SIZE, Judgement.PASS);
  }

  @ParameterizedTest
  @CsvSource({"宋体, 32, FAIL, PASS", "黑体, 24, PASS, FAIL"})
  void stillChecksChineseFontAndSizeIndependently(String chineseFont, int size,
      Judgement fontJudgement, Judgement sizeJudgement) {
    var findings = check(paragraph(" ", null, chineseFont, size, 1));

    assertJudgement(findings, FONT, fontJudgement);
    assertJudgement(findings, SIZE, sizeJudgement);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void retainsTheLatinFontRequirementForRealHeadingAndBodyCharacters(boolean manualBreak) {
    var title = paragraph("第一章  ABC研究" + (manualBreak ? "\n\n" : ""),
        "Arial", "黑体", 32, 0);
    var blank = paragraph(" ", "Arial", "黑体", 32, 1);
    var body = paragraph("正文 ABC123", "Arial", "宋体", 24, 2);
    var document = new ParsedDocument("source",
        manualBreak ? List.of(title, body) : List.of(title, blank, body), List.of(), List.of());

    var findings = new RuleEngine().check(document, RULES);

    assertJudgement(findings, FONT, Judgement.PASS);
    assertJudgement(findings, "HEADING-FONT", Judgement.FAIL);
    assertJudgement(findings, "BODY-FONT", Judgement.FAIL);
  }

  private static List<CheckResult> check(ParagraphModel blank) {
    var title = paragraph("第一章  绪论", "Times New Roman", "黑体", 32, 0);
    var body = paragraph("正文内容", "Times New Roman", "宋体", 24, 2);
    return new RuleEngine().check(new ParsedDocument("source", List.of(title, blank, body),
        List.of(), List.of()), RULES);
  }

  private static ParagraphModel paragraph(String text, String asciiFont, String chineseFont,
      int size, int index) {
    var source = new SourceAddress("word/document.xml", List.of(0, index), 0, text.length());
    var run = new RunModel(text, asciiFont, chineseFont, null, size, false, source);
    return new ParagraphModel(List.of(run), 0, null, null, null, 360, "auto", source,
        0, null, "center");
  }

  private static void assertJudgement(List<CheckResult> findings, String ruleId,
      Judgement judgement) {
    CheckResult result = finding(findings, ruleId);
    assertEquals(judgement, result.judgement(), result.toString());
  }

  private static CheckResult finding(List<CheckResult> findings, String ruleId) {
    var matching = findings.stream().filter(result -> ruleId.equals(result.ruleId())).toList();
    assertEquals(1, matching.size(), matching.toString());
    return matching.getFirst();
  }
}
