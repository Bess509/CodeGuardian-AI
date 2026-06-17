package com.codeguardian.service.integration;

import com.codeguardian.dto.integration.CodeGuardianProjectConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class CodeGuardianConfigFileService {

    private static final String CONFIG_FILE = ".codeguardian.yml";

    public CodeGuardianProjectConfig load(Path repoRoot) {
        if (repoRoot == null) {
            return CodeGuardianProjectConfig.builder().build();
        }
        Path configPath = repoRoot.resolve(CONFIG_FILE).normalize();
        if (!Files.isRegularFile(configPath)) {
            return CodeGuardianProjectConfig.builder().build();
        }
        try {
            Object loaded = new Yaml().load(Files.readString(configPath));
            if (!(loaded instanceof Map<?, ?> root)) {
                return CodeGuardianProjectConfig.builder().sourcePath(configPath.toString()).build();
            }
            Map<?, ?> qualityGate = map(root.get("qualityGate"));
            Map<?, ?> paths = map(root.get("paths"));
            Map<?, ?> cicd = map(root.get("cicd"));
            return CodeGuardianProjectConfig.builder()
                    .sourcePath(configPath.toString())
                    .blockOn(stringValue(firstNonNull(
                            value(qualityGate, "blockOn"),
                            value(cicd, "blockOn"),
                            root.get("blockOn"))))
                    .requireGrounding(booleanValue(value(qualityGate, "requireGrounding")))
                    .requireProofBundle(booleanValue(value(qualityGate, "requireProofBundle")))
                    .requireAuditChain(booleanValue(value(qualityGate, "requireAuditChain")))
                    .ragMode(stringValue(firstNonNull(value(qualityGate, "ragMode"), value(cicd, "ragMode"))))
                    .diffOnly(booleanValue(firstNonNull(value(cicd, "diffOnly"), root.get("diffOnly"))))
                    .includePaths(pathList(value(paths, "include")))
                    .excludePaths(pathList(value(paths, "exclude")))
                    .build();
        } catch (IOException e) {
            log.warn("Failed to read {}: {}", configPath, e.getMessage());
            return CodeGuardianProjectConfig.builder().sourcePath(configPath.toString()).build();
        } catch (Exception e) {
            log.warn("Failed to parse {}: {}", configPath, e.getMessage());
            return CodeGuardianProjectConfig.builder().sourcePath(configPath.toString()).build();
        }
    }

    public String toPathConfig(List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return null;
        }
        return String.join("\n", paths.stream()
                .map(this::normalizePathPattern)
                .filter(item -> !item.isBlank())
                .toList());
    }

    private String normalizePathPattern(String path) {
        if (path == null) {
            return "";
        }
        String normalized = path.trim().replace("\\", "/");
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        if (normalized.endsWith("/**")) {
            normalized = normalized.substring(0, normalized.length() - 3);
        } else if (normalized.endsWith("/*")) {
            normalized = normalized.substring(0, normalized.length() - 2);
        }
        return normalized;
    }

    private Map<?, ?> map(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private Object value(Map<?, ?> map, String key) {
        return map != null ? map.get(key) : null;
    }

    private Object firstNonNull(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String stringValue(Object value) {
        return value != null ? String.valueOf(value).trim() : null;
    }

    private Boolean booleanValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private List<String> pathList(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                String text = stringValue(item);
                if (text != null && !text.isBlank()) {
                    result.add(normalizePathPattern(text));
                }
            }
        } else {
            String text = stringValue(value);
            if (text != null && !text.isBlank()) {
                result.add(normalizePathPattern(text));
            }
        }
        return result;
    }
}
