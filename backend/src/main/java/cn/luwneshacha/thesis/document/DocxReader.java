package cn.luwneshacha.thesis.document;

import cn.luwneshacha.thesis.inspection.InspectionException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class DocxReader {
  private static final int DEFAULT_MAX_PART_BYTES = 16 * 1024 * 1024;
  private static final int MAX_ENTRIES = 10_000;
  private final int maxPartBytes;

  public DocxReader() { this(DEFAULT_MAX_PART_BYTES); }

  public DocxReader(int maxPartBytes) {
    if (maxPartBytes < 1) throw new IllegalArgumentException("maxPartBytes must be positive");
    this.maxPartBytes = maxPartBytes;
  }

  public RawDocx read(Path source) {
    if (source == null || !Files.isRegularFile(source)) invalid("input file does not exist");
    byte[] sourceBytes;
    try { sourceBytes = Files.readAllBytes(source); }
    catch (IOException e) { throw new InspectionException("INVALID_DOCX", "input cannot be read"); }
    Map<String, RawPart> parts = new LinkedHashMap<>();
    try (ZipFile zip = new ZipFile(source.toFile())) {
      int entries = 0;
      var iterator = zip.entries();
      while (iterator.hasMoreElements()) {
        if (++entries > MAX_ENTRIES) resourceLimit("too many ZIP entries");
        ZipEntry entry = iterator.nextElement();
        validateName(entry.getName());
        if (entry.isDirectory()) continue;
        if (parts.containsKey(entry.getName())) invalid("duplicate ZIP part: " + entry.getName());
        byte[] bytes = readBounded(zip.getInputStream(entry));
        boolean xml = entry.getName().endsWith(".xml") || entry.getName().endsWith(".rels");
        if (xml) validateXml(bytes, entry.getName());
        parts.put(entry.getName(), new RawPart(entry.getName(), sha256(bytes), bytes, xml));
      }
      if (!parts.containsKey("[Content_Types].xml")) invalid("missing [Content_Types].xml");
      return new RawDocx(sha256(sourceBytes), sourceBytes, parts, entries);
    } catch (InspectionException e) { throw e; }
    catch (IOException | RuntimeException e) {
      throw new InspectionException("INVALID_DOCX", "input is not a readable DOCX package");
    }
  }

  private byte[] readBounded(InputStream input) throws IOException {
    try (input) {
      ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxPartBytes, 8192));
      byte[] buffer = new byte[8192];
      int total = 0;
      for (int count; (count = input.read(buffer)) != -1;) {
        if (count > maxPartBytes - total) resourceLimit("ZIP part exceeds configured limit");
        out.write(buffer, 0, count);
        total += count;
      }
      return out.toByteArray();
    }
  }

  private static void validateName(String name) {
    if (name.isBlank() || name.startsWith("/") || name.contains("\\") || name.contains(".."))
      invalid("invalid ZIP part name: " + name);
  }

  private static void validateXml(byte[] bytes, String name) {
    String text = new String(bytes, StandardCharsets.UTF_8).toUpperCase(Locale.ROOT);
    if (text.contains("<!DOCTYPE") || text.contains("TARGETMODE=\"EXTERNAL\""))
      invalid("unsafe XML or external relationship in " + name);
  }

  private static String sha256(byte[] bytes) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    catch (Exception e) { throw new IllegalStateException(e); }
  }

  private static void invalid(String message) { throw new InspectionException("INVALID_DOCX", message); }
  private static void resourceLimit(String message) { throw new InspectionException("RESOURCE_LIMIT", message); }
}
