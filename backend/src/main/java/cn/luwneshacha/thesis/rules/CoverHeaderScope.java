package cn.luwneshacha.thesis.rules;

import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.SourceAddress;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Identifies cover content before deciding which effective header variants are required. */
final class CoverHeaderScope {
  private static final Pattern TYPE_HEADING = Pattern.compile(
      "^(?:本科生?)?毕业(?:设计(?:[（(]论文[）)])?|论文)$");
  private static final Pattern FIELD_LABEL = Pattern.compile(
      "^(学生姓名|学号|院系|学院|专业年级|指导教师|指导老师)(?:[:：]|$).*");
  private static final Pattern TITLE_LABEL = Pattern.compile("^(?:论文)?题目(?:[:：]|$).*");
  private static final Pattern DATE = Pattern.compile(
      "^(?:19|20)\\d{2}(?:年\\d{1,2}月(?:\\d{1,2}日)?|[-/.]\\d{1,2}(?:[-/.]\\d{1,2})?)$");
  private static final Set<String> FRONT_MATTER = Set.of("摘要", "ABSTRACT", "目录", "CONTENTS",
      "原创性声明", "诚信声明", "学术诚信声明", "毕业论文原创性声明", "授权声明");

  private final ParsedDocument document;
  private final boolean recognized;
  private final SourceAddress firstCoverSource;
  private final SourceAddress firstRequiredSource;

  private CoverHeaderScope(ParsedDocument document, boolean recognized, SourceAddress firstCoverSource,
      SourceAddress firstRequiredSource) {
    this.document = document;
    this.recognized = recognized;
    this.firstCoverSource = firstCoverSource;
    this.firstRequiredSource = firstRequiredSource;
  }

  static CoverHeaderScope identify(ParsedDocument document) {
    DocumentRegionClassifier regions = new DocumentRegionClassifier();
    int boundary = document.paragraphs().size();
    int lastDate = -1;
    boolean typeHeading = false;
    boolean schoolName = false;
    SourceAddress firstContent = null;
    Set<String> fields = new HashSet<>();
    for (int index = 0; index < document.paragraphs().size(); index++) {
      ParagraphModel paragraph = document.paragraphs().get(index);
      String text = compact(DocumentRegionClassifier.paragraphText(paragraph));
      if (text.isEmpty()) continue;
      if (!paragraph.insideTextBox() && !regions.isInsideTable(paragraph, document.tables())
          && (FRONT_MATTER.contains(text.toUpperCase(java.util.Locale.ROOT))
              || regions.isBodyChapterStart(paragraph, document)
              || regions.isEndMatterHeading(paragraph, document))) {
        boundary = index;
        break;
      }
      if (firstContent == null) firstContent = paragraph.source();
      typeHeading |= TYPE_HEADING.matcher(text).matches();
      schoolName |= text.equals("山东石油化工学院") || text.equals("学校名称：山东石油化工学院")
          || text.equals("学校名称:山东石油化工学院");
      var label = FIELD_LABEL.matcher(text);
      if (label.matches()) fields.add(label.group(1));
      if (DATE.matcher(text).matches()) lastDate = index;
    }
    boolean recognized = typeHeading || schoolName && fields.size() >= 2;
    if (!recognized && firstContent == null) {
      SourceAddress imageCover = initialImageCoverSource(document, boundary);
      if (imageCover != null) {
        recognized = true;
        firstContent = imageCover;
      }
    }
    if (!recognized) {
      SourceAddress first = document.paragraphs().isEmpty() ? null : document.paragraphs().get(0).source();
      return new CoverHeaderScope(document, false, null, first);
    }
    // A standalone cover date also ends the cover when a following declaration or
    // malformed abstract heading is not otherwise identifiable. Blank paragraphs
    // before the next content still belong to the cover's trailing space.
    int start = lastDate < 0 ? boundary : lastDate + 1;
    if (boundary == document.paragraphs().size() && lastDate < 0) {
      // Without a structural boundary or a closing cover date, an arbitrary
      // paragraph after a cover heading is not evidence that the whole file is
      // a cover. Exempt only text that is itself identifiable as cover metadata.
      start = firstUnclassifiedCoverParagraph(document);
    }
    SourceAddress required = null;
    for (int index = start; index < document.paragraphs().size(); index++) {
      ParagraphModel paragraph = document.paragraphs().get(index);
      if (!compact(DocumentRegionClassifier.paragraphText(paragraph)).isEmpty()) {
        required = paragraph.source();
        break;
      }
    }
    return new CoverHeaderScope(document, true, firstContent, required);
  }

