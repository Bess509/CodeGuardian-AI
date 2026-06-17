package com.codeguardian.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import java.util.Arrays;
import java.util.Collections;

/**
 * 代码解析服务
 */
@Service
@Slf4j
public class CodeParserService {

    private final ExecutorService executor = Executors.newFixedThreadPool(20);

    @jakarta.annotation.PreDestroy
    public void destroy() {
        executor.shutdown();
    }
    
    /**
     * 读取文件内容
     */
    public String readFile(String filePath) {
        try {
            Path path = Paths.get(filePath);
            if (!Files.exists(path)) {
                throw new IllegalArgumentException("文件不存在: " + path.toAbsolutePath() + " (输入路径: " + filePath + ")");
            }
            return Files.readString(path);
        } catch (IOException e) {
            log.error("读取文件失败: {}", filePath, e);
            throw new RuntimeException("读取文件失败: " + filePath, e);
        }
    }
    
    /**
     * 读取目录下的所有代码文件
     */
    public String readDirectory(String directoryPath) {
        try {
            Path dir = Paths.get(directoryPath);
            if (!Files.exists(dir) || !Files.isDirectory(dir)) {
                // 尝试检查 src/directoryPath 是否存在，给出提示
                String suggestion = "";
                try {
                    Path srcDir = Paths.get("src", directoryPath);
                    if (Files.exists(srcDir) && Files.isDirectory(srcDir)) {
                        suggestion = " 检测到 " + srcDir.toAbsolutePath() + " 存在，您是否指的是 src/" + directoryPath + " ?";
                    }
                } catch (Exception ignored) {}

                throw new IllegalArgumentException("目录不存在: " + dir.toAbsolutePath() + " (输入路径: " + directoryPath + ")." + suggestion);
            }

            List<Path> files;
            try (Stream<Path> paths = Files.walk(dir)) {
                files = paths.filter(Files::isRegularFile)
                        .filter(this::isCodeFile)
                        .collect(Collectors.toList());
            }

            if (files.isEmpty()) {
                return "";
            }

            List<CompletableFuture<String>> futures = new ArrayList<>();
            for (Path p : files) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        return Files.readString(p);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }, executor)
                        .exceptionally(ex -> {
                            log.warn("读取文件失败: {}", p, ex);
                            return null;
                        }));
            }

            StringBuilder content = new StringBuilder();
            for (int i = 0; i < futures.size(); i++) {
                String fileContent = futures.get(i).join();
                if (fileContent != null) {
                    content.append("=== ")
                            .append(files.get(i).toString())
                            .append(" ===\n")
                            .append(fileContent)
                            .append("\n\n");
                }
            }
            return content.toString();
        } catch (IOException e) {
            log.error("读取目录失败: {}", directoryPath, e);
            throw new RuntimeException("读取目录失败: " + directoryPath, e);
        }
    }
    
    /**
     * 读取项目代码
     */
    public String readProject(String projectPath) {
        return readDirectory(projectPath);
    }
    
    /**
     * 扫描目录下的所有代码文件路径
     */
    public List<Path> scanDirectory(String directoryPath) {
        return scanDirectory(directoryPath, null, null);
    }

    /**
     * 扫描目录下的所有代码文件路径（支持过滤）
     */
    public List<Path> scanDirectory(String directoryPath, String includePaths, String excludePaths) {
        try {
            if (directoryPath == null || directoryPath.trim().isEmpty()) {
                throw new IllegalArgumentException("目录路径不能为空");
            }
            
            Path dir = Paths.get(directoryPath).toAbsolutePath().normalize();
            if (!Files.exists(dir) || !Files.isDirectory(dir)) {
                throw new IllegalArgumentException("目录不存在: " + directoryPath);
            }
            
            log.info("正在扫描目录: {}", dir);

            // 解析配置
            List<String> includes = parsePaths(includePaths);
            List<String> excludes = parsePaths(excludePaths);

            try (Stream<Path> paths = Files.walk(dir)) {
                return paths.filter(Files::isRegularFile)
                        .filter(this::isCodeFile)
                        .filter(path -> {
                            String relativePath = dir.relativize(path).toString().replace(java.io.File.separator, "/");
                            return isPathIncluded(relativePath, includes, excludes);
                        })
                        .collect(Collectors.toList());
            }
        } catch (IOException e) {
            log.error("扫描目录失败: {}", directoryPath, e);
            throw new RuntimeException("扫描目录失败: " + directoryPath, e);
        }
    }

    private List<String> parsePaths(String pathsConfig) {
        if (pathsConfig == null || pathsConfig.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return Arrays.stream(pathsConfig.split("\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.replace("\\", "/")) // 统一分隔符
                .collect(Collectors.toList());
    }

    private boolean isPathIncluded(String path, List<String> includes, List<String> excludes) {
        // 检查排除路径
        for (String exclude : excludes) {
            if (path.startsWith(exclude) || path.contains("/" + exclude + "/")) {
                return false;
            }
        }
        
        // 检查包含路径
        if (!includes.isEmpty()) {
            boolean isIncluded = false;
            for (String include : includes) {
                if (path.startsWith(include) || path.contains("/" + include + "/")) {
                    isIncluded = true;
                    break;
                }
            }
            return isIncluded;
        }
        
        return true;
    }

    /**
     * 判断是否为代码文件
     */
    private boolean isCodeFile(Path path) {
        String fileName = path.getFileName().toString().toLowerCase();
        return fileName.endsWith(".java") ||
               fileName.endsWith(".js") ||
               fileName.endsWith(".ts") ||
               fileName.endsWith(".py") ||
               fileName.endsWith(".go") ||
               fileName.endsWith(".rs") ||
               fileName.endsWith(".cpp") ||
               fileName.endsWith(".c") ||
               fileName.endsWith(".cs") ||
               fileName.endsWith(".php") ||
               fileName.endsWith(".rb") ||
               fileName.endsWith(".swift") ||
               fileName.endsWith(".kt");
    }
}
