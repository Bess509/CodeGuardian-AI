package com.codeguardian.service;

import java.util.List;

public record RagQuery(String text,
                       String strategy,
                       List<Integer> targetLines,
                       List<String> riskKeywords,
                       List<String> ruleCategories) {
}
