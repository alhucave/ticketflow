package com.ticketflow.infrastructure.web;

import com.ticketflow.testsupport.RawHttpConnection;
import com.ticketflow.testsupport.RawHttpConnection.ClosedException;
import com.ticketflow.testsupport.RawHttpConnection.NoResponseException;
import com.ticketflow.testsupport.RawHttpConnection.Response;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Wire-level probe of the early-rejection paths (F-033, DP-039): the server answers 401/405/413/415/429 from a
 * filter or handler WITHOUT consuming the request body, and the same TCP connection may then be reused. For each
 * scenario it sends the rejected request over a raw socket and immediately a second request ({@code GET} of an
 * unknown route, a trivial 404) on the SAME connection, and checks both answers: the right status, the correlation
 * id of the right request, within a short deadline. Anything else is recorded as an {@link Anomaly} with the exact
 * bytes. The scenarios are shared by the fast regression test and by the opt-in large experiment.
 */
final class EarlyRejectionProbe {

    /** One thing that went wrong in one iteration. */
    record Anomaly(String scenario, int iteration, String kind, String detail) {
        @Override
        public String toString() {
            return scenario + "#" + iteration + " " + kind + ": " + detail;
        }
    }

    /** Totals of a run of one scenario. */
    record Result(String scenario, int iterations, int announcedClose, int reconnects, List<Anomaly> anomalies) {
        @Override
        public String toString() {
            return scenario + ": iterations=" + iterations + ", answered with Connection: close=" + announcedClose
                    + ", reconnects=" + reconnects + ", anomalies=" + anomalies.size();
        }
    }

    /** Performs the rejected exchange and returns the response to it. */
    @FunctionalInterface
    interface Rejection {
        /** {@code pipelined} is a second request to write right behind the first one, or null to wait for the reply. */
        Response exchange(RawHttpConnection connection, String marker, byte[] pipelined) throws IOException;
    }

    record Scenario(String name, int expectedStatus, boolean pipelineable, Rejection rejection) { }

    private static final String FOLLOW_UP_STATUS = "404";

    private final String host;
    private final int port;
    private final Duration deadline;
    private final String forwardedFor;

    /** @param forwardedFor value of X-Forwarded-For to identify this client (when the app trusts it), or null */
    EarlyRejectionProbe(String host, int port, Duration deadline, String forwardedFor) {
        this.host = host;
        this.port = port;
        this.deadline = deadline;
        this.forwardedFor = forwardedFor;
    }

    // ------------------------------------------------------------------------------------------- scenarios

    private static final String JSON_BODY = "{\"quantity\":1,\"reason\":\"probe\"}";

