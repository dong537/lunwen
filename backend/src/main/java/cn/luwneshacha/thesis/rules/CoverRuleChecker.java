package cn.luwneshacha.thesis.rules;

import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.RunModel;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Checks the cover region after the cover type heading has been identified. */
final class CoverRuleChecker implements RuleModule {
  private static final Pattern COVER_FIELD = Pattern.compile("(学生姓名|学\\s*号|院\\s*系|学\\s*院|专业年级|年级|指导教师)\\s*[:：]?");
  private static final Pattern COVER_TITLE_LABEL = Pattern.compile("^(?:论文)?题\\s*目\\s*[:：]?");
  private static final Pattern COVER_DATE = Pattern.compile("^(?:19|20)\\d{2}(?:年\\d{1,2}月(?:\\d{1,2}日)?|[-/.]\\d{1,2}(?:[-/.]\\d{1,2})?)$");
  private static final String COVER_TYPE_TEXT = "本科生毕业设计（论文）";
  private static final String SCHOOL_NAME_TEXT = "山东石油化工学院";

  private final ParagraphFormatChecker paragraphFormatChecker;

  CoverRuleChecker(ParagraphFormatChecker paragraphFormatChecker) {
    this.paragraphFormatChecker = paragraphFormatChecker;
  }

  @Override
  public List<CheckResult> check(ParsedDocument document, RuleSet rules) {
    var results = new ArrayList<CheckResult>();
    DocumentRegionClassifier regions = new DocumentRegionClassifier();
    boolean inCover = false;
    boolean titleChecked = false;
    boolean inTitleContinuation = false;
    String currentCoverFieldRule = null;
    String firstCoverDate = null;
    for (int paragraphIndex = 0; paragraphIndex < document.paragraphs().size(); paragraphIndex++) {
      ParagraphModel paragraph = document.paragraphs().get(paragraphIndex);
      String rawText = paragraphText(paragraph);
      String text = rawText.strip();
      int leadingWhitespace = rawText.length() - rawText.stripLeading().length();
      String compact = compactCoverText(text);
      boolean coverTypeHeading = COVER_TYPE_TEXT.equals(compact);
      if (regions.isInsideTable(paragraph, document.tables()) && !inCover) continue;
      if (!inCover && coverTypeHeading) {
        inCover = true;
        checkPreviousSchoolName(results, document, paragraphIndex, regions);
      }
      if (!inCover) continue;
      if (compact.equals("摘要") || compact.equalsIgnoreCase("ABSTRACT")) break;
      if (coverTypeHeading) {
        for (RunModel run : paragraph.runs()) {
          if (isFormattingOnlyRun(run)) continue;
          results.add(checkCoverFont(run, "方正小标宋简体", "COVER-01-FONT"));
          results.add(paragraphFormatChecker.checkExpectedSize(run, 72, "COVER-01-SIZE"));
        }
        results.add(checkCoverAlignment(paragraph, "COVER-01-ALIGNMENT"));
        continue;
      }
      Matcher field = COVER_FIELD.matcher(text);
      if (field.find() && field.start() == 0) {
        inTitleContinuation = false;
        currentCoverFieldRule = "COVER-03";
        int valueStart = leadingWhitespace + field.end();
        if (!text.substring(valueStart).isBlank()) checkCoverFieldRuns(results, paragraph, valueStart, currentCoverFieldRule);
        continue;
      }
      if (text.startsWith("指导老师")) {
        results.add(result("COVER-03-LABEL", "指导教师", "指导老师", "COVER_FIELD_LABEL_MISMATCH",
            Judgement.FAIL, paragraph.source()));
        continue;
      }
      if (COVER_DATE.matcher(compact).matches()) {
        currentCoverFieldRule = null;
        inTitleContinuation = false;
        checkCoverFieldRuns(results, paragraph, 0, "COVER-04");
        if (firstCoverDate == null) firstCoverDate = text;
        else if (!firstCoverDate.equals(text))
          results.add(result("COVER-04-CONSISTENCY", firstCoverDate, text,
              "COVER_DATE_MISMATCH", Judgement.FAIL, paragraph.source()));
        continue;
      }
      Matcher titleLabel = COVER_TITLE_LABEL.matcher(text);
      if (titleLabel.find()) {
        checkCoverTitleRuns(results, paragraph, leadingWhitespace + titleLabel.end());
        titleChecked = true;
        inTitleContinuation = true;
        currentCoverFieldRule = null;
        continue;
      }
      if (currentCoverFieldRule != null && !text.isBlank()) {
        checkCoverFieldRuns(results, paragraph, 0, currentCoverFieldRule);
        continue;
      }
      if (!titleChecked && !text.isBlank()) {
        checkCoverTitleRuns(results, paragraph, 0);
        titleChecked = true;
        inTitleContinuation = true;
        continue;
      }
      if (inTitleContinuation && !text.isBlank()) checkCoverTitleRuns(results, paragraph, 0);
    }
    return List.copyOf(results);
  }

