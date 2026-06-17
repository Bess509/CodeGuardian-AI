package com.codeguardian.service.provenance;

import com.codeguardian.config.AuditSigningProperties;
import com.codeguardian.entity.ReviewAuditEvent;
import com.codeguardian.repository.ReviewAuditEventRepository;
import lombok.Builder;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

@Service
public class ReviewAuditService {

    private static final String GENESIS_HASH = "0".repeat(64);

    private final ReviewAuditEventRepository auditEventRepository;
    private final ProvenanceHashService hashService;
    private final AuditSigningProperties signingProperties;

    @Autowired
    public ReviewAuditService(ReviewAuditEventRepository auditEventRepository,
                              ProvenanceHashService hashService,
                              AuditSigningProperties signingProperties) {
        this.auditEventRepository = auditEventRepository;
        this.hashService = hashService;
        this.signingProperties = signingProperties != null ? signingProperties : new AuditSigningProperties();
    }

    public ReviewAuditService(ReviewAuditEventRepository auditEventRepository,
                              ProvenanceHashService hashService) {
        this(auditEventRepository, hashService, new AuditSigningProperties());
    }

    public ReviewAuditEvent record(Long taskId,
                                   String eventType,
                                   String stage,
                                   String actor,
                                   String message,
                                   Map<String, Object> metadata) {
        if (taskId == null) {
            return null;
        }
        Map<String, Object> safeMetadata = metadata != null ? metadata : Map.of();
        String previousHash = auditEventRepository.findTopByTaskIdOrderByIdDesc(taskId)
                .map(ReviewAuditEvent::getEventHash)
                .orElse(GENESIS_HASH);
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        String payloadHash = hashService.hashPayload(safeMetadata);
        String eventHash = hashService.hashAuditEvent(
                taskId,
                eventType,
                stage,
                actor,
                message,
                payloadHash,
                previousHash,
                now
        );
        String signature = null;
        String signatureKeyId = null;
        String signatureAlgorithm = null;
        if (signingProperties.isActive()) {
            signatureKeyId = signingProperties.getKeyId();
            signatureAlgorithm = signingProperties.getAlgorithm();
            signature = hashService.hmacSha256Hex(signaturePayload(eventHash, signatureKeyId), signingProperties.getSecret());
        }

        ReviewAuditEvent event = ReviewAuditEvent.builder()
                .taskId(taskId)
                .eventType(eventType)
                .stage(stage)
                .actor(actor)
                .message(message)
                .metadata(safeMetadata)
                .payloadHash(payloadHash)
                .previousHash(previousHash)
                .eventHash(eventHash)
                .signatureKeyId(signatureKeyId)
                .signatureAlgorithm(signatureAlgorithm)
                .eventSignature(signature)
                .createdAt(now)
                .build();
        return auditEventRepository.save(event);
    }

    public IntegrityResult verifyTaskChain(Long taskId) {
        return verifyTaskChain(taskId, null);
    }

    public IntegrityResult verifyTaskChain(Long taskId, Integer taskStatus) {
        List<ReviewAuditEvent> events = auditEventRepository.findByTaskIdOrderByIdAsc(taskId);
        String expectedPrevious = GENESIS_HASH;
        int signedEventCount = 0;
        for (ReviewAuditEvent event : events) {
            String payloadHash = hashService.hashPayload(event.getMetadata() != null ? event.getMetadata() : Map.of());
            String eventHash = hashService.hashAuditEvent(
                    event.getTaskId(),
                    event.getEventType(),
                    event.getStage(),
                    event.getActor(),
                    event.getMessage(),
                    payloadHash,
                    event.getPreviousHash(),
                    event.getCreatedAt()
            );
            if (!expectedPrevious.equals(event.getPreviousHash())) {
                return IntegrityResult.builder()
                        .valid(false)
                        .eventCount(events.size())
                        .failedEventId(event.getId())
                        .reason("previous_hash_mismatch")
                        .lastHash(expectedPrevious)
                        .signatureValid(!signingProperties.isActive())
                        .signedEventCount(signedEventCount)
                        .signatureKeyId(signingProperties.getKeyId())
                        .auditCoverageValid(false)
                        .missingEventTypes(List.of())
                        .terminalEventConsistent(false)
                        .auditOrderValid(false)
                        .auditOrderViolations(List.of())
                        .build();
            }
            if (!payloadHash.equals(event.getPayloadHash())) {
                return IntegrityResult.builder()
                        .valid(false)
                        .eventCount(events.size())
                        .failedEventId(event.getId())
                        .reason("payload_hash_mismatch")
                        .lastHash(expectedPrevious)
                        .signatureValid(!signingProperties.isActive())
                        .signedEventCount(signedEventCount)
                        .signatureKeyId(signingProperties.getKeyId())
                        .auditCoverageValid(false)
                        .missingEventTypes(List.of())
                        .terminalEventConsistent(false)
                        .auditOrderValid(false)
                        .auditOrderViolations(List.of())
                        .build();
            }
            if (!eventHash.equals(event.getEventHash())) {
                return IntegrityResult.builder()
                        .valid(false)
                        .eventCount(events.size())
                        .failedEventId(event.getId())
                        .reason("event_hash_mismatch")
                        .lastHash(expectedPrevious)
                        .signatureValid(!signingProperties.isActive())
                        .signedEventCount(signedEventCount)
                        .signatureKeyId(signingProperties.getKeyId())
                        .auditCoverageValid(false)
                        .missingEventTypes(List.of())
                        .terminalEventConsistent(false)
                        .auditOrderValid(false)
                        .auditOrderViolations(List.of())
                        .build();
            }
            if (event.getEventSignature() != null && !event.getEventSignature().isBlank()) {
                signedEventCount++;
            }
            if (signingProperties.isActive()) {
                IntegrityResult signatureResult = verifySignature(event, events.size(), expectedPrevious, signedEventCount);
                if (signatureResult != null) {
                    return signatureResult;
                }
            }
            expectedPrevious = event.getEventHash();
        }

        AuditCoverageEvaluator.AuditCoverageResult coverage = coverageEvaluator().evaluate(events, taskStatus);
        return IntegrityResult.builder()
                .valid(true)
                .eventCount(events.size())
                .lastHash(expectedPrevious)
                .reason("ok")
                .signatureValid(true)
                .signedEventCount(signedEventCount)
                .signatureKeyId(signingProperties.getKeyId())
                .auditCoverageValid(coverage.isValid())
                .missingEventTypes(coverage.getMissingEventTypes())
                .terminalEventConsistent(coverage.isTerminalEventConsistent())
                .auditOrderValid(coverage.isOrderValid())
                .auditOrderViolations(coverage.getOrderViolations())
                .build();
    }

