package cn.luwneshacha.thesis.rendering;

import cn.luwneshacha.thesis.conversion.ConversionProcess;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Runs a local Office renderer in a request-private profile and retains only bounded text geometry. */
public final class RenderedLayoutAdapter {
  private static final Logger LOG = LoggerFactory.getLogger(RenderedLayoutAdapter.class);
  private static final String WORKER_MAIN = "cn.luwneshacha.thesis.rendering.PdfLayoutWorker";
  private static final String BOOT_LAUNCHER =
      "org.springframework.boot.loader.launch.PropertiesLauncher";
  private static final long MAX_RENDER_OVERHEAD_BYTES = 32L * 1024 * 1024;
  private final String executable;
  private final Duration timeout;
  private final long maxSourceBytes;
  private final long maxPdfBytes;
  private final int maxPages;
  private final RenderSafety safety;

  RenderedLayoutAdapter(String executable, Duration timeout, long maxSourceBytes,
      long maxPdfBytes, int maxPages) {
    this(executable, timeout, maxSourceBytes, maxPdfBytes, maxPages, null);
  }

  public RenderedLayoutAdapter(String executable, Duration timeout, long maxSourceBytes,
      long maxPdfBytes, int maxPages, RenderSafety safety) {
    if (executable == null || executable.isBlank() || timeout.isNegative() || timeout.isZero()
        || maxSourceBytes < 1 || maxPdfBytes < 1
        || maxPdfBytes > Long.MAX_VALUE - MAX_RENDER_OVERHEAD_BYTES || maxPages < 1)
      throw new IllegalArgumentException("invalid layout renderer configuration");
    this.executable = executable;
    this.timeout = timeout;
    this.maxSourceBytes = maxSourceBytes;
    this.maxPdfBytes = maxPdfBytes;
    this.maxPages = maxPages;
    this.safety = safety;
  }

  public RenderedLayoutEvidence render(Path source, Path requestDirectory) {
    String sourceSha = "";
    String version = "";
    String timestamp = Instant.now().toString();
    Path work = null;
    try {
      if (!Files.isRegularFile(source) || Files.size(source) > maxSourceBytes)
        return unavailable(sourceSha, version, timestamp, "SOURCE_LIMIT_EXCEEDED");
      sourceSha = sha256(source);
      // A retry must never observe a PDF left behind by an earlier failed
      // conversion and attribute it to the current source hash.
      work = Files.createTempDirectory(requestDirectory, "render-layout-");
      if (safety != null) {
        String memoryFailure = verifyMemoryLimit(work);
        if (memoryFailure != null) return unavailable(sourceSha, version, timestamp, memoryFailure);
        String fontFailure = verifyRequiredFonts(work);
        if (fontFailure != null) return unavailable(sourceSha, version, timestamp, fontFailure);
      }
      Path output = Files.createDirectories(work.resolve("pdf"));
      Path versionLog = work.resolve("version.log");
      try {
        runConverter(List.of(executable, "--version"), versionLog, Duration.ofSeconds(30));
      } catch (IOException error) {
        String reason = error.getMessage() != null && error.getMessage().contains("timed out")
            ? "RENDER_TIMEOUT" : "RENDERER_UNAVAILABLE";
        return unavailable(sourceSha, version, timestamp, reason);
      }
      version = Files.readString(versionLog).lines().filter(line -> line.startsWith("LibreOffice"))
          .findFirst().orElse("").strip();
      if (version.isBlank()) return unavailable(sourceSha, version, timestamp, "RENDERER_VERSION_UNKNOWN");
      String profile = "-env:UserInstallation=" + work.resolve("profile").toAbsolutePath().toUri();
      runBoundedConverter(List.of(executable, profile, "--headless", "--convert-to",
          "pdf", "--outdir", output.toString(), source.toAbsolutePath().toString()),
          work.resolve("conversion.log"), timeout, work);
      String name = source.getFileName().toString();
      Path pdf = output.resolve(name.substring(0, name.lastIndexOf('.')) + ".pdf");
      if (!Files.isRegularFile(pdf)) return unavailable(sourceSha, version, timestamp, "PDF_NOT_CREATED");
      if (Files.size(pdf) > maxPdfBytes)
        return unavailable(sourceSha, version, timestamp, "PDF_LIMIT_EXCEEDED");
      String pdfSha = sha256(pdf);
      PdfLayoutReader.ReadResult page;
      if (safety == null) {
        page = new PdfLayoutReader().readWithPageCount(pdf, maxPages);
      } else {
        Path workerJar = packagedWorkerJar();
        if (workerJar == null)
          return unavailable(sourceSha, version, timestamp, "PDF_WORKER_UNAVAILABLE");
        Path geometry = work.resolve("geometry.bin");
        try {
          runPdfWorker(workerJar, pdf, geometry, work.resolve("pdf-worker.log"));
        } catch (IOException error) {
          String reason = error.getMessage() != null && error.getMessage().contains("timed out")
              ? "PDF_WORKER_TIMEOUT" : "PDF_WORKER_FAILED";
          return unavailable(sourceSha, version, timestamp, reason);
        }
        try {
          page = PdfLayoutWorker.read(geometry, maxPages);
        } catch (IOException error) {
          return unavailable(sourceSha, version, timestamp, "PDF_WORKER_INVALID_EVIDENCE");
        }
      }
      var provenance = new RenderedLayoutEvidence.Provenance("available", sourceSha, pdfSha,
          "LibreOffice", version, timestamp, null, page.pageCount());
      return new RenderedLayoutEvidence(provenance, page.lines(), page.pageBoxes());
    } catch (ConversionProcess.OutputLimitExceededException error) {
      return unavailable(sourceSha, version, timestamp, "RENDER_WORK_LIMIT_EXCEEDED");
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      return unavailable(sourceSha, version, timestamp, "RENDER_INTERRUPTED");
    } catch (IOException error) {
      String reason = error.getMessage() != null && error.getMessage().contains("timed out")
          ? "RENDER_TIMEOUT" : "RENDER_FAILED";
      return unavailable(sourceSha, version, timestamp, reason);
    } finally {
      if (work != null) removeRenderWork(work);
    }
  }

