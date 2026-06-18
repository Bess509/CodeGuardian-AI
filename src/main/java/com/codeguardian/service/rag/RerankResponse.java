package com.codeguardian.service.rag;

import java.util.List;

public record RerankResponse(String model, String endpoint, List<RerankResult> results) {

    public boolean hasResults() {
        return results != null && !results.isEmpty();
    }
}
