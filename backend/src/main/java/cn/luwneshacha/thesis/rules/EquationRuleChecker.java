package cn.luwneshacha.thesis.rules;

import cn.luwneshacha.thesis.document.EquationModel;
import cn.luwneshacha.thesis.document.EquationSymbolExtractor;
import cn.luwneshacha.thesis.document.EquationVariable;
import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.RunModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Object-level formula structure, numbering notation, alignment, and variable checks. */
final class EquationRuleChecker {
  private static final String NUMBER_BODY = "(?:\\d+-\\d+|[A-Z]\\d+)";
  private static final Pattern NUMBER = Pattern.compile("(?:\\(" + NUMBER_BODY + "\\)|（"
      + NUMBER_BODY + "）)");
  private static final Pattern NUMBER_WITH_ANY_HYPHEN = Pattern.compile(
      "(?:\\(\\d+[-－]\\d+\\)|（\\d+[-－]\\d+）|\\([A-Z]\\d+\\)|（[A-Z]\\d+）)");

  List<CheckResult> check(ParsedDocument document, EquationModel equation, RuleSet rules) {
    var results = new ArrayList<CheckResult>();
    if (equation.standalone()) {
      CheckResult numberCheck = checkNumberPresenceAndNotation(document, equation);
      if (numberCheck != null) results.add(numberCheck);
    }
    if (equation.standalone() && (equation.objectEvidence() == null
        || equation.objectEvidence().internalStructureReadable())) {
      results.add(checkVariables(document, equation));
    }
    if (!equation.standalone() || equation.number() == null) return List.copyOf(results);
    results.add(checkNumberFormat(document, equation));
    results.add(checkAlignment(document, equation));
    return List.copyOf(results);
  }

  private CheckResult checkNumberPresenceAndNotation(ParsedDocument document, EquationModel equation) {
    String displayed = equation.numberEvidence() == null ? null : equation.numberEvidence().text();
    SourceAddress source = equation.numberEvidence() == null ? equation.numberSource()
        : equation.numberEvidence().source();
    if (displayed == null && source != null) displayed = sourceText(document, source);
    if (displayed == null && equation.number() != null) {
      displayed = findDisplayedNumber(document, equation);
      if (displayed == null) displayed = equation.number();
    }
    if (displayed == null || displayed.isBlank()) {
      if (equation.number() == null)
        return result("EQ-01", "公式号（如 3-1）", null, "EQUATION_NUMBER_MISSING",
            Judgement.FAIL, equation.source());
      return null;
    }
    String value = displayed.strip();
    if (NUMBER_WITH_ANY_HYPHEN.matcher(value).matches() && value.indexOf('－') >= 0)
      return result("EQ-01", "公式号使用半角连字符，例如 3-1", value,
          "EQUATION_NUMBER_PATTERN_MISMATCH", Judgement.FAIL,
          source == null ? equation.source() : source);
    if (!NUMBER.matcher(value).matches())
      return result("EQ-01", "公式编号用成对圆括号括起，例如 (3-1)", value,
          "EQUATION_NUMBER_PARENTHESES_MISSING", Judgement.FAIL,
          source == null ? equation.source() : source);
    return null;
  }

  private String findDisplayedNumber(ParsedDocument document, EquationModel equation) {
    ParagraphModel paragraph = findEquationParagraph(document, equation.source());
    if (paragraph == null) return null;
    String text = paragraphText(paragraph);
    Matcher match = NUMBER_WITH_ANY_HYPHEN.matcher(text);
    if (match.find()) return match.group();
    Pattern unparenthesized = Pattern.compile("(?:\\d+[-－]\\d+|[A-Z]\\d+)");
    match = unparenthesized.matcher(text);
    return match.find() ? match.group() : null;
  }

  private ParagraphModel findEquationParagraph(ParsedDocument document, SourceAddress source) {
    return document.paragraphs().stream()
        .filter(candidate -> candidate.source().partName().equals(source.partName())
            && isAncestorPath(candidate.source().elementPath(), source.elementPath()))
        .max(Comparator.comparingInt(candidate -> candidate.source().elementPath().size()))
        .orElse(null);
  }

