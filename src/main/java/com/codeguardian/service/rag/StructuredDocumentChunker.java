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

    private static final String CHUNK_STRATEGY = "structure_rule_sentence_token_v1";
    private static final int TARGET_TOKENS = 550;
    private static final int HARD_MAX_TOKENS = 800;
    private static final int OVERLAP_TOKENS = 100;
    private static final int LEGACY_OVERLAP_CHARS = 320;
    private static final int MAX_HEADING_LENGTH = 80;

    private static final String SPLIT_HEADING = "heading";
    private static final String SPLIT_RULE_BOUNDARY = "rule_boundary";
    private static final String SPLIT_PARAGRAPH = "paragraph";
    private static final String SPLIT_SENTENCE = "sentence";
    private static final String SPLIT_TOKEN_WINDOW = "token_window";

    private static final Pattern MARKDOWN_HEADING_PATTERN = Pattern.compile("^\\s*(#{1,6})\\s+(.+?)\\s*$");
    private static final Pattern NUMBERED_HEADING_PATTERN = Pattern.compile("^\\s*(\\d+(?:\\.\\d+)*)(?:[.)])?\\s+(.+?)\\s*$");
    private static final Pattern CHINESE_HEADING_PATTERN = Pattern.compile(
            "^\\s*(?:[\\u4e00-\\u9fa5]{1,8}[\\u3001.]|[\\uff08(][\\u4e00-\\u9fa5]{1,8}[\\uff09)]|\\u7b2c\\s*[0-9\\u4e00-\\u9fa5]+\\s*[\\u7ae0\\u8282\\u7f16])\\s*(.+?)\\s*$");
    private static final Pattern BARE_CHINESE_HEADING_PATTERN = Pattern.compile("^[\\u4e00-\\u9fa5A-Za-z0-9\\s_-]{2,80}$");
    private static final Pattern ALL_CAPS_HEADING_PATTERN = Pattern.compile("^[A-Z][A-Z0-9 _/-]{2,80}$");
    private static final Pattern RULE_BOUNDARY_PATTERN = Pattern.compile(
            "^(?:\\s*(?:Rule|RULE|Guideline|GUIDELINE)\\s*[:#-]?\\s*\\w+.*"
                    + "|\\s*[-*]\\s*(?:Rule|RULE)\\b.+"
                    + "|\\s*(?:CWE-\\d+|OWASP-[A-Za-z0-9_.-]+|P3C-[A-Za-z0-9_.-]+|SEC-[A-Za-z0-9_.-]+|SAFE-[A-Za-z0-9_.-]+|RULE-[A-Za-z0-9_.-]+|CG-CODE-[A-Za-z0-9_.-]+)\\b.*"
                    + "|\\s*\\u3010(?:\\u5f3a\\u5236|\\u63a8\\u8350|\\u53c2\\u8003)\\u3011.*"
                    + "|\\s*\\[(?:Mandatory|Recommended|Reference)\\].*"
                    + "|\\s*(?:\\u89c4\\u5219\\s*[0-9\\u4e00-\\u9fa5]+|\\u7b2c\\s*[0-9\\u4e00-\\u9fa5]+\\s*\\u6761|\\u68c0\\u67e5\\u9879|\\u7981\\u6b62|\\u5efa\\u8bae|\\u98ce\\u9669|\\u4fee\\u590d\\u5efa\\u8bae)\\s*[:\\uff1a]?.*)$");
    private static final Pattern RULE_ID_PATTERN = Pattern.compile(
            "\\b(?:CWE-\\d+|OWASP-[A-Za-z0-9_.-]+|P3C-[A-Za-z0-9_.-]+|SEC-[A-Za-z0-9_.-]+|SAFE-[A-Za-z0-9_.-]+|RULE-[A-Za-z0-9_.-]+|CG-CODE-[A-Za-z0-9_.-]+)\\b");
    private static final Pattern ARTICLE_RULE_PATTERN = Pattern.compile("\\u7b2c\\s*[0-9\\u4e00-\\u9fa5]+\\s*\\u6761");

    private StructuredDocumentChunker() {
    }

    static List<Document> split(String sourceId, String content, Map<String, Object> baseMetadata) {
        String normalized = normalize(content);
        if (normalized.isBlank()) {
            return List.of();
        }

        List<Document> chunks = new ArrayList<>();
        for (Section section : toSections(normalized)) {
            for (Block block : toRuleBlocks(section)) {
                splitBlock(sourceId, block, baseMetadata, chunks);
            }
        }

        int total = chunks.size();
        for (Document chunk : chunks) {
            chunk.getMetadata().put("chunk_count", total);
        }
        return chunks;
    }

    private static List<Section> toSections(String content) {
        List<Section> sections = new ArrayList<>();
        List<Line> lines = linesOf(content, 0);
        List<String> headingStack = new ArrayList<>();
        String currentHeading = "";
        int sectionStart = 0;
        boolean inFence = false;

        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            boolean wasInFence = inFence;
            if (!wasInFence) {
                Heading heading = parseHeading(lines, i);
                if (heading != null) {
                    addSection(sections, currentHeading, content, sectionStart, line.start());
                    while (headingStack.size() >= heading.level()) {
                        headingStack.remove(headingStack.size() - 1);
                    }
                    headingStack.add(heading.title());
                    currentHeading = String.join(" > ", headingStack);
                    sectionStart = line.start();
                }
            }
            if (isFenceLine(line.text())) {
                inFence = !inFence;
            }
        }

        addSection(sections, currentHeading, content, sectionStart, content.length());
        if (sections.isEmpty() && !content.isBlank()) {
            sections.add(new Section("", content, 0, content.length()));
        }
        return sections;
    }

    private static List<Block> toRuleBlocks(Section section) {
        List<Block> blocks = new ArrayList<>();
        List<Line> lines = linesOf(section.text(), section.start());
        if (lines.isEmpty()) {
            blocks.add(new Block(section.headingPath(), section.text(), section.start(), section.end(), SPLIT_HEADING));
            return blocks;
        }

        int blockStart = section.start();
        boolean currentBlockIsRule = false;
        boolean sawContent = false;
        boolean inFence = false;

        for (Line line : lines) {
            boolean wasInFence = inFence;
            boolean ruleBoundary = !wasInFence && isRuleBoundary(line.text());
            if (ruleBoundary) {
                if (sawContent) {
                    String prefix = slice(section.text(), section.start(), blockStart, line.start());
                    if (currentBlockIsRule || !isHeadingOnly(prefix)) {
                        addBlock(blocks, section, blockStart, line.start(),
                                currentBlockIsRule ? SPLIT_RULE_BOUNDARY : SPLIT_HEADING);
                        blockStart = line.start();
                    }
                }
                currentBlockIsRule = true;
            }
            if (!line.text().isBlank()) {
                sawContent = true;
            }
            if (isFenceLine(line.text())) {
                inFence = !inFence;
            }
        }

        addBlock(blocks, section, blockStart, section.end(),
                currentBlockIsRule ? SPLIT_RULE_BOUNDARY : SPLIT_HEADING);
        return blocks;
    }

    private static void splitBlock(String sourceId,
                                   Block block,
                                   Map<String, Object> baseMetadata,
                                   List<Document> chunks) {
        if (countTokens(block.text()) <= HARD_MAX_TOKENS) {
            addChunk(sourceId, block, block.splitReason(), baseMetadata, chunks);
            return;
        }

        splitSemantically(sourceId, new TextSegment(block.text(), block.start(), block.end()),
                block.headingPath(), baseMetadata, chunks);
    }

    private static void splitSemantically(String sourceId,
                                          TextSegment segment,
                                          String headingPath,
                                          Map<String, Object> baseMetadata,
                                          List<Document> chunks) {
        List<TextSegment> paragraphs = paragraphSegments(segment);
        if (paragraphs.size() > 1) {
            emitGroupedSegments(sourceId, segment, paragraphs, headingPath, SPLIT_PARAGRAPH, baseMetadata, chunks);
            return;
        }

        List<TextSegment> sentences = sentenceSegments(segment);
        if (sentences.size() > 1) {
            emitGroupedSegments(sourceId, segment, sentences, headingPath, SPLIT_SENTENCE, baseMetadata, chunks);
            return;
        }

        splitByTokenWindow(sourceId, segment, headingPath, baseMetadata, chunks);
    }

    private static void emitGroupedSegments(String sourceId,
                                            TextSegment source,
                                            List<TextSegment> units,
                                            String headingPath,
                                            String splitReason,
                                            Map<String, Object> baseMetadata,
                                            List<Document> chunks) {
        int groupStart = -1;
        int groupEnd = -1;

        for (TextSegment unit : units) {
            if (countTokens(unit.text()) > HARD_MAX_TOKENS) {
                if (groupStart >= 0) {
                    addChunk(sourceId, blockFrom(source, headingPath, groupStart, groupEnd), splitReason, baseMetadata, chunks);
                    groupStart = -1;
                }
                splitSemantically(sourceId, unit, headingPath, baseMetadata, chunks);
                continue;
            }

            if (groupStart < 0) {
                groupStart = unit.start();
                groupEnd = unit.end();
                continue;
            }

            int candidateTokens = countTokens(slice(source.text(), source.start(), groupStart, unit.end()));
            if (candidateTokens > TARGET_TOKENS) {
                addChunk(sourceId, blockFrom(source, headingPath, groupStart, groupEnd), splitReason, baseMetadata, chunks);
                groupStart = unit.start();
            }
            groupEnd = unit.end();
        }

        if (groupStart >= 0) {
            addChunk(sourceId, blockFrom(source, headingPath, groupStart, groupEnd), splitReason, baseMetadata, chunks);
        }
    }

    private static void splitByTokenWindow(String sourceId,
                                           TextSegment segment,
                                           String headingPath,
                                           Map<String, Object> baseMetadata,
                                           List<Document> chunks) {
        List<TokenSpan> tokens = tokenSpans(segment.text());
        if (tokens.isEmpty()) {
            addChunk(sourceId, new Block(headingPath, segment.text(), segment.start(), segment.end(), SPLIT_TOKEN_WINDOW),
                    SPLIT_TOKEN_WINDOW, baseMetadata, chunks);
            return;
        }

        int startToken = 0;
        while (startToken < tokens.size()) {
            int endToken = Math.min(tokens.size(), startToken + TARGET_TOKENS);
            TokenSpan first = tokens.get(startToken);
            TokenSpan last = tokens.get(endToken - 1);
            int charStart = segment.start() + first.start();
            int charEnd = segment.start() + last.end();
            String text = segment.text().substring(first.start(), last.end());
            addChunk(sourceId, new Block(headingPath, text, charStart, charEnd, SPLIT_TOKEN_WINDOW),
                    SPLIT_TOKEN_WINDOW, baseMetadata, chunks);
            if (endToken == tokens.size()) {
                break;
            }
            startToken = Math.max(endToken - OVERLAP_TOKENS, startToken + 1);
        }
    }

    private static void addChunk(String sourceId,
                                 Block block,
                                 String splitReason,
                                 Map<String, Object> baseMetadata,
                                 List<Document> chunks) {
        TrimmedText trimmed = trim(block.text(), block.start());
        if (trimmed.text().isBlank()) {
            return;
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        if (baseMetadata != null) {
            metadata.putAll(baseMetadata);
        }

        int index = chunks.size();
        int tokenCount = countTokens(trimmed.text());
        String chunkKey = chunkKey(sourceId, index, trimmed.start(), trimmed.end(), trimmed.text());
        String chunkId = UUID.nameUUIDFromBytes(chunkKey.getBytes(StandardCharsets.UTF_8)).toString();

        metadata.put("source_doc_id", sourceId);
        metadata.put("document_id", sourceId);
        metadata.put("chunk_id", chunkId);
        metadata.put("chunk_key", chunkKey);
        metadata.put("chunk_index", index);
        metadata.put("chunk_strategy", CHUNK_STRATEGY);
        metadata.put("heading_path", block.headingPath() != null ? block.headingPath() : "");
        metadata.put("rule_ids", extractRuleIds(trimmed.text()));
        metadata.put("split_reason", splitReason);
        metadata.put("token_count", tokenCount);
        metadata.put("char_start", trimmed.start());
        metadata.put("char_end", trimmed.end());
        metadata.put("chunk_char_start", trimmed.start());
        metadata.put("chunk_char_end", trimmed.end());
        metadata.put("overlap_tokens", OVERLAP_TOKENS);
        metadata.put("chunk_overlap_chars", LEGACY_OVERLAP_CHARS);
        metadata.put("offset_basis", "normalized_text");

        chunks.add(new Document(chunkId, trimmed.text(), metadata));
    }

    private static void addSection(List<Section> sections,
                                   String headingPath,
                                   String content,
                                   int start,
                                   int end) {
        if (end <= start) {
            return;
        }
        String text = content.substring(start, end);
        if (!text.isBlank()) {
            sections.add(new Section(headingPath, text, start, end));
        }
    }

    private static void addBlock(List<Block> blocks, Section section, int start, int end, String splitReason) {
        if (end <= start) {
            return;
        }
        String text = slice(section.text(), section.start(), start, end);
        if (!text.isBlank()) {
            blocks.add(new Block(section.headingPath(), text, start, end, splitReason));
        }
    }

    private static Block blockFrom(TextSegment source, String headingPath, int start, int end) {
        return new Block(headingPath, slice(source.text(), source.start(), start, end), start, end, SPLIT_PARAGRAPH);
    }

    private static Heading parseHeading(List<Line> lines, int index) {
        Line line = lines.get(index);
        String text = line.text().trim();
        if (text.isBlank() || text.length() > MAX_HEADING_LENGTH || isFenceLine(text) || isRuleBoundary(text)) {
            return null;
        }

        Matcher markdown = MARKDOWN_HEADING_PATTERN.matcher(text);
        if (markdown.matches() && isLikelyHeadingTitle(markdown.group(2))) {
            return new Heading(markdown.group(1).length(), markdown.group(2).trim());
        }

        Matcher numbered = NUMBERED_HEADING_PATTERN.matcher(text);
        if (numbered.matches() && isLikelyHeadingTitle(numbered.group(2))) {
            int level = Math.min(6, numbered.group(1).split("\\.").length);
            return new Heading(level, text);
        }

        Matcher chinese = CHINESE_HEADING_PATTERN.matcher(text);
        if (chinese.matches() && isLikelyHeadingTitle(chinese.group(1))) {
            int level = text.startsWith("\uff08") || text.startsWith("(") || text.contains("\u8282") ? 2 : 1;
            return new Heading(level, text);
        }

        if (isSurroundedByBlankLines(lines, index)
                && isLikelyHeadingTitle(text)
                && (BARE_CHINESE_HEADING_PATTERN.matcher(text).matches()
                || ALL_CAPS_HEADING_PATTERN.matcher(text).matches())) {
            return new Heading(1, text);
        }
        return null;
    }

    private static boolean isSurroundedByBlankLines(List<Line> lines, int index) {
        boolean previousBlank = index == 0 || lines.get(index - 1).text().isBlank();
        boolean nextBlank = index == lines.size() - 1 || lines.get(index + 1).text().isBlank();
        return previousBlank && nextBlank;
    }

    private static boolean isLikelyHeadingTitle(String title) {
        String trimmed = title != null ? title.trim() : "";
        return !trimmed.isBlank()
                && trimmed.length() <= MAX_HEADING_LENGTH
                && !endsWithSentencePunctuation(trimmed);
    }

    private static boolean endsWithSentencePunctuation(String text) {
        if (text.isBlank()) {
            return false;
        }
        char last = text.charAt(text.length() - 1);
        return last == '.' || last == '!' || last == '?' || last == ';'
                || last == '\u3002' || last == '\uff01' || last == '\uff1f' || last == '\uff1b';
    }

    private static boolean isHeadingOnly(String text) {
        List<Line> lines = linesOf(text, 0);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).text();
            if (line.isBlank()) {
                continue;
            }
            if (parseHeading(lines, i) == null) {
                return false;
            }
        }
        return true;
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
        Matcher articleMatcher = ARTICLE_RULE_PATTERN.matcher(text);
        while (articleMatcher.find()) {
            ids.add(articleMatcher.group().replaceAll("\\s+", ""));
        }
        return new ArrayList<>(ids);
    }

    private static List<TextSegment> paragraphSegments(TextSegment segment) {
        List<TextSegment> paragraphs = new ArrayList<>();
        List<Line> lines = linesOf(segment.text(), segment.start());
        int paragraphStart = -1;
        int paragraphEnd = -1;

        for (Line line : lines) {
            if (line.text().isBlank()) {
                if (paragraphStart >= 0) {
                    paragraphs.add(segmentFrom(segment, paragraphStart, paragraphEnd));
                    paragraphStart = -1;
                }
                continue;
            }
            if (paragraphStart < 0) {
                paragraphStart = line.start();
            }
            paragraphEnd = line.end();
        }

        if (paragraphStart >= 0) {
            paragraphs.add(segmentFrom(segment, paragraphStart, paragraphEnd));
        }
        if (paragraphs.isEmpty() && !segment.text().isBlank()) {
            paragraphs.add(segment);
        }
        return paragraphs;
    }

    private static List<TextSegment> sentenceSegments(TextSegment segment) {
        List<TextSegment> sentences = new ArrayList<>();
        int sentenceStart = 0;
        for (int i = 0; i < segment.text().length(); i++) {
            char current = segment.text().charAt(i);
            if (isSentenceBoundary(current)) {
                addSentence(sentences, segment, sentenceStart, i + 1);
                sentenceStart = i + 1;
            }
        }
        addSentence(sentences, segment, sentenceStart, segment.text().length());
        if (sentences.isEmpty() && !segment.text().isBlank()) {
            sentences.add(segment);
        }
        return sentences;
    }

    private static void addSentence(List<TextSegment> sentences, TextSegment source, int localStart, int localEnd) {
        if (localEnd <= localStart) {
            return;
        }
        String text = source.text().substring(localStart, localEnd);
        if (!text.isBlank()) {
            sentences.add(new TextSegment(text, source.start() + localStart, source.start() + localEnd));
        }
    }

    private static boolean isSentenceBoundary(char current) {
        return current == '.' || current == '!' || current == '?' || current == ';' || current == ':'
                || current == '\u3002' || current == '\uff01' || current == '\uff1f'
                || current == '\uff1b' || current == '\uff1a';
    }

    private static TextSegment segmentFrom(TextSegment source, int start, int end) {
        return new TextSegment(slice(source.text(), source.start(), start, end), start, end);
    }

    private static List<TokenSpan> tokenSpans(String text) {
        List<TokenSpan> tokens = new ArrayList<>();
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            int charCount = Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)) {
                index += charCount;
                continue;
            }
            if (isCjk(codePoint)) {
                tokens.add(new TokenSpan(index, index + charCount));
                index += charCount;
                continue;
            }
            if (Character.isLetterOrDigit(codePoint) || codePoint == '_' || codePoint == '-') {
                int start = index;
                index += charCount;
                while (index < text.length()) {
                    int next = text.codePointAt(index);
                    if (!(Character.isLetterOrDigit(next) || next == '_' || next == '-')) {
                        break;
                    }
                    index += Character.charCount(next);
                }
                tokens.add(new TokenSpan(start, index));
                continue;
            }
            index += charCount;
        }
        return tokens;
    }

    private static int countTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int count = tokenSpans(text).size();
        return count > 0 ? count : 1;
    }

    private static boolean isCjk(int codePoint) {
        return (codePoint >= 0x4E00 && codePoint <= 0x9FFF)
                || (codePoint >= 0x3400 && codePoint <= 0x4DBF)
                || (codePoint >= 0x20000 && codePoint <= 0x2A6DF);
    }

    private static List<Line> linesOf(String text, int baseStart) {
        List<Line> lines = new ArrayList<>();
        int cursor = 0;
        while (cursor < text.length()) {
            int newline = text.indexOf('\n', cursor);
            int lineEnd = newline >= 0 ? newline : text.length();
            int endWithBreak = newline >= 0 ? newline + 1 : text.length();
            lines.add(new Line(text.substring(cursor, lineEnd), baseStart + cursor, baseStart + endWithBreak));
            cursor = endWithBreak;
        }
        return lines;
    }

    private static String slice(String source, int sourceStart, int start, int end) {
        int localStart = Math.max(0, start - sourceStart);
        int localEnd = Math.min(source.length(), end - sourceStart);
        if (localEnd <= localStart) {
            return "";
        }
        return source.substring(localStart, localEnd);
    }

    private static TrimmedText trim(String text, int start) {
        int left = 0;
        int right = text != null ? text.length() : 0;
        while (left < right && Character.isWhitespace(text.charAt(left))) {
            left++;
        }
        while (right > left && Character.isWhitespace(text.charAt(right - 1))) {
            right--;
        }
        String trimmed = text != null ? text.substring(left, right) : "";
        return new TrimmedText(trimmed, start + left, start + right);
    }

    private static String chunkKey(String sourceId, int index, int charStart, int charEnd, String text) {
        String sourceKey = sourceId != null && !sourceId.isBlank() ? sourceId : "anonymous";
        return sourceKey + "::chunk-" + (index + 1) + ":" + charStart + ":" + charEnd + ":"
                + Integer.toHexString(text.hashCode());
    }

    private static boolean isFenceLine(String line) {
        String trimmed = line != null ? line.trim() : "";
        return trimmed.startsWith("```") || trimmed.startsWith("~~~");
    }

    private static String normalize(String content) {
        return (content != null ? content : "")
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private record Section(String headingPath, String text, int start, int end) {
    }

    private record Block(String headingPath, String text, int start, int end, String splitReason) {
    }

    private record TextSegment(String text, int start, int end) {
    }

    private record Line(String text, int start, int end) {
    }

    private record Heading(int level, String title) {
    }

    private record TokenSpan(int start, int end) {
    }

    private record TrimmedText(String text, int start, int end) {
    }
}
