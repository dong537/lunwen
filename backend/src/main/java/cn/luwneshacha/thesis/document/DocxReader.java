package cn.luwneshacha.thesis.document;

import cn.luwneshacha.thesis.inspection.InspectionException;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.w3c.dom.Element;

public final class DocxReader {
  private static final String RELATIONSHIPS = "http://schemas.openxmlformats.org/package/2006/relationships";
  private static final String HYPERLINK_RELATIONSHIP = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink";
  private static final String STRICT_HYPERLINK_RELATIONSHIP = "http://purl.oclc.org/ooxml/officeDocument/relationships/hyperlink";
  private static final int DEFAULT_MAX_PART_BYTES = 64 * 1024 * 1024;
  private static final int DEFAULT_MAX_TOTAL_BYTES = 256 * 1024 * 1024;
  private static final int DEFAULT_MAX_SOURCE_BYTES = 50 * 1024 * 1024;
  private static final int MAX_ENTRIES = 10_000;
  private final int maxPartBytes;
  private final int maxTotalBytes;
  private final int maxSourceBytes;

  public DocxReader() { this(DEFAULT_MAX_PART_BYTES, DEFAULT_MAX_TOTAL_BYTES, DEFAULT_MAX_SOURCE_BYTES); }

  public DocxReader(int maxPartBytes) {
    this(maxPartBytes, DEFAULT_MAX_TOTAL_BYTES);
  }

  public DocxReader(int maxPartBytes, int maxTotalBytes) {
    this(maxPartBytes, maxTotalBytes, DEFAULT_MAX_SOURCE_BYTES);
  }

  public DocxReader(int maxPartBytes, int maxTotalBytes, int maxSourceBytes) {
    if (maxPartBytes < 1 || maxTotalBytes < 1 || maxSourceBytes < 1)
      throw new IllegalArgumentException("DOCX size limits must be positive");
    this.maxPartBytes = maxPartBytes;
    this.maxTotalBytes = maxTotalBytes;
    this.maxSourceBytes = maxSourceBytes;
  }

  public RawDocx read(Path source) {
    if (source == null || !Files.isRegularFile(source)) invalid("input file does not exist");
    byte[] sourceBytes;
    try (InputStream input = Files.newInputStream(source)) { sourceBytes = readSourceBounded(input); }
    catch (IOException e) { throw new InspectionException("INVALID_DOCX", "input cannot be read"); }
    Map<String, RawPart> parts = new LinkedHashMap<>();
    try (ZipFile zip = new ZipFile(source.toFile())) {
      int entries = 0;
      int totalBytes = 0;
      var iterator = zip.entries();
      while (iterator.hasMoreElements()) {
        if (++entries > MAX_ENTRIES) resourceLimit("too many ZIP entries");
        ZipEntry entry = iterator.nextElement();
        validateName(entry.getName());
        if (entry.isDirectory()) continue;
        if (parts.containsKey(entry.getName())) invalid("duplicate ZIP part: " + entry.getName());
        byte[] bytes = readBounded(zip.getInputStream(entry), maxTotalBytes - totalBytes);
        totalBytes += bytes.length;
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

  private byte[] readSourceBounded(InputStream input) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxSourceBytes, 8192));
    byte[] buffer = new byte[8192];
    int total = 0;
    for (int count; (count = input.read(buffer)) != -1;) {
      if (count > maxSourceBytes - total) resourceLimit("DOCX exceeds configured compressed size limit");
      out.write(buffer, 0, count);
      total += count;
    }
    return out.toByteArray();
  }

  private byte[] readBounded(InputStream input, int remainingTotalBytes) throws IOException {
    if (remainingTotalBytes <= 0) resourceLimit("DOCX exceeds configured uncompressed size limit");
    try (input) {
      ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxPartBytes, 8192));
      byte[] buffer = new byte[8192];
      int total = 0;
      for (int count; (count = input.read(buffer)) != -1;) {
        if (count > maxPartBytes - total) resourceLimit("ZIP part exceeds configured limit");
        if (count > remainingTotalBytes - total) resourceLimit("DOCX exceeds configured uncompressed size limit");
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
    try {
      Element root = XmlPartSupport.parse(bytes).getDocumentElement();
      if (!name.endsWith(".rels")) return;
      var relationships = root.getElementsByTagNameNS(RELATIONSHIPS, "Relationship");
      for (int index = 0; index < relationships.getLength(); index++) {
        Element relationship = (Element) relationships.item(index);
        String targetMode = relationship.getAttribute("TargetMode").trim();
        String type = relationship.getAttribute("Type");
        boolean externalHyperlink = HYPERLINK_RELATIONSHIP.equals(type) || STRICT_HYPERLINK_RELATIONSHIP.equals(type);
        if ("External".equalsIgnoreCase(targetMode) && !externalHyperlink)
          invalid("external relationship is not supported in " + name);
      }
    } catch (cn.luwneshacha.thesis.inspection.InspectionException error) {
      throw error;
    } catch (Exception error) {
      throw new InspectionException("INVALID_DOCX", "invalid or unsafe XML part: " + name, error);
    }
  }

  private static String sha256(byte[] bytes) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    catch (Exception e) { throw new IllegalStateException(e); }
  }

  private static void invalid(String message) { throw new InspectionException("INVALID_DOCX", message); }
  private static void resourceLimit(String message) { throw new InspectionException("RESOURCE_LIMIT", message); }
}
