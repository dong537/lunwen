package cn.luwneshacha.thesis.rules;

import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.RunModel;
import java.util.ArrayList;
import java.util.List;

/** Applies the shared font, size, and line-spacing contract to references. */
final class ReferenceFormatRuleChecker implements RuleModule {
  private final ParagraphFormatChecker formatChecker;

  ReferenceFormatRuleChecker(ParagraphFormatChecker formatChecker) {
    this.formatChecker = formatChecker;
  }

  @Override
  public List<CheckResult> check(ParsedDocument document, RuleSet rules) {
    return evaluate(document, rules).results();
  }

  @Override
  public Evaluation evaluate(ParsedDocument document, RuleSet rules) {
    var results = new ArrayList<CheckResult>();
    int fontCandidates = 0;
    int fontUnknown = 0;
    int sizeCandidates = 0;
    int sizeUnknown = 0;
    int lineCandidates = 0;
    int lineUnknown = 0;
    for (var reference : document.references()) {
      var paragraph = document.paragraphs().stream()
          .filter(candidate -> sameSource(candidate, reference.source()))
          .findFirst().orElse(null);
      if (paragraph == null) {
        fontCandidates++;
        fontUnknown++;
        sizeCandidates++;
        sizeUnknown++;
        lineCandidates++;
        lineUnknown++;
        continue;
      }
      if (paragraph.runs().isEmpty()) {
        fontCandidates++;
        fontUnknown++;
        sizeCandidates++;
        sizeUnknown++;
      }
      for (RunModel run : paragraph.runs()) {
        fontCandidates++;
        sizeCandidates++;
        CheckResult font = checkReferenceFont(run, rules);
        CheckResult size = formatChecker.checkSize(run, rules, "REF-SIZE");
        results.add(font);
        results.add(size);
        if (isUnknown(font)) fontUnknown++;
        if (isUnknown(size)) sizeUnknown++;
      }
      lineCandidates++;
      CheckResult line = formatChecker.checkLineSpacing("REF-LINE", paragraph);
      results.add(line);
      if (isUnknown(line)) lineUnknown++;
    }
    var runs = new ArrayList<RuleRun>();
    String moduleId = getClass().getSimpleName();
    if (fontCandidates > 0) runs.add(new RuleRun("REF-FONT", moduleId, fontCandidates,
        fontCandidates - fontUnknown, fontUnknown, null));
    if (sizeCandidates > 0) runs.add(new RuleRun("REF-SIZE", moduleId, sizeCandidates,
        sizeCandidates - sizeUnknown, sizeUnknown, null));
    if (lineCandidates > 0) runs.add(new RuleRun("REF-LINE", moduleId, lineCandidates,
        lineCandidates - lineUnknown, lineUnknown, null));
    return new Evaluation(List.copyOf(results), List.copyOf(runs));
  }

  private static boolean isUnknown(CheckResult result) {
    return result.judgement() == Judgement.REVIEW
        || result.applicability() == Applicability.UNKNOWN
        || result.executionStatus() != ExecutionStatus.EXECUTED;
  }

