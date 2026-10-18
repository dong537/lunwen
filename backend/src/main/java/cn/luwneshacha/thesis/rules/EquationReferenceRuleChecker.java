package cn.luwneshacha.thesis.rules;

import cn.luwneshacha.thesis.document.EquationModel;
import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.SourceAddress;
import java.util.regex.Pattern;

/** Checks whether each numbered equation is cited in its body or appendix. */
final class EquationReferenceRuleChecker {
  private static final Pattern CAPTION = Pattern.compile("^(续表|表|图)\\s*[0-9一二三四五六七八九十]+",
      Pattern.CASE_INSENSITIVE);
  private final DocumentRegionClassifier regions = new DocumentRegionClassifier();

  CheckResult check(ParsedDocument document, EquationModel equation) {
    Pattern reference = citationPattern(equation.number());
    String appendix = regions.appendixLetterAt(document, equation.source());
    boolean cited = appendix == null
        ? citedInBody(document, reference)
        : citedInAppendix(document, appendix, reference);
    return new CheckResult("EQ-04", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        cited ? Judgement.PASS : Judgement.FAIL,
        appendix == null ? "正文引用公式 " + equation.number()
            : "附录 " + appendix + " 引用公式 " + equation.number(),
        cited ? equation.number() : null,
        cited ? "" : "EQUATION_NOT_REFERENCED",
        cited ? citationSource(document, appendix, reference) : equation.source());
  }

  private boolean citedInBody(ParsedDocument document, Pattern reference) {
    boolean inMainBody = false;
    for (ParagraphModel paragraph : document.paragraphs()) {
      String text = text(paragraph);
      if (!regions.isInsideTable(paragraph, document.tables())
          && regions.isBodyChapterStart(paragraph, document)) inMainBody = true;
      if (!regions.isInsideTable(paragraph, document.tables()) && inMainBody
          && regions.isEndMatterHeading(paragraph, document)) inMainBody = false;
      if (inMainBody && eligibleParagraph(paragraph, document) && reference.matcher(text).find()) return true;
    }
    return false;
  }

  private boolean citedInAppendix(ParsedDocument document, String appendix, Pattern reference) {
    boolean inAppendix = false;
    for (ParagraphModel paragraph : document.paragraphs()) {
      String text = text(paragraph);
      String heading = regions.appendixHeadingLetter(paragraph, document);
      if (heading != null) {
        inAppendix = appendix.equals(heading);
        continue;
      }
      if (inAppendix && eligibleParagraph(paragraph, document) && reference.matcher(text).find()) return true;
    }
    return false;
  }

  private SourceAddress citationSource(ParsedDocument document, String appendix, Pattern reference) {
    boolean inMainBody = false;
    boolean inAppendix = false;
    for (ParagraphModel paragraph : document.paragraphs()) {
      String text = text(paragraph);
      if (appendix == null) {
        if (!regions.isInsideTable(paragraph, document.tables())
            && regions.isBodyChapterStart(paragraph, document)) inMainBody = true;
        if (!regions.isInsideTable(paragraph, document.tables()) && inMainBody
            && regions.isEndMatterHeading(paragraph, document)) inMainBody = false;
        if (inMainBody && eligibleParagraph(paragraph, document) && reference.matcher(text).find())
          return paragraph.source();
      } else {
        String heading = regions.appendixHeadingLetter(paragraph, document);
        if (heading != null) {
          inAppendix = appendix.equals(heading);
          continue;
        }
        if (inAppendix && eligibleParagraph(paragraph, document) && reference.matcher(text).find())
          return paragraph.source();
      }
    }
    return null;
  }

  private boolean eligibleParagraph(ParagraphModel paragraph, ParsedDocument document) {
    String value = text(paragraph);
    return !regions.isInsideTable(paragraph, document.tables()) && !CAPTION.matcher(value).find();
  }

  private Pattern citationPattern(String number) {
    String quoted = Pattern.quote(number);
    return Pattern.compile("(?:公式|式)(?:\\(" + quoted + "\\)|（" + quoted + "）)"
        + "(?![\\p{IsLatin}\\p{N}])");
  }

  private static String text(ParagraphModel paragraph) {
    return paragraph.runs().stream().map(run -> run.text()).reduce("", String::concat).trim();
  }

}
