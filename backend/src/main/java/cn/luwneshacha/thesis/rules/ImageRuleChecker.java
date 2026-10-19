package cn.luwneshacha.thesis.rules;

import cn.luwneshacha.thesis.document.ImageModel;
import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.RunModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ImageRuleChecker implements RuleModule {
  private static final Pattern FIGURE_NUMBER = Pattern.compile("图[\\s\\p{Zs}]*((?:\\d+[-－]\\d+)|(?:[A-Z]\\d+))(?![A-Za-z\\d-])");
  private static final Pattern UNNUMBERED_FIGURE_REFERENCE = Pattern.compile("(?:如|见|参见)图(?!\\s*\\d)(?:所示|如下)?|下图所示");
  private static final Pattern FIGURE_CAPTION_FORMAT = Pattern.compile("^\\s*图(?:\\d+-\\d+|[A-Z]\\d+) \\S.*");
  private static final Pattern FIGURE_SEQUENCE = Pattern.compile("^\\s*图(\\d+)-(\\d+)(?![\\d-])");
  private static final Pattern TABLE_OR_FIGURE_CAPTION = Pattern.compile("^(续表|表|图)\\s*[0-9一二三四五六七八九十]+", Pattern.CASE_INSENSITIVE);

  private final ParagraphFormatChecker paragraphFormatChecker = new ParagraphFormatChecker();
  private final DocumentRegionClassifier regions = new DocumentRegionClassifier();

  @Override
  public List<CheckResult> check(ParsedDocument document, RuleSet rules) {
    // School rules cover figure captions and references, but do not set an
    // image-size requirement. Use only relationships the parser can prove.
    var results = new ArrayList<CheckResult>();
    for (ImageModel image : document.images()) {
      if (regions.isBeforeMainBody(document, image.source())) continue;
      if (image.captionText() == null) {
        results.add(result("FIG-03", "图下方有图号和图题", "未找到图题", "FIGURE_CAPTION_MISSING",
            Judgement.FAIL, image.source()));
      } else {
        if (!FIGURE_CAPTION_FORMAT.matcher(image.captionText()).matches())
          results.add(result("FIG-03", "图章号-序号 + 1 空格 + 图题", image.captionText(),
              "FIGURE_CAPTION_NUMBER_FORMAT", Judgement.FAIL, image.captionSource()));
        else
          results.add(result("FIG-03", "图号-序号 + 1 空格 + 图题", image.captionText(), "",
              Judgement.PASS, image.captionSource()));
        Matcher figureNumber = FIGURE_NUMBER.matcher(image.captionText());
        if (regions.appendixLetterAt(document, image.source()) == null) {
          if (!figureNumber.find())
            results.add(review("FIG-01", "body reference to figure", "FIGURE_NUMBER_UNKNOWN", image.source()));
          else if (regions.isFigureReferencedInBody(document, figureNumber.group(1), image.captionSource(), FIGURE_NUMBER))
            results.add(result("FIG-01", "body reference to figure " + figureNumber.group(1), figureNumber.group(1), "",
                Judgement.PASS, image.captionSource()));
          else if (regions.hasUnnumberedFigureReference(document, image.captionSource(), FIGURE_NUMBER,
              UNNUMBERED_FIGURE_REFERENCE))
            results.add(result("FIG-01", "正文引用图号", "正文只有未指明图号的引用", "FIGURE_REFERENCE_NEEDS_REVIEW",
                Judgement.FAIL, image.captionSource()));
          else
            results.add(result("FIG-01", "body reference to figure " + figureNumber.group(1), figureNumber.group(1),
                "FIGURE_NOT_CITED_IN_BODY", Judgement.FAIL, image.captionSource()));
        }
      }
      captionParagraph(document, image.captionSource()).ifPresent(paragraph -> addCaptionResults(results, paragraph, rules));
      if (image.captionText() != null) {
        if (Boolean.FALSE.equals(image.captionCentered()))
          results.add(result("FIG-02", "caption below image and centered", "caption is not centered",
              "FIGURE_NOT_CENTERED", Judgement.FAIL, image.captionSource()));
        else if (!image.inline())
          results.add(result("FIG-02", "caption below image and centered", "XML cannot verify image placement",
              "FIGURE_LAYOUT_REVIEW", Judgement.REVIEW, image.source()));
        else if (image.captionCentered() == null)
          results.add(result("FIG-02", "caption below image and centered", "XML cannot verify caption alignment",
              "FIGURE_LAYOUT_REVIEW", Judgement.REVIEW, image.captionSource()));
        else
          results.add(result("FIG-02", "caption below image and centered", "caption alignment verified from DOCX XML", "",
              Judgement.PASS, image.captionSource()));
      }
    }
    checkFigureNumberSequences(results, document);
    return List.copyOf(results);
  }

  private void checkFigureNumberSequences(List<CheckResult> results, ParsedDocument document) {
    Map<String, BigInteger> next = new java.util.HashMap<>();
    Set<SourceAddress> seenCaptions = new LinkedHashSet<>();
    for (ImageModel image : document.images()) {
      if (regions.isBeforeMainBody(document, image.source())
          || regions.appendixLetterAt(document, image.source()) != null) continue;
      SourceAddress captionSource = image.captionSource() == null ? image.source() : image.captionSource();
      if (!seenCaptions.add(captionSource)) continue;
      Matcher matcher = FIGURE_SEQUENCE.matcher(image.captionText() == null ? "" : image.captionText());
      if (!matcher.find()) continue;
      checkNumberSequence(results, next, matcher.group(1), matcher.group(2), image.captionText(), captionSource);
    }
  }

  private void checkNumberSequence(List<CheckResult> results, Map<String, BigInteger> nextByChapter,
      String chapterText, String sequenceText, String actualText, SourceAddress source) {
    BigInteger chapter = new BigInteger(chapterText);
    BigInteger sequence = new BigInteger(sequenceText);
    BigInteger expected = nextByChapter.getOrDefault(chapterText, BigInteger.ONE);
    if (!chapter.toString().equals(chapterText) || !sequence.toString().equals(sequenceText)
        || !sequence.equals(expected))
      results.add(result("FIG-05", "图" + chapter + "-" + expected, actualText,
          "FIGURE_NUMBER_SEQUENCE_MISMATCH", Judgement.FAIL, source));
    else
      results.add(result("FIG-05", "图" + chapter + "-" + expected, actualText,
          "", Judgement.PASS, source));
    nextByChapter.put(chapterText, expected.max(sequence).add(BigInteger.ONE));
  }

  private void addCaptionResults(List<CheckResult> results, ParagraphModel paragraph, RuleSet rules) {
    for (RunModel run : paragraph.runs()) {
      results.add(paragraphFormatChecker.checkFont(run, rules, "CAPTION-FONT"));
      if (run.fontSizeHalfPoints() == null)
        results.add(result("CAPTION-SIZE", "21", "unknown", "SIZE_UNKNOWN", Judgement.REVIEW, run.source()));
      else
        results.add(result("CAPTION-SIZE", "21", String.valueOf(run.fontSizeHalfPoints()), "",
            run.fontSizeHalfPoints() == 21 ? Judgement.PASS : Judgement.FAIL, run.source()));
    }
  }

  private java.util.Optional<ParagraphModel> captionParagraph(ParsedDocument document, SourceAddress source) {
    if (source == null) return java.util.Optional.empty();
    return document.paragraphs().stream()
        .filter(paragraph -> paragraph.source().partName().equals(source.partName())
            && paragraph.source().elementPath().equals(source.elementPath()))
        .findFirst();
  }

  private CheckResult review(String ruleId, String expected, String reason, SourceAddress source) {
    return result(ruleId, expected, null, reason, Judgement.REVIEW, source);
  }

  private CheckResult result(String ruleId, String expected, String actual, String reason,
      Judgement judgement, SourceAddress source) {
    return new CheckResult(ruleId, Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        judgement, expected, actual, reason, source);
  }
}
