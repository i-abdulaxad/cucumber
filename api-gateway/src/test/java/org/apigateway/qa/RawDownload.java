package org.apigateway.qa;

import org.apigateway.support.TestTokens;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * QA helper for platform-hardening US-6 (D11): a GET with {@code {valid token}} over a plain socket that tells a cleanly
 * framed end (terminating chunk, or {@code Content-Length} bytes) from an abnormal one (end of stream or reset first).
 * The request uses keep-alive (no {@code Connection: close}), so only the framing can make a response look complete.
 */
final class RawDownload {

    private static final int CLIENT_READ_TIMEOUT_MILLIS = 10_000;

    enum End { COMPLETE, EOF_BEFORE_COMPLETE, CONNECTION_RESET, CLOSE_DELIMITED, CLIENT_TIMEOUT }

    record Received(int status, String framing, String body, End end) {

        boolean endedAbnormally() {
            return end == End.EOF_BEFORE_COMPLETE || end == End.CONNECTION_RESET;
        }

        String tail() {
            return body.substring(Math.max(0, body.length() - 300));
        }
    }

    private RawDownload() {
    }

    static Received get(int port, String path, String requestId) {
        String request = "GET " + path + " HTTP/1.1\r\nHost: localhost:" + port + "\r\n"
                + "Authorization: " + TestTokens.bearerValid() + "\r\nX-Request-Id: " + requestId + "\r\n\r\n";
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(CLIENT_READ_TIMEOUT_MILLIS);
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = new BufferedInputStream(socket.getInputStream());
            String head = readLine(in);
            if (head == null) {
                throw new IllegalStateException("No status line received for " + path);
            }
            int status = Integer.parseInt(head.split(" ")[1]);
            String framing = "close-delimited";
            long contentLength = -1;
            for (String line = readLine(in); line != null && !line.isEmpty(); line = readLine(in)) {
                String lower = line.toLowerCase(Locale.ROOT);
                if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) {
                    framing = "chunked";
                } else if (lower.startsWith("content-length:")) {
                    contentLength = Long.parseLong(line.substring(line.indexOf(':') + 1).trim());
                    framing = "content-length " + contentLength;
                }
            }
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            End end;
            try {
                if (framing.equals("chunked")) {
                    end = readChunked(in, body);
                } else if (contentLength >= 0) {
                    end = copy(in, body, contentLength) ? End.COMPLETE : End.EOF_BEFORE_COMPLETE;
                } else {
                    copy(in, body, Long.MAX_VALUE);
                    end = End.CLOSE_DELIMITED;
                }
            } catch (SocketTimeoutException hung) {
                end = End.CLIENT_TIMEOUT;
            } catch (IOException reset) {
                end = End.CONNECTION_RESET;
            }
            return new Received(status, framing, body.toString(StandardCharsets.UTF_8), end);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static End readChunked(InputStream in, ByteArrayOutputStream body) throws IOException {
        while (true) {
            String sizeLine = readLine(in);
            if (sizeLine == null) {
                return End.EOF_BEFORE_COMPLETE;
            }
            int size = Integer.parseInt(sizeLine.split(";")[0].trim(), 16);
            if (size == 0) {
                for (String trailer = readLine(in); ; trailer = readLine(in)) {
                    if (trailer == null) {
                        return End.EOF_BEFORE_COMPLETE;
                    }
                    if (trailer.isEmpty()) {
                        return End.COMPLETE;
                    }
                }
            }
            if (!copy(in, body, size) || readLine(in) == null) {
                return End.EOF_BEFORE_COMPLETE;
            }
        }
    }

    /** Copies up to {@code length} bytes; false if the stream ended first. */
    private static boolean copy(InputStream in, ByteArrayOutputStream body, long length) throws IOException {
        byte[] buffer = new byte[8192];
        long remaining = length;
        while (remaining > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read == -1) {
                return false;
            }
            body.write(buffer, 0, read);
            remaining -= read;
        }
        return true;
    }

    /** One CRLF-terminated line without the CRLF, or null at end of stream. */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int previous = -1;
        int next;
        while ((next = in.read()) != -1) {
            if (previous == '\r' && next == '\n') {
                byte[] bytes = line.toByteArray();
                return new String(bytes, 0, bytes.length - 1, StandardCharsets.ISO_8859_1);
            }
            line.write(next);
            previous = next;
        }
        return null;
    }
}
