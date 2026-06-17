package com.codeguardian.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

/**
 * Git服务
 */
@Service
@Slf4j
public class GitService {
    
    private static final String TEMP_DIR_PREFIX = "git_repo_";
    private final Map<String, String> clonedRepos = new HashMap<>(); // gitUrl -> localPath
    
    /**
     * 克隆Git仓库到临时目录
     * @param gitUrl Git仓库地址
     * @param username 用户名（可选）
     * @param password 密码/Token（可选）
     * @return 本地临时目录路径
     */
    public String cloneRepository(String gitUrl, String username, String password) {
        try {
            // 检查是否已经克隆过
            if (clonedRepos.containsKey(gitUrl)) {
                String existingPath = clonedRepos.get(gitUrl);
                if (Files.exists(Paths.get(existingPath))) {
                    log.info("使用已存在的克隆: {}", existingPath);
                    return existingPath;
                } else {
                    clonedRepos.remove(gitUrl);
                }
            }
            
            // 创建临时目录
            Path tempDir = Files.createTempDirectory(TEMP_DIR_PREFIX);
            String tempDirPath = tempDir.toAbsolutePath().toString();
            
            // 构建git clone命令
            // 对于公开仓库，直接使用原始URL；对于私有仓库，需要插入认证信息
            String cloneUrl = gitUrl;
            if (username != null && !username.trim().isEmpty() && !username.trim().equals("")) {
                // 如果提供了用户名，将其插入到URL中
                // 需要正确解析URL，在协议和主机之间插入用户名和密码
                try {
                    java.net.URI uri = new java.net.URI(gitUrl);
                    String scheme = uri.getScheme();
                    String host = uri.getHost();
                    int port = uri.getPort();
                    String path = uri.getPath();
                    String query = uri.getQuery();
                    String fragment = uri.getFragment();
                    
                    // 构建带认证信息的URL
                    StringBuilder urlBuilder = new StringBuilder();
                    urlBuilder.append(scheme).append("://");
                    
                    // 插入用户名和密码（需要对特殊字符进行编码）
                    String encodedUsername = java.net.URLEncoder.encode(username, java.nio.charset.StandardCharsets.UTF_8);
                    if (password != null && !password.trim().isEmpty()) {
                        String encodedPassword = java.net.URLEncoder.encode(password, java.nio.charset.StandardCharsets.UTF_8);
                        urlBuilder.append(encodedUsername).append(":").append(encodedPassword).append("@");
                    } else {
                        urlBuilder.append(encodedUsername).append("@");
                    }
                    
                    // 添加主机
                    urlBuilder.append(host);
                    
                    // 添加端口（如果有）
                    if (port != -1) {
                        urlBuilder.append(":").append(port);
                    }
                    
                    // 添加路径
                    if (path != null) {
                        urlBuilder.append(path);
                    }
                    
                    // 添加查询字符串（如果有）
                    if (query != null && !query.isEmpty()) {
                        urlBuilder.append("?").append(query);
                    }
                    
                    // 添加引用（如果有）
                    if (fragment != null && !fragment.isEmpty()) {
                        urlBuilder.append("#").append(fragment);
                    }
                    
                    cloneUrl = urlBuilder.toString();
                } catch (Exception e) {
                    log.warn("解析URL失败，使用简单替换方式: {}", e.getMessage());
                    // 如果URL解析失败，使用简单替换方式
                    if (gitUrl.startsWith("https://")) {
                        String auth = username + (password != null && !password.trim().isEmpty() ? ":" + password : "");
                        cloneUrl = gitUrl.replace("https://", "https://" + auth + "@");
                    } else if (gitUrl.startsWith("http://")) {
                        String auth = username + (password != null && !password.trim().isEmpty() ? ":" + password : "");
                        cloneUrl = gitUrl.replace("http://", "http://" + auth + "@");
                    }
                }
            }
            
            // 从URL中提取仓库名称（用于创建目录）
            String repoName = gitUrl.substring(gitUrl.lastIndexOf('/') + 1);
            if (repoName.endsWith(".git")) {
                repoName = repoName.substring(0, repoName.length() - 4);
            }
            String repoPath = tempDirPath + File.separator + repoName;
            
            // 执行git clone命令（git clone会在tempDir下创建repoName目录）
            ProcessBuilder processBuilder = new ProcessBuilder("git", "clone", cloneUrl, repoPath);
            processBuilder.redirectErrorStream(true);
            
            Process process = processBuilder.start();
            
            // 读取输出
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            
            int exitCode = process.waitFor();
            
            if (exitCode != 0) {
                log.error("Git clone失败: {}", output.toString());
                // 清理临时目录
                deleteDirectory(tempDir.toFile());
                throw new RuntimeException("Git clone失败: " + output.toString());
            }
            
            // 检查克隆后的目录是否存在
            File repoDir = new File(repoPath);
            if (repoDir.exists() && repoDir.isDirectory()) {
                clonedRepos.put(gitUrl, repoPath);
                log.info("Git仓库克隆成功: {} -> {}", gitUrl, repoPath);
                return repoPath;
            }
            
            // 如果指定路径不存在，尝试查找其他目录
            File[] files = tempDir.toFile().listFiles();
            if (files != null && files.length > 0) {
                File foundDir = files[0];
                if (foundDir.isDirectory()) {
                    clonedRepos.put(gitUrl, foundDir.getAbsolutePath());
                    log.info("Git仓库克隆成功: {} -> {}", gitUrl, foundDir.getAbsolutePath());
                    return foundDir.getAbsolutePath();
                }
            }
            
            throw new RuntimeException("Git clone完成但未找到仓库目录");
            
        } catch (IOException | InterruptedException e) {
            log.error("克隆Git仓库失败: {}", gitUrl, e);
            throw new RuntimeException("克隆Git仓库失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 获取目录下的所有文件列表（用于构建文件树）
     * @param directoryPath 目录路径
     * @return 文件路径列表
     */
    public void checkoutRevision(String repoPath, String branch, String commitHash) {
        if (repoPath == null || repoPath.isBlank()) {
            return;
        }
        try {
            if (branch != null && !branch.isBlank()) {
                GitCommandResult branchResult = runGit(repoPath, "checkout", branch);
                if (branchResult.exitCode() != 0) {
                    GitCommandResult remoteBranchResult = runGit(repoPath, "checkout", "-B", branch, "origin/" + branch);
                    if (remoteBranchResult.exitCode() != 0) {
                        throw new RuntimeException(remoteBranchResult.output());
                    }
                }
            }
            if (commitHash != null && !commitHash.isBlank()) {
                GitCommandResult commitResult = runGit(repoPath, "checkout", commitHash);
                if (commitResult.exitCode() != 0) {
                    throw new RuntimeException(commitResult.output());
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Git checkout failed: " + e.getMessage(), e);
        }
    }

    public List<String> getChangedFiles(String repoPath, String baseCommit, String headCommit) {
        if (repoPath == null || repoPath.isBlank() || baseCommit == null || baseCommit.isBlank()) {
            return List.of();
        }
        String head = headCommit != null && !headCommit.isBlank() ? headCommit : "HEAD";
        GitCommandResult result = runGit(repoPath, "diff", "--name-only", "--diff-filter=ACMRTUXB", baseCommit, head);
        if (result.exitCode() != 0) {
            throw new RuntimeException("Git diff failed: " + result.output());
        }
        return Arrays.stream(result.output().split("\\R"))
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .map(line -> line.replace("\\", "/"))
                .toList();
    }

    public String getUnifiedDiff(String repoPath, String baseCommit, String headCommit) {
        if (repoPath == null || repoPath.isBlank() || baseCommit == null || baseCommit.isBlank()) {
            return "";
        }
        String head = headCommit != null && !headCommit.isBlank() ? headCommit : "HEAD";
        GitCommandResult result = runGit(repoPath, "diff", "--unified=0", "--diff-filter=ACMRTUXB", baseCommit, head);
        if (result.exitCode() != 0) {
            throw new RuntimeException("Git diff failed: " + result.output());
        }
        return result.output();
    }

    public String readTextFileIfExists(String repoPath, String relativePath) {
        if (repoPath == null || relativePath == null || relativePath.isBlank()) {
            return null;
        }
        try {
            Path root = Paths.get(repoPath).toAbsolutePath().normalize();
            Path file = root.resolve(relativePath).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                return null;
            }
            return Files.readString(file);
        } catch (Exception e) {
            log.warn("Read git file failed: repoPath={}, relativePath={}, error={}", repoPath, relativePath, e.getMessage());
            return null;
        }
    }

    private GitCommandResult runGit(String repoPath, String... args) {
        try {
            List<String> command = new ArrayList<>();
            command.add("git");
            command.addAll(Arrays.asList(args));
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.directory(new File(repoPath));
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            int exitCode = process.waitFor();
            return new GitCommandResult(exitCode, output.toString());
        } catch (IOException e) {
            throw new RuntimeException("Git command failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Git command interrupted", e);
        }
    }

    private record GitCommandResult(int exitCode, String output) {
    }

    public List<String> getFileList(String directoryPath) {
        return getFileList(directoryPath, null, null);
    }

    /**
     * 获取目录下的所有文件列表（支持过滤）
     * @param directoryPath 目录路径
     * @param includePaths 包含路径配置（换行符分隔）
     * @param excludePaths 排除路径配置（换行符分隔）
     * @return 文件路径列表
     */
    public List<String> getFileList(String directoryPath, String includePaths, String excludePaths) {
        // 解析配置
        List<String> includes = parsePaths(includePaths);
        List<String> excludes = parsePaths(excludePaths);
        
        List<String> fileList = new ArrayList<>();
        try {
            Path dir = Paths.get(directoryPath);
            if (!Files.exists(dir) || !Files.isDirectory(dir)) {
                throw new IllegalArgumentException("目录不存在: " + directoryPath);
            }
            
            try (Stream<Path> paths = Files.walk(dir)) {
                paths.filter(Files::isRegularFile)
                     .forEach(path -> {
                         String relativePath = dir.relativize(path).toString().replace(File.separator, "/");
                         if (isPathIncluded(relativePath, includes, excludes)) {
                             fileList.add(relativePath);
                         }
                     });
            }
            
            return fileList;
        } catch (IOException e) {
            log.error("获取文件列表失败: {}", directoryPath, e);
            throw new RuntimeException("获取文件列表失败: " + e.getMessage(), e);
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
                .collect(java.util.stream.Collectors.toList());
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
     * 读取文件内容
     * @param filePath 完整文件路径
     * @return 文件内容
     */
    public String readFile(String filePath) {
        try {
            Path path = Paths.get(filePath);
            if (!Files.exists(path)) {
                throw new IllegalArgumentException("文件不存在: " + filePath);
            }
            return Files.readString(path);
        } catch (IOException e) {
            log.error("读取文件失败: {}", filePath, e);
            throw new RuntimeException("读取文件失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 删除目录及其所有内容
     */
    private void deleteDirectory(File directory) {
        if (directory.exists()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        file.delete();
                    }
                }
            }
            directory.delete();
        }
    }
}
