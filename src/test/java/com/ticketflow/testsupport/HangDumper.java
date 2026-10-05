package com.ticketflow.testsupport;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Writes the evidence needed to find the root cause of a hung or timed-out test (F-033, DP-039): every thread of the
 * JVM with its FULL stack and lock information (the event-loop threads of both the server and the client of the test
 * are in the same JVM), the JVM-detected deadlocks, and the state of every TCP socket of the host with its receive
 * and send queues (a request that was sent but never read shows up as bytes stuck in a queue, or a connection stuck
 * in CLOSE_WAIT or FIN_WAIT). It never throws: a failing dump must not turn a test failure into something else.
 */
public final class HangDumper {

    private static final int MAX_SOCKET_LINES = 600;

    private HangDumper() {}

    /** Appends one section to {@code <dir>/<file name derived from testId>.txt} and returns the file. */
    public static Path dump(Path dir, String testId, String reason) {
        Path file = dir.resolve(fileName(testId));
        try {
            Files.createDirectories(dir);
            String text = "===== " + Instant.now() + " | " + reason + " | " + testId + " =====\n"
                    + "pid=" + ProcessHandle.current().pid() + " java=" + Runtime.version() + "\n\n"
                    + "--- Threads (full stacks) ---\n" + threadDump() + "\n"
                    + "--- Deadlocks ---\n" + deadlocks() + "\n"
                    + "--- TCP sockets of the host (state, Recv-Q, Send-Q, local, peer) ---\n" + socketState() + "\n";
            Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            System.err.println("[hang-dump] could not write " + file + ": " + e);
        }
        return file;
    }

    /** A file-system-safe name for a JUnit unique id or display name. */
    static String fileName(String testId) {
        String safe = testId.replaceAll("[^A-Za-z0-9._-]+", "_");
        return (safe.length() > 150 ? safe.substring(safe.length() - 150) : safe) + ".txt";
    }

    /** All threads, full stack depth (ThreadInfo.toString cuts it at 8 frames), with held and awaited locks. */
    static String threadDump() {
        StringBuilder out = new StringBuilder();
        for (ThreadInfo info : ManagementFactory.getThreadMXBean().dumpAllThreads(true, true)) {
            out.append('"').append(info.getThreadName()).append("\" #").append(info.getThreadId())
                    .append(info.isDaemon() ? " daemon" : "").append(" state=").append(info.getThreadState());
            if (info.getLockName() != null) {
                out.append(" waiting on ").append(info.getLockName());
                if (info.getLockOwnerName() != null) {
                    out.append(" held by \"").append(info.getLockOwnerName()).append('"');
                }
            }
            out.append('\n');
            for (StackTraceElement frame : info.getStackTrace()) {
                out.append("    at ").append(frame).append('\n');
            }
            for (var monitor : info.getLockedMonitors()) {
                out.append("    locked ").append(monitor).append('\n');
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static String deadlocks() {
        long[] ids = ManagementFactory.getThreadMXBean().findDeadlockedThreads();
        if (ids == null) {
            return "none detected\n";
        }
        StringBuilder out = new StringBuilder();
        for (ThreadInfo info : ManagementFactory.getThreadMXBean().getThreadInfo(ids)) {
            out.append(info.getThreadName()).append(" blocked on ").append(info.getLockName()).append('\n');
        }
        return out.toString();
    }

    /** {@code ss -tan} (Linux, the CI runner) or {@code netstat -an -p tcp} (macOS); the output is cut at a sane size. */
    static String socketState() {
        for (List<String> command : List.of(List.of("ss", "-tan"), List.of("netstat", "-an", "-p", "tcp"))) {
            try {
                Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
                var reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream(),
                        StandardCharsets.UTF_8));
                List<String> lines = new ArrayList<>();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (lines.size() < MAX_SOCKET_LINES) {
                        lines.add(line);
                    }
                }
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
                if (process.exitValue() == 0 && !lines.isEmpty()) {
                    return "$ " + String.join(" ", command) + "\n" + String.join("\n", lines) + "\n";
                }
            } catch (IOException e) {
                // that tool is not installed: try the next one
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "interrupted while reading socket state\n";
            }
        }
        return "no socket tool available (ss, netstat)\n";
    }
}
