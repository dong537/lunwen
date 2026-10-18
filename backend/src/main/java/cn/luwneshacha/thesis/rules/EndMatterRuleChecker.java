package cn.luwneshacha.thesis.rules;

import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.RunModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import java.util.ArrayList;
import java.util.List;

/** Checks the required single blank line after reference and acknowledgement titles. */
final class EndMatterRuleChecker implements RuleModule {
  private final DocumentRegionClassifier regions = new DocumentRegionClassifier();

  @Override
  public List<CheckResult> check(ParsedDocument document, RuleSet rules) {
    List<ParagraphModel> paragraphs = document.paragraphs();
    var results = new ArrayList<CheckResult>();
    for (int index = 0; index < paragraphs.size(); index++) {
      ParagraphModel heading = paragraphs.get(index);
      if (regions.isInsideTable(heading, document.tables())) continue;
      String normalized = compact(text(heading));
      String ruleId;
      String missingReason;
      String countReason;
      if ("参考文献".equals(normalized) || "REFERENCES".equalsIgnoreCase(normalized)) {
        ruleId = "REF-01-BLANK";
        missingReason = "REFERENCE_TITLE_BLANK_LINE_MISSING";
        countReason = "REFERENCE_TITLE_BLANK_LINE_COUNT_MISMATCH";
      } else if ("致谢".equals(normalized) || "ACKNOWLEDGMENTS".equalsIgnoreCase(normalized)
          || "ACKNOWLEDGEMENTS".equalsIgnoreCase(normalized)) {
        ruleId = "END-01-BLANK";
        missingReason = "ACKNOWLEDGEMENT_TITLE_BLANK_LINE_MISSING";
        countReason = "ACKNOWLEDGEMENT_TITLE_BLANK_LINE_COUNT_MISMATCH";
      } else {
        continue;
      }
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
        results.add(result(ruleId, "空一行", "文档末尾无下一段可确认",
            missingReason, Judgement.REVIEW, heading.source()));
        continue;
      }
      SourceAddress source = missing ? paragraphs.get(next).source() : heading.source();
      results.add(result(ruleId, "空一行", missing ? "未空一行" : String.valueOf(blankLines),
          missing ? missingReason : countReason, Judgement.FAIL, source));
    }
    return List.copyOf(results);
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
