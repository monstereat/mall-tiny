package com.macro.mall.tiny.modules.monitor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.modules.monitor.dto.SourceMapResolvedPosition;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class SourceMapV3ResolverTest {

    private final SourceMapV3Resolver resolver = new SourceMapV3Resolver(new ObjectMapper());

    @Test
    void shouldResolveGeneratedPositionToOriginalSource() {
        String sourceMap = """
                {
                  "version": 3,
                  "file": "app.js",
                  "sourceRoot": "",
                  "sources": ["src/app.ts"],
                  "names": [],
                  "mappings": "AAAA;AACA",
                  "sourcesContent": ["const a = 1;\\nthrow new Error('boom');"]
                }
                """;

        SourceMapResolvedPosition position = resolver.resolve(
                        sourceMap.getBytes(StandardCharsets.UTF_8),
                        2,
                        1
                )
                .orElseThrow();

        assertEquals("src/app.ts", position.source());
        assertEquals(2, position.line());
        assertEquals(1, position.column());
        assertTrue(position.sourceContent().contains("boom"));
    }

    @Test
    void shouldReturnEmptyForUnmappedLine() {
        String sourceMap = """
                {
                  "version": 3,
                  "sources": ["src/app.ts"],
                  "names": [],
                  "mappings": "AAAA",
                  "sourcesContent": ["const a = 1;"]
                }
                """;

        assertTrue(resolver.resolve(
                sourceMap.getBytes(StandardCharsets.UTF_8),
                3,
                1
        ).isEmpty());
    }

    @Test
    void shouldReturnEmptyInsideUnmappedRangeAfterMappedSegment() {
        String sourceMap = """
                {
                  "version": 3,
                  "sources": ["src/app.ts"],
                  "names": [],
                  "mappings": "AAAA,K",
                  "sourcesContent": ["const a = 1;"]
                }
                """;

        assertTrue(resolver.resolve(
                sourceMap.getBytes(StandardCharsets.UTF_8),
                1,
                6
        ).isEmpty());
    }
}
