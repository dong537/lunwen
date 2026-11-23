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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class AppendixRuleModule implements RuleModule {
  static final String OPTIONAL_APPENDIX_ABSENT_REASON =
      "OPTIONAL_APPENDIX_ABSENT_AFTER_END_MATTER_SCAN";
  private final DocumentRegionClassifier regions = new DocumentRegionClassifier();

  @Override
  public List<CheckResult> check(ParsedDocument document, RuleSet rules) {
    return evaluate(document, rules).results();
  }

  @Override
  public Evaluation evaluate(ParsedDocument document, RuleSet rules) {
    var findings = new ArrayList<CheckResult>();
    var blankLine = new EvidenceCounter();
    var pageBreak = new EvidenceCounter();
    var numbering = new EvidenceCounter();
    checkAppendixTitleBlankLine(findings, document, blankLine);
    checkAppendixPageBreak(findings, document, pageBreak);
    checkAppendixSequence(findings, document, numbering);
    checkAppendixObjects(findings, document, numbering);
    var runs = new ArrayList<RuleRun>();
    if (numbering.candidates > 0) runs.add(new RuleRun("APP-01", getClass().getSimpleName(),
        numbering.candidates, numbering.candidates - numbering.unknown, numbering.unknown, null));
    if (blankLine.candidates > 0) runs.add(new RuleRun("APP-02-BLANK",
        getClass().getSimpleName(), blankLine.candidates,
        blankLine.candidates - blankLine.unknown, blankLine.unknown, null));
    if (pageBreak.candidates > 0) runs.add(new RuleRun("APP-03", getClass().getSimpleName(),
        pageBreak.candidates, pageBreak.candidates - pageBreak.unknown, pageBreak.unknown, null));
    if (hasProvenOptionalAppendixAbsent(document)) {
      for (String ruleId : List.of("APP-01", "APP-02-BLANK", "APP-03"))
        runs.add(new RuleRun(ruleId, getClass().getSimpleName(), 0, 0, 0,
            OPTIONAL_APPENDIX_ABSENT_REASON));
    }
    return new Evaluation(List.copyOf(findings), List.copyOf(runs));
  }

  /**
   * A parsed empty appendix list is not proof of absence.  Require an ordered
   * body, references and acknowledgements boundary, and reject unresolved
   * parser/object evidence that might conceal a malformed appendix.
   * Raw DOCX coverage is checked independently before the platform accepts a report.
   */
  static boolean hasProvenOptionalAppendixAbsent(ParsedDocument document) {
    if (!document.diagnostics().isEmpty() || !document.tableInventoryComplete()) return false;
    var regions = new DocumentRegionClassifier();
    boolean body = false;
    boolean references = false;
    SourceAddress acknowledgement = null;
    SourceAddress previous = null;
    for (ParagraphModel paragraph : document.paragraphs()) {
      if (!"word/document.xml".equalsIgnoreCase(paragraph.source().partName())
          || paragraph.insideTextBox()
          || (previous != null && !atOrAfter(previous, paragraph.source()))) return false;
      previous = paragraph.source();
      String text = DocumentRegionClassifier.compactContentsText(
          ManualLineBreakSupport.text(paragraph));
      String uppercase = text.toUpperCase(Locale.ROOT);
      // Include the TOC and malformed headings: either is evidence that an
      // appendix may exist even when the exact title parser found no candidate.
      if (text.contains("附录") || text.contains("附錄") || uppercase.contains("APPENDIX"))
        return false;
      if (regions.hasDefiniteContentsEvidence(paragraph)) continue;
      if ((regions.isBodyChapterStart(paragraph, document)
          || isReferenceHeading(text) || isAcknowledgementHeading(text))
          && regions.isContentsEntry(paragraph, document)) continue;
      if (!body && regions.isBodyChapterStart(paragraph, document)) body = true;
      if (body && isReferenceHeading(text)) references = true;
      if (references && isAcknowledgementHeading(text)) acknowledgement = paragraph.source();
    }
    if (acknowledgement == null) return false;
    for (TableModel table : document.tables()) {
      if (table.flowPosition() != TableModel.FlowPosition.INLINE_BODY
          || atOrAfter(acknowledgement, table.source())) return false;
    }
    for (ImageModel image : document.images()) {
      if (!image.inline() || image.captionNumberAmbiguous()
          || atOrAfter(acknowledgement, image.source())
          || appendixObjectLabel(image.captionText())) return false;
    }
    for (EquationModel equation : document.equations()) {
      if (atOrAfter(acknowledgement, equation.source())
          || appendixObjectLabel(equation.number())
          || appendixObjectLabel(equation.summary())) return false;
    }
    return true;
  }

  private static boolean isReferenceHeading(String text) {
    return "参考文献".equals(text) || "REFERENCES".equalsIgnoreCase(text);
  }

  private static boolean isAcknowledgementHeading(String text) {
    return "致谢".equals(text) || "ACKNOWLEDGMENTS".equalsIgnoreCase(text)
        || "ACKNOWLEDGEMENTS".equalsIgnoreCase(text);
  }

  private static boolean appendixObjectLabel(String value) {
    if (value == null) return false;
    return Pattern.compile("(?i)^(?:续表|表|图|式|figure|table|equation)\\s*[A-Z]\\s*\\d+")
        .matcher(value.strip()).find();
  }

  private static boolean atOrAfter(SourceAddress boundary, SourceAddress candidate) {
    if (candidate == null || !boundary.partName().equals(candidate.partName())) return true;
    List<Integer> left = candidate.elementPath();
    List<Integer> right = boundary.elementPath();
    for (int index = 0; index < Math.min(left.size(), right.size()); index++) {
      int compared = Integer.compare(left.get(index), right.get(index));
      if (compared != 0) return compared > 0;
    }
    return left.size() >= right.size();
  }

  /**
   * The appendix total heading must start a new page.  A positive XML signal
   * is authoritative; when no signal is present the final Word pagination is
   * unknowable from static XML, so keep the result as REVIEW.
   */
  private void checkAppendixPageBreak(List<CheckResult> findings, ParsedDocument document,
      EvidenceCounter evidenceCounts) {
    for (ParagraphModel heading : document.paragraphs()) {
      if (!regions.isMainAppendixTitle(heading, document)) continue;
      evidenceCounts.candidates++;
      HeadingBreakModel evidence = document.chapterBreaks().stream()
          .filter(candidate -> sameParagraph(candidate.source(), heading.source()))
          .findFirst().orElse(null);
      // Parsed DOCX documents always emit an evidence record for the main
      // appendix heading.  Synthetic ParsedDocument fixtures may omit it;
      // avoid inventing a second finding when no source evidence exists.
      if (evidence == null) {
        evidenceCounts.unknown++;
        continue;
      }
      if (evidence.explicitBreak()) continue;
      evidenceCounts.unknown++;
      String actual = "XML 中未发现附录标题前分页属性";
      findings.add(result("APP-03", "附录总标题另起一页", actual,
          "APPENDIX_PAGE_BREAK_REVIEW", Judgement.REVIEW, heading.source()));
    }
  }

  private boolean sameParagraph(SourceAddress left, SourceAddress right) {
    return left != null && right != null && left.partName().equals(right.partName())
        && left.elementPath().equals(right.elementPath());
  }

  /** The main "附    录" title is followed by exactly one visual blank paragraph. */
  private void checkAppendixTitleBlankLine(List<CheckResult> findings, ParsedDocument document,
      EvidenceCounter evidenceCounts) {
    List<ParagraphModel> paragraphs = document.paragraphs();
    for (int index = 0; index < paragraphs.size(); index++) {
      ParagraphModel heading = paragraphs.get(index);
      if (!regions.isMainAppendixTitle(heading, document)) continue;
      evidenceCounts.candidates++;

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
        evidenceCounts.unknown++;
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

  private void checkAppendixSequence(List<CheckResult> findings, ParsedDocument document,
      EvidenceCounter evidenceCounts) {
    char expectedHeading = 'A';
    for (ParagraphModel paragraph : document.paragraphs()) {
      String actual = regions.appendixHeadingLetter(paragraph, document);
      if (actual == null) continue;
      evidenceCounts.candidates++;
      boolean matches = expectedHeading == actual.charAt(0);
      findings.add(result("APP-01", String.valueOf(expectedHeading), actual,
          matches ? "" : "APPENDIX_SEQUENCE_MISMATCH",
          matches ? Judgement.PASS : Judgement.FAIL, paragraph.source()));
      expectedHeading++;
    }
  }

  private void checkAppendixObjects(List<CheckResult> findings, ParsedDocument document,
      EvidenceCounter evidenceCounts) {
    Map<String, BigInteger> nextByTypeAndAppendix = new HashMap<>();
    var uncertainFigureAppendices = new HashSet<String>();
    for (TableModel table : document.tables()) {
      if (table.fragmentEvidence() != null) continue;
      String appendix = regions.appendixLetterAt(document,
          table.captionSource() == null ? table.source() : table.captionSource());
      if (appendix == null) continue;
      evidenceCounts.candidates++;
      SourceAddress source = table.captionSource() == null ? table.source() : table.captionSource();
      findings.add(checkAppendixObjectNumber("表", appendix, table.captionText(), source));
      if (!checkAppendixObjectSequence(findings, nextByTypeAndAppendix,
          "表", appendix, table.captionText(), source)) evidenceCounts.unknown++;
    }
    for (ImageModel image : document.images()) {
      String appendix = regions.appendixLetterAt(document, image.source());
      if (appendix == null) continue;
      evidenceCounts.candidates++;
      SourceAddress source = image.captionSource() == null ? image.source() : image.captionSource();
      if (image.captionNumberAmbiguous()) {
        evidenceCounts.unknown++;
        uncertainFigureAppendices.add(appendix);
        findings.add(result("APP-01", "可确认的附录图号", image.captionText(),
            "APPENDIX_FIGURE_NUMBER_AMBIGUOUS", Judgement.REVIEW, source));
        continue;
      }
      findings.add(checkAppendixObjectNumber("图", appendix, image.captionText(), source));
      if (uncertainFigureAppendices.contains(appendix)) {
        evidenceCounts.unknown++;
        findings.add(result("APP-01", "附录图号连续", image.captionText(),
            "APPENDIX_FIGURE_SEQUENCE_UNRESOLVED_AFTER_AMBIGUOUS_CAPTION", Judgement.REVIEW, source));
      } else {
        if (!checkAppendixObjectSequence(findings, nextByTypeAndAppendix,
            "图", appendix, image.captionText(), source)) evidenceCounts.unknown++;
      }
    }
    for (EquationModel equation : document.equations()) {
      if (!equation.standalone()) continue;
      String appendix = regions.appendixLetterAt(document, equation.source());
      if (appendix == null) continue;
      evidenceCounts.candidates++;
      String actualLetter = equation.number() != null && equation.number().matches("[A-Z]\\d+")
          ? equation.number().substring(0, 1) : null;
      findings.add(appendixObjectResult("公式", appendix, actualLetter,
          equation.number(), equation.source()));
      if (!checkAppendixObjectSequence(findings, nextByTypeAndAppendix,
          "公式", appendix, equation.number(), equation.source())) evidenceCounts.unknown++;
    }
  }

  private boolean checkAppendixObjectSequence(List<CheckResult> findings,
      Map<String, BigInteger> nextByTypeAndAppendix, String type, String appendix,
      String value, SourceAddress source) {
    if (value == null) return false;
    // A continuation does not advance the sequence, but its appendix prefix was checked.
    if ("表".equals(type) && value.stripLeading().startsWith("续表")) return true;
    String prefix = "公式".equals(type) ? "" : ("表".equals(type)
        ? "(?:表|续表)\\s*" : Pattern.quote(type) + "\\s*");
    Matcher matcher = Pattern.compile("^" + prefix + Pattern.quote(appendix) + "(\\d+)(?:\\s|$)")
        .matcher(value.strip());
    if (!matcher.find()) return false;
    String key = type + appendix;
    BigInteger actual = new BigInteger(matcher.group(1));
    BigInteger expected = nextByTypeAndAppendix.getOrDefault(key, BigInteger.ONE);
    if (!actual.toString().equals(matcher.group(1)) || !actual.equals(expected))
      findings.add(result("APP-01", type + appendix + expected, value,
          "APPENDIX_OBJECT_SEQUENCE_MISMATCH", Judgement.FAIL, source));
    nextByTypeAndAppendix.put(key, expected.max(actual).add(BigInteger.ONE));
    return true;
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

  private static boolean isVisualBlankParagraph(ParagraphModel paragraph) {
    return ManualLineBreakSupport.visualBlankLines(paragraph) > 0;
  }

  private static final class EvidenceCounter {
    private int candidates;
    private int unknown;
  }

  private static CheckResult result(String ruleId, String expected, String actual, String reason,
      Judgement judgement, SourceAddress source) {
    return new CheckResult(ruleId, Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        judgement, expected, actual, reason, source);
  }
}
