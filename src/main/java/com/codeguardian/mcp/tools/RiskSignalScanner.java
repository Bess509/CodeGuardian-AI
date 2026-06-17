package com.codeguardian.mcp.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class RiskSignalScanner {

    private static final Map<String, List<String>> KEYWORDS = Map.of(
            "credential", List.of("password", "passwd", "secret", "token", "apikey", "api_key"),
            "sql", List.of("select ", "insert ", "update ", "delete ", "executequery", "executeupdate"),
            "command", List.of("runtime.getruntime", "processbuilder", "exec(", "system("),
            "dynamic_code", List.of("eval(", "new function", "classloader"),
            "path", List.of("../", "normalize(", "resolve(")
    );

    List<Map<String, Object>> scan(List<String> lines, int maxSignals) {
        List<Map<String, Object>> signals = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : KEYWORDS.entrySet()) {
            collectSignals(lines, maxSignals, signals, entry);
            if (signals.size() >= maxSignals) {
                return signals;
            }
        }
        return signals;
    }

    private void collectSignals(List<String> lines,
                                int maxSignals,
                                List<Map<String, Object>> signals,
                                Map.Entry<String, List<String>> entry) {
        for (int i = 0; i < lines.size(); i++) {
            String lower = lines.get(i).toLowerCase(java.util.Locale.ROOT);
            for (String keyword : entry.getValue()) {
                if (lower.contains(keyword)) {
                    signals.add(Map.of(
                            "type", entry.getKey(),
                            "line", i + 1,
                            "keyword", keyword
                    ));
                    break;
                }
            }
            if (signals.size() >= maxSignals) {
                return;
            }
        }
    }
}
