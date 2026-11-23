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
  private static final Pattern FIGURE_REFERENCE = Pattern.compile(
      "图[\\s\\p{Zs}]*((?:\\d+[\\t\\p{Zs}]*[-－][\\t\\p{Zs}]*\\d+)|(?:[A-Z]\\d+))(?![A-Za-z\\d-])");
  private static final Pattern UNNUMBERED_FIGURE_REFERENCE = Pattern.compile("(?:如|见|参见)图(?!\\s*\\d)(?:所示|如下)?|下图所示");
  private static final Pattern FIGURE_CAPTION_FORMAT = Pattern.compile("^\\s*图(?:\\d+-\\d+|[A-Z]\\d+) \\S.*");
  private static final Pattern FIGURE_SEQUENCE = Pattern.compile("^\\s*图[\\t\\p{Zs}]*(\\d+)-(\\d+)(?![\\d-])");
  private static final Pattern TABLE_OR_FIGURE_CAPTION = Pattern.compile("^(续表|表|图)\\s*[0-9一二三四五六七八九十]+", Pattern.CASE_INSENSITIVE);

  private final ParagraphFormatChecker paragraphFormatChecker = new ParagraphFormatChecker();
  private final DocumentRegionClassifier regions = new DocumentRegionClassifier();

  @Override
  public List<CheckResult> check(ParsedDocument document, RuleSet rules) {
    return evaluate(document, rules).results();
  }

  @Override
  public Evaluation evaluate(ParsedDocument document, RuleSet rules) {
    // School rules cover figure captions and references, but do not set an
    // image-size requirement. Use only relationships the parser can prove.
    var results = new ArrayList<CheckResult>();
    var referenceRun = new FigureReferenceRun();
    var layoutRun = new EvidenceCounter("FIG-02");
    var captionRun = new EvidenceCounter("FIG-03");
    var sequenceRun = new EvidenceCounter("FIG-05");
    var captionFontRun = new EvidenceCounter("CAPTION-FONT");
    var captionSizeRun = new EvidenceCounter("CAPTION-SIZE");
    boolean bodyBoundaryKnown = document.paragraphs().stream()
        .anyMatch(paragraph -> !paragraph.insideTextBox()
            && regions.isBodyChapterStart(paragraph, document));
    for (ImageModel image : document.images()) {
      if (regions.isBeforeMainBody(document, image.source())) continue;
      boolean referenceRequired = regions.appendixLetterAt(document, image.source()) == null;
      if (image.captionText() == null) {
        if (referenceRequired) referenceRun.unknown();
        if (!bodyBoundaryKnown && referenceRequired)
          results.add(review("FIG-03", "正文插图下方有图号和图题",
              "FIGURE_SCOPE_UNRESOLVED", image.source()));
        else
          results.add(result("FIG-03", "图下方有图号和图题", "未找到图题", "FIGURE_CAPTION_MISSING",
              Judgement.FAIL, image.source()));
        captionRun.record(results.getLast(), bodyBoundaryKnown);
      } else {
        if (image.captionFragment())
          results.add(review("FIG-03", "图号与完整图题位于同一可辨认题注", "FIGURE_CAPTION_FRAGMENTED",
              image.captionSource()));
        else if (!FIGURE_CAPTION_FORMAT.matcher(image.captionText()).matches())
          results.add(result("FIG-03", "图章号-序号 + 1 空格 + 图题", image.captionText(),
              "FIGURE_CAPTION_NUMBER_FORMAT", Judgement.FAIL, image.captionSource()));
        else
          results.add(result("FIG-03", "图号-序号 + 1 空格 + 图题", image.captionText(), "",
              Judgement.PASS, image.captionSource()));
        captionRun.record(results.getLast(), bodyBoundaryKnown);
        Matcher figureNumber = FIGURE_NUMBER.matcher(image.captionText());
        if (referenceRequired) {
          if (image.captionNumberAmbiguous())
            results.add(review("FIG-01", "正文引用可确认的图号", "FIGURE_NUMBER_AMBIGUOUS",
                image.captionSource()));
          else if (!figureNumber.find())
            results.add(review("FIG-01", "body reference to figure", "FIGURE_NUMBER_UNKNOWN", image.captionSource()));
          else if (regions.isFigureReferencedInBody(document, figureNumber.group(1), image.captionSource(), FIGURE_REFERENCE))
            results.add(result("FIG-01", "body reference to figure " + figureNumber.group(1), figureNumber.group(1), "",
                Judgement.PASS, image.captionSource()));
          else if (regions.hasUnnumberedFigureReference(document, image.captionSource(), FIGURE_REFERENCE,
              UNNUMBERED_FIGURE_REFERENCE))
            results.add(result("FIG-01", "正文引用图号", "正文只有未指明图号的引用", "FIGURE_REFERENCE_NEEDS_REVIEW",
                Judgement.FAIL, image.captionSource()));
          else
            results.add(result("FIG-01", "body reference to figure " + figureNumber.group(1), figureNumber.group(1),
                "FIGURE_NOT_CITED_IN_BODY", Judgement.FAIL, image.captionSource()));
          // Exactly one reference finding is produced for each numbered image.
          referenceRun.record(bodyBoundaryKnown ? results.getLast().judgement() : Judgement.REVIEW);
        }
      }
      if (image.captionNumberAmbiguous()) {
        results.add(review("CAPTION-FONT", "宋体/Times New Roman",
            "FIGURE_CAPTION_TYPOGRAPHY_UNRESOLVED", image.captionSource()));
        results.add(review("CAPTION-SIZE", "21",
            "FIGURE_CAPTION_TYPOGRAPHY_UNRESOLVED", image.captionSource()));
        captionFontRun.unknown();
        captionSizeRun.unknown();
      } else {
        var paragraph = captionParagraph(document, image.captionSource());
        if (paragraph.isPresent()) {
          int candidatesBefore = captionFontRun.candidates;
          addCaptionResults(results, paragraph.orElseThrow(), rules, bodyBoundaryKnown,
              captionFontRun, captionSizeRun);
          if (captionFontRun.candidates == candidatesBefore) {
            captionFontRun.unknown();
            captionSizeRun.unknown();
          }
        } else {
          captionFontRun.unknown();
          captionSizeRun.unknown();
        }
      }
      if (image.captionText() != null) {
        if (image.captionFragment())
          results.add(review("FIG-02", "图题位于图下方并居中", "FIGURE_CAPTION_LAYOUT_UNRESOLVED",
              image.captionSource()));
        else if (Boolean.FALSE.equals(image.captionCentered()))
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
        layoutRun.record(results.getLast(), bodyBoundaryKnown);
      } else {
        if (image.placementNeedsReview())
          results.add(review("FIG-02", "图题位于图下方并居中",
              "FIGURE_LAYOUT_REVIEW", image.source()));
        layoutRun.unknown();
      }
    }
    checkFigureNumberSequences(results, document, sequenceRun, bodyBoundaryKnown);
    var runs = new ArrayList<RuleRun>(referenceRun.runs());
    for (var evidence : List.of(layoutRun, captionRun, sequenceRun, captionFontRun, captionSizeRun)) {
      if (evidence.candidates > 0) runs.add(evidence.toRun());
    }
    return new Evaluation(List.copyOf(results), List.copyOf(runs));
  }

  private static final class EvidenceCounter {
    private final String ruleId;
    private int candidates;
    private int unknown;

    private EvidenceCounter(String ruleId) { this.ruleId = ruleId; }

    private void record(CheckResult result, boolean scopeKnown) {
      candidates++;
      if (!scopeKnown || result.judgement() == Judgement.REVIEW
          || result.applicability() == Applicability.UNKNOWN
          || result.executionStatus() != ExecutionStatus.EXECUTED) unknown++;
    }

    private void unknown() { candidates++; unknown++; }

    private RuleRun toRun() {
      return new RuleRun(ruleId, "ImageRuleChecker", candidates, candidates - unknown, unknown, null);
    }
  }

  private static final class FigureReferenceRun {
    private int checked;
    private int unknown;

    private void record(Judgement judgement) {
      if (judgement == Judgement.REVIEW) unknown();
      else checked++;
    }

    private void unknown() { unknown++; }

    private List<RuleRun> runs() {
      int candidates = checked + unknown;
      if (candidates == 0) return List.of();
      return List.of(new RuleRun("FIG-01", "ImageRuleChecker", candidates, checked, unknown, null));
    }
  }

  private void checkFigureNumberSequences(List<CheckResult> results, ParsedDocument document,
      EvidenceCounter sequenceRun, boolean bodyBoundaryKnown) {
    Map<String, BigInteger> next = new java.util.HashMap<>();
    Set<SourceAddress> seenCaptions = new LinkedHashSet<>();
    Set<String> uncertainChapters = new HashSet<>();
    boolean unknownChapterAmbiguity = false;
    for (ImageModel image : document.images()) {
      if (regions.isBeforeMainBody(document, image.source())
          || regions.appendixLetterAt(document, image.source()) != null) continue;
      SourceAddress captionSource = image.captionSource() == null ? image.source() : image.captionSource();
      if (!seenCaptions.add(captionSource)) continue;
      Matcher matcher = FIGURE_SEQUENCE.matcher(image.captionText() == null ? "" : image.captionText());
      if (image.captionNumberAmbiguous()) {
        if (!image.ambiguousCaptionChapters().isEmpty())
          uncertainChapters.addAll(image.ambiguousCaptionChapters());
        else if (matcher.find()) uncertainChapters.add(matcher.group(1));
        else unknownChapterAmbiguity = true;
        sequenceRun.unknown();
        continue;
      }
      if (!matcher.find()) {
        sequenceRun.unknown();
        continue;
      }
      if (unknownChapterAmbiguity || uncertainChapters.contains(matcher.group(1))) {
        results.add(review("FIG-05", "同章图号连续", "FIGURE_SEQUENCE_UNRESOLVED_AFTER_AMBIGUOUS_CAPTION",
            captionSource));
        sequenceRun.record(results.getLast(), bodyBoundaryKnown);
        continue;
      }
      checkNumberSequence(results, next, matcher.group(1), matcher.group(2), image.captionText(), captionSource);
      sequenceRun.record(results.getLast(), bodyBoundaryKnown);
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

  private void addCaptionResults(List<CheckResult> results, ParagraphModel paragraph, RuleSet rules,
      boolean bodyBoundaryKnown, EvidenceCounter fontRun, EvidenceCounter sizeRun) {
    for (RunModel run : paragraph.runs()) {
      if (ParagraphFormatChecker.isLayoutControlOnly(run)) continue;
      results.add(paragraphFormatChecker.checkFont(run, rules, "CAPTION-FONT"));
      fontRun.record(results.getLast(), bodyBoundaryKnown);
      if (run.fontSizeHalfPoints() == null)
        results.add(result("CAPTION-SIZE", "21", "unknown", "SIZE_UNKNOWN", Judgement.REVIEW, run.source()));
      else
        results.add(result("CAPTION-SIZE", "21", String.valueOf(run.fontSizeHalfPoints()), "",
            run.fontSizeHalfPoints() == 21 ? Judgement.PASS : Judgement.FAIL, run.source()));
      sizeRun.record(results.getLast(), bodyBoundaryKnown);
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
