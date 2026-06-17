package com.codeguardian.service.provenance;

import com.codeguardian.dto.ReviewRuntimeManifestDTO;
import com.codeguardian.dto.ReviewTraceGraphDTO;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ReviewTraceGraphSupport {

    private ReviewTraceGraphSupport() {
    }

    static ReviewTraceGraphDTO.Node node(String id,
                                         String type,
                                         String label,
                                         String status,
                                         String hash,
                                         Map<String, Object> metadata) {
        return ReviewTraceGraphDTO.Node.builder()
                .id(id)
                .type(type)
                .label(label)
                .status(status)
                .hash(hash)
                .metadata(metadata)
                .build();
    }

    static ReviewTraceGraphDTO.Edge edge(String id,
                                         String source,
                                         String target,
                                         String type,
                                         String label,
                                         Map<String, Object> metadata) {
        return ReviewTraceGraphDTO.Edge.builder()
                .id(id)
                .source(source)
                .target(target)
                .type(type)
                .label(label)
                .metadata(metadata)
                .build();
    }

    static Map<String, Object> mapOf(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) {
            map.put(String.valueOf(values[i]), values[i + 1]);
        }
        return map;
    }

    static int size(List<?> values) {
        return values != null ? values.size() : 0;
    }

    static Boolean databaseAppendOnlyGuardsInstalled(ReviewRuntimeManifestDTO manifest) {
        return manifest != null && manifest.getDatabaseGuards() != null
                ? manifest.getDatabaseGuards().getAppendOnlyGuardsInstalled()
                : null;
    }

    static String taskId(Long id) {
        return "task:" + id;
    }

    static String findingNodeId(Long id) {
        return "finding:" + id;
    }

    static String evidenceNodeId(Long id) {
        return "evidence:" + id;
    }

    static String auditNodeId(Long id) {
        return "audit:" + id;
    }

    static String violationNodeId(Long findingId, int index) {
        return "grounding-violation:" + (findingId != null ? findingId : index);
    }

    static String nonBlank(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    static String nullToEmpty(String value) {
        return value != null ? value : "";
    }
}
