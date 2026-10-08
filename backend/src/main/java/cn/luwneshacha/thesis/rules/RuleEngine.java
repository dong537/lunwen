package cn.luwneshacha.thesis.rules;

import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.RunModel;
import cn.luwneshacha.thesis.document.SectionModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;

public final class RuleEngine {
  public List<CheckResult> check(ParsedDocument document, RuleSet rules) {
    var results = new ArrayList<CheckResult>();
    for (SectionModel section : document.sections()) results.add(checkMargins(section, rules));
    for (ParagraphModel paragraph : document.paragraphs()) {
      results.add(checkIndent(paragraph, rules));
      for (RunModel run : paragraph.runs()) {
        results.add(checkFont(run, rules));
        results.add(checkSize(run, rules));
      }
    }
    for (var table : document.tables()) {
      if (table.captionText() == null) results.add(result("TAB-01", "表1-1", null, "TABLE_CAPTION_MISSING", Judgement.FAIL, table.source()));
      if (table.layoutNeedsReview()) results.add(review("TAB-02", "caption above table", "TABLE_LAYOUT_REVIEW", table.source()));
      if (!table.topBorder() || !table.middleBorder() || !table.bottomBorder() || table.verticalBorders())
        results.add(result("TAB-03", "three-line table", "border evidence", "TABLE_BORDER_MISMATCH", Judgement.FAIL, table.source()));
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
      if (reference.category() == null) results.add(result("REF-02", "M/J/D/C/N/EB/OL", null, "REFERENCE_CATEGORY_MISSING", Judgement.FAIL, reference.source()));
    }
    return List.copyOf(results);
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
    boolean hasHan = run.text().codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN);
    boolean hasLatin = run.text().codePoints().anyMatch(c -> Character.isLetterOrDigit(c) && Character.UnicodeScript.of(c) == Character.UnicodeScript.LATIN);
    if ((hasHan && run.eastAsiaFont() == null) || (hasLatin && run.asciiFont() == null)) return review("BODY-FONT", "font slots", "FONT_UNKNOWN", run.source());
    if ((hasHan && !rules.expectedEastAsiaFont().equals(run.eastAsiaFont())) || (hasLatin && !rules.expectedLatinFont().equals(run.asciiFont()))) {
      return result("BODY-FONT", rules.expectedEastAsiaFont() + "/" + rules.expectedLatinFont(), run.eastAsiaFont() + "/" + run.asciiFont(), "FONT_MISMATCH", Judgement.FAIL, run.source());
    }
    return result("BODY-FONT", rules.expectedEastAsiaFont() + "/" + rules.expectedLatinFont(), run.eastAsiaFont() + "/" + run.asciiFont(), "", Judgement.PASS, run.source());
  }

  private CheckResult checkSize(RunModel run, RuleSet rules) {
    if (run.fontSizeHalfPoints() == null) return review("BODY-SIZE", String.valueOf(rules.expectedSizeHalfPoints()), "SIZE_UNKNOWN", run.source());
    return result("BODY-SIZE", String.valueOf(rules.expectedSizeHalfPoints()), String.valueOf(run.fontSizeHalfPoints()), "", run.fontSizeHalfPoints() == rules.expectedSizeHalfPoints() ? Judgement.PASS : Judgement.FAIL, run.source());
  }

  private CheckResult review(String ruleId, String expected, String reason, SourceAddress source) {
    return result(ruleId, expected, null, reason, Judgement.REVIEW, source);
  }

  private CheckResult result(String ruleId, String expected, String actual, String reason, Judgement judgement, SourceAddress source) {
    return new CheckResult(ruleId, Applicability.APPLICABLE, ExecutionStatus.EXECUTED, judgement, expected, actual, reason, source);
  }
}
