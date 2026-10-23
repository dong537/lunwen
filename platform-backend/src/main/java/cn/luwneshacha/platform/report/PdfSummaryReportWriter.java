package cn.luwneshacha.platform.report;

import java.awt.Color;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Writes the short, one-page PDF counterpart of the complete Word report. */
@Component
public final class PdfSummaryReportWriter implements PdfSummaryReportGenerator {
  private static final float PAGE_WIDTH = PDRectangle.A4.getWidth();
  private static final float PAGE_HEIGHT = PDRectangle.A4.getHeight();
  private static final float LEFT = 48;
  private static final float RIGHT = 48;
  private static final float TOP = 48;
  private static final float BOTTOM = 44;
  private static final float LINE_HEIGHT = 14;
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
      .withLocale(Locale.ROOT).withZone(ZoneId.of("Asia/Shanghai"));

  private final ObjectMapper mapper;
  private final String configuredFontPath;

  public PdfSummaryReportWriter(ObjectMapper mapper,
      @Value("${platform.report.pdf-font-path:}") String configuredFontPath) {
    this.mapper = Objects.requireNonNull(mapper);
    this.configuredFontPath = configuredFontPath == null ? "" : configuredFontPath.trim();
  }

  public PdfSummaryReportWriter(ObjectMapper mapper) {
    this(mapper, "");
  }

  public record StudentInfo(String name, String username, String major, String teacherName) {
    public StudentInfo {
      name = blankAsDash(name);
      username = blankAsDash(username);
      major = blankAsDash(major);
      teacherName = blankAsDash(teacherName);
    }
  }

  public record Metadata(String originalFilename, long submissionNumber, Instant finishedAt,
      StudentInfo student) {
    public Metadata {
      originalFilename = blankAsDash(originalFilename);
      if (submissionNumber < 1) throw new IllegalArgumentException("submission number must be positive");
      finishedAt = Objects.requireNonNull(finishedAt, "finishedAt");
      student = Objects.requireNonNull(student, "student");
    }
  }

