package com.codeguardian.service;

import com.codeguardian.entity.Finding;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class ReviewFindingPolicy {

    private ReviewFindingPolicy() {
    }

    static List<Finding> mergeSeedFindings(List<Finding> aiFindings, List<Finding> seedFindings) {
        List<Finding> merged = new ArrayList<>();
        if (aiFindings != null) {
            merged.addAll(aiFindings);
        }
        if (seedFindings == null || seedFindings.isEmpty()) {
            return merged;
        }
        for (Finding seed : seedFindings) {
            if (seed != null && !isDuplicate(seed, merged)) {
                merged.add(seed);
            }
        }
        return merged;
    }

    static boolean isCategoryEnabled(String findingCategory, Map<String, Boolean> configCategories) {
        if (findingCategory == null) return true;
        String code = findingCategory;
        String key = "style";
        if ("SECURITY".equals(code)) key = "security";
        else if ("PERFORMANCE".equals(code)) key = "performance";
        else if ("BUG".equals(code)) key = "logic_error";
        else if ("MAINTAINABILITY".equals(code)) key = "maintainability";
        return configCategories.getOrDefault(key, true);
    }

    private static boolean isDuplicate(Finding newFinding, List<Finding> existingFindings) {
        if (existingFindings == null || existingFindings.isEmpty()) return false;
        for (Finding existing : existingFindings) {
            if (Objects.equals(existing.getStartLine(), newFinding.getStartLine())) {
                if (existing.getCategory() != null && newFinding.getCategory() != null &&
                        existing.getCategory().equals(newFinding.getCategory())) {
                    return true;
                }
            }
        }
        return false;
    }
}
