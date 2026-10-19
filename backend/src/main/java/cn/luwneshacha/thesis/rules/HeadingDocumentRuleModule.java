package cn.luwneshacha.thesis.rules;

import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.RunModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import java.util.ArrayList;
import java.util.List;

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
    var findings = new ArrayList<CheckResult>();
    boolean chapterNeedsBlankLine = false;
    int chapterBlankLineCount = 0;
    boolean inToc = false;

    for (int index = 0; index < document.paragraphs().size(); index++) {
      ParagraphModel paragraph = document.paragraphs().get(index);
      if (regions.hasDefiniteContentsEvidence(paragraph)) continue;
      String text = paragraphText(paragraph);
      String normalizedText = text.replaceAll("\\s+", "");
      boolean insideTable = regions.isInsideTable(paragraph, document.tables());
      boolean tocEntry = inToc && isTocEntryParagraph(document, index, paragraph, text);
      boolean namedSectionTitle = !insideTable && !tocEntry && checkNamedSectionTitle(findings, paragraph);
      if (!insideTable
          && ("目录".equals(normalizedText) || "CONTENTS".equalsIgnoreCase(normalizedText))) inToc = true;

      if (chapterNeedsBlankLine && !insideTable && !tocEntry && !isVisualBlankParagraph(paragraph)) {
        if (chapterBlankLineCount == 0)
          findings.add(result("HEAD-02-BLANK", "空一行", "未空一行", "CHAPTER_BODY_BLANK_LINE_MISSING",
              Judgement.FAIL, paragraph.source()));
        else if (chapterBlankLineCount > 1)
          findings.add(result("HEAD-02-BLANK", "空一行", String.valueOf(chapterBlankLineCount),
              "CHAPTER_BODY_BLANK_LINE_COUNT_MISMATCH", Judgement.FAIL, paragraph.source()));
        chapterNeedsBlankLine = false;
        chapterBlankLineCount = 0;
      }
      if (chapterNeedsBlankLine && !insideTable && isVisualBlankParagraph(paragraph)) chapterBlankLineCount++;
      if (!insideTable && !tocEntry && regions.isBodyChapterStart(paragraph)) {
        chapterNeedsBlankLine = true;
        chapterBlankLineCount = ManualLineBreakSupport.blankLinesAfter(paragraph);
      }
      if (isVisualBlankParagraph(paragraph)) continue;
      if (tocEntry) {
        // ContentsRuleChecker owns entry spacing; retain this state only to exclude TOC entries.
        continue;
      } else if (inToc && (regions.isBodyChapterStart(paragraph, document)
          || regions.isEndMatterHeading(paragraph, document))) {
        inToc = false;
      }
      findings.addAll(headingRuleChecker.check(paragraph, rules, insideTable, namedSectionTitle));
    }
    return List.copyOf(findings);
  }

  private boolean checkNamedSectionTitle(List<CheckResult> findings, ParagraphModel paragraph) {
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
    if ("EN-01".equals(ruleId) && !"ABSTRACT".equals(titleText))
      findings.add(result("EN-01-TEXT", "ABSTRACT", titleText, "ABSTRACT_TITLE_CASE_MISMATCH",
          Judgement.FAIL, paragraph.source()));
    if ("REF-01".equals(ruleId)) {
      boolean matches = "参考文献".equals(titleText);
      findings.add(result("REF-01-SPACING", "参考文献（字间不加空格）", titleText,
          matches ? "" : "REFERENCE_TITLE_SPACING_MISMATCH",
          matches ? Judgement.PASS : Judgement.FAIL,
          matches ? paragraph.source() : referenceTitleSpacingSource(paragraph, rawTextOffset, titleText)));
    } else if ("END-01".equals(ruleId)) {
      if ("致谢".equals(normalized)) {
        boolean matches = "致    谢".equals(titleText);
        findings.add(result("END-01-SPACING", "致    谢（字间空 4 个普通空格）", titleText,
            matches ? "" : "ACKNOWLEDGEMENT_TITLE_SPACING_MISMATCH",
            matches ? Judgement.PASS : Judgement.FAIL,
            matches ? paragraph.source()
                : interwordSpacingSource(paragraph, rawTextOffset, titleText, 4)));
      } else if ("ACKNOWLEDGMENTS".equalsIgnoreCase(normalized)
          || "ACKNOWLEDGEMENTS".equalsIgnoreCase(normalized)) {
        findings.add(result("END-01-SPACING", "致谢", rawText,
            "NAMED_SECTION_TITLE_TEXT_MISMATCH", Judgement.FAIL, paragraph.source()));
      }
    } else if ("APP-02".equals(ruleId)
        && ("附录".equals(normalized) || "APPENDIX".equalsIgnoreCase(normalized))) {
      boolean matches = "附    录".equals(titleText);
      findings.add(result("APP-02-SPACING", "附    录", titleText,
          matches ? "" : "APPENDIX_TITLE_SPACING_MISMATCH",
          matches ? Judgement.PASS : Judgement.FAIL,
          matches ? paragraph.source()
              : interwordSpacingSource(paragraph, rawTextOffset, titleText, 4)));
    }
    if (paragraph.alignment() == null)
      findings.add(result(ruleId + "-ALIGNMENT", "center", "unknown", "ALIGNMENT_UNKNOWN",
          Judgement.REVIEW, paragraph.source()));
    else
      findings.add(result(ruleId + "-ALIGNMENT", "center", paragraph.alignment(), "",
          "center".equalsIgnoreCase(paragraph.alignment()) ? Judgement.PASS : Judgement.FAIL,
          paragraph.source()));
    for (RunModel run : paragraph.runs()) {
      if (run.text().isBlank() || ManualLineBreakSupport.isBreakOnly(run.text())) continue;
      findings.add(headingRuleChecker.checkHeadingFont(run, 1, ruleId + "-FONT"));
      findings.add(paragraphFormatChecker.checkExpectedSize(run, 32, ruleId + "-SIZE"));
      if ("EN-01".equals(ruleId)) {
        if (run.bold() == null)
          findings.add(result("EN-01-BOLD", "true", "unknown", "BOLD_UNKNOWN", Judgement.REVIEW,
              run.source()));
        else
          findings.add(result("EN-01-BOLD", "true", String.valueOf(run.bold()), "",
              run.bold() ? Judgement.PASS : Judgement.FAIL, run.source()));
      }
    }
    return true;
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
    return paragraph == null || ManualLineBreakSupport.visualBlankLines(paragraph) > 0;
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
