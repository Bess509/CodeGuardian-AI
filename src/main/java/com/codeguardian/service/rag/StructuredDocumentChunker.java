package com.codeguardian.service.rag;

import org.springframework.ai.document.Document;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class StructuredDocumentChunker {

    private static final int TARGET_CHARS = 2600;
    private static final int HARD_LIMIT_CHARS = 3600;
    private static final int OVERLAP_CHARS = 320;
    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");
    private static final Pattern RULE_BOUNDARY_PATTERN = Pattern.compile(
            "^(?:\\s*(?:Rule|RULE|Guideline|GUIDELINE)\\s*[:#-]?\\s*\\w+|\\s*(?:\\d+\\.)+\\s+.+|\\s*[-*]\\s*(?:Rule|RULE)\\b.+|\\s*(?:CWE|OWASP|P3C|SEC|SAFE|RULE)-[A-Za-z0-9_.-]+\\b.*|\\s*\\u3010(?:\\u5f3a\\u5236|\\u63a8\\u8350|\\u53c2\\u8003)\\u3011.*)$");
    private static final Pattern RULE_ID_PATTERN = Pattern.compile("\\b(?:CWE-\\d+|OWASP-[A-Za-z0-9_.-]+|P3C-[A-Za-z0-9_.-]+|SEC-[A-Za-z0-9_.-]+|SAFE-[A-Za-z0-9_.-]+|RULE-[A-Za-z0-9_.-]+)\\b");

    private StructuredDocumentChunker() {
    }

    static List<Document> split(String sourceId, String content, Map<String, Object> baseMetadata) {
        String normalized = normalize(content);
        if (normalized.isBlank()) {
            return List.of();
        }

        List<Section> sections = toSections(normalized);
        List<Document> chunks = new ArrayList<>();
        int[] offset = {0};
        for (Section section : sections) {
            splitSection(sourceId, section, baseMetadata, chunks, offset);
            offset[0] += section.text.length();
        }

        int total = chunks.size();
        for (int i = 0; i < total; i++) {
            chunks.get(i).getMetadata().put("chunk_count", total);
        }
        return chunks;
    }

    private static List<Section> toSections(String content) {
        List<Section> sections = new ArrayList<>();
        List<String> headingStack = new ArrayList<>();
        String currentHeading = "";
        StringBuilder buffer = new StringBuilder();

        String[] lines = content.split("\\n", -1);
        for (String line : lines) {
            Matcher heading = HEADING_PATTERN.matcher(line);
            if (heading.matches()) {
                flush(sections, currentHeading, buffer);
                int level = heading.group(1).length();
                while (headingStack.size() >= level) {
                    headingStack.remove(headingStack.size() - 1);
                }
                headingStack.add(heading.group(2).trim());
                currentHeading = String.join(" > ", headingStack);
                appendLine(buffer, line);
                continue;
            }

            if (isRuleBoundary(line) && buffer.length() > 0) {
                flush(sections, currentHeading, buffer);
            }
            appendLine(buffer, line);
        }

        flush(sections, currentHeading, buffer);
        return sections;
    }

    private static void splitSection(String sourceId,
                                     Section section,
                                     Map<String, Object> baseMetadata,
                                     List<Document> chunks,
                                     int[] offset) {
        if (section.text.length() <= HARD_LIMIT_CHARS) {
            addChunk(sourceId, section.text, section.headingPath, offset[0], baseMetadata, chunks);
            return;
        }

        List<String> paragraphs = paragraphBlocks(section.text);
        StringBuilder current = new StringBuilder();
        int chunkStart = offset[0];
        for (String paragraph : paragraphs) {
            if (paragraph.length() > HARD_LIMIT_CHARS) {
                if (current.length() > 0) {
                    addChunk(sourceId, current.toString(), section.headingPath, chunkStart, baseMetadata, chunks);
                    chunkStart += Math.max(0, current.length() - OVERLAP_CHARS);
                    current = new StringBuilder(overlap(current.toString()));
                }
                chunkStart = addLargeParagraph(sourceId, paragraph, section.headingPath, chunkStart, baseMetadata, chunks);
                current = new StringBuilder();
                continue;
            }

            if (current.length() > 0 && current.length() + paragraph.length() > TARGET_CHARS) {
                addChunk(sourceId, current.toString(), section.headingPath, chunkStart, baseMetadata, chunks);
                chunkStart += Math.max(0, current.length() - OVERLAP_CHARS);
                current = new StringBuilder(overlap(current.toString()));
            }
            current.append(paragraph);
        }

        if (current.length() > 0) {
            addChunk(sourceId, current.toString(), section.headingPath, chunkStart, baseMetadata, chunks);
        }
    }

    private static int addLargeParagraph(String sourceId,
                                         String paragraph,
                                         String headingPath,
                                         int startOffset,
                                         Map<String, Object> baseMetadata,
                                         List<Document> chunks) {
        int index = 0;
        while (index < paragraph.length()) {
            int end = Math.min(paragraph.length(), index + TARGET_CHARS);
            String part = paragraph.substring(index, end);
            addChunk(sourceId, part, headingPath, startOffset + index, baseMetadata, chunks);
            if (end == paragraph.length()) {
                break;
            }
            index = Math.max(end - OVERLAP_CHARS, index + 1);
        }
        return startOffset + paragraph.length();
    }

    private static void addChunk(String sourceId,
                                 String rawText,
                                 String headingPath,
                                 int charStart,
                                 Map<String, Object> baseMetadata,
                                 List<Document> chunks) {
        String text = rawText != null ? rawText.trim() : "";
        if (text.isBlank()) {
            return;
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        if (baseMetadata != null) {
            metadata.putAll(baseMetadata);
        }
        int index = chunks.size();
        String chunkKey = (sourceId != null && !sourceId.isBlank() ? sourceId : UUID.randomUUID()) + "::chunk-" + (index + 1);
        String chunkId = UUID.nameUUIDFromBytes(chunkKey.getBytes(StandardCharsets.UTF_8)).toString();
        metadata.put("source_doc_id", sourceId);
        metadata.put("chunk_id", chunkId);
        metadata.put("chunk_key", chunkKey);
        metadata.put("chunk_index", index);
        metadata.put("chunk_strategy", "heading_rule_aware_overlap");
        metadata.put("heading_path", headingPath != null ? headingPath : "");
        metadata.put("rule_ids", extractRuleIds(text));
        metadata.put("chunk_char_start", charStart);
        metadata.put("chunk_char_end", charStart + text.length());
        metadata.put("chunk_overlap_chars", OVERLAP_CHARS);

        chunks.add(new Document(chunkId, text, metadata));
    }

    private static void flush(List<Section> sections, String headingPath, StringBuilder buffer) {
        String text = buffer.toString().trim();
        if (!text.isBlank()) {
            sections.add(new Section(headingPath, text));
        }
        buffer.setLength(0);
    }

    private static void appendLine(StringBuilder buffer, String line) {
        buffer.append(line != null ? line : "").append('\n');
    }

    private static boolean isRuleBoundary(String line) {
        return line != null && RULE_BOUNDARY_PATTERN.matcher(line).matches();
    }

    private static List<String> extractRuleIds(String text) {
        Set<String> ids = new LinkedHashSet<>();
        Matcher matcher = RULE_ID_PATTERN.matcher(text);
        while (matcher.find()) {
            ids.add(matcher.group());
        }
        return new ArrayList<>(ids);
    }

    private static List<String> paragraphBlocks(String text) {
        List<String> blocks = new ArrayList<>();
        String[] parts = text.split("(?<=\\n\\n)");
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                blocks.add(part);
            }
        }
        if (blocks.isEmpty() && text != null && !text.isBlank()) {
            blocks.add(text);
        }
        return blocks;
    }

    private static String overlap(String text) {
        if (text == null || text.length() <= OVERLAP_CHARS) {
            return text != null ? text : "";
        }
        return text.substring(text.length() - OVERLAP_CHARS);
    }

    private static String normalize(String content) {
        return (content != null ? content : "")
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim();
    }

    private record Section(String headingPath, String text) {
    }
}
