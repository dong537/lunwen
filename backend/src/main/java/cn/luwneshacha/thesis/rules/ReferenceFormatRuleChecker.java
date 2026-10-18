package cn.luwneshacha.thesis.rules;

import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.RunModel;
import java.util.ArrayList;
import java.util.List;

/** Applies the shared font, size, and line-spacing contract to references. */
final class ReferenceFormatRuleChecker implements RuleModule {
  private final ParagraphFormatChecker formatChecker;

  ReferenceFormatRuleChecker(ParagraphFormatChecker formatChecker) {
    this.formatChecker = formatChecker;
  }

  @Override
  public List<CheckResult> check(ParsedDocument document, RuleSet rules) {
    var results = new ArrayList<CheckResult>();
    for (var reference : document.references()) {
      document.paragraphs().stream()
          .filter(paragraph -> sameSource(paragraph, reference.source()))
          .findFirst()
          .ifPresent(paragraph -> addParagraphResults(results, paragraph, rules));
    }
    return List.copyOf(results);
  }

  private void addParagraphResults(List<CheckResult> results, ParagraphModel paragraph, RuleSet rules) {
    for (RunModel run : paragraph.runs()) {
      results.add(formatChecker.checkFont(run, rules, "REF-FONT"));
      results.add(formatChecker.checkSize(run, rules, "REF-SIZE"));
    }
    results.add(formatChecker.checkLineSpacing("REF-LINE", paragraph));
  }

  private static boolean sameSource(ParagraphModel paragraph,
      cn.luwneshacha.thesis.document.SourceAddress source) {
    return paragraph.source().partName().equals(source.partName())
        && paragraph.source().elementPath().equals(source.elementPath());
  }
}
