package com.codeguardian.service.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrossEncoderRerankerClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void rerank_ShouldPostTeiPayloadAndParseScores() throws Exception {
        AtomicReference<JsonNode> requestJson = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rerank", exchange -> handleRerank(exchange, requestJson));
        server.start();
        try {
            RagRerankerProperties properties = new RagRerankerProperties();
            properties.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/rerank");
            properties.setModelId("test-reranker");
            properties.setTimeoutMillis(1000);
            CrossEncoderRerankerClient client = new CrossEncoderRerankerClient(properties, objectMapper);

            List<RetrievedKnowledgeChunk> chunks = List.of(
                    RetrievedKnowledgeChunk.builder()
                            .chunkId("chunk-a")
                            .title("Style")
                            .content("Use clear names.")
                            .metadata(Map.of("category", "CODE_STYLE"))
                            .build(),
                    RetrievedKnowledgeChunk.builder()
                            .chunkId("chunk-b")
                            .title("Security")
                            .content("Use prepared statements.")
                            .metadata(Map.of("category", "SECURITY"))
                            .build()
            );

            RerankResponse response = client.rerank("SQL injection", chunks);

            assertEquals("test-reranker", response.model());
            assertTrue(response.hasResults());
            assertEquals(2, response.results().size());
            assertEquals(1, response.results().get(0).index());
            assertEquals("chunk-b", response.results().get(0).chunkId());
            assertEquals(0.92d, response.results().get(0).score());
            assertEquals("SQL injection", requestJson.get().get("query").asText());
            assertEquals(false, requestJson.get().get("raw_scores").asBoolean());
            assertTrue(requestJson.get().get("texts").get(0).asText().contains("Title: Style"));
            assertTrue(requestJson.get().get("texts").get(1).asText().contains("Content:"));
        } finally {
            server.stop(0);
        }
    }

    private void handleRerank(HttpExchange exchange, AtomicReference<JsonNode> requestJson) throws IOException {
        byte[] requestBytes = exchange.getRequestBody().readAllBytes();
        requestJson.set(objectMapper.readTree(new String(requestBytes, StandardCharsets.UTF_8)));
        byte[] responseBytes = """
                {"results":[
                  {"index":1,"score":0.92},
                  {"index":0,"score":0.14}
                ]}
                """.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, responseBytes.length);
        exchange.getResponseBody().write(responseBytes);
        exchange.close();
    }
}
