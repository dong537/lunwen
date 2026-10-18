package cn.luwneshacha.thesis.rules;

import cn.luwneshacha.thesis.document.ReferenceEntryModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ReferenceRuleChecker implements RuleModule {
  private static final Pattern TEXTUAL_REFERENCE_NUMBER = Pattern.compile("^\\s*\\[(\\d+)\\](.*)$", Pattern.DOTALL);
  private static final Pattern PAGE_RANGE_SPACING = Pattern.compile(
      "\\d+(?:[\\s\\p{Zs}]+[-－]|[-－][\\s\\p{Zs}]+)\\d+");

  @Override
  public List<CheckResult> check(cn.luwneshacha.thesis.document.ParsedDocument document, RuleSet rules) {
    return check(document.references());
  }

  List<CheckResult> check(List<ReferenceEntryModel> references) {
    var results = new ArrayList<CheckResult>();
    int expectedNumber = 1;
    for (ReferenceEntryModel reference : references) {
      String expected = String.valueOf(expectedNumber);
      String actual = reference.number();
      if (actual == null || actual.isBlank() || !actual.matches("[1-9][0-9]*")) {
        results.add(fail("REF-07", String.valueOf(expectedNumber), reference.number(),
            "REFERENCE_NUMBER_INVALID", reference.source()));
      } else if (!expected.equals(actual)) {
        results.add(fail("REF-07", String.valueOf(expectedNumber), reference.number(),
            "REFERENCE_NUMBER_SEQUENCE_MISMATCH", reference.source()));
      }
      checkNumberSpacing(results, reference);
      checkPageRangeSpacing(results, reference);
      checkFields(results, reference);
      // Advance by entry position even after malformed or skipped numbers.
      expectedNumber++;
    }
    return List.copyOf(results);
  }

  /** A bibliography label must be followed by exactly one ordinary U+0020 space. */
  private static void checkNumberSpacing(List<CheckResult> results, ReferenceEntryModel reference) {
    if (reference.number() == null || reference.number().isBlank() || reference.text() == null) return;
    if (reference.automaticNumbering()) {
      Integer suffixSpaces = reference.numberingSeparatorSpaces();
      Integer formatTrailingSpaces = reference.numberingFormatTrailingSpaces();
      if (suffixSpaces == null || formatTrailingSpaces == null) {
        results.add(new CheckResult("REF-07", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
            Judgement.REVIEW, "one ordinary half-width space after reference number",
            "automatic numbering separator cannot be confirmed from XML",
            "REFERENCE_NUMBER_SPACING_UNCONFIRMED", zeroWidthSource(reference.source())));
      } else if (suffixSpaces != 1 || formatTrailingSpaces != 0) {
        results.add(fail("REF-07", "one ordinary half-width space after reference number",
            "suffix=" + suffixSpaces + ", formatTrailing=" + formatTrailingSpaces,
            "REFERENCE_NUMBER_SPACING_MISMATCH", zeroWidthSource(reference.source())));
      }
      return;
    }
    Matcher matcher = TEXTUAL_REFERENCE_NUMBER.matcher(reference.text());
    if (!matcher.matches() || !reference.number().equals(matcher.group(1))) return;
    String separatorAndContent = matcher.group(2);
    if (!hasExactlyOneOrdinarySpace(separatorAndContent)) {
      results.add(fail("REF-07", "编号后一个普通半角空格", describeSpacing(separatorAndContent),
          "REFERENCE_NUMBER_SPACING_MISMATCH", spacingSource(reference, matcher, separatorAndContent)));
    }
  }

  private static SourceAddress spacingSource(ReferenceEntryModel reference, Matcher matcher,
      String separatorAndContent) {
    int whitespaceLength = 0;
    while (whitespaceLength < separatorAndContent.length()) {
      int codePoint = separatorAndContent.codePointAt(whitespaceLength);
      if (!isWhitespace(codePoint)) break;
      whitespaceLength += Character.charCount(codePoint);
    }

    int rangeStart = matcher.start(2);
    int rangeEnd = rangeStart;
    if (whitespaceLength > 0) {
      if (separatorAndContent.charAt(0) == ' ') rangeStart++;
      else rangeEnd = rangeStart;
      rangeEnd = matcher.start(2) + whitespaceLength;
    }
    return sourceRange(reference.source(), reference.source().start() + rangeStart,
        reference.source().start() + rangeEnd);
  }

  private static SourceAddress zeroWidthSource(SourceAddress source) {
    return sourceRange(source, source.start(), source.start());
  }

  private static SourceAddress sourceRange(SourceAddress source, int start, int end) {
    return new SourceAddress(source.partName(), source.elementPath(), start, end);
  }

  private static boolean hasExactlyOneOrdinarySpace(String value) {
    if (value == null || value.isEmpty() || value.charAt(0) != ' ') return false;
    if (value.length() == 1) return true;
    int next = value.codePointAt(1);
    return !isWhitespace(next);
  }

  private static boolean isWhitespace(int codePoint) {
    return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
        || codePoint == 0x200B || codePoint == 0x200C || codePoint == 0x200D
        || codePoint == 0x2060 || codePoint == 0xFEFF;
  }

  private static String describeSpacing(String value) {
    if (value == null || value.isEmpty()) return "编号后没有空格";
    int firstContent = 0;
    while (firstContent < value.length() && isWhitespace(value.codePointAt(firstContent))) {
      firstContent += Character.charCount(value.codePointAt(firstContent));
    }
    String separator = value.substring(0, firstContent);
    if (separator.isEmpty()) return "编号后没有空格";
    if (separator.codePoints().allMatch(codePoint -> codePoint == ' '))
      return "编号后连续 " + separator.length() + " 个普通半角空格";
    return "编号后空白字符不符合要求";
  }

  private static void checkFields(List<CheckResult> results, ReferenceEntryModel reference) {
    String category = reference.category();
    if (category == null || category.isBlank()) return;
    category = category.toUpperCase(Locale.ROOT);
    var missing = new ArrayList<String>();
    require(missing, "作者", reference.authorText());
    require(missing, "题名", reference.titleText());

    boolean online = category.endsWith("/OL");
    if (online) {
      require(missing, "网络地址", reference.urlText());
      if (reference.accessDateText() == null || !reference.accessDateText().matches("\\d{4}-\\d{2}-\\d{2}"))
        missing.add("访问日期");
      if (category.startsWith("J/")) {
        require(missing, "期刊来源", reference.sourceText());
        require(missing, "出版年份", reference.yearText());
        require(missing, "页码", reference.pagesText());
      }
    } else {
      require(missing, "出版来源", reference.sourceText());
      require(missing, "出版年份", reference.yearText());
      if (category.startsWith("J") || category.startsWith("N"))
        require(missing, "页码", reference.pagesText());
    }

    if (!missing.isEmpty())
      results.add(fail("REF-04", "文献类别对应字段完整", reference.text() + "；缺少" + String.join("、", missing),
          "REFERENCE_FIELDS_MISSING", reference.source()));
  }

  private static void checkPageRangeSpacing(List<CheckResult> results, ReferenceEntryModel reference) {
    String pages = reference.pagesText();
    if (pages == null) return;
    Matcher matcher = PAGE_RANGE_SPACING.matcher(pages);
    if (matcher.find()) {
      results.add(fail("REF-04", "页码范围连接号两侧不留空格",
          "页码范围写成“" + pages + "”", "REFERENCE_PAGE_RANGE_SPACING", reference.source()));
    }
  }

  private static void require(List<String> missing, String label, String value) {
    if (value == null || value.isBlank()) missing.add(label);
  }

  private static CheckResult fail(String ruleId, String expected, String actual, String reason, SourceAddress source) {
    return new CheckResult(ruleId, Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, expected, actual, reason, source);
  }
}
