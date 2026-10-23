package cn.luwneshacha.thesis.reporting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.luwneshacha.thesis.document.SourceAddress;
import cn.luwneshacha.thesis.rules.Applicability;
import cn.luwneshacha.thesis.rules.CheckResult;
import cn.luwneshacha.thesis.rules.ExecutionStatus;
import cn.luwneshacha.thesis.rules.Judgement;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import java.io.StringReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommentWriterTest {
  @TempDir Path tempDir;

  @Test
  void marginOnBlankSectionEndStaysBeforeTheNextSection() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:p><w:r><w:t>前节正文</w:t></w:r></w:p>"
        + "<w:p><w:pPr><w:sectPr><w:pgMar w:right=\"1418\"/></w:sectPr></w:pPr></w:p>"
        + "<w:p><w:r><w:t>致谢</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("section-margin.docx");
    var finding = new CheckResult("PAGE-01", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "1474", "1418", "MARGIN_MISMATCH",
        new SourceAddress("word/document.xml", List.of(0, 1), 0, 0));
    new CommentWriter().writeCopy(source, target, List.of(finding));
    try (ZipFile zip = new ZipFile(target.toFile())) {
      String document = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
      var paragraphs = Pattern.compile("<w:p(?:\\s[^>]*)?>.*?</w:p>", Pattern.DOTALL).matcher(document);
      boolean found = false;
      while (paragraphs.find()) {
        String paragraph = paragraphs.group();
        if (paragraph.contains("commentRangeStart")) {
          found = true;
          assertTrue(paragraph.contains("前节正文"), paragraph);
          assertFalse(paragraph.contains("致谢"), paragraph);
        }
      }
      assertTrue(found);
    }
  }

  @Test
  void fallsBackToMainStoryParagraphInsteadOfTextboxParagraphForEmptySectionAnchor() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:p><w:r><w:t>正文段落</w:t></w:r></w:p>"
        + "<w:p><w:r><w:drawing/><w:txbxContent><w:p><w:r><w:t>monkeypatch.setattr(</w:t></w:r></w:p></w:txbxContent></w:r></w:p>"
        + "<w:p><w:pPr><w:sectPr><w:pgMar w:right=\"1418\"/></w:sectPr></w:pPr></w:p>"
        + "</w:body></w:document>");
    Path target = tempDir.resolve("section-margin-skips-textbox.docx");
    var finding = new CheckResult("PAGE-01", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "1474", "1418", "MARGIN_MISMATCH",
        new SourceAddress("word/document.xml", List.of(0, 2), 0, 0));

    new CommentWriter().writeCopy(source, target, List.of(finding));

    assertEquals("正文段落", documentCommentRangeText(target, "0"));
    try (ZipFile zip = new ZipFile(target.toFile())) {
      String document = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertFalse(document.contains("<w:txbxContent><w:p><w:commentRangeStart"), document);
    }
  }

  @Test
  void resolvesSectionPropertiesToNonEmptyMainStoryParagraph() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:p><w:r><w:drawing/><w:txbxContent><w:p><w:r><w:t>monkeypatch.setattr(</w:t></w:r></w:p></w:txbxContent></w:r></w:p>"
        + "<w:p><w:r><w:t>正文段落</w:t></w:r></w:p>"
        + "<w:sectPr><w:pgMar w:right=\"1418\"/></w:sectPr>"
        + "</w:body></w:document>");
    Path target = tempDir.resolve("section-properties-skip-textbox.docx");
    var finding = new CheckResult("PAGE-01", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "1474", "1418", "MARGIN_MISMATCH",
        new SourceAddress("word/document.xml", List.of(0, 2), 0, 0));

    new CommentWriter().writeCopy(source, target, List.of(finding));

    assertEquals("正文段落", documentCommentRangeText(target, "0"));
  }

  @Test
  void anchorsTerminalSectionPropertiesToTheLastParagraphInThatSection() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:p><w:r><w:t>第一章正文</w:t></w:r></w:p>"
        + "<w:p><w:r><w:t>末节正文</w:t></w:r></w:p>"
        + "<w:sectPr><w:pgMar w:right=\"1418\"/></w:sectPr>"
        + "</w:body></w:document>");
    Path target = tempDir.resolve("terminal-section-anchor.docx");
    var finding = new CheckResult("PAGE-01", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "1474", "1418", "MARGIN_MISMATCH",
        new SourceAddress("word/document.xml", List.of(0, 2), 0, 0));

    new CommentWriter().writeCopy(source, target, List.of(finding));

    assertEquals("末节正文", documentCommentRangeText(target, "0"));
  }

  @Test
  void doesNotHighlightWholeParagraphForParagraphLevelFormatting() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>这是一段正文，首行缩进属性错误时不应把整段文字染色。</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("paragraph-level-no-highlight.docx");
    var finding = new CheckResult("BODY-INDENT", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "480", "0", "INDENT_MISMATCH",
        new SourceAddress("word/document.xml", List.of(0, 0), 0, 24));

    new CommentWriter().writeCopy(source, target, List.of(finding));

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String document = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertFalse(document.contains("w:highlight"), document);
      assertTrue(document.contains("commentRangeStart"));
    }
  }

  @Test
  void highlightsOnlyTheRunThatProducedRunLevelFinding() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>正常文字</w:t></w:r><w:r><w:t>错误文字</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("run-level-highlight.docx");
    var finding = new CheckResult("BODY-FONT", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "宋体/Times New Roman", "Arial", "FONT_MISMATCH",
        new SourceAddress("word/document.xml", List.of(0, 0, 1), 0, 4));

    new CommentWriter().writeCopy(source, target, List.of(finding));

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String document = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
      var runs = Pattern.compile("<w:r\\b.*?</w:r>", Pattern.DOTALL).matcher(document);
      int highlighted = 0;
      while (runs.find()) if (runs.group().contains("w:highlight")) highlighted++;
      assertEquals(1, highlighted);
      assertTrue(document.contains("<w:t>正常文字</w:t>"));
      assertTrue(document.contains("<w:t>错误文字</w:t>"));
    }
  }

  @Test
  void highlightsOnlyBoldTextAfterEnglishKeywordLabelWhenItSharesOneRun() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p>"
        + "<w:r><w:rPr><w:b/></w:rPr><w:t>Keywords: alpha; beta</w:t></w:r>"
        + "</w:p></w:body></w:document>");
    Path target = tempDir.resolve("keyword-bold-suffix-only.docx");
    var finding = new CheckResult("EN-03-BOLD", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "Keywords 标签单独加粗", "冒号、空格和关键词内容都被加粗", "KEYWORD_CONTENT_BOLD",
        new SourceAddress("word/document.xml", List.of(0, 0), 8, 21));

    new CommentWriter().writeCopy(source, target, List.of(finding));

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String document = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8);
      var runs = Pattern.compile("<w:r(?:\\s[^>]*)?>.*?</w:r>", Pattern.DOTALL).matcher(document);
      String highlighted = null;
      int highlightedCount = 0;
      while (runs.find()) {
        if (!runs.group().contains("w:highlight")) continue;
        highlighted = runs.group();
        highlightedCount++;
      }
      assertEquals(1, highlightedCount);
      assertTrue(highlighted.contains(": alpha; beta"), highlighted);
      assertFalse(highlighted.contains("Keywords"), highlighted);
      assertTrue(document.contains("<w:t>Keywords</w:t>"), document);
      assertTrue(comments.contains("“Keywords”标签（含冒号）应保持加粗；取消冒号后的空格和关键词正文的加粗。"), comments);
    }
  }

  @Test
  void highlightsOnlyTheRunAtTheParagraphTextOffsetAndExplainsBoldColon() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p>"
        + "<w:r><w:t>Keywords</w:t></w:r>"
        + "<w:r><w:t xml:space=\"preserve\">: </w:t></w:r>"
        + "<w:r><w:t>WeChat mini program; Aquarium Ecological Regulation; Water Quality Monitoring; Esp32</w:t></w:r>"
        + "</w:p></w:body></w:document>");
    Path target = tempDir.resolve("keyword-colon-highlight.docx");
    var finding = new CheckResult("EN-03-BOLD", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "Keywords 标签单独加粗", "冒号及其后的空格被加粗", "KEYWORD_CONTENT_BOLD",
        new SourceAddress("word/document.xml", List.of(0, 0), 8, 10));

    new CommentWriter().writeCopy(source, target, List.of(finding));

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String document = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8);
      var runs = Pattern.compile("<w:r(?:\\s[^>]*)?>.*?</w:r>", Pattern.DOTALL).matcher(document);
      int highlighted = 0;
      String highlightedRun = null;
      while (runs.find()) {
        if (!runs.group().contains("w:highlight")) continue;
        highlighted++;
        highlightedRun = runs.group();
      }
      assertEquals(1, highlighted);
      assertTrue(highlightedRun.contains(": "), highlightedRun);
      assertFalse(highlightedRun.contains("WeChat"), highlightedRun);
      assertEquals(": ", commentRangeText(document, "0"));
      assertTrue(comments.contains("“Keywords”标签（含冒号）应保持加粗；请取消冒号后的空格加粗，关键词正文保持常规字重。"), comments);
    }
  }

  @Test
  void combinesParagraphAndKeywordFindingsWithoutHighlightingTheWholeParagraph() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p>"
        + "<w:r><w:rPr><w:b/></w:rPr><w:t>Keywords</w:t></w:r>"
        + "<w:r><w:rPr><w:b/></w:rPr><w:t xml:space=\"preserve\">: </w:t></w:r>"
        + "<w:r><w:t>alpha; beta</w:t></w:r>"
        + "</w:p></w:body></w:document>");
    Path target = tempDir.resolve("keyword-indent-group-annotated.docx");
    var findings = List.of(
        new CheckResult("ABSTRACT-INDENT", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
            Judgement.FAIL, "2 characters", "0", "INDENT_MISMATCH",
            new SourceAddress("word/document.xml", List.of(0, 0), 0, 21)),
        new CheckResult("EN-03-BOLD", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
            Judgement.FAIL, "Keywords 标签单独加粗", "冒号及其后的空格被加粗", "KEYWORD_CONTENT_BOLD",
            new SourceAddress("word/document.xml", List.of(0, 0), 8, 10)));

    new CommentWriter().writeCopy(source, target, findings);

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String document = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8);
      var runs = Pattern.compile("<w:r(?:\\s[^>]*)?>.*?</w:r>", Pattern.DOTALL).matcher(document);
      List<String> highlightedRuns = new ArrayList<>();
      while (runs.find()) if (runs.group().contains("w:highlight")) highlightedRuns.add(runs.group());

      assertEquals(1, highlightedRuns.size(), highlightedRuns.toString());
      assertTrue(highlightedRuns.get(0).contains(": "), highlightedRuns.get(0));
      assertFalse(highlightedRuns.get(0).contains("Keywords") || highlightedRuns.get(0).contains("alpha"),
          highlightedRuns.get(0));
      assertEquals(": ", commentRangeText(document, "0"));
      assertTrue(comments.contains("Keywords"), comments);
      assertTrue(comments.contains("冒号后的空格"), comments);
    }
  }

  @Test
  void highlightsOnlyInvalidSpacesInChapterAndAcknowledgementHeadings() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:p><w:r><w:t>5. 6 结论</w:t></w:r></w:p>"
        + "<w:p><w:r><w:t>致 谢</w:t></w:r></w:p>"
        + "</w:body></w:document>");
    Path target = tempDir.resolve("heading-spacing-highlight.docx");
    var findings = List.of(
        new CheckResult("HEAD-02-SPACING", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
            Judgement.FAIL, "5.6", "5. 6", "HEADING_NUMBER_INTERNAL_WHITESPACE",
            new SourceAddress("word/document.xml", List.of(0, 0), 2, 3)),
        new CheckResult("END-01-SPACING", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
            Judgement.FAIL, "致    谢", "致 谢", "ACKNOWLEDGEMENT_TITLE_SPACING_MISMATCH",
            new SourceAddress("word/document.xml", List.of(0, 1), 1, 2)));

    new CommentWriter().writeCopy(source, target, findings);

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String document = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
      var highlightedRuns = Pattern.compile("<w:r(?:\\s[^>]*)?>.*?</w:r>", Pattern.DOTALL).matcher(document);
      int highlighted = 0;
      while (highlightedRuns.find()) {
        if (!highlightedRuns.group().contains("w:highlight")) continue;
        assertTrue(highlightedRuns.group().contains("<w:t xml:space=\"preserve\"> </w:t>"), highlightedRuns.group());
        assertFalse(highlightedRuns.group().contains("5.") || highlightedRuns.group().contains("6")
            || highlightedRuns.group().contains("致") || highlightedRuns.group().contains("谢"), highlightedRuns.group());
        highlighted++;
      }
      assertEquals(2, highlighted);
    }
  }

  @Test
  void givesReferenceAndAcknowledgementBlankLineErrorsSpecificLocations() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:p><w:r><w:t>参考文献</w:t></w:r></w:p><w:p><w:r><w:t>[1] 作者</w:t></w:r></w:p>"
        + "<w:p><w:r><w:t>致谢</w:t></w:r></w:p><w:p><w:r><w:t>感谢导师</w:t></w:r></w:p>"
        + "</w:body></w:document>");
    Path target = tempDir.resolve("end-matter-specific-blank-advice.docx");
    var findings = List.of(
        new CheckResult("REF-01-BLANK", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
            Judgement.FAIL, "标题与首条文献之间空一行", "未空一行", "REFERENCE_TITLE_BLANK_LINE_MISSING",
            new SourceAddress("word/document.xml", List.of(0, 1), 0, 4)),
        new CheckResult("END-01-BLANK", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
            Judgement.FAIL, "致谢标题与正文之间空一行", "未空一行", "ACKNOWLEDGEMENT_TITLE_BLANK_LINE_MISSING",
            new SourceAddress("word/document.xml", List.of(0, 3), 0, 4)));

    new CommentWriter().writeCopy(source, target, findings);

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(comments.contains("在“参考文献”标题与第一条文献之间保留一个空白段落。"), comments);
      assertTrue(comments.contains("在“致谢”标题与正文之间保留一个空白段落。"), comments);
    }
  }

  @Test
  void givesDistinctEnglishKeywordBoldAdviceForColonContentAndBoth() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:p><w:r><w:t>Keywords: alpha; beta</w:t></w:r></w:p>"
        + "<w:p><w:r><w:t>Keywords: alpha; beta</w:t></w:r></w:p>"
        + "</w:body></w:document>");
    Path target = tempDir.resolve("keyword-bold-advice.docx");
    var findings = List.of(
        new CheckResult("EN-03-BOLD", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
            Judgement.FAIL, "Keywords 标签单独加粗", "关键词内容被加粗", "KEYWORD_CONTENT_BOLD",
            new SourceAddress("word/document.xml", List.of(0, 0), 0, 20)),
        new CheckResult("EN-03-BOLD", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
            Judgement.FAIL, "Keywords 标签单独加粗", "冒号和关键词内容都被加粗", "KEYWORD_CONTENT_BOLD",
            new SourceAddress("word/document.xml", List.of(0, 1), 0, 20)));

    new CommentWriter().writeCopy(source, target, findings);

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(comments.contains("关键词正文被加粗；请取消其加粗，保留“Keywords”标签（含冒号）加粗；冒号后的空格保持常规字重。"), comments);
      assertTrue(comments.contains("“Keywords”标签（含冒号）应保持加粗；取消关键词正文的加粗，冒号后的空格保持常规字重。"), comments);
    }
  }

  @Test
  void explainsThatTheEnglishKeywordLabelIncludesTheColonButNotTheFollowingSpace() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:p><w:r><w:t>Keywords: alpha; beta</w:t></w:r></w:p>"
        + "</w:body></w:document>");
    Path target = tempDir.resolve("english-keyword-label-not-bold.docx");
    var finding = new CheckResult("EN-03-BOLD", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "Keywords 标签（含冒号）加粗", "标签未加粗", "KEYWORD_LABEL_NOT_BOLD",
        new SourceAddress("word/document.xml", List.of(0, 0), 0, 8));

    new CommentWriter().writeCopy(source, target, List.of(finding));

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(comments.contains("请将“Keywords”标签（含冒号）加粗；冒号后的空格和关键词正文保持常规字重。"), comments);
    }
  }

  @Test
  void referenceFieldAdviceListsOnlyFieldsReportedAsMissing() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>参考文献</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("reference-missing-fields.docx");
    var finding = new CheckResult("REF-04", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "文献类别对应字段完整",
        "[10] 余志锐,程铁栋. 题名[J/OL]. 电子测量技术, 1-10[2026-05-27]. URL；缺少出版年份、页码",
        "REFERENCE_FIELDS_MISSING", new SourceAddress("word/document.xml", List.of(0, 0), 0, 4));

    new CommentWriter().writeCopy(source, target, List.of(finding));

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(comments.contains("这条参考文献缺少出版年份、页码，请补齐。"), comments);
      assertFalse(comments.contains("补齐作者、题名、来源、年份和页码"), comments);
    }
  }

  @Test
  void advisesOneSpaceAfterNumericSubsectionNumberInContents() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>1.1  引言</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("toc-one-space-suggestion.docx");
    var finding = new CheckResult("TOC-SPACING", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "编号后空 1 格", "普通空格、普通空格", "TOC_NUMBER_SPACING_MISMATCH",
        new SourceAddress("word/document.xml", List.of(0, 0), 0, 7));

    new CommentWriter().writeCopy(source, target, List.of(finding));

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(comments.contains("编号后有 2 个普通空格，请删除 1 个，只保留 1 个普通半角空格"), comments);
    }
  }

  @Test
  void explainsThatInternalNumberWhitespaceMustBeRemovedFromContentsHeading() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>5. 6 系统实现</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("toc-internal-number-space.docx");
    var finding = new CheckResult("TOC-SPACING", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "章节序号内部不夹空格", "5. 6", "TOC_NUMBER_SPACING_MISMATCH",
        new SourceAddress("word/document.xml", List.of(0, 0), 2, 3));

    new CommentWriter().writeCopy(source, target, List.of(finding));

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(comments.contains("编号写成了“5. 6”，请删除编号内部的空格，改为“5.6”"), comments);
    }
  }

  @Test
  void chapterSpacingCommentUsesTheSameTwoSpaceRequirementAsTheReport() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>第一章 引言</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("toc-two-spaces-suggestion.docx");
    var finding = new CheckResult("TOC-SPACING", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "编号后空 2 格", "普通空格", "TOC_NUMBER_SPACING_MISMATCH",
        new SourceAddress("word/document.xml", List.of(0, 0), 3, 4));
    new CommentWriter().writeCopy(source, target, List.of(finding));
    try (ZipFile zip = new ZipFile(target.toFile())) {
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8);
      String suggestion = cn.luwneshacha.thesis.report.RuleDescriptions.suggestion(finding);
      assertTrue(suggestion.contains("补 1 个"), suggestion);
      assertTrue(suggestion.contains("2 个普通半角空格"), suggestion);
      assertTrue(comments.contains(suggestion), comments);
      assertFalse(comments.contains("只保留一个"), comments);
    }
  }

  @Test
  void highlightsOnlyTheExtraSpaceInsideOneTextRun() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:rPr><w:b/></w:rPr><w:t>第一章  引言</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("toc-extra-space-highlight.docx");
    var finding = new CheckResult("TOC-SPACING", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "编号后空 1 格", "普通空格、普通空格", "TOC_NUMBER_SPACING_MISMATCH",
        new SourceAddress("word/document.xml", List.of(0, 0), 4, 5));

    new CommentWriter().writeCopy(source, target, List.of(finding));

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String document = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
      var runs = Pattern.compile("<w:r\\b.*?</w:r>", Pattern.DOTALL).matcher(document);
      int highlighted = 0;
      while (runs.find()) {
        if (!runs.group().contains("w:highlight")) continue;
        highlighted++;
        assertTrue(runs.group().contains("xml:space=\"preserve\"> </w:t>"), runs.group());
        assertFalse(runs.group().contains("第一章") || runs.group().contains("引言"), runs.group());
        assertTrue(runs.group().contains("<w:b"), runs.group());
      }
      assertEquals(1, highlighted);
      assertTrue(document.contains(">第一章 </w:t>"), document);
      assertTrue(document.contains(">引言</w:t>"), document);
    }
  }

  @Test
  void highlightsWhitespaceAcrossRunBoundariesWithoutLosingFormatting() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p>"
        + "<w:r><w:rPr><w:b/></w:rPr><w:t>第一章 </w:t></w:r>"
        + "<w:r><w:rPr><w:i/></w:rPr><w:t xml:space=\"preserve\"> 引言</w:t></w:r>"
        + "</w:p></w:body></w:document>");
    Path target = tempDir.resolve("toc-space-cross-run-highlight.docx");
    var finding = new CheckResult("TOC-SPACING", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "编号后空 1 格", "普通空格、普通空格", "TOC_NUMBER_SPACING_MISMATCH",
        new SourceAddress("word/document.xml", List.of(0, 0), 3, 5));

    new CommentWriter().writeCopy(source, target, List.of(finding));

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String document = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
      var runs = Pattern.compile("<w:r\\b.*?</w:r>", Pattern.DOTALL).matcher(document);
      int highlightedSpaces = 0;
      while (runs.find()) {
        String run = runs.group();
        if (!run.contains("w:highlight")) continue;
        assertTrue(run.contains("xml:space=\"preserve\"> </w:t>"), run);
        assertFalse(run.contains("第一章") || run.contains("引言"), run);
        assertTrue(run.contains("<w:b") || run.contains("<w:i"), run);
        highlightedSpaces++;
      }
      assertEquals(2, highlightedSpaces);
      assertTrue(document.contains("<w:b/>"), document);
      assertTrue(document.contains("<w:i/>"), document);
      assertTrue(document.contains(">第一章</w:t>"), document);
      assertTrue(document.contains(">引言</w:t>"), document);
    }
  }

  @Test
  void highlightsOnlyTabElementWhenWhitespaceIsEncodedAsWordTab() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p>"
        + "<w:r><w:rPr><w:b/></w:rPr><w:t>第一章</w:t><w:tab/><w:t>引言</w:t></w:r>"
        + "</w:p></w:body></w:document>");
    Path target = tempDir.resolve("toc-tab-highlight.docx");
    var finding = new CheckResult("TOC-SPACING", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "编号后空 1 格", "制表符", "TOC_NUMBER_SPACING_MISMATCH",
        new SourceAddress("word/document.xml", List.of(0, 0), 3, 4));

    new CommentWriter().writeCopy(source, target, List.of(finding));

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String document = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
      var runs = Pattern.compile("<w:r(?:\\s[^>]*)?>.*?</w:r>", Pattern.DOTALL).matcher(document);
      int highlighted = 0;
      while (runs.find()) {
        if (!runs.group().contains("w:highlight")) continue;
        assertTrue(runs.group().contains("<w:tab/>") || runs.group().contains("<w:tab />"), runs.group());
        assertFalse(runs.group().contains("第一章") || runs.group().contains("引言"), runs.group());
        highlighted++;
      }
      assertEquals(1, highlighted);
      assertTrue(document.contains("<w:t>第一章</w:t>"), document);
      assertTrue(document.contains("<w:t>引言</w:t>"), document);
    }
  }

  @Test
  void indentationMismatchAdviceDoesNotClaimThatTextHasExtraSpaces() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>正文段落</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("indent-only-advice.docx");
    var finding = new CheckResult("BODY-INDENT", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "2 字符", "首行缩进不足", "INDENT_MISMATCH",
        new SourceAddress("word/document.xml", List.of(0, 0), 0, 4));

    new CommentWriter().writeCopy(source, target, List.of(finding));

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(comments.contains("正文首行缩进未达到规范要求的 2 字符；请在 Word 的“段落”设置中将“特殊格式”设为“首行”，缩进量设为 2 字符。"), comments);
      assertFalse(comments.contains("删除段首空白"), comments);
    }
  }

  @Test
  void distinguishesTypedWhitespaceFromParagraphIndentInComment() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:p><w:r><w:t>正文首段</w:t></w:r></w:p>"
        + "<w:p><w:r><w:t>正文次段</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("indent-source-specific-suggestion.docx");
    var findings = List.of(
        new CheckResult("BODY-INDENT", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
            Judgement.FAIL, "480 twips", "420 twips；文字前有 1 个文字空白", "INDENT_MISMATCH",
            new SourceAddress("word/document.xml", List.of(0, 0), 0, 4)),
        new CheckResult("BODY-INDENT", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
            Judgement.FAIL, "480 twips", "360 twips", "INDENT_MISMATCH",
            new SourceAddress("word/document.xml", List.of(0, 1), 0, 4)));

    new CommentWriter().writeCopy(source, target, findings);

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(comments.contains("正文段首有 1 个空白，首行缩进约为 0.74 厘米，规范要求 2 字符（约 0.85 厘米）"), comments);
      assertTrue(comments.contains("首行缩进约为 0.64 厘米，规范要求 2 字符（约 0.85 厘米）"), comments);
      assertFalse(comments.contains("360 twips"), comments);
      assertFalse(comments.contains("首行缩进应为 2 字符，请在段落设置中调整"), comments);
    }
  }

  @Test
  void usesSpecificRuleDescriptionWhenWritingContentsFormatComments() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>第一章 引言</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("toc-specific-format-suggestion.docx");
    var finding = new CheckResult("TOC-ENTRY-SIZE", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "24", "22", "SIZE_MISMATCH",
        new SourceAddress("word/document.xml", List.of(0, 0), 0, 6));

    new CommentWriter().writeCopy(source, target, List.of(finding));

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(comments.contains("目录条目统一改为小四号"), comments);
      assertFalse(comments.contains("请按学校规范修改此处格式，并与同级内容保持一致"), comments);
    }
  }

  @Test
  void removesHighlightsAlreadyPresentInInputDocument() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:rPr><w:highlight w:val=\"magenta\"/></w:rPr><w:t>旧高亮</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("old-highlight-removed.docx");
    var finding = new CheckResult("BODY-INDENT", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "480", "0", "INDENT_MISMATCH",
        new SourceAddress("word/document.xml", List.of(0, 0), 0, 3));

    new CommentWriter().writeCopy(source, target, List.of(finding));

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String document = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertFalse(document.contains("w:highlight"));
    }
  }

  @Test
  void writesCommentToDirectBodyParagraphAndLeavesOtherPartsUntouched() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr><w:r><w:t>正文</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("annotated.docx");
    var result = result(List.of(0, 0));

    AnnotationReport report = new CommentWriter().writeCopy(source, target, List.of(result));

    assertEquals(AnnotationStatus.WRITTEN, report.items().get(0).status());
    try (ZipFile zip = new ZipFile(target.toFile())) {
      String document = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8);
      String commentsExtended = new String(zip.getInputStream(zip.getEntry("word/commentsExtended.xml")).readAllBytes(), StandardCharsets.UTF_8);
      String people = new String(zip.getInputStream(zip.getEntry("word/people.xml")).readAllBytes(), StandardCharsets.UTF_8);
      String relationships = new String(zip.getInputStream(zip.getEntry("word/_rels/document.xml.rels")).readAllBytes(), StandardCharsets.UTF_8);
      String contentTypes = new String(zip.getInputStream(zip.getEntry("[Content_Types].xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(document.contains("commentRangeStart"));
      assertTrue(document.indexOf("w:pPr") < document.indexOf("commentRangeStart"));
      assertTrue(document.contains("commentReference"));
      assertTrue(comments.contains("当前分节页边距为 2.50119 cm（XML：1418 twips）"));
      assertTrue(zip.getEntry("word/_rels/document.xml.rels") != null);
      assertTrue(comments.matches("(?s).*w14:paraId=\"[A-F0-9]{8}\".*"), comments);
      assertTrue(commentsExtended.contains("w15:commentEx"), commentsExtended);
      assertTrue(people.contains("w15:person") && people.contains("论文格式筛查"), people);
      assertTrue(relationships.contains("/commentsExtended") && relationships.contains("/people"), relationships);
      assertTrue(contentTypes.contains("/word/commentsExtended.xml") && contentTypes.contains("/word/people.xml"), contentTypes);
      assertTrue(zip.getEntry("word/commentsIds.xml") != null);
      String commentsIds = new String(zip.getInputStream(zip.getEntry("word/commentsIds.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(commentsIds.matches("(?s).*w16cid:commentId[^>]*w16cid:(?:paraId=\"[A-F0-9]{8}\"[^>]*durableId=\"[A-F0-9]{8}\"|durableId=\"[A-F0-9]{8}\"[^>]*paraId=\"[A-F0-9]{8}\").*"), commentsIds);
      assertTrue(relationships.contains("/commentsIds"), relationships);
      assertTrue(contentTypes.contains("/word/commentsIds.xml"), contentTypes);
    }
  }

  @Test
  void writesSpecificColorAndNaturalSuggestionForCoverFormatError() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>论文题目</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("cover-annotated.docx");
    var finding = new CheckResult("COVER-02-FONT", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "黑体", "宋体", "FONT_MISMATCH",
        new SourceAddress("word/document.xml", List.of(0, 0), 0, 4));

    AnnotationReport report = new CommentWriter().writeCopy(source, target, List.of(finding));

    assertEquals(AnnotationStatus.WRITTEN, report.items().get(0).status());
    try (ZipFile zip = new ZipFile(target.toFile())) {
      String document = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(document.contains("w:val=\"cyan\""));
      assertTrue(comments.contains("论文题目：改为黑体。"));
    }
  }

  @Test
  void coverFieldBoldCommentNamesOnlyTheFieldAtTheAnchor() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>专业年级：2022级物联网工程专业</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("cover-grade-bold-annotated.docx");
    var finding = new CheckResult("COVER-03-BOLD", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "true", "false", "BOLD_MISMATCH",
        new SourceAddress("word/document.xml", List.of(0, 0), 6, 10));

    new CommentWriter().writeCopy(source, target, List.of(finding));

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(comments.contains("专业年级内容没有加粗，请选中该字段内容并设置为加粗。"), comments);
      assertFalse(comments.contains("学生姓名、学号、院系、专业年级、指导教师：统一加粗"), comments);
    }
  }

  @Test
  void headingFontCommentNamesTheSpecificHeadingLevel() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>1.1 标题</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("heading-font-annotated.docx");
    var finding = new CheckResult("HEADING-FONT", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "二级标题：黑体/Times New Roman", "宋体/Arial", "FONT_MISMATCH",
        new SourceAddress("word/document.xml", List.of(0, 0), 0, 6));

    new CommentWriter().writeCopy(source, target, List.of(finding));

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(comments.contains("二级标题：中文改为黑体，字母和数字改为 Times New Roman。"));
      assertFalse(comments.contains("本级标题"));
      assertFalse(comments.contains("按标题层级"));
    }
  }

  @Test
  void writesAppendixTitleSpecificColorAndSuggestion() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>附 录</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("appendix-title-annotated.docx");
    var finding = new CheckResult("APP-02-SPACING", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "附    录", "附 录", "APPENDIX_TITLE_SPACING_MISMATCH",
        // “附 录”是 3 个 UTF-16 字符，错误范围只落在现有的一个空格上。
        new SourceAddress("word/document.xml", List.of(0, 0), 1, 2));

    AnnotationReport report = new CommentWriter().writeCopy(source, target, List.of(finding));

    assertEquals(AnnotationStatus.WRITTEN, report.items().get(0).status());
    try (ZipFile zip = new ZipFile(target.toFile())) {
      String document = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(document.contains("w:val=\"magenta\""), document);
      assertTrue(comments.contains("附录标题按规范写为“附    录”，中间留 4 个空格。"), comments);
    }
  }

  @Test
  void referenceTitleCommentDoesNotTellUserToInsertASpace() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>参考 文献</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("reference-title-spacing.docx");
    var finding = new CheckResult("REF-01-SPACING", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "参考文献", "参考 文献", "REFERENCE_TITLE_SPACING_MISMATCH",
        new SourceAddress("word/document.xml", List.of(0, 0), 0, 5));

    new CommentWriter().writeCopy(source, target, List.of(finding));

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(comments.contains("参考文献"), comments);
      assertFalse(comments.contains("参考 文献\u201d，中间保留 1 个空格"), comments);
      assertTrue(comments.contains("两个词之间不要插入空格"), comments);
    }
  }

  @Test
  void acknowledgementTitleCommentExplainsTheRequiredFourSpaces() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>致  谢</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("acknowledgement-title-spacing.docx");
    var finding = new CheckResult("END-01-SPACING", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "致谢", "致  谢", "ACKNOWLEDGEMENT_TITLE_SPACING_MISMATCH",
        new SourceAddress("word/document.xml", List.of(0, 0), 0, 4));

    new CommentWriter().writeCopy(source, target, List.of(finding));

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(comments.contains("两个字之间保留 4 个普通空格"), comments);
      assertTrue(comments.contains("致    谢"), comments);
    }
  }

  @Test
  void explainsWhatIsWrongWithHeadingAndFigureCaptionSpacing() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:p><w:r><w:t>5.1功能模块设计</w:t></w:r></w:p>"
        + "<w:p><w:r><w:t>图5-2用户登录流程图</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("spacing-explanations.docx");
    var heading = new CheckResult("HEAD-02-SPACING", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "编号后空 1 格", "没有空格", "HEADING_NUMBER_SPACING_MISMATCH",
        new SourceAddress("word/document.xml", List.of(0, 0), 0, 9));
    var figure = new CheckResult("FIG-03", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "图号后空 1 格", "图5-2用户登录流程图", "FIGURE_CAPTION_NUMBER_FORMAT",
        new SourceAddress("word/document.xml", List.of(0, 1), 0, 11));

    new CommentWriter().writeCopy(source, target, List.of(heading, figure));

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(comments.contains("编号和标题直接连在一起"), comments);
      assertTrue(comments.contains("“图5-2”和图题之间没有空格"), comments);
      assertTrue(comments.contains("请在编号后输入 1 个普通空格"), comments);
      assertTrue(comments.contains("请在编号后输入 1 个普通空格"), comments);
      assertTrue(comments.contains("请在图号后只留一个普通空格"), comments);
      assertFalse(comments.contains("XML") || comments.contains("twips") || comments.contains("段落属性"), comments);
    }
  }

  @Test
  void explainsInternalNumberSpaceWithoutClaimingTheSeparatorIsMissing() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>5. 6 按键模块</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("number-internal-space.docx");
    var finding = new CheckResult("HEAD-02-SPACING", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "5.6", "5. 6", "HEADING_NUMBER_INTERNAL_WHITESPACE",
        new SourceAddress("word/document.xml", List.of(0, 0), 0, 9));
    new CommentWriter().writeCopy(source, target, List.of(finding));
    try (ZipFile zip = new ZipFile(target.toFile())) {
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(comments.contains("编号内部的空格"), comments);
      assertTrue(comments.contains("改为“5.6”"), comments);
      assertFalse(comments.contains("标题之间的间隔不符合"), comments);
    }
  }

  @Test
  void lowerHeadingMarkerAdviceMatchesTheSchoolExample() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>（1）、测试内容</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("lower-heading-marker-advice.docx");
    var finding = new CheckResult("HEAD-05-MARKER", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "（1）标题", "（1）、测试内容", "LOWER_HEADING_MARKER_MISMATCH",
        new SourceAddress("word/document.xml", List.of(0, 0), 0, 8));

    new CommentWriter().writeCopy(source, target, List.of(finding));

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(comments.contains("三级以下标题编号应写成“（1）标题”，右括号后不要加顿号。"), comments);
    }
  }

  @Test
  void bodyIndentAdviceSeparatesLeadingTextSpacesFromParagraphIndent() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>正文段落</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("body-indent-leading-spaces.docx");
    var finding = new CheckResult("BODY-INDENT", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "2 chars / 420 twips", "420 twips；文字前有 2 个文字空白", "INDENT_TEXT_WHITESPACE",
        new SourceAddress("word/document.xml", List.of(0, 0), 0, 4));

    new CommentWriter().writeCopy(source, target, List.of(finding));

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(comments.contains("正文文字开头有 2 个空白字符，请删除该空白，保留段落首行缩进为 2 字符。"), comments);
    }
  }

  @Test
  void explainsExcessAndLeadingCaptionSpacesSeparately() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:p><w:r><w:t>图4-6  报修工单实体属性图</w:t></w:r></w:p>"
        + "<w:p><w:r><w:t>     图5-10 房东处理预约申请页面图</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("caption-space-details.docx");
    var first = new CheckResult("FIG-03", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "图号后空 1 格", "图4-6  报修工单实体属性图", "FIGURE_CAPTION_NUMBER_FORMAT",
        new SourceAddress("word/document.xml", List.of(0, 0), 0, 15));
    var second = new CheckResult("FIG-03", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "图号后空 1 格", "     图5-10 房东处理预约申请页面图", "FIGURE_CAPTION_NUMBER_FORMAT",
        new SourceAddress("word/document.xml", List.of(0, 1), 0, 22));

    new CommentWriter().writeCopy(source, target, List.of(first, second));

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(comments.contains("“图4-6”和图题之间有 2 个普通空格，多留了1格"), comments);
      assertTrue(comments.contains("改成“图4-6 报修工单实体属性图”"), comments);
      assertTrue(comments.contains("图题开头有 5 个普通空格"), comments);
      assertTrue(comments.contains("编号和图题之间的一个空格是对的"), comments);
      assertTrue(comments.contains("请删掉图号前的空格，再把整段设为居中"), comments);
      assertFalse(comments.contains("XML") || comments.contains("twips") || comments.contains("段落属性"), comments);
    }
  }

  @Test
  void explainsWhitespaceBetweenFigureLabelAndNumberWithTheCorrectCaption() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:p><w:r><w:t>图 4-1 总体架构图</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("figure-caption-number-space.docx");
    var finding = new CheckResult("FIG-03", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "图章号-序号 + 1 空格 + 图题", "图 4-1 总体架构图", "FIGURE_CAPTION_NUMBER_FORMAT",
        new SourceAddress("word/document.xml", List.of(0, 0), 0, 11));

    new CommentWriter().writeCopy(source, target, List.of(finding));

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(comments.contains("“图”和编号之间多了 1 个普通空格"), comments);
      assertTrue(comments.contains("图4-1 总体架构图"), comments);
    }
  }

  @Test
  void givesTableCaptionSpacingCorrectionUsingItsActualNumberAndTitle() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:p><w:r><w:t>表4-6执行器状态表</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("table-caption-number-space.docx");
    var finding = new CheckResult("TAB-03", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "表章号-序号 + 1 空格 + 表题", "表4-6执行器状态表", "TABLE_CAPTION_NUMBER_FORMAT",
        new SourceAddress("word/document.xml", List.of(0, 0), 0, 10));

    new CommentWriter().writeCopy(source, target, List.of(finding));

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(comments.contains("“表4-6”和表题直接连在一起"), comments);
      assertTrue(comments.contains("表4-6 执行器状态表"), comments);
    }
  }

  @Test
  void doesNotHighlightParagraphLevelIndentOrLineSpacingErrors() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:p><w:r><w:t>正文段落</w:t></w:r></w:p><w:p><w:r><w:t>摘要行距</w:t></w:r></w:p>"
        + "</w:body></w:document>");
    Path target = tempDir.resolve("valid-highlight-values.docx");
    var findings = List.of(
        new CheckResult("BODY-INDENT", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
            Judgement.FAIL, "480", "0", "INDENT_MISMATCH", new SourceAddress("word/document.xml", List.of(0, 0), 0, 4)),
        new CheckResult("ABSTRACT-LINE", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
            Judgement.FAIL, "360", "240", "LINE_SPACING_MISMATCH", new SourceAddress("word/document.xml", List.of(0, 1), 0, 4)));

    AnnotationReport report = new CommentWriter().writeCopy(source, target, findings);

    assertTrue(report.outputWritten());
    try (ZipFile zip = new ZipFile(target.toFile())) {
      String document = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
      // 首行缩进是段落属性，没有可准确映射的文字范围；只写批注，不染整段正文。
      assertFalse(document.contains("w:highlight"), document);
      assertTrue(document.contains("commentRangeStart"), document);
    }
  }

  @Test
  void givesNewRuleFamiliesStableHighlightColors() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:p><w:r><w:t>正文行距</w:t></w:r></w:p>"
        + "<w:p><w:r><w:t>正文单位</w:t></w:r></w:p>"
        + "<w:p><w:r><w:t>表格单位</w:t></w:r></w:p>"
        + "<w:p><w:r><w:t>公式布局</w:t></w:r></w:p>"
        + "<w:p><w:r><w:t>公式变量</w:t></w:r></w:p>"
        + "<w:p><w:r><w:t>封面标签</w:t></w:r></w:p>"
        + "</w:body></w:document>");
    Path target = tempDir.resolve("new-rule-colors.docx");
    var findings = List.of(
        new CheckResult("BODY-LINE", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
            Judgement.FAIL, "1.5", "1.0", "LINE_SPACING_MISMATCH", new SourceAddress("word/document.xml", List.of(0, 0), 0, 4)),
        new CheckResult("BODY-UNIT", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
            Judgement.FAIL, "统一", "cm/厘米混用", "UNIT_STYLE_INCONSISTENT", new SourceAddress("word/document.xml", List.of(0, 1), 0, 4)),
        new CheckResult("TAB-04", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
            Judgement.REVIEW, "表头包含量名称和单位", "未检测到", "TABLE_UNIT_LOCATION_NOT_CAPTURED", new SourceAddress("word/document.xml", List.of(0, 2), 0, 4)),
        new CheckResult("EQ-03", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
            Judgement.REVIEW, "公式居中；编号右对齐", "未知", "EQUATION_LAYOUT_REVIEW", new SourceAddress("word/document.xml", List.of(0, 3), 0, 4)),
        new CheckResult("EQ-07", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
            Judgement.REVIEW, "变量有说明", "未检测到", "EQUATION_VARIABLES_REVIEW", new SourceAddress("word/document.xml", List.of(0, 4), 0, 4)),
        new CheckResult("COVER-02-FONT", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
            Judgement.REVIEW, "黑体", "无法拆分", "COVER_TITLE_LABEL_AND_VALUE_SHARE_RUN", new SourceAddress("word/document.xml", List.of(0, 5), 0, 4)));

    AnnotationReport report = new CommentWriter().writeCopy(source, target, findings);

    assertTrue(report.outputWritten());
    try (ZipFile zip = new ZipFile(target.toFile())) {
      String document = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertFalse(document.contains("w:val=\"darkMagenta\""), "paragraph-level line spacing must not color text");
      assertTrue(document.contains("w:val=\"magenta\""));
      assertTrue(document.contains("w:val=\"lightGray\""));
      assertEquals(5, Pattern.compile("<w:highlight\\b").matcher(document).results().count());
      var paragraphs = Pattern.compile("<w:p\\b.*?</w:p>", Pattern.DOTALL).matcher(document);
      assertTrue(paragraphs.find());
      assertFalse(paragraphs.group().contains("w:highlight"), "line-spacing errors must not color paragraph text");
    }
  }

  @Test
  void givesContentsEntryFormatErrorsTheirRuleCategoryColors() throws Exception {
    StringBuilder body = new StringBuilder("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>");
    for (int index = 0; index < 5; index++)
      body.append("<w:p><w:r><w:t>目录条目").append(index).append("</w:t></w:r></w:p>");
    body.append("</w:body></w:document>");
    Path source = packageWithDocument(body.toString());
    Path target = tempDir.resolve("contents-entry-rule-colors.docx");
    String[] ruleIds = {"TOC-ENTRY-FONT", "TOC-ENTRY-SIZE", "TOC-ENTRY-LINE", "TOC-ENTRY-INDENT", "TOC-ENTRY-PAGE"};
    var findings = java.util.stream.IntStream.range(0, ruleIds.length).mapToObj(index ->
        new CheckResult(ruleIds[index], Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
            Judgement.FAIL, "规范", "实际", "FORMAT_MISMATCH",
            new SourceAddress("word/document.xml", List.of(0, index), 0, 5)))
        .toList();

    AnnotationReport report = new CommentWriter().writeCopy(source, target, findings);

    assertTrue(report.outputWritten());
    try (ZipFile zip = new ZipFile(target.toFile())) {
      String document = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
      var paragraphs = Pattern.compile("<w:p\\b.*?</w:p>", Pattern.DOTALL).matcher(document);
      List<String> actualColors = new ArrayList<>();
      int index = 0;
      while (paragraphs.find()) {
        boolean highlighted = paragraphs.group().contains("w:highlight");
        if (index == 2 || index == 3) {
          assertFalse(highlighted, "paragraph-level TOC rules must not color text: " + ruleIds[index]);
        } else {
          assertTrue(highlighted, "text-level TOC rule should identify its entry: " + ruleIds[index]);
          Matcher color = Pattern.compile("w:val=\"([^\"]+)\"").matcher(paragraphs.group());
          assertTrue(color.find(), paragraphs.group());
          actualColors.add(color.group(1));
        }
        index++;
      }
      assertEquals(5, index);
      assertEquals(List.of("cyan", "yellow", "red"), actualColors);
    }
  }

  @Test
  void givesExistingObjectRuleFamiliesExplicitHighlightColors() throws Exception {
    StringBuilder body = new StringBuilder("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>");
    for (int index = 0; index < 10; index++)
      body.append("<w:p><w:r><w:t>规则").append(index).append("</w:t></w:r></w:p>");
    body.append("</w:body></w:document>");
    Path source = packageWithDocument(body.toString());
    Path target = tempDir.resolve("object-rule-colors.docx");
    String[] ruleIds = {"EQ-01", "EQ-03", "EQ-06", "TAB-02", "TAB-04", "FIG-04", "REF-02", "REF-04", "REF-07", "CITE-01"};
    var findings = java.util.stream.IntStream.range(0, ruleIds.length).mapToObj(index ->
        new CheckResult(ruleIds[index], Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
            Judgement.FAIL, "规范", "实际", "FORMAT_MISMATCH", new SourceAddress("word/document.xml", List.of(0, index), 0, 3)))
        .toList();

    AnnotationReport report = new CommentWriter().writeCopy(source, target, findings);

    assertTrue(report.outputWritten());
    try (ZipFile zip = new ZipFile(target.toFile())) {
      String document = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
      var paragraphs = Pattern.compile("<w:p\\b.*?</w:p>", Pattern.DOTALL).matcher(document);
      String[] expectedColors = {"yellow", "lightGray", "lightGray", "lightGray", "magenta", "yellow", "magenta", "magenta", "red", "yellow"};
      int index = 0;
      while (paragraphs.find() && index < expectedColors.length) {
        assertTrue(paragraphs.group().contains("w:val=\"" + expectedColors[index] + "\""),
            "rule " + ruleIds[index] + " should use " + expectedColors[index]);
        index++;
      }
      assertEquals(expectedColors.length, index);
    }
  }

  @Test
  void writesAConcreteReferenceNumberSpacingSuggestion() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>[1]作者</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("reference-number-spacing.docx");
    var finding = new CheckResult("REF-07", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "编号后一个普通半角空格", "编号后没有空格", "REFERENCE_NUMBER_SPACING_MISMATCH",
        new SourceAddress("word/document.xml", List.of(0, 0), 0, 4));

    new CommentWriter().writeCopy(source, target, List.of(finding));

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(comments.contains("参考文献编号后缺少空格，请在编号后补一个普通半角空格，再接文献内容。"), comments);
    }
  }

  @Test
  void referenceNumberSpacingAdviceExplainsHowToFixMultipleSpaces() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>[1]  作者</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("reference-number-multiple-spaces.docx");
    var finding = new CheckResult("REF-07", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "编号后一个普通半角空格", "编号后连续 2 个普通半角空格",
        "REFERENCE_NUMBER_SPACING_MISMATCH", new SourceAddress("word/document.xml", List.of(0, 0), 0, 4));

    new CommentWriter().writeCopy(source, target, List.of(finding));

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(comments.contains("参考文献编号后有 2 个普通半角空格，请删除多余空格，只保留一个普通半角空格，再接文献内容。"), comments);
    }
  }

  @Test
  void placesZeroWidthReferenceSpacingCommentAfterNumberInsideSingleRun() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:rPr><w:b/></w:rPr><w:t>[1]作者</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("reference-number-zero-width-single-run.docx");
    var finding = new CheckResult("REF-07", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "编号后一个普通半角空格", "编号后没有空格", "REFERENCE_NUMBER_SPACING_MISMATCH",
        new SourceAddress("word/document.xml", List.of(0, 0), 3, 3));

    AnnotationReport report = new CommentWriter().writeCopy(source, target, List.of(finding));

    assertEquals(AnnotationStatus.WRITTEN, report.items().get(0).status());
    try (ZipFile zip = new ZipFile(target.toFile())) {
      String document = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(document.matches("(?s).*<w:r><w:rPr><w:b/></w:rPr><w:t>\\[1\\]</w:t></w:r><w:commentRangeStart w:id=\\\"0\\\"/><w:commentRangeEnd w:id=\\\"0\\\"/>.*<w:r><w:rPr><w:b/></w:rPr><w:t>作者</w:t></w:r>.*"), document);
      assertFalse(document.matches("(?s).*<w:commentRangeStart w:id=\\\"0\\\"/>.*<w:t>\\[1\\]作者</w:t>.*<w:commentRangeEnd w:id=\\\"0\\\"/>.*"), document);
    }
  }

  @Test
  void placesZeroWidthReferenceSpacingCommentBetweenRunsWithoutDroppingRunProperties() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p>"
        + "<w:r><w:rPr><w:b/></w:rPr><w:t>[1]</w:t></w:r>"
        + "<w:r><w:rPr><w:i/></w:rPr><w:t>作者</w:t></w:r>"
        + "</w:p></w:body></w:document>");
    Path target = tempDir.resolve("reference-number-zero-width-cross-run.docx");
    var finding = new CheckResult("REF-07", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "编号后一个普通半角空格", "编号后没有空格", "REFERENCE_NUMBER_SPACING_MISMATCH",
        new SourceAddress("word/document.xml", List.of(0, 0), 3, 3));

    AnnotationReport report = new CommentWriter().writeCopy(source, target, List.of(finding));

    assertEquals(AnnotationStatus.WRITTEN, report.items().get(0).status());
    try (ZipFile zip = new ZipFile(target.toFile())) {
      String document = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(document.matches("(?s).*<w:r><w:rPr><w:b/></w:rPr><w:t>\\[1\\]</w:t></w:r><w:commentRangeStart w:id=\\\"0\\\"/><w:commentRangeEnd w:id=\\\"0\\\"/>.*<w:r><w:rPr><w:i/></w:rPr><w:t>作者</w:t></w:r>.*"), document);
      assertTrue(document.contains("<w:b/>") && document.contains("<w:i/>"), document);
    }
  }

  @Test
  void placesZeroWidthReferenceCommentBetweenTextNodesInOneRunAndPreservesSpace() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:rPr><w:b/></w:rPr>"
        + "<w:t>[1]</w:t><w:t xml:space=\"preserve\"> 作者</w:t>"
        + "</w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("reference-number-zero-width-multiple-text-nodes.docx");
    var finding = new CheckResult("REF-07", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "编号后一个普通半角空格", "编号后没有空格", "REFERENCE_NUMBER_SPACING_MISMATCH",
        new SourceAddress("word/document.xml", List.of(0, 0), 3, 3));

    AnnotationReport report = new CommentWriter().writeCopy(source, target, List.of(finding));

    assertEquals(AnnotationStatus.WRITTEN, report.items().get(0).status());
    try (ZipFile zip = new ZipFile(target.toFile())) {
      String document = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(document.matches("(?s).*<w:r><w:rPr><w:b/></w:rPr><w:t>\\[1\\]</w:t></w:r><w:commentRangeStart w:id=\\\"0\\\"/><w:commentRangeEnd w:id=\\\"0\\\"/>.*<w:r><w:rPr><w:b/></w:rPr><w:t xml:space=\\\"preserve\\\"> 作者</w:t></w:r>.*"), document);
    }
  }

  @Test
  void keepsZeroWidthReferenceAnchorWhenParagraphHasAnotherFinding() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>[1]作者</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("reference-number-zero-width-with-other-finding.docx");
    var paragraphFinding = new CheckResult("BODY-FONT", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "宋体", "Arial", "FONT_MISMATCH",
        new SourceAddress("word/document.xml", List.of(0, 0), 0, 5));
    var spacingFinding = new CheckResult("REF-07", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "编号后一个普通半角空格", "编号后没有空格", "REFERENCE_NUMBER_SPACING_MISMATCH",
        new SourceAddress("word/document.xml", List.of(0, 0), 3, 3));

    AnnotationReport report = new CommentWriter().writeCopy(source, target,
        List.of(paragraphFinding, spacingFinding));

    assertTrue(report.outputWritten());
    assertEquals(2, report.items().stream().filter(item -> item.status() == AnnotationStatus.WRITTEN).count());
    try (ZipFile zip = new ZipFile(target.toFile())) {
      String document = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(document.matches("(?s).*<w:t>\\[1\\]</w:t></w:r><w:commentRangeStart w:id=\\\"0\\\"/><w:commentRangeEnd w:id=\\\"0\\\"/>.*<w:t>作者</w:t>.*"), document);
    }
  }

  @Test
  void mergesRunAndParagraphFindingsIntoOnePreciseParagraphComment() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p>"
        + "<w:r><w:rPr><w:b/></w:rPr><w:t>[1]</w:t></w:r>"
        + "<w:r><w:t>作者</w:t></w:r>"
        + "</w:p></w:body></w:document>");
    Path target = tempDir.resolve("reference-number-zero-width-cross-source-path.docx");
    var runFinding = new CheckResult("REF-FONT", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "宋体", "Arial", "FONT_MISMATCH",
        new SourceAddress("word/document.xml", List.of(0, 0, 0), 0, 3));
    var spacingFinding = new CheckResult("REF-07", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "编号后一个普通半角空格", "编号后没有空格", "REFERENCE_NUMBER_SPACING_MISMATCH",
        new SourceAddress("word/document.xml", List.of(0, 0), 3, 3));

    AnnotationReport report = new CommentWriter().writeCopy(source, target,
        List.of(runFinding, spacingFinding));

    assertTrue(report.outputWritten());
    try (ZipFile zip = new ZipFile(target.toFile())) {
      String document = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertEquals(1, Pattern.compile("<w:comment\\b").matcher(comments).results().count());
      assertTrue(document.matches("(?s).*<w:t>\\[1\\]</w:t></w:r><w:commentRangeStart w:id=\\\"0\\\"/><w:commentRangeEnd w:id=\\\"0\\\"/>.*<w:t>作者</w:t>.*"), document);
    }
  }

  @Test
  void preservesZeroWidthAnchorInsideComplexRunWithoutWrappingWholeParagraph() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>[1]</w:t><w:tab/><w:t>作者</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("reference-number-zero-width-complex-run.docx");
    var finding = new CheckResult("REF-07", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "编号后一个普通半角空格", "制表符", "REFERENCE_NUMBER_SPACING_MISMATCH",
        new SourceAddress("word/document.xml", List.of(0, 0), 3, 3));

    AnnotationReport report = new CommentWriter().writeCopy(source, target, List.of(finding));

    assertTrue(report.outputWritten());
    assertEquals(AnnotationStatus.WRITTEN, report.items().get(0).status());
    try (ZipFile zip = new ZipFile(target.toFile())) {
      String document = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertEquals("", commentRangeText(document, "0"));
      assertTrue(document.contains("<w:commentRangeStart w:id=\"0\"/><w:commentRangeEnd w:id=\"0\"/>"), document);
    }
  }

  @Test
  void explainsThatAutomaticReferenceNumberingMustBeReplacedWithManualText() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>作者. 题名[J]. 期刊, 2024.</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("reference-auto-number-spacing.docx");
    var finding = new CheckResult("NUMBERING-01", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "使用手动输入的编号", "自动列表编号",
        "AUTOMATIC_NUMBERING_FORBIDDEN", new SourceAddress("word/document.xml", List.of(0, 0), 0, 4));

    new CommentWriter().writeCopy(source, target, List.of(finding));

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(comments.contains("取消该段的编号或多级列表"), comments);
      assertTrue(comments.contains("手动输入原序号"), comments);
      assertFalse(comments.contains("编号之后"), comments);
    }
  }

  @Test
  void explainsUnexpectedIndentPropertiesOnLowerLevelHeadings() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>（1）测试标题</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("lower-heading-indent-advice.docx");
    var finding = new CheckResult("HEAD-05-INDENT", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "firstLineChars=200 / firstLine=420 twips", "存在额外的左缩进、右缩进或悬挂缩进",
        "INDENT_MISMATCH", new SourceAddress("word/document.xml", List.of(0, 0), 0, 6));

    new CommentWriter().writeCopy(source, target, List.of(finding));

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(comments.contains("三级以下标题还设置了多余的左缩进、右缩进或悬挂缩进；请清除这些额外缩进，只保留首行缩进 2 字符。"), comments);
    }
  }

  @Test
  void requestsManualReviewWhenTheNumberingTypeCannotBeConfirmed() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>作者. 题名[M]. 出版社，2024.</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("reference-number-spacing-review.docx");
    var finding = new CheckResult("NUMBERING-01", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.REVIEW, "使用手动输入的编号", "编号设置不完整，无法确认编号类型",
        "AUTOMATIC_NUMBERING_UNCONFIRMED",
        new SourceAddress("word/document.xml", List.of(0, 0), 0, 12));

    new CommentWriter().writeCopy(source, target, List.of(finding));

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(comments.contains("请在 Word/WPS 中检查此处编号"), comments);
      assertTrue(comments.contains("如为自动编号"), comments);
      assertFalse(comments.contains("编号之后"), comments);
    }
  }

  @Test
  void tableSuggestionsRespectOptionalAuxiliaryLinesAndTopLineUnits() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>表格</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("table-suggestions.docx");
    var findings = List.of(
        new CheckResult("TAB-02", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
            Judgement.REVIEW, "three-line table", "TABLE_AUXILIARY_BORDER_REVIEW", "TABLE_AUXILIARY_BORDER_REVIEW",
            new SourceAddress("word/document.xml", List.of(0, 0), 0, 2)),
        new CheckResult("TAB-04", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
            Judgement.FAIL, "表头包含量名称和单位", "温度", "TABLE_UNITS_MISSING",
            new SourceAddress("word/document.xml", List.of(0, 0), 0, 2)));

    new CommentWriter().writeCopy(source, target, findings);

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(comments.contains("必要时可以保留辅助线"));
      assertTrue(comments.contains("“温度”列"));
      assertTrue(comments.contains("单位不同时分别标注，不用统一写在表顶"));
      assertFalse(comments.contains("保持全文单位写法一致"));
      assertFalse(comments.contains("删除不必要的竖线"));
      assertFalse(comments.contains("单位放在括号内"));
    }
  }

  @Test
  void tableCaptionAlignmentReasonsUseTheirSpecificCommentSuggestions() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:p><w:r><w:t>续表4-1</w:t></w:r></w:p>"
        + "<w:p><w:r><w:t>表4-2 运行结果</w:t></w:r></w:p>"
        + "</w:body></w:document>");
    Path target = tempDir.resolve("table-caption-alignment-annotated.docx");
    var findings = List.of(
        new CheckResult("TAB-03", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
            Judgement.FAIL, "right", "left", "TABLE_CONTINUATION_CAPTION_NOT_RIGHT",
            new SourceAddress("word/document.xml", List.of(0, 0), 0, 5)),
        new CheckResult("TAB-03", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
            Judgement.FAIL, "center", "left", "TABLE_CAPTION_NOT_CENTERED",
            new SourceAddress("word/document.xml", List.of(0, 1), 0, 8)));

    new CommentWriter().writeCopy(source, target, findings);

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(comments.contains("续表标记应右对齐"));
      assertTrue(comments.contains("表题应居中显示"));
      assertFalse(comments.contains("请把表题移到表格上方"));
    }
  }

  @Test
  void replacesAnExistingRunHighlightInsteadOfWritingDuplicateProperties() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p>"
        + "<w:r><w:rPr><w:highlight w:val=\"cyan\"/><w:b/></w:rPr><w:t>正文内容</w:t></w:r>"
        + "</w:p></w:body></w:document>");
    Path target = tempDir.resolve("single-highlight-property.docx");

    new CommentWriter().writeCopy(source, target, List.of(result(List.of(0, 0))));

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String document = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertEquals(1, Pattern.compile("<w:highlight\\b").matcher(document).results().count());
      assertTrue(document.contains("w:val=\"magenta\""));
    }
  }

  @Test
  void writesTheCurrentUtcTimestampOnNewComments() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>正文</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("dated-annotated.docx");
    Instant before = Instant.now().minusSeconds(1);

    new CommentWriter().writeCopy(source, target, List.of(result(List.of(0, 0))));

    Instant after = Instant.now().plusSeconds(1);
    try (ZipFile zip = new ZipFile(target.toFile())) {
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8);
      var matcher = Pattern.compile("w:date=\"([^\"]+)\"").matcher(comments);
      assertTrue(matcher.find());
      Instant commentDate = Instant.parse(matcher.group(1));
      assertTrue(!commentDate.isBefore(before) && !commentDate.isAfter(after));
    }
  }

  @Test
  void writesAppendixNumberingIssueIntoWordComment() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>图B1 示例</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("appendix-annotated.docx");
    var finding = new CheckResult("APP-01", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "图A", "图B1 示例", "APPENDIX_OBJECT_LETTER_MISMATCH",
        // “图B1 示例”长度为 6，错误只在附录字母 B。
        new SourceAddress("word/document.xml", List.of(0, 0), 1, 2));

    AnnotationReport report = new CommentWriter().writeCopy(source, target, List.of(finding));

    assertEquals(AnnotationStatus.WRITTEN, report.items().get(0).status());
    try (ZipFile zip = new ZipFile(target.toFile())) {
      String document = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(document.contains("w:val=\"red\""), document);
      assertTrue(comments.contains("附录中的图、表或公式编号字母应与本附录一致。"), comments);
    }
  }

  @Test
  void rejectsChangedSourceHashBeforeWriting() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>正文</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("annotated.docx");

    var report = new CommentWriter().writeCopy(source, target, "0".repeat(64), List.of(result(List.of(0, 0))));

    assertEquals(AnnotationStatus.REJECTED, report.items().get(0).status());
    assertTrue(Files.notExists(target));
  }

  @Test
  void reportsOnlyWriteFailureWhenAnnotatedCopyCannotBeCreated() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>正文</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("missing-directory").resolve("annotated.docx");

    AnnotationReport report = new CommentWriter().writeCopy(source, target, List.of(result(List.of(0, 0))));

    assertFalse(report.outputWritten());
    assertEquals(1, report.items().size());
    assertEquals(AnnotationStatus.REJECTED, report.items().get(0).status());
    assertEquals("WRITE_FAILED", report.items().get(0).reason());
    assertTrue(Files.notExists(target));
  }

  @Test
  void writesCommentToTableParagraphByStructuralPath() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:tbl><w:tr><w:tc><w:p><w:r><w:t>表格内容</w:t></w:r></w:p></w:tc></w:tr></w:tbl></w:body></w:document>");
    Path target = tempDir.resolve("table-annotated.docx");

    AnnotationReport report = new CommentWriter().writeCopy(source, target, List.of(result(List.of(0, 0, 0, 0, 0))));

    assertEquals(AnnotationStatus.WRITTEN, report.items().get(0).status(), report.items().toString());
    assertTrue(Files.exists(target));
  }

  @Test
  void writesCommentToDrawingOnlyParagraph() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:drawing><wp:inline xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\"/></w:drawing></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("drawing-annotated.docx");

    var finding = new CheckResult("FIG-02", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "图题位置正确", "图题缺失", "FIGURE_CAPTION_MISSING",
        new SourceAddress("word/document.xml", List.of(0, 0), 0, 0));
    AnnotationReport report = new CommentWriter().writeCopy(source, target, List.of(finding));

    assertEquals(AnnotationStatus.WRITTEN, report.items().get(0).status());
    assertTrue(Files.exists(target));
  }

  @Test
  void writesHeaderMissingCommentAtAnEmptySectionParagraph() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:pPr><w:sectPr/></w:pPr></w:p></w:body></w:document>");
    Path target = tempDir.resolve("header-missing-empty-anchor.docx");
    var finding = new CheckResult("HEADER-01-MISSING", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "山东石油化工学院本科毕业设计（论文）", "missing default header", "HEADER_MISSING",
        new SourceAddress("word/document.xml", List.of(0, 0), 0, 0));

    AnnotationReport report = new CommentWriter().writeCopy(source, target, List.of(finding));

    assertEquals(AnnotationStatus.WRITTEN, report.items().get(0).status());
    try (ZipFile zip = new ZipFile(target.toFile())) {
      String document = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(document.contains("<w:commentRangeStart w:id=\"0\"/><w:commentRangeEnd w:id=\"0\"/>"), document);
      assertTrue(document.contains("<w:commentReference w:id=\"0\"/>"), document);
      assertTrue(comments.contains("页眉"), comments);
    }
  }

  @Test
  void stillRejectsOrdinaryFindingAtAnEmptyParagraph() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p/></w:body></w:document>");
    Path target = tempDir.resolve("ordinary-empty-anchor.docx");
    var finding = new CheckResult("BODY-INDENT", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "480 twips", "0", "INDENT_MISMATCH",
        new SourceAddress("word/document.xml", List.of(0, 0), 0, 0));

    AnnotationReport report = new CommentWriter().writeCopy(source, target, List.of(finding));

    assertEquals(AnnotationStatus.UNSUPPORTED_ANCHOR, report.items().get(0).status());
    assertEquals("EMPTY_ANCHOR", report.items().get(0).reason());
  }

  @Test
  void writesFigureSpecificCommentWithoutPretendingImageHasTextHighlight() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:drawing/></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("figure-annotated.docx");
    CheckResult finding = new CheckResult("FIG-01", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "body reference to figure", "1-1", "FIGURE_NOT_CITED_IN_BODY", new SourceAddress("word/document.xml", List.of(0, 0), 0, 0));

    new CommentWriter().writeCopy(source, target, List.of(finding));

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String document = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertFalse(document.contains("w:val=\"red\""));
      assertTrue(comments.contains("没有找到这幅图的引用"));
    }
  }

  @Test
  void usesAbstractSpecificCommentAndHighlight() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>摘要内容</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("abstract-annotated.docx");
    var finding = new CheckResult("ABSTRACT-SIZE", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "24", "18", "", new SourceAddress("word/document.xml", List.of(0, 0), 0, 4));

    new CommentWriter().writeCopy(source, target, List.of(finding));

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String document = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(document.contains("w:val=\"yellow\""));
      assertTrue(comments.contains("摘要字号不符合要求；改为小四号。"));
    }
  }

  @Test
  void writesNaturalChineseKeywordSuggestions() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>关键词：甲；乙</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("keywords-annotated.docx");
    var anchor = new SourceAddress("word/document.xml", List.of(0, 0), 0, 7);
    var findings = List.of(
        new CheckResult("ABSTRACT-KEYWORD-BOLD", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
            Judgement.FAIL, "true", "false", "KEYWORD_LABEL_NOT_BOLD", anchor),
        new CheckResult("ABSTRACT-KEYWORD-FORMAT", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
            Judgement.FAIL, "3-5 terms", "2 terms", "KEYWORD_LIST_MISMATCH", anchor),
        new CheckResult("ABSTRACT-KEYWORD-ALIGN", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
            Judgement.FAIL, "both", "left", "KEYWORD_ALIGNMENT_MISMATCH", anchor));

    new CommentWriter().writeCopy(source, target, findings);

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(comments.contains("“关键词”标签需要加粗，后面的词不用加粗。"));
      assertTrue(comments.contains("关键词格式不符合要求；改为 3 至 5 个，用中文冒号引出、中文分号分隔，末尾不加标点。"));
      assertTrue(comments.contains("中文关键词段落应设为两端对齐。"));
    }
  }

  @Test
  void writesEnglishKeywordCountSuggestionInChinese() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>Keywords: one; two; three; four; five; six</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("english-keyword-count-annotated.docx");
    var finding = new CheckResult("EN-03-FORMAT", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "3-5", "6", "ENGLISH_KEYWORD_COUNT_MISMATCH",
        new SourceAddress("word/document.xml", List.of(0, 0), 0, 42));

    new CommentWriter().writeCopy(source, target, List.of(finding));

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(comments.contains("英文关键词应为 3 至 5 个，使用分号分隔。"));
    }
  }

  @Test
  void writesTableNotePositionSuggestionInChinese() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>注：说明</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("table-note-annotated.docx");
    var finding = new CheckResult("TAB-07", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "left", "center", "TABLE_NOTE_NOT_LEFT_ALIGNED",
        new SourceAddress("word/document.xml", List.of(0, 0), 0, 4));

    new CommentWriter().writeCopy(source, target, List.of(finding));

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(comments.contains("表注应放在表格下方并左对齐"));
    }
  }

  @Test
  void writesContinuationHeaderSuggestionAtTheCaption() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>续表1-1</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("continuation-header-annotated.docx");
    var finding = new CheckResult("TAB-08", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "项目 | 要求", "项目 | 结果", "TABLE_CONTINUATION_HEADER_MISMATCH",
        new SourceAddress("word/document.xml", List.of(0, 0), 0, 5));

    new CommentWriter().writeCopy(source, target, List.of(finding));

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(comments.contains("续表的表头和原表不一致"));
    }
  }

  @Test
  void writesShortCitationFormattingSuggestion() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>[1]</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("citation-annotated.docx");
    var finding = new CheckResult("CITE-01", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "小四号 Times New Roman / 上标", "format mismatch", "CITATION_FORMAT_MISMATCH",
        new SourceAddress("word/document.xml", List.of(0, 0), 0, 3));

    new CommentWriter().writeCopy(source, target, List.of(finding));

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(comments.contains("\u5f15\u7528\u7f16\u53f7\u8bf7\u8bbe\u4e3a\u5c0f\u56db\u53f7 Times New Roman\uff0c\u5e76\u8bbe\u7f6e\u4e3a\u4e0a\u6807\u3002"));
    }
  }

  @Test
  void doesNotSuggestCitationBeforeSentencePunctuationForLegacyFinding() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>[1]</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("citation-position-annotated.docx");
    var finding = new CheckResult("CITE-01", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "citation position", "citation after text", "CITATION_POSITION_MISMATCH",
        new SourceAddress("word/document.xml", List.of(0, 0), 0, 3));

    new CommentWriter().writeCopy(source, target, List.of(finding));

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(comments.contains("\u5c0f\u56db\u53f7 Times New Roman"));
      assertFalse(comments.contains("\u6807\u70b9\u524d"));
    }
  }

  @Test
  void writesEquationReferenceFindingWithItsOwnColorAndComment() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>(3-1)</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("equation-reference-annotated.docx");
    var finding = new CheckResult("EQ-04", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "body reference to equation 3-1", null, "EQUATION_NOT_REFERENCED",
        new SourceAddress("word/document.xml", List.of(0, 0), 0, 5));

    new CommentWriter().writeCopy(source, target, List.of(finding));

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String document = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(document.contains("w:val=\"red\""));
      assertTrue(comments.contains("正文里没有找到这条公式的引用"));
    }
  }

  @Test
  void writesSpecificChineseSuggestionForEquationNumberWithoutParentheses() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>3-1</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("equation-number-parentheses-annotated.docx");
    var finding = new CheckResult("EQ-01", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "(3-1)", "3-1", "EQUATION_NUMBER_PARENTHESES_MISSING",
        new SourceAddress("word/document.xml", List.of(0, 0), 0, 3));

    AnnotationReport report = new CommentWriter().writeCopy(source, target, List.of(finding));

    assertEquals(AnnotationStatus.WRITTEN, report.items().get(0).status());
    try (ZipFile zip = new ZipFile(target.toFile())) {
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(comments.contains("公式编号请加圆括号，例如“(3-1)”。"));
      assertFalse(comments.contains("EQUATION_NUMBER_PARENTHESES_MISSING"));
    }
  }

  @Test
  void writesPageNumberFeedbackAtItsBodyAnchor() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>第一章 绪论</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("page-number-annotated.docx");
    var finding = new CheckResult("PAGE-05", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "center", "left", "PAGE_NUMBER_NOT_CENTERED",
        // 页码居中是页脚段落属性，正文锚点只承载批注，不应把正文标题整段染色。
        new SourceAddress("word/document.xml", List.of(0, 0), 0, 0));

    AnnotationReport report = new CommentWriter().writeCopy(source, target, List.of(finding));

    assertEquals(AnnotationStatus.WRITTEN, report.items().get(0).status());
    try (ZipFile zip = new ZipFile(target.toFile())) {
      String document = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertFalse(document.contains("w:highlight"), document);
      assertTrue(comments.contains("页码格式不符合要求；将页脚设为居中，字体改为五号 Times New Roman。"), comments);
    }
  }

  @Test
  void reportsUnsupportedStoryAnchorWithItsOriginalAddress() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>正文</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("unsupported-annotated.docx");
    var anchor = new SourceAddress("word/footer1.xml", List.of(0, 1), 0, 4);
    var finding = new CheckResult("PAGE-05", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "center", "left", "PAGE_NUMBER_NOT_CENTERED", anchor);

    AnnotationReport report = new CommentWriter().writeCopy(source, target, List.of(finding));

    assertEquals(false, report.outputWritten());
    assertEquals(AnnotationStatus.UNSUPPORTED_ANCHOR, report.items().get(0).status());
    assertEquals("PART_NOT_SUPPORTED", report.items().get(0).reason());
    assertEquals(anchor, report.items().get(0).source());
  }

  @Test
  void writesHeaderCommentThroughMainDocumentRelationshipAndPreservesModernCommentParts() throws Exception {
    Path source = packageWithParts(Map.of(
        "[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
            + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
            + "<Override PartName=\"/word/header1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml\"/>"
            + "</Types>",
        "word/document.xml", "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
            + "<w:p><w:r><w:t>正文</w:t></w:r></w:p><w:sectPr><w:headerReference w:type=\"default\" r:id=\"rId1\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"/></w:sectPr>"
            + "</w:body></w:document>",
        "word/_rels/document.xml.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/header\" Target=\"header1.xml\"/>"
            + "</Relationships>",
        "word/header1.xml", "<w:hdr xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:p><w:r><w:t>页眉文本</w:t></w:r></w:p></w:hdr>",
        "word/commentsExtended.xml", "<w15:commentsEx xmlns:w15=\"http://schemas.microsoft.com/office/word/2012/wordml\"><w15:commentEx w15:paraId=\"01234567\" w15:done=\"0\"/></w15:commentsEx>",
        "word/commentsIds.xml", "<w16cid:commentsIds xmlns:w16cid=\"http://schemas.microsoft.com/office/word/2016/wordml/cid\"/>",
        "word/people.xml", "<w15:people xmlns:w15=\"http://schemas.microsoft.com/office/word/2012/wordml\"/>"));
    Path target = tempDir.resolve("header-annotated.docx");
    var finding = new CheckResult("PAGE-05", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "center", "left", "PAGE_NUMBER_NOT_CENTERED",
        new SourceAddress("word/header1.xml", List.of(0), 0, 4));

    AnnotationReport report = new CommentWriter().writeCopy(source, target, List.of(finding));

    assertEquals(AnnotationStatus.WRITTEN, report.items().get(0).status());
    try (ZipFile zip = new ZipFile(target.toFile())) {
      String header = new String(zip.getInputStream(zip.getEntry("word/header1.xml")).readAllBytes(), StandardCharsets.UTF_8);
      String documentRelationships = new String(zip.getInputStream(zip.getEntry("word/_rels/document.xml.rels")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(header.contains("commentRangeStart"));
      assertEquals(1, documentRelationships.split("Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/comments\"", -1).length - 1);
      assertEquals(null, zip.getEntry("word/_rels/header1.xml.rels"));
      assertTrue(zip.getEntry("word/commentsExtended.xml") != null);
      assertTrue(zip.getEntry("word/commentsIds.xml") != null);
      assertTrue(zip.getEntry("word/people.xml") != null);
      String commentsExtended = new String(zip.getInputStream(zip.getEntry("word/commentsExtended.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(commentsExtended.contains("01234567"));
      var paraIdMatcher = Pattern.compile("w14:paraId=\"([A-F0-9]{8})\"").matcher(
          new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(), StandardCharsets.UTF_8));
      assertTrue(paraIdMatcher.find());
      String newParaId = paraIdMatcher.group(1);
      assertTrue(commentsExtended.contains("w15:paraId=\"01234567\""), commentsExtended);
      assertTrue(commentsExtended.contains("w15:paraId=\"" + newParaId + "\""), commentsExtended);
      assertEquals(2, commentsExtended.split("w15:commentEx", -1).length - 1, commentsExtended);
      assertTrue(commentsExtended.contains("w15:done=\"0\""), commentsExtended);
      String people = new String(zip.getInputStream(zip.getEntry("word/people.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(people.contains("w15:person") && people.contains("论文格式筛查"), people);
      assertTrue(documentRelationships.contains("/commentsExtended") && documentRelationships.contains("/people"), documentRelationships);
      String commentsIds = new String(zip.getInputStream(zip.getEntry("word/commentsIds.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(commentsIds.matches("(?s).*w16cid:commentId[^>]*w16cid:(?:paraId=\"[A-F0-9]{8}\"[^>]*durableId=\"[A-F0-9]{8}\"|durableId=\"[A-F0-9]{8}\"[^>]*paraId=\"[A-F0-9]{8}\").*"), commentsIds);
      assertTrue(documentRelationships.contains("/commentsIds"), documentRelationships);
    }
  }

  @Test
  void allocatesCommentParagraphIdsDistinctFromIdsInOtherWordParts() throws Exception {
    Map<String, byte[]> parts = Map.of(
        "word/document.xml", "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" xmlns:w14=\"http://schemas.microsoft.com/office/word/2010/wordml\"><w:body><w:p w14:paraId=\"A1B2C3D4\"/></w:body></w:document>".getBytes(StandardCharsets.UTF_8),
        "word/header1.xml", "<w:hdr xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" xmlns:w14=\"http://schemas.microsoft.com/office/word/2010/wordml\"><w:p w14:paraId=\"11223344\"/></w:hdr>".getBytes(StandardCharsets.UTF_8),
        "word/commentsExtended.xml", "<w15:commentsEx xmlns:w15=\"http://schemas.microsoft.com/office/word/2012/wordml\"><w15:commentEx w15:paraId=\"55667788\"/></w15:commentsEx>".getBytes(StandardCharsets.UTF_8));
    Set<String> used = new HashSet<>();

    CommentWriter.collectParaIds(parts, used);

    assertTrue(used.containsAll(Set.of("A1B2C3D4", "11223344", "55667788")), used.toString());
  }

  @Test
  void createsAnIndependentClosedRangeForARepeatedCommentOnTheSameParagraph() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>正文内容</w:t></w:r></w:p></w:body></w:document>");
    Path first = tempDir.resolve("same-paragraph-first.docx");
    Path second = tempDir.resolve("same-paragraph-second.docx");
    var finding = findingAt("BODY-FONT", "word/document.xml", List.of(0, 0));

    assertTrue(new CommentWriter().writeCopy(source, first, List.of(finding)).outputWritten());
    assertTrue(new CommentWriter().writeCopy(first, second, List.of(finding)).outputWritten());

    try (ZipFile zip = new ZipFile(second.toFile())) {
      String document = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
      var startIds = Pattern.compile("<w:commentRangeStart w:id=\"(\\d+)\"/>").matcher(document);
      var endIds = Pattern.compile("<w:commentRangeEnd w:id=\"(\\d+)\"/>").matcher(document);
      List<String> starts = new ArrayList<>();
      List<String> ends = new ArrayList<>();
      while (startIds.find()) starts.add(startIds.group(1));
      while (endIds.find()) ends.add(endIds.group(1));
      assertEquals(Set.of("0", "1"), Set.copyOf(starts));
      assertEquals(Set.of("0", "1"), Set.copyOf(ends));
      for (String id : starts) {
        int start = document.indexOf("<w:commentRangeStart w:id=\"" + id + "\"/>");
        int end = document.indexOf("<w:commentRangeEnd w:id=\"" + id + "\"/>");
        assertTrue(start >= 0 && end > start, "comment range " + id + " must be closed");
      }
      assertEquals(2, document.split("commentReference", -1).length - 1);
    }
  }

  @Test
  void writesAndReadsAThreadedReplyWithAnExplicitParentCommentId() throws Exception {
    assertTrue(java.util.Arrays.stream(CommentWriter.class.getMethods())
        .anyMatch(method -> method.getName().equals("writeCopy") && method.getParameterCount() == 5),
        "thread replies need an explicit parent id input instead of pretending each reply is a document annotation");
  }

  @Test
  void threadsCanBeReadBackFromAllModernParts() throws Exception {
    assertTrue(java.util.Arrays.stream(CommentWriter.class.getMethods())
        .anyMatch(method -> method.getName().equals("readThreads") && method.getParameterCount() == 1),
        "modern comment links need a readback surface for round-trip verification");
  }

  @Test
  void refusesToReplaceExistingCommentMetadataWithUnexpectedRoots() throws Exception {
    Map<String, String> invalidParts = Map.of(
        "word/comments.xml", "<w:other xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"/>",
        "word/commentsExtended.xml", "<w15:other xmlns:w15=\"http://schemas.microsoft.com/office/word/2012/wordml\"/>",
        "word/people.xml", "<w15:other xmlns:w15=\"http://schemas.microsoft.com/office/word/2012/wordml\"/>");

    for (var invalidPart : invalidParts.entrySet()) {
      Map<String, String> parts = new java.util.LinkedHashMap<>();
      parts.put("[Content_Types].xml",
          "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
              + "<Override PartName=\"/word/document.xml\" ContentType=\"application/xml\"/>"
              + "</Types>");
      parts.put("word/document.xml",
          "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
              + "<w:body><w:p><w:r><w:t>paragraph</w:t></w:r></w:p></w:body></w:document>");
      parts.put("word/_rels/document.xml.rels",
          "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"/>");
      parts.put(invalidPart.getKey(), invalidPart.getValue());
      Path source = packageWithParts(parts);
      Path target = tempDir.resolve(invalidPart.getKey().replace('/', '-') + "-must-not-write.docx");

      AnnotationReport report = new CommentWriter().writeCopy(source, target,
          List.of(findingAt("BODY-FONT", "word/document.xml", List.of(0, 0))));

      assertFalse(report.outputWritten(), invalidPart.getKey());
      assertFalse(Files.exists(target), invalidPart.getKey());
      assertEquals(AnnotationStatus.REJECTED, report.items().get(0).status(), invalidPart.getKey());
    }
  }

  @Test
  void rejectsInvalidSingleIndexAnchorInsteadOfUsingAParagraphOrdinal() throws Exception {
    Path source = packageWithDocument(
        "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
            + "<w:p><w:r><w:t>first</w:t></w:r></w:p>"
            + "<w:p><w:r><w:t>second</w:t></w:r></w:p>"
            + "</w:body></w:document>");
    Path target = tempDir.resolve("invalid-single-index.docx");
    var finding = findingAt("BODY-FONT", "word/document.xml", List.of(1));

    AnnotationReport report = new CommentWriter().writeCopy(source, target, List.of(finding));

    assertFalse(report.outputWritten());
    assertFalse(Files.exists(target));
    assertEquals(AnnotationStatus.UNSUPPORTED_ANCHOR, report.items().get(0).status());
    assertEquals("PATH_OUT_OF_RANGE", report.items().get(0).reason());
  }

  @Test
  void writesCommentsToFooterFootnoteAndEndnoteStories() throws Exception {
    Path source = packageWithParts(Map.of(
        "[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
            + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
            + "</Types>",
        "word/document.xml", "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
            + "<w:p><w:r><w:t>正文</w:t></w:r></w:p><w:sectPr/></w:body></w:document>",
        "word/footer1.xml", "<w:ftr xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:p><w:r><w:t>页脚文本</w:t></w:r></w:p></w:ftr>",
        "word/footnotes.xml", "<w:footnotes xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:footnote w:id=\"1\"><w:p><w:r><w:t>脚注文本</w:t></w:r></w:p></w:footnote></w:footnotes>",
        "word/endnotes.xml", "<w:endnotes xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:endnote w:id=\"1\"><w:p><w:r><w:t>尾注文本</w:t></w:r></w:p></w:endnote></w:endnotes>"));
    Path target = tempDir.resolve("stories-annotated.docx");
    var findings = List.of(
        findingAt("PAGE-05", "word/footer1.xml", List.of(0)),
        findingAt("BODY-FONT", "word/footnotes.xml", List.of(0, 0)),
        findingAt("BODY-SIZE", "word/endnotes.xml", List.of(0, 0)));

    AnnotationReport report = new CommentWriter().writeCopy(source, target, findings);

    assertEquals(3, report.items().size());
    assertTrue(report.items().stream().allMatch(item -> item.status() == AnnotationStatus.WRITTEN));
    try (ZipFile zip = new ZipFile(target.toFile())) {
      for (String part : List.of("word/footer1.xml", "word/footnotes.xml", "word/endnotes.xml")) {
        String xml = new String(zip.getInputStream(zip.getEntry(part)).readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(xml.contains("commentRangeStart"), part);
        assertTrue(xml.contains("commentRangeEnd"), part);
        assertTrue(xml.contains("commentReference"), part);
        assertEquals(null, zip.getEntry("word/_rels/" + part.substring("word/".length()) + ".rels"));
      }
      String documentRelationships = new String(zip.getInputStream(zip.getEntry("word/_rels/document.xml.rels")).readAllBytes(), StandardCharsets.UTF_8);
      assertEquals(1, documentRelationships.split("Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/comments\"", -1).length - 1);
    }
  }

  @Test
  void writesCommentsInsideTextBoxesAndTrackedChanges() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:p><w:r><w:t>外层段落</w:t><w:txbxContent><w:p><w:r><w:t>文本框文本</w:t></w:r></w:p></w:txbxContent></w:r></w:p>"
        + "<w:p><w:ins w:id=\"1\" w:author=\"A\" w:date=\"2026-10-17T00:00:00Z\"><w:r><w:t>插入修订</w:t></w:r></w:ins></w:p>"
        + "<w:p><w:del w:id=\"2\" w:author=\"A\" w:date=\"2026-10-17T00:00:00Z\"><w:r><w:delText>删除修订</w:delText></w:r></w:del></w:p>"
        + "</w:body></w:document>");
    Path target = tempDir.resolve("nested-stories-annotated.docx");
    var findings = List.of(
        findingAt("BODY-FONT", "word/document.xml", List.of(0, 0, 0, 1, 0, 0, 0)),
        findingAt("BODY-SIZE", "word/document.xml", List.of(0, 1)),
        findingAt("BODY-INDENT", "word/document.xml", List.of(0, 2)));

    AnnotationReport report = new CommentWriter().writeCopy(source, target, findings);

    assertEquals(3, report.items().size());
    assertTrue(report.items().stream().allMatch(item -> item.status() == AnnotationStatus.WRITTEN));
    try (ZipFile zip = new ZipFile(target.toFile())) {
      String document = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(document.contains("<w:txbxContent><w:p><w:commentRangeStart"));
      assertTrue(document.matches("(?s).*<w:commentRangeStart w:id=\"1\"/><w:ins\\b[^>]*>.*<w:commentRangeEnd w:id=\"1\"/>.*"));
      assertTrue(document.matches("(?s).*<w:commentRangeStart w:id=\"2\"/><w:del\\b[^>]*>.*<w:commentRangeEnd w:id=\"2\"/>.*"));
    }
  }

  @Test
  void tracksVisibleOffsetsWithoutCountingDeletedOrMovedFromText() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p>"
        + "<w:del w:id=\"1\"><w:r><w:delText>已删除内容</w:delText></w:r></w:del>"
        + "<w:moveFrom w:id=\"2\"><w:r><w:t>已移出内容</w:t></w:r></w:moveFrom>"
        + "<w:r><w:t>目标文字</w:t></w:r>"
        + "</w:p></w:body></w:document>");
    Path target = tempDir.resolve("tracked-text-offsets.docx");
    var finding = new CheckResult("BODY-FONT", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "宋体", "Arial", "FONT_MISMATCH",
        new SourceAddress("word/document.xml", List.of(0, 0, 2), 0, 2));

    AnnotationReport report = new CommentWriter().writeCopy(source, target, List.of(finding));

    assertTrue(report.outputWritten());
    assertEquals("目标", documentCommentRangeText(target, "0"));
  }

  @Test
  void keepsPreciseCommentRangeInsideHyperlinkInsteadOfWrappingParagraph() throws Exception {
    var sourcePackage = org.docx4j.openpackaging.packages.WordprocessingMLPackage.createPackage();
    sourcePackage.getMainDocumentPart().setContents((org.docx4j.wml.Document) org.docx4j.XmlUtils.unmarshalString(
        "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p>"
            + "<w:r><w:t>before </w:t></w:r>"
            + "<w:hyperlink w:anchor=\"target\"><w:r><w:t>bad</w:t></w:r></w:hyperlink>"
            + "<w:r><w:t> after</w:t></w:r>"
            + "</w:p><w:sectPr/></w:body></w:document>"));
    Path source = tempDir.resolve("hyperlink-source.docx");
    sourcePackage.save(source.toFile());
    Path target = tempDir.resolve("hyperlink-precise-range.docx");
    var finding = new CheckResult("CAPTION-FONT", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "宋体", "Arial", "FONT_MISMATCH",
        new SourceAddress("word/document.xml", List.of(0, 0), 7, 10));

    AnnotationReport report = new CommentWriter().writeCopy(source, target, List.of(finding));

    assertTrue(report.outputWritten());
    assertEquals("bad", documentCommentRangeText(target, "0"));
    try (ZipFile zip = new ZipFile(target.toFile())) {
      String document = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
      assertEquals("p", commentMarkerParent(document, "commentRangeStart", "0"));
      assertEquals("p", commentMarkerParent(document, "commentRangeEnd", "0"));
    }
    assertTrue(org.docx4j.openpackaging.packages.WordprocessingMLPackage.load(target.toFile()) != null);
  }

  @Test
  void anchorsOnlyTheThirdChapterSpaceInsideHyperlinkAndPreservesItsContents() throws Exception {
    String wordNamespace = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    String relationshipNamespace = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    var sourcePackage = org.docx4j.openpackaging.packages.WordprocessingMLPackage.createPackage();
    sourcePackage.getMainDocumentPart().setContents((org.docx4j.wml.Document) org.docx4j.XmlUtils.unmarshalString(
        "<w:document xmlns:w=\"" + wordNamespace + "\" xmlns:r=\"" + relationshipNamespace + "\"><w:body><w:p>"
            + "<w:hyperlink r:id=\"rIdTocFixture\" w:anchor=\"_TocChapter1\" w:history=\"1\">"
            + "<w:bookmarkStart w:id=\"20\" w:name=\"TocEntry\"/>"
            + "<w:r><w:rPr><w:b/><w:rFonts w:eastAsia=\"宋体\"/><w:sz w:val=\"24\"/></w:rPr><w:t xml:space=\"preserve\">第一章 </w:t></w:r>"
            + "<w:r><w:rPr><w:i/><w:rFonts w:eastAsia=\"宋体\"/><w:sz w:val=\"24\"/></w:rPr><w:t xml:space=\"preserve\">  引言</w:t></w:r>"
            + "<w:bookmarkEnd w:id=\"20\"/></w:hyperlink><w:r><w:tab/><w:t>1</w:t></w:r>"
            + "</w:p><w:sectPr/></w:body></w:document>"));
    var relationship = new org.docx4j.relationships.Relationship();
    relationship.setId("rIdTocFixture");
    relationship.setType(relationshipNamespace + "/hyperlink");
    relationship.setTarget("https://example.invalid/thesis.docx");
    relationship.setTargetMode("External");
    sourcePackage.getMainDocumentPart().getRelationshipsPart().getRelationships().getRelationship().add(relationship);
    Path source = tempDir.resolve("toc-hyperlink-extra-space-source.docx");
    sourcePackage.save(source.toFile());
    Path target = tempDir.resolve("toc-hyperlink-extra-space-annotated.docx");
    var finding = new CheckResult("TOC-SPACING", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "编号后空 2 格", "普通空格、普通空格、普通空格", "TOC_NUMBER_SPACING_MISMATCH",
        new SourceAddress("word/document.xml", List.of(0, 0), 5, 6));

    AnnotationReport report = new CommentWriter().writeCopy(source, target, List.of(finding));

    assertTrue(report.outputWritten());
    assertEquals(" ", documentCommentRangeText(target, "0"));
    try (ZipFile sourceZip = new ZipFile(source.toFile()); ZipFile zip = new ZipFile(target.toFile())) {
      var factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      var sourceDocument = factory.newDocumentBuilder().parse(sourceZip.getInputStream(sourceZip.getEntry("word/document.xml")));
      var sourceHyperlink = (org.w3c.dom.Element) sourceDocument.getElementsByTagNameNS(wordNamespace, "hyperlink").item(0);
      var document = factory.newDocumentBuilder().parse(zip.getInputStream(zip.getEntry("word/document.xml")));
      var hyperlinks = document.getElementsByTagNameNS(wordNamespace, "hyperlink");
      assertEquals(1, hyperlinks.getLength());
      var hyperlink = (org.w3c.dom.Element) hyperlinks.item(0);
      assertTrue(sourceHyperlink.hasAttributeNS(relationshipNamespace, "id"));
      assertTrue(sourceHyperlink.hasAttributeNS(wordNamespace, "anchor"));
      assertTrue(sourceHyperlink.hasAttributeNS(wordNamespace, "history"));
      assertEquals(sourceHyperlink.getAttributeNS(relationshipNamespace, "id"), hyperlink.getAttributeNS(relationshipNamespace, "id"));
      assertEquals(sourceHyperlink.getAttributeNS(wordNamespace, "anchor"), hyperlink.getAttributeNS(wordNamespace, "anchor"));
      assertEquals(sourceHyperlink.getAttributeNS(wordNamespace, "history"), hyperlink.getAttributeNS(wordNamespace, "history"));
      assertEquals("第一章   引言", wordText(hyperlink));
      var bookmarkStart = (org.w3c.dom.Element) hyperlink.getElementsByTagNameNS(wordNamespace, "bookmarkStart").item(0);
      var bookmarkEnd = (org.w3c.dom.Element) hyperlink.getElementsByTagNameNS(wordNamespace, "bookmarkEnd").item(0);
      assertEquals("TocEntry", bookmarkStart.getAttributeNS(wordNamespace, "name"));
      assertEquals("20", bookmarkStart.getAttributeNS(wordNamespace, "id"));
      assertEquals("20", bookmarkEnd.getAttributeNS(wordNamespace, "id"));
      var highlighted = document.getElementsByTagNameNS(wordNamespace, "highlight");
      assertEquals(1, highlighted.getLength());
      var highlightedRun = (org.w3c.dom.Element) highlighted.item(0).getParentNode().getParentNode();
      assertEquals(" ", wordText(highlightedRun));
      assertEquals(1, highlightedRun.getElementsByTagNameNS(wordNamespace, "i").getLength());
      assertEquals(1, hyperlink.getElementsByTagNameNS(wordNamespace, "b").getLength());
      var textNodes = hyperlink.getElementsByTagNameNS(wordNamespace, "t");
      for (int index = 0; index < textNodes.getLength(); index++) {
        var run = (org.w3c.dom.Element) textNodes.item(index).getParentNode();
        var font = (org.w3c.dom.Element) run.getElementsByTagNameNS(wordNamespace, "rFonts").item(0);
        var size = (org.w3c.dom.Element) run.getElementsByTagNameNS(wordNamespace, "sz").item(0);
        assertEquals("宋体", font.getAttributeNS(wordNamespace, "eastAsia"));
        assertEquals("24", size.getAttributeNS(wordNamespace, "val"));
      }
    }
    var reloaded = org.docx4j.openpackaging.packages.WordprocessingMLPackage.load(target.toFile());
    var retained = reloaded.getMainDocumentPart().getRelationshipsPart().getRelationships().getRelationship().stream()
        .filter(item -> "rIdTocFixture".equals(item.getId())).findFirst().orElseThrow();
    assertEquals(relationship.getType(), retained.getType());
    assertEquals(relationship.getTarget(), retained.getTarget());
    assertEquals(relationship.getTargetMode(), retained.getTargetMode());
    Path roundTrip = tempDir.resolve("toc-hyperlink-extra-space-roundtrip.docx");
    reloaded.save(roundTrip.toFile());
    assertEquals(" ", documentCommentRangeText(roundTrip, "0"));
  }

  @Test
  void reportsFindingWithoutXmlSourceInsteadOfThrowing() throws Exception {
    Path source = packageWithDocument("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>正文</w:t></w:r></w:p></w:body></w:document>");
    Path target = tempDir.resolve("missing-source.docx");
    var finding = new CheckResult("REF-05", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "10", "0", "REFERENCE_COUNT_TOO_LOW", null);

    AnnotationReport report = new CommentWriter().writeCopy(source, target, List.of(finding));

    assertFalse(report.outputWritten());
    assertEquals(1, report.items().size());
    assertEquals(AnnotationStatus.UNSUPPORTED_ANCHOR, report.items().get(0).status());
    assertEquals("MISSING_SOURCE", report.items().get(0).reason());
  }

  private String wordText(org.w3c.dom.Element element) {
    var text = new StringBuilder();
    var textNodes = element.getElementsByTagNameNS(
        "http://schemas.openxmlformats.org/wordprocessingml/2006/main", "t");
    for (int index = 0; index < textNodes.getLength(); index++) {
      text.append(textNodes.item(index).getTextContent());
    }
    return text.toString();
  }

  private CheckResult result(List<Integer> path) {
    return new CheckResult("PAGE-01", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "1474", "1418", "MARGIN_MISMATCH",
        new SourceAddress("word/document.xml", path, 0, 2));
  }

  private Path packageWithDocument(String document) throws Exception {
    Path source = tempDir.resolve("source.docx");
    try (var output = Files.newOutputStream(source); var zip = new ZipOutputStream(output)) {
      put(zip, "[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Override PartName=\"/word/document.xml\" ContentType=\"application/xml\"/></Types>");
      put(zip, "word/document.xml", document);
      put(zip, "word/_rels/document.xml.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"/>");
    }
    return source;
  }

  private CheckResult findingAt(String ruleId, String partName, List<Integer> path) {
    return new CheckResult(ruleId, Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "expected", "actual", "TEST_ANCHOR", new SourceAddress(partName, path, 0, 4));
  }

  private Path packageWithParts(Map<String, String> parts) throws Exception {
    Path source = tempDir.resolve("story-source.docx");
    try (var output = Files.newOutputStream(source); var zip = new ZipOutputStream(output)) {
      for (var part : parts.entrySet()) put(zip, part.getKey(), part.getValue());
    }
    return source;
  }

  private void put(ZipOutputStream zip, String name, String text) throws Exception {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(text.getBytes(StandardCharsets.UTF_8));
    zip.closeEntry();
  }

  private String commentRangeText(String documentXml, String commentId) throws Exception {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    var document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(documentXml)));
    var text = new StringBuilder();
    appendCommentRangeText(document.getDocumentElement(), commentId, new boolean[] {false}, text);
    return text.toString();
  }

  private String documentCommentRangeText(Path annotatedDocument, String commentId) throws Exception {
    try (ZipFile zip = new ZipFile(annotatedDocument.toFile())) {
      String xml = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
      return commentRangeText(xml, commentId);
    }
  }

  private String commentMarkerParent(String documentXml, String markerName, String commentId) throws Exception {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    var document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(documentXml)));
    var markers = document.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main", markerName);
    for (int index = 0; index < markers.getLength(); index++) {
      var marker = (org.w3c.dom.Element) markers.item(index);
      if (commentId.equals(marker.getAttributeNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "id")))
        return marker.getParentNode().getLocalName();
    }
    return null;
  }

  private void appendCommentRangeText(Node node, String commentId, boolean[] inRange, StringBuilder text) {
    if (node.getNodeType() == Node.ELEMENT_NODE) {
      String localName = node.getLocalName();
      if ("commentRangeStart".equals(localName)
          && commentId.equals(((org.w3c.dom.Element) node).getAttributeNS(
              "http://schemas.openxmlformats.org/wordprocessingml/2006/main", "id"))) {
        inRange[0] = true;
        return;
      }
      if ("commentRangeEnd".equals(localName)
          && commentId.equals(((org.w3c.dom.Element) node).getAttributeNS(
              "http://schemas.openxmlformats.org/wordprocessingml/2006/main", "id"))) {
        inRange[0] = false;
        return;
      }
      if (inRange[0] && "t".equals(localName)) {
        text.append(node.getTextContent());
        return;
      }
      if (inRange[0] && "tab".equals(localName)) text.append('\t');
      if (inRange[0] && "cr".equals(localName)) text.append('\n');
    }
    for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling())
      appendCommentRangeText(child, commentId, inRange, text);
  }
}
