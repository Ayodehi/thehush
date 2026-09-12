package com.ayodehi.thehush.debug;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiniHttpTest {
    private static MiniHttp.Request parse(String raw) throws IOException {
        return MiniHttp.read(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void parsesGetWithQuery() throws IOException {
        var r = parse("GET /entities?player=Ayodehi&radius=12&q=a%20b HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer t\r\n\r\n");
        assertEquals("GET", r.method());
        assertEquals("/entities", r.path());
        assertEquals("Ayodehi", r.param("player"));
        assertEquals("12", r.param("radius"));
        assertEquals("a b", r.param("q"));
        assertEquals("24", r.param("missing", "24"));
        assertEquals("Bearer t", r.header("authorization"));
        assertEquals("", r.body());
    }

    @Test
    void readsBodyByContentLength() throws IOException {
        String body = "{\"command\":\"hush status\"}";
        var r = parse("POST /command HTTP/1.1\r\nContent-Length: " + body.length() + "\r\n\r\n" + body + "trailing junk");
        assertEquals("POST", r.method());
        assertEquals(body, r.body());
    }

    @Test
    void emptyStreamIsNull() throws IOException {
        assertNull(parse(""));
    }

    @Test
    void truncatedBodyFails() {
        assertThrows(IOException.class, () -> parse("POST /x HTTP/1.1\r\nContent-Length: 10\r\n\r\nabc"));
    }

    @Test
    void writesFixedLengthResponse() throws IOException {
        var out = new ByteArrayOutputStream();
        MiniHttp.write(out, 404, "application/json", "{\"error\":\"nope\"}");
        String s = out.toString(StandardCharsets.UTF_8);
        assertTrue(s.startsWith("HTTP/1.1 404 Not Found\r\n"));
        assertTrue(s.contains("Content-Length: 16\r\n"));
        assertTrue(s.endsWith("\r\n\r\n{\"error\":\"nope\"}"));
    }
}
