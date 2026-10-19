package cn.luwneshacha.thesis.rules;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.ImageModel;
import cn.luwneshacha.thesis.document.RunModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import cn.luwneshacha.thesis.document.TableModel;
import java.util.List;
import org.junit.jupiter.api.Test;

class RequiredSectionRuleCheckerTest {
  @Test
  void reportsMissingRequiredSectionsAndCoverFieldsForAThesisDocument() {
    var document = document(List.of(
        "本科生毕业设计（论文）", "题目：论文题目", "学生姓名：张三", "院系：信息工程学院",
        "专业年级：计算机科学与技术", "指导教师：李四", "2026年6月",
        "摘    要", "摘要内容", "ABSTRACT", "English abstract", "Keywords: format; check",
        "第一章 绪论", "参考文献"));

    var findings = new RequiredSectionRuleChecker().check(document);

    assertTrue(findings.stream().anyMatch(result -> result.expected().equals("学号")
        && result.reasonCode().equals("COVER_FIELD_MISSING")));
    assertTrue(findings.stream().anyMatch(result -> result.expected().equals("学校名称")
        && result.reasonCode().equals("COVER_FIELD_MISSING")));
    assertTrue(findings.stream().anyMatch(result -> result.expected().equals("中文关键词")
        && result.reasonCode().equals("REQUIRED_SECTION_MISSING")));
    assertTrue(findings.stream().anyMatch(result -> result.expected().equals("目录")
        && result.reasonCode().equals("REQUIRED_SECTION_MISSING")));
    assertTrue(findings.stream().anyMatch(result -> result.expected().equals("致谢")
        && result.reasonCode().equals("REQUIRED_SECTION_MISSING")));
    assertFalse(findings.stream().anyMatch(result -> result.expected().equals("附录")
        && result.reasonCode().equals("REQUIRED_SECTION_MISSING")), findings.toString());
  }

  @Test
  void acceptsACompleteThesisStructureAndCoverFieldSet() {
    var document = document(List.of(
        "山东石油化工学院", "本科生毕业设计（论文）", "题目：毕业论文", "学生姓名：张三", "学号：20260001",
        "院系：信息工程学院", "专业年级：计算机科学与技术", "指导教师：李四", "2026年6月",
        "摘    要", "关键词：格式；检查", "ABSTRACT", "Keywords: format; check", "目 录",
        "第一章 绪论", "第五章 结论", "参考文献", "致谢", "附    录", "附录A 名词术语及缩略词"));

    assertTrue(new RequiredSectionRuleChecker().check(document).isEmpty());
  }

  @Test
  void rejectsConclusionChapterOtherThanChapterFive() {
    var document = document(List.of(
        "山东石油化工学院", "本科生毕业设计（论文）", "题目：毕业论文", "学生姓名：张三", "学号：20260001",
        "院系：信息工程学院", "专业年级：计算机科学与技术", "指导教师：李四", "2026年6月",
        "摘要", "关键词：格式；检查", "ABSTRACT", "Keywords: format; check", "目录",
        "第一章 绪论", "第四章 结论", "参考文献", "致谢", "附录", "附录A 名词术语及缩略词"));

    var findings = new RequiredSectionRuleChecker().check(document);

    assertTrue(findings.stream().anyMatch(result ->
        "CONCLUSION_CHAPTER_MISMATCH".equals(result.reasonCode())
            && result.judgement() == Judgement.FAIL), findings.toString());
  }

  @Test
  void reviewsUnnumberedConclusionBecauseItsChapterNumberCannotBeProved() {
    var document = documentWithStyledConclusion("Heading1");

    var findings = new RequiredSectionRuleChecker().check(document);

    assertTrue(findings.stream().anyMatch(result ->
        "CONCLUSION_CHAPTER_UNCONFIRMED".equals(result.reasonCode())
            && result.judgement() == Judgement.REVIEW), findings.toString());
  }

