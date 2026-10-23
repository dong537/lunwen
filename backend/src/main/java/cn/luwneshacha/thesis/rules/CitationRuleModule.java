package cn.luwneshacha.thesis.rules;

import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.ReferenceEntryModel;
import cn.luwneshacha.thesis.document.RunModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Owns body citation typography and the citation-to-reference relationship. */
final class CitationRuleModule implements RuleModule {
  private static final Pattern CITATION = Pattern.compile("\\[([^\\]]*)\\]");
  private static final Pattern NUMBER = Pattern.compile("\\d+");
  private static final Pattern TOC_ENTRY = Pattern.compile(".+(?:\\.{2,}|……+|\\t|[\\p{Zs}]{2,})\\s*\\d+$");
  private static final Pattern CAPTION = Pattern.compile("^(续表|表|图)\\s*[0-9一二三四五六七八九十]+", Pattern.CASE_INSENSITIVE);
  private static final Pattern FIGURE_OR_TABLE = Pattern.compile("(?:图|表)\\s*[A-Z]?\\d+(?:[-－]\\d+)?");

  private final HeadingRuleChecker headingChecker;
  private final DocumentRegionClassifier regions = new DocumentRegionClassifier();

  CitationRuleModule(HeadingRuleChecker headingChecker) {
    this.headingChecker = headingChecker;
  }

  @Override
  public List<CheckResult> check(ParsedDocument document, RuleSet rules) {
    var results = new ArrayList<CheckResult>();
    var firstCitationSources = new LinkedHashMap<String, SourceAddress>();
    var validCitationNumbers = new LinkedHashSet<String>();
    var unresolved = new LinkedHashSet<SourceAddress>();
    boolean inMainBody = false;
    for (ParagraphModel paragraph : document.paragraphs()) {
      if (paragraph.insideTextBox()) continue;
      String text = paragraphText(paragraph);
      boolean insideTable = regions.isInsideTable(paragraph, document.tables());
      if (!insideTable && regions.isBodyChapterStart(paragraph, document)) inMainBody = true;
      if (!insideTable && inMainBody && regions.isEndMatterHeading(paragraph, document)) inMainBody = false;
      if (!inMainBody || isVisualBlank(paragraph) || insideTable
          || isTocEntry(paragraph, text) || isHeading(paragraph) || CAPTION.matcher(text).find()
          || isCodeParagraph(paragraph)) continue;
      checkCitationFormatting(results, paragraph, firstCitationSources, validCitationNumbers, unresolved);
    }
    checkReferenceCitations(results, document, firstCitationSources, validCitationNumbers, unresolved);
    return List.copyOf(results);
  }

  private void checkCitationFormatting(List<CheckResult> results, ParagraphModel paragraph,
      Map<String, SourceAddress> firstCitationSources, Set<String> validCitationNumbers,
      Set<SourceAddress> unresolved) {
    String text = rawText(paragraph);
    Matcher matcher = CITATION.matcher(text);
    boolean found = false;
    boolean failed = false;
    boolean unknown = false;
    boolean wrongPosition = false;
    while (matcher.find()) {
      String group = matcher.group(1);
      if (!group.matches("(?s)[\\s\\p{Zs}\\u200B\\u200C\\u200D\\u2060\\uFEFF]*\\d.*")) continue;
      found = true;
      if (!citationAtSentenceEnd(text, matcher.end())) wrongPosition = true;
      // Keep the intended reference numbers even when the separator is invalid.
      // This lets the relationship check report the separator problem once
      // instead of cascading into false "reference not cited" findings.
      boolean valid = group.matches("\\d+(?:[-–,，、]\\d+)*") && addCitationNumbers(group, paragraph.source(), firstCitationSources);
      if (!valid) {
        recordIntendedCitationNumbers(group, paragraph.source(), firstCitationSources);
        unresolved.add(paragraph.source());
        failed = true;
      } else {
        recordValidCitationNumbers(group, validCitationNumbers);
      }
      int offset = 0;
      for (RunModel run : paragraph.runs()) {
        int end = offset + run.text().length();
        if (offset < matcher.end() && end > matcher.start()) {
          if (!"superscript".equals(run.verticalAlignment())) failed = true;
          if (run.asciiFont() == null || run.fontSizeHalfPoints() == null) unknown = true;
          else if (!"Times New Roman".equals(run.asciiFont()) || run.fontSizeHalfPoints() != 24) failed = true;
        }
        offset = end;
      }
    }
    if (!found) return;
    if (wrongPosition) results.add(result("CITE-01", "句末标注", "引用后仍有正文内容",
        "CITATION_POSITION_MISMATCH", Judgement.FAIL, paragraph.source()));
    if (failed) results.add(result("CITE-01", "小四号 Times New Roman / 上标", "format mismatch",
        "CITATION_FORMAT_MISMATCH", Judgement.FAIL, paragraph.source()));
    else if (unknown) results.add(result("CITE-01", "小四号 Times New Roman / 上标", null,
        "CITATION_FORMAT_UNKNOWN", Judgement.REVIEW, paragraph.source()));
    else results.add(result("CITE-01", "小四号 Times New Roman / 上标", "matched", "",
        Judgement.PASS, paragraph.source()));
  }

