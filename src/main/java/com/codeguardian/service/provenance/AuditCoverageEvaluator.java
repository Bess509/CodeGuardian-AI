package com.codeguardian.service.provenance;

import com.codeguardian.entity.ReviewAuditEvent;
import com.codeguardian.enums.TaskStatusEnum;
import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

final class AuditCoverageEvaluator {

    AuditCoverageResult evaluate(List<ReviewAuditEvent> events, Integer taskStatus) {
        if (taskStatus == null) {
            return AuditCoverageResult.builder()
                    .valid(true)
                    .missingEventTypes(List.of())
                    .terminalEventConsistent(true)
                    .orderValid(true)
                    .orderViolations(List.of())
                    .build();
        }
        Set<String> eventTypes = events.stream()
                .map(ReviewAuditEvent::getEventType)
                .collect(Collectors.toSet());
        TaskStatusEnum status = TaskStatusEnum.fromValue(taskStatus);
        List<String> required = new ArrayList<>();
        required.add("TASK_CREATED");
        if (status == TaskStatusEnum.RUNNING
                || status == TaskStatusEnum.COMPLETED
                || status == TaskStatusEnum.FAILED) {
            required.add("TASK_STARTED");
        }
        if (status == TaskStatusEnum.COMPLETED) {
            boolean noAnalyzableFiles = eventTypes.contains("NO_ANALYZABLE_FILES");
            if (noAnalyzableFiles) {
                required.add("REVIEW_SCOPE_SCANNED");
            } else {
                required.add("REVIEW_STRATEGY_STARTED");
                required.add("REVIEW_STRATEGY_COMPLETED");
            }
            if (eventTypes.contains("REVIEW_SCOPE_SCANNED")) {
                required.add("REVIEW_BATCH_COMPLETED");
            }
            required.add("FINDINGS_SAVED");
            required.add("TASK_COMPLETED");
        } else if (status == TaskStatusEnum.FAILED) {
            required.add("TASK_FAILED");
        }

        List<String> missing = required.stream()
                .filter(requiredType -> !eventTypes.contains(requiredType))
                .toList();
        long terminalEventCount = events.stream()
                .filter(event -> "TASK_COMPLETED".equals(event.getEventType())
                        || "TASK_FAILED".equals(event.getEventType()))
                .count();
        boolean terminalConsistent = terminalEventCount <= 1;
        if (status == TaskStatusEnum.COMPLETED) {
            terminalConsistent = terminalConsistent
                    && eventTypes.contains("TASK_COMPLETED")
                    && !eventTypes.contains("TASK_FAILED");
        } else if (status == TaskStatusEnum.FAILED) {
            terminalConsistent = terminalConsistent
                    && eventTypes.contains("TASK_FAILED")
                    && !eventTypes.contains("TASK_COMPLETED");
        } else {
            terminalConsistent = terminalConsistent
                    && !eventTypes.contains("TASK_COMPLETED")
                    && !eventTypes.contains("TASK_FAILED");
        }
        List<String> orderViolations = evaluateOrder(events, status, eventTypes);
        boolean orderValid = orderViolations.isEmpty();

        return AuditCoverageResult.builder()
                .valid(missing.isEmpty() && terminalConsistent && orderValid)
                .missingEventTypes(missing)
                .terminalEventConsistent(terminalConsistent)
                .orderValid(orderValid)
                .orderViolations(orderViolations)
                .build();
    }

    private List<String> evaluateOrder(List<ReviewAuditEvent> events, TaskStatusEnum status, Set<String> eventTypes) {
        if (events == null || events.isEmpty() || status == null) {
            return List.of();
        }
        List<String> violations = new ArrayList<>();
        requireBefore(events, violations, "TASK_CREATED", "TASK_STARTED");
        if (status == TaskStatusEnum.COMPLETED) {
            if (eventTypes.contains("NO_ANALYZABLE_FILES")) {
                requireBefore(events, violations, "TASK_STARTED", "REVIEW_SCOPE_SCANNED");
                requireBefore(events, violations, "REVIEW_SCOPE_SCANNED", "NO_ANALYZABLE_FILES");
                requireBefore(events, violations, "NO_ANALYZABLE_FILES", "FINDINGS_SAVED");
            } else {
                requireBefore(events, violations, "TASK_STARTED", "REVIEW_STRATEGY_STARTED");
                requireBefore(events, violations, "REVIEW_STRATEGY_STARTED", "REVIEW_STRATEGY_COMPLETED");
                requireBefore(events, violations, "REVIEW_STRATEGY_COMPLETED", "FINDINGS_SAVED");
            }
            if (eventTypes.contains("REVIEW_BATCH_COMPLETED")) {
                requireBefore(events, violations, "FINDINGS_SAVED", "REVIEW_BATCH_COMPLETED");
                requireBefore(events, violations, "REVIEW_BATCH_COMPLETED", "TASK_COMPLETED");
            } else {
                requireBefore(events, violations, "FINDINGS_SAVED", "TASK_COMPLETED");
            }
            requireTerminalLast(events, violations, "TASK_COMPLETED");
        } else if (status == TaskStatusEnum.FAILED) {
            requireBefore(events, violations, "TASK_STARTED", "TASK_FAILED");
            requireTerminalLast(events, violations, "TASK_FAILED");
        }
        return violations;
    }

    private void requireBefore(List<ReviewAuditEvent> events, List<String> violations, String before, String after) {
        int beforeIndex = firstIndex(events, before);
        int afterIndex = firstIndex(events, after);
        if (beforeIndex >= 0 && afterIndex >= 0 && beforeIndex > afterIndex) {
            violations.add(before + "_after_" + after);
        }
    }

    private void requireTerminalLast(List<ReviewAuditEvent> events, List<String> violations, String terminalType) {
        int terminalIndex = firstIndex(events, terminalType);
        if (terminalIndex >= 0 && terminalIndex != events.size() - 1) {
            violations.add(terminalType + "_not_last");
        }
    }

    private int firstIndex(List<ReviewAuditEvent> events, String eventType) {
        for (int i = 0; i < events.size(); i++) {
            if (eventType.equals(events.get(i).getEventType())) {
                return i;
            }
        }
        return -1;
    }

    @Data
    @Builder
    static class AuditCoverageResult {
        private boolean valid;
        private List<String> missingEventTypes;
        private boolean terminalEventConsistent;
        private boolean orderValid;
        private List<String> orderViolations;
    }
}