    private IntegrityResult verifySignature(ReviewAuditEvent event,
                                            int eventCount,
                                            String lastKnownHash,
                                            int signedEventCount) {
        if (event.getEventSignature() == null || event.getEventSignature().isBlank()) {
            return IntegrityResult.builder()
                    .valid(false)
                    .eventCount(eventCount)
                    .failedEventId(event.getId())
                    .reason("signature_missing")
                    .lastHash(lastKnownHash)
                    .signatureValid(false)
                    .signedEventCount(signedEventCount)
                    .signatureKeyId(signingProperties.getKeyId())
                    .auditCoverageValid(false)
                    .missingEventTypes(List.of())
                    .terminalEventConsistent(false)
                    .auditOrderValid(false)
                    .auditOrderViolations(List.of())
                    .build();
        }
        if (event.getSignatureKeyId() == null || !event.getSignatureKeyId().equals(signingProperties.getKeyId())) {
            return IntegrityResult.builder()
                    .valid(false)
                    .eventCount(eventCount)
                    .failedEventId(event.getId())
                    .reason("signature_key_mismatch")
                    .lastHash(lastKnownHash)
                    .signatureValid(false)
                    .signedEventCount(signedEventCount)
                    .signatureKeyId(signingProperties.getKeyId())
                    .auditCoverageValid(false)
                    .missingEventTypes(List.of())
                    .terminalEventConsistent(false)
                    .auditOrderValid(false)
                    .auditOrderViolations(List.of())
                    .build();
        }
        String expectedSignature = hashService.hmacSha256Hex(
                signaturePayload(event.getEventHash(), event.getSignatureKeyId()),
                signingProperties.getSecret());
        if (!hashService.secureEquals(expectedSignature, event.getEventSignature())) {
            return IntegrityResult.builder()
                    .valid(false)
                    .eventCount(eventCount)
                    .failedEventId(event.getId())
                    .reason("signature_mismatch")
                    .lastHash(lastKnownHash)
                    .signatureValid(false)
                    .signedEventCount(signedEventCount)
                    .signatureKeyId(signingProperties.getKeyId())
                    .auditCoverageValid(false)
                    .missingEventTypes(List.of())
                    .terminalEventConsistent(false)
                    .auditOrderValid(false)
                    .auditOrderViolations(List.of())
                    .build();
        }
        return null;
    }

    private AuditCoverageEvaluator coverageEvaluator() {
        return new AuditCoverageEvaluator();
    }

    private String signaturePayload(String eventHash, String keyId) {
        return "codeguardian-audit-v1\n" + (keyId != null ? keyId : "") + "\n" + (eventHash != null ? eventHash : "");
    }

    @Data
    @Builder
    public static class IntegrityResult {
        private boolean valid;
        private int eventCount;
        private Long failedEventId;
        private String reason;
        private String lastHash;
        private boolean signatureValid;
        private int signedEventCount;
        private String signatureKeyId;
        private boolean auditCoverageValid;
        private List<String> missingEventTypes;
        private boolean terminalEventConsistent;
        private boolean auditOrderValid;
        private List<String> auditOrderViolations;
    }

}
