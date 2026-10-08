package cn.luwneshacha.thesis.rules;

import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.RunModel;
import cn.luwneshacha.thesis.document.SectionModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.regex.Pattern;

public final class RuleEngine {
  private static final Pattern REFERENCE_ENTRY = Pattern.compile("^\\[\\d+\\]");
  private static final Pattern TABLE_OR_FIGURE_CAPTION = Pattern.compile("^(表|图)\\s*[0-9一二三四五六七八九十]+", Pattern.CASE_INSENSITIVE);
  private static final Pattern EQUATION_NUMBER = Pattern.compile("^\\(?\\d+[\\-－]\\d+\\)?$");
  private static final Pattern TOC_ENTRY = Pattern.compile(".+(?:\\.{2,}|……+)\\s*\\d+$");
  public List<CheckResult> check(ParsedDocument document, RuleSet rules) {
    var results = new ArrayList<CheckResult>();
    for (SectionModel section : document.sections()) results.add(checkMargins(section, rules));
    for (ParagraphModel paragraph : document.paragraphs()) {
      int headingLevel = headingLevel(paragraph);
      if (headingLevel > 0 && !TOC_ENTRY.matcher(paragraphText(paragraph)).matches()) {
        for (RunModel run : paragraph.runs()) {
          results.add(checkHeadingFont(run, headingLevel));
          results.add(checkHeadingSize(run, headingLevel));
          results.add(checkHeadingBold(run));
        }
      }
      if (!isBodyParagraph(paragraph)) continue;
      results.add(checkIndent(paragraph, rules));
      for (RunModel run : paragraph.runs()) {
        results.add(checkFont(run, rules));
        results.add(checkSize(run, rules));
      }
    }
    checkAbstractLineSpacing(results, document);
    for (var reference : document.references()) {
      document.paragraphs().stream()
          .filter(paragraph -> paragraph.source().elementPath().equals(reference.source().elementPath()))
          .findFirst()
          .ifPresent(paragraph -> {
            for (RunModel run : paragraph.runs()) {
              results.add(checkFont(run, rules, "REF-FONT"));
              results.add(checkSize(run, rules, "REF-SIZE"));
            }
            results.add(checkReferenceLineSpacing(paragraph));
          });
    }
    for (var table : document.tables()) {
      if (table.captionText() == null) results.add(result("TAB-01", "表1-1", null, "TABLE_CAPTION_MISSING", Judgement.FAIL, table.source()));
      else if (!table.captionBefore()) results.add(result("TAB-02", "caption above table", null, "TABLE_CAPTION_BELOW", Judgement.FAIL, table.source()));
      else if (table.layoutNeedsReview()) results.add(review("TAB-02", "caption above table", "TABLE_LAYOUT_REVIEW", table.source()));
      if (!table.topBorder() || !table.middleBorder() || !table.bottomBorder())
        results.add(result("TAB-03", "three-line table", "border evidence", "TABLE_BORDER_MISMATCH", Judgement.FAIL, table.source()));
      else if (table.verticalBorders())
        results.add(review("TAB-03", "three-line table", "TABLE_AUXILIARY_BORDER_REVIEW", table.source()));
      captionParagraph(document, table.captionSource()).ifPresent(paragraph -> addCaptionResults(results, paragraph, rules));
    }
    for (var image : document.images()) {
      if (image.captionText() == null) {
        results.add(result("FIG-01", "图号和图题", null, "FIGURE_CAPTION_MISSING", Judgement.FAIL, image.source()));
      }
      captionParagraph(document, image.captionSource()).ifPresent(paragraph -> addCaptionResults(results, paragraph, rules));
      if (image.captionText() != null && image.placementNeedsReview())
        results.add(review("FIG-02", "caption below and centered", "FIGURE_LAYOUT_REVIEW", image.source()));
    }
    var equationNumbers = new HashSet<String>();
    for (var equation : document.equations()) {
      if (equation.standalone() && equation.number() == null) results.add(result("EQ-01", "(3-1)", null, "EQUATION_NUMBER_MISSING", Judgement.FAIL, equation.source()));
      if (equation.number() != null && !equationNumbers.add(equation.number())) results.add(result("EQ-03", "unique", equation.number(), "EQUATION_NUMBER_DUPLICATE", Judgement.FAIL, equation.source()));
      if (equation.needsReview()) results.add(review("EQ-04", equation.type(), "EQUATION_OBJECT_REVIEW", equation.source()));
    }
    var referenceNumbers = new HashSet<String>();
    for (var reference : document.references()) {
      if (!referenceNumbers.add(reference.number())) results.add(result("REF-04", "unique", reference.number(), "REFERENCE_NUMBER_DUPLICATE", Judgement.FAIL, reference.source()));
      if (reference.category() == null) results.add(result("REF-02", "M/J/D/C/N/R/S/P/Z/EB/OL", null, "REFERENCE_CATEGORY_MISSING", Judgement.FAIL, reference.source()));
    }
    return List.copyOf(results);
  }

