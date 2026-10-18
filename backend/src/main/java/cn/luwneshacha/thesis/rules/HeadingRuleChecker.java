package cn.luwneshacha.thesis.rules;

import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.RunModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Owns heading classification and heading-specific formatting checks.
 *
 * <p>{@link HeadingDocumentRuleModule} owns document traversal and state; this
 * checker evaluates one paragraph so heading checks share paragraph-format
 * evidence with the other modules.</p>
 */
final class HeadingRuleChecker {
  private static final Pattern TOC_ENTRY = Pattern.compile(".+(?:\\.{2,}|……+|\\t|[\\p{Zs}]{2,})\\s*\\d+$");
  private static final Pattern NUMBERED_HEADING_PREFIX =
      Pattern.compile("^(\\d+(?:\\s*\\.\\s*\\d+){1,2})(.*)$");
  private static final Pattern YEAR_MONTH_PROSE_PREFIX = Pattern.compile("^(?:19|20)\\d{2}\\.\\d{1,2}(?=\\p{IsHan})");
  private static final Pattern HEADING_STYLE = Pattern.compile("(?i)^(?:heading|标题)([1-3])$");

  private final ParagraphFormatChecker paragraphFormatChecker;
  private final DocumentRegionClassifier regions = new DocumentRegionClassifier();

  HeadingRuleChecker(ParagraphFormatChecker paragraphFormatChecker) {
    this.paragraphFormatChecker = paragraphFormatChecker;
  }

  List<CheckResult> check(ParagraphModel paragraph, RuleSet rules,
      boolean insideTable, boolean namedSectionTitle) {
    if (regions.hasDefiniteContentsEvidence(paragraph)) return List.of();
    var results = new ArrayList<CheckResult>();
    String text = paragraphText(paragraph);
    if (!insideTable && !namedSectionTitle && !TOC_ENTRY.matcher(text).matches()) {
      int level = headingLevel(paragraph);
      if (level > 0) {
        if (level <= 3) {
          if (level == 1) results.add(checkHeadingAlignment(paragraph));
          for (RunModel run : paragraph.runs()) {
            results.add(checkHeadingFont(run, level));
            results.add(checkHeadingSize(run, level));
          }
          if (level == 2 || level == 3) {
            results.add(paragraphFormatChecker.checkLineSpacing("HEADING-LINE", paragraph));
          }
          if (level == 3) {
            results.add(paragraphFormatChecker.checkIndent(paragraph, rules, "HEAD-03-INDENT"));
          }
        }
        if (hasHeadingNumber(paragraph, level, text)) {
          results.add(checkHeadingNumberSpacing(paragraph, level, text));
        }
      }
      if (level == 0 && isLowerHeading(text)) {
        boolean markerMatches = text.matches("^（\\d+）(?:、)?[^\\p{P}\\p{S}\\s].{0,79}$");
        results.add(result("HEAD-05-MARKER", "（1）标题（顿号可选）", text,
            markerMatches ? "" : "LOWER_HEADING_MARKER_MISMATCH",
            markerMatches ? Judgement.PASS : Judgement.FAIL, paragraph.source()));
        for (RunModel run : paragraph.runs()) {
          if (run.text().isBlank()) continue;
          results.add(paragraphFormatChecker.checkFont(run, rules, "HEAD-05-FONT"));
          results.add(paragraphFormatChecker.checkSize(run, rules, "HEAD-05-SIZE"));
        }
        results.add(paragraphFormatChecker.checkExactFirstLineIndent(paragraph,
            rules.expectedFirstLineChars(), 420, "HEAD-05-INDENT"));
        results.add(paragraphFormatChecker.checkLineSpacing("HEAD-05-LINE", paragraph));
      }
    }
    return List.copyOf(results);
  }

  int headingLevel(ParagraphModel paragraph) {
    if (regions.hasDefiniteContentsEvidence(paragraph)) return 0;
    String text = paragraphText(paragraph);
    if (isSummaryTitle(text)) return 0;
    if ("结论".equals(text.strip()) || "结论与展望".equals(text.strip())) {
      Integer outlineLevel = paragraph.outlineLevel();
      boolean hasOutlineEvidence = outlineLevel != null && outlineLevel >= 0 && outlineLevel < 3;
      boolean hasNumberingEvidence = paragraph.automaticNumberingActive()
          && paragraph.numberingLabel() != null && !paragraph.numberingLabel().isBlank();
      return hasOutlineEvidence || headingStyleLevel(paragraph) > 0 || hasNumberingEvidence ? 1 : 0;
    }
    if (TOC_ENTRY.matcher(text).matches()) return 0;
    if (isChapterHeading(text)) return 1;
    if (isUnnumberedIntroductionHeading(text) && hasHeadingEvidence(paragraph)) return 1;
    if (isYearMonthProse(paragraph, text)) return 0;
    int numericLevel = numericHeadingLevel(text);
    if (numericLevel > 0) return numericLevel;
    if (paragraph.outlineLevel() != null && paragraph.outlineLevel() >= 0 && paragraph.outlineLevel() < 3
        && paragraph.automaticNumberingActive() && paragraph.numberingLabel() != null
        && (paragraph.numberingLabel().matches("\\d+(?:\\.\\d+)*")
            || paragraph.numberingLabel().matches("第\\d+章"))) return paragraph.outlineLevel() + 1;
    return headingStyleLevel(paragraph);
  }