    private String head(String method, String path, String contentType, String marker, String extra, String length) {
        StringBuilder sb = new StringBuilder(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                .append("Host: ").append(host).append(':').append(port).append("\r\n")
                .append("X-Correlation-Id: ").append(marker).append("\r\n");
        if (forwardedFor != null) {
            sb.append("X-Forwarded-For: ").append(forwardedFor).append("\r\n");
        }
        if (contentType != null) {
            sb.append("Content-Type: ").append(contentType).append("\r\n");
        }
        sb.append(extra);
        if (length != null) {
            sb.append(length).append("\r\n");
        }
        return sb.append("\r\n").toString();
    }

    private byte[] full(String method, String path, String contentType, String marker, String extra, byte[] body) {
        byte[] head = head(method, path, contentType, marker, extra, "Content-Length: " + body.length)
                .getBytes(StandardCharsets.ISO_8859_1);
        byte[] all = new byte[head.length + body.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(body, 0, all, head.length, body.length);
        return all;
    }

    private Rejection simple(String method, String path, String contentType, String extra, byte[] body) {
        return (connection, marker, pipelined) -> {
            connection.write(full(method, path, contentType, marker, extra, body));
            if (pipelined != null) {
                connection.write(pipelined);
            }
            return connection.readResponse();
        };
    }

    private static byte[] filler(int size) {
        byte[] body = new byte[size];
        java.util.Arrays.fill(body, (byte) 'a');
        body[0] = '{';
        body[size - 1] = '}';
        return body;
    }

    /** 401 (admin key missing / wrong), 405, 415, 413 (small, 100 KB, 1 MB), body arriving late, chunked bodies. */
    List<Scenario> rejectionScenarios() {
        String json = "application/json";
        String complimentary = "/events/evt-1/complimentary";
        byte[] smallBody = JSON_BODY.getBytes(StandardCharsets.UTF_8);
        List<Scenario> all = new ArrayList<>();
        all.add(new Scenario("401-admin-key-missing", 401, true,
                simple("POST", complimentary, json, "Idempotency-Key: probe-key-0123456789\r\n", smallBody)));
        all.add(new Scenario("401-admin-key-wrong", 401, true,
                simple("POST", complimentary, json, "X-Admin-Key: wrong\r\nIdempotency-Key: probe-key-0123456789\r\n",
                        smallBody)));
        all.add(new Scenario("401-admin-key-missing-body-10KB", 401, true,
                simple("POST", complimentary, json, "", filler(10_000))));
        all.add(new Scenario("405-delete-with-body", 405, true, simple("DELETE", "/events", json, "", smallBody)));
        all.add(new Scenario("405-delete-no-body", 405, true,
                (c, marker, pipelined) -> {
                    c.write(head("DELETE", "/events", null, marker, "", null));
                    if (pipelined != null) {
                        c.write(pipelined);
                    }
                    return c.readResponse();
                }));
        all.add(new Scenario("415-text-plain", 415, true,
                simple("POST", "/events", "text/plain", "", "x".getBytes(StandardCharsets.UTF_8))));
        all.add(new Scenario("415-text-plain-body-10KB", 415, true,
                simple("POST", "/events", "text/plain", "", filler(10_000))));
        all.add(new Scenario("413-body-100KB", 413, true,
                simple("POST", "/events", json, "", filler(100_000))));
        all.add(new Scenario("413-body-1MB", 413, true,
                simple("POST", "/orders", json, "Idempotency-Key: probe-key-0123456789\r\n", filler(1_000_000))));
        all.add(new Scenario("401-body-sent-after-the-response", 401, false, (c, marker, pipelined) -> {
            byte[] body = filler(2_000);
            c.write(head("POST", complimentary, json, marker, "", "Content-Length: " + body.length));
            Response response = c.readResponse(); // the 401 is out before a single body byte exists
            c.write(body);
            return response;
        }));
        all.add(new Scenario("401-chunked-body-all-at-once", 401, true, (c, marker, pipelined) -> {
            c.write(head("POST", complimentary, json, marker, "", "Transfer-Encoding: chunked") + chunks(16, 4_096));
            if (pipelined != null) {
                c.write(pipelined);
            }
            return c.readResponse();
        }));
        all.add(new Scenario("401-chunked-body-trickled-after-the-response", 401, false, (c, marker, pipelined) -> {
            c.write(head("POST", complimentary, json, marker, "", "Transfer-Encoding: chunked")
                    + chunk(4_096));
            Response response = c.readResponse();
            for (int i = 0; i < 15; i++) {
                c.write(chunk(4_096));
            }
            c.write("0\r\n\r\n");
            return response;
        }));
        all.add(new Scenario("413-chunked-body-trickled", 413, false, (c, marker, pipelined) -> {
            c.write(head("POST", "/events", json, marker, "", "Transfer-Encoding: chunked") + chunk(20_000));
            for (int i = 0; i < 4; i++) {
                c.write(chunk(20_000));
            }
            Response response = c.readResponse();
            c.write(chunk(20_000));
            c.write("0\r\n\r\n");
            return response;
        }));
        return all;
    }

    /** 429 of the write budget (POST /orders, POST /events). The caller must have spent the client's budget. */
    List<Scenario> writeBudgetScenarios() {
        String json = "application/json";
        byte[] smallBody = JSON_BODY.getBytes(StandardCharsets.UTF_8);
        return List.of(
                new Scenario("429-write-budget-spent", 429, true,
                        simple("POST", "/orders", json, "Idempotency-Key: probe-key-0123456789\r\n", smallBody)),
                new Scenario("429-write-budget-spent-body-100KB", 429, true,
                        simple("POST", "/events", json, "", filler(100_000))));
    }

    /** 429 of the admin-failure lockout (the admin key is not even checked). The caller must have locked the client out. */
    List<Scenario> adminLockoutScenarios() {
        return List.of(new Scenario("429-admin-failure-lockout", 429, true,
                simple("POST", "/events/evt-1/complimentary", "application/json", "X-Admin-Key: wrong\r\n",
                        JSON_BODY.getBytes(StandardCharsets.UTF_8))));
    }

    private static String chunk(int size) {
        return Integer.toHexString(size) + "\r\n" + "a".repeat(size) + "\r\n";
    }

    private static String chunks(int count, int size) {
        return chunk(size).repeat(count) + "0\r\n\r\n";
    }

    /** One plain request on the connection, answered or not: used to spend a budget before measuring. */
    Response once(Scenario scenario, String marker) throws IOException {
        try (RawHttpConnection connection = RawHttpConnection.open(host, port, deadline)) {
            return scenario.rejection().exchange(connection, marker, null);
        }
    }

    // ----------------------------------------------------------------------------------------------- the run

    private byte[] followUp(String marker) {
        return head("GET", "/no/such/route", null, marker, "", null).getBytes(StandardCharsets.ISO_8859_1);
    }

    /**
     * Runs {@code iterations} rounds of "rejected request, then a second request on the same connection". The connection
     * is kept for the whole run for as long as the server keeps it open.
     */
    Result run(Scenario scenario, int iterations, boolean pipelined) {
        if (pipelined && !scenario.pipelineable()) {
            throw new IllegalArgumentException(scenario.name() + " cannot be pipelined");
        }
        List<Anomaly> anomalies = new ArrayList<>();
        int announcedClose = 0;
        int reconnects = 0;
        RawHttpConnection connection = null;
        String name = scenario.name() + (pipelined ? "[pipelined]" : "");
        try {
            for (int i = 0; i < iterations; i++) {
                if (connection == null || !connection.isUsable()) {
                    close(connection);
                    connection = open();
                    reconnects++;
                }
                String rejectedMarker = "rej-" + i;
                String followMarker = "next-" + i;
                String phase = "rejected request";
                try {
                    Response rejected = scenario.rejection().exchange(connection, rejectedMarker,
                            pipelined ? followUp(followMarker) : null);
                    check(anomalies, name, i, "rejected response", rejected, scenario.expectedStatus() + "", rejectedMarker);
                    phase = "follow-up request";
                    if (rejected.closesConnection()) {
                        announcedClose++;
                        close(connection);
                        connection = open();
                        reconnects++;
                        if (pipelined) {
                            continue; // the pipelined follow-up was discarded together with the closed connection
                        }
                    }
                    if (!pipelined) {
                        connection.write(followUp(followMarker));
                    }
                    Response next = connection.readResponse();
                    check(anomalies, name, i, "follow-up response", next, FOLLOW_UP_STATUS, followMarker);
                } catch (NoResponseException e) {
                    anomalies.add(new Anomaly(name, i, "NO_RESPONSE", "no answer to the " + phase + " within "
                            + deadline.toMillis() + " ms; partial bytes: " + RawHttpConnection.printable(e.partial())));
                    close(connection);
                    connection = null;
                } catch (ClosedException e) {
                    anomalies.add(new Anomaly(name, i, "CLOSED_WITHOUT_ANSWER", phase + ": " + e.getMessage()
                            + "; partial bytes: " + RawHttpConnection.printable(e.partial())));
                    close(connection);
                    connection = null;
                } catch (IOException | RuntimeException e) {
                    anomalies.add(new Anomaly(name, i, "IO_ERROR", phase + ": " + e));
                    close(connection);
                    connection = null;
                }
            }
        } finally {
            close(connection);
        }
        return new Result(name, iterations, announcedClose, reconnects, anomalies);
    }

    private static void check(List<Anomaly> anomalies, String scenario, int iteration, String what, Response response,
                              String expectedStatus, String expectedMarker) {
        if (!(response.status() + "").equals(expectedStatus)) {
            anomalies.add(new Anomaly(scenario, iteration, "WRONG_STATUS", what + ": expected " + expectedStatus
                    + " but got " + RawHttpConnection.printable(response.raw())));
        } else if (!expectedMarker.equals(response.header("x-correlation-id"))) {
            anomalies.add(new Anomaly(scenario, iteration, "RESPONSE_OF_ANOTHER_REQUEST", what + ": expected correlation id "
                    + expectedMarker + " but got " + RawHttpConnection.printable(response.raw())));
        }
    }

    private RawHttpConnection open() {
        try {
            return RawHttpConnection.open(host, port, deadline);
        } catch (IOException e) {
            throw new IllegalStateException("cannot connect to " + host + ":" + port, e);
        }
    }

    private static void close(RawHttpConnection connection) {
        if (connection != null) {
            connection.close();
        }
    }
}
