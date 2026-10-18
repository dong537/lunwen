package cn.luwneshacha.thesis.rules;

import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.EquationModel;
import cn.luwneshacha.thesis.document.HeadingBreakModel;
import cn.luwneshacha.thesis.document.ImageModel;
import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import cn.luwneshacha.thesis.document.TableModel;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class AppendixRuleModule implements RuleModule {
  private final DocumentRegionClassifier regions = new DocumentRegionClassifier();

  @Override
  public List<CheckResult> check(ParsedDocument document, RuleSet rules) {
    var findings = new ArrayList<CheckResult>();
    checkAppendixTitleBlankLine(findings, document);
    checkAppendixPageBreak(findings, document);
    checkAppendixSequence(findings, document);
    checkAppendixObjects(findings, document);
    return List.copyOf(findings);
  }

  /**
   * The appendix total heading must start a new page.  A positive XML signal
   * is authoritative; when no signal is present the final Word pagination is
   * unknowable from static XML, so keep the result as REVIEW.
   */
  private void checkAppendixPageBreak(List<CheckResult> findings, ParsedDocument document) {
    for (ParagraphModel heading : document.paragraphs()) {
      if (regions.isInsideTable(heading, document.tables()) || !isMainAppendixTitle(heading)) continue;
      HeadingBreakModel evidence = document.chapterBreaks().stream()
          .filter(candidate -> sameParagraph(candidate.source(), heading.source()))
          .findFirst().orElse(null);
      // Parsed DOCX documents always emit an evidence record for the main
      // appendix heading.  Synthetic ParsedDocument fixtures may omit it;
      // avoid inventing a second finding when no source evidence exists.
      if (evidence == null) continue;
      if (evidence.explicitBreak()) continue;
      String actual = "XML 中未发现附录标题前分页属性";
      findings.add(result("APP-03", "附录总标题另起一页", actual,
          "APPENDIX_PAGE_BREAK_REVIEW", Judgement.REVIEW, heading.source()));
    }
  }

  private boolean isMainAppendixTitle(ParagraphModel paragraph) {
    String normalized = compact(text(paragraph));
    return "附录".equals(normalized) || "APPENDIX".equalsIgnoreCase(normalized);
  }

  private boolean sameParagraph(SourceAddress left, SourceAddress right) {
    return left != null && right != null && left.partName().equals(right.partName())
        && left.elementPath().equals(right.elementPath());
  }

  /** The main "附    录" title is followed by exactly one visual blank paragraph. */
  private void checkAppendixTitleBlankLine(List<CheckResult> findings, ParsedDocument document) {
    List<ParagraphModel> paragraphs = document.paragraphs();
    for (int index = 0; index < paragraphs.size(); index++) {
      ParagraphModel heading = paragraphs.get(index);
      if (regions.isInsideTable(heading, document.tables())) continue;
      String normalized = compact(text(heading));
      if (!"附录".equals(normalized) && !"APPENDIX".equalsIgnoreCase(normalized)) continue;

      int next = index + 1;
      int blankLines = ManualLineBreakSupport.blankLinesAfter(heading);
      while (next < paragraphs.size() && !regions.isInsideTable(paragraphs.get(next), document.tables())
          && isVisualBlankParagraph(paragraphs.get(next))) {
        blankLines += ManualLineBreakSupport.visualBlankLines(paragraphs.get(next));
        next++;
      }
      if (blankLines == 1) continue;

      boolean missing = blankLines == 0;
      if (missing && next >= paragraphs.size()) {
        findings.add(result("APP-02-BLANK", "空一行", "文档末尾无下一段可确认",
            "APPENDIX_TITLE_BLANK_LINE_MISSING", Judgement.REVIEW, heading.source()));
        continue;
      }
      SourceAddress source = missing ? paragraphs.get(next).source() : heading.source();
      findings.add(result("APP-02-BLANK", "空一行", missing ? "未空一行" : String.valueOf(blankLines),
          missing ? "APPENDIX_TITLE_BLANK_LINE_MISSING"
              : "APPENDIX_TITLE_BLANK_LINE_COUNT_MISMATCH",
          Judgement.FAIL, source));
    }
  }

  private void checkAppendixSequence(List<CheckResult> findings, ParsedDocument document) {
    char expectedHeading = 'A';
    for (ParagraphModel paragraph : document.paragraphs()) {
      String actual = regions.appendixHeadingLetter(paragraph, document);
      if (actual == null) continue;
      boolean matches = expectedHeading == actual.charAt(0);
      findings.add(result("APP-01", String.valueOf(expectedHeading), actual,
          matches ? "" : "APPENDIX_SEQUENCE_MISMATCH",
          matches ? Judgement.PASS : Judgement.FAIL, paragraph.source()));
      expectedHeading++;
    }
  }

  private void checkAppendixObjects(List<CheckResult> findings, ParsedDocument document) {
    Map<String, BigInteger> nextByTypeAndAppendix = new HashMap<>();
    for (TableModel table : document.tables()) {
      String appendix = regions.appendixLetterAt(document,
          table.captionSource() == null ? table.source() : table.captionSource());
      if (appendix == null) continue;
      SourceAddress source = table.captionSource() == null ? table.source() : table.captionSource();
      findings.add(checkAppendixObjectNumber("表", appendix, table.captionText(), source));
      checkAppendixObjectSequence(findings, nextByTypeAndAppendix,
          "表", appendix, table.captionText(), source);
    }
    for (ImageModel image : document.images()) {
      String appendix = regions.appendixLetterAt(document, image.source());
      if (appendix == null) continue;
      SourceAddress source = image.captionSource() == null ? image.source() : image.captionSource();
      findings.add(checkAppendixObjectNumber("图", appendix, image.captionText(), source));
      checkAppendixObjectSequence(findings, nextByTypeAndAppendix,
          "图", appendix, image.captionText(), source);
    }
    for (EquationModel equation : document.equations()) {
      if (!equation.standalone()) continue;
      String appendix = regions.appendixLetterAt(document, equation.source());
      if (appendix == null) continue;
      String actualLetter = equation.number() != null && equation.number().matches("[A-Z]\\d+")
          ? equation.number().substring(0, 1) : null;
      findings.add(appendixObjectResult("公式", appendix, actualLetter,
          equation.number(), equation.source()));
      checkAppendixObjectSequence(findings, nextByTypeAndAppendix,
          "公式", appendix, equation.number(), equation.source());
    }
  }

  private void checkAppendixObjectSequence(List<CheckResult> findings,
      Map<String, BigInteger> nextByTypeAndAppendix, String type, String appendix,
      String value, SourceAddress source) {
    if (value == null || ("表".equals(type) && value.stripLeading().startsWith("续表"))) return;
    String prefix = "公式".equals(type) ? "" : ("表".equals(type)
        ? "(?:表|续表)\\s*" : Pattern.quote(type) + "\\s*");
    Matcher matcher = Pattern.compile("^" + prefix + Pattern.quote(appendix) + "(\\d+)(?:\\s|$)")
        .matcher(value.strip());
    if (!matcher.find()) return;
    String key = type + appendix;
    BigInteger actual = new BigInteger(matcher.group(1));
    BigInteger expected = nextByTypeAndAppendix.getOrDefault(key, BigInteger.ONE);
    if (!actual.toString().equals(matcher.group(1)) || !actual.equals(expected))
      findings.add(result("APP-01", type + appendix + expected, value,
          "APPENDIX_OBJECT_SEQUENCE_MISMATCH", Judgement.FAIL, source));
    nextByTypeAndAppendix.put(key, expected.max(actual).add(BigInteger.ONE));
  }

  private CheckResult checkAppendixObjectNumber(String label, String appendix,
      String caption, SourceAddress source) {
    if (caption == null) return appendixObjectResult(label, appendix, null, null, source);
    String prefix = "表".equals(label) ? "(?:续表|表)" : Pattern.quote(label);
    Matcher matcher = Pattern.compile("^\\s*" + prefix + "\\s*([A-Z])\\d+").matcher(caption);
    String actualLetter = matcher.find() ? matcher.group(1) : null;
    return appendixObjectResult(label, appendix, actualLetter, caption, source);
  }

  private CheckResult appendixObjectResult(String label, String expected, String actualLetter,
      String actual, SourceAddress source) {
    boolean matches = expected.equals(actualLetter);
    String reason = matches ? "" : actualLetter == null
        ? "APPENDIX_OBJECT_PREFIX_MISSING" : "APPENDIX_OBJECT_LETTER_MISMATCH";
    return result("APP-01", label + expected, actual, reason,
        matches ? Judgement.PASS : Judgement.FAIL, source);
  }

  private static String text(ParagraphModel paragraph) {
    return ManualLineBreakSupport.text(paragraph);
  }

  private static String compact(String text) {
    return text.codePoints()
        .filter(codePoint -> !Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)
            && codePoint != 0x200B && codePoint != 0x200C && codePoint != 0x200D
            && codePoint != 0x2060 && codePoint != 0xFEFF)
        .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
        .toString();
  }

  private static boolean isVisualBlankParagraph(ParagraphModel paragraph) {
    return ManualLineBreakSupport.visualBlankLines(paragraph) > 0;
  }

  private static CheckResult result(String ruleId, String expected, String actual, String reason,
      Judgement judgement, SourceAddress source) {
    return new CheckResult(ruleId, Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        judgement, expected, actual, reason, source);
  }
}