  private int headingStyleLevel(ParagraphModel paragraph) {
    int styleIdLevel = headingStyleLevel(paragraph.paragraphStyleId());
    return styleIdLevel > 0 ? styleIdLevel : headingStyleLevel(paragraph.paragraphStyleName());
  }

  private int headingStyleLevel(String styleNameOrId) {
    if (styleNameOrId == null) return 0;
    Matcher matcher = HEADING_STYLE.matcher(styleNameOrId.replaceAll("\\s+", "").strip());
    return matcher.matches() ? Integer.parseInt(matcher.group(1)) : 0;
  }

  private boolean hasHeadingEvidence(ParagraphModel paragraph) {
    return headingStyleLevel(paragraph) > 0
        || Integer.valueOf(0).equals(paragraph.outlineLevel())
        || paragraph.automaticNumberingActive();
  }

  private boolean isUnnumberedIntroductionHeading(String text) {
    String compact = text == null ? "" : text.codePoints()
        .filter(codePoint -> !Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)
            && codePoint != 0x200B && codePoint != 0x200C && codePoint != 0x200D
            && codePoint != 0x2060 && codePoint != 0xFEFF)
        .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
        .toString();
    return compact.equals("绪论") || compact.equals("引言");
  }

  private boolean isYearMonthProse(ParagraphModel paragraph, String text) {
    return !paragraph.automaticNumberingActive()
        && headingStyleLevel(paragraph) == 0
        && YEAR_MONTH_PROSE_PREFIX.matcher(text).find();
  }

  private CheckResult checkHeadingAlignment(ParagraphModel paragraph) {
    if (paragraph.alignment() == null)
      return result("HEAD-01-ALIGNMENT", "center", "unknown", "ALIGNMENT_UNKNOWN",
          Judgement.REVIEW, paragraph.source());
    boolean matches = "center".equalsIgnoreCase(paragraph.alignment());
    return result("HEAD-01-ALIGNMENT", "center", paragraph.alignment(),
        matches ? "" : "HEADING_ALIGNMENT_MISMATCH",
        matches ? Judgement.PASS : Judgement.FAIL, paragraph.source());
  }

  private int numericHeadingLevel(String text) {
    Matcher matcher = NUMBERED_HEADING_PREFIX.matcher(text);
    if (!matcher.matches()) return 0;
    String suffix = matcher.group(2);
    int offset = 0;
    while (offset < suffix.length()) {
      int codePoint = suffix.codePointAt(offset);
      if (!isHeadingSeparator(codePoint)) break;
      offset += Character.charCount(codePoint);
    }
    if (offset == suffix.length()) return 0;
    int firstTitleCodePoint = suffix.codePointAt(offset);
    boolean startsWithLetter = isHeadingLetter(firstTitleCodePoint);
    boolean separatedTitleContainsLetters = offset > 0
        && suffix.codePoints().anyMatch(this::isHeadingLetter);
    if (!startsWithLetter && !separatedTitleContainsLetters) return 0;
    return matcher.group(1).chars().filter(character -> character == '.').count() == 1 ? 2 : 3;
  }

  private boolean isHeadingLetter(int codePoint) {
    Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
    return script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.LATIN;
  }

  private boolean isHeadingSeparator(int codePoint) {
    return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
        || codePoint == 0x200B || codePoint == 0x200C || codePoint == 0x200D
        || codePoint == 0x2060 || codePoint == 0xFEFF;
  }

  CheckResult checkHeadingFont(RunModel run, int level) {
    return checkHeadingFont(run, level, "HEADING-FONT");
  }

  CheckResult checkHeadingFont(RunModel run, int level, String ruleId) {
    String expected = (level == 1 ? "一级标题" : level == 2 ? "二级标题" : "三级标题")
        + "：黑体，数字字母 Times New Roman";
    boolean hasHan = run.text().codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN);
    boolean hasLatin = run.text().codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.LATIN
        || (c >= '0' && c <= '9'));
    boolean hasHighAnsi = run.text().codePoints().anyMatch(c -> c > 0x7f
        && (Character.UnicodeScript.of(c) == Character.UnicodeScript.LATIN
            || Character.UnicodeScript.of(c) == Character.UnicodeScript.GREEK
            || Character.UnicodeScript.of(c) == Character.UnicodeScript.CYRILLIC));
    if ((hasHan && run.eastAsiaFont() == null) || (hasLatin && run.asciiFont() == null)
        || (hasHighAnsi && run.highAnsiFont() == null))
      return result(ruleId, "黑体/Times New Roman", "unknown", "FONT_UNKNOWN", Judgement.REVIEW, run.source());
    boolean matches = (!hasHan || ParagraphFormatChecker.matchesChineseFont("黑体", run.eastAsiaFont()))
        && (!hasLatin || "Times New Roman".equals(run.asciiFont()))
        && (!hasHighAnsi || "Times New Roman".equals(run.highAnsiFont()));
    return result(ruleId, expected,
        run.eastAsiaFont() + "/" + run.asciiFont() + "/" + run.highAnsiFont(), "",
        matches ? Judgement.PASS : Judgement.FAIL, run.source());
  }

  private CheckResult checkHeadingSize(RunModel run, int level) {
    int expected = level == 1 ? 32 : level == 2 ? 28 : 24;
    if (run.fontSizeHalfPoints() == null)
      return result("HEADING-SIZE", String.valueOf(expected), "unknown", "SIZE_UNKNOWN", Judgement.REVIEW, run.source());
    boolean matches = run.fontSizeHalfPoints() == expected;
    return result("HEADING-SIZE", String.valueOf(expected), String.valueOf(run.fontSizeHalfPoints()),
        matches ? "" : "SIZE_MISMATCH", matches ? Judgement.PASS : Judgement.FAIL, run.source());
  }

  private boolean hasHeadingNumber(ParagraphModel paragraph, int level, String text) {
    if (paragraph.automaticNumberingActive()) return true;
    if (level == 1) return text.replaceAll("[\\u200B\\u200C\\u200D\\u2060\\uFEFF]", "").matches("^第.+?章.*$");
    return text.matches("^\\d+(?:\\s*\\.\\s*\\d+){" + (level - 1) + "}.*$");
  }

  private CheckResult checkHeadingNumberSpacing(ParagraphModel paragraph, int level, String text) {
    Matcher internalWhitespace = Pattern.compile("^(\\d+(?:\\s*\\.\\s*\\d+){1,2})(?=\\s|$).*$")
        .matcher(text);
    if (internalWhitespace.matches() && internalWhitespace.group(1).matches(".*\\s+.*")) {
      String actual = internalWhitespace.group(1);
      int badWhitespace = firstWhitespaceIndex(actual);
      return result(level == 1 ? "HEAD-01-SPACING" : "HEAD-0" + level + "-SPACING",
          actual.replaceAll("\\s+", ""), actual, "HEADING_NUMBER_INTERNAL_WHITESPACE",
          Judgement.FAIL, sourceRange(paragraph,
              internalWhitespace.start(1) + badWhitespace,
              internalWhitespace.start(1) + badWhitespace + 1));
    }
    Matcher chapterNumber = Pattern.compile("^(第[一二三四五六七八九十百千万零〇\\s\\p{Zs}]+章)(.*)$")
        .matcher(text);
    if (level == 1 && chapterNumber.matches() && chapterNumber.group(1).matches(".*[\\s\\p{Zs}].*")) {
      String actual = chapterNumber.group(1);
      int badWhitespace = firstWhitespaceIndex(actual);
      return result("HEAD-01-SPACING", "章节序号内部不夹空格", actual,
          "HEADING_NUMBER_SPACING_MISMATCH", Judgement.FAIL,
          sourceRange(paragraph, chapterNumber.start(1) + badWhitespace,
              chapterNumber.start(1) + badWhitespace + 1));
    }
    Pattern numberPattern = level == 1
        ? Pattern.compile("^(第.+?章)(.*)$")
        : Pattern.compile("^(\\d+(?:\\.\\d+){" + (level - 1) + "})(.*)$");
    Matcher numberMatcher = numberPattern.matcher(text);
    int expected = level == 1 ? 2 : 1;
    int spaces = -1;
    boolean invalidSeparator = false;
    String separatorDescription = null;
    int badSeparatorStart = -1;
    int badSeparatorEnd = -1;
    boolean badSeparatorUsesRawOffset = false;
    int missingSeparatorPoint = 0;
    if (paragraph.automaticNumberingActive() && paragraph.numberingLabel() == null) {
      invalidSeparator = true;
      separatorDescription = "自动编号设置无法确认";
    }
    if (numberMatcher.matches()) {
      String suffix = numberMatcher.group(2);
      int suffixStart = numberMatcher.start(2);
      missingSeparatorPoint = suffixStart;
      spaces = 0;
      while (spaces < suffix.length()) {
        int codePoint = suffix.codePointAt(spaces);
        if (!isHeadingSeparator(codePoint)) break;
        if (codePoint != 0x20) {
          invalidSeparator = true;
          if (badSeparatorStart < 0) {
            badSeparatorStart = suffixStart + spaces;
            badSeparatorEnd = badSeparatorStart + Character.charCount(codePoint);
          }
        }
        spaces += Character.charCount(codePoint);
      }
      if (badSeparatorStart < 0 && spaces > expected) {
        badSeparatorStart = suffixStart + whitespaceOffset(suffix, expected);
        badSeparatorEnd = badSeparatorStart + 1;
      }
      separatorDescription = describeHeadingSeparator(suffix);
    }
    if (paragraph.automaticNumberingActive() && spaces < 0) {
      var visibleWhitespaceOffsets = new ArrayList<Integer>();
      int visibleLeadingSpaces = 0;
      boolean invalidVisibleSeparator = false;
      int invalidVisibleSeparatorOffset = -1;
      boolean leadingTextEnded = false;
      int runOffset = 0;
      for (RunModel run : paragraph.runs()) {
        for (int offset = 0; offset < run.text().length();) {
          int codePoint = run.text().codePointAt(offset);
          if (leadingTextEnded || !isHeadingSeparator(codePoint)) {
            leadingTextEnded = true;
            break;
          }
          visibleWhitespaceOffsets.add(runOffset + offset);
          if (codePoint == 0x20) visibleLeadingSpaces++;
          else {
            invalidVisibleSeparator = true;
            if (invalidVisibleSeparatorOffset < 0) invalidVisibleSeparatorOffset = runOffset + offset;
          }
          offset += Character.charCount(codePoint);
        }
        runOffset += run.text().length();
        if (leadingTextEnded) break;
      }
      Integer suffixSpaces = paragraph.numberingSeparatorSpaces();
      if (suffixSpaces == null) {
        invalidSeparator = true;
        separatorDescription = "编号后不是普通空格";
        if (invalidVisibleSeparatorOffset >= 0) {
          badSeparatorStart = invalidVisibleSeparatorOffset;
          badSeparatorEnd = badSeparatorStart + 1;
          badSeparatorUsesRawOffset = true;
        }
      } else {
        int formatTrailingSpaces = paragraph.numberingFormatTrailingSpaces() == null
            ? 0 : paragraph.numberingFormatTrailingSpaces();
        spaces = suffixSpaces + visibleLeadingSpaces
            + formatTrailingSpaces;
        invalidSeparator = invalidVisibleSeparator;
        if (invalidVisibleSeparatorOffset >= 0) {
          badSeparatorStart = invalidVisibleSeparatorOffset;
          badSeparatorEnd = badSeparatorStart + 1;
          badSeparatorUsesRawOffset = true;
        } else if (spaces > expected) {
          int extraVisibleSpaceIndex = expected - suffixSpaces - formatTrailingSpaces;
          if (extraVisibleSpaceIndex >= 0 && extraVisibleSpaceIndex < visibleWhitespaceOffsets.size()) {
            badSeparatorStart = visibleWhitespaceOffsets.get(extraVisibleSpaceIndex);
            badSeparatorEnd = badSeparatorStart + 1;
            badSeparatorUsesRawOffset = true;
          }
        }
        separatorDescription = spaces == 0 ? "没有空格" : spaces + " 个普通空格";
      }
    }
    if (!invalidSeparator && spaces < 0 && paragraph.numberingSeparatorSpaces() != null)
      spaces = paragraph.numberingSeparatorSpaces();
    if (separatorDescription == null)
      separatorDescription = spaces < 0 ? "编号设置的空白无法确认" : spaces + " 个空格";
    if (invalidSeparator || spaces != expected)
      return result(level == 1 ? "HEAD-01-SPACING" : "HEAD-0" + level + "-SPACING",
          "编号后空 " + expected + " 格", separatorDescription, "HEADING_NUMBER_SPACING_MISMATCH",
          Judgement.FAIL, badSeparatorStart >= 0
              ? badSeparatorUsesRawOffset
                  ? sourceRangeRaw(paragraph, badSeparatorStart, badSeparatorEnd)
                  : sourceRange(paragraph, badSeparatorStart, badSeparatorEnd)
              : pointRange(paragraph, missingSeparatorPoint));
    return result(level == 1 ? "HEAD-01-SPACING" : "HEAD-0" + level + "-SPACING",
        "编号后空 " + expected + " 格", String.valueOf(spaces), "", Judgement.PASS, paragraph.source());
  }

  private String describeHeadingSeparator(String suffix) {
    if (suffix.isEmpty()) return "没有空格";
    int index = 0;
    int count = 0;
    String description = null;
    while (index < suffix.length()) {
      int codePoint = suffix.codePointAt(index);
      boolean separator = Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
          || codePoint == 0x200B || codePoint == 0x200C || codePoint == 0x200D
          || codePoint == 0x2060 || codePoint == 0xFEFF;
      if (!separator) break;
      count++;
      String current = switch (codePoint) {
        case 0x20 -> "普通空格";
        case 0x09 -> "制表符";
        case 0x3000 -> "全角空格";
        case 0x00A0 -> "不换行空格";
        default -> "看不见的空白";
      };
      description = description == null || description.equals(current) ? current : "不同类型的空白";
      index += Character.charCount(codePoint);
    }
    if (count == 0) return "没有空格";
    if (count == 1 && !"看不见的空白".equals(description)) return description;
    return count + " 个" + description;
  }

  private int firstWhitespaceIndex(String text) {
    for (int index = 0; index < text.length();) {
      int codePoint = text.codePointAt(index);
      if (isHeadingSeparator(codePoint)) return index;
      index += Character.charCount(codePoint);
    }
    return 0;
  }

  private int whitespaceOffset(String text, int ordinarySpaceCount) {
    int spaces = 0;
    for (int index = 0; index < text.length();) {
      int codePoint = text.codePointAt(index);
      if (!isHeadingSeparator(codePoint)) break;
      if (spaces >= ordinarySpaceCount) return index;
      spaces++;
      index += Character.charCount(codePoint);
    }
    return text.length();
  }

  private SourceAddress sourceRange(ParagraphModel paragraph, int start, int end) {
    SourceAddress source = paragraph.source();
    int trimOffset = leadingTrimLength(paragraph);
    return new SourceAddress(source.partName(), source.elementPath(),
        source.start() + trimOffset + start, source.start() + trimOffset + end);
  }

  private SourceAddress pointRange(ParagraphModel paragraph, int offset) {
    return sourceRange(paragraph, offset, offset);
  }

  private SourceAddress sourceRangeRaw(ParagraphModel paragraph, int start, int end) {
    SourceAddress source = paragraph.source();
    return new SourceAddress(source.partName(), source.elementPath(),
        source.start() + start, source.start() + end);
  }

  private int leadingTrimLength(ParagraphModel paragraph) {
    String rawText = paragraph.runs().stream().map(RunModel::text).reduce("", String::concat);
    int offset = 0;
    while (offset < rawText.length() && rawText.charAt(offset) <= ' ') offset++;
    return offset;
  }

  boolean isLowerHeading(String text) {
    return text.matches("^(?:（\\d+）|\\(\\d+\\))[、.，,]?\\S.{0,79}$")
        && !text.matches(".*[；;。！？]$");
  }

  private boolean isChapterHeading(String text) {
    String normalized = text == null ? "" : text.replaceAll("[\\s\\p{Zs}\\u200B\\u200C\\u200D\\u2060\\uFEFF]+", "");
    return normalized.matches("^第[0-9一二三四五六七八九十百千零〇]+章.+$")
        && (text == null || !text.matches(".*[；;。！？]$"))
        && !TOC_ENTRY.matcher(text).matches();
  }

  private boolean isSummaryTitle(String text) {
    if (text == null) return false;
    String compact = text.codePoints()
        .filter(codePoint -> !Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)
            && codePoint != 0x200B && codePoint != 0x200C && codePoint != 0x200D
            && codePoint != 0x2060 && codePoint != 0xFEFF)
        .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
        .toString();
    return "总结".equals(compact);
  }

  private String paragraphText(ParagraphModel paragraph) {
    return paragraph.runs().stream().map(RunModel::text).reduce("", String::concat).trim();
  }

  private CheckResult result(String ruleId, String expected, String actual, String reason,
      Judgement judgement, cn.luwneshacha.thesis.document.SourceAddress source) {
    return new CheckResult(ruleId, Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        judgement, expected, actual, reason, source);
  }
}
