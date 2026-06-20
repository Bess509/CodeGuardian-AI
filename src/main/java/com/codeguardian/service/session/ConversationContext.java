package com.codeguardian.service.session;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class ConversationContext {
    String promptContext;
    List<Long> memoryIds;
    boolean fromCache;
}
