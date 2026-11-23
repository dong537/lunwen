package cn.luwneshacha.thesis.inspection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.luwneshacha.thesis.rules.RuleEngine;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuleCatalogOwnerMappingTest {
  private static final String ABSTRACT = "AbstractRuleChecker";
  private static final String APPENDIX = "AppendixRuleModule";
  private static final String BODY = "BodyFormatRuleChecker";
  private static final String CITATION = "CitationRuleModule";
  private static final String CONTENTS = "ContentsRuleChecker";
  private static final String COVER = "CoverRuleChecker";
  private static final String END_MATTER = "EndMatterRuleChecker";
  private static final String HEADING = "HeadingDocumentRuleModule";
  private static final String REFERENCE_COLLECTION = "ReferenceCollectionRuleModule";

  @Test
  void eachCatalogRuleHasOnlyThePipelineModulesThatCanCheckIt() {
    int ownerPairCount = 0;
    for (var entry : RuleCatalog.entries()) {
      List<String> expected = expectedOwners(entry);
      assertEquals(expected, RuleCatalog.ownerModuleIds(entry.ruleId()), entry.ruleId());
      ownerPairCount += expected.size();
    }
    assertEquals(146, ownerPairCount);
  }

  @Test
  void completedUnrelatedModulesDoNotControlRuleEvaluation() {
    var withoutHeading = new ArrayList<>(new RuleEngine().registeredModuleIds());
    withoutHeading.remove(HEADING);
    var headingEvidence = RuleExecutionEvidence.from(withoutHeading);
    assertTrue(headingEvidence.evaluatedRuleIds().contains("TOC-01-FONT"));
    assertTrue(headingEvidence.evaluatedRuleIds().contains("REF-FONT"));
    assertFalse(headingEvidence.evaluatedRuleIds().contains("ZH-01-FONT"));
    assertFalse(headingEvidence.evaluatedRuleIds().contains("REF-01-FONT"));

    var withoutBody = new ArrayList<>(new RuleEngine().registeredModuleIds());
    withoutBody.remove(BODY);
    var bodyEvidence = RuleExecutionEvidence.from(withoutBody);
    assertTrue(bodyEvidence.evaluatedRuleIds().contains("HEAD-05-FONT"));
    assertFalse(bodyEvidence.evaluatedRuleIds().contains("HEAD-EMBEDDED"));
    assertFalse(bodyEvidence.evaluatedRuleIds().contains("HEADING-FONT"));

    var withoutCitation = new ArrayList<>(new RuleEngine().registeredModuleIds());
    withoutCitation.remove(CITATION);
    var citationEvidence = RuleExecutionEvidence.from(withoutCitation);
    assertTrue(citationEvidence.evaluatedRuleIds().contains("REF-04"));
    assertFalse(citationEvidence.evaluatedRuleIds().contains("REF-03"));
  }

  private static List<String> expectedOwners(RuleCatalog.Entry entry) {
    String id = entry.ruleId();
    return switch (entry.module()) {
      case "编号方式" -> List.of("AutomaticNumberingRuleModule");
      case "封面" -> id.equals("COVER-05") ? List.of("RequiredSectionRuleChecker") : List.of(COVER);
      case "摘要" -> switch (id) {
        case "ZH-01-FONT", "ZH-01-SIZE", "ZH-01-ALIGNMENT" -> List.of(HEADING);
        default -> List.of(ABSTRACT);
      };
      case "英文摘要" -> id.equals("EN-01-BLANK") || id.startsWith("EN-03-")
          ? List.of(ABSTRACT) : List.of(HEADING);
      case "目录" -> List.of(CONTENTS);
      case "正文标题" -> switch (id) {
        case "HEAD-EMBEDDED" -> List.of(BODY);
        case "HEAD-02-SPACING", "HEAD-03-SPACING", "HEADING-FONT", "HEADING-SIZE" ->
            List.of(BODY, HEADING);
        default -> List.of(HEADING);
      };
      case "正文格式" -> List.of(BODY);
      case "必需章节" -> List.of("RequiredSectionRuleChecker");
      case "页面与页码" -> List.of("SectionLayoutRuleChecker");
      case "页眉" -> List.of("HeaderRuleModule");
      case "插图" -> List.of("ImageRuleChecker");
      case "图表题" -> List.of("ImageRuleChecker", "TableRuleModule");
      case "表格" -> List.of("TableRuleModule");
      case "公式" -> List.of("EquationRuleModule");
      case "参考文献" -> switch (id) {
        case "REF-01-SPACING", "REF-01-FONT", "REF-01-SIZE", "REF-01-ALIGNMENT" -> List.of(HEADING);
        case "REF-01-BLANK" -> List.of(END_MATTER);
        case "REF-02", "REF-05" -> List.of(REFERENCE_COLLECTION);
        case "REF-03" -> List.of(CITATION, REFERENCE_COLLECTION);
        case "REF-04", "REF-07" -> List.of("ReferenceRuleChecker");
        case "REF-FONT", "REF-LINE", "REF-SIZE" -> List.of("ReferenceFormatRuleChecker");
        default -> throw new AssertionError("Unclassified reference rule: " + id);
      };
      case "致谢" -> id.equals("END-01-BLANK") ? List.of(END_MATTER) : List.of(HEADING);
      case "附录" -> switch (id) {
        case "APP-01", "APP-02-BLANK", "APP-03" -> List.of(APPENDIX);
        default -> List.of(HEADING);
      };
      case "引用" -> List.of(CITATION);
      default -> throw new AssertionError("Unclassified catalog group: " + entry.module());
    };
  }
}
