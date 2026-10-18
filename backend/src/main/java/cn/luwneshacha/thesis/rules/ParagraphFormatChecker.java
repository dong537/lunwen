package cn.luwneshacha.thesis.rules;

import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.RunModel;
import java.util.Objects;

/**
 * Shared checks for paragraph and run formatting evidence.
 *
 * <p>Semantic rule modules decide which rule applies; this module only compares
 * the supplied OOXML evidence with the requested formatting contract.</p>
 */
public final class ParagraphFormatChecker {

  public CheckResult checkFont(RunModel run, RuleSet rules, String ruleId) {
    Objects.requireNonNull(run, "run");
    Objects.requireNonNull(rules, "rules");
    return checkFont(run, rules.expectedEastAsiaFont(), rules.expectedLatinFont(), ruleId);
  }

  /**
   * Checks a run against the font contract owned by the calling semantic module.
   * This keeps title, cover, body, and reference checks on the same evidence
   * path while allowing each module to select its required Chinese and Latin
   * fonts.
   */
  public CheckResult checkFont(RunModel run, String expectedEastAsiaFont,
      String expectedLatinFont, String ruleId) {
    Objects.requireNonNull(run, "run");
    Objects.requireNonNull(expectedEastAsiaFont, "expectedEastAsiaFont");
    Objects.requireNonNull(expectedLatinFont, "expectedLatinFont");
    boolean hasHan = codePoints(run).anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN);
    boolean hasLatin = codePoints(run).anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.LATIN
        || (c >= '0' && c <= '9'));
    boolean hasComplex = codePoints(run).mapToObj(Character.UnicodeScript::of)
        .anyMatch(script -> script == Character.UnicodeScript.ARABIC || script == Character.UnicodeScript.HEBREW);
    boolean hasHighAnsi = codePoints(run).anyMatch(c -> c > 0x7f
        && (Character.UnicodeScript.of(c) == Character.UnicodeScript.LATIN
            || Character.UnicodeScript.of(c) == Character.UnicodeScript.GREEK
            || Character.UnicodeScript.of(c) == Character.UnicodeScript.CYRILLIC));

    if ((hasHan && run.eastAsiaFont() == null) || (hasLatin && run.asciiFont() == null)
        || (hasComplex && run.complexFont() == null)) {
      return result(ruleId, expectedEastAsiaFont + "/" + expectedLatinFont, "unknown",
          "FONT_UNKNOWN", Judgement.REVIEW, run.source());
    }
    if ((hasHan && !matchesChineseFont(expectedEastAsiaFont, run.eastAsiaFont()))
        || (hasLatin && !expectedLatinFont.equals(run.asciiFont()))
        || (hasComplex && !expectedLatinFont.equals(run.complexFont()))
        || (hasHighAnsi && (run.highAnsiFont() == null || !expectedLatinFont.equals(run.highAnsiFont())))) {
      return result(ruleId, expectedEastAsiaFont + "/" + expectedLatinFont,
          "eastAsia=" + run.eastAsiaFont() + ", ascii=" + run.asciiFont()
              + ", hAnsi=" + run.highAnsiFont() + ", cs=" + run.complexFont(),
          "FONT_MISMATCH", Judgement.FAIL, run.source());
    }
    return result(ruleId, expectedEastAsiaFont + "/" + expectedLatinFont,
        run.eastAsiaFont() + "/" + run.asciiFont(), "", Judgement.PASS, run.source());
  }

  public CheckResult checkSize(RunModel run, RuleSet rules, String ruleId) {
    Objects.requireNonNull(run, "run");
    Objects.requireNonNull(rules, "rules");
    boolean complexScript = codePoints(run).mapToObj(Character.UnicodeScript::of)
        .anyMatch(script -> script == Character.UnicodeScript.ARABIC
            || script == Character.UnicodeScript.HEBREW);
    if (complexScript) {
      Integer complexSize = run.complexFontSizeHalfPoints();
      if (complexSize == null) {
        return result(ruleId, String.valueOf(rules.expectedSizeHalfPoints()), "szCs 未明确指定",
            "SIZE_UNKNOWN", Judgement.REVIEW, run.source());
      }
      String actual = "szCs=" + complexSize + " (sz=" + run.fontSizeHalfPoints() + ")";
      boolean matches = complexSize == rules.expectedSizeHalfPoints();
      return result(ruleId, String.valueOf(rules.expectedSizeHalfPoints()), actual,
          matches ? "" : "SIZE_MISMATCH", matches ? Judgement.PASS : Judgement.FAIL, run.source());
    }
    if (run.fontSizeHalfPoints() == null) {
      return result(ruleId, String.valueOf(rules.expectedSizeHalfPoints()), "unknown",
          "SIZE_UNKNOWN", Judgement.REVIEW, run.source());
    }
    boolean matches = run.fontSizeHalfPoints() == rules.expectedSizeHalfPoints();
    return result(ruleId, String.valueOf(rules.expectedSizeHalfPoints()),
        String.valueOf(run.fontSizeHalfPoints()), matches ? "" : "SIZE_MISMATCH",
        matches ? Judgement.PASS : Judgement.FAIL, run.source());
  }

  public CheckResult checkExpectedSize(RunModel run, int expectedHalfPoints, String ruleId) {
    Objects.requireNonNull(run, "run");
    boolean complexScript = codePoints(run).mapToObj(Character.UnicodeScript::of)
        .anyMatch(script -> script == Character.UnicodeScript.ARABIC
            || script == Character.UnicodeScript.HEBREW);
    if (complexScript) {
      Integer complexSize = run.complexFontSizeHalfPoints();
      if (complexSize == null) {
        return result(ruleId, String.valueOf(expectedHalfPoints), "szCs unknown",
            "SIZE_UNKNOWN", Judgement.REVIEW, run.source());
      }
      boolean matches = complexSize == expectedHalfPoints;
      return result(ruleId, String.valueOf(expectedHalfPoints),
          "szCs=" + complexSize + " (sz=" + run.fontSizeHalfPoints() + ")",
          matches ? "" : "SIZE_MISMATCH",
          matches ? Judgement.PASS : Judgement.FAIL, run.source());
    }
    if (run.fontSizeHalfPoints() == null) {
      return result(ruleId, String.valueOf(expectedHalfPoints), "unknown", "SIZE_UNKNOWN",
          Judgement.REVIEW, run.source());
    }
    boolean matches = run.fontSizeHalfPoints() == expectedHalfPoints;
    return result(ruleId, String.valueOf(expectedHalfPoints),
        String.valueOf(run.fontSizeHalfPoints()), matches ? "" : "SIZE_MISMATCH",
        matches ? Judgement.PASS : Judgement.FAIL, run.source());
  }

  public CheckResult checkIndent(ParagraphModel paragraph, RuleSet rules, String ruleId) {
    Objects.requireNonNull(paragraph, "paragraph");
    Objects.requireNonNull(rules, "rules");
    int leadingTextWhitespace = leadingTextWhitespaceCount(paragraph);
    if (paragraph.firstLineEvidenceInvalid() || paragraph.hangingEvidenceInvalid()) {
      return result(ruleId, "有效且不冲突的首行缩进", "冲突或无法读取",
          "INDENT_EVIDENCE_INVALID", Judgement.FAIL, paragraph.source());
    }
    int expectedTwips = expectedIndentTwips(paragraph, rules);
    if (leadingTextWhitespace > 0) {
      String actualIndent = paragraph.firstLineChars() != null
          ? paragraph.firstLineChars() + " chars"
              + (paragraph.firstLineIndentTwips() == null ? "" : " / " + paragraph.firstLineIndentTwips() + " twips")
          : paragraph.firstLineIndentTwips() != null
              ? paragraph.firstLineIndentTwips() + " twips" : "未设置首行缩进";
      boolean indentMatches = paragraph.firstLineChars() != null
          ? paragraph.firstLineChars() == rules.expectedFirstLineChars()
          : paragraph.firstLineIndentTwips() != null
              && paragraph.firstLineIndentTwips() == expectedTwips;
      return result(ruleId, rules.expectedFirstLineChars() + " chars / " + expectedTwips + " twips",
          actualIndent + "；文字前有 " + leadingTextWhitespace + " 个文字空白",
          indentMatches ? "INDENT_TEXT_WHITESPACE" : "INDENT_MISMATCH",
          Judgement.FAIL, paragraph.source());
    }
    if (paragraph.firstLineChars() == null) {
      if (paragraph.firstLineIndentTwips() == null) {
        return result(ruleId, expectedTwips + " twips", missingFirstLineActual(paragraph),
            "INDENT_MISMATCH", Judgement.FAIL,
            paragraph.source());
      }
      boolean matches = paragraph.firstLineIndentTwips() == expectedTwips;
      return result(ruleId, expectedTwips + " twips",
          indentActual(paragraph, paragraph.firstLineIndentTwips() + " twips"),
          matches ? "" : "INDENT_MISMATCH",
          matches ? Judgement.PASS : Judgement.FAIL, paragraph.source());
    }
    boolean matches = paragraph.firstLineChars() == rules.expectedFirstLineChars();
    return result(ruleId, rules.expectedFirstLineChars() + " chars / " + expectedTwips + " twips",
        indentActual(paragraph, paragraph.firstLineChars() + " chars"
            + (paragraph.firstLineIndentTwips() == null ? "" : " / " + paragraph.firstLineIndentTwips() + " twips")),
        matches ? "" : "INDENT_MISMATCH", matches ? Judgement.PASS : Judgement.FAIL,
        paragraph.source());
  }

  /**
   * Heading indentation uses the same OOXML evidence contract as body text.
   * Keeping this entry point in the shared formatter prevents semantic modules
   * from reimplementing conflict/unknown handling with weaker checks.
   */
  public CheckResult checkHeadingIndent(ParagraphModel paragraph, RuleSet rules, String ruleId) {
    return checkIndent(paragraph, rules, ruleId);
  }

  /**
   * Checks a semantic section whose first-line indentation differs from body
   * text.  The expected XML values are supplied by the owning module so a
   * directory entry can use the exact toc1/toc2/toc3 style evidence.
   */
  public CheckResult checkExactFirstLineIndent(ParagraphModel paragraph, Integer expectedChars,
      Integer expectedTwips, String ruleId) {
    Objects.requireNonNull(paragraph, "paragraph");
    if (paragraph.leftEvidenceInvalid()) {
      return result(ruleId, indentExpected(expectedChars, expectedTwips), "左缩进 XML 属性冲突或无法读取",
          "INDENT_EVIDENCE_INVALID", Judgement.FAIL, paragraph.source());
    }
    if (hasNonZero(paragraph.leftIndentChars()) || hasNonZero(paragraph.leftIndentTwips())
        || hasNonZero(paragraph.rightIndentTwips()) || hasNonZero(paragraph.hangingIndentChars())
        || hasNonZero(paragraph.hangingIndentTwips())) {
      return result(ruleId, indentExpected(expectedChars, expectedTwips),
          "存在额外的左缩进、右缩进或悬挂缩进", "INDENT_MISMATCH", Judgement.FAIL,
          paragraph.source());
    }
    int leadingTextWhitespace = leadingTextWhitespaceCount(paragraph);
    if (leadingTextWhitespace > 0) {
      return result(ruleId, indentExpected(expectedChars, expectedTwips),
          leadingTextWhitespace + " 个文字空白", "INDENT_MISMATCH", Judgement.FAIL, paragraph.source());
    }
    if (paragraph.firstLineEvidenceInvalid() || paragraph.hangingEvidenceInvalid()) {
      return result(ruleId, indentExpected(expectedChars, expectedTwips), "冲突或无法读取",
          "INDENT_EVIDENCE_INVALID", Judgement.FAIL, paragraph.source());
    }

    // A missing first-line attribute is the exact XML representation of zero
    // indentation.  For a non-zero contract, absence is unknown and fails.
    if ((expectedChars == null || expectedChars == 0)
        && (expectedTwips == null || expectedTwips == 0)
        && paragraph.firstLineChars() == null && paragraph.firstLineIndentTwips() == null) {
      return result(ruleId, indentExpected(expectedChars, expectedTwips), "0（XML 未写入首行缩进）",
          "", Judgement.PASS, paragraph.source());
    }
    if (expectedChars != null && paragraph.firstLineChars() != null) {
      // Character-based OOXML indentation is the exact contract for these
      // rules; the twip value is the compatibility representation.
      boolean matches = paragraph.firstLineChars().equals(expectedChars);
      return result(ruleId, indentExpected(expectedChars, expectedTwips),
          "firstLineChars=" + paragraph.firstLineChars()
              + (paragraph.firstLineIndentTwips() == null ? "" : ", firstLine=" + paragraph.firstLineIndentTwips()),
          matches ? "" : "INDENT_MISMATCH", matches ? Judgement.PASS : Judgement.FAIL,
          paragraph.source());
    }
    if (expectedTwips != null && paragraph.firstLineIndentTwips() != null) {
      boolean matches = paragraph.firstLineIndentTwips().equals(expectedTwips);
      return result(ruleId, indentExpected(expectedChars, expectedTwips),
          "firstLine=" + paragraph.firstLineIndentTwips(), matches ? "" : "INDENT_MISMATCH",
          matches ? Judgement.PASS : Judgement.FAIL, paragraph.source());
    }
    if ((expectedChars != null && expectedChars > 0)
        || (expectedTwips != null && expectedTwips > 0)) {
      return result(ruleId, indentExpected(expectedChars, expectedTwips), "0",
          "INDENT_MISMATCH", Judgement.FAIL, paragraph.source());
    }
    return result(ruleId, indentExpected(expectedChars, expectedTwips), "unknown",
        "INDENT_UNKNOWN", Judgement.REVIEW, paragraph.source());
  }

  private String indentExpected(Integer expectedChars, Integer expectedTwips) {
    if (expectedChars != null && expectedTwips != null)
      return "firstLineChars=" + expectedChars + " / firstLine=" + expectedTwips + " twips";
    if (expectedChars != null) return "firstLineChars=" + expectedChars;
    return expectedTwips == null ? "无首行缩进" : "firstLine=" + expectedTwips + " twips";
  }

  private boolean hasNonZero(Integer value) {
    return value != null && value != 0;
  }

  public CheckResult checkLineSpacing(String ruleId, ParagraphModel paragraph) {
    Objects.requireNonNull(paragraph, "paragraph");
    if (paragraph.lineSpacingEvidenceInvalid()) {
      return result(ruleId, "360 / auto", "显式行距数值超出支持范围或无法解析",
          "LINE_SPACING_INVALID", Judgement.FAIL, paragraph.source());
    }
    if (paragraph.lineTwips() == null) {
      return result(ruleId, "360", "unknown", "LINE_SPACING_UNKNOWN", Judgement.REVIEW, paragraph.source());
    }
    String lineRule = paragraph.lineRule() == null ? "auto" : paragraph.lineRule();
    boolean matches = paragraph.lineTwips() == 360 && "auto".equalsIgnoreCase(lineRule);
    return result(ruleId, "360 / auto", paragraph.lineTwips() + " / " + lineRule,
        matches ? "" : "LINE_SPACING_MISMATCH", matches ? Judgement.PASS : Judgement.FAIL,
        paragraph.source());
  }

  private java.util.stream.IntStream codePoints(RunModel run) {
    return (run.text() == null ? "" : run.text()).codePoints();
  }

  static boolean matchesChineseFont(String expected, String actual) {
    if (expected == null || actual == null) return false;
    if (expected.equals(actual)) return true;
    return switch (expected) {
      case "宋体", "瀹嬩綋" -> actual.equalsIgnoreCase("SimSun") || actual.equalsIgnoreCase("NSimSun");
      case "黑体", "榛戜綋" -> actual.equalsIgnoreCase("SimHei");
      case "仿宋", "浠垮畫" -> actual.equalsIgnoreCase("FangSong") || actual.equalsIgnoreCase("FangSong_GB2312");
      default -> false;
    };
  }

  private int leadingTextWhitespaceCount(ParagraphModel paragraph) {
    int count = 0;
    for (RunModel run : paragraph.runs()) {
      String text = run.text() == null ? "" : run.text();
      for (int offset = 0; offset < text.length();) {
        int codePoint = text.codePointAt(offset);
        boolean whitespace = Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
            || codePoint == 0x200B || codePoint == 0x200C || codePoint == 0x200D
            || codePoint == 0x2060 || codePoint == 0xFEFF;
        if (!whitespace) return count;
        count++;
        offset += Character.charCount(codePoint);
      }
    }
    return count;
  }

  private int expectedIndentTwips(ParagraphModel paragraph, RuleSet rules) {
    Integer bodyFontSizeHalfPoints = paragraph.runs().stream()
        .filter(run -> run.text() != null && !run.text().isBlank())
        .filter(run -> !isRaisedOrLowered(run.verticalAlignment()))
        .map(RunModel::fontSizeHalfPoints)
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(null);
    if (bodyFontSizeHalfPoints == null) return rules.expectedFirstLineIndentTwips();

    // OOXML stores font size in half-points and character indentation in
    // hundredths of a character; one point is 20 twips.
    return (int) Math.round(bodyFontSizeHalfPoints * rules.expectedFirstLineChars() / 10.0);
  }

  private boolean isRaisedOrLowered(String verticalAlignment) {
    return "superscript".equalsIgnoreCase(verticalAlignment)
        || "subscript".equalsIgnoreCase(verticalAlignment);
  }

  private String indentActual(ParagraphModel paragraph, String firstLine) {
    String left = paragraph.leftIndentChars() != null ? "left=" + paragraph.leftIndentChars() + " chars"
        : paragraph.leftIndentTwips() != null ? "left=" + paragraph.leftIndentTwips() + " twips" : null;
    return left == null ? firstLine : firstLine + " (" + left + ")";
  }

  private String missingFirstLineActual(ParagraphModel paragraph) {
    if (paragraph.leftIndentTwips() != null && paragraph.leftIndentTwips() != 0)
      return "0；整体左缩进 " + paragraph.leftIndentTwips() + " twips";
    if (paragraph.leftIndentChars() != null && paragraph.leftIndentChars() != 0)
      return "0；整体左缩进 " + paragraph.leftIndentChars() + " chars";
    return "0";
  }

  private CheckResult result(String ruleId, String expected, String actual, String reason,
      Judgement judgement, cn.luwneshacha.thesis.document.SourceAddress source) {
    return new CheckResult(ruleId, Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        judgement, expected, actual, reason, source);
  }
}
