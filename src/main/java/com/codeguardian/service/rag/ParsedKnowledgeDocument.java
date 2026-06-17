package com.codeguardian.service.rag;

import lombok.Builder;
import lombok.Value;

import java.util.Map;

@Value
@Builder
public class ParsedKnowledgeDocument {
    String content;
    String parser;
    String parserStrategy;
    Map<String, Object> metadata;
}
