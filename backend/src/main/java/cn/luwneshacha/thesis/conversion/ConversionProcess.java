package cn.luwneshacha.thesis.conversion;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Bounds converter execution and drains output to disk so a full pipe cannot hang a worker. */
final class ConversionProcess {
  private ConversionProcess() {}

  static void run(List<String> command, Path log, Duration timeout) throws IOException, InterruptedException {
    if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("positive timeout required");
    // Own the log handle in this JVM. Windows may retain a redirected child handle
    // briefly after termination; the caller must be able to remove the work directory.
    try (var target = Files.newOutputStream(log)) {
      Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      InputStream source = process.getInputStream();
      AtomicReference<IOException> outputFailure = new AtomicReference<>();
      Thread drain = Thread.ofVirtual().start(() -> {
        try { source.transferTo(target); }
        catch (IOException error) { outputFailure.set(error); }
      });
      try {
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS))
          throw new IOException("LibreOffice conversion timed out");
        if (process.exitValue() != 0)
          throw new IOException("LibreOffice conversion failed with exit code " + process.exitValue());
        if (!drain.join(Duration.ofSeconds(5)))
          throw new IOException("LibreOffice output did not close after process exit");
        if (outputFailure.get() != null) throw outputFailure.get();
      } finally {
        if (process.isAlive()) {
          process.descendants().forEach(ProcessHandle::destroyForcibly);
          process.destroyForcibly();
          process.waitFor(5, TimeUnit.SECONDS);
        }
        source.close();
        process.getOutputStream().close();
        process.getErrorStream().close();
        drain.interrupt();
      }
    }
  }
}
