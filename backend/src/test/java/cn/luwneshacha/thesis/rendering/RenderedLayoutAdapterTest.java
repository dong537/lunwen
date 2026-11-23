package cn.luwneshacha.thesis.rendering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RenderedLayoutAdapterTest {
  @TempDir Path temp;

  @Test
  void unsafeInProcessConstructorIsNotPublicApi() throws Exception {
    var constructor = RenderedLayoutAdapter.class.getDeclaredConstructor(String.class,
        Duration.class, long.class, long.class, int.class);
    assertFalse(java.lang.reflect.Modifier.isPublic(constructor.getModifiers()));
  }

  @Test
  void failedRendererIsRecordedAsUnavailableInsteadOfPassingRules() throws Exception {
    Path source = temp.resolve("source.docx");
    Files.writeString(source, "example");

    var result = new RenderedLayoutAdapter("nonexistent-libreoffice-binary",
        Duration.ofSeconds(2), 50_000_000, 25_000_000, 200).render(source, temp);

    assertEquals("unavailable", result.provenance().status());
    assertEquals("RENDERER_UNAVAILABLE", result.provenance().reasonCode());
    assertEquals(64, result.provenance().sourceSha256().length());
    assertEquals(RenderedLayoutEvidence.KeywordStatus.UNKNOWN,
        result.englishKeywordAlignment("Keywords: Alpha; Beta").status());
    try (var children = Files.list(temp)) {
      assertTrue(children.noneMatch(path -> path.getFileName().toString().startsWith("render-layout-")));
    }
  }

  @Test
  void previousRenderArtifactsCannotBeReusedAsCurrentPdfEvidence() throws Exception {
    Path source = temp.resolve("source.docx");
    Files.writeString(source, "example");
    Files.writeString(temp.resolve("render-layout"), "occupied by a previous run");

    var result = new RenderedLayoutAdapter("nonexistent-libreoffice-binary",
        Duration.ofSeconds(2), 50_000_000, 25_000_000, 200).render(source, temp);

    assertEquals("RENDERER_UNAVAILABLE", result.provenance().reasonCode());
  }

  @Test
  void strictRenderingStaysUnknownWhenTheProcessLimitCannotBeProven() throws Exception {
    Path source = temp.resolve("source.docx");
    Files.writeString(source, "example");
    var safety = new RenderedLayoutAdapter.RenderSafety("missing-prlimit-binary",
        "missing-fc-list-binary", 1_073_741_824L, java.util.List.of("Times New Roman"));

    var result = new RenderedLayoutAdapter("missing-soffice-binary", Duration.ofSeconds(2),
        50_000_000, 25_000_000, 200, safety).render(source, temp);

    assertEquals("unavailable", result.provenance().status());
    assertEquals("RENDER_MEMORY_LIMIT_UNVERIFIED", result.provenance().reasonCode());
  }

  @Test
  void strictRenderingStaysUnknownWhenRequiredFontsWouldBeSubstituted() throws Exception {
    Assumptions.assumeTrue(Files.isExecutable(Path.of("/usr/bin/prlimit"))
        && Files.isExecutable(Path.of("/usr/bin/fc-list")));
    Path source = temp.resolve("source.docx");
    Files.writeString(source, "example");
    var safety = new RenderedLayoutAdapter.RenderSafety("/usr/bin/prlimit",
        "/usr/bin/fc-list", 1_073_741_824L, java.util.List.of("Uninstalled Thesis Font 999"));

    var result = new RenderedLayoutAdapter("missing-soffice-binary", Duration.ofSeconds(5),
        50_000_000, 25_000_000, 200, safety).render(source, temp);

    assertEquals("unavailable", result.provenance().status());
    assertEquals("RENDER_REQUIRED_FONT_UNAVAILABLE", result.provenance().reasonCode());
  }

  @Test
  void oversizedRendererFileReportsDiskLimitInsteadOfGenericFailure() throws Exception {
    Assumptions.assumeTrue(Files.isExecutable(Path.of("/usr/bin/prlimit"))
        && Files.isExecutable(Path.of("/usr/bin/setsid")));
    Path source = temp.resolve("source.docx");
    Files.writeString(source, "example");
    Path fontList = temp.resolve("font-list.sh");
    Files.writeString(fontList, "#!/bin/sh\nprintf 'Disk Test Font\\n'\n");
    assertTrue(fontList.toFile().setExecutable(true));
    Path renderer = temp.resolve("renderer.sh");
    Files.writeString(renderer, "#!/bin/sh\n"
        + "if [ \"$1\" = --version ]; then echo 'LibreOffice test'; exit 0; fi\n"
        + "while [ \"$#\" -gt 0 ]; do\n"
        + "  if [ \"$1\" = --outdir ]; then shift; outdir=$1; fi\n"
        + "  shift\n"
        + "done\n"
        + "dd if=/dev/zero of=\"$outdir/source.pdf\" bs=1048576 count=40 2>/dev/null\n");
    assertTrue(renderer.toFile().setExecutable(true));
    var safety = new RenderedLayoutAdapter.RenderSafety("/usr/bin/prlimit",
        fontList.toString(), 1_073_741_824L, java.util.List.of("Disk Test Font"));

    var result = new RenderedLayoutAdapter(renderer.toString(), Duration.ofSeconds(5),
        50_000_000L, 1L, 200, safety).render(source, temp);

    assertEquals("unavailable", result.provenance().status());
    assertEquals("RENDER_WORK_LIMIT_EXCEEDED", result.provenance().reasonCode());
  }

  @Test
  void boundedPdfWorkerLimitsProcessorCountAndMallocArenasBeforeLaunchingJava() throws Exception {
    Assumptions.assumeTrue(Files.isExecutable(Path.of("/bin/sh")));
    Path limiter = temp.resolve("record-limit.sh");
    Files.writeString(limiter, "#!/bin/sh\nprintf '%s\\n' \"$@\"\n");
    assertTrue(limiter.toFile().setExecutable(true));
    var safety = new RenderedLayoutAdapter.RenderSafety(limiter.toString(),
        "unused-font-list", 1_073_741_824L, java.util.List.of("unused"));
    var adapter = new RenderedLayoutAdapter("unused-converter", Duration.ofSeconds(5),
        50_000_000, 25_000_000, 200, safety);
    Path log = temp.resolve("worker-command.log");

    var run = RenderedLayoutAdapter.class.getDeclaredMethod("runPdfWorker",
        Path.class, Path.class, Path.class, Path.class);
    run.setAccessible(true);
    run.invoke(adapter, temp.resolve("app.jar"), temp.resolve("sample.pdf"),
        temp.resolve("geometry.bin"), log);

    var arguments = Files.readAllLines(log);
    assertEquals("--as=1073741824", arguments.get(0));
    assertEquals("--", arguments.get(1));
    assertEquals("/usr/bin/env", arguments.get(2));
    assertEquals("MALLOC_ARENA_MAX=1", arguments.get(3));
    assertEquals(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        arguments.get(4));
    assertTrue(arguments.indexOf("-XX:ActiveProcessorCount=2") > 4,
        "limited Java process must receive its processor cap");
    assertTrue(arguments.indexOf("-XX:ActiveProcessorCount=2")
        < arguments.indexOf("-Dloader.main=cn.luwneshacha.thesis.rendering.PdfLayoutWorker"));
  }

  @Test
  void realLibreOfficeConversionRecordsPdfHashAndVersionWhenInstalled() throws Exception {
    String binary = System.getProperty("thesis.soffice",
        System.getProperty("os.name").toLowerCase().contains("win")
            ? "C:/Program Files/LibreOffice/program/soffice.com" : "/usr/bin/soffice");
    Assumptions.assumeTrue(Files.isRegularFile(Path.of(binary)));
    Path source = temp.resolve("source.docx");
    var doc = WordprocessingMLPackage.createPackage();
    doc.getMainDocumentPart().addParagraphOfText("Keywords: Alpha; Beta; Gamma");
    doc.save(source.toFile());

    var result = new RenderedLayoutAdapter(binary, Duration.ofSeconds(60),
        50_000_000, 25_000_000, 200).render(source, temp);

    assertEquals("available", result.provenance().status(), result.provenance().reasonCode());
    assertEquals(64, result.provenance().sourceSha256().length());
    assertEquals(64, result.provenance().pdfSha256().length());
    assertNotNull(result.provenance().rendererVersion());
    assertTrue(result.provenance().pageCount() >= 1);
    assertEquals(RenderedLayoutEvidence.KeywordStatus.NO_WRAP,
        result.englishKeywordAlignment("Keywords: Alpha; Beta; Gamma").status());
  }
}
