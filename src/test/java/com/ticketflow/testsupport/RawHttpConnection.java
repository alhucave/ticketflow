package com.ticketflow.testsupport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A minimal HTTP/1.1 client over ONE raw TCP socket, for tests that need exact control of the bytes on the wire
 * (what is sent, when, and in how many writes) and of the connection reuse, which a pooled HTTP client hides.
 * It is deliberately small: it writes whatever bytes it is given, and it parses one response at a time
 * (status line, headers, {@code Content-Length} or chunked body) leaving the rest of the stream untouched, so a
 * response that belongs to another request, or bytes after a response, show up in the next read.
 */
public final class RawHttpConnection implements AutoCloseable {

    /** One parsed response and the exact bytes it occupied on the wire. */
    public record Response(int status, Map<String, String> headers, byte[] body, byte[] raw) {

        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        public boolean closesConnection() {
            String connection = header("connection");
            return connection != null && connection.toLowerCase(Locale.ROOT).contains("close");
        }

        public String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    /** The server sent no (complete) response within the deadline; carries the bytes received so far. */
    public static final class NoResponseException extends IOException {
        private final transient byte[] partial;

        NoResponseException(String message, byte[] partial) {
            super(message);
            this.partial = partial;
        }

        public byte[] partial() {
            return partial;
        }
    }

    /** The server closed the connection (EOF) before a complete response arrived. */
    public static final class ClosedException extends IOException {
        private final transient byte[] partial;

        ClosedException(String message, byte[] partial) {
            super(message);
            this.partial = partial;
        }

        public byte[] partial() {
            return partial;
        }
    }

    private static final Duration WRITE_LIMIT = Duration.ofSeconds(20);
    private static final java.util.concurrent.ScheduledExecutorService WATCHDOGS =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "raw-http-write-watchdog");
                thread.setDaemon(true);
                return thread;
            });

    private final Socket socket;
    private final InputStream in;

    private RawHttpConnection(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new java.io.BufferedInputStream(socket.getInputStream());
    }

    public static RawHttpConnection open(String host, int port, Duration readDeadline) throws IOException {
        Socket socket = new Socket();
        socket.setTcpNoDelay(true);
        socket.setSoTimeout((int) readDeadline.toMillis());
        socket.connect(new InetSocketAddress(host, port), 5_000);
        return new RawHttpConnection(socket);
    }

    public int localPort() {
        return socket.getLocalPort();
    }

    public boolean isUsable() {
        return !socket.isClosed() && !socket.isInputShutdown() && !socket.isOutputShutdown();
    }

    /**
     * Writes all bytes. A raw socket has no write timeout, and a server that answers early and stops reading would
     * block a large write forever once the TCP buffers fill: a watchdog closes the socket after {@link #WRITE_LIMIT}.
     */
    public void write(byte[] bytes) throws IOException {
        var watchdog = WATCHDOGS.schedule(this::close, WRITE_LIMIT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        try {
            socket.getOutputStream().write(bytes);
            socket.getOutputStream().flush();
        } catch (java.net.SocketException e) {
            throw new ClosedException("write failed (connection closed or write stalled for " + WRITE_LIMIT.toSeconds()
                    + " s): " + e.getMessage(), new byte[0]);
        } finally {
            watchdog.cancel(false);
        }
    }

    public void write(String ascii) throws IOException {
        write(ascii.getBytes(StandardCharsets.ISO_8859_1));
    }

    /** True if the server has closed its side (EOF) without sending anything more; waits at most {@code wait}. */
    public boolean serverClosedWithin(Duration wait) throws IOException {
        int previous = socket.getSoTimeout();
        socket.setSoTimeout(Math.max(1, (int) wait.toMillis()));
        try {
            in.mark(1);
            int next = in.read();
            if (next != -1) {
                in.reset();
            }
            return next == -1;
        } catch (SocketTimeoutException e) {
            return false;
        } catch (IOException reset) {
            return true;
        } finally {
            socket.setSoTimeout(previous);
        }
    }

    /** Reads exactly one response, honouring {@code Content-Length} and chunked framing. */
    public Response readResponse() throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try {
            String statusLine = readLine(raw);
            String[] parts = statusLine.split(" ", 3);
            if (parts.length < 2 || !parts[0].startsWith("HTTP/1.")) {
                throw new IOException("malformed status line: " + statusLine);
            }
            int status = Integer.parseInt(parts[1]);
            Map<String, String> headers = new LinkedHashMap<>();
            for (String line = readLine(raw); !line.isEmpty(); line = readLine(raw)) {
                int colon = line.indexOf(':');
                if (colon < 1) {
                    throw new IOException("malformed header line: " + line);
                }
                headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
            }
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            String transferEncoding = headers.get("transfer-encoding");
            if (transferEncoding != null && transferEncoding.toLowerCase(Locale.ROOT).contains("chunked")) {
                while (true) {
                    int size = Integer.parseInt(readLine(raw).split(";")[0].trim(), 16);
                    if (size == 0) {
                        while (!readLine(raw).isEmpty()) {
                            // trailers
                        }
                        break;
                    }
                    body.write(readExactly(size, raw));
                    readLine(raw); // CRLF after the chunk
                }
            } else if (headers.containsKey("content-length")) {
                body.write(readExactly(Integer.parseInt(headers.get("content-length")), raw));
            } else if (status >= 200 && status != 204 && status != 304) {
                throw new IOException("response without Content-Length or chunked framing: " + statusLine);
            }
            return new Response(status, headers, body.toByteArray(), raw.toByteArray());
        } catch (SocketTimeoutException e) {
            throw new NoResponseException("no complete response before the read deadline (" + socket.getSoTimeout()
                    + " ms); bytes received so far: " + raw.size(), raw.toByteArray());
        } catch (java.net.SocketException e) {
            throw new ClosedException("connection failed while reading a response: " + e.getMessage(), raw.toByteArray());
        }
    }

    private String readLine(ByteArrayOutputStream raw) throws IOException {
        StringBuilder line = new StringBuilder();
        while (true) {
            int b = in.read();
            if (b == -1) {
                throw new ClosedException("connection closed by the server while reading a response line", raw.toByteArray());
            }
            raw.write(b);
            if (b == '\n') {
                int n = line.length();
                if (n > 0 && line.charAt(n - 1) == '\r') {
                    line.setLength(n - 1);
                }
                return line.toString();
            }
            line.append((char) b);
        }
    }

    private byte[] readExactly(int count, ByteArrayOutputStream raw) throws IOException {
        byte[] data = new byte[count];
        int read = 0;
        while (read < count) {
            int n = in.read(data, read, count - read);
            if (n == -1) {
                throw new ClosedException("connection closed by the server inside a response body", raw.toByteArray());
            }
            raw.write(data, read, n);
            read += n;
        }
        return data;
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }

    /** Printable form of wire bytes for failure messages: control characters are escaped, long input is cut. */
    public static String printable(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.ISO_8859_1).replace("\r", "\\r").replace("\n", "\\n\n");
        return text.length() > 1_500 ? text.substring(0, 1_500) + "...(" + bytes.length + " bytes)" : text;
    }
}
