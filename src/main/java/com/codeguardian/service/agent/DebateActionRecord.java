package com.codeguardian.service.agent;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class DebateActionRecord {
    private int iteration;
    private DebateActionType action;
    private String reason;
    private List<String> draftIds;
    private int beforeCount;
    private int afterCount;
    private boolean terminal;
}
