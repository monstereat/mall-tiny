package com.macro.mall.tiny.modules.monitor.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SourceMapStackTraceParserTest {

    private final SourceMapStackTraceParser parser = new SourceMapStackTraceParser();

    @Test
    void parsesV8AndGeckoFramesAndSkipsNativeFrames() {
        List<SourceMapStackTraceParser.Frame> frames = parser.parse("""
                Error: boom
                    at handleClick (https://example.com/assets/app.js?v=1:12:34)
                    at https://example.com/assets/vendor.js:3:4
                submit@https://example.com/assets/legacy.js:5:6
                native code
                """, null, null, null);

        assertEquals(3, frames.size());
        assertEquals("handleClick", frames.get(0).function());
        assertEquals("https://example.com/assets/app.js", frames.get(0).file());
        assertEquals(12, frames.get(0).line());
        assertEquals(34, frames.get(0).column());
        assertNull(frames.get(1).function());
        assertEquals("submit", frames.get(2).function());
        assertEquals(2, frames.get(2).index());
    }

    @Test
    void usesReportedLocationWhenStackHasNoParseableFrames() {
        List<SourceMapStackTraceParser.Frame> frames = parser.parse(
                "Error: boom", "https://example.com/assets/app.js?v=2", 7, 0);

        assertEquals(1, frames.size());
        assertEquals("https://example.com/assets/app.js", frames.get(0).file());
        assertEquals("https://example.com/assets/app.js?v=2:7:1", frames.get(0).raw());
        assertEquals(7, frames.get(0).line());
        assertEquals(1, frames.get(0).column());
    }
}
