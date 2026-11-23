package cn.luwneshacha.thesis.conversion;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounds converter execution, output and (on Linux) the entire dedicated process group. */
public final class ConversionProcess {
  private static final System.Logger LOG = System.getLogger(ConversionProcess.class.getName());
  private static final long DEFAULT_MAX_LOG_BYTES = 1024L * 1024;
  private static final int MAX_SIGNAL_OUTPUT_BYTES = 4096;
  private static final int MAX_WORK_ENTRIES = 4096;
  private static final int LINUX_FILE_SIZE_LIMIT_EXIT = 128 + 25; // SIGXFSZ
  private static final String DEFAULT_SETSID = "/usr/bin/setsid";
  private static final Pattern ADDRESS_SPACE_LIMIT = Pattern.compile(
      "^Max address space\\s+(\\d+)\\s+(\\d+)\\s+bytes\\s*$");
  private static final Pattern GROUP_ALREADY_GONE = Pattern.compile(
      "(?s).*:\\s*No such process\\s*");
  private static final String GROUP_HANDSHAKE =
      "printf '%s\n' \"$$\" > \"$1\"; "
          + "n=0; while [ ! -e \"$2\" ] && [ \"$n\" -lt 100 ]; do "
          + "sleep 0.05; n=$((n+1)); done; "
          + "[ -e \"$2\" ] || exit 124; shift 2; exec \"$@\"";

  private ConversionProcess() {}

  public static void run(List<String> command, Path log, Duration timeout) throws IOException, InterruptedException {
    run(command, log, timeout, DEFAULT_MAX_LOG_BYTES);
  }

  /** Use only on Linux: the group and address-space limit are both verified before the command starts. */
  public static void runLimited(List<String> command, Path log, Duration timeout,
      String limiterExecutable, long maxAddressSpaceBytes) throws IOException, InterruptedException {
    runLimited(command, log, timeout, limiterExecutable, maxAddressSpaceBytes, null, 0, 0);
  }

  /** Bounds files written by a renderer as well as its address space and running time. */
  public static void runLimited(List<String> command, Path log, Duration timeout,
      String limiterExecutable, long maxAddressSpaceBytes,
      Path workDirectory, long maxWorkBytes, long maxFileBytes) throws IOException, InterruptedException {
    if (limiterExecutable == null || limiterExecutable.isBlank() || maxAddressSpaceBytes < 1)
      throw new IllegalArgumentException("positive address-space limit and limiter required");
    OutputBudget budget = null;
    if (workDirectory != null) {
      if (maxWorkBytes < 1 || maxFileBytes < 1)
        throw new IllegalArgumentException("positive work directory and file limits required");
      budget = new OutputBudget(workDirectory, maxWorkBytes, maxFileBytes);
      budget.verify();
    } else if (maxWorkBytes != 0 || maxFileBytes != 0) {
      throw new IllegalArgumentException("work directory required for file limits");
    }
    if (!Files.isExecutable(Path.of(limiterExecutable)) || !Files.isReadable(Path.of("/proc/self/stat"))
        || !Files.isExecutable(Path.of("/bin/sh"))
        || !Files.isExecutable(Path.of("/bin/kill"))
        || !Files.isExecutable(Path.of(System.getProperty("thesis.setsid", DEFAULT_SETSID))))
      throw new IOException("isolated Linux converter process group is unavailable");
    var limited = new ArrayList<String>(command.size() + 5);
    limited.add(limiterExecutable);
    limited.add("--as=" + maxAddressSpaceBytes);
    if (budget != null) {
      // RLIMIT_FSIZE is an OS-enforced per-file backstop between directory scans.
      limited.add("--fsize=" + maxFileBytes);
      limited.add("--core=0");
    }
    limited.add("--");
    limited.addAll(command);
    runIsolated(limited, log, timeout, DEFAULT_MAX_LOG_BYTES, budget);
  }

  /** A limiter path is not proof: verify actual child soft and hard limits under the isolated group. */
  public static boolean provesAddressSpaceLimit(String limiterExecutable, long maxAddressSpaceBytes, Path log)
      throws InterruptedException {
    if (limiterExecutable == null || limiterExecutable.isBlank() || maxAddressSpaceBytes < 1)
      return false;
    try {
      if (!Files.isExecutable(Path.of("/bin/cat")) || !Files.isReadable(Path.of("/proc/self/limits")))
        return false;
      runLimited(List.of("/bin/cat", "/proc/self/limits"), log, Duration.ofSeconds(5),
          limiterExecutable, maxAddressSpaceBytes);
      String expected = Long.toString(maxAddressSpaceBytes);
      return Files.readString(log).lines().map(ADDRESS_SPACE_LIMIT::matcher)
          .filter(Matcher::matches)
          .anyMatch(match -> expected.equals(match.group(1)) && expected.equals(match.group(2)));
    } catch (IOException | IllegalArgumentException error) {
      return false;
    }
  }

