package cn.luwneshacha.thesis.rules;

import cn.luwneshacha.thesis.document.EquationModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import java.util.ArrayList;
import java.util.List;

/**
 * Owns the equation-object boundary of the rule pipeline.
 *
 * <p>The parser can expose several equation objects for one document. This
 * module keeps equation object, citation, and sequence checks behind one
 * document-level rule contract.</p>
 */
final class EquationRuleModule implements RuleModule {
  private final EquationRuleChecker checker = new EquationRuleChecker();
  private final EquationReferenceRuleChecker referenceChecker = new EquationReferenceRuleChecker();
  private final EquationSequenceRuleChecker sequenceChecker = new EquationSequenceRuleChecker();

  @Override
  public List<CheckResult> check(ParsedDocument document, RuleSet rules) {
    var findings = new ArrayList<CheckResult>();
    for (EquationModel equation : document.equations()) {
      findings.addAll(checker.check(document, equation, rules));
      if (equation.standalone() && equation.number() != null) {
        findings.add(referenceChecker.check(document, equation));
      }
    }
    findings.addAll(sequenceChecker.check(document));
    return List.copyOf(findings);
  }
}
