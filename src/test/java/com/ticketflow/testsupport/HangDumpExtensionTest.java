package com.ticketflow.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves the hang-dump instrumentation of F-033 / DP-039 without leaving a failing or slow test behind: the extension
 * is driven with a tiny threshold and a mocked JUnit context, a thread is deliberately stalled, and the dump file is
 * checked for the evidence a real hang would need (the stalled thread's stack, all threads, the sockets).
 */
class HangDumpExtensionTest {

    @TempDir
    Path directory;

    private static ExtensionContext context(String id, Set<String> tags) {
        ExtensionContext context = mock(ExtensionContext.class);
        when(context.getUniqueId()).thenReturn(id);
        when(context.getDisplayName()).thenReturn("stalled()");
        when(context.getTags()).thenReturn(tags);
        return context;
    }

    private static void stalledOnPurpose(CountDownLatch release) throws InterruptedException {
        release.await();
    }

    @Test
    void integrationTestStillRunningAfterTheThreshold_isDumpedWhileItIsStuck() throws Exception {
        var extension = new HangDumpExtension(Duration.ofMillis(150), directory);
        var context = context("[engine:junit-jupiter]/[class:com.x.SomeIT]/[method:stalled()]", Set.of("integration"));
        CountDownLatch release = new CountDownLatch(1);
        Thread stalled = new Thread(() -> {
            try {
                stalledOnPurpose(release);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "deliberately-stalled-test-thread");
        stalled.start();

        extension.beforeEach(context);
        Path expected = directory.resolve(HangDumper.fileName(context.getUniqueId()));
        await().atMost(TestTimeouts.WAIT).until(() -> Files.exists(expected) && Files.size(expected) > 0);
        extension.afterEach(context);
        release.countDown();
        stalled.join();

        String dump = Files.readString(expected);
        assertThat(dump).contains("still running after 0 s: stalled()")
                .contains("\"deliberately-stalled-test-thread\"")
                .contains("com.ticketflow.testsupport.HangDumpExtensionTest.stalledOnPurpose")
                .contains("--- Threads (full stacks) ---", "--- Deadlocks ---", "none detected")
                .contains("--- TCP sockets of the host");
        assertThat(dump).containsPattern("\"main\"|\"Test worker\"");
    }

    @Test
    void testThatFinishesBeforeTheThreshold_leavesNoDump() throws Exception {
        var extension = new HangDumpExtension(Duration.ofMillis(300), directory);
        var context = context("[method:quick()]", Set.of("integration"));

        extension.beforeEach(context);
        extension.afterEach(context);
        Thread.sleep(900); // three thresholds: a negative needs the time to pass

        try (var files = Files.list(directory)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void testWithoutTheIntegrationTag_isNeverWatched() throws Exception {
        var extension = new HangDumpExtension(Duration.ofMillis(100), directory);
        var context = context("[method:unit()]", Set.of());

        extension.beforeEach(context);
        Thread.sleep(500);
        extension.afterEach(context);

        try (var files = Files.list(directory)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void failureWithATimeout_isDumpedAndRethrownUnchanged() throws IOException {
        var extension = new HangDumpExtension(Duration.ofMinutes(5), directory);
        var context = context("[method:timedOut()]", Set.of("integration"));
        var failure = new IllegalStateException("Timeout on blocking read for 30000000000 NANOSECONDS",
                new TimeoutException());

        assertThatThrownBy(() -> extension.handleTestExecutionException(context, failure)).isSameAs(failure);

        String dump = Files.readString(directory.resolve(HangDumper.fileName("[method:timedOut()]")));
        assertThat(dump).contains("failed with a timeout: java.lang.IllegalStateException: Timeout on blocking read");
    }

    @Test
    void failureWithoutATimeout_isRethrownWithoutADump() throws IOException {
        var extension = new HangDumpExtension(Duration.ofMinutes(5), directory);
        var failure = new AssertionError("expected 1 but was 2");

        assertThatThrownBy(() -> extension.handleTestExecutionException(
                context("[method:wrong()]", Set.of("integration")), failure)).isSameAs(failure);

        try (var files = Files.list(directory)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void isTimeout_looksThroughTheCauseChain() {
        assertThat(HangDumpExtension.isTimeout(new RuntimeException("x", new TimeoutException("y")))).isTrue();
        assertThat(HangDumpExtension.isTimeout(new RuntimeException("Timeout on blocking read"))).isTrue();
        assertThat(HangDumpExtension.isTimeout(new RuntimeException("x", new IllegalStateException("z")))).isFalse();
    }

    @Test
    void fileName_isFileSystemSafeAndBounded() {
        String name = HangDumper.fileName("[engine:junit-jupiter]/[class:a.B]/[method:c(java.lang.String)]");
        assertThat(name).matches("[A-Za-z0-9._-]+\\.txt");
        assertThat(HangDumper.fileName("x".repeat(500))).hasSizeLessThanOrEqualTo(154);
    }

    @Test
    void theExtensionIsRegisteredForTheWholeSuite() throws IOException {
        assertThat(ServiceLoader.load(Extension.class).stream().map(provider -> provider.type().getName()).toList())
                .contains(HangDumpExtension.class.getName());
        try (var properties = getClass().getResourceAsStream("/junit-platform.properties")) {
            var loaded = new java.util.Properties();
            loaded.load(properties);
            assertThat(loaded).containsEntry("junit.jupiter.extensions.autodetection.enabled", "true");
        }
    }
}
