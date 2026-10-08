package cn.luwneshacha.thesis.document;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ReferenceParser {
  private static final Pattern HEADING = Pattern.compile("^(参考文献|references)\\s*$", Pattern.CASE_INSENSITIVE);
  private static final Pattern END_HEADING = Pattern.compile(
      "^(?:致\\s*谢|Acknowledg(?:e)?ments?|附\\s*录(?:\\s*[A-Z])?|Appendix(?:\\s+[A-Z])?)\\s*$",
      Pattern.CASE_INSENSITIVE);
  private static final Pattern NUMBER = Pattern.compile("^\\s*\\[(\\d+)\\]");
  /** GB/T 7714 常见文献类型：单字母类型，以及电子资源、数据库和计算机程序。 */
  private static final Pattern CATEGORY = Pattern.compile(
      "\\[((?:EB|DB|CP|OL|DL|M|J|D|C|N|R|S|P|Z|A)(?:/(?:OL|DL))?)\\](?=\\s*(?:[.。]|$))",
      Pattern.CASE_INSENSITIVE);
  private static final Pattern YEAR = Pattern.compile("(?<!\\d)((?:19|20)\\d{2})(?!\\d)");
  private static final Pattern PAGES = Pattern.compile("[:：]\\s*(\\d+(?:[-－]\\d+)?)");

  public List<ReferenceEntryModel> parse(List<ParagraphModel> paragraphs) {
    var result = new ArrayList<ReferenceEntryModel>();
    boolean inReferences = false;
    for (ParagraphModel paragraph : paragraphs) {
      String text = paragraph.runs().stream().map(RunModel::text).reduce("", String::concat).trim();
      if (HEADING.matcher(text).matches()) { inReferences = true; continue; }
      if (inReferences && END_HEADING.matcher(text).matches()) { inReferences = false; continue; }
      if (!inReferences || text.isBlank()) continue;
      Matcher number = NUMBER.matcher(text);
      if (!number.find()) continue;
      Matcher category = CATEGORY.matcher(text);
      boolean hasCategory = category.find();
      var fields = extractFields(text, number, category, hasCategory);
      result.add(new ReferenceEntryModel(paragraph.source(), number.group(1), text,
          hasCategory ? category.group(1).toUpperCase(Locale.ROOT) : null, !hasCategory,
          fields.author(), fields.title(), fields.source(), fields.year(), fields.pages()));
    }
    return List.copyOf(result);
  }

  private static Fields extractFields(String text, Matcher number, Matcher category, boolean hasCategory) {
    if (!hasCategory) return new Fields(null, null, null, null, null);
    String body = text.substring(number.end()).trim();
    int categoryStart = category.start();
    int bodyOffset = text.length() - body.length();
    int relativeCategoryStart = Math.max(0, categoryStart - bodyOffset);
    if (relativeCategoryStart > body.length()) return new Fields(null, null, null, null, null);
    String authorAndTitle = trimPunctuation(body.substring(0, relativeCategoryStart));
    String author = null;
    String title = null;
    int separator = firstTitleSeparator(authorAndTitle);
    if (separator >= 0) {
      author = trimPunctuation(authorAndTitle.substring(0, separator));
      title = trimPunctuation(authorAndTitle.substring(separator + 1));
    }
    String publication = trimPunctuation(body.substring(Math.min(body.length(), relativeCategoryStart +
        (category.end() - category.start()))));
    Matcher yearMatcher = YEAR.matcher(publication);
    String year = yearMatcher.find() ? yearMatcher.group(1) : null;
    String source = year == null ? trimPunctuation(publication) : trimPunctuation(publication.substring(0, yearMatcher.start()));
    String pages = null;
    Matcher pagesMatcher = PAGES.matcher(publication);
    while (pagesMatcher.find()) pages = pagesMatcher.group(1).replace('－', '-');
    return new Fields(emptyToNull(author), emptyToNull(title), emptyToNull(source), year, pages);
  }

  private static int firstTitleSeparator(String value) {
    int ascii = value.indexOf('.');
    int full = value.indexOf('。');
    if (ascii < 0) return full;
    if (full < 0) return ascii;
    return Math.min(ascii, full);
  }

  private static String trimPunctuation(String value) {
    return value.replaceFirst("^[\\s.。；;:：]+", "")
        .replaceFirst("[\\s.。；;:：,，]+$", "").trim();
  }

  private static String emptyToNull(String value) { return value == null || value.isBlank() ? null : value; }

  private record Fields(String author, String title, String source, String year, String pages) {}
}
