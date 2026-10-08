package cn.luwneshacha.thesis.document;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReferenceParserTest {
  private static final SourceAddress A = new SourceAddress("word/document.xml", List.of(0), 0, 1);
  private static ParagraphModel p(String text) {
    return new ParagraphModel(List.of(new RunModel(text, null, null, null, 24, false, A)), null, null, null, null, null, A, null);
  }
  @Test
  void recognizesSupportedReferenceCategories() {
    var entries = new ReferenceParser().parse(List.of(p("参考文献"), p("[1] 作者. 书名[M]. 出版社"), p("[2] 作者. 文章[J]. 期刊"), p("[3] 未分类")));
    assertEquals(java.util.Arrays.asList("M", "J", null), entries.stream().map(ReferenceEntryModel::category).toList());
    assertTrue(entries.get(2).needsReview());
  }

  @Test
  void recognizesStandardAndOtherCategories() {
    var entries = new ReferenceParser().parse(List.of(
        p("References"),
        p("[1] Standard[S]. 2024."),
        p("[2] Regulation[Z]. 2024.")));
    assertEquals(java.util.Arrays.asList("S", "Z"), entries.stream().map(ReferenceEntryModel::category).toList());
    assertTrue(entries.stream().noneMatch(ReferenceEntryModel::needsReview));
  }

  @Test
  void recognizesElectronicResourceCategoriesWithAndWithoutSubtype() {
    var entries = new ReferenceParser().parse(List.of(
        p("References"),
        p("[1] Web page[OL]. 2024."),
        p("[2] Database[DB]. 2024."),
        p("[3] Online database[DB/OL]. 2024."),
        p("[4] Program[CP]. 2024."),
        p("[5] Download[DL]. 2024.")));

    assertEquals(java.util.Arrays.asList("OL", "DB", "DB/OL", "CP", "DL"),
        entries.stream().map(ReferenceEntryModel::category).toList());
    assertTrue(entries.stream().noneMatch(ReferenceEntryModel::needsReview));
  }

  @Test
  void extractsReferenceFieldsNeededForFormatChecks() {
    var entries = new ReferenceParser().parse(List.of(
        p("参考文献"),
        p("[1] 严蔚敏，吴伟民. 数据结构[M]. 北京：清华大学出版社，1997：35-37.")));

    var entry = entries.get(0);
    assertEquals("严蔚敏，吴伟民", entry.authorText());
    assertEquals("数据结构", entry.titleText());
    assertEquals("北京：清华大学出版社", entry.sourceText());
    assertEquals("1997", entry.yearText());
    assertEquals("35-37", entry.pagesText());
  }

  @Test
  void stopsCollectingEntriesAtAcknowledgementsAndAppendix() {
    var afterAcknowledgements = new ReferenceParser().parse(List.of(
        p("参考文献"), p("[1] 作者. 书名[M]. 出版社"), p("致谢"), p("[1] 本文使用的测试数据")));
    var afterAppendix = new ReferenceParser().parse(List.of(
        p("References"), p("[1] Author. Title[J]. Journal"), p("附录 A"), p("[2] 示例条目")));

    assertEquals(1, afterAcknowledgements.size());
    assertEquals(1, afterAppendix.size());
  }
}
