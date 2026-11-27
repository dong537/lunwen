package cn.luwneshacha.thesis.conversion;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

class ConversionProcessTest {
  @TempDir Path temp;

  @Test
  void capturesOutputWithoutPipeDeadlock() throws Exception {
    Path log = temp.resolve("conversion.log");
    ConversionProcess.run(command("output"), log, Duration.ofSeconds(15));
    assertTrue(Files.size(log) > 64 * 1024);
  }

  @Test
  void unsuccessfulGroupSignalFailsAnOtherwiseSuccessfulConversion() throws Exception {
    Process converter = new ProcessBuilder(command("output")).redirectErrorStream(true).start();

    var error = assertThrows(java.io.IOException.class, () -> ConversionProcess.execute(
        converter, temp.resolve("signal-failure.log"), deadline(), 1024 * 1024,
        command("signal-fail")));

    assertTrue(error.getMessage().contains("group signal failed"), error.getMessage());
    assertTrue(error.getMessage().length() < 5000, "signal stderr must remain bounded");
  }

  @Test
  void failedGroupSignalDoesNotReplaceTheConverterFailure() throws Exception {
    Process converter = new ProcessBuilder(command("exit-failure")).redirectErrorStream(true).start();

    var error = assertThrows(java.io.IOException.class, () -> ConversionProcess.execute(
        converter, temp.resolve("converter-and-signal-failure.log"), deadline(),
        1024 * 1024, command("signal-fail")));

    assertTrue(error.getMessage().contains("exit code 9"), error.getMessage());
    assertEquals(1, error.getSuppressed().length);
    assertTrue(error.getSuppressed()[0].getMessage().contains("group signal failed"));
  }

  @Test
  void missingGroupAfterConversionIsACompletedCleanup() throws Exception {
    Process converter = new ProcessBuilder(command("output")).redirectErrorStream(true).start();

    ConversionProcess.execute(converter, temp.resolve("already-gone.log"), deadline(),
        1024 * 1024, command("signal-missing"));
  }

