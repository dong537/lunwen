package cn.luwneshacha.thesis.rules;

import cn.luwneshacha.thesis.document.ParsedDocument;
import java.util.List;

public final class RuleEngine {
  private final ParagraphFormatChecker paragraphFormatChecker = new ParagraphFormatChecker();
  private final HeadingRuleChecker headingRuleChecker = new HeadingRuleChecker(paragraphFormatChecker);
  private final RuleModulePipeline modules = new RuleModulePipeline(List.of(
      new AutomaticNumberingRuleModule(),
      new SectionLayoutRuleChecker(),
      new HeaderRuleModule(),
      new RequiredSectionRuleChecker(),
      new AbstractRuleChecker(paragraphFormatChecker),
      new ContentsRuleChecker(paragraphFormatChecker),
      new EndMatterRuleChecker(),
      new BodyFormatRuleChecker(paragraphFormatChecker),
      new CoverRuleChecker(paragraphFormatChecker),
      new EquationRuleModule(),
      new ImageRuleChecker(),
      new ReferenceRuleChecker(),
      new ReferenceFormatRuleChecker(paragraphFormatChecker),
      new CitationRuleModule(headingRuleChecker),
      new AppendixRuleModule(),
      new HeadingDocumentRuleModule(headingRuleChecker, paragraphFormatChecker),
      new TableRuleModule(),
      new ReferenceCollectionRuleModule()));

  public List<CheckResult> check(ParsedDocument document, RuleSet rules) {
    if (document.diagnostics().contains(cn.luwneshacha.thesis.document.DocumentClassifier.RULE_SPECIFICATION_DOCUMENT))
      return List.of();
    return modules.check(document, rules);
  }
}