  /** Required fonts are exact installed families, not fontconfig fallback matches. */
  public record RenderSafety(String limiterExecutable, String fontListExecutable,
      long maxAddressSpaceBytes, List<String> requiredFontFamilies) {
    public RenderSafety {
      Objects.requireNonNull(limiterExecutable, "limiterExecutable");
      Objects.requireNonNull(fontListExecutable, "fontListExecutable");
      requiredFontFamilies = List.copyOf(requiredFontFamilies);
    }
  }

  private String verifyMemoryLimit(Path work) throws InterruptedException {
    return ConversionProcess.provesAddressSpaceLimit(safety.limiterExecutable(),
        safety.maxAddressSpaceBytes(), work.resolve("memory-limit.log"))
        ? null : "RENDER_MEMORY_LIMIT_UNVERIFIED";
  }

  private String verifyRequiredFonts(Path work) throws InterruptedException {
    if (safety.requiredFontFamilies().isEmpty()
        || !Files.isExecutable(Path.of(safety.fontListExecutable())))
      return "RENDER_FONT_INVENTORY_UNVERIFIED";
    Path log = work.resolve("installed-fonts.log");
    try {
      ConversionProcess.run(List.of(safety.fontListExecutable(), "-f", "%{family}\\n"),
          log, Duration.ofSeconds(10));
      Set<String> installed = new HashSet<>();
      Files.readString(log).lines().forEach(line -> {
        for (String name : line.split(",")) installed.add(name.strip().toLowerCase(Locale.ROOT));
      });
      return safety.requiredFontFamilies().stream().allMatch(name ->
          installed.contains(name.strip().toLowerCase(Locale.ROOT)))
          ? null : "RENDER_REQUIRED_FONT_UNAVAILABLE";
    } catch (IOException error) {
      return "RENDER_FONT_INVENTORY_UNVERIFIED";
    }
  }

