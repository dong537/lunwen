package cn.luwneshacha.thesis.rules;

import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.RunModel;
import cn.luwneshacha.thesis.document.SectionModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import cn.luwneshacha.thesis.document.TableModel;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RuleEngine {
  private static final Pattern REFERENCE_ENTRY = Pattern.compile("^\\[\\d+\\]");
  private static final Pattern TABLE_OR_FIGURE_CAPTION = Pattern.compile("^(表|图)\\s*[0-9一二三四五六七八九十]+", Pattern.CASE_INSENSITIVE);
  private static final Pattern EQUATION_NUMBER = Pattern.compile("^\\(?\\d+[\\-－]\\d+\\)?$");
  private static final Pattern TOC_ENTRY = Pattern.compile(".+(?:\\.{2,}|……+)\\s*\\d+$");
  private static final Pattern CHAPTER_NUMBER = Pattern.compile("^第([0-9]+|[一二三四五六七八九十百千零〇]+)章");
  public List<CheckResult> check(ParsedDocument document, RuleSet rules) {
    var results = new ArrayList<CheckResult>();
    for (SectionModel section : document.sections()) results.add(checkMargins(section, rules));
    for (SectionModel section : document.sections()) {
      if (section.pageNumberFormat() != null && !"decimal".equalsIgnoreCase(section.pageNumberFormat()))
        results.add(review("PAGE-04", "decimal in main text", "PAGE_NUMBER_FORMAT_REVIEW", section.source()));
    }
    for (var chapter : document.chapterBreaks()) {
      if (chapterNumber(chapter.text()) > 1 && !chapter.explicitBreak())
        results.add(review("PAGE-03", "chapter starts on new page", "CHAPTER_PAGE_BREAK_REVIEW", chapter.source()));
    }
    for (var pageNumber : document.pageNumbers()) {
      if (!pageNumber.centered()) results.add(result("PAGE-05", "center", "not centered", "PAGE_NUMBER_NOT_CENTERED", Judgement.FAIL, pageNumber.source()));
      if (!"Times New Roman".equals(pageNumber.asciiFont())) results.add(result("PAGE-05", "Times New Roman", pageNumber.asciiFont(), "PAGE_NUMBER_FONT_MISMATCH", Judgement.FAIL, pageNumber.source()));
      if (pageNumber.sizeHalfPoints() == null) results.add(review("PAGE-05", "21", "PAGE_NUMBER_SIZE_UNKNOWN", pageNumber.source()));
      else if (pageNumber.sizeHalfPoints() != 21) results.add(result("PAGE-05", "21", String.valueOf(pageNumber.sizeHalfPoints()), "PAGE_NUMBER_SIZE_MISMATCH", Judgement.FAIL, pageNumber.source()));
    }
    boolean inMainBody = false;
    for (ParagraphModel paragraph : document.paragraphs()) {
      String text = paragraphText(paragraph);
      if (isChapterHeading(text)) inMainBody = true;
      if (inMainBody && isEndMatterHeading(text)) inMainBody = false;
      int headingLevel = headingLevel(paragraph);
      if (headingLevel > 0 && !TOC_ENTRY.matcher(text).matches()) {
        for (RunModel run : paragraph.runs()) {
          results.add(checkHeadingFont(run, headingLevel));
          results.add(checkHeadingSize(run, headingLevel));
          results.add(checkHeadingBold(run));
        }
      }
      if (!inMainBody || !isBodyParagraph(paragraph) || isInsideTable(paragraph, document.tables())) continue;
      results.add(checkIndent(paragraph, rules));
      for (RunModel run : paragraph.runs()) {
        results.add(checkFont(run, rules));
        results.add(checkSize(run, rules));
      }
    }
    checkAbstractFormatting(results, document, rules);
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
    if (!document.references().isEmpty() && document.references().size() < 10) {
      results.add(result("REF-05", "10", String.valueOf(document.references().size()), "REFERENCE_COUNT_TOO_LOW", Judgement.FAIL,
          document.references().get(0).source()));
    }
    for (var reference : document.references()) {
      if (!referenceNumbers.add(reference.number())) results.add(result("REF-03", "unique", reference.number(), "REFERENCE_NUMBER_DUPLICATE", Judgement.FAIL, reference.source()));
      if (reference.category() == null) results.add(result("REF-02", "M/J/D/C/N/R/S/P/Z/EB/OL", null, "REFERENCE_CATEGORY_MISSING", Judgement.FAIL, reference.source()));
    }
    return List.copyOf(results);
  }

  private static int chapterNumber(String text) {
    Matcher matcher = CHAPTER_NUMBER.matcher(text);
    if (!matcher.find()) return 0;
    String number = matcher.group(1);
    try { return Integer.parseInt(number); }
    catch (NumberFormatException ignored) { }
    int total = 0;
    int section = 0;
    int digit = 0;
    for (int i = 0; i < number.length(); i++) {
      char value = number.charAt(i);
      int numeric = value == '〇' ? 0 : "零一二三四五六七八九".indexOf(value);
      if (numeric >= 0) { digit = numeric; continue; }
      int unit = value == '十' ? 10 : value == '百' ? 100 : value == '千' ? 1000 : 0;
      if (unit == 0) return 0;
      section += (digit == 0 ? 1 : digit) * unit;
      digit = 0;
      if (unit >= 100) { total += section; section = 0; }
    }
    return total + section + digit;
  }

  private String paragraphText(ParagraphModel paragraph) {
    return paragraph.runs().stream().map(RunModel::text).reduce("", String::concat).trim();
  }

  private int headingLevel(ParagraphModel paragraph) {
    String text = paragraphText(paragraph);
    if (isChapterHeading(text)) return 1;
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

  private boolean isChapterHeading(String text) {
    return text.matches("^第[0-9一二三四五六七八九十百千零〇]+章\\s+.*")
        && !TOC_ENTRY.matcher(text).matches();
  }

  private boolean isEndMatterHeading(String text) {
    String normalized = text.replaceAll("\\s+", "");
    if (normalized.equals("参考文献") || normalized.equalsIgnoreCase("References") || normalized.equals("致谢")
        || normalized.equalsIgnoreCase("Acknowledgments") || normalized.equalsIgnoreCase("Acknowledgements")) return true;
    return normalized.matches("^附录(?:[A-Za-z0-9一二三四五六七八九十百千零〇].*)?$");
  }

  private boolean isInsideTable(ParagraphModel paragraph, List<TableModel> tables) {
    List<Integer> paragraphPath = paragraph.source().elementPath();
    return tables.stream().anyMatch(table -> {
      SourceAddress tableSource = table.source();
      List<Integer> tablePath = tableSource.elementPath();
      return tableSource.partName().equals(paragraph.source().partName())
          && paragraphPath.size() > tablePath.size()
          && paragraphPath.subList(0, tablePath.size()).equals(tablePath);
    });
  }

  private boolean isSectionHeading(String text) {
    return text.matches("^(摘要|Abstract|关键词|Keywords|目录|Contents|参考文献|References|致谢|Acknowledg(?:e)?ments)$")
        || text.matches("^第[一二三四五六七八九十百千零〇\\d]+章.*$")
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
    return checkIndent(paragraph, rules, "BODY-INDENT");
  }

  private CheckResult checkIndent(ParagraphModel paragraph, RuleSet rules, String ruleId) {
    if (paragraph.firstLineChars() == null) {
      if (paragraph.firstLineIndentTwips() == null) return review(ruleId, "2 chars", "INDENT_UNKNOWN", paragraph.source());
      return result(ruleId, String.valueOf(rules.expectedFirstLineIndentTwips()),
          String.valueOf(paragraph.firstLineIndentTwips()), "",
          paragraph.firstLineIndentTwips() == rules.expectedFirstLineIndentTwips() ? Judgement.PASS : Judgement.FAIL,
          paragraph.source());
    }
    return result(ruleId, String.valueOf(rules.expectedFirstLineChars()), String.valueOf(paragraph.firstLineChars()), "", paragraph.firstLineChars() == rules.expectedFirstLineChars() ? Judgement.PASS : Judgement.FAIL, paragraph.source());
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

  private void checkAbstractFormatting(List<CheckResult> results, ParsedDocument document, RuleSet rules) {
    String language = null;
    for (ParagraphModel paragraph : document.paragraphs()) {
      String text = paragraphText(paragraph);
      String normalized = text.replaceAll("\\s+", "");
      if (normalized.equals("摘要")) { language = "zh"; continue; }
      if (normalized.equalsIgnoreCase("ABSTRACT")) { language = "en"; continue; }
      if (language == null) continue;
      if (isEndMatterHeading(text) || isChapterHeading(text) || normalized.equals("目录") || normalized.equalsIgnoreCase("CONTENTS")) {
        language = null;
        continue;
      }
      if (text.isBlank()) continue;
      boolean keywords = normalized.matches("^(关键词|keywords)[:：]?.*");
      for (RunModel run : paragraph.runs()) {
        results.add(checkFont(run, rules, "ABSTRACT-FONT"));
        results.add(checkSize(run, rules, "ABSTRACT-SIZE"));
      }
      results.add(checkIndent(paragraph, rules, "ABSTRACT-INDENT"));
      results.add(checkLineSpacing("ABSTRACT-LINE", paragraph));
      if (keywords) {
        if ("zh".equals(language)) {
          results.add(checkChineseKeywordLabelBold(paragraph));
          results.add(checkChineseKeywordFormat(paragraph, text));
        }
        language = null;
      }
    }
  }

  private CheckResult checkChineseKeywordLabelBold(ParagraphModel paragraph) {
    int labelLength = "关键词".length();
    int offset = 0;
    for (RunModel run : paragraph.runs()) {
      int end = offset + run.text().length();
      if (offset < labelLength && end > offset && !run.bold())
        return result("ABSTRACT-KEYWORD-BOLD", "true", "false", "KEYWORD_LABEL_NOT_BOLD", Judgement.FAIL, run.source());
      offset = end;
    }
    if (offset < labelLength) return review("ABSTRACT-KEYWORD-BOLD", "true", "KEYWORD_LABEL_UNREADABLE", paragraph.source());
    return result("ABSTRACT-KEYWORD-BOLD", "true", "true", "", Judgement.PASS, paragraph.source());
  }

  private CheckResult checkChineseKeywordFormat(ParagraphModel paragraph, String text) {
    String normalized = text.replaceAll("\\s+", "");
    if (!normalized.startsWith("关键词："))
      return result("ABSTRACT-KEYWORD-FORMAT", "关键词：词1；词2；词3", text,
          "KEYWORD_SEPARATOR_MISMATCH", Judgement.FAIL, paragraph.source());
    String list = normalized.substring("关键词：".length());
    String[] words = list.split("；", -1);
    boolean trailingPunctuation = list.matches(".*[。！？；，,.!?]$");
    boolean valid = words.length >= 3 && words.length <= 5
        && java.util.Arrays.stream(words).allMatch(word -> !word.isBlank())
        && !trailingPunctuation;
    return result("ABSTRACT-KEYWORD-FORMAT", "3-5 个关键词，用中文分号分隔且末尾无标点", text,
        valid ? "" : "KEYWORD_LIST_MISMATCH", valid ? Judgement.PASS : Judgement.FAIL, paragraph.source());
  }

  private CheckResult checkReferenceLineSpacing(ParagraphModel paragraph) {
    return checkLineSpacing("REF-LINE", paragraph);
  }

  private CheckResult checkLineSpacing(String ruleId, ParagraphModel paragraph) {
    if (paragraph.lineTwips() == null) return review(ruleId, "360", "LINE_SPACING_UNKNOWN", paragraph.source());
    String lineRule = paragraph.lineRule() == null ? "auto" : paragraph.lineRule();
    boolean matches = paragraph.lineTwips() == 360 && "auto".equalsIgnoreCase(lineRule);
    return result(ruleId, "360 / auto", paragraph.lineTwips() + " / " + lineRule, "",
        matches ? Judgement.PASS : Judgement.FAIL, paragraph.source());
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
