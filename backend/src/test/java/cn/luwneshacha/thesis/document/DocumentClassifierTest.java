package cn.luwneshacha.thesis.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class DocumentClassifierTest {
  @Test
  void recognizesLongMixedRuleAndExampleDocument() throws Exception {
    String[] phrases = {"\u4e09\u53f7", "\u5c0f\u56db\u53f7", "\u4e94\u53f7", "\u9996\u884c\u7f29\u8fdb", "\u500d\u884c\u8ddd", "\u9875\u8fb9\u8ddd", "\u56fe\u53f7", "\u56fe\u9898", "\u8868\u53f7", "\u8868\u9898", "\u516c\u5f0f\u53f7", "\u7eed\u8868", "\u53c2\u8003\u6587\u732e", "\u5173\u952e\u8bcd", "\u9875\u7801", "\u7a7a\u4e00\u884c"};
    var xml = new StringBuilder("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>");
    xml.append("<w:p><w:r><w:t>\u5b66\u6821\u8bba\u6587\u89c4\u8303\uff08\u6709\u683c\u5f0f\u8981\u6c42\u6807\u7b7e\u7248\uff09</w:t></w:r></w:p>");
    for (int i = 0; i < 80; i++) {
      xml.append("<w:p><w:r><w:t>").append(phrases[i % phrases.length]);
      if (i % 8 == 0) xml.append("\u89c4\u8303\u8981\u6c42\u793a\u4f8b\u6ce8\u610f\uff1a");
      xml.append("</w:t></w:r></w:p>");
    }
    xml.append("<w:tbl/></w:body></w:document>");
    assertEquals(java.util.List.of(DocumentClassifier.RULE_SPECIFICATION_DOCUMENT), DocumentClassifier.classify(xml.toString().getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void recognizesRuleDocumentWhenTitleContainsInvisibleWordFormattingCharacters() throws Exception {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:p><w:r><w:t>\uFEFF学校论\u200C文规范（有格式要求标签版）</w:t></w:r></w:p>"
        + "</w:body></w:document>";

    assertEquals(java.util.List.of(DocumentClassifier.RULE_SPECIFICATION_DOCUMENT),
        DocumentClassifier.classify(xml.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void doesNotClassifyAnOrdinaryThesisParagraphAsRuleDocument() throws Exception {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>\u672c\u6587\u53c2\u8003\u6587\u732e\u91c7\u7528\u7edf\u4e00\u683c\u5f0f\uff0c\u6458\u8981\u5173\u952e\u8bcd\u5e94\u7b26\u5408\u8981\u6c42\u3002</w:t></w:r></w:p></w:body></w:document>";
    assertTrue(DocumentClassifier.classify(xml.getBytes(StandardCharsets.UTF_8)).isEmpty());
  }

  @Test
  void doesNotRejectAThesisWithAbstractChaptersAndReferences() throws Exception {
    var xml = new StringBuilder("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>");
    xml.append("<w:p><w:r><w:t>\u6458    \u8981</w:t></w:r></w:p><w:p><w:r><w:t>ABSTRACT</w:t></w:r></w:p>");
    for (int i = 1; i <= 7; i++) xml.append("<w:p><w:r><w:t>\u7b2c").append(i).append("\u7ae0 \u7814\u7a76\u5185\u5bb9</w:t></w:r></w:p>");
    xml.append("<w:p><w:r><w:t>\u53c2\u8003\u6587\u732e</w:t></w:r></w:p></w:body></w:document>");
    assertTrue(DocumentClassifier.classify(xml.toString().getBytes(StandardCharsets.UTF_8)).isEmpty());
  }

  @Test
  void doesNotClassifyAThesisWithArabicChaptersAndManyFormattingTerms() throws Exception {
    String[] phrases = {"\u4e09\u53f7", "\u5c0f\u56db\u53f7", "\u4e94\u53f7", "\u9996\u884c\u7f29\u8fdb", "\u500d\u884c\u8ddd", "\u9875\u8fb9\u8ddd", "\u56fe\u53f7", "\u56fe\u9898", "\u8868\u53f7", "\u8868\u9898", "\u516c\u5f0f\u53f7", "\u7eed\u8868", "\u53c2\u8003\u6587\u732e", "\u5173\u952e\u8bcd", "\u9875\u7801", "\u7a7a\u4e00\u884c"};
    var xml = new StringBuilder("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>");
    xml.append("<w:p><w:r><w:t>\u6458 \u8981</w:t></w:r></w:p><w:p><w:r><w:t>\u6458\u8981\u6b63\u6587</w:t></w:r></w:p>");
    xml.append("<w:p><w:r><w:t>1 \u5f15\u8a00</w:t></w:r></w:p>");
    for (int i = 0; i < 80; i++) {
      xml.append("<w:p><w:r><w:t>").append(phrases[i % phrases.length]);
      if (i % 8 == 0) xml.append("\u89c4\u8303\u8981\u6c42\u793a\u4f8b\u6ce8\u610f\uff1a");
      xml.append("</w:t></w:r></w:p>");
    }
    xml.append("<w:p><w:r><w:t>\u53c2\u8003\u6587\u732e</w:t></w:r></w:p><w:tbl/></w:body></w:document>");
    assertTrue(DocumentClassifier.classify(xml.toString().getBytes(StandardCharsets.UTF_8)).isEmpty());
  }
}
