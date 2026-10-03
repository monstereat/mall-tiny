package com.macro.mall.tiny.modules.monitor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.modules.monitor.dto.SourceMapResolvedPosition;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class SourceMapV3Resolver {

    private static final String BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    private final ObjectMapper objectMapper;

    public Optional<SourceMapResolvedPosition> resolve(
            byte[] sourceMapBytes,
            int generatedLine,
            int generatedColumn) {
        if (generatedLine <= 0 || generatedColumn <= 0) {
            return Optional.empty();
        }

        try {
            JsonNode root = objectMapper.readTree(sourceMapBytes);
            if (root.path("version").asInt() != 3) {
                throw new IllegalArgumentException("only Source Map v3 is supported");
            }

            String mappings = root.path("mappings").asText("");
            if (!StringUtils.hasText(mappings)) {
                return Optional.empty();
            }

            JsonNode sources = root.path("sources");
            JsonNode names = root.path("names");
            JsonNode sourcesContent = root.path("sourcesContent");
            String sourceRoot = root.path("sourceRoot").asText("");

            int targetLine = generatedLine - 1;
            int targetColumn = generatedColumn - 1;
            String[] lines = mappings.split(";", -1);

            int sourceIndex = 0;
            int originalLine = 0;
            int originalColumn = 0;
            int nameIndex = 0;

            for (int lineIndex = 0; lineIndex < lines.length && lineIndex <= targetLine; lineIndex++) {
                int generatedColumnState = 0;
                SourceMapResolvedPosition candidate = null;
                String mappingLine = lines[lineIndex];

                if (!mappingLine.isEmpty()) {
                    String[] segments = mappingLine.split(",", -1);
                    for (String segment : segments) {
                        if (segment.isEmpty()) {
                            continue;
                        }

                        int[] values = decodeSegment(segment);
                        if (values.length == 0) {
                            continue;
                        }

                        generatedColumnState += values[0];

                        // A single-field segment marks an unmapped range. It must clear a prior
                        // candidate so positions after this segment are not attributed to an
                        // earlier generated range.
                        if (lineIndex == targetLine && generatedColumnState <= targetColumn && values.length == 1) {
                            candidate = null;
                        } else if (values.length >= 4) {
                            sourceIndex += values[1];
                            originalLine += values[2];
                            originalColumn += values[3];
                            if (values.length >= 5) {
                                nameIndex += values[4];
                            }

                            if (lineIndex == targetLine && generatedColumnState <= targetColumn) {
                                candidate = toPosition(
                                        sourceRoot,
                                        sources,
                                        names,
                                        sourcesContent,
                                        sourceIndex,
                                        originalLine,
                                        originalColumn,
                                        nameIndex,
                                        values.length >= 5
                                );
                            }
                        }

                        if (lineIndex == targetLine && generatedColumnState > targetColumn) {
                            break;
                        }
                    }
                }

                if (lineIndex == targetLine) {
                    return Optional.ofNullable(candidate);
                }
            }

            return Optional.empty();
        } catch (IOException e) {
            throw new IllegalArgumentException("invalid source map json", e);
        }
    }

    private SourceMapResolvedPosition toPosition(
            String sourceRoot,
            JsonNode sources,
            JsonNode names,
            JsonNode sourcesContent,
            int sourceIndex,
            int originalLine,
            int originalColumn,
            int nameIndex,
            boolean hasName) {
        if (!sources.isArray() || sourceIndex < 0 || sourceIndex >= sources.size()) {
            return null;
        }

        String source = sources.get(sourceIndex).asText();
        if (StringUtils.hasText(sourceRoot)) {
            source = joinPath(sourceRoot, source);
        }

        String name = null;
        if (hasName && names.isArray() && nameIndex >= 0 && nameIndex < names.size()) {
            name = names.get(nameIndex).asText(null);
        }

        String sourceContent = null;
        if (sourcesContent.isArray()
                && sourceIndex >= 0
                && sourceIndex < sourcesContent.size()
                && !sourcesContent.get(sourceIndex).isNull()) {
            sourceContent = sourcesContent.get(sourceIndex).asText();
        }

        return new SourceMapResolvedPosition(
                source,
                originalLine + 1,
                originalColumn + 1,
                name,
                sourceContent
        );
    }

    private String joinPath(String root, String source) {
        if (root.endsWith("/") || source.startsWith("/")) {
            return root + source;
        }
        return root + "/" + source;
    }

    private int[] decodeSegment(String segment) {
        List<Integer> values = new ArrayList<>();
        int index = 0;

        while (index < segment.length()) {
            int result = 0;
            int shift = 0;
            boolean continuation;

            do {
                if (index >= segment.length()) {
                    throw new IllegalArgumentException("truncated VLQ segment");
                }
                int digit = BASE64.indexOf(segment.charAt(index++));
                if (digit < 0) {
                    throw new IllegalArgumentException("invalid base64 VLQ digit");
                }

                continuation = (digit & 32) != 0;
                digit &= 31;
                result += digit << shift;
                shift += 5;
            } while (continuation);

            boolean negative = (result & 1) == 1;
            int value = result >> 1;
            values.add(negative ? -value : value);
        }

        return values.stream().mapToInt(Integer::intValue).toArray();
    }
}
