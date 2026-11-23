package cn.luwneshacha.thesis.inspection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.luwneshacha.thesis.rules.RuleEngine;
import cn.luwneshacha.thesis.rules.RuleRun;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuleExecutionEvidenceTest {
  @Test
  void catalogRuleOwnersBelongToTheRegisteredPipelineAndCompletionIsExact() {
    var registered = new RuleEngine().registeredModuleIds();
    assertEquals(List.of("AutomaticNumberingRuleModule", "SectionLayoutRuleChecker",
        "HeaderRuleModule", "RequiredSectionRuleChecker", "AbstractRuleChecker",
        "ContentsRuleChecker", "EndMatterRuleChecker", "BodyFormatRuleChecker",
        "CoverRuleChecker", "EquationRuleModule", "ImageRuleChecker",
        "ReferenceRuleChecker", "ReferenceFormatRuleChecker", "CitationRuleModule",
        "AppendixRuleModule", "HeadingDocumentRuleModule", "TableRuleModule",
        "ReferenceCollectionRuleModule"), registered);
    for (var entry : RuleCatalog.entries()) {
      var owners = RuleCatalog.ownerModuleIds(entry.ruleId());
      assertFalse(owners.isEmpty(), entry.ruleId());
      assertTrue(registered.containsAll(owners), entry.ruleId() + " owners: " + owners);
    }

    var evidence = RuleExecutionEvidence.from(registered);
    assertEquals(2, evidence.schemaVersion());
    assertEquals(139, evidence.catalogRuleIds().size());
    assertEquals(evidence.catalogRuleIds(), evidence.evaluatedRuleIds());
    assertEquals(64, evidence.catalogSha256().length());
    assertEquals("dc9effdf24676d305db150e5375a84f681e4597fa5fc73f28f77ccb7d270fa37",
        evidence.catalogSha256());
    assertEquals("05119a8147376d77fe437805bee71d3ef84d2b4b31ee3e68b754e0a98fefeba8",
        evidence.ownerPairsSha256());
    assertEquals(registered, evidence.completedModuleIds());
  }

  @Test
  void ordersOnlyReportedRuleRunsByCatalogAndOwner() {
    var registered = new RuleEngine().registeredModuleIds();
    var margins = new RuleRun("PAGE-01", "SectionLayoutRuleChecker", 4, 4, 0, null);
    var numbering = new RuleRun("NUMBERING-01", "AutomaticNumberingRuleModule", 1, 1, 0, null);

    var evidence = RuleExecutionEvidence.from(registered, List.of(margins, numbering));

    assertEquals(List.of(numbering, margins), evidence.ruleRuns());
    assertEquals(2, evidence.ruleRuns().size(), "已完成模块不能生成未报告的规则证据");
  }

  @Test
  void rejectsDuplicateUnknownOrUnfinishedOwnerEvidence() {
    var registered = new RuleEngine().registeredModuleIds();
    var margins = new RuleRun("PAGE-01", "SectionLayoutRuleChecker", 4, 4, 0, null);
    assertThrows(IllegalArgumentException.class,
        () -> RuleExecutionEvidence.from(registered, List.of(margins, margins)));
    assertThrows(IllegalArgumentException.class,
        () -> RuleExecutionEvidence.from(registered,
            List.of(new RuleRun("UNKNOWN", "SectionLayoutRuleChecker", 1, 1, 0, null))));
    var unfinished = new ArrayList<>(registered);
    unfinished.remove("SectionLayoutRuleChecker");
    assertThrows(IllegalArgumentException.class,
        () -> RuleExecutionEvidence.from(unfinished, List.of(margins)));
  }

  @Test
  void missingCheckerCannotClaimItsRulesWereEvaluated() {
    var completed = new ArrayList<>(new RuleEngine().registeredModuleIds());
    completed.remove("BodyFormatRuleChecker");

    var evidence = RuleExecutionEvidence.from(completed);
    assertFalse(evidence.evaluatedRuleIds().contains("BODY-SCOPE"));
    assertFalse(evidence.evaluatedRuleIds().contains("HEAD-EMBEDDED"));
    assertTrue(evidence.evaluatedRuleIds().contains("PAGE-01"));
  }
}
