package cn.luwneshacha.thesis.rules;

import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.ReferenceEntryModel;
import cn.luwneshacha.thesis.document.RunModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReferenceFormatRuleCheckerTest {
  private static final SourceAddress SOURCE =
      new SourceAddress("word/document.xml", List.of(0, 1), 0, 60);

  @Test
  void checksEastAsianPunctuationEvenWhenItIsInItsOwnRun() {
    var results = check(List.of(
        run("[1] 作者", "Times New Roman", "宋体", 24),
        run("，", "Times New Roman", "黑体", 24),
        run("题名[J].期刊", "Times New Roman", "宋体", 24)));

    assertTrue(results.stream().anyMatch(result -> "REF-FONT".equals(result.ruleId())
        && result.judgement() == Judgement.FAIL
        && "FONT_MISMATCH".equals(result.reasonCode())), results.toString());
  }

  @Test
  void checksPunctuationSizeWithoutRequiringChineseCharactersInItsRun() {
    var results = check(List.of(run("[1] 作者", "Times New Roman", "宋体", 24),
        run("。", "Times New Roman", "宋体", 21)));

    assertTrue(results.stream().anyMatch(result -> "REF-SIZE".equals(result.ruleId())
        && result.judgement() == Judgement.FAIL), results.toString());
  }

  @Test
  void checksHalfWidthPunctuationInItsOwnRunAgainstTimesNewRoman() {
    var results = check(List.of(run("[1] 作者", "Times New Roman", "宋体", 24),
        run(".", "宋体", "宋体", 24)));

    assertTrue(results.stream().anyMatch(result -> "REF-FONT".equals(result.ruleId())
        && "FONT_MISMATCH".equals(result.reasonCode())
        && result.judgement() == Judgement.FAIL), results.toString());
  }

  @Test
  void checksUnicodeHalfWidthPunctuationUsingItsEastAsianFontSlot() {
    var correct = check(List.of(run("｡｢｣､･", "Times New Roman", "Times New Roman", 24)));
    assertTrue(correct.stream().filter(result -> "REF-FONT".equals(result.ruleId()))
        .allMatch(result -> result.judgement() == Judgement.PASS), correct.toString());

    // With eastAsia=Times New Roman and ascii=hAnsi, Word actually uses ascii.
    var wrong = check(List.of(run("｡｢｣､･", "宋体", "宋体", "Times New Roman", 24)));
    assertTrue(wrong.stream().anyMatch(result -> "REF-FONT".equals(result.ruleId())
        && result.judgement() == Judgement.FAIL), wrong.toString());

    var unknown = check(List.of(run("｡｢｣､･", "Times New Roman", null, 24)));
    assertTrue(unknown.stream().anyMatch(result -> "REF-FONT".equals(result.ruleId())
        && result.judgement() == Judgement.REVIEW), unknown.toString());

    var unresolvedOverride = check(List.of(run("｡｢｣､･", "宋体", null,
        "Times New Roman", 24)));
    assertTrue(unresolvedOverride.stream().anyMatch(result -> "REF-FONT".equals(result.ruleId())
        && result.judgement() == Judgement.REVIEW), unresolvedOverride.toString());

    var noOverride = check(List.of(run("｡｢｣､･", "宋体", "Times New Roman",
        "Times New Roman", 24)));
    assertTrue(noOverride.stream().filter(result -> "REF-FONT".equals(result.ruleId()))
        .allMatch(result -> result.judgement() == Judgement.PASS), noOverride.toString());
  }

  @Test
  void fullWidthPunctuationStillUsesChineseFont() {
    var correct = check(List.of(run("．，：", "Times New Roman", "宋体", 24)));
    assertTrue(correct.stream().filter(result -> "REF-FONT".equals(result.ruleId()))
        .allMatch(result -> result.judgement() == Judgement.PASS), correct.toString());

    var fallback = check(List.of(run("．，：", "宋体", "宋体", "Times New Roman", 24)));
    assertTrue(fallback.stream().filter(result -> "REF-FONT".equals(result.ruleId()))
        .allMatch(result -> result.judgement() == Judgement.PASS), fallback.toString());

    var fallbackWithChinese = check(List.of(run("题名．", "宋体", "宋体",
        "Times New Roman", 24)));
    assertTrue(fallbackWithChinese.stream().filter(result -> "REF-FONT".equals(result.ruleId()))
        .allMatch(result -> result.judgement() == Judgement.PASS), fallbackWithChinese.toString());

    var wrong = check(List.of(run("．，：", "Times New Roman", "Times New Roman", 24)));
    assertTrue(wrong.stream().anyMatch(result -> "REF-FONT".equals(result.ruleId())
        && result.judgement() == Judgement.FAIL), wrong.toString());
  }

  @Test
  void treatsUnknownHalfWidthPunctuationFontAsUncertain() {
    var results = check(List.of(run("[1] 作者", "Times New Roman", "宋体", 24),
        run(".", null, "宋体", 24)));

    assertTrue(results.stream().anyMatch(result -> "REF-FONT".equals(result.ruleId())
        && result.judgement() == Judgement.REVIEW), results.toString());
  }

  @Test
  void mixedChineseAndHalfWidthPunctuationUsesTheirOwnFontProperties() {
    var results = check(List.of(run("[1] 作者，Title.", "Times New Roman", "宋体", 24)));

    assertTrue(results.stream().filter(result -> "REF-FONT".equals(result.ruleId()))
        .allMatch(result -> result.judgement() == Judgement.PASS), results.toString());
  }

  private static List<CheckResult> check(List<RunModel> runs) {
    var paragraph = new ParagraphModel(runs, null, null, null, null, 360, "auto",
        SOURCE, null, null, null);
    var reference = new ReferenceEntryModel(SOURCE, "1", "[1] 作者，题名[J].期刊。", "J", false);
    var document = new ParsedDocument("source", List.of(paragraph), List.of(), List.of(),
        List.of(), List.of(), List.of(reference));
    return new ReferenceFormatRuleChecker(new ParagraphFormatChecker())
        .check(document, RuleSet.schoolV1());
  }

  private static RunModel run(String text, String latinFont, String eastAsianFont, int size) {
    return new RunModel(text, latinFont, eastAsianFont, null, size, false, SOURCE);
  }

  private static RunModel run(String text, String asciiFont, String highAnsiFont,
      String eastAsianFont, int size) {
    return new RunModel(text, asciiFont, highAnsiFont, eastAsianFont, null,
        size, null, false, null, SOURCE);
  }
}
