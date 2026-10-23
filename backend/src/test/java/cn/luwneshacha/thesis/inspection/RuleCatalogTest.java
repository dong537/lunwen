package cn.luwneshacha.thesis.inspection;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import org.junit.jupiter.api.Test;

class RuleCatalogTest {
  @Test
  void appendixIsOptionalAndOnlyExistingAppendicesAreInScope() {
    var required = RuleCatalog.find("SECTION-REQUIRED");
    var appendix = RuleCatalog.find("APP-01");

    assertFalse(required.description().contains("附录"));
    assertTrue(appendix.description().contains("附录可选"));
    assertTrue(appendix.scope().contains("实际存在"));
  }

  @Test
  void everyCatalogRuleIdIsUniqueAndResolvable() {
    var ids = RuleCatalog.entries().stream().map(RuleCatalog.Entry::ruleId).toList();

    assertFalse(ids.isEmpty());
    assertTrue(ids.size() == new HashSet<>(ids).size(), "duplicate rule id must fail catalog construction");
    for (String id : ids) assertNotNull(RuleCatalog.find(id), "missing index entry: " + id);
  }

  @Test
  void catalogDoesNotExposeRulePrefixesThatNeverProduceFindings() {
    var ids = RuleCatalog.entries().stream().map(RuleCatalog.Entry::ruleId)
        .collect(java.util.stream.Collectors.toSet());

    assertFalse(ids.contains("ZH-01"));
    assertFalse(ids.contains("EN-01"));
    assertFalse(ids.contains("TOC-01"));
    assertFalse(ids.contains("EN-TOC-01"));
    assertFalse(ids.contains("REF-01"));
    assertFalse(ids.contains("END-01"));
  }

  @Test
  void catalogGroupsUseOnlyKnownRuleModules() {
    for (RuleCatalog.Entry entry : RuleCatalog.entries()) {
      assertFalse(entry.module().isBlank());
      assertFalse(entry.scope().isBlank());
      assertFalse(entry.evidence().isBlank());
    }
  }

  @Test
  void includesReferenceTitleAlignmentProducedByNamedSectionChecks() {
    var entry = RuleCatalog.entries().stream()
        .filter(candidate -> candidate.ruleId().equals("REF-01-ALIGNMENT"))
        .findFirst().orElseThrow();

    assertTrue(entry.module().equals("参考文献"));
    assertTrue(entry.scope().equals("参考文献"));
    assertFalse(entry.spec().description().isBlank());
  }

  @Test
  void exposesOnlyImplementedAppendixAndFigureRules() {
    var ids = RuleCatalog.entries().stream().map(RuleCatalog.Entry::ruleId).collect(java.util.stream.Collectors.toSet());

    assertFalse(ids.contains("FIG-04"));
    assertFalse(ids.contains("APP-02"));
    assertTrue(ids.contains("APP-02-BLANK"));
    assertFalse(ids.contains("APP-02-LINE"));
    assertFalse(ids.contains("END-01-LINE"));
    assertTrue(ids.contains("APP-02-SPACING"));
    assertTrue(ids.contains("APP-02-FONT"));
    assertTrue(ids.contains("APP-02-SIZE"));
    assertTrue(ids.contains("APP-02-ALIGNMENT"));
  }

  @Test
  void reportsActualCoverContentsAndHeadingCoverageBoundaries() {
    var cover = RuleCatalog.find("COVER-01-FONT");
    var contents = RuleCatalog.find("TOC-01-SPACING");
    var heading = RuleCatalog.find("HEAD-01-SPACING");
    var table = RuleCatalog.find("TAB-02");
    var appendix = RuleCatalog.find("APP-01");

    assertTrue(cover.inspectionLimitation().contains("段前、段后和行距未检查"));
    assertTrue(contents.description().contains("条目字体字号行距"));
    assertTrue(contents.description().contains("一级条目顶格"));
    assertTrue(contents.description().contains("不检查目录首行缩进"));
    assertTrue(contents.description().contains("不强制二、三级条目整体左缩进"));
    assertTrue(contents.description().contains("标题对应关系和顺序"));
    assertTrue(contents.description().contains("目录标题"));
    assertTrue(contents.inspectionLimitation().contains("最终页码显示"));
    assertTrue(heading.description().contains("一级至三级标题"));
    assertTrue(heading.description().contains("章标题后空行"));
    assertTrue(heading.inspectionLimitation().contains("章节是否实际另起一页无法仅凭 XML 确认"));
    assertTrue(table.inspectionLimitation().contains("单元格字体、字号、对齐、列宽、行高"));
    assertFalse(appendix.inspectionLimitation().contains("附录标题后的空行未检查"));
  }

  @Test
  void catalogsFirstLevelHeadingAlignmentAsAHardRule() {
    var alignment = RuleCatalog.find("HEAD-01-ALIGNMENT");

    assertEquals("正文标题", alignment.module());
    assertTrue(alignment.annotationSupported());
    assertTrue(alignment.description().contains("居中"));
  }

  @Test
  void headerRulesAreHardChecksAndCanBeAnnotated() {
    var header = RuleCatalog.find("HEADER-01-FONT");

    assertTrue(header.annotationSupported());
    assertTrue(header.annotationLimitation().isBlank());
    assertTrue(header.scope().contains("封面豁免"));
    assertTrue(header.scope().contains("摘要至附录"));
    assertTrue(header.description().contains("封面以外各节"));
  }

  @Test
  void groupsCaptionRulesForBothFiguresAndTables() {
    var font = RuleCatalog.find("CAPTION-FONT");
    var size = RuleCatalog.find("CAPTION-SIZE");

    assertEquals("图表题", font.module());
    assertEquals("图题和表题", font.scope());
    assertEquals("图表题", size.module());
    assertEquals("图题和表题", size.scope());
  }
}
