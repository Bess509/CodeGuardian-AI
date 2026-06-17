package com.codeguardian.service.ai.tools;

import com.codeguardian.entity.Finding;
import com.codeguardian.service.ai.context.ReviewContextHolder;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Description;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Semgrep分析工具
 * 
 * <p>尝试调用本地Semgrep进行扫描，如果不可用则使用内置正则规则进行模拟</p>
 */
@Component("semgrepAnalysis")
@Description("使用Semgrep（或回退规则）扫描代码中的安全漏洞")
@Slf4j
@RequiredArgsConstructor
public class SemgrepAnalyzerTool implements Function<SemgrepAnalyzerTool.Request, SemgrepAnalyzerTool.Response> {

    private final ObjectMapper objectMapper;

    @Override
    public Response apply(Request request) {
        log.info("[Function Calling] AI 模型请求执行 Semgrep 分析工具...");
        long startTime = System.currentTimeMillis();
        
        try {
            Response response = runSemgrepAnalysis(request);
            
            // 将发现的问题添加到上下文，确保即使AI忽略了这些问题，也能被后续流程捕获
            if (response.findings != null && !response.findings.isEmpty()) {
                log.info("将 Semgrep 发现的 {} 个问题添加到 ReviewContextHolder", response.findings.size());
                ReviewContextHolder.addFindings(response.findings);
            }
            
            log.info("Semgrep分析完成，耗时: {} ms，发现 {} 个问题", System.currentTimeMillis() - startTime, response.vulnerabilities != null ? response.vulnerabilities.size() : 0);
            return response;
        } catch (Exception e) {
            log.error("Semgrep运行失败 (耗时: {} ms): {}", System.currentTimeMillis() - startTime, e.getMessage());
            return new Response(false, List.of("Semgrep分析失败: " + e.getMessage()), "分析过程中发生错误，请检查环境配置。");
        }
    }

    public Response runSemgrepAnalysis(Request request) throws Exception {
        String semgrepPath = resolveSemgrepPath();
        if (semgrepPath == null) {
            throw new RuntimeException("Semgrep executable not found in PATH or standard locations. Please install semgrep.");
        }

        // 自动包裹代码片段：如果代码中没有类定义，尝试包裹在类中，以帮助Semgrep解析
        String codeToScan = request.code;
        if (!codeToScan.contains("class ") && !codeToScan.contains("interface ") && !codeToScan.contains("enum ")) {
            codeToScan = "public class SemgrepWrapper {\n" + codeToScan + "\n}";
            log.info("检测到代码片段可能缺少类定义，已自动包裹 SemgrepWrapper 类以提高解析成功率");
        }

        // 由于Semgrep CLI通常需要文件路径，我们将代码写入临时文件
        Path tempFile = Files.createTempFile("semgrep_scan_", ".java");
        Files.writeString(tempFile, codeToScan);
        log.debug("创建临时文件用于Semgrep扫描: {}", tempFile);
        
        try {
            log.info("启动Semgrep进程扫描: {}", semgrepPath);
            // 使用 p/default 规则集
            ProcessBuilder processBuilder = new ProcessBuilder(
                    semgrepPath,
                    "--config", "p/default", 
                    "--json",
                    "--quiet",
                    tempFile.toString()
            );
            
            Process process = processBuilder.start();
            
            boolean finished = process.waitFor(60, TimeUnit.SECONDS);
            if (!finished) {
                process.destroy();
                throw new RuntimeException("Timeout waiting for Semgrep");
            }
            
            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }
            
            // Read stderr for debugging
            String errorOutput;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                errorOutput = reader.lines().collect(Collectors.joining("\n"));
            }

            if (process.exitValue() != 0) {
                 log.error("Semgrep非零退出 (code: {}). Stderr: {}", process.exitValue(), errorOutput);
                 // Semgrep returns 0 on success (findings or no findings), 1 on error
                 if (output.isEmpty()) {
                     throw new RuntimeException("Semgrep exited with code " + process.exitValue() + ". Error: " + errorOutput);
                 }
            }

            // Parse JSON output
            List<Finding> findings = parseSemgrepOutput(output);
            
            // Check for parse errors in stderr if no findings
            // Semgrep often prints "Ran 0 rules" or "Succeeded" but if there are syntax errors it might be in stderr
            List<String> vulnerabilityStrings = findings.stream()
                .map(f -> String.format("[%s] Line %d: %s", f.getTitle(), f.getStartLine(), f.getDescription()))
                .collect(Collectors.toList());

            if (findings.isEmpty()) {
                log.warn("Semgrep结果为空。Stderr: {}", errorOutput); // Log stderr for debugging zero findings
                
                if (errorOutput.contains("syntax error") || errorOutput.contains("parse error") || errorOutput.contains("Syntax error")) {
                    String warning = "Semgrep未发现漏洞，但在分析过程中遇到语法解析错误，导致分析可能中断。请检查代码语法是否正确。";
                    log.warn(warning);
                    vulnerabilityStrings.add("[WARNING] " + warning);
                } else if (errorOutput.contains("No rules run") || errorOutput.contains("unauthorized")) {
                     String warning = "Semgrep未运行任何规则，可能是网络问题或规则集配置错误。";
                     log.warn(warning);
                     vulnerabilityStrings.add("[WARNING] " + warning);
                } else {
                     // Generic hint for 0 findings
                     log.info("Semgrep未发现问题。提示: Semgrep静态分析依赖完整的数据流，未使用的变量或严重语法错误可能导致漏报。");
                }
            } else {
                // 打印Semgrep发现的问题到日志
                log.info("========== Semgrep 发现 {} 个问题 ==========", findings.size());
                for (Finding f : findings) {
                    log.info("[{}] Line {}: {}", f.getTitle(), f.getStartLine(), f.getDescription());
                }
                log.info("============================================");
            }
            