  @Test
  void timedOutGroupSignalFailsWithinItsBound() throws Exception {
    Process converter = new ProcessBuilder(command("output")).redirectErrorStream(true).start();
    long start = System.nanoTime();

    var error = assertThrows(java.io.IOException.class, () -> ConversionProcess.execute(
        converter, temp.resolve("signal-timeout.log"), deadline(), 1024 * 1024,
        command("signal-hang")));

    assertTrue(error.getMessage().contains("group signal timed out"), error.getMessage());
    assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(5)) < 0);
  }

  @Test
  void successfulGroupSignalKeepsSuccessfulConversion() throws Exception {
    Process converter = new ProcessBuilder(command("output")).redirectErrorStream(true).start();

    ConversionProcess.execute(converter, temp.resolve("signal-success.log"), deadline(),
        1024 * 1024, command("signal-success"));
  }

  private static long deadline() {
    return System.nanoTime() + Duration.ofSeconds(10).toNanos();
  }

  @Test
  void capsConversionLogWithoutBlockingTheChildPipe() throws Exception {
    Path log = temp.resolve("bounded.log");
    var error = assertThrows(java.io.IOException.class,
        () -> ConversionProcess.run(command("output"), log, Duration.ofSeconds(15), 1024));
    assertTrue(error.getMessage().contains("output exceeded"));
    assertTrue(Files.size(log) <= 1024);
  }

  @Test
  void linuxAddressSpaceLimitIsVisibleInsideTheChildProcess() throws Exception {
    Assumptions.assumeTrue(Files.isExecutable(Path.of("/usr/bin/prlimit"))
        && Files.isReadable(Path.of("/proc/self/limits")));
    Path log = temp.resolve("limits.log");
    ConversionProcess.runLimited(List.of("/bin/cat", "/proc/self/limits"), log,
        Duration.ofSeconds(5), "/usr/bin/prlimit", 1_073_741_824L);
    assertTrue(Files.readString(log).lines().anyMatch(line -> line.matches(
        "Max address space\\s+1073741824\\s+1073741824\\s+bytes\\s*")));
  }

  @Test
  void missingLimiterDoesNotCountAsProven() throws Exception {
    assertFalse(ConversionProcess.provesAddressSpaceLimit(temp.resolve("missing-prlimit").toString(),
        1_073_741_824L, temp.resolve("not-verified.log")));
  }

  @Test
  void linuxLimitPreflightProvesBothSoftAndHardValues() throws Exception {
    Assumptions.assumeTrue(Files.isExecutable(Path.of("/usr/bin/prlimit"))
        && Files.isReadable(Path.of("/proc/self/limits")));
    assertTrue(ConversionProcess.provesAddressSpaceLimit("/usr/bin/prlimit", 1_073_741_824L,
        temp.resolve("verified.log")));
  }

  @Test
  void linuxStopsConverterWhenItsWorkDirectoryExceedsBudget() throws Exception {
    Assumptions.assumeTrue(Files.isExecutable(Path.of("/usr/bin/prlimit"))
        && Files.isExecutable(Path.of("/usr/bin/setsid")));
    Path output = Files.createDirectories(temp.resolve("growing-work"));
    Path file = output.resolve("large.bin");
    long start = System.nanoTime();

    var error = assertThrows(java.io.IOException.class, () -> ConversionProcess.runLimited(
        List.of("/bin/sh", "-c",
            "dd if=/dev/zero of=\"$1\" bs=65536 count=512 2>/dev/null; sleep 20",
            "writer", file.toString()), temp.resolve("growing-work.log"),
        Duration.ofSeconds(5), "/usr/bin/prlimit", 1_073_741_824L,
        output, 1_048_576L, 2_097_152L));

    assertTrue(error.getMessage().contains("work directory exceeded"), error.getMessage());
    assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(4)) < 0);
    assertTrue(Files.size(file) <= 2_097_152L, "the OS file-size cap must also apply");
  }

  @Test
  void linuxFileSizeLimitStopsOversizedSingleOutput() throws Exception {
    Assumptions.assumeTrue(Files.isExecutable(Path.of("/usr/bin/prlimit"))
        && Files.isExecutable(Path.of("/usr/bin/setsid")));
    Path output = Files.createDirectories(temp.resolve("single-file"));
    Path file = output.resolve("large.bin");

    assertThrows(ConversionProcess.OutputLimitExceededException.class, () -> ConversionProcess.runLimited(
        List.of("/bin/sh", "-c", "dd if=/dev/zero of=\"$1\" bs=65536 count=512",
            "writer", file.toString()), temp.resolve("single-file.log"),
        Duration.ofSeconds(5), "/usr/bin/prlimit", 1_073_741_824L,
        output, 8_388_608L, 1_048_576L));
    assertTrue(Files.size(file) <= 1_048_576L);
  }

  @Test
  void linuxAllowsOutputWithinBothDiskBudgets() throws Exception {
    Assumptions.assumeTrue(Files.isExecutable(Path.of("/usr/bin/prlimit"))
        && Files.isExecutable(Path.of("/usr/bin/setsid")));
    Path output = Files.createDirectories(temp.resolve("small-output"));
    Path file = output.resolve("small.bin");

    ConversionProcess.runLimited(List.of("/bin/sh", "-c",
        "dd if=/dev/zero of=\"$1\" bs=65536 count=1 2>/dev/null",
        "writer", file.toString()), temp.resolve("small-output.log"),
        Duration.ofSeconds(5), "/usr/bin/prlimit", 1_073_741_824L,
        output, 1_048_576L, 1_048_576L);
    assertEquals(65_536L, Files.size(file));
  }

  @Test
  void linuxPlacesConverterTemporaryFilesInsideTheMonitoredDirectory() throws Exception {
    Assumptions.assumeTrue(Files.isExecutable(Path.of("/usr/bin/prlimit"))
        && Files.isExecutable(Path.of("/usr/bin/setsid")));
    Path output = Files.createDirectories(temp.resolve("monitored-tmp"));
    Path log = temp.resolve("temporary-environment.log");

    ConversionProcess.runLimited(List.of("/bin/sh", "-c",
        "printf '%s\n' \"$TMPDIR\" \"$TMP\" \"$TEMP\"; mktemp \"$TMPDIR/renderer.XXXXXX\""),
        log, Duration.ofSeconds(5), "/usr/bin/prlimit", 1_073_741_824L,
        output, 1_048_576L, 1_048_576L);

    var lines = Files.readAllLines(log);
    String expected = output.toAbsolutePath().normalize().toString();
    assertEquals(List.of(expected, expected, expected), lines.subList(0, 3));
    assertTrue(Path.of(lines.get(3)).startsWith(output));
    assertTrue(Files.isRegularFile(Path.of(lines.get(3))));
  }

  @Test
  void terminatesHungConversionWithinItsDeadline() {
    long start = System.nanoTime();
    var error = assertThrows(java.io.IOException.class,
        () -> ConversionProcess.run(command("hang"), temp.resolve("hung.log"), Duration.ofMillis(500)));
    assertTrue(error.getMessage().contains("timed out"));
    assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(10)) < 0);
  }

  @Test
  void linuxKillsOrphanedDescendantHoldingOutputPipeWithinDeadline() throws Exception {
    Assumptions.assumeTrue(Files.isExecutable(Path.of("/usr/bin/prlimit"))
        && Files.isExecutable(Path.of("/usr/bin/setsid"))
        && Files.isReadable(Path.of("/proc/self/stat")));
    Path childPidFile = temp.resolve("orphan.pid");
    long start = System.nanoTime();
    try {
      var error = assertThrows(java.io.IOException.class,
          () -> ConversionProcess.runLimited(List.of("/bin/sh", "-c",
              "sleep 20 & echo $! > $1", "child", childPidFile.toString()),
              temp.resolve("orphan.log"), Duration.ofSeconds(2), "/usr/bin/prlimit", 1_073_741_824L));
      assertTrue(error.getMessage().contains("timed out"), error.getMessage());
      assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(8)) < 0,
          "group cleanup must not wait for the descendant's 20-second sleep");
      long childPid = Long.parseLong(Files.readString(childPidFile).trim());
      assertFalse(isRunningLinuxProcess(childPid), "orphaned converter descendant must be terminated");
    } finally {
      if (Files.isRegularFile(childPidFile))
        ProcessHandle.of(Long.parseLong(Files.readString(childPidFile).trim()))
            .ifPresent(ProcessHandle::destroyForcibly);
    }
  }

  @Test
  void linuxTerminatesRunningConverterGroupOnTimeout() throws Exception {
    Assumptions.assumeTrue(Files.isExecutable(Path.of("/usr/bin/prlimit"))
        && Files.isExecutable(Path.of("/usr/bin/setsid"))
        && Files.isReadable(Path.of("/proc/self/stat")));
    Path processPidFile = temp.resolve("running.pid");
    try {
      var error = assertThrows(java.io.IOException.class,
          () -> ConversionProcess.runLimited(List.of("/bin/sh", "-c",
              "echo $$ > \"$1\"; exec sleep 20", "child", processPidFile.toString()),
              temp.resolve("running.log"), Duration.ofSeconds(2), "/usr/bin/prlimit", 1_073_741_824L));
      assertTrue(error.getMessage().contains("timed out"), error.getMessage());
      long pid = Long.parseLong(Files.readString(processPidFile).trim());
      assertFalse(isRunningLinuxProcess(pid), "the still-running converter must be terminated");
    } finally {
      if (Files.isRegularFile(processPidFile))
        ProcessHandle.of(Long.parseLong(Files.readString(processPidFile).trim()))
            .ifPresent(ProcessHandle::destroyForcibly);
    }
  }

  @Test
  void linuxCleansBackgroundDescendantAfterSuccessfulParentExit() throws Exception {
    Assumptions.assumeTrue(Files.isExecutable(Path.of("/usr/bin/prlimit"))
        && Files.isExecutable(Path.of("/usr/bin/setsid"))
        && Files.isReadable(Path.of("/proc/self/stat")));
    Path childPidFile = temp.resolve("successful-parent.pid");
    long start = System.nanoTime();
    try {
      ConversionProcess.runLimited(List.of("/bin/sh", "-c",
          "sleep 20 > /dev/null 2>&1 & echo $! > \"$1\"", "child", childPidFile.toString()),
          temp.resolve("successful-parent.log"), Duration.ofSeconds(5),
          "/usr/bin/prlimit", 1_073_741_824L);
      assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(8)) < 0);
      long childPid = Long.parseLong(Files.readString(childPidFile).trim());
      assertFalse(isRunningLinuxProcess(childPid),
          "a converter must not leave a detached-output descendant after success");
    } finally {
      if (Files.isRegularFile(childPidFile))
        ProcessHandle.of(Long.parseLong(Files.readString(childPidFile).trim()))
            .ifPresent(ProcessHandle::destroyForcibly);
    }
  }

  @Test
  void unisolatedRunnerReturnsOnDeadlineEvenWhenOrphanHoldsOutputPipe() throws Exception {
    Path childPidFile = temp.resolve("raw-orphan.pid");
    long start = System.nanoTime();
    try {
      var error = assertThrows(java.io.IOException.class,
          () -> ConversionProcess.run(command("spawn", childPidFile.toString()),
              temp.resolve("raw-orphan.log"), Duration.ofSeconds(2)));
      assertTrue(error.getMessage().contains("timed out"), error.getMessage());
      assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(8)) < 0,
          "closing a pipe owned by an orphan must not block the caller");
    } finally {
      if (Files.isRegularFile(childPidFile))
        ProcessHandle.of(Long.parseLong(Files.readString(childPidFile).trim()))
            .ifPresent(ProcessHandle::destroyForcibly);
    }
  }

  @Test
  void exitedProcessIsStoppedWhenItsStatDisappearsDuringRead() throws Exception {
    Process child = new ProcessBuilder(command("signal-success")).start();
    try {
      assertTrue(child.waitFor(5, java.util.concurrent.TimeUnit.SECONDS));
      assertFalse(isRunningLinuxProcess(child.pid(), path -> {
        throw new IOException("No such process");
      }));
    } finally {
      if (child.isAlive()) child.destroyForcibly();
    }
  }

  @Test
  void liveProcessDoesNotHideStatReadErrors() {
    for (IOException failure : List.of(
        new AccessDeniedException("stat"), new IOException("unrelated read failure"))) {
      IOException actual = assertThrows(IOException.class, () ->
          isRunningLinuxProcess(ProcessHandle.current().pid(), path -> { throw failure; }));
      assertSame(failure, actual);
    }
  }

  @Test
  void runningAndZombieProcessStatesStayDistinct() throws Exception {
    long pid = ProcessHandle.current().pid();
    for (String state : List.of("R", "S")) {
      assertTrue(isRunningLinuxProcess(pid, path -> pid + " (converter child) " + state + " 1 2"));
    }
    assertFalse(isRunningLinuxProcess(pid, path -> pid + " (converter child) Z 1 2"));
  }

  private static boolean isRunningLinuxProcess(long pid) throws IOException {
    return isRunningLinuxProcess(pid, Files::readString);
  }

  private static boolean isRunningLinuxProcess(long pid, ProcessStatReader reader) throws IOException {
    Path stat = Path.of("/proc", Long.toString(pid), "stat");
    try {
      String value = reader.read(stat);
      return value.charAt(value.lastIndexOf(')') + 2) != 'Z';
    } catch (IOException failure) {
      // A killed process can disappear after /proc/<pid>/stat is opened but before read completes.
      if (ProcessHandle.of(pid).filter(ProcessHandle::isAlive).isEmpty()) return false;
      throw failure;
    }
  }

  @FunctionalInterface
  private interface ProcessStatReader {
    String read(Path path) throws IOException;
  }

  private List<String> command(String mode) {
    return command(mode, null);
  }

  private List<String> command(String mode, String argument) {
    String binary = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
    var result = new java.util.ArrayList<>(List.of(
        Path.of(System.getProperty("java.home"), "bin", binary).toString(), "-cp",
        System.getProperty("java.class.path"), Child.class.getName(), mode));
    if (argument != null) result.add(argument);
    return result;
  }

  public static class Child {
    public static void main(String[] args) throws Exception {
      if (args[0].equals("hang")) Thread.sleep(30_000);
      else if (args[0].equals("hold")) Thread.sleep(20_000);
      else if (args[0].equals("exit-failure")) System.exit(9);
      else if (args[0].equals("signal-fail")) {
        System.err.print("permission denied ".repeat(1000));
        System.exit(7);
      }
      else if (args[0].equals("signal-missing")) {
        System.err.println("kill: (-99999): No such process");
        System.exit(1);
      }
      else if (args[0].equals("signal-hang")) Thread.sleep(20_000);
      else if (args[0].equals("signal-success")) return;
      else if (args[0].equals("spawn")) {
        String binary = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        Process child = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", binary).toString(), "-cp",
            System.getProperty("java.class.path"), Child.class.getName(), "hold")
            .inheritIO().start();
        Files.writeString(Path.of(args[1]), Long.toString(child.pid()));
      }
      else System.out.print("conversion-output".repeat(10_000));
    }
  }
}
