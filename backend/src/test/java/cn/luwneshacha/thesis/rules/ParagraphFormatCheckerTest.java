package cn.luwneshacha.thesis.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.RunModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

class ParagraphFormatCheckerTest {
  private static final SourceAddress SOURCE = new SourceAddress("word/document.xml", List.of(0), 0, 3);

  private final ParagraphFormatChecker checker = new ParagraphFormatChecker();
  private final RuleSet rules = RuleSet.schoolV1();

  @Test
  void checksFontAndSizeAgainstTheSelectedRuleSet() {
    var run = new RunModel("论文 ABC", "Times New Roman", "宋体", null, 24, false, SOURCE);

    assertEquals(Judgement.PASS, checker.checkFont(run, rules, "BODY-FONT").judgement());
    assertEquals(Judgement.PASS, checker.checkSize(run, rules, "BODY-SIZE").judgement());
  }

  @Test
  void checksParagraphIndentAndLineSpacingWithCallerSuppliedRuleIds() {
    var run = new RunModel("正文", "Times New Roman", "宋体", null, 24, false, SOURCE);
    var paragraph = new ParagraphModel(List.of(run), 480, null, null, null, 360, "auto", SOURCE, 200,
        null, "both");

    assertEquals(Judgement.PASS, checker.checkIndent(paragraph, rules, "BODY-INDENT").judgement());
    assertEquals(Judgement.PASS, checker.checkLineSpacing("BODY-LINE", paragraph).judgement());
  }

  @Test
  void ignoresSuperscriptFontSizeWhenMatchingTwoCharacterIndent() {
    var citation = new RunModel("[1]", "Times New Roman", "宋体", null, 15, false, "superscript", SOURCE);
    var body = new RunModel("正文内容", "Times New Roman", "宋体", null, 21, false, SOURCE);
    var paragraph = new ParagraphModel(List.of(citation, body), 420, null, null, null, 360, "auto",
        SOURCE, null, null, "both");

    CheckResult result = checker.checkIndent(paragraph, rules, "BODY-INDENT");

    assertEquals(Judgement.PASS, result.judgement());
  }

  @Test
  void doesNotLetSmallerSuperscriptMakeTwelvePointBodyIndentPassAtFourHundredTwentyTwips() {
    var citation = new RunModel("[1]", "Times New Roman", "宋体", null, 15, false, "superscript", SOURCE);
    var body = new RunModel("正文内容", "Times New Roman", "宋体", null, 24, false, SOURCE);
    var paragraph = new ParagraphModel(List.of(citation, body), 420, null, null, null, 360, "auto",
        SOURCE, null, null, "both");

    CheckResult result = checker.checkIndent(paragraph, rules, "BODY-INDENT");

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("INDENT_MISMATCH", result.reasonCode());
    assertTrue(result.expected().contains("480 twips"), result.expected());
  }

  @Test
  void treatsMissingBodyIndentAsZeroWhenTheSchoolRequiresTwoCharacters() {
    var paragraph = new ParagraphModel(List.of(), null, null, null, null, 360, "auto", SOURCE,
        null, null, "left");

    CheckResult result = checker.checkIndent(paragraph, rules, "BODY-INDENT");

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("INDENT_MISMATCH", result.reasonCode());
    assertEquals("0", result.actual());
  }

  @Test
  void preservesWholeParagraphLeftIndentWhenFirstLineIndentIsMissing() {
    var paragraph = new ParagraphModel(List.of(), null, 720, null, 360, "auto", SOURCE, null);

    CheckResult result = checker.checkIndent(paragraph, rules, "BODY-INDENT");

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("INDENT_MISMATCH", result.reasonCode());
    assertTrue(result.actual().contains("整体左缩进 720 twips"), result.actual());
  }

  @Test
  void reportsLeadingTextWhitespaceSeparatelyWhenParagraphIndentIsCorrect() {
    var paragraph = new ParagraphModel(List.of(new RunModel(" \u4e2d\u6587", "Times New Roman",
        "\u5b8b\u4f53", null, 24, false, SOURCE)), 480, null, null, null, 360, "auto", SOURCE,
        200, null, "left");

    CheckResult result = checker.checkIndent(paragraph, rules, "BODY-INDENT");

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("INDENT_TEXT_WHITESPACE", result.reasonCode());
    assertTrue(result.actual().contains("480 twips"), result.actual());
    assertTrue(result.actual().contains("1"), result.actual());
  }

