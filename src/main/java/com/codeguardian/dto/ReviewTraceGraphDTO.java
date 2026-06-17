package com.codeguardian.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewTraceGraphDTO {
    private String graphVersion;
    private Long taskId;
    private String reviewStateHash;
    private String bundleHash;
    private Summary summary;
    private List<Node> nodes;
    private List<Edge> edges;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Summary {
        private Integer nodeCount;
        private Integer edgeCount;
        private Integer findingCount;
        private Integer evidenceCount;
        private Integer auditEventCount;
        private Integer groundedFindingCount;
        private Integer groundingViolationCount;
        private Integer groundingViolationNodeCount;
        private Integer invalidSourceAnchorCount;
        private Boolean auditChainValid;
        private Boolean auditCoverageValid;
        private Boolean auditOrderValid;
        private Integer auditOrderViolationCount;
        private Boolean evidenceHashValid;
        private Boolean reviewStateHashValid;
        private Boolean runtimeManifestHashValid;
        private Boolean groundingPolicyValid;
        private Boolean proofBundleValid;
        private Boolean currentStateMatch;
        private Boolean currentReviewStateMatch;
        private Boolean currentRuntimeManifestMatch;
        private Boolean currentBundleMatch;
        private Boolean databaseAppendOnlyGuardsInstalled;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Node {
        private String id;
        private String type;
        private String label;
        private String status;
        private String hash;
        private Map<String, Object> metadata;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Edge {
        private String id;
        private String source;
        private String target;
        private String type;
        private String label;
        private Map<String, Object> metadata;
    }
}
