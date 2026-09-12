package com.ayodehi.thehush.debug;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Just enough HTTP/1.1 for a loopback debugging bridge: one request per connection, a request line, headers,
 * an optional Content-Length body, and a fixed-length response. No dependency on jdk.httpserver, which the
 * launcher's bundled runtime may not ship.
 */
public final class MiniHttp {
    private static final int MAX_HEADER_BYTES = 64 * 1024;
    private static final int MAX_BODY_BYTES = 4 * 1024 * 1024;

    private MiniHttp() {}

    public record Request(String method, String path, Map<String, String> query, Map<String, String> headers, String body) {
        public String param(String name) {
            return query.get(name);
        }

        public String param(String name, String fallback) {
            String v = query.get(name);
            return v == null || v.isEmpty() ? fallback : v;
        }

        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    /** Reads one request. Returns null on a clean end of stream before any bytes. */
    public static Request read(InputStream in) throws IOException {
        String head = readHead(in);
        if (head == null) return null;
        String[] lines = head.split("\r\n");
        String[] requestLine = lines[0].split(" ");
        if (requestLine.length < 2) throw new IOException("Bad request line: " + lines[0]);
        String method = requestLine[0].toUpperCase(Locale.ROOT);
        String target = requestLine[1];
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).trim());
        }
        String path = target;
        Map<String, String> query = new LinkedHashMap<>();
        int q = target.indexOf('?');
        if (q >= 0) {
            path = target.substring(0, q);
            for (String pair : target.substring(q + 1).split("&")) {
                if (pair.isEmpty()) continue;
                int eq = pair.indexOf('=');
                String k = eq < 0 ? pair : pair.substring(0, eq);
                String v = eq < 0 ? "" : pair.substring(eq + 1);
                query.put(decode(k), decode(v));
            }
        }
        int length = 0;
        String cl = headers.get("content-length");
        if (cl != null) {
            try {
                length = Integer.parseInt(cl.trim());
            } catch (NumberFormatException e) {
                throw new IOException("Bad Content-Length: " + cl);
            }
        }
        if (length < 0 || length > MAX_BODY_BYTES) throw new IOException("Body too large: " + length);
        byte[] body = in.readNBytes(length);
        if (body.length != length) throw new IOException("Truncated body: " + body.length + " of " + length);
        return new Request(method, path, query, headers, new String(body, StandardCharsets.UTF_8));
    }

    public static void write(OutputStream out, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + ' ' + reason(status) + "\r\n"
                + "Content-Type: " + contentType + "; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
    }

    static String reason(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 500 -> "Internal Server Error";
            case 503 -> "Service Unavailable";
            case 504 -> "Gateway Timeout";
            default -> "Status " + status;
        };
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        int state = 0; // counts the \r\n\r\n terminator
        while ((b = in.read()) >= 0) {
            buf.write(b);
            if (buf.size() > MAX_HEADER_BYTES) throw new IOException("Headers too large");
            state = switch (state) {
                case 0 -> b == '\r' ? 1 : 0;
                case 1 -> b == '\n' ? 2 : 0;
                case 2 -> b == '\r' ? 3 : 0;
                case 3 -> b == '\n' ? 4 : 0;
                default -> 0;
            };
            if (state == 4) break;
        }
        if (buf.size() == 0) return null;
        if (state != 4) throw new IOException("Truncated request head");
        String head = buf.toString(StandardCharsets.ISO_8859_1);
        return head.substring(0, head.length() - 4);
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}
