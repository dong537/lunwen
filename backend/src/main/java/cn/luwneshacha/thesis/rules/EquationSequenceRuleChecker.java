package cn.luwneshacha.thesis.rules;

import cn.luwneshacha.thesis.document.EquationModel;
import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.SourceAddress;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Document-wide equation numbering and object-readability checks. */
final class EquationSequenceRuleChecker {
  private static final Pattern CHAPTER_NUMBER = Pattern.compile(
      "^第([0-9]+|[一二三四五六七八九十百千零〇]+)章");
  private final DocumentRegionClassifier regions = new DocumentRegionClassifier();

  List<CheckResult> check(ParsedDocument document) {
    var results = new ArrayList<CheckResult>();
    Set<String> equationNumbers = new HashSet<>();
    var nextEquationByChapter = new HashMap<BigInteger, BigInteger>();
    for (EquationModel equation : document.equations()) {
      if (equation.number() != null) {
        if (!equationNumbers.add(equation.number()))
          results.add(result("EQ-05", "unique", equation.number(), "EQUATION_NUMBER_DUPLICATE",
              Judgement.FAIL, equation.source()));
        Matcher sequence = Pattern.compile("(\\d+)-(\\d+)").matcher(equation.number());
        if (sequence.matches()) {
          BigInteger chapter = new BigInteger(sequence.group(1));
          BigInteger number = new BigInteger(sequence.group(2));
          int activeChapter = chapterAt(document, equation.source());
          BigInteger expected = nextEquationByChapter.getOrDefault(chapter, BigInteger.ONE);
          String expectedNumber = (activeChapter > 0 ? String.valueOf(activeChapter) : chapter.toString())
              + "-" + expected;
          if (!number.toString().equals(sequence.group(2)) || !number.equals(expected)
              || (activeChapter > 0 && !chapter.equals(BigInteger.valueOf(activeChapter))))
            results.add(result("EQ-05", expectedNumber, equation.number(),
                "EQUATION_NUMBER_SEQUENCE_MISMATCH", Judgement.FAIL, equation.source()));
          nextEquationByChapter.put(chapter, expected.max(number).add(BigInteger.ONE));
        }
      }
      if (equation.needsReview())
        results.add(result("EQ-06", "可读取的公式对象", equation.type(), "EQUATION_OBJECT_UNSUPPORTED",
            Judgement.REVIEW, equation.source()));
    }
    return List.copyOf(results);
  }

  private int chapterAt(ParsedDocument document, SourceAddress source) {
    int chapter = 0;
    for (ParagraphModel paragraph : document.paragraphs()) {
      if (regions.isInsideTable(paragraph, document.tables())
          || !isBeforeOrAt(paragraph.source(), source)) continue;
      String text = paragraphText(paragraph);
      if (regions.isNumberedChapterHeading(paragraph)) chapter = chapterNumber(text);
      else if (regions.isAutomaticNumberedChapter(paragraph)) {
        try {
          chapter = Integer.parseInt(paragraph.numberingLabel());
        } catch (NumberFormatException ignored) {
          // An invalid automatic chapter label provides no numeric chapter evidence.
        }
      }
    }
    return chapter;
  }

  private static int chapterNumber(String text) {
    Matcher matcher = CHAPTER_NUMBER.matcher(text);
    if (!matcher.find()) return 0;
    String number = matcher.group(1);
    try {
      return Integer.parseInt(number);
    } catch (NumberFormatException ignored) {
      // Continue with Chinese numerals.
    }
    int total = 0;
    int section = 0;
    int digit = 0;
    for (int index = 0; index < number.length(); index++) {
      char value = number.charAt(index);
      int numeric = value == '〇' ? 0 : "零一二三四五六七八九".indexOf(value);
      if (numeric >= 0) {
        digit = numeric;
        continue;
      }
      int unit = value == '十' ? 10 : value == '百' ? 100 : value == '千' ? 1000 : 0;
      if (unit == 0) return 0;
      section += (digit == 0 ? 1 : digit) * unit;
      digit = 0;
      if (unit >= 100) {
        total += section;
        section = 0;
      }
    }
    return total + section + digit;
  }

  private static boolean isBeforeOrAt(SourceAddress paragraph, SourceAddress target) {
    if (!paragraph.partName().equals(target.partName())) return false;
    List<Integer> left = paragraph.elementPath();
    List<Integer> right = target.elementPath();
    int common = Math.min(left.size(), right.size());
    for (int index = 0; index < common; index++) {
      if (!left.get(index).equals(right.get(index))) return left.get(index) < right.get(index);
    }
    return left.size() <= right.size();
  }

  private static String paragraphText(ParagraphModel paragraph) {
    return paragraph.runs().stream().map(run -> run.text() == null ? "" : run.text())
        .reduce("", String::concat).trim();
  }

  private static CheckResult result(String ruleId, String expected, String actual, String reason,
      Judgement judgement, SourceAddress source) {
    return new CheckResult(ruleId, Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        judgement, expected, actual, reason, source);
  }
}
