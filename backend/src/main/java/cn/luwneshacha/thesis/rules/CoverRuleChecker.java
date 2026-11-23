package cn.luwneshacha.thesis.rules;

import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.RunModel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    return evaluate(document, rules).results();
  }

  @Override
  public Evaluation evaluate(ParsedDocument document, RuleSet rules) {
    var results = new ArrayList<CheckResult>();
    var evidence = new Evidence();
    DocumentRegionClassifier regions = new DocumentRegionClassifier();
    boolean inCover = false;
    boolean titleChecked = false;
    boolean inTitleContinuation = false;
    String currentCoverFieldRule = null;
    String firstCoverDate = null;
    int coverDateCount = 0;
    boolean coverBoundarySeen = false;
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
        checkPreviousSchoolName(results, evidence, document, paragraphIndex, regions);
      }
      if (!inCover) continue;
      if (compact.equals("摘要") || compact.equalsIgnoreCase("ABSTRACT")) {
        coverBoundarySeen = true;
        break;
      }
      if (coverTypeHeading) {
        for (RunModel run : paragraph.runs()) {
          if (isFormattingOnlyRun(run)) continue;
          checked(results, evidence, checkCoverFont(run, "方正小标宋简体", "COVER-01-FONT"));
          checked(results, evidence, paragraphFormatChecker.checkExpectedSize(run, 72, "COVER-01-SIZE"));
        }
        checked(results, evidence, checkCoverAlignment(paragraph, "COVER-01-ALIGNMENT"));
        continue;
      }
      Matcher field = COVER_FIELD.matcher(text);
      if (field.find() && field.start() == 0) {
        inTitleContinuation = false;
        currentCoverFieldRule = "COVER-03";
        if (compactCoverText(field.group(1)).equals("指导教师"))
          evidence.record("COVER-03-LABEL", false);
        int valueStart = leadingWhitespace + field.end();
        if (!text.substring(valueStart).isBlank())
          checkCoverFieldRuns(results, evidence, paragraph, valueStart, currentCoverFieldRule);
        continue;
      }
      if (text.startsWith("指导老师")) {
        checked(results, evidence, result("COVER-03-LABEL", "指导教师", "指导老师",
            "COVER_FIELD_LABEL_MISMATCH", Judgement.FAIL, paragraph.source()));
        continue;
      }
      if (COVER_DATE.matcher(compact).matches()) {
        coverDateCount++;
        currentCoverFieldRule = null;
        inTitleContinuation = false;
        checkCoverFieldRuns(results, evidence, paragraph, 0, "COVER-04");
        if (firstCoverDate == null) firstCoverDate = text;
        else {
          boolean consistent = firstCoverDate.equals(text);
          checked(results, evidence, result("COVER-04-CONSISTENCY", firstCoverDate, text,
              consistent ? "" : "COVER_DATE_MISMATCH",
              consistent ? Judgement.PASS : Judgement.FAIL, paragraph.source()));
        }
        continue;
      }
      Matcher titleLabel = COVER_TITLE_LABEL.matcher(text);
      if (titleLabel.find()) {
        checkCoverTitleRuns(results, evidence, paragraph, leadingWhitespace + titleLabel.end());
        titleChecked = true;
        inTitleContinuation = true;
        currentCoverFieldRule = null;
        continue;
      }
      if (currentCoverFieldRule != null && !text.isBlank()) {
        checkCoverFieldRuns(results, evidence, paragraph, 0, currentCoverFieldRule);
        continue;
      }
      if (!titleChecked && !text.isBlank()) {
        checkCoverTitleRuns(results, evidence, paragraph, 0);
        titleChecked = true;
        inTitleContinuation = true;
        continue;
      }
      if (inTitleContinuation && !text.isBlank())
        checkCoverTitleRuns(results, evidence, paragraph, 0);
    }
    var runs = new ArrayList<>(evidence.ruleRuns());
    if (coverBoundarySeen && coverDateCount == 1)
      runs.add(new RuleRun("COVER-04-CONSISTENCY", getClass().getSimpleName(), 0, 0, 0,
          "SINGLE_COVER_DATE"));
    return new Evaluation(List.copyOf(results), List.copyOf(runs));
  }

  private void checkPreviousSchoolName(List<CheckResult> results, Evidence evidence,
      ParsedDocument document, int typeIndex, DocumentRegionClassifier regions) {
    for (int index = typeIndex - 1; index >= 0; index--) {
      ParagraphModel candidate = document.paragraphs().get(index);
      if (regions.isInsideTable(candidate, document.tables())) continue;
      String text = paragraphText(candidate).strip();
      if (isFormattingOnlyText(text)) continue;
      if (!SCHOOL_NAME_TEXT.equals(compactCoverText(text))) return;
      for (RunModel run : candidate.runs()) {
        if (isFormattingOnlyRun(run)) continue;
        checked(results, evidence, checkCoverFont(run, "方正小标宋简体", "COVER-01-FONT"));
        checked(results, evidence, paragraphFormatChecker.checkExpectedSize(run, 72, "COVER-01-SIZE"));
      }
      checked(results, evidence, checkCoverAlignment(candidate, "COVER-01-ALIGNMENT"));
      return;
    }
  }

  private void checkCoverTitleRuns(List<CheckResult> results, Evidence evidence,
      ParagraphModel paragraph, int valueStart) {
    int offset = 0;
    for (RunModel run : paragraph.runs()) {
      int end = offset + run.text().length();
      offset = end;
      if (end <= valueStart || run.text().isBlank()) continue;
      checked(results, evidence, checkCoverFont(run, "黑体", "COVER-02-FONT"));
      checked(results, evidence, paragraphFormatChecker.checkExpectedSize(run, 36, "COVER-02-SIZE"));
    }
  }

  private void checkCoverFieldRuns(List<CheckResult> results, Evidence evidence,
      ParagraphModel paragraph, int valueStart, String ruleId) {
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
      checked(results, evidence, checkCoverFont(run, "仿宋", ruleId + "-FONT"));
      checked(results, evidence, paragraphFormatChecker.checkExpectedSize(run, 32, ruleId + "-SIZE"));
      if (run.bold() == null)
        checked(results, evidence, review(ruleId + "-BOLD", "true", "BOLD_UNKNOWN", run.source()));
      else checked(results, evidence, result(ruleId + "-BOLD", "true", String.valueOf(run.bold()), "",
          run.bold() ? Judgement.PASS : Judgement.FAIL, run.source()));
    }
    if (!found) checked(results, evidence,
        review(ruleId + "-FONT", "仿宋", "COVER_FIELD_VALUE_UNKNOWN", paragraph.source()));
  }

  private static void checked(List<CheckResult> results, Evidence evidence, CheckResult result) {
    evidence.record(result.ruleId(), result.judgement() == Judgement.REVIEW
        || result.applicability() == Applicability.UNKNOWN
        || result.executionStatus() != ExecutionStatus.EXECUTED);
    results.add(result);
  }

  private static final class Evidence {
    private final Map<String, int[]> counts = new LinkedHashMap<>();

    private void record(String ruleId, boolean unknown) {
      int[] count = counts.computeIfAbsent(ruleId, ignored -> new int[2]);
      count[0]++;
      if (unknown) count[1]++;
    }

    private List<RuleRun> ruleRuns() {
      var runs = new ArrayList<RuleRun>();
      for (var entry : counts.entrySet()) {
        int candidates = entry.getValue()[0];
        int unknown = entry.getValue()[1];
        runs.add(new RuleRun(entry.getKey(), CoverRuleChecker.class.getSimpleName(),
            candidates, candidates - unknown, unknown, null));
      }
      return List.copyOf(runs);
    }
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
