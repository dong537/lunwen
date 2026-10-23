package cn.luwneshacha.thesis.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.luwneshacha.thesis.inspection.RuleCatalog;
import org.junit.jupiter.api.Test;

class RuleSummaryDescriptionsTest {
  @Test
  void providesChineseRequirementsForEveryRegisteredRule() {
    for (var entry : RuleCatalog.entries()) {
      String requirement = RuleSummaryDescriptions.requirement(entry.ruleId());
      assertFalse(requirement.isBlank(), entry.ruleId());
      assertFalse(requirement.equals("其他格式要求"), entry.ruleId());
      assertTrue(requirement.matches(".*[\\p{IsHan}].*"), entry.ruleId());
      assertFalse(requirement.contains(entry.ruleId()), entry.ruleId());
      assertFalse(requirement.contains("XML"), entry.ruleId());
      assertFalse(requirement.contains("twips"), entry.ruleId());
    }
  }

  @Test
  void namesTheSpecificRequirementWithoutIncludingAnObservedProblem() {
    assertEquals("正文采用 1.5 倍行距", RuleSummaryDescriptions.requirement("BODY-LINE"));
    assertEquals("正文每段首行缩进 2 字符", RuleSummaryDescriptions.requirement("BODY-INDENT"));
    assertEquals("图题和表题采用五号字", RuleSummaryDescriptions.requirement("CAPTION-SIZE"));
    assertEquals("参考文献编号连续、不重复，编号后空 1 个普通半角空格", RuleSummaryDescriptions.requirement("REF-07"));
    assertEquals("致谢标题两字之间空 4 个普通半角空格", RuleSummaryDescriptions.requirement("END-01-SPACING"));
    assertEquals("参考文献标题各字之间不加空格", RuleSummaryDescriptions.requirement("REF-01-SPACING"));
    assertEquals("英文关键词仅 Keywords 标签加粗", RuleSummaryDescriptions.requirement("EN-03-BOLD"));
  }

  @Test
  void statesConditionalRulesWithoutCreatingExtraRequirements() {
    assertEquals("一级目录条目顶格，不强制二、三级条目的缩进方式或数值",
        RuleSummaryDescriptions.requirement("TOC-ENTRY-INDENT"));
    assertEquals("出现附录时，附录按大写字母连续编号，图、表和公式编号冠以对应附录字母",
        RuleSummaryDescriptions.requirement("APP-01"));
    assertEquals("表中参数标明量和单位；相同单位可集中标注，不同单位可分别标注",
        RuleSummaryDescriptions.requirement("TAB-04"));
  }

  @Test
  void returnsAStableChineseLabelForMissingAndUnregisteredRules() {
    for (String id : new String[] {null, "", " ", "UNKNOWN-论文原文", "BODY-LINE<script>"}) {
      assertEquals("其他格式要求", RuleSummaryDescriptions.requirement(id));
    }
  }
}


