package com.ticketflow.testsupport;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;

/**
 * Turns "a test hung for 30 s and then failed with a bare timeout" into a root cause (F-033, DP-039). For every test
 * tagged {@code integration} (so also the end-to-end ones) it arms a watchdog: if the test is still running after the
 * threshold (20 s by default) it dumps all threads and the TCP socket state WHILE the test is stuck, which is the only
 * moment that evidence exists; and if the test fails with a timeout it dumps once more. Files go to
 * {@code build/reports/hang-dumps/<test>.txt}, which CI uploads with the {@code reports} artifact, and a short notice
 * is printed. Registered automatically (JUnit extension auto-detection, see
 * {@code META-INF/services} and {@code junit-platform.properties}); a passing test that is merely slow also leaves a
 * dump, so a dump alone is not proof of a hang. Configure with {@code HANG_DUMP_THRESHOLD_SECONDS} and
 * {@code HANG_DUMP_DIR} (environment) or the system properties {@code ticketflow.hangdump.threshold-seconds} and
 * {@code ticketflow.hangdump.dir}.
 */
public final class HangDumpExtension implements BeforeEachCallback, AfterEachCallback, TestExecutionExceptionHandler {

    static final Duration DEFAULT_THRESHOLD = Duration.ofSeconds(20);
    static final String DEFAULT_DIRECTORY = "build/reports/hang-dumps";

    private static final ScheduledExecutorService WATCHDOGS = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "hang-dump-watchdog");
        thread.setDaemon(true);
        return thread;
    });

    private final Duration threshold;
    private final Path directory;
    private final Map<String, ScheduledFuture<?>> armed = new ConcurrentHashMap<>();

    /** Used by JUnit (auto-detection): reads the configuration from the environment. */
    public HangDumpExtension() {
        this(configuredThreshold(), Path.of(setting("ticketflow.hangdump.dir", "HANG_DUMP_DIR", DEFAULT_DIRECTORY)));
    }

    HangDumpExtension(Duration threshold, Path directory) {
        this.threshold = threshold;
        this.directory = directory;
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        if (!context.getTags().contains("integration")) {
            return;
        }
        String id = context.getUniqueId();
        String name = context.getDisplayName();
        armed.put(id, WATCHDOGS.schedule(() -> {
            Path file = HangDumper.dump(directory, id, "still running after " + threshold.toSeconds() + " s: " + name);
            System.err.println("[hang-dump] " + name + " is still running after " + threshold.toSeconds()
                    + " s; threads and sockets written to " + file);
        }, threshold.toMillis(), TimeUnit.MILLISECONDS));
    }

    @Override
    public void afterEach(ExtensionContext context) {
        ScheduledFuture<?> watchdog = armed.remove(context.getUniqueId());
        if (watchdog != null) {
            watchdog.cancel(false);
        }
    }

    @Override
    public void handleTestExecutionException(ExtensionContext context, Throwable failure) throws Throwable {
        if (context.getTags().contains("integration") && isTimeout(failure)) {
            Path file = HangDumper.dump(directory, context.getUniqueId(), "failed with a timeout: " + failure);
            System.err.println("[hang-dump] " + context.getDisplayName() + " failed with a timeout; threads and "
                    + "sockets written to " + file);
        }
        throw failure;
    }

    static boolean isTimeout(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (t instanceof TimeoutException || (message != null && message.contains("Timeout"))) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    private static Duration configuredThreshold() {
        String value = setting("ticketflow.hangdump.threshold-seconds", "HANG_DUMP_THRESHOLD_SECONDS", null);
        return value == null ? DEFAULT_THRESHOLD : Duration.ofSeconds(Long.parseLong(value.trim()));
    }

    private static String setting(String property, String environment, String fallback) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            value = System.getenv(environment);
        }
        return value == null || value.isBlank() ? fallback : value;
    }
}
