package org.apigateway.qa;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * QA helper: sends a request byte for byte over a plain socket, so tests control the exact request line and
 * headers (paths such as {@code //api}, {@code /..}, {@code %2e%2e} or {@code ;jsessionid}, duplicate headers,
 * control characters) that {@link java.net.http.HttpClient} would normalise or refuse.
 */
final class RawHttp {

    private static final int READ_TIMEOUT_MILLIS = 30_000;

    private RawHttp() {
    }

    /**
     * Sends {@code METHOD target HTTP/1.1} with the given header lines (each without CRLF) and no body,
     * then reads the whole response ({@code Connection: close}).
     */
    static Response send(int port, String method, String target, String... headerLines) {
        StringBuilder request = new StringBuilder()
                .append(method).append(' ').append(target).append(" HTTP/1.1\r\n")
                .append("Host: localhost:").append(port).append("\r\n");
        for (String headerLine : headerLines) {
            request.append(headerLine).append("\r\n");
        }
        request.append("Content-Length: 0\r\n").append("Connection: close\r\n\r\n");
        return sendBytes(port, request.toString().getBytes(StandardCharsets.ISO_8859_1));
    }

    /**
     * Sends raw bytes as they are and reads the whole response.
     */
    static Response sendBytes(int port, byte[] rawRequest) {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(READ_TIMEOUT_MILLIS);
            OutputStream out = socket.getOutputStream();
            out.write(rawRequest);
            out.flush();
            return Response.parse(readAll(socket.getInputStream()));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    /**
     * A parsed HTTP/1.1 response. Header names keep their wire case; lookups ignore case.
     */
    record Response(int status, List<Map.Entry<String, String>> headers, String body) {

        List<String> headerValues(String name) {
            return headers.stream()
                    .filter(header -> header.getKey().equalsIgnoreCase(name))
                    .map(Map.Entry::getValue)
                    .toList();
        }

        List<String> headerNamesLowerCase() {
            return headers.stream().map(header -> header.getKey().toLowerCase(Locale.ROOT)).toList();
        }

        static Response parse(byte[] raw) {
            String text = new String(raw, StandardCharsets.ISO_8859_1);
            int headEnd = text.indexOf("\r\n\r\n");
            if (headEnd < 0) {
                throw new IllegalStateException("No complete HTTP response head received: " + text);
            }
            String[] lines = text.substring(0, headEnd).split("\r\n");
            int status = Integer.parseInt(lines[0].split(" ")[1]);
            List<Map.Entry<String, String>> headers = new ArrayList<>();
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                headers.add(Map.entry(lines[i].substring(0, colon).trim(), lines[i].substring(colon + 1).trim()));
            }
            String rawBody = text.substring(headEnd + 4);
            boolean chunked = headers.stream().anyMatch(header ->
                    header.getKey().equalsIgnoreCase("Transfer-Encoding")
                            && header.getValue().toLowerCase(Locale.ROOT).contains("chunked"));
            String body = chunked ? dechunk(rawBody) : rawBody;
            return new Response(status, List.copyOf(headers),
                    new String(body.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8));
        }

        private static String dechunk(String chunked) {
            StringBuilder body = new StringBuilder();
            int position = 0;
            while (position < chunked.length()) {
                int lineEnd = chunked.indexOf("\r\n", position);
                if (lineEnd < 0) {
                    break;
                }
                int size = Integer.parseInt(chunked.substring(position, lineEnd).split(";")[0].trim(), 16);
                if (size == 0) {
                    break;
                }
                int start = lineEnd + 2;
                body.append(chunked, start, Math.min(start + size, chunked.length()));
                position = start + size + 2;
            }
            return body.toString();
        }
    }
}