  private CheckResult checkReferenceFont(RunModel run, RuleSet rules) {
    String effectiveEastAsiaFont = effectiveEastAsiaFont(run);
    RunModel effectiveRun = new RunModel(run.text(), run.asciiFont(), run.highAnsiFont(),
        effectiveEastAsiaFont, run.complexFont(), run.fontSizeHalfPoints(),
        run.complexFontSizeHalfPoints(), run.bold(), run.verticalAlignment(), run.source());
    CheckResult basic = formatChecker.checkFont(effectiveRun, rules, "REF-FONT");
    String text = run.text() == null ? "" : run.text();
    boolean eastAsianPunctuation = text.codePoints()
        .anyMatch(ReferenceFormatRuleChecker::isEastAsianPunctuation);
    boolean asciiPunctuation = text.codePoints()
        .anyMatch(ReferenceFormatRuleChecker::isAsciiPunctuation);
    boolean unicodeHalfWidthPunctuation = text.codePoints()
        .anyMatch(ReferenceFormatRuleChecker::isUnicodeHalfWidthPunctuation);
    if (!eastAsianPunctuation && !asciiPunctuation && !unicodeHalfWidthPunctuation) return basic;
    var actualFonts = new ArrayList<String>();
    if (eastAsianPunctuation) actualFonts.add("中文及全角标点字体=" + readableFont(effectiveEastAsiaFont));
    if (asciiPunctuation) actualFonts.add("普通半角标点字体=" + run.asciiFont());
    if (unicodeHalfWidthPunctuation) actualFonts.add("半角标点字体=" + readableFont(effectiveEastAsiaFont));
    String actual = String.join("，", actualFonts);
    boolean eastAsianMismatch = eastAsianPunctuation && effectiveEastAsiaFont != null
        && !ParagraphFormatChecker.matchesChineseFont(
            rules.expectedEastAsiaFont(), effectiveEastAsiaFont);
    boolean halfWidthMismatch = asciiPunctuation && run.asciiFont() != null
        && !rules.expectedLatinFont().equals(run.asciiFont());
    // U+FF61..U+FF65 normally select eastAsia, subject to Word's ascii override.
    boolean unicodeHalfWidthMismatch = unicodeHalfWidthPunctuation
        && effectiveEastAsiaFont != null
        && !rules.expectedLatinFont().equals(effectiveEastAsiaFont);
    if (eastAsianMismatch || halfWidthMismatch || unicodeHalfWidthMismatch) {
      return new CheckResult("REF-FONT", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
          Judgement.FAIL, rules.expectedEastAsiaFont() + "/" + rules.expectedLatinFont(),
          actual, "FONT_MISMATCH", run.source());
    }
    if (basic.judgement() == Judgement.FAIL) return basic;
    if ((eastAsianPunctuation && effectiveEastAsiaFont == null)
        || (asciiPunctuation && run.asciiFont() == null)
        || (unicodeHalfWidthPunctuation && effectiveEastAsiaFont == null)) {
      return new CheckResult("REF-FONT", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
          Judgement.REVIEW, rules.expectedEastAsiaFont() + "/" + rules.expectedLatinFont(),
          actual, "FONT_UNKNOWN", run.source());
    }
    return basic;
  }

  private static String readableFont(String font) {
    return font == null ? "无法确认" : font;
  }

  private static String effectiveEastAsiaFont(RunModel run) {
    if (!"Times New Roman".equals(run.eastAsiaFont())) return run.eastAsiaFont();
    // MS-OI29500 §2.1.87: when eastAsia is Times New Roman and ascii=hAnsi,
    // Word uses ascii before considering the Unicode-range eastAsia selection.
    if (run.asciiFont() == null || run.highAnsiFont() == null) return null;
    return run.asciiFont().equals(run.highAnsiFont())
        ? run.asciiFont() : run.eastAsiaFont();
  }

  private static boolean isAsciiPunctuation(int codePoint) {
    return codePoint >= 0x21 && codePoint <= 0x7E && isPunctuation(codePoint);
  }

  private static boolean isUnicodeHalfWidthPunctuation(int codePoint) {
    return codePoint >= 0xFF61 && codePoint <= 0xFF65 && isPunctuation(codePoint);
  }

  private static boolean isEastAsianPunctuation(int codePoint) {
    if (!isPunctuation(codePoint) || isUnicodeHalfWidthPunctuation(codePoint)) return false;
    Character.UnicodeBlock block = Character.UnicodeBlock.of(codePoint);
    return block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
        || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
        || codePoint == 0x00B7 || (codePoint >= 0x2014 && codePoint <= 0x2026);
  }

  private static boolean isPunctuation(int codePoint) {
    int type = Character.getType(codePoint);
    return type == Character.CONNECTOR_PUNCTUATION
        || type == Character.DASH_PUNCTUATION || type == Character.START_PUNCTUATION
        || type == Character.END_PUNCTUATION || type == Character.INITIAL_QUOTE_PUNCTUATION
        || type == Character.FINAL_QUOTE_PUNCTUATION || type == Character.OTHER_PUNCTUATION;
  }

  private static boolean sameSource(ParagraphModel paragraph,
      cn.luwneshacha.thesis.document.SourceAddress source) {
    return paragraph.source().partName().equals(source.partName())
        && paragraph.source().elementPath().equals(source.elementPath());
  }
}
