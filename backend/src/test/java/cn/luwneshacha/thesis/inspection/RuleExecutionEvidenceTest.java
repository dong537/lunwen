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
    assertEquals(140, evidence.catalogRuleIds().size());
    assertEquals(evidence.catalogRuleIds(), evidence.evaluatedRuleIds());
    assertEquals(64, evidence.catalogSha256().length());
    assertEquals("82c3e83def51c8761e496ad190b7660786d649e9424910ba5b34b6ec08d14f0a",
        evidence.catalogSha256());
    assertEquals("f4a724a7eb4757d7f65caf4eb3f968543245c739231d99cb6e6bdcece62d8ffe",
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