  /**
   * A scanned cover has no paragraph text. Recognize only an isolated initial
   * image section immediately followed by an abstract, not arbitrary image pages.
   * Shared header parts remain subject to the later non-cover section's checks.
   */
  private static SourceAddress initialImageCoverSource(ParsedDocument document, int boundary) {
    if (boundary <= 0 || boundary >= document.paragraphs().size() || document.sections().size() < 2)
      return null;
    ParagraphModel next = document.paragraphs().get(boundary);
    String title = compact(DocumentRegionClassifier.paragraphText(next));
    if (!title.equals("摘要") && !title.equalsIgnoreCase("ABSTRACT")) return null;
    SourceAddress nextSource = next.source();
    SourceAddress firstSectionEnd = document.sections().get(0).source();
    SourceAddress secondSectionEnd = document.sections().get(1).source();
    if (document.sections().get(0).terminal() || !isBodyParagraph(nextSource)
        || !isBodyParagraph(firstSectionEnd) || !isBodyParagraph(secondSectionEnd)
        || !before(firstSectionEnd, nextSource) || before(secondSectionEnd, nextSource)) return null;
    // Text in tables/text boxes is not a scanned cover; neither are a blank
    // leading table, an equation-only page, or a captioned illustration.
    if (document.tables().stream().anyMatch(table -> before(table.source(), nextSource))
        || document.equations().stream().anyMatch(equation -> before(equation.source(), nextSource)))
      return null;
    var images = document.images().stream().filter(image -> before(image.source(), nextSource)).toList();
    if (images.size() != 1) return null;
    var image = images.get(0);
    if (!isBodyParagraph(image.source()) || before(firstSectionEnd, image.source())
        || image.captionSource() != null || image.captionText() != null && !image.captionText().isBlank())
      return null;
    return image.source();
  }

  private static boolean isBodyParagraph(SourceAddress source) {
    return source != null && "word/document.xml".equals(source.partName())
        && source.elementPath().size() == 2 && source.elementPath().get(0) == 0;
  }

  private static int firstUnclassifiedCoverParagraph(ParsedDocument document) {
    for (int index = 0; index < document.paragraphs().size(); index++) {
      ParagraphModel paragraph = document.paragraphs().get(index);
      String text = compact(DocumentRegionClassifier.paragraphText(paragraph));
      if (text.isEmpty() || TYPE_HEADING.matcher(text).matches()
          || text.equals("山东石油化工学院") || text.equals("学校名称：山东石油化工学院")
          || text.equals("学校名称:山东石油化工学院") || FIELD_LABEL.matcher(text).matches()
          || TITLE_LABEL.matcher(text).matches()) continue;
      return index;
    }
    return document.paragraphs().size();
  }

  boolean entireDocumentIsCover() {
    return recognized && firstRequiredSource == null;
  }

  SourceAddress firstRequiredSource() {
    return firstRequiredSource;
  }

  boolean sectionIsCoverOnly(int sectionIndex) {
    if (!recognized) return false;
    if (firstRequiredSource == null) return true;
    return before(document.sections().get(sectionIndex).source(), firstRequiredSource);
  }

  boolean firstPageIsCover(int sectionIndex) {
    if (!recognized || firstCoverSource == null) return false;
    if (firstRequiredSource != null && !before(firstCoverSource, firstRequiredSource)) return false;
    // FIRST belongs to the first page of a section, not to the first page of the
    // document. Only the section that starts with the recognized cover is exempt.
    if (sectionIndex > 0 && !before(document.sections().get(sectionIndex - 1).source(), firstCoverSource))
      return false;
    return !before(document.sections().get(sectionIndex).source(), firstCoverSource);
  }

  private static boolean before(SourceAddress left, SourceAddress right) {
    if (left == null || right == null || !left.partName().equals(right.partName())) return false;
    int count = Math.min(left.elementPath().size(), right.elementPath().size());
    for (int index = 0; index < count; index++) {
      int comparison = Integer.compare(left.elementPath().get(index), right.elementPath().get(index));
      if (comparison != 0) return comparison < 0;
    }
    return left.elementPath().size() < right.elementPath().size();
  }

  private static String compact(String text) {
    return text.codePoints().filter(codePoint -> !Character.isWhitespace(codePoint)
        && !Character.isSpaceChar(codePoint) && codePoint != 0x200B && codePoint != 0x200C
        && codePoint != 0x200D && codePoint != 0x2060 && codePoint != 0xFEFF)
        .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
  }
}
