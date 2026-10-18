package cn.luwneshacha.thesis.document;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ReferenceParser {
  private static final Pattern HEADING = Pattern.compile("^(?:参\\s*考\\s*文\\s*献|references)\\s*$", Pattern.CASE_INSENSITIVE);
  private static final Pattern END_HEADING = Pattern.compile(
      "^(?:致[\\s\\p{Zs}]*谢|Acknowledg(?:e)?ments?|附[\\s\\p{Zs}]*录(?:[\\s\\p{Zs}]*[A-Z](?:[\\s\\p{Zs}]+\\S.*|\\p{IsHan}.*)?|[\\s\\p{Zs}]+\\S.*)?|Appendix(?:[\\s\\p{Zs}]+[A-Z])?(?:[\\s\\p{Zs}]+\\S.*)?)[\\s\\p{Zs}]*$",
      Pattern.CASE_INSENSITIVE);
  private static final Pattern NUMBER = Pattern.compile("^\\s*\\[(\\d+)\\]");
  private static final Pattern BIBLIOGRAPHIC_CATEGORY = Pattern.compile(
      "\\[(?:EB|DB|CP|M|J|D|C|N|R|S|P|Z|A)(?:/(?:OL|CD|DK|MT))?\\](?=[\\s\\p{Zs}]*(?:[.。．]|//|$))",
      Pattern.CASE_INSENSITIVE);
  /** GB/T 7714 常见文献类型：单字母类型，以及电子资源、数据库和计算机程序。 */
  private static final Pattern CATEGORY = Pattern.compile(
      "\\[((?:EB|DB|CP|M|J|D|C|N|R|S|P|Z|A)(?:/(?:OL|CD|DK|MT))?)\\](?=[\\s\\p{Zs}]*(?:[.。．]|//|$))");
  private static final Pattern YEAR = Pattern.compile("(?<!\\d)((?:19|20)\\d{2})(?!\\d)");
  private static final Pattern NEWSPAPER_PAGE = Pattern.compile(
      "(?<!\\d)(?:19|20)\\d{2}[-－]\\d{1,2}[-－]\\d{1,2}\\s*[（(](\\d{1,3})[）)]");
  /**
   * GB/T 7714 entries usually introduce pages with a colon, but online-first
   * journal records also use a comma before a page range, e.g. "Journal, 1-10"
   * followed by the access date. Supplementary pages may be joined with "+",
   * e.g. "83-85+89". Only the comma form with an actual range is accepted so a
   * publication year such as ", 2024." cannot become a page.
   */
  private static final String PAGE_ATOM = "\\d+(?:[-－][\\s\\p{Zs}]*\\d+)?";
  private static final String PAGE_SEQUENCE = PAGE_ATOM + "(?:[+＋]" + PAGE_ATOM + ")*";
  private static final Pattern PAGES = Pattern.compile(
      "(?:(?:[:：])\\s*(" + PAGE_SEQUENCE + ")|(?:[,，])\\s*(\\d+[-－][\\s\\p{Zs}]*\\d+(?:[+＋]\\d+(?:[-－][\\s\\p{Zs}]*\\d+)?)*))"
          + "(?=\\s*(?:[.。．,，;；)\\]\\[]|$))");
  private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?://|www\\.)\\S+");
  private static final Pattern ACCESS_DATE = Pattern.compile("\\[(\\d{4}-\\d{1,2}-\\d{1,2})\\]");

  public List<ReferenceEntryModel> parse(List<ParagraphModel> paragraphs) {
    var result = new ArrayList<ReferenceEntryModel>();
    boolean inReferences = false;
    for (ParagraphModel paragraph : paragraphs) {
      String rawText = paragraph.runs().stream().map(RunModel::text).reduce("", String::concat);
      String text = rawText.trim();
      if (isReferenceHeading(text)) { inReferences = true; continue; }
      if (inReferences && END_HEADING.matcher(removeInvisibleFormatting(text)).matches()) {
        inReferences = false; continue;
      }
      if (!inReferences || isFormattingBlank(text)) continue;
      Matcher number = NUMBER.matcher(text);
      String numberValue = null;
      int numberEnd = 0;
      boolean numbered = number.find();
      if (numbered) {
        numberValue = number.group(1);
        numberEnd = number.end();
      } else {
        numberValue = automaticNumber(paragraph);
        numbered = numberValue != null;
      }
      // Some Word documents contain a real bibliography but omit the required
      // numeric labels. Count those entries so REF-05 reports the true count;
      // the missing label is reported separately by REF-07.
      if (!numbered && !isUnnumberedBibliographyEntry(text)) continue;
      Matcher category = uniqueCategoryAfterContent(text, numberEnd, CATEGORY);
      boolean hasCategory = category.find();
      var fields = extractFields(text, numberEnd, category, hasCategory);
      SourceAddress textSource = trimmedTextSource(paragraph.source(), rawText, text);
      result.add(new ReferenceEntryModel(textSource, numbered ? numberValue : null, text,
          hasCategory ? category.group(1) : null, !hasCategory,
          fields.author(), fields.title(), fields.source(), fields.year(), fields.pages(),
          fields.accessDate(), fields.url(), paragraph.numberingSeparatorSpaces(),
          paragraph.numberingFormatTrailingSpaces(), paragraph.automaticNumberingActive() && numbered));
    }
    return List.copyOf(result);
  }

  private static SourceAddress trimmedTextSource(SourceAddress paragraphSource, String rawText,
      String trimmedText) {
    int leadingCharacters = 0;
    while (leadingCharacters < rawText.length() && rawText.charAt(leadingCharacters) <= ' ')
      leadingCharacters++;
    int start = paragraphSource.start() + leadingCharacters;
    return new SourceAddress(paragraphSource.partName(), paragraphSource.elementPath(), start,
        start + trimmedText.length());
  }

  /**
   * Word automatic numbering is rendered outside the paragraph text. The parser
   * stores the rendered label separately, so only an unambiguous numeric label
   * is accepted as a reference number. Labels such as "1." and "1、" are
   * normalized; chapter labels or unresolved numbering are deliberately left
   * unresolved and remain subject to the missing-number rule.
   */
  private static String automaticNumber(ParagraphModel paragraph) {
    if (!paragraph.automaticNumberingActive() || paragraph.numberingLabel() == null) return null;
    Matcher label = Pattern.compile("^\\s*(?:\\[)?([1-9][0-9]*)(?:\\])?[.、．)]?\\s*$")
        .matcher(paragraph.numberingLabel());
    return label.matches() ? label.group(1) : null;
  }

  private static boolean isReferenceHeading(String text) {
    if (text == null) return false;
    String compact = compactFormattingWhitespace(text);
    return HEADING.matcher(compact).matches();
  }

  private static String compactFormattingWhitespace(String text) {
    return text.codePoints()
        .filter(codePoint -> !Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)
            && !isInvisibleFormattingWhitespace(codePoint))
        .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
        .toString();
  }

  private static String removeInvisibleFormatting(String text) {
    return text.codePoints()
        .filter(codePoint -> !isInvisibleFormattingWhitespace(codePoint))
        .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
        .toString();
  }

  private static boolean isInvisibleFormattingWhitespace(int codePoint) {
    return codePoint == 0x200B || codePoint == 0x200C || codePoint == 0x200D
        || codePoint == 0x2060 || codePoint == 0xFEFF;
  }

  private static boolean isUnnumberedBibliographyEntry(String text) {
    Matcher category = uniqueCategoryAfterContent(text, 0, BIBLIOGRAPHIC_CATEGORY);
    boolean hasCategory = category.find();
    String authorAndTitle = hasCategory ? text.substring(0, category.start()).trim() : text.trim();
    int separator = firstTitleSeparator(authorAndTitle);
    if (separator <= 0 || separator >= authorAndTitle.length() - 1) return false;
    if (hasCategory) return true;

    String titleAndPublication = text.substring(separator + 1).trim();
    int titleEnd = firstTitleSeparator(titleAndPublication);
    if (titleEnd <= 0 || titleEnd >= titleAndPublication.length() - 1) return false;
    String publicationDetails = trimPunctuation(titleAndPublication.substring(titleEnd + 1));
    Matcher year = YEAR.matcher(publicationDetails);
    return URL.matcher(publicationDetails).find()
        || year.find() && !isFormattingBlank(publicationDetails.substring(0, year.start()));
  }

  private static Matcher uniqueCategoryAfterContent(String text, int bodyStart, Pattern pattern) {
    Matcher candidates = pattern.matcher(text);
    candidates.region(bodyStart, text.length());
    if (!candidates.find()) return pattern.matcher("");
    int categoryStart = candidates.start();
    if (candidates.find()) return pattern.matcher("");
    if (isFormattingBlank(text.substring(bodyStart, categoryStart))) return pattern.matcher("");

    Matcher result = pattern.matcher(text);
    result.region(categoryStart, text.length());
    return result;
  }

  private static Fields extractFields(String text, int numberEnd, Matcher category, boolean hasCategory) {
    if (!hasCategory) return new Fields(null, null, null, null, null, null, null);
    String body = text.substring(Math.min(numberEnd, text.length())).trim();
    int categoryStart = category.start();
    int bodyOffset = text.length() - body.length();
    int relativeCategoryStart = Math.max(0, categoryStart - bodyOffset);
    if (relativeCategoryStart > body.length()) return new Fields(null, null, null, null, null, null, null);
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
    Matcher accessDateMatcher = ACCESS_DATE.matcher(publication);
    String accessDate = accessDateMatcher.find() ? accessDateMatcher.group(1) : null;
    String bibliographicDetails = ACCESS_DATE.matcher(publication).replaceAll(" ");
    Matcher urlMatcher = URL.matcher(bibliographicDetails);
    String url = null;
    if (urlMatcher.find()) url = trimUrl(urlMatcher.group());
    bibliographicDetails = URL.matcher(bibliographicDetails).replaceAll(" ");
    bibliographicDetails = trimPunctuation(bibliographicDetails);

    Matcher yearMatcher = YEAR.matcher(bibliographicDetails);
    String year = yearMatcher.find() ? yearMatcher.group(1) : null;
    String source = year == null ? trimPunctuation(bibliographicDetails)
        : trimPunctuation(bibliographicDetails.substring(0, yearMatcher.start()));
    String pages = null;
    Matcher pagesMatcher = PAGES.matcher(bibliographicDetails);
    while (pagesMatcher.find()) {
      String pageValue = pagesMatcher.group(1) != null ? pagesMatcher.group(1) : pagesMatcher.group(2);
      pages = pageValue.replace('－', '-').replace('＋', '+');
    }
    String categoryValue = category.group(1);
    if (pages == null && categoryValue.equalsIgnoreCase("N")) {
      Matcher newspaperPageMatcher = NEWSPAPER_PAGE.matcher(bibliographicDetails);
      if (newspaperPageMatcher.find()) pages = newspaperPageMatcher.group(1);
    }
    if (categoryValue.equalsIgnoreCase("J/OL")) {
      source = emptyToNull(source);
    } else if (isElectronic(categoryValue)) {
      source = url == null ? source : url;
    }
    return new Fields(emptyToNull(author), emptyToNull(title), emptyToNull(source), year, pages,
        accessDate, url);
  }

  private static int firstTitleSeparator(String value) {
    int ascii = value.indexOf('.');
    int full = value.indexOf('。');
    int fullWidth = value.indexOf('．');
    if (ascii < 0) return full < 0 ? fullWidth : fullWidth < 0 ? full : Math.min(full, fullWidth);
    if (full < 0) return fullWidth < 0 ? ascii : Math.min(ascii, fullWidth);
    return fullWidth < 0 ? Math.min(ascii, full) : Math.min(Math.min(ascii, full), fullWidth);
  }

  private static String trimPunctuation(String value) {
    return value.replaceFirst("^[\\s\\p{Zs}.。．；;:：]+", "")
        .replaceFirst("[\\s\\p{Zs}.。．；;:：,，]+$", "").trim();
  }

  private static String emptyToNull(String value) { return value == null || isFormattingBlank(value) ? null : value; }

  private static boolean isFormattingBlank(String value) {
    return value != null && value.codePoints().allMatch(codePoint ->
        Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
            || isInvisibleFormattingWhitespace(codePoint));
  }

  private static boolean isElectronic(String category) {
    String normalized = category.toUpperCase(Locale.ROOT);
    return normalized.contains("/OL") || normalized.equals("EB") || normalized.equals("DB")
        || normalized.equals("CP");
  }

  private static String trimUrl(String value) {
    String result = value.trim();
    while (!result.isEmpty() && ".。；;:：,，".indexOf(result.charAt(result.length() - 1)) >= 0)
      result = result.substring(0, result.length() - 1);
    if (result.endsWith(")") && count(result, '(') < count(result, ')'))
      result = result.substring(0, result.length() - 1);
    if (result.endsWith("）") && count(result, '（') < count(result, '）'))
      result = result.substring(0, result.length() - 1);
    return result;
  }

  private static int count(String value, char target) {
    int count = 0;
    for (int i = 0; i < value.length(); i++) if (value.charAt(i) == target) count++;
    return count;
  }

  private record Fields(String author, String title, String source, String year, String pages,
      String accessDate, String url) {}
}