  private CheckResult checkNumberFormat(ParsedDocument document, EquationModel equation) {
    if (equation.numberSource() != null) {
      String text = sourceText(document, equation.numberSource());
      if (text != null && !text.strip().matches("[（(](?:\\d+-\\d+|[A-Z]\\d+)[）)]"))
        return result("EQ-02", "括号内的公式号使用五号 Times New Roman", text,
            "EQUATION_NUMBER_FORMAT_MISMATCH", Judgement.FAIL, equation.numberSource());
    }
    if (equation.numberEvidence() != null) {
      var evidence = equation.numberEvidence();
      boolean mismatch = (evidence.fontName() != null
              && !"Times New Roman".equals(evidence.fontName()))
          || (evidence.fontSizeHalfPoints() != null && evidence.fontSizeHalfPoints() != 21);
      boolean unknown = evidence.fontName() == null || evidence.fontSizeHalfPoints() == null;
      boolean matches = "Times New Roman".equals(evidence.fontName())
          && evidence.fontSizeHalfPoints() != null && evidence.fontSizeHalfPoints() == 21;
      String actual = (evidence.fontName() == null ? "字体未明确指定" : evidence.fontName()) + " / "
          + (evidence.fontSizeHalfPoints() == null ? "未明确指定字号" : evidence.fontSizeHalfPoints());
      return result("EQ-02", "五号 / Times New Roman", actual,
          matches ? "" : (mismatch ? "EQUATION_NUMBER_FORMAT_MISMATCH" : "EQUATION_NUMBER_FORMAT_UNKNOWN"),
          matches ? Judgement.PASS : (mismatch ? Judgement.FAIL : Judgement.REVIEW), evidence.source());
    }
    SourceAddress anchor = equation.numberSource() == null ? equation.source() : equation.numberSource();
    ParagraphModel paragraph = document.paragraphs().stream()
        .filter(candidate -> candidate.source().partName().equals(anchor.partName())
            && isAncestorPath(candidate.source().elementPath(), anchor.elementPath()))
        .max(Comparator.comparingInt(candidate -> candidate.source().elementPath().size())).orElse(null);
    if (paragraph == null) return review("EQ-02", "五号 / Times New Roman",
        "EQUATION_NUMBER_FORMAT_UNKNOWN", equation.source());
    String text = paragraph.runs().stream().map(RunModel::text).reduce("", String::concat);
    Matcher matcher = Pattern.compile("(?:\\(" + Pattern.quote(equation.number()) + "\\)|（"
        + Pattern.quote(equation.number()) + "）)").matcher(text);
    if (!matcher.find()) return review("EQ-02", "五号 / Times New Roman",
        "EQUATION_NUMBER_FORMAT_UNKNOWN", equation.source());
    int offset = 0;
    SourceAddress unknown = null;
    boolean found = false;
    for (RunModel run : paragraph.runs()) {
      int start = offset;
      int end = start + run.text().length();
      offset = end;
      if (end <= matcher.start() || start >= matcher.end()) continue;
      found = true;
      if ((run.asciiFont() != null && !"Times New Roman".equals(run.asciiFont()))
          || (run.fontSizeHalfPoints() != null && run.fontSizeHalfPoints() != 21))
        return result("EQ-02", "五号 / Times New Roman",
            run.asciiFont() + " / " + run.fontSizeHalfPoints(),
            "EQUATION_NUMBER_FORMAT_MISMATCH", Judgement.FAIL, run.source());
      if (run.asciiFont() == null || run.fontSizeHalfPoints() == null) unknown = run.source();
    }
    if (unknown != null) return review("EQ-02", "五号 / Times New Roman",
        "EQUATION_NUMBER_FORMAT_UNKNOWN", unknown);
    return found ? result("EQ-02", "五号 / Times New Roman", "Times New Roman / 21", "",
        Judgement.PASS, equation.source()) : review("EQ-02", "五号 / Times New Roman",
        "EQUATION_NUMBER_FORMAT_UNKNOWN", equation.source());
  }