  private void checkReferenceCitations(List<CheckResult> results, ParsedDocument document,
      Map<String, SourceAddress> firstCitationSources, Set<String> validCitationNumbers,
      Set<SourceAddress> unresolved) {
    for (SourceAddress source : unresolved)
      results.add(result("REF-03", "valid citation number separators", "invalid",
          "CITATION_RANGE_MISMATCH", Judgement.FAIL, source));
    var references = new LinkedHashMap<String, ReferenceEntryModel>();
    for (ReferenceEntryModel reference : document.references()) {
      if (reference.number() != null && !reference.number().isBlank())
        references.putIfAbsent(reference.number(), reference);
    }
    var citedNumbers = new ArrayList<>(firstCitationSources.keySet());
    for (String number : citedNumbers) {
      if (!references.containsKey(number)) {
        boolean range = hasRangeAt(document, firstCitationSources.get(number));
        results.add(result("REF-03", "matching reference entry", number,
            range ? "CITATION_RANGE_MISMATCH" : "CITED_REFERENCE_MISSING", Judgement.FAIL,
            firstCitationSources.get(number)));
      }
    }
    int index = 0;
    for (ReferenceEntryModel reference : document.references()) {
      String number = reference.number();
      if (number == null || number.isBlank()) continue;
      // A malformed group is enough to establish the user's intent when the
      // document has no valid citation at all. Once any valid group exists,
      // numbers that appear only in malformed groups remain uncited and are
      // reported independently.
      boolean isCited = firstCitationSources.containsKey(number)
          && (validCitationNumbers.isEmpty() || validCitationNumbers.contains(number));
      if (!isCited)
        results.add(result("REF-03", "cited in body", number, "REFERENCE_NOT_CITED", Judgement.FAIL, reference.source()));
      if (index < citedNumbers.size() && !number.equals(citedNumbers.get(index)))
        results.add(result("REF-03", citedNumbers.get(index), number, "REFERENCE_ORDER_MISMATCH", Judgement.FAIL, reference.source()));
      index++;
    }
  }

  private boolean addCitationNumbers(String group, SourceAddress source, Map<String, SourceAddress> first) {
    Matcher matcher = NUMBER.matcher(group);
    BigInteger previous = null;
    int previousEnd = 0;
    while (matcher.find()) {
      BigInteger current;
      try { current = new BigInteger(matcher.group()); }
      catch (NumberFormatException error) { return false; }
      if (previous == null) first.putIfAbsent(matcher.group(), source);
      else {
        String separator = group.substring(previousEnd, matcher.start());
        if (separator.contains("-") || separator.contains("–")) {
          BigInteger distance = current.subtract(previous);
          if (distance.signum() < 0 || distance.compareTo(BigInteger.valueOf(1000)) > 0) return false;
          for (BigInteger value = previous.add(BigInteger.ONE); value.compareTo(current) <= 0; value = value.add(BigInteger.ONE))
            first.putIfAbsent(value.toString(), source);
        } else {
          if (!separator.matches("\\s*[,，、]\\s*")) return false;
          first.putIfAbsent(matcher.group(), source);
        }
      }
      previous = current;
      previousEnd = matcher.end();
    }
    return previous != null && group.substring(previousEnd).isBlank();
  }