  @Test
  void reportsBothIndentAndTextWhitespaceWhenBothAreWrong() {
    var paragraph = new ParagraphModel(List.of(new RunModel(" \u4e2d\u6587", "Times New Roman",
        "\u5b8b\u4f53", null, 24, false, SOURCE)), 420, null, null, null, 360, "auto", SOURCE,
        null, null, "left");

    CheckResult result = checker.checkIndent(paragraph, rules, "BODY-INDENT");

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("INDENT_MISMATCH", result.reasonCode());
    assertTrue(result.actual().contains("420 twips"), result.actual());
    assertTrue(result.actual().contains("1"), result.actual());
  }

  @Test
  void treatsMissingIndentAsZeroWhenARequiredIndentIsExplicit() {
    var paragraph = new ParagraphModel(List.of(), null, null, null, null, 360, "auto", SOURCE,
        null, null, "left");

    CheckResult result = checker.checkExactFirstLineIndent(paragraph, 200, 480, "TOC-ENTRY-INDENT");

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("INDENT_MISMATCH", result.reasonCode());
    assertEquals("0", result.actual());
  }

  @Test
  void reportsMissingLineSpacingEvidenceForReview() {
    var paragraph = new ParagraphModel(List.of(), null, null, null, null, null, null, SOURCE, null,
        null, "left");

    CheckResult result = checker.checkLineSpacing("ABSTRACT-LINE", paragraph);

    assertEquals(Judgement.REVIEW, result.judgement());
    assertEquals("LINE_SPACING_UNKNOWN", result.reasonCode());
    assertEquals("ABSTRACT-LINE", result.ruleId());
  }

  @Test
  void checksExplicitSizesForSemanticRules() {
    var correct = new RunModel("标题", "Times New Roman", "黑体", null, 32, false, SOURCE);
    var wrong = new RunModel("标题", "Times New Roman", "黑体", null, 30, false, SOURCE);

    assertEquals(Judgement.PASS, checker.checkExpectedSize(correct, 32, "COVER-01-SIZE").judgement());
    assertEquals(Judgement.FAIL, checker.checkExpectedSize(wrong, 32, "COVER-01-SIZE").judgement());
  }

  @Test
  void explicitSizeCheckUsesComplexScriptSizeEvidence() {
    var wrongComplexSize = new RunModel("العربية", "Times New Roman", "Times New Roman", "宋体",
        "Times New Roman", 32, 30, false, null, SOURCE);

    CheckResult result = checker.checkExpectedSize(wrongComplexSize, 32, "COVER-01-SIZE");

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("SIZE_MISMATCH", result.reasonCode());
  }

  @Test
  void checksFontAgainstCallerSuppliedFontContract() {
    var heading = new RunModel("标题", "Calibri", "宋体", null, 32, false, SOURCE);

    CheckResult result = checker.checkFont(heading, "黑体", "Times New Roman", "HEADING-FONT");

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("FONT_MISMATCH", result.reasonCode());
  }

  @Test
  void reportsMissingAndWrongChineseFontEvidence() {
    var missing = new RunModel("\u4e2d\u6587", "Times New Roman", null, null, 24, false, SOURCE);
    var wrong = new RunModel("\u4e2d\u6587", "Times New Roman", "Arial", null, 24, false, SOURCE);

    CheckResult unknown = checker.checkFont(missing, rules, "BODY-FONT");
    assertEquals("FONT_UNKNOWN", unknown.reasonCode());
    assertEquals(Judgement.REVIEW, unknown.judgement());
    assertEquals("FONT_MISMATCH", checker.checkFont(wrong, rules, "BODY-FONT").reasonCode());
  }

  @Test
  void reportsMissingAndWrongSizeEvidence() {
    var missing = new RunModel("\u4e2d\u6587", "Times New Roman", "\u5b8b\u4f53", null, null, false, SOURCE);
    var wrong = new RunModel("\u4e2d\u6587", "Times New Roman", "\u5b8b\u4f53", null, 22, false, SOURCE);

    CheckResult unknown = checker.checkSize(missing, rules, "BODY-SIZE");
    assertEquals("SIZE_UNKNOWN", unknown.reasonCode());
    assertEquals(Judgement.REVIEW, unknown.judgement());
    assertEquals("SIZE_MISMATCH", checker.checkSize(wrong, rules, "BODY-SIZE").reasonCode());
  }

