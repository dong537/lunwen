package cn.luwneshacha.thesis.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import cn.luwneshacha.thesis.document.HeaderModel;
import cn.luwneshacha.thesis.document.ImageModel;
import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.RunModel;
import cn.luwneshacha.thesis.document.SectionModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HeaderContextSourceTest {
  private static final SourceAddress HEADER = new SourceAddress("word/header1.xml", List.of(0), 0, 0);

  @Test
  void sharedCoverAndAbstractHeaderKeepsEvidenceButUsesAbstractContext() {
    var findings = check(List.of(section(1, false, false), section(3, true, false)),
        header(Map.of(HeaderModel.Type.DEFAULT, List.of(0, 1))), List.of(),
        "本科生毕业设计（论文）", "2026年6月", "摘    要", "摘要正文");

    assertEquals(1, findings.size());
    assertEquals(HEADER, findings.getFirst().source());
    assertEquals(source(2, 6), findings.getFirst().contextSource());
  }

  @Test
  void sameSectionCoverAndAbstractContextStartsAfterCover() {
    var findings = check(List.of(section(3, true, true)),
        header(Map.of(HeaderModel.Type.DEFAULT, List.of(0))), List.of(),
        "本科生毕业设计（论文）", "2026年6月", "ABSTRACT", "Abstract text");

    assertEquals(1, findings.size());
    assertEquals(HEADER, findings.getFirst().source());
    assertEquals(source(2, 8), findings.getFirst().contextSource());
  }

  @Test
  void imageCoverInheritedHeaderContextStartsInAbstractSection() {
    var image = new ImageModel(source(0, 0), null, null, 7560000, 10692000, true, true, null);
    var findings = check(List.of(section(1, false, false), section(3, true, false)),
        header(Map.of(HeaderModel.Type.DEFAULT, List.of(0, 1))), List.of(image),
        "", "", "摘    要", "摘要正文");

    assertEquals(1, findings.size());
    assertEquals(source(2, 6), findings.getFirst().contextSource());
  }

  @Test
  void emptyTerminalSectionUsesItsOwnParagraphNotPreviousAcknowledgement() {
    var findings = check(List.of(section(0, false, false), section(1, true, false)),
        header(Map.of(HeaderModel.Type.DEFAULT, List.of(1))), List.of(), "致谢正文", "");
    var headerFinding = findings.stream().filter(f -> f.ruleId().equals("HEADER-01-TEXT")).findFirst().orElseThrow();

    assertEquals(HEADER, headerFinding.source());
    assertEquals(source(1, 0), headerFinding.contextSource());
  }

  @Test
  void coverFirstVariantDoesNotDetermineLaterDefaultHeaderContext() {
    var findings = check(List.of(section(1, false, true), section(3, true, false)),
        header(Map.of(HeaderModel.Type.FIRST, List.of(0), HeaderModel.Type.DEFAULT, List.of(1))),
        List.of(), "本科生毕业设计（论文）", "2026年6月", "摘要", "摘要正文");

    assertEquals(1, findings.size());
    assertEquals(source(2, 2), findings.getFirst().contextSource());
  }

  @Test
  void firstVariantInLaterNonCoverSectionUsesThatSectionsStart() {
    var findings = check(List.of(section(1, false, false), section(3, true, true)),
        header(Map.of(HeaderModel.Type.FIRST, List.of(1))), List.of(),
        "摘要", "摘要正文", "第一章  绪论", "正文");
    var headerFinding = findings.stream().filter(f -> f.ruleId().equals("HEADER-01-TEXT")).findFirst().orElseThrow();

    assertEquals(source(2, 7), headerFinding.contextSource());
  }

  @Test
  void existingConstructorDoesNotInventContext() {
    var finding = new CheckResult("HEADER-01-TEXT", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "expected", "actual", "HEADER_TEXT_MISMATCH", HEADER);

    assertNull(finding.contextSource());
  }

  private List<CheckResult> check(List<SectionModel> sections, HeaderModel header,
      List<ImageModel> images, String... texts) {
    var paragraphs = new ArrayList<ParagraphModel>();
    for (int i = 0; i < texts.length; i++) {
      SourceAddress source = source(i, texts[i].length());
      paragraphs.add(new ParagraphModel(List.of(new RunModel(texts[i], "Times New Roman", "宋体",
          null, 24, false, source)), null, null, null, null, null, null, source, null, null, "left", null));
    }
    var document = new ParsedDocument("sha", paragraphs, sections, List.of(), List.of(), List.of(),
        List.of(), images, List.of(), List.of(), List.of(header));
    return new HeaderRuleModule().check(document, RuleSet.schoolV1());
  }

  private HeaderModel header(Map<HeaderModel.Type, List<Integer>> sections) {
    return new HeaderModel(HEADER, "", true,
        List.of(new HeaderModel.RunFormatting("黑体", "黑体", 21, HEADER)), sections);
  }

  private SectionModel section(int index, boolean terminal, boolean firstPage) {
    return new SectionModel(1474, 1474, 1474, 1474, 0, source(index, 0), null, false, null, false,
        terminal, false, firstPage, false);
  }

  private SourceAddress source(int index, int length) {
    return new SourceAddress("word/document.xml", List.of(0, index), 0, length);
  }
}