  /** Unisolated helper for local commands; production Office must use runLimited. */
  public static void run(List<String> command, Path log, Duration timeout, long maxLogBytes)
      throws IOException, InterruptedException {
    validate(timeout, maxLogBytes);
    long deadline = deadline(timeout);
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    execute(process, log, deadline, maxLogBytes, -1, null);
  }

  private static void runIsolated(List<String> command, Path log, Duration timeout,
      long maxLogBytes, OutputBudget budget)
      throws IOException, InterruptedException {
    validate(timeout, maxLogBytes);
    long deadline = deadline(timeout);
    Path pidFile = Files.createTempFile(log.getParent(), "conversion-group-", ".pid");
    Path releaseFile = pidFile.resolveSibling(pidFile.getFileName() + ".go");
    var isolated = new ArrayList<String>(command.size() + 10);
    isolated.add(System.getProperty("thesis.setsid", DEFAULT_SETSID));
    isolated.add("--fork");
    isolated.add("--wait");
    isolated.add("--");
    isolated.add("/bin/sh");
    isolated.add("-c");
    isolated.add(GROUP_HANDSHAKE);
    isolated.add("conversion-group");
    isolated.add(pidFile.toString());
    isolated.add(releaseFile.toString());
    isolated.addAll(command);
    Process process = null;
    long groupPid = -1;
    boolean startedCommand = false;
    Throwable primaryFailure = null;
    try {
      ProcessBuilder launcher = new ProcessBuilder(isolated).redirectErrorStream(true);
      if (budget != null) {
        // LibreOffice may use OS temporary paths outside its profile/outdir.
        // Keep every converter-created temporary file within the monitored budget.
        String tempDirectory = budget.directory().toAbsolutePath().normalize().toString();
        launcher.environment().put("TMPDIR", tempDirectory);
        launcher.environment().put("TMP", tempDirectory);
        launcher.environment().put("TEMP", tempDirectory);
      }
      process = launcher.start();
      groupPid = awaitVerifiedGroup(process, pidFile, deadline);
      Files.writeString(releaseFile, "go", StandardOpenOption.CREATE_NEW);
      startedCommand = true;
      execute(process, log, deadline, maxLogBytes, groupPid, budget);
    } catch (IOException | InterruptedException | RuntimeException | Error error) {
      primaryFailure = error;
      throw error;
    } finally {
      IOException cleanupFailure = null;
      if (groupPid > 0 && !startedCommand) {
        try { killGroup(groupPid); }
        catch (IOException error) { cleanupFailure = error; }
      }
      if (process != null && groupPid < 0) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
      }
      try { Files.deleteIfExists(releaseFile); }
      catch (IOException error) {
        if (cleanupFailure == null) cleanupFailure = error;
        else cleanupFailure.addSuppressed(error);
      }
      try { Files.deleteIfExists(pidFile); }
      catch (IOException error) {
        if (cleanupFailure == null) cleanupFailure = error;
        else cleanupFailure.addSuppressed(error);
      }
      if (cleanupFailure != null) {
        if (primaryFailure != null) primaryFailure.addSuppressed(cleanupFailure);
        else throw cleanupFailure;
      }
    }
  }

  private static long awaitVerifiedGroup(Process wrapper, Path pidFile, long deadline)
      throws IOException, InterruptedException {
    while (remainingMillis(deadline) > 0) {
      String contents = Files.readString(pidFile);
      if (contents.endsWith("\n")) {
        long pid;
        try { pid = Long.parseLong(contents.trim()); }
        catch (NumberFormatException error) { throw new IOException("invalid converter group handshake", error); }
        Path stat = Path.of("/proc", Long.toString(pid), "stat");
        if (Files.isRegularFile(stat)) {
          String data = Files.readString(stat);
          int end = data.lastIndexOf(')');
          if (end < 0) throw new IOException("invalid converter group process evidence");
          String[] fields = data.substring(end + 2).split("\\s+");
          if (fields.length < 4) throw new IOException("invalid converter group process evidence");
          if (Long.toString(pid).equals(fields[2]) && Long.toString(pid).equals(fields[3]))
            return pid;
          throw new IOException("converter did not start in its own process group");
        }
      }
      if (!wrapper.isAlive()) throw new IOException("converter group launcher exited before verification");
      Thread.sleep(Math.min(10, remainingMillis(deadline)));
    }
    throw new IOException("LibreOffice conversion timed out before process group verification");
  }

  private static void execute(Process process, Path log, long deadline, long maxLogBytes,
      long groupPid, OutputBudget budget)
      throws IOException, InterruptedException {
    execute(process, log, deadline, maxLogBytes,
        groupPid > 0 ? groupSignalCommand(groupPid) : null, budget);
  }

  static void execute(Process process, Path log, long deadline, long maxLogBytes,
      List<String> groupSignalCommand) throws IOException, InterruptedException {
    execute(process, log, deadline, maxLogBytes, groupSignalCommand, null);
  }

  private static void execute(Process process, Path log, long deadline, long maxLogBytes,
      List<String> groupSignalCommand, OutputBudget budget) throws IOException, InterruptedException {
    AtomicReference<IOException> outputFailure = new AtomicReference<>();
    AtomicBoolean outputExceeded = new AtomicBoolean();
    Thread drain = null;
    Throwable primaryFailure = null;
    try (var target = Files.newOutputStream(log)) {
      // The drain owns the input stream. Closing it on the caller thread can block while
      // an orphaned descendant still owns the inherited stdout pipe.
      drain = Thread.ofVirtual().start(() -> {
        try (var source = process.getInputStream()) {
          byte[] buffer = new byte[8192];
          long written = 0;
          int count;
          while ((count = source.read(buffer)) != -1) {
            int permitted = (int) Math.min(count, Math.max(0, maxLogBytes - written));
            if (permitted > 0) {
              target.write(buffer, 0, permitted);
              written += permitted;
            }
            if (permitted < count) outputExceeded.set(true);
          }
        } catch (IOException error) { outputFailure.set(error); }
      });
      boolean fileAtLimit = false;
      if (budget == null) {
        if (!process.waitFor(remainingMillis(deadline), TimeUnit.MILLISECONDS))
          throw new IOException("LibreOffice conversion timed out");
      } else {
        // Check while the child is writing; a post-exit size check cannot protect the disk.
        while (!process.waitFor(Math.min(50, remainingMillis(deadline)), TimeUnit.MILLISECONDS)) {
          budget.verify();
          if (remainingMillis(deadline) == 0)
            throw new IOException("LibreOffice conversion timed out");
        }
        fileAtLimit = budget.verify();
      }
      if (process.exitValue() != 0 && budget != null
          && (process.exitValue() == LINUX_FILE_SIZE_LIMIT_EXIT || fileAtLimit))
        throw new OutputLimitExceededException("render work file exceeded configured limit");
      if (process.exitValue() != 0)
        throw new IOException("LibreOffice conversion failed with exit code " + process.exitValue());
      if (!drain.join(Duration.ofMillis(remainingMillis(deadline))))
        throw new IOException("LibreOffice conversion timed out while draining output");
      if (outputFailure.get() != null) throw outputFailure.get();
      if (outputExceeded.get()) throw new IOException("LibreOffice output exceeded configured limit");
    } catch (IOException | InterruptedException | RuntimeException | Error error) {
      primaryFailure = error;
      throw error;
    } finally {
      try { cleanup(process, drain, groupSignalCommand); }
      catch (IOException error) {
        if (primaryFailure != null) primaryFailure.addSuppressed(error);
        else throw error;
      }
    }
  }

  private static void cleanup(Process process, Thread drain, List<String> groupSignalCommand)
      throws IOException {
    boolean interrupted = Thread.interrupted();
    IOException signalFailure = null;
    try {
      if (groupSignalCommand != null) {
        try { killGroup(groupSignalCommand); }
        catch (IOException error) { signalFailure = error; }
      }
      if (process.isAlive()) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
      }
      try { process.waitFor(500, TimeUnit.MILLISECONDS); }
      catch (InterruptedException error) { interrupted = true; process.destroyForcibly(); }
      if (drain != null) {
        try { drain.join(Duration.ofMillis(500)); }
        catch (InterruptedException error) { interrupted = true; }
      }
      try { process.getOutputStream().close(); }
      catch (IOException error) {
        LOG.log(System.Logger.Level.DEBUG, "Unable to close converter stdin", error);
      }
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
    if (signalFailure != null) throw signalFailure;
  }

  private static void killGroup(long groupPid) throws IOException {
    // Group ID comes only from the verified setsid handshake, never a parent PID guess.
    killGroup(groupSignalCommand(groupPid));
  }

  private static List<String> groupSignalCommand(long groupPid) {
    return List.of("/bin/kill", "-KILL", "--", "-" + groupPid);
  }

  private static void killGroup(List<String> command) throws IOException {
    boolean interrupted = Thread.interrupted();
    Process killer = null;
    Thread drain = null;
    var output = new ByteArrayOutputStream();
    AtomicBoolean outputTruncated = new AtomicBoolean();
    AtomicReference<IOException> outputFailure = new AtomicReference<>();
    try {
      ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
      builder.environment().put("LC_ALL", "C");
      killer = builder.start();
      Process signalProcess = killer;
      drain = Thread.ofVirtual().start(() -> {
        try (var source = signalProcess.getInputStream()) {
          byte[] bytes = new byte[1024];
          int count;
          while ((count = source.read(bytes)) != -1) {
            int permitted = Math.min(count, MAX_SIGNAL_OUTPUT_BYTES - output.size());
            if (permitted > 0) output.write(bytes, 0, permitted);
            if (permitted < count) outputTruncated.set(true);
          }
        } catch (IOException error) { outputFailure.set(error); }
      });
      try {
        if (!killer.waitFor(1, TimeUnit.SECONDS))
          throw new IOException("group signal timed out");
        if (!drain.join(Duration.ofMillis(500)))
          throw new IOException("group signal output timed out");
      } catch (InterruptedException error) {
        interrupted = true;
        throw new IOException("group signal interrupted", error);
      }
      if (outputFailure.get() != null) throw outputFailure.get();
      if (killer.exitValue() == 0) return;
      String message = output.toString(StandardCharsets.UTF_8).strip();
      if (!outputTruncated.get() && GROUP_ALREADY_GONE.matcher(message).matches()) return;
      throw new IOException("group signal failed with exit code " + killer.exitValue()
          + (message.isEmpty() ? "" : ": " + message)
          + (outputTruncated.get() ? " [output truncated]" : ""));
    } finally {
      if (killer != null && killer.isAlive()) killer.destroyForcibly();
      if (drain != null && drain.isAlive()) drain.interrupt();
      if (interrupted) Thread.currentThread().interrupt();
    }
  }

  private static void validate(Duration timeout, long maxLogBytes) {
    if (timeout == null || timeout.isNegative() || timeout.isZero())
      throw new IllegalArgumentException("positive timeout required");
    if (maxLogBytes < 1) throw new IllegalArgumentException("positive log limit required");
  }

  private static long deadline(Duration timeout) { return System.nanoTime() + timeout.toNanos(); }

  private static long remainingMillis(long deadline) {
    long nanos = deadline - System.nanoTime();
    return nanos <= 0 ? 0 : Math.max(1, TimeUnit.NANOSECONDS.toMillis(nanos));
  }

  public static final class OutputLimitExceededException extends IOException {
    private OutputLimitExceededException(String message) { super(message); }
  }

  private record OutputBudget(Path directory, long maxBytes, long maxFileBytes) {
    boolean verify() throws IOException {
      if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
        throw new IOException("render work directory is unavailable");
      class SizeVisitor extends SimpleFileVisitor<Path> {
        long total;
        int entries;
        boolean fileAtLimit;

        private void countEntry() throws IOException {
          if (++entries > MAX_WORK_ENTRIES)
            throw new OutputLimitExceededException("render work directory exceeded entry limit");
        }

        @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes)
            throws IOException {
          countEntry();
          return FileVisitResult.CONTINUE;
        }

        @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attributes)
            throws IOException {
          countEntry();
          if (!attributes.isRegularFile())
            throw new IOException("render work directory contains an unsupported file type");
          if (attributes.size() > maxBytes - total)
            throw new OutputLimitExceededException("render work directory exceeded configured limit");
          if (attributes.size() >= maxFileBytes) fileAtLimit = true;
          total += attributes.size();
          return FileVisitResult.CONTINUE;
        }
      }
      var visitor = new SizeVisitor();
      Files.walkFileTree(directory, visitor);
      return visitor.fileAtLimit;
    }
  }
}
