package com.codeguardian.service.ai.context;

import com.codeguardian.entity.Finding;
import com.codeguardian.service.provenance.EvidenceDraft;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 审查上下文持有者
 * 用于在线程中传递工具发现的问题
 */
public class ReviewContextHolder {
    private static final ThreadLocal<List<Finding>> findingsHolder = ThreadLocal.withInitial(ArrayList::new);
    private static final ThreadLocal<List<EvidenceDraft>> evidenceHolder = ThreadLocal.withInitial(ArrayList::new);
    private static final ThreadLocal<Long> taskIdHolder = new ThreadLocal<>();

    public static void setTaskId(Long taskId) {
        if (taskId != null) {
            taskIdHolder.set(taskId);
        } else {
            taskIdHolder.remove();
        }
    }

    public static Long getTaskId() {
        return taskIdHolder.get();
    }

    public static void addFindings(List<Finding> findings) {
        if (findings != null) {
            findingsHolder.get().addAll(findings);
        }
    }
    
    public static void addFinding(Finding finding) {
        if (finding != null) {
            findingsHolder.get().add(finding);
        }
    }

    public static List<Finding> getFindings() {
        return Collections.unmodifiableList(new ArrayList<>(findingsHolder.get()));
    }

    public static void addEvidence(EvidenceDraft evidence) {
        if (evidence != null) {
            evidenceHolder.get().add(evidence);
        }
    }

    public static void addEvidence(List<EvidenceDraft> evidence) {
        if (evidence != null && !evidence.isEmpty()) {
            evidenceHolder.get().addAll(evidence);
        }
    }

    public static List<EvidenceDraft> getEvidence() {
        return Collections.unmodifiableList(new ArrayList<>(evidenceHolder.get()));
    }

    public static void clearFindingsAndEvidence() {
        findingsHolder.remove();
        evidenceHolder.remove();
    }

    public static void clear() {
        findingsHolder.remove();
        evidenceHolder.remove();
        taskIdHolder.remove();
    }
}