  private void checkPreviousSchoolName(List<CheckResult> results, ParsedDocument document, int typeIndex,
      DocumentRegionClassifier regions) {
    for (int index = typeIndex - 1; index >= 0; index--) {
      ParagraphModel candidate = document.paragraphs().get(index);
      if (regions.isInsideTable(candidate, document.tables())) continue;
      String text = paragraphText(candidate).strip();
      if (isFormattingOnlyText(text)) continue;
      if (!SCHOOL_NAME_TEXT.equals(compactCoverText(text))) return;
      for (RunModel run : candidate.runs()) {
        if (isFormattingOnlyRun(run)) continue;
        results.add(checkCoverFont(run, "方正小标宋简体", "COVER-01-FONT"));
        results.add(paragraphFormatChecker.checkExpectedSize(run, 72, "COVER-01-SIZE"));
      }
      results.add(checkCoverAlignment(candidate, "COVER-01-ALIGNMENT"));
      return;
    }
  }

  private void checkCoverTitleRuns(List<CheckResult> results, ParagraphModel paragraph, int valueStart) {
    int offset = 0;
    for (RunModel run : paragraph.runs()) {
      int end = offset + run.text().length();
      offset = end;
      if (end <= valueStart || run.text().isBlank()) continue;
      results.add(checkCoverFont(run, "黑体", "COVER-02-FONT"));
      results.add(paragraphFormatChecker.checkExpectedSize(run, 36, "COVER-02-SIZE"));
    }
  }

  private void checkCoverFieldRuns(List<CheckResult> results, ParagraphModel paragraph, int valueStart, String ruleId) {
    int offset = 0;
    boolean found = false;
    int labelStart = -1;
    int labelEnd = -1;
    if (valueStart > 0) {
      String rawText = paragraphText(paragraph);
      int leadingWhitespace = rawText.length() - rawText.stripLeading().length();
      Matcher field = COVER_FIELD.matcher(rawText.strip());
      if (field.find() && field.start() == 0) {
        labelStart = leadingWhitespace + field.start(1);
        labelEnd = leadingWhitespace + field.end(1);
      }
    }
    for (RunModel run : paragraph.runs()) {
      int start = offset;
      int end = start + run.text().length();
      offset = end;
      boolean hasLabelText = labelStart >= 0 && start < labelEnd && end > labelStart
          && containsCoverFieldContent(run.text());
      boolean hasValueText = end > valueStart && !run.text().isBlank();
      if (!hasLabelText && !hasValueText) continue;
      found = true;
      results.add(checkCoverFont(run, "仿宋", ruleId + "-FONT"));
      results.add(paragraphFormatChecker.checkExpectedSize(run, 32, ruleId + "-SIZE"));
      if (run.bold() == null) results.add(review(ruleId + "-BOLD", "true", "BOLD_UNKNOWN", run.source()));
      else results.add(result(ruleId + "-BOLD", "true", String.valueOf(run.bold()), "",
          run.bold() ? Judgement.PASS : Judgement.FAIL, run.source()));
    }
    if (!found) results.add(review(ruleId + "-FONT", "仿宋", "COVER_FIELD_VALUE_UNKNOWN", paragraph.source()));
  }

  private static boolean containsCoverFieldContent(String text) {
    if (text == null || text.isEmpty()) return false;
    return text.codePoints().anyMatch(codePoint -> !(Character.isWhitespace(codePoint)
        || Character.isSpaceChar(codePoint) || codePoint == ':' || codePoint == '：'));
  }

  private CheckResult checkCoverFont(RunModel run, String expected, String ruleId) {
    // Cover rules name one font for the whole item and do not specify a
    // separate Latin font, so letters and digits follow that same font.
    return paragraphFormatChecker.checkFont(run, expected, expected, ruleId);
  }

  private CheckResult checkCoverAlignment(ParagraphModel paragraph, String ruleId) {
    if (paragraph.alignment() == null) return result(ruleId, "center", "unknown", "ALIGNMENT_UNKNOWN", Judgement.REVIEW, paragraph.source());
    return result(ruleId, "center", paragraph.alignment(), "",
        "center".equalsIgnoreCase(paragraph.alignment()) ? Judgement.PASS : Judgement.FAIL, paragraph.source());
  }

  private static String paragraphText(ParagraphModel paragraph) {
    return paragraph.runs().stream().map(RunModel::text).reduce("", String::concat);
  }

  private static String compactCoverText(String text) {
    if (text == null) return "";
    StringBuilder result = new StringBuilder();
    text.codePoints().forEach(codePoint -> {
      if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
          || codePoint == 0x200B || codePoint == 0x200C || codePoint == 0x200D
          || codePoint == 0x2060 || codePoint == 0xFEFF) return;
      result.appendCodePoint(codePoint == '(' ? '（' : codePoint == ')' ? '）' : codePoint);
    });
    return result.toString();
  }

  private static boolean isFormattingOnlyText(String text) {
    if (text == null || text.isEmpty()) return true;
    return text.codePoints().allMatch(codePoint -> Character.isWhitespace(codePoint)
        || Character.isSpaceChar(codePoint) || codePoint == 0x200B || codePoint == 0x200C
        || codePoint == 0x200D || codePoint == 0x2060 || codePoint == 0xFEFF);
  }

  private static boolean isFormattingOnlyRun(RunModel run) {
    return run == null || isFormattingOnlyText(run.text());
  }

  private CheckResult review(String ruleId, String expected, String reason, cn.luwneshacha.thesis.document.SourceAddress source) {
    return result(ruleId, expected, null, reason, Judgement.REVIEW, source);
  }

  private CheckResult result(String ruleId, String expected, String actual, String reason,
      Judgement judgement, cn.luwneshacha.thesis.document.SourceAddress source) {
    return new CheckResult(ruleId, Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        judgement, expected, actual, reason, source);
  }
}
