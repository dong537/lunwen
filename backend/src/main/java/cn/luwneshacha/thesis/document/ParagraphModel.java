package cn.luwneshacha.thesis.document;

import java.util.List;

public record ParagraphModel(
    List<RunModel> runs,
    Integer firstLineIndentTwips,
    Integer leftIndentTwips,
    Integer leftIndentChars,
    Integer rightIndentTwips,
    Integer lineTwips,
    String lineRule,
    SourceAddress source,
    Integer firstLineChars,
    Integer outlineLevel,
    String alignment,
    String numberingLabel,
    Integer numberingSeparatorSpaces,
    Integer numberingFormatTrailingSpaces,
    boolean generatedTocField,
    Integer hangingIndentTwips,
    Integer hangingIndentChars,
    boolean automaticNumberingActive,
    boolean firstLineEvidenceInvalid,
    boolean hangingEvidenceInvalid,
    boolean leftEvidenceInvalid,
    Integer beforeSpacingTwips,
    Integer afterSpacingTwips,
    String paragraphStyleId,
    boolean lineSpacingEvidenceInvalid,
    boolean insideTextBox,
    String paragraphStyleName) {
  public ParagraphModel(List<RunModel> runs, Integer firstLineIndentTwips, Integer leftIndentTwips,
      Integer leftIndentChars, Integer rightIndentTwips, Integer lineTwips, String lineRule,
      SourceAddress source, Integer firstLineChars, Integer outlineLevel, String alignment,
      String numberingLabel, Integer numberingSeparatorSpaces, Integer numberingFormatTrailingSpaces,
      boolean generatedTocField, Integer hangingIndentTwips, Integer hangingIndentChars,
      boolean automaticNumberingActive, boolean firstLineEvidenceInvalid,
      boolean hangingEvidenceInvalid, boolean leftEvidenceInvalid, Integer beforeSpacingTwips,
      Integer afterSpacingTwips, String paragraphStyleId, boolean lineSpacingEvidenceInvalid,
      boolean insideTextBox) {
    this(runs, firstLineIndentTwips, leftIndentTwips, leftIndentChars, rightIndentTwips,
        lineTwips, lineRule, source, firstLineChars, outlineLevel, alignment, numberingLabel,
        numberingSeparatorSpaces, numberingFormatTrailingSpaces, generatedTocField,
        hangingIndentTwips, hangingIndentChars, automaticNumberingActive, firstLineEvidenceInvalid,
        hangingEvidenceInvalid, leftEvidenceInvalid, beforeSpacingTwips, afterSpacingTwips,
        paragraphStyleId, lineSpacingEvidenceInvalid, insideTextBox, null);
  }
  public ParagraphModel(List<RunModel> runs, Integer firstLineIndentTwips, Integer leftIndentTwips,
      Integer leftIndentChars, Integer rightIndentTwips, Integer lineTwips, String lineRule,
      SourceAddress source, Integer firstLineChars, Integer outlineLevel, String alignment,
      String numberingLabel, Integer numberingSeparatorSpaces, Integer numberingFormatTrailingSpaces,
      boolean generatedTocField, Integer hangingIndentTwips, Integer hangingIndentChars,
      boolean automaticNumberingActive, boolean firstLineEvidenceInvalid,
      boolean hangingEvidenceInvalid, boolean leftEvidenceInvalid, Integer beforeSpacingTwips,
      Integer afterSpacingTwips, String paragraphStyleId, boolean lineSpacingEvidenceInvalid) {
    this(runs, firstLineIndentTwips, leftIndentTwips, leftIndentChars, rightIndentTwips,
        lineTwips, lineRule, source, firstLineChars, outlineLevel, alignment, numberingLabel,
        numberingSeparatorSpaces, numberingFormatTrailingSpaces, generatedTocField,
        hangingIndentTwips, hangingIndentChars, automaticNumberingActive, firstLineEvidenceInvalid,
        hangingEvidenceInvalid, leftEvidenceInvalid, beforeSpacingTwips, afterSpacingTwips,
        paragraphStyleId, lineSpacingEvidenceInvalid, false);
  }
  /** Keeps the prior full-evidence constructor available to existing callers. */
  public ParagraphModel(List<RunModel> runs, Integer firstLineIndentTwips, Integer leftIndentTwips,
      Integer leftIndentChars, Integer rightIndentTwips, Integer lineTwips, String lineRule,
      SourceAddress source, Integer firstLineChars, Integer outlineLevel, String alignment,
      String numberingLabel, Integer numberingSeparatorSpaces, Integer numberingFormatTrailingSpaces,
      boolean generatedTocField, Integer hangingIndentTwips, Integer hangingIndentChars,
      boolean automaticNumberingActive, boolean firstLineEvidenceInvalid,
      boolean hangingEvidenceInvalid, boolean leftEvidenceInvalid, Integer beforeSpacingTwips,
      Integer afterSpacingTwips, String paragraphStyleId) {
    this(runs, firstLineIndentTwips, leftIndentTwips, leftIndentChars, rightIndentTwips,
        lineTwips, lineRule, source, firstLineChars, outlineLevel, alignment, numberingLabel,
        numberingSeparatorSpaces, numberingFormatTrailingSpaces, generatedTocField,
        hangingIndentTwips, hangingIndentChars, automaticNumberingActive, firstLineEvidenceInvalid,
        hangingEvidenceInvalid, leftEvidenceInvalid, beforeSpacingTwips, afterSpacingTwips,
        paragraphStyleId, false);
  }

  /** Keeps the prior full-evidence constructor available to existing callers. */
  public ParagraphModel(List<RunModel> runs, Integer firstLineIndentTwips, Integer leftIndentTwips,
      Integer leftIndentChars, Integer rightIndentTwips, Integer lineTwips, String lineRule,
      SourceAddress source, Integer firstLineChars, Integer outlineLevel, String alignment,
      String numberingLabel, Integer numberingSeparatorSpaces, Integer numberingFormatTrailingSpaces,
      boolean generatedTocField, Integer hangingIndentTwips, Integer hangingIndentChars,
      boolean automaticNumberingActive, boolean firstLineEvidenceInvalid,
      boolean hangingEvidenceInvalid, boolean leftEvidenceInvalid, Integer beforeSpacingTwips,
      Integer afterSpacingTwips) {
    this(runs, firstLineIndentTwips, leftIndentTwips, leftIndentChars, rightIndentTwips,
        lineTwips, lineRule, source, firstLineChars, outlineLevel, alignment, numberingLabel,
        numberingSeparatorSpaces, numberingFormatTrailingSpaces, generatedTocField,
        hangingIndentTwips, hangingIndentChars, automaticNumberingActive, firstLineEvidenceInvalid,
        hangingEvidenceInvalid, leftEvidenceInvalid, beforeSpacingTwips, afterSpacingTwips, null, false);
  }

  public ParagraphModel(List<RunModel> runs, Integer firstLineIndentTwips, Integer leftIndentTwips,
      Integer leftIndentChars, Integer rightIndentTwips, Integer lineTwips, String lineRule,
      SourceAddress source, Integer firstLineChars, Integer outlineLevel, String alignment,
      String numberingLabel, Integer numberingSeparatorSpaces, Integer numberingFormatTrailingSpaces,
      boolean generatedTocField, Integer hangingIndentTwips, Integer hangingIndentChars,
      boolean automaticNumberingActive) {
    this(runs, firstLineIndentTwips, leftIndentTwips, leftIndentChars, rightIndentTwips, lineTwips,
        lineRule, source, firstLineChars, outlineLevel, alignment, numberingLabel,
        numberingSeparatorSpaces, numberingFormatTrailingSpaces, generatedTocField,
        hangingIndentTwips, hangingIndentChars, automaticNumberingActive, false, false, false, null, null);
  }

  /** Compatibility constructor for callers that provide all legacy evidence flags. */
  public ParagraphModel(List<RunModel> runs, Integer firstLineIndentTwips, Integer leftIndentTwips,
      Integer leftIndentChars, Integer rightIndentTwips, Integer lineTwips, String lineRule,
      SourceAddress source, Integer firstLineChars, Integer outlineLevel, String alignment,
      String numberingLabel, Integer numberingSeparatorSpaces, Integer numberingFormatTrailingSpaces,
      boolean generatedTocField, Integer hangingIndentTwips, Integer hangingIndentChars,
      boolean automaticNumberingActive, boolean firstLineEvidenceInvalid,
      boolean hangingEvidenceInvalid, boolean leftEvidenceInvalid) {
    this(runs, firstLineIndentTwips, leftIndentTwips, leftIndentChars, rightIndentTwips, lineTwips,
        lineRule, source, firstLineChars, outlineLevel, alignment, numberingLabel,
        numberingSeparatorSpaces, numberingFormatTrailingSpaces, generatedTocField,
        hangingIndentTwips, hangingIndentChars, automaticNumberingActive,
        firstLineEvidenceInvalid, hangingEvidenceInvalid, leftEvidenceInvalid, null, null);
  }

  /** Compatibility overload for callers that only have one aggregate invalid flag. */
  public ParagraphModel(List<RunModel> runs, Integer firstLineIndentTwips, Integer leftIndentTwips,
      Integer leftIndentChars, Integer rightIndentTwips, Integer lineTwips, String lineRule,
      SourceAddress source, Integer firstLineChars, Integer outlineLevel, String alignment,
      String numberingLabel, Integer numberingSeparatorSpaces, Integer numberingFormatTrailingSpaces,
      boolean generatedTocField, Integer hangingIndentTwips, Integer hangingIndentChars,
      boolean automaticNumberingActive, boolean indentEvidenceInvalid) {
    this(runs, firstLineIndentTwips, leftIndentTwips, leftIndentChars, rightIndentTwips, lineTwips,
        lineRule, source, firstLineChars, outlineLevel, alignment, numberingLabel,
        numberingSeparatorSpaces, numberingFormatTrailingSpaces, generatedTocField,
        hangingIndentTwips, hangingIndentChars, automaticNumberingActive,
        indentEvidenceInvalid, indentEvidenceInvalid, indentEvidenceInvalid, null, null);
  }

  public ParagraphModel(List<RunModel> runs, Integer firstLineIndentTwips, Integer leftIndentTwips,
      Integer leftIndentChars, Integer rightIndentTwips, Integer lineTwips, String lineRule,
      SourceAddress source, Integer firstLineChars, Integer outlineLevel, String alignment,
      String numberingLabel, Integer numberingSeparatorSpaces, Integer numberingFormatTrailingSpaces,
      boolean generatedTocField) {
    this(runs, firstLineIndentTwips, leftIndentTwips, leftIndentChars, rightIndentTwips, lineTwips,
        lineRule, source, firstLineChars, outlineLevel, alignment, numberingLabel,
        numberingSeparatorSpaces, numberingFormatTrailingSpaces, generatedTocField, null, null,
        numberingLabel != null, false, false, false, null, null);
  }

  public ParagraphModel(List<RunModel> runs, Integer firstLineIndentTwips, Integer leftIndentTwips,
      Integer leftIndentChars, Integer rightIndentTwips, Integer lineTwips, String lineRule,
      SourceAddress source, Integer firstLineChars, Integer outlineLevel, String alignment,
      String numberingLabel, Integer numberingSeparatorSpaces, Integer numberingFormatTrailingSpaces) {
    this(runs, firstLineIndentTwips, leftIndentTwips, leftIndentChars, rightIndentTwips, lineTwips,
        lineRule, source, firstLineChars, outlineLevel, alignment, numberingLabel,
        numberingSeparatorSpaces, numberingFormatTrailingSpaces, false, null, null,
        numberingLabel != null, false, false, false, null, null);
  }

  public ParagraphModel(List<RunModel> runs, Integer firstLineIndentTwips, Integer leftIndentTwips,
      Integer leftIndentChars, Integer rightIndentTwips, Integer lineTwips, String lineRule,
      SourceAddress source, Integer firstLineChars, Integer outlineLevel, String alignment) {
    this(runs, firstLineIndentTwips, leftIndentTwips, leftIndentChars, rightIndentTwips, lineTwips,
        lineRule, source, firstLineChars, outlineLevel, alignment, null, null, null, false, null, null, false, false, false, false, null, null);
  }

  public ParagraphModel(List<RunModel> runs, Integer firstLineIndentTwips, Integer leftIndentTwips,
      Integer leftIndentChars, Integer rightIndentTwips, Integer lineTwips, String lineRule,
      SourceAddress source, Integer firstLineChars, Integer outlineLevel, String alignment,
      String numberingLabel) {
    this(runs, firstLineIndentTwips, leftIndentTwips, leftIndentChars, rightIndentTwips, lineTwips,
        lineRule, source, firstLineChars, outlineLevel, alignment, numberingLabel, null, null, false, null, null,
        numberingLabel != null, false, false, false, null, null);
  }

  public ParagraphModel(List<RunModel> runs, Integer firstLineIndentTwips, Integer leftIndentTwips,
      Integer rightIndentTwips, Integer lineTwips, String lineRule, SourceAddress source,
      Integer firstLineChars, Integer outlineLevel, String alignment) {
    this(runs, firstLineIndentTwips, leftIndentTwips, null, rightIndentTwips, lineTwips, lineRule,
        source, firstLineChars, outlineLevel, alignment, null, null, null, false, null, null, false, false, false, false, null, null);
  }

  public ParagraphModel(List<RunModel> runs, Integer firstLineIndentTwips, Integer leftIndentTwips,
      Integer rightIndentTwips, Integer lineTwips, String lineRule, SourceAddress source,
      Integer firstLineChars, Integer outlineLevel) {
    this(runs, firstLineIndentTwips, leftIndentTwips, null, rightIndentTwips, lineTwips, lineRule,
        source, firstLineChars, outlineLevel, null, null, null, null, false, null, null, false, false, false, false, null, null);
  }

  public ParagraphModel(List<RunModel> runs, Integer firstLineIndentTwips, Integer leftIndentTwips,
      Integer rightIndentTwips, Integer lineTwips, String lineRule, SourceAddress source,
      Integer firstLineChars) {
    this(runs, firstLineIndentTwips, leftIndentTwips, null, rightIndentTwips, lineTwips, lineRule,
        source, firstLineChars, null, null, null, null, null, false, null, null, false, false, false, false, null, null);
  }

  public ParagraphModel {
    runs = List.copyOf(runs);
  }
}
