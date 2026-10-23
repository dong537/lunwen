package cn.luwneshacha.thesis.document;

import java.util.List;
import java.util.Set;
import org.w3c.dom.Element;

/** Identifies the school's mixed rule/example document before thesis rules run. */
public final class DocumentClassifier {
  public static final String RULE_SPECIFICATION_DOCUMENT = "RULE_SPECIFICATION_DOCUMENT";
  private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
  private static final Set<String> RULE_DOCUMENT_TITLES = Set.of(
      "学校论文规范（有格式要求标签版）",
      "学校论文规范(有格式要求标签版)",
      "山东石油化工学院本科毕业设计（论文）格式规范",
      "山东石油化工学院本科毕业设计(论文)格式规范",
      "山东石油化工学院本科毕业设计（论文）格式规范（GPT可读版）",
      "山东石油化工学院本科毕业设计(论文)格式规范(GPT可读版)");

  private DocumentClassifier() {}

  public static List<String> classify(byte[] documentXml) throws Exception {
    if (documentXml == null || documentXml.length == 0) return List.of();
    Element root = XmlPartSupport.parse(documentXml).getDocumentElement();
    var paragraphs = root.getElementsByTagNameNS(W, "p");
    int nonEmptyParagraphs = 0;
    for (int i = 0; i < paragraphs.getLength() && nonEmptyParagraphs < 5; i++) {
      String text = normalize(paragraphs.item(i).getTextContent());
      if (text.isEmpty()) continue;
      nonEmptyParagraphs++;
      if (RULE_DOCUMENT_TITLES.contains(text)) {
        return List.of(RULE_SPECIFICATION_DOCUMENT);
      }
    }
    return List.of();
  }

  private static String normalize(String text) {
    return text.codePoints()
        .filter(codePoint -> !Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)
            && !XmlPartSupport.isInvisibleFormattingWhitespace(codePoint))
        .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
        .toString();
  }
}