  private CheckResult checkAlignment(ParsedDocument document, EquationModel equation) {
    ParagraphModel paragraph = document.paragraphs().stream()
        .filter(candidate -> isAncestorPath(candidate.source().elementPath(), equation.source().elementPath()))
        .max(Comparator.comparingInt(candidate -> candidate.source().elementPath().size())).orElse(null);
    Boolean centered = equation.layoutEvidence() == null ? null : equation.layoutEvidence().formulaCentered();
    if (centered == null && paragraph != null && paragraph.alignment() != null)
      centered = "center".equalsIgnoreCase(paragraph.alignment());
    if (centered == null || (equation.numberSource() == null && equation.numberEvidence() == null))
      return result("EQ-03", "公式居中；公式号右对齐", "未读取到完整对齐证据",
          "EQUATION_ALIGNMENT_UNKNOWN",
          paragraph == null || paragraph.alignment() == null ? Judgement.FAIL : Judgement.REVIEW,
          equation.source());
    Boolean right = equation.layoutEvidence() == null ? null : equation.layoutEvidence().numberRightAligned();
    if (right == null) right = equation.numberRightAligned();
    if (right == null && equation.numberSource() != null) {
      ParagraphModel numberParagraph = document.paragraphs().stream()
          .filter(candidate -> isAncestorPath(candidate.source().elementPath(), equation.numberSource().elementPath()))
          .max(Comparator.comparingInt(candidate -> candidate.source().elementPath().size())).orElse(null);
      if (numberParagraph != null && numberParagraph.alignment() != null)
        right = "right".equalsIgnoreCase(numberParagraph.alignment());
    }
    if (right == null) return result("EQ-03", "公式居中；公式号右对齐", "未读取到公式号的右对齐证据",
        "EQUATION_ALIGNMENT_UNKNOWN", Judgement.REVIEW, equation.source());
    boolean pass = centered && right;
    return result("EQ-03", "公式居中；公式号右对齐", "公式居中=" + centered + " / 公式号右对齐=" + right,
        pass ? "" : "EQUATION_ALIGNMENT_MISMATCH", pass ? Judgement.PASS : Judgement.FAIL, equation.source());
  }

  private CheckResult checkVariables(ParsedDocument document, EquationModel equation) {
    var variables = new TreeSet<String>();
    var structured = new HashMap<String, EquationVariable>();
    if (!equation.structuredVariables().isEmpty()) {
      for (EquationVariable variable : equation.structuredVariables()) {
        variables.add(variable.identifier());
        structured.put(variable.identifier(), variable);
      }
    } else variables.addAll(equation.variables());
    if (variables.isEmpty() && !equation.variableSymbolsResolved())
      EquationSymbolExtractor.addIdentifiers(equation.summary(), variables);
    if (variables.isEmpty() && !equation.variableSymbolsResolved())
      return result("EQ-07", "公式中的变量均有说明", "无法从公式结构中确定变量",
          "EQUATION_VARIABLES_UNREADABLE", Judgement.REVIEW, equation.source());
    if (variables.isEmpty()) return result("EQ-07", "变量含义和单位说明", "公式中没有可识别变量", "",
        Judgement.PASS, equation.source());
    String explanation = equation.variableExplanation();
    SourceAddress explanationSource = equation.source();
    if (explanation == null) {
      int index = equationParagraphIndex(document, equation);
      if (index >= 0 && index + 1 < document.paragraphs().size()) {
        ParagraphModel next = document.paragraphs().get(index + 1);
        String text = paragraphText(next).strip();
        if (text.matches("^(式中|其中)\\s*[：:].*")) {
          explanation = text;
          explanationSource = next.source();
        }
      }
    }
    if (explanation == null) return result("EQ-07", "变量含义和单位说明", String.join("、", variables),
        "EQUATION_VARIABLES_MISSING", Judgement.FAIL, equation.source());
    var missing = new ArrayList<String>();
    for (String variable : variables)
      if (!hasVariableDefinition(explanation, variable, structured.get(variable))) missing.add(variable);
    return result("EQ-07", "变量含义和单位说明", String.join("、", missing.isEmpty() ? variables : missing),
        missing.isEmpty() ? "" : "EQUATION_VARIABLES_MISSING",
        missing.isEmpty() ? Judgement.PASS : Judgement.FAIL, explanationSource);
  }