  @Override public void write(byte[] auditJson, Metadata metadata, Path target) throws IOException {
    Objects.requireNonNull(auditJson, "auditJson");
    Objects.requireNonNull(metadata, "metadata");
    Objects.requireNonNull(target, "target");
    JsonNode envelope = mapper.readTree(auditJson);
    JsonNode report = envelope.path("report").isObject() ? envelope.path("report") : envelope;
    Summary summary = summary(report, envelope);
    Path parent = target.toAbsolutePath().getParent();
    if (parent != null) Files.createDirectories(parent);

    try (PDDocument document = new PDDocument(); LoadedFont loaded = loadFont(document)) {
      PDPage page = new PDPage(PDRectangle.A4);
      document.addPage(page);
      try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
        float y = PAGE_HEIGHT - TOP;
        y = text(stream, loaded.font(), y, "论文审核结果单", 18, LEFT, true);
        y -= 7;
        y = section(stream, loaded.font(), y, "论文信息");
        y = field(stream, loaded.font(), y, "文件名称", metadata.originalFilename(), 9.5f, 2);
        y = field(stream, loaded.font(), y, "学生姓名", metadata.student().name(), 9.5f, 1);
        y = field(stream, loaded.font(), y, "学号", metadata.student().username(), 9.5f, 1);
        y = field(stream, loaded.font(), y, "专业", metadata.student().major(), 9.5f, 2);
        y = field(stream, loaded.font(), y, "指导教师", metadata.student().teacherName(), 9.5f, 1);
        y = field(stream, loaded.font(), y, "提交次数", String.valueOf(metadata.submissionNumber()), 9.5f, 1);
        y = field(stream, loaded.font(), y, "审核完成", TIME.format(metadata.finishedAt()), 9.5f, 1);
        y -= 5;
        y = section(stream, loaded.font(), y, "审核结果");
        String result = summary.failures() == 0 ? "未发现明确格式问题" : "发现 " + summary.failures() + " 项明确格式问题";
        y = text(stream, loaded.font(), y, result, 12, LEFT, true);
        y = field(stream, loaded.font(), y, "待确认", summary.reviews() + " 项（不计入明确问题）", 9.5f, 1);
        y = field(stream, loaded.font(), y, "批注写入", summary.annotationLabel(), 9.5f, 1);
        y = field(stream, loaded.font(), y, "规则版本", summary.ruleSetId(), 8.5f, 1);
        y = field(stream, loaded.font(), y, "源文件指纹", summary.fingerprint(), 8.5f, 1);
        y -= 5;
        y = section(stream, loaded.font(), y, "统计说明");
        y = paragraph(stream, loaded.font(), y,
            "明确问题数统计本次审核结果中判断为 FAIL 的项目；REVIEW 表示需要人工确认，不作为错误统计。没有执行证据的规则不计为通过。", 8.5f);
        y = paragraph(stream, loaded.font(), y,
            "完整问题明细、待确认项、规则覆盖情况和未检查区域请查看同次生成的 Word 详细报告。", 8.5f);
        y -= 5;
        y = section(stream, loaded.font(), y, "提交提示");
        y = paragraph(stream, loaded.font(), y,
            "本结果单用于快速查看和纸质提交；修改论文时请结合带批注论文及 Word 详细报告中的逐项建议。", 8.5f);
        if (y < BOTTOM) throw new IOException("PDF 审核结果单内容超出单页版心");
      }
      if (document.getNumberOfPages() != 1) throw new IOException("PDF 审核结果单必须为一页");
      document.save(target.toFile());
    }
  }

  private static Summary summary(JsonNode report, JsonNode envelope) throws IOException {
    JsonNode results = report.path("results");
    if (!results.isArray()) throw new IOException("审核 JSON 缺少 results 数组");
    int failures = 0;
    int reviews = 0;
    for (JsonNode result : results) {
      switch (result.path("judgement").asText()) {
        case "FAIL" -> failures++;
        case "REVIEW" -> reviews++;
        case "PASS", "NOT_APPLICABLE" -> { }
        default -> throw new IOException("审核 JSON 含有未知判断结果");
      }
    }
    JsonNode annotation = envelope.path("annotation");
    String annotationLabel;
    if (!annotation.isObject()) {
      annotationLabel = "本次审核未提供批注状态";
    } else if (!annotation.path("available").asBoolean(false)) {
      annotationLabel = "未生成可下载批注文件";
    } else {
      JsonNode items = annotation.path("items");
      if (!items.isArray()) throw new IOException("批注状态可用但缺少批注明细");
      int written = 0;
      int notWritten = 0;
      for (JsonNode item : items) {
        if ("WRITTEN".equals(item.path("status").asText())) written++;
        else notWritten++;
      }
      annotationLabel = notWritten == 0
          ? "已写入 " + written + " 项"
          : "已写入 " + written + " 项，未写入 " + notWritten + " 项";
    }
    return new Summary(failures, reviews, blankAsDash(report.path("ruleSetId").asText("")),
        shortFingerprint(report.path("sourceSha256").asText("")), annotationLabel);
  }

  private LoadedFont loadFont(PDDocument document) throws IOException {
    if (!configuredFontPath.isBlank()) {
      Path path = Path.of(configuredFontPath);
      if (!Files.isRegularFile(path)) throw new IOException("配置的 PDF 中文字体文件不存在");
      return load(document, path);
    }
    String property = System.getProperty("thesis.pdf.font", "").trim();
    String environment = System.getenv("THESIS_PDF_FONT_PATH");
    if (!property.isBlank()) return loadConfigured(document, Path.of(property));
    if (environment != null && !environment.isBlank()) return loadConfigured(document, Path.of(environment.trim()));

    List<Path> paths = List.of(
        Path.of("/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc"),
        Path.of("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"),
        Path.of("C:/Windows/Fonts/simsun.ttc"),
        Path.of("C:/Windows/Fonts/Deng.ttf"));
    IOException failure = new IOException("未找到可嵌入的中文字体，请配置 platform.report.pdf-font-path");
    for (Path path : paths) {
      if (!Files.isRegularFile(path)) continue;
      try {
        return load(document, path);
      } catch (IOException | RuntimeException error) {
        failure.addSuppressed(error);
      }
    }
    throw failure;
  }

  private LoadedFont loadConfigured(PDDocument document, Path path) throws IOException {
    if (!Files.isRegularFile(path)) throw new IOException("配置的 PDF 中文字体文件不存在");
    return load(document, path);
  }

  private static LoadedFont load(PDDocument document, Path path) throws IOException {
    if (!path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ttc")) {
      return new LoadedFont(PDType0Font.load(document, path.toFile()), null);
    }
    TrueTypeCollection collection = new TrueTypeCollection(path.toFile());
    try {
      List<TrueTypeFont> fonts = new ArrayList<>();
      collection.processAllFonts(fonts::add);
      TrueTypeFont selected = fonts.stream()
          .filter(font -> fontName(font).toLowerCase(Locale.ROOT).contains("wenquanyi"))
          .findFirst().orElseGet(() -> fonts.isEmpty() ? null : fonts.get(0));
      if (selected == null) throw new IOException("中文字体集合中没有可用字型");
      return new LoadedFont(PDType0Font.load(document, selected, true), collection);
    } catch (IOException | RuntimeException error) {
      collection.close();
      throw error;
    }
  }

  private static String fontName(TrueTypeFont font) {
    try {
      return font.getName();
    } catch (IOException error) {
      return "";
    }
  }

  private static float section(PDPageContentStream stream, PDFont font, float y, String label) throws IOException {
    stream.moveTo(LEFT, y - 2);
    stream.lineTo(PAGE_WIDTH - RIGHT, y - 2);
    stream.stroke();
    return text(stream, font, y - 14, label, 10.5f, LEFT, true) - 1;
  }

  private static float field(PDPageContentStream stream, PDFont font, float y, String label,
      String value, float size, int maxLines) throws IOException {
    String prefix = label + "：";
    float prefixWidth = font.getStringWidth(prefix) / 1000f * size;
    drawText(stream, font, y, prefix, size, LEFT, true);
    List<String> lines = wrap(font, blankAsDash(value), size, PAGE_WIDTH - RIGHT - LEFT - prefixWidth, maxLines);
    for (int index = 0; index < lines.size(); index++) {
      drawText(stream, font, y - index * LINE_HEIGHT, lines.get(index), size,
          index == 0 ? LEFT + prefixWidth : LEFT, false);
    }
    return y - LINE_HEIGHT * Math.max(1, lines.size());
  }

  private static float paragraph(PDPageContentStream stream, PDFont font, float y,
      String value, float size) throws IOException {
    List<String> lines = wrap(font, value, size, PAGE_WIDTH - RIGHT - LEFT, 2);
    for (int index = 0; index < lines.size(); index++) {
      drawText(stream, font, y - index * LINE_HEIGHT, lines.get(index), size, LEFT, false);
    }
    return y - LINE_HEIGHT * Math.max(1, lines.size());
  }

  private static float text(PDPageContentStream stream, PDFont font, float y, String value,
      float size, float x, boolean bold) throws IOException {
    drawText(stream, font, y, value, size, x, bold);
    return y - LINE_HEIGHT;
  }

  private static void drawText(PDPageContentStream stream, PDFont font, float y, String value,
      float size, float x, boolean bold) throws IOException {
    stream.beginText();
    stream.setFont(font, size);
    stream.newLineAtOffset(x, y);
    if (bold) {
      stream.setStrokingColor(Color.BLACK);
      stream.setLineWidth(0.2f);
      stream.setRenderingMode(RenderingMode.FILL_STROKE);
    }
    stream.showText(value);
    if (bold) stream.setRenderingMode(RenderingMode.FILL);
    stream.endText();
  }

  private static List<String> wrap(PDFont font, String value, float size, float width, int maxLines) throws IOException {
    String normalized = blankAsDash(value).replace('\r', ' ').replace('\n', ' ');
    List<String> lines = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean truncated = false;
    for (int offset = 0; offset < normalized.length();) {
      int codePoint = normalized.codePointAt(offset);
      String part = new String(Character.toChars(codePoint));
      if (current.length() > 0 && font.getStringWidth(current + part) / 1000f * size > width) {
        if (cannotStartLine(codePoint)) {
          current.append(part);
          offset += Character.charCount(codePoint);
          continue;
        }
        lines.add(current.toString());
        current.setLength(0);
        if (lines.size() == maxLines) {
          truncated = true;
          break;
        }
      }
      current.append(part);
      offset += Character.charCount(codePoint);
    }
    if (!truncated && current.length() > 0) lines.add(current.toString());
    if (lines.isEmpty()) lines.add("—");
    if (truncated) lines.set(lines.size() - 1, ellipsis(font, lines.get(lines.size() - 1), size, width));
    return lines;
  }

  private static boolean cannotStartLine(int codePoint) {
    return "，。；：！？、…）》】』」〕〉》〗〙〛”’.,;:!?)]}%".indexOf(codePoint) >= 0;
  }

  private static String ellipsis(PDFont font, String value, float size, float width) throws IOException {
    String result = value;
    while (!result.isEmpty() && font.getStringWidth(result + "…") / 1000f * size > width) {
      int end = result.offsetByCodePoints(result.length(), -1);
      result = result.substring(0, end);
    }
    return result + "…";
  }

  private static String shortFingerprint(String fingerprint) {
    String clean = blankAsDash(fingerprint);
    return clean.length() > 16 ? clean.substring(0, 16) + "…" : clean;
  }

  private static String blankAsDash(String value) {
    return value == null || value.isBlank() ? "—" : value;
  }

  private record Summary(int failures, int reviews, String ruleSetId, String fingerprint,
      String annotationLabel) { }

  private record LoadedFont(PDType0Font font, TrueTypeCollection collection) implements AutoCloseable {
    @Override public void close() throws IOException {
      if (collection != null) collection.close();
    }
  }
}
