package cn.luwneshacha.thesis.rules;

import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.rendering.RenderedLayoutEvidence;
import java.util.List;

public final class RuleEngine {
  private final ParagraphFormatChecker paragraphFormatChecker = new ParagraphFormatChecker();
  private final HeadingRuleChecker headingRuleChecker = new HeadingRuleChecker(paragraphFormatChecker);
  private final RuleModulePipeline modules;

  public RuleEngine() { this(null, false); }

  public RuleEngine(RenderedLayoutEvidence renderedLayout) { this(renderedLayout, false); }

  public RuleEngine(RenderedLayoutEvidence renderedLayout, boolean untrustedFooterContent) {
    modules = new RuleModulePipeline(List.of(
      new AutomaticNumberingRuleModule(),
      new SectionLayoutRuleChecker(untrustedFooterContent, renderedLayout),
      new HeaderRuleModule(),
      new RequiredSectionRuleChecker(),
      new AbstractRuleChecker(paragraphFormatChecker, renderedLayout),
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
      new TableRuleModule(renderedLayout),
      new ReferenceCollectionRuleModule()));
  }

  public List<CheckResult> check(ParsedDocument document, RuleSet rules) {
    return checkWithExecution(document, rules).results();
  }

  public Evaluation checkWithExecution(ParsedDocument document, RuleSet rules) {
    if (document.diagnostics().contains(cn.luwneshacha.thesis.document.DocumentClassifier.RULE_SPECIFICATION_DOCUMENT))
      return new Evaluation(List.of(), List.of(), List.of());
    var checked = modules.checkWithExecution(document, rules);
    return new Evaluation(checked.results(), checked.completedModuleIds(), checked.ruleRuns());
  }

  public List<String> registeredModuleIds() { return modules.registeredModuleIds(); }

  public record Evaluation(List<CheckResult> results, List<String> completedModuleIds, List<RuleRun> ruleRuns) {
    public Evaluation {
      results = List.copyOf(results);
      completedModuleIds = List.copyOf(completedModuleIds);
      ruleRuns = List.copyOf(ruleRuns);
    }
  }
}
