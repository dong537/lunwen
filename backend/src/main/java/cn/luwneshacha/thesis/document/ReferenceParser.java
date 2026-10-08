package cn.luwneshacha.thesis.document;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ReferenceParser {
  private static final Pattern HEADING = Pattern.compile("^(参考文献|references)\\s*$", Pattern.CASE_INSENSITIVE);
  private static final Pattern NUMBER = Pattern.compile("^\\s*\\[(\\d+)\\]");
  private static final Pattern CATEGORY = Pattern.compile("\\[(M|J|D|C|N|EB/OL)\\](?=\\s*(?:[.。]|$))", Pattern.CASE_INSENSITIVE);

  public List<ReferenceEntryModel> parse(List<ParagraphModel> paragraphs) {
    var result = new ArrayList<ReferenceEntryModel>();
    boolean inReferences = false;
    for (ParagraphModel paragraph : paragraphs) {
      String text = paragraph.runs().stream().map(RunModel::text).reduce("", String::concat).trim();
      if (HEADING.matcher(text).matches()) { inReferences = true; continue; }
      if (!inReferences || text.isBlank()) continue;
      Matcher number = NUMBER.matcher(text);
      if (!number.find()) continue;
      Matcher category = CATEGORY.matcher(text);
      boolean hasCategory = category.find();
      result.add(new ReferenceEntryModel(paragraph.source(), number.group(1), text,
          hasCategory ? category.group(1).toUpperCase(Locale.ROOT) : null, !hasCategory));
    }
    return List.copyOf(result);
  }
}