  @Test
  void rejectsPhysicalIndentOneTwipAwayFromTheExactCharacterWidth() {
    var run = new RunModel("\u4e2d\u6587", "Times New Roman", "\u5b8b\u4f53", null, 21, false, SOURCE);
    var paragraph = new ParagraphModel(List.of(run), 421, null, null, null, 360, "auto", SOURCE, null,
        null, "left");

    CheckResult result = checker.checkIndent(paragraph, rules, "BODY-INDENT");

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("INDENT_MISMATCH", result.reasonCode());
  }

  @Test
  void rejectsCharacterIndentThatIsOneHundredNinetyNineChars() {
    var run = new RunModel("\u4e2d\u6587", "Times New Roman", "\u5b8b\u4f53", null, 24, false, SOURCE);
    var paragraph = new ParagraphModel(List.of(run), null, null, null, null, 360, "auto", SOURCE, 199,
        null, "left");

    CheckResult result = checker.checkIndent(paragraph, rules, "BODY-INDENT");

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("INDENT_MISMATCH", result.reasonCode());
  }

  @Test
  void rejectsFullWidthAndZeroWidthLeadingWhitespace() {
    var fullWidth = new ParagraphModel(List.of(new RunModel("\u3000\u4e2d\u6587", "Times New Roman",
        "\u5b8b\u4f53", null, 24, false, SOURCE)), 480, null, null, null, 360, "auto", SOURCE, 200,
        null, "left");
    var zeroWidth = new ParagraphModel(List.of(new RunModel("\u200b\u4e2d\u6587", "Times New Roman",
        "\u5b8b\u4f53", null, 24, false, SOURCE)), 480, null, null, null, 360, "auto", SOURCE, 200,
        null, "left");

    assertEquals("INDENT_TEXT_WHITESPACE", checker.checkIndent(fullWidth, rules, "BODY-INDENT").reasonCode());
    assertEquals("INDENT_TEXT_WHITESPACE", checker.checkIndent(zeroWidth, rules, "BODY-INDENT").reasonCode());
  }

  @Test
  void reportsActualIndentAndLeadingWhitespaceTogether() {
    var paragraph = new ParagraphModel(List.of(new RunModel(" 正文", "Times New Roman", "宋体",
        null, 24, false, SOURCE)), 420, null, null, null, 360, "auto", SOURCE, null,
        null, "left");

    CheckResult result = checker.checkIndent(paragraph, rules, "ABSTRACT-INDENT");

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("INDENT_MISMATCH", result.reasonCode());
    assertTrue(result.expected().contains("480 twips"), result.expected());
    assertTrue(result.actual().contains("420 twips"), result.actual());
    assertTrue(result.actual().contains("1 个文字空白"), result.actual());
  }

  @Test
  void rejectsConflictingIndentEvidence() {
    var run = new RunModel("\u4e2d\u6587", "Times New Roman", "\u5b8b\u4f53", null, 24, false, SOURCE);
    var paragraph = new ParagraphModel(List.of(run), 480, null, null, null, 360, "auto", SOURCE, 200,
        null, "left", null, null, null, false, null, null, false, true, false, false);

    CheckResult result = checker.checkIndent(paragraph, rules, "BODY-INDENT");

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("INDENT_EVIDENCE_INVALID", result.reasonCode());
  }

  @Test
  void headingIndentUsesTheSameEvidenceChecksAsBodyIndent() {
    var run = new RunModel("2.2.1 标题", "Times New Roman", "宋体", null, 24, false, SOURCE);
    var paragraph = new ParagraphModel(List.of(run), 480, null, null, null, 360, "auto", SOURCE, 200,
        null, "left", null, null, null, false, null, null, false, false, true, false);

    CheckResult result = checker.checkHeadingIndent(paragraph, rules, "HEAD-05-INDENT");

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("INDENT_EVIDENCE_INVALID", result.reasonCode());
  }

  @Test
  void rejectsLineSpacingOneTwipBelowTheExactValue() {
    var paragraph = new ParagraphModel(List.of(), null, null, null, null, 359, "auto", SOURCE, null,
        null, "left");

    CheckResult result = checker.checkLineSpacing("BODY-LINE", paragraph);

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("LINE_SPACING_MISMATCH", result.reasonCode());
  }

  @Test
  void rejectsNonAutomaticLineSpacingEvenAtTheExpectedTwips() {
    var paragraph = new ParagraphModel(List.of(), null, null, null, null, 360, "exact", SOURCE, null,
        null, "left");

    CheckResult result = checker.checkLineSpacing("BODY-LINE", paragraph);

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("LINE_SPACING_MISMATCH", result.reasonCode());
  }
}
