package cn.luwneshacha.thesis.rules;

import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.RunModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class HeadingDocumentRuleModule implements RuleModule {
  private final HeadingRuleChecker headingRuleChecker;
  private final ParagraphFormatChecker paragraphFormatChecker;
  private final DocumentRegionClassifier regions = new DocumentRegionClassifier();

  HeadingDocumentRuleModule(HeadingRuleChecker headingRuleChecker,
      ParagraphFormatChecker paragraphFormatChecker) {
    this.headingRuleChecker = headingRuleChecker;
    this.paragraphFormatChecker = paragraphFormatChecker;
  }

  @Override
  public List<CheckResult> check(ParsedDocument document, RuleSet rules) {
    return evaluate(document, rules).results();
  }

  @Override
  public Evaluation evaluate(ParsedDocument document, RuleSet rules) {
    var findings = new ArrayList<CheckResult>();
    var evidence = new Evidence();
    boolean chapterNeedsBlankLine = false;
    int chapterBlankLineCount = 0;
    boolean firstLevelTitleNeedsBlankTypography = false;
    boolean inToc = false;

    for (int index = 0; index < document.paragraphs().size(); index++) {
      ParagraphModel paragraph = document.paragraphs().get(index);
      if (regions.hasDefiniteContentsEvidence(paragraph)) continue;
      String text = paragraphText(paragraph);
      String normalizedText = text.replaceAll("\\s+", "");
      boolean insideTable = regions.isInsideTable(paragraph, document.tables());
      boolean tocEntry = inToc && isTocEntryParagraph(document, index, paragraph, text);
      boolean namedSectionTitle = !insideTable && !tocEntry
          && checkNamedSectionTitle(findings, paragraph, evidence);
      boolean visualBlank = isVisualBlankParagraph(paragraph);
      if (insideTable) firstLevelTitleNeedsBlankTypography = false;
      if (firstLevelTitleNeedsBlankTypography && !insideTable) {
        if (visualBlank) checkBlankLineTypography(findings, evidence, paragraph,
            paragraph.runs().isEmpty() ? List.of() : paragraph.runs());
        else firstLevelTitleNeedsBlankTypography = false;
      }
      if (!insideTable
          && ("目录".equals(normalizedText) || "CONTENTS".equalsIgnoreCase(normalizedText))) inToc = true;

      if (chapterNeedsBlankLine && !insideTable && !tocEntry && !visualBlank) {
        if (chapterBlankLineCount == 0) {
          CheckResult finding = result("HEAD-02-BLANK", "空一行", "未空一行",
              "CHAPTER_BODY_BLANK_LINE_MISSING", Judgement.FAIL, paragraph.source());
          findings.add(finding);
          evidence.decision(finding);
        } else if (chapterBlankLineCount > 1) {
          CheckResult finding = result("HEAD-02-BLANK", "空一行", String.valueOf(chapterBlankLineCount),
              "CHAPTER_BODY_BLANK_LINE_COUNT_MISMATCH", Judgement.FAIL, paragraph.source());
          findings.add(finding);
          evidence.decision(finding);
        } else {
          evidence.pass("HEAD-02-BLANK");
        }
        chapterNeedsBlankLine = false;
        chapterBlankLineCount = 0;
      }
      if (chapterNeedsBlankLine && !insideTable && visualBlank) chapterBlankLineCount++;
      boolean chapterStart = !insideTable && !tocEntry && regions.isBodyChapterStart(paragraph);
      if (chapterStart) {
        evidence.candidate("HEAD-02-BLANK");
        chapterNeedsBlankLine = true;
        chapterBlankLineCount = ManualLineBreakSupport.blankLinesAfter(paragraph);
      }
      if (!insideTable && !tocEntry && (chapterStart
          || (!namedSectionTitle && headingRuleChecker.headingLevel(paragraph) == 1)
          || (namedSectionTitle && List.of("摘要", "目录", "参考文献", "致谢", "附录")
              .contains(compactTitleText(text))))) {
        firstLevelTitleNeedsBlankTypography = true;
        if (ManualLineBreakSupport.blankLinesAfter(paragraph) > 0) {
          List<RunModel> breakRuns = paragraph.runs().stream()
              .filter(run -> run.text().indexOf('\n') >= 0 || run.text().indexOf('\r') >= 0)
              .toList();
          checkBlankLineTypography(findings, evidence, paragraph, breakRuns);
        }
      }
      if (visualBlank) continue;
      if (tocEntry) {
        // ContentsRuleChecker owns entry spacing; retain this state only to exclude TOC entries.
        continue;
      } else if (inToc && (regions.isBodyChapterStart(paragraph, document)
          || regions.isEndMatterHeading(paragraph, document))) {
        inToc = false;
      }
      findings.addAll(headingRuleChecker.check(paragraph, rules, insideTable,
          namedSectionTitle, evidence::checkedCandidate));
    }
    var runs = new ArrayList<>(evidence.runs(getClass().getSimpleName()));
    if (AppendixRuleModule.hasProvenOptionalAppendixAbsent(document)) {
      for (String ruleId : List.of("APP-02-SPACING", "APP-02-ALIGNMENT", "APP-02-FONT",
          "APP-02-SIZE"))
        runs.add(new RuleRun(ruleId, getClass().getSimpleName(), 0, 0, 0,
            AppendixRuleModule.OPTIONAL_APPENDIX_ABSENT_REASON));
    }
    return new Evaluation(List.copyOf(findings), List.copyOf(runs));
  }

  private boolean checkNamedSectionTitle(List<CheckResult> findings, ParagraphModel paragraph,
      Evidence evidence) {
    String fullText = ManualLineBreakSupport.text(paragraph);
    int rawTextOffset = leadingBoundaryBreakCount(fullText);
    String rawText = ManualLineBreakSupport.stripBoundaryBreaks(fullText);
    String titleText = rawText.stripTrailing();
    String normalized = compactTitleText(titleText);
    String ruleId = switch (normalized.toUpperCase(java.util.Locale.ROOT)) {
      case "摘要" -> "ZH-01";
      case "ABSTRACT" -> "EN-01";
      case "目录", "CONTENTS" -> "TOC-01";
      case "参考文献", "REFERENCES" -> "REF-01";
      case "致谢", "ACKNOWLEDGMENTS", "ACKNOWLEDGEMENTS" -> "END-01";
      case "附录", "APPENDIX" -> "APP-02";
      default -> null;
    };
    if (ruleId == null) return regions.appendixHeadingLetter(paragraph) != null;
    if ("TOC-01".equals(ruleId)) return true;
    if ("EN-01".equals(ruleId)) {
      evidence.candidate("EN-01-TEXT");
      if ("ABSTRACT".equals(titleText)) evidence.pass("EN-01-TEXT");
      else {
        CheckResult finding = result("EN-01-TEXT", "ABSTRACT", titleText,
            "ABSTRACT_TITLE_CASE_MISMATCH", Judgement.FAIL, paragraph.source());
        findings.add(finding);
        evidence.decision(finding);
      }
    }
    if ("REF-01".equals(ruleId)) {
      boolean matches = "参考文献".equals(titleText);
      add(findings, evidence, result("REF-01-SPACING", "参考文献（字间不加空格）", titleText,
          matches ? "" : "REFERENCE_TITLE_SPACING_MISMATCH",
          matches ? Judgement.PASS : Judgement.FAIL,
          matches ? paragraph.source() : referenceTitleSpacingSource(paragraph, rawTextOffset, titleText)));
    } else if ("END-01".equals(ruleId)) {
      if ("致谢".equals(normalized)) {
        boolean matches = "致    谢".equals(titleText);
        add(findings, evidence, result("END-01-SPACING", "致    谢（字间空 4 个普通空格）", titleText,
            matches ? "" : "ACKNOWLEDGEMENT_TITLE_SPACING_MISMATCH",
            matches ? Judgement.PASS : Judgement.FAIL,
            matches ? paragraph.source()
                : interwordSpacingSource(paragraph, rawTextOffset, titleText, 4)));
      } else if ("ACKNOWLEDGMENTS".equalsIgnoreCase(normalized)
          || "ACKNOWLEDGEMENTS".equalsIgnoreCase(normalized)) {
        add(findings, evidence, result("END-01-SPACING", "致谢", rawText,
            "NAMED_SECTION_TITLE_TEXT_MISMATCH", Judgement.FAIL, paragraph.source()));
      }
    } else if ("APP-02".equals(ruleId)
        && ("附录".equals(normalized) || "APPENDIX".equalsIgnoreCase(normalized))) {
      boolean matches = "附    录".equals(titleText);
      add(findings, evidence, result("APP-02-SPACING", "附    录", titleText,
          matches ? "" : "APPENDIX_TITLE_SPACING_MISMATCH",
          matches ? Judgement.PASS : Judgement.FAIL,
          matches ? paragraph.source()
              : interwordSpacingSource(paragraph, rawTextOffset, titleText, 4)));
    }
    if (paragraph.alignment() == null)
      add(findings, evidence, result(ruleId + "-ALIGNMENT", "center", "unknown", "ALIGNMENT_UNKNOWN",
          Judgement.REVIEW, paragraph.source()));
    else
      add(findings, evidence, result(ruleId + "-ALIGNMENT", "center", paragraph.alignment(), "",
          "center".equalsIgnoreCase(paragraph.alignment()) ? Judgement.PASS : Judgement.FAIL,
          paragraph.source()));
    for (RunModel run : paragraph.runs()) {
      if (run.text().isBlank() || ManualLineBreakSupport.isBreakOnly(run.text())) continue;
      add(findings, evidence, headingRuleChecker.checkHeadingFont(run, 1, ruleId + "-FONT"));
      add(findings, evidence, paragraphFormatChecker.checkExpectedSize(run, 32, ruleId + "-SIZE"));
      if ("EN-01".equals(ruleId)) {
        if (run.bold() == null)
          add(findings, evidence, result("EN-01-BOLD", "true", "unknown", "BOLD_UNKNOWN", Judgement.REVIEW,
              run.source()));
        else
          add(findings, evidence, result("EN-01-BOLD", "true", String.valueOf(run.bold()), "",
              run.bold() ? Judgement.PASS : Judgement.FAIL, run.source()));
      }
    }
    return true;
  }

  private static void add(List<CheckResult> findings, Evidence evidence, CheckResult checked) {
    evidence.checkedCandidate(checked);
    findings.add(checked);
  }

  private static final class Evidence {
    private final Map<String, Counts> counts = new LinkedHashMap<>();

    private void candidate(String ruleId) {
      counts.computeIfAbsent(ruleId, ignored -> new Counts()).candidates++;
    }

    private void checkedCandidate(CheckResult result) {
      candidate(result.ruleId());
      decision(result);
    }

    private void decision(CheckResult result) {
      if (result.judgement() == Judgement.PASS || result.judgement() == Judgement.FAIL) {
        if (result.applicability() == Applicability.APPLICABLE
            && result.executionStatus() == ExecutionStatus.EXECUTED)
          counts.get(result.ruleId()).checked++;
      }
    }

    private void pass(String ruleId) {
      counts.get(ruleId).checked++;
    }

    private List<RuleRun> runs(String moduleId) {
      var runs = new ArrayList<RuleRun>();
      for (var entry : counts.entrySet()) {
        Counts value = entry.getValue();
        if (value.candidates > 0 && value.checked <= value.candidates)
          runs.add(new RuleRun(entry.getKey(), moduleId, value.candidates, value.checked,
              value.candidates - value.checked, null));
      }
      return List.copyOf(runs);
    }
  }

  private static final class Counts {
    private int candidates;
    private int checked;
  }

  private static String compactTitleText(String text) {
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

  private static SourceAddress referenceTitleSpacingSource(ParagraphModel paragraph, int titleOffset,
      String titleText) {
    for (int index = 0; index < titleText.length();) {
      int codePoint = titleText.codePointAt(index);
      if (isTitleWhitespace(codePoint))
        return range(paragraph, titleOffset + index, titleOffset + index + Character.charCount(codePoint));
      index += Character.charCount(codePoint);
    }
    return range(paragraph, titleOffset, titleOffset);
  }

  private static SourceAddress interwordSpacingSource(ParagraphModel paragraph, int titleOffset,
      String titleText, int expectedSpaces) {
    int firstText = 0;
    while (firstText < titleText.length()) {
      int codePoint = titleText.codePointAt(firstText);
      if (!isTitleWhitespace(codePoint)) break;
      return range(paragraph, titleOffset + firstText,
          titleOffset + firstText + Character.charCount(codePoint));
    }
    if (firstText == titleText.length()) return range(paragraph, titleOffset, titleOffset);

    int gapStart = firstText + Character.charCount(titleText.codePointAt(firstText));
    int gapEnd = gapStart;
    int gapCount = 0;
    while (gapEnd < titleText.length()) {
      int codePoint = titleText.codePointAt(gapEnd);
      if (!isTitleWhitespace(codePoint)) break;
      if (codePoint != 0x20)
        return range(paragraph, titleOffset + gapEnd,
            titleOffset + gapEnd + Character.charCount(codePoint));
      if (gapCount == expectedSpaces)
        return range(paragraph, titleOffset + gapEnd,
            titleOffset + gapEnd + Character.charCount(codePoint));
      gapCount++;
      gapEnd += Character.charCount(codePoint);
    }
    if (gapCount < expectedSpaces)
      return range(paragraph, titleOffset + gapEnd, titleOffset + gapEnd);
    return range(paragraph, titleOffset + gapStart, titleOffset + gapEnd);
  }

  private static boolean isTitleWhitespace(int codePoint) {
    return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
        || codePoint == 0x200B || codePoint == 0x200C || codePoint == 0x200D
        || codePoint == 0x2060 || codePoint == 0xFEFF;
  }

  private static int leadingBoundaryBreakCount(String text) {
    int offset = 0;
    while (offset < text.length() && (text.charAt(offset) == '\n' || text.charAt(offset) == '\r'))
      offset++;
    return offset;
  }

  private static SourceAddress range(ParagraphModel paragraph, int start, int end) {
    SourceAddress source = paragraph.source();
    return new SourceAddress(source.partName(), source.elementPath(),
        source.start() + start, source.start() + end);
  }

  private boolean isVisualBlankParagraph(ParagraphModel paragraph) {
    return paragraph != null && paragraph.structurallyBlankParagraph()
        && ManualLineBreakSupport.visualBlankLines(paragraph) > 0;
  }

  private void checkBlankLineTypography(List<CheckResult> findings, Evidence evidence,
      ParagraphModel paragraph, List<RunModel> blankRuns) {
    List<RunModel> formatting = blankRuns.isEmpty() && paragraph.blankParagraphMarkFormat() != null
        ? List.of(paragraph.blankParagraphMarkFormat()) : blankRuns;
    if (formatting.isEmpty()) {
      add(findings, evidence, result("HEAD-02-BLANK-FONT", "黑体", "无法读取空行字体",
          "FIRST_LEVEL_BLANK_LINE_FONT_UNKNOWN", Judgement.REVIEW, paragraph.source()));
      add(findings, evidence, result("HEAD-02-BLANK-SIZE", "三号（16 磅）", "无法读取空行字号",
          "FIRST_LEVEL_BLANK_LINE_SIZE_UNKNOWN", Judgement.REVIEW, paragraph.source()));
      return;
    }
    for (RunModel format : formatting) {
      String fonts = "中文=" + format.eastAsiaFont();
      boolean fontKnown = format.eastAsiaFont() != null;
      boolean fontMatches = fontKnown
          && ParagraphFormatChecker.matchesChineseFont("黑体", format.eastAsiaFont());
      add(findings, evidence, result("HEAD-02-BLANK-FONT", "黑体", fonts,
          fontKnown ? fontMatches ? "" : "FIRST_LEVEL_BLANK_LINE_FONT_MISMATCH"
              : "FIRST_LEVEL_BLANK_LINE_FONT_UNKNOWN",
          fontKnown ? fontMatches ? Judgement.PASS : Judgement.FAIL : Judgement.REVIEW,
          format.source()));
      Integer size = format.fontSizeHalfPoints();
      add(findings, evidence, result("HEAD-02-BLANK-SIZE", "三号（16 磅）",
          size == null ? "无法读取空行字号" : (size / 2.0) + " 磅",
          size == null ? "FIRST_LEVEL_BLANK_LINE_SIZE_UNKNOWN"
              : size == 32 ? "" : "FIRST_LEVEL_BLANK_LINE_SIZE_MISMATCH",
          size == null ? Judgement.REVIEW : size == 32 ? Judgement.PASS : Judgement.FAIL,
          format.source()));
    }
  }

  boolean isTocEntryParagraph(ParsedDocument document, int index,
      ParagraphModel paragraph, String text) {
    return regions.isPotentialContentsEntry(document, index, paragraph, text);
  }

  private static String paragraphText(ParagraphModel paragraph) {
    return ManualLineBreakSupport.stripBoundaryBreaks(ManualLineBreakSupport.text(paragraph)).trim();
  }

  private static CheckResult result(String ruleId, String expected, String actual, String reason,
      Judgement judgement, SourceAddress source) {
    return new CheckResult(ruleId, Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        judgement, expected, actual, reason, source);
  }
}