  private static void recordIntendedCitationNumbers(String group, SourceAddress source,
      Map<String, SourceAddress> first) {
    Matcher matcher = NUMBER.matcher(group);
    while (matcher.find()) {
      try {
        first.putIfAbsent(new BigInteger(matcher.group()).toString(), source);
      } catch (NumberFormatException ignored) {
        // The normal validation path reports malformed numeric tokens.
      }
    }
  }

  private static void recordValidCitationNumbers(String group, Set<String> valid) {
    Matcher matcher = NUMBER.matcher(group);
    BigInteger previous = null;
    int previousEnd = 0;
    while (matcher.find()) {
      try {
        BigInteger current = new BigInteger(matcher.group());
        if (previous == null) {
          valid.add(current.toString());
        } else {
          String separator = group.substring(previousEnd, matcher.start());
          if (separator.contains("-") || separator.contains("–")) {
            BigInteger distance = current.subtract(previous);
            if (distance.signum() >= 0 && distance.compareTo(BigInteger.valueOf(1000)) <= 0) {
              for (BigInteger value = previous; value.compareTo(current) <= 0; value = value.add(BigInteger.ONE))
                valid.add(value.toString());
            }
          } else {
            valid.add(current.toString());
          }
        }
        previous = current;
        previousEnd = matcher.end();
      } catch (NumberFormatException ignored) { return; }
    }
  }

  private static boolean citationAtSentenceEnd(String text, int end) {
    int next = end;
    while (next < text.length() && Character.isWhitespace(text.charAt(next))) next++;
    return next == text.length() || text.charAt(next) == '[' || "。！？!?；;,.，".indexOf(text.charAt(next)) >= 0;
  }

  private static boolean hasRangeAt(ParsedDocument document, SourceAddress source) {
    return document.paragraphs().stream().filter(p -> p.source().equals(source)).map(CitationRuleModule::rawText)
        .anyMatch(text -> Pattern.compile("\\[\\d+[-–]\\d+\\]").matcher(text).find());
  }

  private boolean isHeading(ParagraphModel paragraph) { return headingChecker.headingLevel(paragraph) > 0; }

  private static boolean isCodeParagraph(ParagraphModel paragraph) {
    String text = paragraphText(paragraph).strip();
    if (text.isEmpty()) return false;
    if (text.startsWith("//") || text.startsWith("/*") || text.startsWith("*")
        || text.startsWith(".")) return true;
    if (text.matches("(?s)^(if|for|while|return|throw|try|catch|else|switch|case)\\b.*[;{}].*$"))
      return true;
    return text.matches("(?s)^[\\w<>?,.\\[\\]\\s]+\\s+[A-Za-z_$][\\w$]*\\s*=.*;.*$")
        || text.matches("(?s)^[A-Za-z_$][\\w$]*\\s*(?:[+\\-*/]?=).*;.*$")
        || text.matches("(?s)^[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)+\\s*\\(.*;.*$");
  }

  private static boolean isTocEntry(ParagraphModel paragraph, String text) {
    return paragraph.generatedTocField() || TOC_ENTRY.matcher(text).matches();
  }

  private static boolean isVisualBlank(ParagraphModel paragraph) {
    return rawText(paragraph).codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c)
        || c == 0x200B || c == 0x200C || c == 0x200D || c == 0x2060 || c == 0xFEFF);
  }

  private static String rawText(ParagraphModel paragraph) { return paragraph.runs().stream().map(RunModel::text).reduce("", String::concat); }
  private static String paragraphText(ParagraphModel paragraph) { return rawText(paragraph).trim(); }

  private static CheckResult result(String ruleId, String expected, String actual, String reason,
      Judgement judgement, SourceAddress source) {
    return new CheckResult(ruleId, Applicability.APPLICABLE, ExecutionStatus.EXECUTED, judgement, expected, actual, reason, source);
  }
}