  private void runConverter(List<String> command, Path log, Duration duration)
      throws IOException, InterruptedException {
    if (safety == null) ConversionProcess.run(command, log, duration);
    else ConversionProcess.runLimited(command, log, duration, safety.limiterExecutable(),
        safety.maxAddressSpaceBytes());
  }

  private void runBoundedConverter(List<String> command, Path log, Duration duration, Path work)
      throws IOException, InterruptedException {
    if (safety == null) {
      // The package-private constructor exists for local tests only; production has RenderSafety.
      ConversionProcess.run(command, log, duration);
    } else {
      long workLimit = maxPdfBytes + MAX_RENDER_OVERHEAD_BYTES;
      ConversionProcess.runLimited(command, log, duration, safety.limiterExecutable(),
          safety.maxAddressSpaceBytes(), work, workLimit, workLimit);
    }
  }

  private void runPdfWorker(Path jar, Path pdf, Path geometry, Path log)
      throws IOException, InterruptedException {
    Path java = Path.of(System.getProperty("java.home"), "bin", "java");
    if (!Files.isExecutable(java)) throw new IOException("PDF worker Java executable is unavailable");
    Path environment = Path.of("/usr/bin/env");
    if (!Files.isExecutable(environment))
      throw new IOException("PDF worker environment launcher is unavailable");
    // glibc may reserve one large malloc arena per thread. Under RLIMIT_AS
    // those virtual reservations can exhaust 1 GiB while RSS stays low.
    var command = new ArrayList<String>();
    command.add(environment.toString());
    command.add("MALLOC_ARENA_MAX=1");
    command.addAll(List.of(java.toString(), "-Xms32m", "-Xmx256m",
        "-XX:ActiveProcessorCount=2",
        "-XX:MaxDirectMemorySize=64m", "-XX:CompressedClassSpaceSize=64m",
        "-XX:MaxMetaspaceSize=128m", "-XX:ReservedCodeCacheSize=64m",
        "-XX:+UseSerialGC", "-Xss512k", "-Dloader.main=" + WORKER_MAIN,
        "-cp", jar.toString(), BOOT_LAUNCHER, pdf.toString(), geometry.toString(),
        Integer.toString(maxPages)));
    ConversionProcess.runLimited(command, log, timeout, safety.limiterExecutable(),
        safety.maxAddressSpaceBytes());
  }

  private static Path packagedWorkerJar() {
    String classpath = System.getProperty("java.class.path", "");
    if (classpath.isBlank() || classpath.contains(File.pathSeparator)) return null;
    Path jar = Path.of(classpath).toAbsolutePath().normalize();
    if (!jar.toString().endsWith(".jar") || !Files.isRegularFile(jar)) return null;
    try (var zip = new ZipFile(jar.toFile())) {
      return zip.getEntry("org/springframework/boot/loader/launch/PropertiesLauncher.class") != null
          && zip.getEntry("BOOT-INF/classes/cn/luwneshacha/thesis/rendering/PdfLayoutWorker.class") != null
          ? jar : null;
    } catch (IOException error) {
      return null;
    }
  }

  private static void removeRenderWork(Path work) {
    try {
      Files.walkFileTree(work, new SimpleFileVisitor<>() {
        @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
            throws IOException {
          Files.deleteIfExists(file);
          return FileVisitResult.CONTINUE;
        }

        @Override public FileVisitResult postVisitDirectory(Path directory, IOException error)
            throws IOException {
          if (error != null) throw error;
          Files.deleteIfExists(directory);
          return FileVisitResult.CONTINUE;
        }
      });
    } catch (IOException error) {
      LOG.warn("Could not remove temporary renderer files; request cleanup will retry", error);
    }
  }

  private static RenderedLayoutEvidence unavailable(String sourceSha, String version,
      String timestamp, String reason) {
    return new RenderedLayoutEvidence(new RenderedLayoutEvidence.Provenance("unavailable",
        sourceSha, null, "LibreOffice", version, timestamp, reason, 0), List.of());
  }

  private static String sha256(Path file) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream input = new DigestInputStream(Files.newInputStream(file), digest)) {
        input.transferTo(java.io.OutputStream.nullOutputStream());
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable", error);
    }
  }
}
