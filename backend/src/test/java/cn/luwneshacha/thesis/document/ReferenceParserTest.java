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
}
