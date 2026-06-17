package com.codeguardian.controller;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ReportPdfHtmlPreparer {

    private ReportPdfHtmlPreparer() {
    }

    static String prepare(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        html = html.replaceAll("(?is)<script[^>]*>.*?</script>", "");
        html = html.replaceAll("(?is)<link[^>]*/?>", "");
        html = cleanMarkdownInHtml(html);
        html = escapeAmpersands(html);

        html = html.replace(
                "*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font-family:-apple-system,BlinkMacSystemFont,\"Segoe UI\",Helvetica,Arial,sans-serif}",
                "*{box-sizing:border-box}body{margin:0;background:#0d1117;color:#c9d1d9;font-family:ArialUnicode,sans-serif}"
        );
        html = html.replaceAll("<a class=\"back\"[^>]*>.*?</a>", "");
        html = html.replace("<div class=\"table-hd\"><div>标题</div>", "<div class=\"table-hd\"><div></div>");
        html = html.replace("<div class=\"table-hd\"><div>鏍囬</div>", "<div class=\"table-hd\"><div></div>");
        html = replaceCssVariables(html);
        html = html.replace("</style>", "</style>\n" + prismStyles());
        html = removeFontAwesomeIcons(html);
        return html.replaceAll("(?i)<(meta|img|br|hr|input|area|base|col|embed|source|track|wbr)([^>]*?)(?<!\\s/)(?<!/)>", "<$1$2 />");
    }

    static String cleanMarkdownInHtml(String html) {
        if (html == null || html.isEmpty()) {
            return html;
        }
        for (int i = 0; i < 5; i++) {
            html = html.replaceAll("(>)(\\s*)#{1,6}(?:[:\\s]*)", "$1$2");
            html = html.replaceAll("(>)([^<]*?)\\*\\*([^*]+?)\\*\\*([^<]*?)(<)", "$1$2$3$4$5");
            html = html.replaceAll("(>)([^<]*?)`([^`]+?)`([^<]*?)(<)", "$1$2$3$4$5");
            html = html.replaceAll("(>)([^<]*?)\\[([^\\]]+?)\\]\\([^\\)]+\\)([^<]*?)(<)", "$1$2$3$4$5");
            html = html.replaceAll("(>)([^<]*?)([-*+])(\\s+)([^<]*?)(<)", "$1$2$5$6");
            html = html.replaceAll("(>)([^<]*?)(>)(\\s+)([^<]*?)(<)", "$1$2$5$6");
            html = html.replaceAll("(>)([^<]*?)(#{1,6})([^<]*?)(<)", "$1$2$4$5");
        }
        html = html.replaceAll("(>)\\s*#+[:\\s]*", "$1");
        html = html.replaceAll("(</span>\\s*)#+[:\\s]*", "$1");
        for (int i = 0; i < 3; i++) {
            html = html.replaceAll("(>)([^<]*?)(#{1,})([^<]*?)(<)", "$1$2$4$5");
        }
        return html;
    }

    private static String replaceCssVariables(String html) {
        return html.replaceAll("var\\(--bg\\)", "#0d1117")
                .replaceAll("var\\(--card\\)", "#161b22")
                .replaceAll("var\\(--text\\)", "#c9d1d9")
                .replaceAll("var\\(--text2\\)", "#8b949e")
                .replaceAll("var\\(--border\\)", "#30363d")
                .replaceAll("var\\(--primary\\)", "#58a6ff")
                .replaceAll("var\\(--critical\\)", "#f44336")
                .replaceAll("var\\(--high\\)", "#ff9800")
                .replaceAll("var\\(--medium\\)", "#ffc107")
                .replaceAll("var\\(--low\\)", "#4caf50")
                .replaceAll("var\\(--editor-bg\\)", "#0d1117")
                .replaceAll("var\\(--editor-line-number\\)", "#6e7681")
                .replaceAll("var\\(--editor-text\\)", "#c9d1d9")
                .replaceAll("var\\(--editor-padding\\)", "20px");
    }

    private static String removeFontAwesomeIcons(String html) {
        html = html.replaceAll("(?i)<i\\b[^>]*class=\"[^\"]*\\bfa-arrow-left\\b[^\"]*\"[^>]*></i>", "");
        html = html.replaceAll("(?i)<i\\b[^>]*class=\"[^\"]*\\bfa-shield-alt\\b[^\"]*\"[^>]*></i>", "");
        html = html.replaceAll("(?i)<i\\b[^>]*class=\"[^\"]*\\bfa-check\\b[^\"]*\"[^>]*></i>", "");
        html = html.replaceAll("(?i)<i\\b[^>]*class=\"[^\"]*\\bfa-bug\\b[^\"]*\"[^>]*></i>", "");
        html = html.replaceAll("(?i)<i\\b[^>]*class=\"[^\"]*\\bfa-cog\\b[^\"]*\"[^>]*></i>", "");
        html = html.replaceAll("(?i)<i\\b[^>]*class=\"[^\"]*\\bfa-chart-bar\\b[^\"]*\"[^>]*></i>", "");
        return html.replaceAll("(?i)<i\\b[^>]*class=\"[^\"]*\\bfas\\b[^\"]*\"[^>]*></i>", "");
    }

    private static String escapeAmpersands(String html) {
        Map<String, String> entityMap = new HashMap<>();
        int placeholderIndex = 0;
        Pattern entityPattern = Pattern.compile("&(?:[a-zA-Z]+|#[0-9]+|#x[0-9a-fA-F]+);");
        Matcher entityMatcher = entityPattern.matcher(html);
        StringBuffer protectedHtml = new StringBuffer();

        while (entityMatcher.find()) {
            String entity = entityMatcher.group();
            String placeholder = "___ENTITY_" + placeholderIndex++ + "___";
            entityMap.put(placeholder, entity);
            entityMatcher.appendReplacement(protectedHtml, Matcher.quoteReplacement(placeholder));
        }
        entityMatcher.appendTail(protectedHtml);

        String escapedHtml = protectedHtml.toString().replace("&", "&amp;");
        for (Map.Entry<String, String> entry : entityMap.entrySet()) {
            escapedHtml = escapedHtml.replace(entry.getKey(), entry.getValue());
        }
        return escapedHtml;
    }

    private static String prismStyles() {
        return """
                <style>
                code[class*="language-"], pre[class*="language-"] {
                  color: #c9d1d9;
                  background: #0d1117;
                  text-shadow: none;
                  font-family: 'Monaco', 'Menlo', 'ArialUnicode', monospace;
                  font-size: 12px;
                  line-height: 1.6;
                }
                .code-editor-container {
                  display: table;
                  width: 100%;
                  max-width: 100%;
                  table-layout: fixed;
                  background: #0d1117;
                  border-collapse: collapse;
                  overflow-x: auto;
                  overflow-y: visible;
                  box-sizing: border-box;
                }
                .line-numbers {
                  display: table-cell;
                  vertical-align: top;
                  width: 50px;
                  padding: 0 8px 0 0;
                  text-align: right;
                  border-right: 1px solid #30363d;
                  font-family: 'Monaco', 'Menlo', 'ArialUnicode', monospace;
                  font-size: 12px;
                  line-height: 19.2px;
                  color: #6e7681;
                  background-color: #0d1117;
                  white-space: pre;
                  box-sizing: border-box;
                }
                .code-editor-pre {
                  display: table-cell;
                  vertical-align: top;
                  margin: 0;
                  padding: 0;
                  font-family: 'Monaco', 'Menlo', 'ArialUnicode', monospace;
                  font-size: 12px;
                  line-height: 19.2px;
                  background-color: transparent;
                  white-space: pre;
                  overflow-x: auto;
                  overflow-y: hidden;
                  box-sizing: border-box;
                  width: auto;
                  max-width: 100%;
                }
                .code-editor-pre code {
                  display: block;
                  margin: 0;
                  padding: 0;
                  font-size: 12px;
                  line-height: 19.2px;
                  font-family: inherit;
                  color: #c9d1d9;
                  background: transparent;
                }
                .code-editor-pre code span,
                .code-editor-pre code .token {
                  display: inline;
                  font-size: 12px;
                  line-height: 19.2px;
                  vertical-align: baseline;
                  margin: 0;
                  padding: 0;
                }
                .code-editor-wrapper {
                  display: block;
                  width: 100%;
                  max-width: 100%;
                  overflow-x: auto;
                  overflow-y: visible;
                  background: #0d1117;
                  page-break-inside: avoid;
                  break-inside: avoid;
                  box-sizing: border-box;
                }
                .config-panel {
                  page-break-inside: avoid;
                  -fs-page-break-inside: avoid;
                  page-break-after: avoid;
                  -fs-page-break-after: avoid;
                  display: inline-block;
                  width: 100%;
                  overflow: visible;
                  margin-bottom: 12px;
                }
                .config-panel .panel-bd {
                  min-height: initial;
                  max-height: 40px;
                  overflow: hidden;
                  padding: 4px 12px;
                  flex: 0 0 auto;
                }
                .config-panel .panel-hd {
                  padding: 8px 18px;
                  font-size: 13px;
                }
                .config-panel .panel-bd .muted {
                  font-size: 11px;
                  line-height: 1.2;
                  margin: 0;
                  padding: 0;
                }
                .panel.code-panel,
                .panel.table {
                  page-break-before: always;
                  break-before: page;
                }
                .panel.code-panel .panel-bd {
                  min-height: 400px;
                  max-height: 500px;
                  overflow-y: auto;
                }
                .grid { gap: 8px; margin-top: 8px; }
                .overview-row { gap: 12px; }
                .stats { gap: 8px; }
                .token.comment, .token.prolog, .token.doctype, .token.cdata { color: #8b949e; }
                .token.punctuation { color: #c9d1d9; }
                .token.property, .token.tag, .token.boolean, .token.number, .token.constant, .token.symbol, .token.deleted { color: #79c0ff; }
                .token.selector, .token.attr-name, .token.string, .token.char, .token.builtin, .token.inserted { color: #a5d6ff; }
                .token.operator, .token.entity, .token.url, .language-css .token.string, .style .token.string { color: #ff7b72; }
                .token.atrule, .token.attr-value, .token.keyword { color: #ff7b72; }
                .token.function, .token.class-name { color: #d2a8ff; }
                .token.regex, .token.important, .token.variable { color: #ffa657; }
                </style>
                """;
    }
}
