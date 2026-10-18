package cn.luwneshacha.thesis.rules;

import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.RunModel;

/** Interprets explicit Word line breaks as visual blank-line evidence. */
final class ManualLineBreakSupport {
  private ManualLineBreakSupport() { }

  static String text(ParagraphModel paragraph) {
    return paragraph.runs().stream().map(RunModel::text).reduce("", String::concat);
  }

  static String stripTrailingBreaks(String text) {
    int index = text.length();
    int firstTrailingBreak = -1;
    while (index > 0) {
      char value = text.charAt(index - 1);
      if (value == '\n' || value == '\r') firstTrailingBreak = index - 1;
      else if (!Character.isWhitespace(value) && !Character.isSpaceChar(value)) break;
      index--;
    }
    return firstTrailingBreak < 0 ? text : text.substring(0, firstTrailingBreak);
  }

  static String stripBoundaryBreaks(String text) {
    String value = stripTrailingBreaks(text);
    int start = 0;
    while (start < value.length() && isLineBreak(value.charAt(start))) start++;
    return value.substring(start);
  }

  static boolean isBreakOnly(String text) {
    return text != null && !text.isEmpty() && text.chars().allMatch(ManualLineBreakSupport::isLineBreak);
  }

  private static boolean isLineBreak(int value) {
    return value == '\n' || value == '\r';
  }

  static int blankLinesAfter(ParagraphModel paragraph) {
    String text = text(paragraph);
    int index = text.length();
    int breaks = 0;
    while (index > 0) {
      char value = text.charAt(index - 1);
      if (value == '\n') {
        breaks++;
        index--;
        if (index > 0 && text.charAt(index - 1) == '\r') index--;
      } else if (value == '\r') {
        breaks++;
        index--;
      } else if (Character.isWhitespace(value) || Character.isSpaceChar(value)) {
        index--;
      } else {
        break;
      }
    }
    return Math.max(0, breaks - 1);
  }

  static int visualBlankLines(ParagraphModel paragraph) {
    String text = text(paragraph);
    boolean blank = text.codePoints().allMatch(codePoint -> Character.isWhitespace(codePoint)
        || Character.isSpaceChar(codePoint) || isInvisibleWhitespace(codePoint));
    if (!blank) return 0;
    int lineBreaks = 0;
    for (int index = 0; index < text.length(); index++) {
      char value = text.charAt(index);
      if (value == '\n') {
        lineBreaks++;
        if (index > 0 && text.charAt(index - 1) == '\r') lineBreaks--;
      } else if (value == '\r') {
        lineBreaks++;
      }
    }
    return Math.max(1, lineBreaks);
  }

  private static boolean isInvisibleWhitespace(int codePoint) {
    return codePoint == 0x200B || codePoint == 0x200C || codePoint == 0x200D
        || codePoint == 0x2060 || codePoint == 0xFEFF;
  }
}