            String resultMsg = "Semgrep分析完成，发现 " + findings.size() + " 个问题。";
            if (findings.isEmpty()) {
                resultMsg += " (注意: 只有完整的代码逻辑才会被Semgrep检测到，未使用的变量或语法错误会被忽略)";
            }
            return new Response(true, vulnerabilityStrings, findings, resultMsg);
            
        } finally {
            try {
                Files.deleteIfExists(tempFile);
                log.debug("删除临时文件: {}", tempFile);
            } catch (Exception ignored) {}
        }
    }

    protected List<Finding> parseSemgrepOutput(String jsonOutput) throws Exception {
        JsonNode root = objectMapper.readTree(jsonOutput);
        JsonNode results = root.path("results");
        
        List<Finding> findings = new ArrayList<>();
        if (results.isArray()) {
            log.info("Semgrep原始结果数量: {}", results.size());
            for (JsonNode result : results) {
                String message = result.path("extra").path("message").asText();
                String ruleId = result.path("check_id").asText();
                int line = result.path("start").path("line").asInt();
                int endLine = result.path("end").path("line").asInt();
                String severityStr = result.path("extra").path("severity").asText("INFO");
                String severity = "MEDIUM";
                if ("ERROR".equalsIgnoreCase(severityStr)) severity = "HIGH";
                else if ("WARNING".equalsIgnoreCase(severityStr)) severity = "MEDIUM";
                else if ("INFO".equalsIgnoreCase(severityStr)) severity = "LOW";
                String category = "SECURITY";
                if (ruleId.contains("correctness") || ruleId.contains("bug")) category = "BUG";
                else if (ruleId.contains("performance")) category = "PERFORMANCE";
                else if (ruleId.contains("maintainability")) category = "MAINTAINABILITY";
                
                Finding finding = Finding.builder()
                        .title(ruleId)
                        .description(message)
                        .startLine(line)
                        .endLine(endLine)
                        .severity(com.codeguardian.enums.SeverityEnum.fromName(severity).getValue())
                        .category(category)
                        .source("Semgrep")
                        .location("Code Snippet") // Will be updated by caller
                        .build();
                        
                findings.add(finding);
            }
        } else {
            log.debug("Semgrep返回结果为空或格式不符");
        }
        return findings;
    }

    protected String resolveSemgrepPath() {
        // 1. Check if 'semgrep' is in PATH
        try {
            Process process = new ProcessBuilder("semgrep", "--version").start();
            if (process.waitFor() == 0) {
                return "semgrep";
            }
        } catch (Exception ignored) {}

        // 2. Check known locations
        String[] knownPaths = {
            "/usr/local/bin/semgrep",
            "/opt/homebrew/bin/semgrep",
            "/Library/Frameworks/Python.framework/Versions/3.8/bin/semgrep", // Mac standard python
            "/Library/Frameworks/Python.framework/Versions/3.9/bin/semgrep",
            "/Library/Frameworks/Python.framework/Versions/3.10/bin/semgrep",
            "/Library/Frameworks/Python.framework/Versions/3.11/bin/semgrep",
            "/Library/Frameworks/Python.framework/Versions/3.12/bin/semgrep",
            System.getProperty("user.home") + "/Library/Python/3.8/bin/semgrep", // User pip install
            System.getProperty("user.home") + "/Library/Python/3.9/bin/semgrep",
            System.getProperty("user.home") + "/.local/bin/semgrep" // Linux standard
        };

        for (String path : knownPaths) {
            if (new java.io.File(path).exists()) {
                log.info("Found Semgrep at: {}", path);
                return path;
            }
        }
        
        return null;
    }

    // 移除 runFallbackAnalysis 方法和 checkPattern 方法，不再支持回退
    // private Response runFallbackAnalysis(String code) { ... }
    // private void checkPattern(...) { ... }

    @Data
    @JsonClassDescription("安全分析请求")
    public static class Request {
        @JsonPropertyDescription("需要分析的代码内容")
        @JsonProperty(required = true)
        public String code;
    }

    @Data
    public static class Response {
        public boolean success;
        public List<String> vulnerabilities;
        public List<Finding> findings;
        public String message;

        public Response(boolean success, List<String> vulnerabilities, String message) {
            this(success, vulnerabilities, new ArrayList<>(), message);
        }
        
        public Response(boolean success, List<String> vulnerabilities, List<Finding> findings, String message) {
            this.success = success;
            this.vulnerabilities = vulnerabilities;
            this.findings = findings;
            this.message = message;
        }
    }
}
