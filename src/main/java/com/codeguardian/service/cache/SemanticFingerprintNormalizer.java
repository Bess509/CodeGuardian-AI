package com.codeguardian.service.cache;

import java.util.Set;
import java.util.regex.Pattern;

final class SemanticFingerprintNormalizer {

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT_SLASH = Pattern.compile("(?m)//.*?$");
    private static final Pattern LINE_COMMENT_HASH = Pattern.compile("(?m)#.*?$");
    private static final Pattern STRING_LITERAL = Pattern.compile("\"([^\"\\\\]|\\\\.)*\"|'([^'\\\\]|\\\\.)*'");
    private static final Pattern NUMBER_LITERAL = Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b");
    private static final Pattern IDENTIFIER = Pattern.compile("\\b[A-Za-z_][A-Za-z0-9_]*\\b");
    private static final Set<String> KEYWORDS = Set.of(
            "if", "else", "for", "while", "do", "switch", "case", "default", "break", "continue", "return", "try", "catch", "finally",
            "throw", "throws", "new", "class", "interface", "enum", "extends", "implements", "public", "private", "protected", "static",
            "final", "void", "int", "long", "float", "double", "boolean", "char", "byte", "short", "null", "true", "false", "this",
            "super", "import", "package", "var", "let", "const", "function", "async", "await", "yield", "def", "lambda", "with", "as",
            "from", "in", "and", "or", "not", "pass", "raise"
    );

    private SemanticFingerprintNormalizer() {
    }

    static String normalize(String code, String language) {
        if (code == null || code.isBlank()) {
            return "";
        }

        String s = code;
        s = BLOCK_COMMENT.matcher(s).replaceAll(" ");
        s = LINE_COMMENT_SLASH.matcher(s).replaceAll(" ");
        s = LINE_COMMENT_HASH.matcher(s).replaceAll(" ");
        s = STRING_LITERAL.matcher(s).replaceAll(" STR ");
        s = NUMBER_LITERAL.matcher(s).replaceAll(" NUM ");

        s = IDENTIFIER.matcher(s).replaceAll(mr -> {
            String token = mr.group();
            String lower = token.toLowerCase();
            if (KEYWORDS.contains(lower)) {
                return lower;
            }
            return "id";
        });

        s = s.replaceAll("\\s+", " ").trim();
        return (language != null ? language : "unknown") + ":" + s;
    }
}