  private String paragraphText(ParagraphModel paragraph) {
    return paragraph.runs().stream().map(RunModel::text).reduce("", String::concat).trim();
  }

  private int headingLevel(ParagraphModel paragraph) {
    String text = paragraphText(paragraph);
    if (text.matches("^第[0-9一二三四五六七八九十]+章\\s+.*")) return 1;
    if (text.matches("^\\d+\\.\\d+\\.\\d+\\s+.*")) return 3;
    if (text.matches("^\\d+\\.\\d+\\s+.*")) return 2;
    return 0;
  }

  private CheckResult checkHeadingFont(RunModel run, int level) {
    boolean hasHan = run.text().codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN);
    boolean hasLatin = run.text().codePoints().anyMatch(c -> Character.isLetterOrDigit(c) && Character.UnicodeScript.of(c) == Character.UnicodeScript.LATIN);
    if ((hasHan && run.eastAsiaFont() == null) || (hasLatin && run.asciiFont() == null))
      return review("HEADING-FONT", "黑体/Times New Roman", "FONT_UNKNOWN", run.source());
    boolean matches = (!hasHan || "黑体".equals(run.eastAsiaFont())) && (!hasLatin || "Times New Roman".equals(run.asciiFont()));
    return result("HEADING-FONT", "黑体/Times New Roman", run.eastAsiaFont() + "/" + run.asciiFont(), "",
        matches ? Judgement.PASS : Judgement.FAIL, run.source());
  }

  private CheckResult checkHeadingSize(RunModel run, int level) {
    int expected = level == 1 ? 32 : level == 2 ? 28 : 24;
    if (run.fontSizeHalfPoints() == null) return review("HEADING-SIZE", String.valueOf(expected), "SIZE_UNKNOWN", run.source());
    return result("HEADING-SIZE", String.valueOf(expected), String.valueOf(run.fontSizeHalfPoints()), "",
        run.fontSizeHalfPoints() == expected ? Judgement.PASS : Judgement.FAIL, run.source());
  }

  private CheckResult checkHeadingBold(RunModel run) {
    return result("HEADING-BOLD", "true", String.valueOf(run.bold()), "",
        run.bold() ? Judgement.PASS : Judgement.FAIL, run.source());
  }

  private boolean isBodyParagraph(ParagraphModel paragraph) {
    String text = paragraph.runs().stream().map(RunModel::text).reduce("", String::concat).trim();
    if (text.isBlank()) return false;
    if (REFERENCE_ENTRY.matcher(text).find()) return false;
    if (TABLE_OR_FIGURE_CAPTION.matcher(text).find()) return false;
    if (EQUATION_NUMBER.matcher(text).matches()) return false;
    if (TOC_ENTRY.matcher(text).matches()) return false;
    return !isSectionHeading(text);
  }

  private boolean isSectionHeading(String text) {
    return text.matches("^(摘要|Abstract|关键词|Keywords|目录|Contents|参考文献|References|致谢|Acknowledg(?:e)?ments)$")
        || text.matches("^第[一二三四五六七八九十百零\\d]+章.*$")
        || text.matches("^\\d+(?:\\.\\d+){0,3}\\s+\\S+.*$");
  }

  private CheckResult checkMargins(SectionModel section, RuleSet rules) {
    Integer[] values = {section.topMarginTwips(), section.rightMarginTwips(), section.bottomMarginTwips(), section.leftMarginTwips()};
    for (Integer value : values) {
      if (value == null) return review("PAGE-01", "margin", "MARGIN_UNKNOWN", section.source());
      if (value != rules.expectedMarginTwips()) return result("PAGE-01", String.valueOf(rules.expectedMarginTwips()), String.valueOf(value), "MARGIN_MISMATCH", Judgement.FAIL, section.source());
    }
    return result("PAGE-01", String.valueOf(rules.expectedMarginTwips()), "all sides", "", Judgement.PASS, section.source());
  }

  private CheckResult checkIndent(ParagraphModel paragraph, RuleSet rules) {
    if (paragraph.firstLineChars() == null) {
      if (paragraph.firstLineIndentTwips() == null) return review("BODY-INDENT", "2 chars", "INDENT_UNKNOWN", paragraph.source());
      return result("BODY-INDENT", String.valueOf(rules.expectedFirstLineIndentTwips()),
          String.valueOf(paragraph.firstLineIndentTwips()), "",
          paragraph.firstLineIndentTwips() == rules.expectedFirstLineIndentTwips() ? Judgement.PASS : Judgement.FAIL,
          paragraph.source());
    }
    return result("BODY-INDENT", String.valueOf(rules.expectedFirstLineChars()), String.valueOf(paragraph.firstLineChars()), "", paragraph.firstLineChars() == rules.expectedFirstLineChars() ? Judgement.PASS : Judgement.FAIL, paragraph.source());
  }

  private CheckResult checkFont(RunModel run, RuleSet rules) {
    return checkFont(run, rules, "BODY-FONT");
  }

  private CheckResult checkFont(RunModel run, RuleSet rules, String ruleId) {
    boolean hasHan = run.text().codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN);
    boolean hasLatin = run.text().codePoints().anyMatch(c -> Character.isLetterOrDigit(c) && Character.UnicodeScript.of(c) == Character.UnicodeScript.LATIN);
    if ((hasHan && run.eastAsiaFont() == null) || (hasLatin && run.asciiFont() == null)) return review(ruleId, "font slots", "FONT_UNKNOWN", run.source());
    if ((hasHan && !rules.expectedEastAsiaFont().equals(run.eastAsiaFont())) || (hasLatin && !rules.expectedLatinFont().equals(run.asciiFont()))) {
      return result(ruleId, rules.expectedEastAsiaFont() + "/" + rules.expectedLatinFont(), run.eastAsiaFont() + "/" + run.asciiFont(), "FONT_MISMATCH", Judgement.FAIL, run.source());
    }
    return result(ruleId, rules.expectedEastAsiaFont() + "/" + rules.expectedLatinFont(), run.eastAsiaFont() + "/" + run.asciiFont(), "", Judgement.PASS, run.source());
  }

  private CheckResult checkSize(RunModel run, RuleSet rules) {
    return checkSize(run, rules, "BODY-SIZE");
  }

  private CheckResult checkSize(RunModel run, RuleSet rules, String ruleId) {
    if (run.fontSizeHalfPoints() == null) return review(ruleId, String.valueOf(rules.expectedSizeHalfPoints()), "SIZE_UNKNOWN", run.source());
    return result(ruleId, String.valueOf(rules.expectedSizeHalfPoints()), String.valueOf(run.fontSizeHalfPoints()), "", run.fontSizeHalfPoints() == rules.expectedSizeHalfPoints() ? Judgement.PASS : Judgement.FAIL, run.source());
  }

  private void checkAbstractLineSpacing(List<CheckResult> results, ParsedDocument document) {
    boolean inAbstract = false;
    for (ParagraphModel paragraph : document.paragraphs()) {
      String text = paragraph.runs().stream().map(RunModel::text).reduce("", String::concat).trim();
      if (text.matches("^(摘要|Abstract)$")) { inAbstract = true; continue; }
      if (inAbstract && text.matches("^(关键词|Keywords)[:：]?.*")) {
        results.add(checkLineSpacing("ABSTRACT-LINE", paragraph));
        inAbstract = false;
        continue;
      }
      if (inAbstract && !text.isBlank()) results.add(checkLineSpacing("ABSTRACT-LINE", paragraph));
    }
  }

  private CheckResult checkReferenceLineSpacing(ParagraphModel paragraph) {
    return checkLineSpacing("REF-LINE", paragraph);
  }

  private CheckResult checkLineSpacing(String ruleId, ParagraphModel paragraph) {
    if (paragraph.lineTwips() == null) return review(ruleId, "360", "LINE_SPACING_UNKNOWN", paragraph.source());
    return result(ruleId, "360", String.valueOf(paragraph.lineTwips()), "",
        paragraph.lineTwips() == 360 ? Judgement.PASS : Judgement.FAIL, paragraph.source());
  }

  private void addCaptionResults(List<CheckResult> results, ParagraphModel paragraph, RuleSet rules) {
    for (RunModel run : paragraph.runs()) {
      results.add(checkFont(run, rules, "CAPTION-FONT"));
      results.add(checkCaptionSize(run));
    }
  }

  private CheckResult checkCaptionSize(RunModel run) {
    if (run.fontSizeHalfPoints() == null) return review("CAPTION-SIZE", "21", "SIZE_UNKNOWN", run.source());
    return result("CAPTION-SIZE", "21", String.valueOf(run.fontSizeHalfPoints()), "",
        run.fontSizeHalfPoints() == 21 ? Judgement.PASS : Judgement.FAIL, run.source());
  }

  private java.util.Optional<ParagraphModel> captionParagraph(ParsedDocument document, SourceAddress source) {
    if (source == null) return java.util.Optional.empty();
    return document.paragraphs().stream()
        .filter(paragraph -> paragraph.source().elementPath().equals(source.elementPath()))
        .findFirst();
  }

  private CheckResult review(String ruleId, String expected, String reason, SourceAddress source) {
    return result(ruleId, expected, null, reason, Judgement.REVIEW, source);
  }

  private CheckResult result(String ruleId, String expected, String actual, String reason, Judgement judgement, SourceAddress source) {
    return new CheckResult(ruleId, Applicability.APPLICABLE, ExecutionStatus.EXECUTED, judgement, expected, actual, reason, source);
  }
}
