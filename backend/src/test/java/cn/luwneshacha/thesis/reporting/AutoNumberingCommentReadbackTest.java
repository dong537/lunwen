package cn.luwneshacha.thesis.reporting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.luwneshacha.thesis.document.Docx4jDocumentParser;
import cn.luwneshacha.thesis.document.DocxReader;
import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.rules.RuleEngine;
import cn.luwneshacha.thesis.rules.RuleSet;
import java.nio.file.Path;
import java.util.List;
import org.docx4j.XmlUtils;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.openpackaging.parts.WordprocessingML.NumberingDefinitionsPart;
import org.docx4j.wml.Document;
import org.docx4j.wml.Numbering;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AutoNumberingCommentReadbackTest {
  private static final String NS = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"";
  @TempDir Path temp;

  @Test
  void formalAnnotationFlowAnchorsListNumberingAtTheCurrentParagraphStart() throws Exception {
    Path input = fixture("<w:p><w:pPr><w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"1\"/>"
        + "</w:numPr></w:pPr><w:r><w:t>绪论</w:t></w:r></w:p>"
        + "<w:p><w:pPr><w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"2\"/>"
        + "</w:numPr></w:pPr><w:r><w:t>作者. 题名[M]. 出版社，2024.</w:t></w:r></w:p>");
    var raw = new DocxReader().read(input);
    var parsed = new Docx4jDocumentParser().parse(raw);
    var findings = new RuleEngine().check(parsed, RuleSet.schoolV1()).stream()
        .filter(result -> "NUMBERING-01".equals(result.ruleId())).toList();

    assertEquals(2, findings.size());
    for (int index = 0; index < findings.size(); index++) {
      var source = findings.get(index).source();
      var paragraph = parsed.paragraphs().get(index);
      assertEquals(paragraph.source().elementPath(), source.elementPath());
      assertEquals(0, source.start());
      assertEquals(0, source.end(),
          "自动列表标签不是正文文字，使用当前段首的零宽锚点，避免整段高亮");
    }
    Path output = temp.resolve("list-comments.docx");
    var selected = AnnotationFindings.select(findings);
    var report = new CommentWriter().writeCopy(input, output, raw.sourceSha256(), selected.representatives());

    assertEquals(2, report.items().size());
    assertTrue(report.items().stream().allMatch(item -> item.status() == AnnotationStatus.WRITTEN),
        report.toString());
    assertEquals(2, selected.match(report).size());
    var comments = CommentWriter.readThreads(output);
    assertEquals(2, comments.size());
    for (var comment : comments) {
      assertTrue(comment.text().contains("取消该段的编号或多级列表"), comment.text());
      assertTrue(comment.text().contains("手动输入原序号"), comment.text());
      assertFalse(comment.text().contains("编号之后"), comment.text());
    }
    var reread = new Docx4jDocumentParser().parse(new DocxReader().read(output));
    assertEquals(parsed.paragraphs().stream().map(AutoNumberingCommentReadbackTest::text).toList(),
        reread.paragraphs().stream().map(AutoNumberingCommentReadbackTest::text).toList());
    assertEquals(List.of("绪论", "作者. 题名[M]. 出版社，2024."),
        reread.paragraphs().stream().map(AutoNumberingCommentReadbackTest::text).toList());
  }

  @ParameterizedTest
  @ValueSource(strings = {"SEQ Figure", "LISTNUM", "AUTONUM", "AUTONUMLGL", "AUTONUMOUT"})
  void fieldNumberingCommentPreservesTheDisplayedNumberAndOnlyTargetsThatNumber(String instruction)
      throws Exception {
    Path input = fixture("<w:p><w:r><w:t>图</w:t></w:r><w:fldSimple w:instr=\""
        + instruction + "\"><w:r><w:t>1</w:t></w:r></w:fldSimple>"
        + "<w:r><w:t xml:space=\"preserve\"> 结构图</w:t></w:r></w:p>");
    var raw = new DocxReader().read(input);
    var parsed = new Docx4jDocumentParser().parse(raw);
    var findings = new RuleEngine().check(parsed, RuleSet.schoolV1()).stream()
        .filter(result -> "NUMBERING-01".equals(result.ruleId())).toList();
    assertEquals(1, findings.size());
    Path output = temp.resolve(instruction.replace(' ', '-') + "-comment.docx");
    var report = new CommentWriter().writeCopy(input, output, raw.sourceSha256(),
        AnnotationFindings.select(findings).representatives());

    assertEquals(1, report.items().size());
    assertEquals(AnnotationStatus.WRITTEN, report.items().getFirst().status(), report.toString());
    var comments = CommentWriter.readThreads(output);
    assertEquals(1, comments.size());
    String advice = comments.getFirst().text();
    assertTrue(advice.contains("保留此处显示的序号"), advice);
    assertTrue(advice.contains("转换为普通文字"), advice);
    assertFalse(advice.contains("取消该段的编号"), advice);
    assertFalse(advice.contains("全选"), advice);
    assertFalse(advice.contains("XML"), advice);
    var reread = new Docx4jDocumentParser().parse(new DocxReader().read(output));
    assertEquals(text(parsed.paragraphs().getFirst()), text(reread.paragraphs().getFirst()));
    assertEquals("图1 结构图", text(reread.paragraphs().getFirst()));
  }

  private static String text(ParagraphModel paragraph) {
    return paragraph.runs().stream().map(run -> run.text()).reduce("", String::concat);
  }

  private Path fixture(String body) throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body>" + body + "<w:sectPr/></w:body></w:document>"));
    var numbering = new NumberingDefinitionsPart();
    numbering.setContents((Numbering) XmlUtils.unmarshalString("<w:numbering " + NS + ">"
        + "<w:abstractNum w:abstractNumId=\"1\"><w:lvl w:ilvl=\"0\"><w:start w:val=\"1\"/>"
        + "<w:numFmt w:val=\"decimal\"/><w:lvlText w:val=\"%1\"/><w:suff w:val=\"space\"/>"
        + "</w:lvl></w:abstractNum>"
        + "<w:abstractNum w:abstractNumId=\"2\"><w:lvl w:ilvl=\"0\"><w:start w:val=\"1\"/>"
        + "<w:numFmt w:val=\"decimal\"/><w:lvlText w:val=\"[%1]\"/><w:suff w:val=\"space\"/>"
        + "</w:lvl></w:abstractNum>"
        + "<w:num w:numId=\"1\"><w:abstractNumId w:val=\"1\"/></w:num>"
        + "<w:num w:numId=\"2\"><w:abstractNumId w:val=\"2\"/></w:num></w:numbering>"));
    pkg.getMainDocumentPart().addTargetPart(numbering);
    Path source = temp.resolve("source.docx");
    pkg.save(source.toFile());
    return source;
  }
}
