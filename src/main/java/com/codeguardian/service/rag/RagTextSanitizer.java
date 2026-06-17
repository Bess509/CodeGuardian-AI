package com.codeguardian.service.rag;

class RagTextSanitizer {

    private RagTextSanitizer() {
    }

    static String clean(String text) {
        if (text == null) {
            return "";
        }
        String[] lines = text.split("\r?\n");
        StringBuilder sb = new StringBuilder();
        boolean prevBlank = false;
        for (String line : lines) {
            String normalized = line.replace('\u00A0', ' ');
            String trimmed = normalized.trim();
            if (shouldDrop(trimmed)) {
                continue;
            }
            if (trimmed.isEmpty()) {
                if (!prevBlank) {
                    sb.append('\n');
                    prevBlank = true;
                }
            } else {
                sb.append(normalized).append('\n');
                prevBlank = false;
            }
        }
        return sb.toString().trim();
    }

    private static boolean shouldDrop(String trimmed) {
        boolean isPageNum = trimmed.matches("^\\d+\\s*/\\s*\\d+$");
        boolean isDashBanner = trimmed.matches("^閳ユ敒2,}.*閳ユ敒2,}$");
        boolean containsCopyright = trimmed.contains("commercial use prohibited") || trimmed.contains("rights reserved");
        boolean isHeader = trimmed.contains("Alibaba") && trimmed.contains("Java") && trimmed.contains("manual");
        return isPageNum || isDashBanner || containsCopyright || isHeader;
    }
}