  private static boolean hasVariableDefinition(String explanation, String identifier, EquationVariable variable) {
    var candidates = new LinkedHashSet<String>();
    candidates.add(identifier);
    if (variable != null) {
      String base = variable.base(), sub = variable.subscript(), sup = variable.superscript();
      if (!sub.isBlank() && !sup.isBlank()) {
        candidates.add(base + "_" + sub + "^" + sup);
        candidates.add(base + sub + sup);
        candidates.add(base + toSubscript(sub) + toSuperscript(sup));
      } else if (!sub.isBlank()) {
        candidates.add(base + "_" + sub); candidates.add(base + sub); candidates.add(base + toSubscript(sub));
      } else if (!sup.isBlank()) {
        candidates.add(base + "^" + sup); candidates.add(base + sup); candidates.add(base + toSuperscript(sup));
      }
    }
    String alternatives = candidates.stream().filter(candidate -> candidate != null && !candidate.isBlank())
        .map(Pattern::quote).reduce((left, right) -> left + "|" + right).orElse(Pattern.quote(identifier));
    return Pattern.compile("(?<![\\p{L}&&[^\\p{IsHan}]])(?<![\\p{N}\\p{M}_^])(?:" + alternatives
        + ")\\s*(?:为|是|表示|代表|指|的含义是|单位为|[：:])").matcher(explanation).find();
  }

  private static String toSubscript(String value) {
    return value.replace('0', '₀').replace('1', '₁').replace('2', '₂').replace('3', '₃').replace('4', '₄')
        .replace('5', '₅').replace('6', '₆').replace('7', '₇').replace('8', '₈').replace('9', '₉')
        .replace('i', 'ᵢ').replace('j', 'ⱼ');
  }
  private static String toSuperscript(String value) {
    return value.replace('0', '⁰').replace('1', '¹').replace('2', '²').replace('3', '³').replace('4', '⁴')
        .replace('5', '⁵').replace('6', '⁶').replace('7', '⁷').replace('8', '⁸').replace('9', '⁹')
        .replace('+', '⁺').replace('-', '⁻');
  }
  private static int equationParagraphIndex(ParsedDocument document, EquationModel equation) {
    for (int index = 0; index < document.paragraphs().size(); index++) {
      List<Integer> paragraph = document.paragraphs().get(index).source().elementPath();
      List<Integer> source = equation.source().elementPath();
      if (paragraph.size() <= source.size() && source.subList(0, paragraph.size()).equals(paragraph)) return index;
    }
    return -1;
  }
  private static String sourceText(ParsedDocument document, SourceAddress source) {
    ParagraphModel paragraph = document.paragraphs().stream().filter(candidate ->
        candidate.source().partName().equals(source.partName())
            && candidate.source().elementPath().equals(source.elementPath())).findFirst().orElse(null);
    if (paragraph == null) return null;
    String text = paragraph.runs().stream().map(RunModel::text).reduce("", String::concat);
    return source.start() >= 0 && source.end() >= source.start() && source.end() <= text.length()
        ? text.substring(source.start(), source.end()) : null;
  }
  private static boolean isAncestorPath(List<Integer> ancestor, List<Integer> descendant) {
    return ancestor.size() <= descendant.size() && descendant.subList(0, ancestor.size()).equals(ancestor);
  }
  private static String paragraphText(ParagraphModel paragraph) {
    return paragraph.runs().stream().map(RunModel::text).reduce("", String::concat);
  }
  private static CheckResult review(String ruleId, String expected, String reason, SourceAddress source) {
    return result(ruleId, expected, null, reason, Judgement.REVIEW, source);
  }
  private static CheckResult result(String ruleId, String expected, String actual, String reason,
      Judgement judgement, SourceAddress source) {
    return new CheckResult(ruleId, Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        judgement, expected, actual, reason, source);
  }
}