  @Test
  void appendixTextInsideATableDoesNotActivateOptionalAppendixChecks() {
    var texts = List.of(
        "山东石油化工学院", "本科生毕业设计（论文）", "题目：毕业论文", "学生姓名：张三", "学号：20260001",
        "院系：信息工程学院", "专业年级：计算机科学与技术", "指导教师：李四", "2026年6月",
        "摘    要", "关键词：格式；检查", "ABSTRACT", "Keywords: format; check", "目 录",
        "第一章 绪论", "第五章 结论", "参考文献", "致谢", "附录A 名词术语及缩略词");
    var paragraphs = new java.util.ArrayList<ParagraphModel>();
    int appendixIndex = texts.size() - 1;
    for (int index = 0; index < texts.size(); index++) {
      String value = texts.get(index);
      List<Integer> path = index == appendixIndex ? List.of(0, index, 0, 0) : List.of(0, index);
      var source = new SourceAddress("word/document.xml", path, 0, value.length());
      paragraphs.add(new ParagraphModel(List.of(new RunModel(value, null, null, null,
          24, false, source)), null, null, null, null, null, null, source, null, null, null));
    }
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(0, appendixIndex), 0, 0),
        null, null, 1, 1, false, false, false, false, false, false);
    var document = new ParsedDocument("source", paragraphs, List.of(), List.of(),
        List.of(table), List.of(), List.of(), List.of());

    var findings = new RequiredSectionRuleChecker().check(document);

    assertFalse(findings.stream().anyMatch(result -> result.expected().startsWith("附录")),
        findings.toString());
  }

  @Test
  void requiresALetteredAppendixSectionAfterTheMainAppendixTitle() {
    var document = document(List.of(
        "山东石油化工学院", "本科生毕业设计（论文）", "题目：毕业论文", "学生姓名：张三", "学号：20260001",
        "院系：信息工程学院", "专业年级：计算机科学与技术", "指导教师：李四", "2026年6月",
        "摘    要", "关键词：格式；检查", "ABSTRACT", "Keywords: format; check", "目 录",
        "第一章 绪论", "第五章 结论", "参考文献", "致谢", "附    录"));

    var findings = new RequiredSectionRuleChecker().check(document);

    assertTrue(findings.stream().anyMatch(result -> result.expected().equals("附录分项")
        && result.reasonCode().equals("APPENDIX_SECTION_MISSING")), findings.toString());
  }

  @Test
  void reportsMissingMainAppendixTitleWhenLetteredAppendixExists() {
    var document = document(List.of(
        "山东石油化工学院", "本科生毕业设计（论文）", "题目：毕业论文", "学生姓名：张三", "学号：20260001",
        "院系：信息工程学院", "专业年级：计算机科学与技术", "指导教师：李四", "2026年6月",
        "摘    要", "关键词：格式；检查", "ABSTRACT", "Keywords: format; check", "目录",
        "第一章 绪论", "第五章 结论", "参考文献", "致谢", "附录A 名词术语及缩略词"));

    var findings = new RequiredSectionRuleChecker().check(document);

    assertTrue(findings.stream().anyMatch(result -> result.expected().equals("附录总标题")
        && result.reasonCode().equals("APPENDIX_TITLE_MISSING")), findings.toString());
  }

  @Test
  void recognizesFullWidthColonForEnglishKeywords() {
    var document = document(List.of(
        "山东石油化工学院", "本科生毕业设计（论文）", "题目：毕业论文", "学生姓名：张三", "学号：20260001",
        "院系：信息工程学院", "专业年级：计算机科学与技术", "指导教师：李四", "2026年6月",
        "摘    要", "关键词：格式；检查", "ABSTRACT", "Keywords：format; check", "目录",
        "第一章 绪论", "参考文献", "致谢"));

    var findings = new RequiredSectionRuleChecker().check(document);

    assertTrue(findings.stream().noneMatch(result -> result.expected().equals("英文关键词")
        && result.reasonCode().equals("REQUIRED_SECTION_MISSING")), findings.toString());
  }

  @Test
  void reportsCoverLabelsThatHaveNoValue() {
    var document = document(List.of(
        "山东石油化工学院", "本科生毕业设计（论文）", "题目：毕业论文", "学生姓名：", "学号：",
        "院系：信息工程学院", "专业年级：计算机科学与技术", "指导教师：李四", "2026年6月",
        "摘    要", "关键词：格式；检查", "ABSTRACT", "Keywords: format; check", "目录",
        "第一章 绪论", "参考文献", "致谢"));

    var findings = new RequiredSectionRuleChecker().check(document);

    assertTrue(findings.stream().anyMatch(result -> result.expected().equals("学生姓名")
        && result.reasonCode().equals("COVER_FIELD_MISSING")));
    assertTrue(findings.stream().anyMatch(result -> result.expected().equals("学号")
        && result.reasonCode().equals("COVER_FIELD_MISSING")));
  }

  @Test
  void doesNotClaimCoverSchoolNameIsMissingWhenCoverContainsAnImage() {
    var texts = List.of("", "本科生毕业设计（论文）", "题目：毕业论文", "学生姓名：张三", "学号：20260001",
        "院系：信息工程学院", "专业年级：计算机科学与技术", "指导教师：李四", "2026年6月", "摘要",
        "关键词：格式；检查", "ABSTRACT", "Keywords: format; check", "目录", "第一章 绪论",
        "第五章 结论", "参考文献", "致谢", "附    录", "附录A 名词术语及缩略词");
    var paragraphs = new java.util.ArrayList<ParagraphModel>();
    for (int index = 0; index < texts.size(); index++) {
      String text = texts.get(index);
      var source = new SourceAddress("word/document.xml", List.of(0, index), 0, text.length());
      paragraphs.add(new ParagraphModel(List.of(new RunModel(text, null, null, null,
          24, false, source)), null, null, null, null, null, source, null));
    }
    var imageSource = paragraphs.get(0).source();
    var image = new ImageModel(imageSource, null, null, 1000, 500, true, true, null);
    var document = new ParsedDocument("source", paragraphs, List.of(), List.of(), List.of(), List.of(),
        List.of(), List.of(image), List.of(), List.of(), List.of());

    var findings = new RequiredSectionRuleChecker().check(document);

    assertTrue(findings.stream().noneMatch(result -> result.expected().equals("学校名称")
        && result.judgement() == Judgement.FAIL), findings.toString());
    assertTrue(findings.stream().anyMatch(result -> result.expected().equals("学校名称")
        && result.reasonCode().equals("COVER_SCHOOL_NAME_IMAGE_UNCONFIRMED")
        && result.judgement() == Judgement.REVIEW), findings.toString());
  }

  @Test
  void requiresCoverDateAsIndependentTextWithoutALabel() {
    var document = document(List.of(
        "山东石油化工学院", "本科生毕业设计（论文）", "题目：毕业论文", "学生姓名：张三", "学号：20260001",
        "院系：信息工程学院", "专业年级：计算机科学与技术", "指导教师：李四", "日期：2026年6月",
        "摘    要", "关键词：格式；检查", "ABSTRACT", "Keywords: format; check", "目录",
        "第一章 绪论", "第五章 结论", "参考文献", "致谢", "附录A 名词术语及缩略词"));

    var findings = new RequiredSectionRuleChecker().check(document);

    assertTrue(findings.stream().anyMatch(result -> result.expected().equals("日期")
        && result.reasonCode().equals("COVER_FIELD_MISSING")), findings.toString());
  }

  @Test
  void acceptsIndependentDateTextInsideACoverTable() {
    var texts = List.of(
        "山东石油化工学院", "本科生毕业设计（论文）", "题目：毕业论文", "学生姓名：张三", "学号：20260001",
        "院系：信息工程学院", "专业年级：计算机科学与技术", "指导教师：李四", "2026年6月",
        "摘要", "关键词：格式；检查", "ABSTRACT", "Keywords: format; check", "目录",
        "第一章 绪论", "第五章 结论", "参考文献", "致谢", "附    录", "附录A 名词术语及缩略词");
    var findings = new RequiredSectionRuleChecker().check(documentWithTableParagraph(texts, 8));

    assertFalse(findings.stream().anyMatch(result -> result.expected().equals("日期")
        && result.reasonCode().equals("COVER_FIELD_MISSING")), findings.toString());
  }

  @Test
  void acceptsCoverFieldsStoredInCoverTableCells() {
    var texts = List.of(
        "山东石油化工学院", "本科生毕业设计（论文）", "题目：毕业论文", "学生姓名：张三", "学号：20260001",
        "院系：信息工程学院", "专业年级：计算机科学与技术", "指导教师：李四", "2026年6月",
        "摘要", "关键词：格式；检查", "ABSTRACT", "Keywords: format; check", "目录",
        "第一章 绪论", "第五章 结论", "参考文献", "致谢", "附    录", "附录A 名词术语及缩略词");
    var paragraphs = new java.util.ArrayList<ParagraphModel>();
    for (int index = 0; index < texts.size(); index++) {
      String value = texts.get(index);
      boolean inCoverTable = index >= 2 && index <= 7;
      List<Integer> path = inCoverTable
          ? List.of(0, 2, 0, index - 2) : List.of(0, index);
      var source = new SourceAddress("word/document.xml", path, 0, value.length());
      paragraphs.add(new ParagraphModel(List.of(new RunModel(value, "Times New Roman", "仿宋", null,
          32, true, source)), null, null, null, null, null, source, null));
    }
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(0, 2), 0, 0),
        null, null, 6, 1, false, false, false, false, false, false);
    var document = new ParsedDocument("source", paragraphs, List.of(), List.of(),
        List.of(table), List.of(), List.of(), List.of());

    var findings = new RequiredSectionRuleChecker().check(document);

    assertFalse(findings.stream().anyMatch(result -> result.ruleId().equals("COVER-05")
        && result.reasonCode().equals("COVER_FIELD_MISSING")), findings.toString());
  }

  @Test
  void bodyTextCannotFillMissingCoverFields() {
    var document = document(List.of(
        "山东石油化工学院", "本科生毕业设计（论文）", "题目：毕业论文", "学号：20260001",
        "院系：信息工程学院", "专业年级：计算机科学与技术", "指导教师：李四",
        "摘    要", "摘要内容", "关键词：格式；检查", "ABSTRACT", "English abstract",
        "Keywords: format; check", "目录", "第一章 绪论", "学生姓名：张三", "2026年6月",
        "参考文献", "致谢", "结论"));

    var findings = new RequiredSectionRuleChecker().check(document);

    assertTrue(findings.stream().anyMatch(result -> result.expected().equals("学生姓名")
        && result.reasonCode().equals("COVER_FIELD_MISSING")), findings.toString());
    assertTrue(findings.stream().anyMatch(result -> result.expected().equals("日期")
        && result.reasonCode().equals("COVER_FIELD_MISSING")), findings.toString());
  }

  @Test
  void reportsConclusionAsMissingForAThesisDocument() {
    var document = document(List.of(
        "山东石油化工学院", "本科生毕业设计（论文）", "题目：毕业论文", "学生姓名：张三", "学号：20260001",
        "院系：信息工程学院", "专业年级：计算机科学与技术", "指导教师：李四", "2026年6月",
        "摘要", "关键词：格式；检查", "ABSTRACT", "Keywords: format; check", "目录",
        "第一章 绪论", "参考文献", "致谢"));

    var findings = new RequiredSectionRuleChecker().check(document);

    assertTrue(findings.stream().anyMatch(result -> result.expected().equals("结论")
        && result.reasonCode().equals("REQUIRED_SECTION_MISSING")), findings.toString());
  }

  @Test
  void acceptsUnnumberedConclusionAndOutlookWhenItHasHeadingStyleEvidence() {
    var document = documentWithStyledConclusion("Heading1");

    var findings = new RequiredSectionRuleChecker().check(document);

    assertFalse(findings.stream().anyMatch(result -> result.expected().equals("结论")
        && result.reasonCode().equals("REQUIRED_SECTION_MISSING")), findings.toString());
  }

  @Test
  void doesNotTreatUnnumberedConclusionAndOutlookAsARequiredHeadingWithoutEvidence() {
    var document = document(List.of(
        "山东石油化工学院", "本科生毕业设计（论文）", "题目：毕业论文", "学生姓名：张三", "学号：20260001",
        "院系：信息工程学院", "专业年级：计算机科学与技术", "指导教师：李四", "2026年6月",
        "摘要", "关键词：格式；检查", "ABSTRACT", "Keywords: format; check", "目录",
        "第一章 绪论", "结论与展望", "参考文献", "致谢", "附录A 名词术语及缩略词"));

    var findings = new RequiredSectionRuleChecker().check(document);

    assertTrue(findings.stream().anyMatch(result -> result.expected().equals("结论")
        && result.reasonCode().equals("REQUIRED_SECTION_MISSING")), findings.toString());
  }

  @Test
  void missingSectionFindingAnchorsToTheBodyChapterInsteadOfATableChapter() {
    var texts = List.of(
        "山东石油化工学院", "本科生毕业设计（论文）", "题目：毕业论文", "学生姓名：张三", "学号：20260001",
        "院系：信息工程学院", "专业年级：计算机科学与技术", "指导教师：李四", "2026年6月",
        "摘要", "关键词：格式；检查", "ABSTRACT", "Keywords: format; check", "目录",
        "第一章 表格中的标题", "第一章 绪论", "第五章 结论", "参考文献");
    var document = documentWithTableParagraph(texts, 14);

    var finding = new RequiredSectionRuleChecker().check(document).stream()
        .filter(result -> result.expected().equals("致谢")
            && result.reasonCode().equals("REQUIRED_SECTION_MISSING"))
        .findFirst().orElseThrow();

    assertTrue(finding.source().equals(document.paragraphs().get(15).source()), finding.toString());
  }

  @Test
  void missingBodyFindingDoesNotFallBackToATableParagraph() {
    var texts = List.of(
        "第一章 表格中的标题", "山东石油化工学院", "本科生毕业设计（论文）", "题目：毕业论文",
        "学生姓名：张三", "学号：20260001", "院系：信息工程学院", "专业年级：计算机科学与技术",
        "指导教师：李四", "2026年6月", "摘要", "关键词：格式；检查", "ABSTRACT",
        "Keywords: format; check", "目录", "参考文献", "致谢", "附录A 名词术语及缩略词");
    var document = documentWithTableParagraph(texts, 0);

    var finding = new RequiredSectionRuleChecker().check(document).stream()
        .filter(result -> result.expected().equals("正文")
            && result.reasonCode().equals("REQUIRED_SECTION_MISSING"))
        .findFirst().orElseThrow();

    assertTrue(finding.source().equals(document.paragraphs().get(1).source()), finding.toString());
  }

  @Test
  void classificationReviewUsesANonTableAnchor() {
    var texts = List.of("表格内容", "项目说明", "这是一个简短文档");
    var document = documentWithTableParagraph(texts, 0);

    var finding = new RequiredSectionRuleChecker().check(document).stream()
        .filter(result -> result.reasonCode().equals("THESIS_DOCUMENT_CLASSIFICATION_REVIEW"))
        .findFirst().orElseThrow();

    assertTrue(finding.source().equals(document.paragraphs().get(1).source()), finding.toString());
  }

  @Test
  void tableTextAfterAnAppendixDoesNotMoveTheEndMatterBoundary() {
    var texts = List.of(
        "山东石油化工学院", "本科生毕业设计（论文）", "题目：毕业论文", "学生姓名：张三", "学号：20260001",
        "院系：信息工程学院", "专业年级：计算机科学与技术", "指导教师：李四", "2026年6月",
        "摘要", "关键词：格式；检查", "ABSTRACT", "Keywords: format; check", "目录",
        "第一章 绪论", "第五章 结论", "参考文献", "致谢", "附录A 名词术语及缩略词", "参考文献");
    var document = documentWithTableParagraph(texts, 19);

    var findings = new RequiredSectionRuleChecker().check(document);

    assertFalse(findings.stream().anyMatch(result -> result.expected().equals("附录")
        && result.reasonCode().equals("REQUIRED_SECTION_MISSING")), findings.toString());
  }

  @Test
  void reviewsVeryShortDocumentsWhenThesisTypeCannotBeDetermined() {
    var document = document(List.of("项目说明", "这是一个简短文档"));

    var findings = new RequiredSectionRuleChecker().check(document);

    assertTrue(findings.stream().anyMatch(result -> result.judgement() == Judgement.REVIEW
        && result.reasonCode().equals("THESIS_DOCUMENT_CLASSIFICATION_REVIEW")), findings.toString());
  }

  @Test
  void acceptsConclusionFromAutomaticChapterFiveNumbering() {
    var document = documentWithAutomaticChapters("5", true);

    var findings = new RequiredSectionRuleChecker().check(document);

    assertFalse(findings.stream().anyMatch(result -> result.reasonCode().equals("REQUIRED_SECTION_MISSING")
        && (result.expected().equals("正文") || result.expected().equals("结论"))), findings.toString());
    assertFalse(findings.stream().anyMatch(result -> result.reasonCode().startsWith("CONCLUSION_CHAPTER_")),
        findings.toString());
  }

  @Test
  void rejectsConclusionFromAutomaticChapterSixNumbering() {
    var document = documentWithAutomaticChapters("6", true);

    var findings = new RequiredSectionRuleChecker().check(document);

    assertTrue(findings.stream().anyMatch(result -> result.reasonCode().equals("CONCLUSION_CHAPTER_MISMATCH")
        && result.judgement() == Judgement.FAIL), findings.toString());
  }

  @Test
  void doesNotTreatGeneratedContentsChapterAsTheBody() {
    var document = documentWithAutomaticChapters("5", false);

    var regions = new DocumentRegionClassifier();
    assertTrue(document.paragraphs().get(14).generatedTocField());
    assertFalse(regions.isBodyChapterStart(document.paragraphs().get(14), document));
    assertTrue(document.paragraphs().get(15).generatedTocField());
    assertFalse(regions.isBodyChapterStart(document.paragraphs().get(15), document));

    var findings = new RequiredSectionRuleChecker().check(document);

    assertTrue(findings.stream().anyMatch(result -> result.expected().equals("正文")
        && result.reasonCode().equals("REQUIRED_SECTION_MISSING")), findings.toString());
  }

  private static ParsedDocument document(List<String> texts) {
    var paragraphs = new java.util.ArrayList<ParagraphModel>();
    for (int index = 0; index < texts.size(); index++) {
      String text = texts.get(index);
      var source = new SourceAddress("word/document.xml", List.of(0, index), 0, text.length());
      paragraphs.add(new ParagraphModel(List.of(new RunModel(text, null, null, null,
          24, false, source)), null, null, null, null, null, null, source,
          null, text.matches("^第[一二三四五六七八九十百千零〇]+章.*") ? 0 : null, null));
    }
    return new ParsedDocument("source", paragraphs, List.of(), List.of());
  }

  private static ParsedDocument documentWithTableParagraph(List<String> texts, int tableIndex) {
    var paragraphs = new java.util.ArrayList<ParagraphModel>();
    for (int index = 0; index < texts.size(); index++) {
      String text = texts.get(index);
      List<Integer> path = index == tableIndex
          ? List.of(0, index, 0, 0) : List.of(0, index);
      var source = new SourceAddress("word/document.xml", path, 0, text.length());
      paragraphs.add(new ParagraphModel(List.of(new RunModel(text, null, null, null,
          24, false, source)), null, null, null, null, null, source, null));
    }
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(0, tableIndex), 0, 0),
        null, null, 1, 1, false, false, false, false, false, false);
    return new ParsedDocument("source", paragraphs, List.of(), List.of(),
        List.of(table), List.of(), List.of(), List.of());
  }

  private static ParsedDocument documentWithStyledConclusion(String styleId) {
    var base = document(List.of(
        "山东石油化工学院", "本科生毕业设计（论文）", "题目：毕业论文", "学生姓名：张三", "学号：20260001",
        "院系：信息工程学院", "专业年级：计算机科学与技术", "指导教师：李四", "2026年6月",
        "摘要", "关键词：格式；检查", "ABSTRACT", "Keywords: format; check", "目录",
        "第一章 绪论", "结论与展望", "参考文献", "致谢", "附录A 名词术语及缩略词"));
    var paragraphs = new java.util.ArrayList<>(base.paragraphs());
    SourceAddress source = paragraphs.get(15).source();
    var heading = new ParagraphModel(List.of(new RunModel("结论与展望", null, null, null,
        32, true, source)), null, null, null, null, 360, "auto", source,
        null, 0, "center", null, null, null, false, null, null, false,
        false, false, false, null, null, styleId);
    paragraphs.set(15, heading);
    return new ParsedDocument("source", paragraphs, List.of(), List.of());
  }

  private static ParsedDocument documentWithAutomaticChapters(String conclusionNumber,
      boolean includeBody) {
    var base = document(List.of(
        "山东石油化工学院", "本科生毕业设计（论文）", "题目：毕业论文", "学生姓名：张三", "学号：20260001",
        "院系：信息工程学院", "专业年级：计算机科学与技术", "指导教师：李四", "2026年6月",
        "摘要", "关键词：格式；检查", "ABSTRACT", "Keywords: format; check", "目录",
        "第一章 绪论", "第五章 结论", "参考文献", "致谢", "附录A 名词术语及缩略词"));
    var paragraphs = new java.util.ArrayList<>(base.paragraphs());
    if (includeBody) {
      paragraphs.set(14, automaticChapter(paragraphs.get(14), "绪论", "1", false));
      paragraphs.set(15, automaticChapter(paragraphs.get(15), "结论", conclusionNumber, false));
    } else {
      paragraphs.set(14, automaticChapter(paragraphs.get(14), "绪论", "1", true));
      paragraphs.set(15, automaticChapter(paragraphs.get(15), "结论", conclusionNumber, true));
    }
    return new ParsedDocument("source", paragraphs, List.of(), List.of());
  }

  private static ParagraphModel automaticChapter(ParagraphModel paragraph, String text, String label,
      boolean generatedToc) {
    var run = new RunModel(text, null, null, null, 24, false, paragraph.source());
    return new ParagraphModel(List.of(run), paragraph.firstLineIndentTwips(), paragraph.leftIndentTwips(),
        paragraph.leftIndentChars(), paragraph.rightIndentTwips(), paragraph.lineTwips(), paragraph.lineRule(),
        paragraph.source(), paragraph.firstLineChars(), 0, "center", label, 1, 0, generatedToc,
        paragraph.hangingIndentTwips(), paragraph.hangingIndentChars(), true,
        paragraph.firstLineEvidenceInvalid(), paragraph.hangingEvidenceInvalid(), paragraph.leftEvidenceInvalid(),
        paragraph.beforeSpacingTwips(), paragraph.afterSpacingTwips(), "Heading1", paragraph.lineSpacingEvidenceInvalid());
  }
}
