package com.codeguardian.service;

import com.codeguardian.dto.FileContentDTO;
import com.codeguardian.dto.ReviewRequestDTO;
import com.codeguardian.entity.Finding;
import com.codeguardian.enums.SeverityEnum;
import lombok.extern.slf4j.Slf4j;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Slf4j
public class JavaReviewGateService {

    private static final Pattern TYPE_PATTERN = Pattern.compile("\\b(class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)");
    private static final Pattern PUBLIC_TYPE_PATTERN = Pattern.compile("\\bpublic\\s+(?:class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)");
    private static final Pattern PACKAGE_PATTERN = Pattern.compile("(?m)^\\s*package\\s+([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)\\s*;");
    private static final Pattern METHOD_PATTERN = Pattern.compile(
            "(?s)\\b(?:public|protected|private|static|final|synchronized|abstract|native|strictfp|default)\\b[\\s\\w<>\\[\\],.?@]*\\s+[A-Za-z_$][\\w$]*\\s*\\([^;{}]*\\)\\s*\\{");
    private static final Duration BUILD_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration TEST_TIMEOUT = Duration.ofMinutes(3);
    private static final int MAX_OUTPUT_CHARS = 6000;

    List<Finding> checkSingleScope(ReviewRequestDTO request, String codeContent, String sourceRef) {
        String reviewType = request.getReviewType() != null ? request.getReviewType().toUpperCase(Locale.ROOT) : "";
        if ("SNIPPET".equals(reviewType)) {
            return compileSnippetIfJava(codeContent, request.getLanguage(), sourceRef);
        }
        if ("FILE".equals(reviewType)) {
            Optional<Path> projectRoot = findProjectRootForFile(request.getFilePath());
            if (projectRoot.isPresent()) {
                List<Finding> buildFindings = compileProject(projectRoot.get(), sourceRef);
                if (!buildFindings.isEmpty()) {
                    return buildFindings;
                }
                return runIncrementalTests(projectRoot.get(), request, null);
            }
            return compileSourceIfJava(codeContent, request.getLanguage(), sourceRef);
        }
        return List.of();
    }

    List<Finding> checkProjectScope(Path rootPath, ReviewRequestDTO request, GitService gitService) {
        if (rootPath == null) {
            return List.of();
        }
        Optional<Path> projectRoot = findProjectRoot(rootPath);
        if (projectRoot.isEmpty()) {
            log.info("No Maven or Gradle build file found for {}, skipping project compile gate", rootPath);
            return List.of();
        }
        List<Finding> buildFindings = compileProject(projectRoot.get(), rootPath.toString());
        if (!buildFindings.isEmpty()) {
            return buildFindings;
        }
        return runIncrementalTests(projectRoot.get(), request, gitService);
    }

    List<Finding> checkUploadedFiles(List<FileContentDTO> files) {
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        List<SourceUnit> sources = files.stream()
                .filter(file -> file != null && file.getContent() != null && isJavaPath(file.getPath()))
                .map(file -> sourceForFullJava(file.getContent(), file.getPath()))
                .toList();
        if (sources.isEmpty()) {
            return List.of();
        }
        return compileSources(sources, "uploaded-files");
    }

    private List<Finding> compileSnippetIfJava(String code, String language, String sourceRef) {
        if (!isJavaLanguage(language) || code == null || code.isBlank()) {
            return List.of();
        }
        return compileSources(List.of(sourceForSnippet(code, sourceRef)), nonBlank(sourceRef, "Code Snippet"));
    }

    private List<Finding> compileSourceIfJava(String code, String language, String sourceRef) {
        if (!isJavaLanguage(language) && !isJavaPath(sourceRef)) {
            return List.of();
        }
        if (code == null || code.isBlank()) {
            return List.of();
        }
        return compileSources(List.of(sourceForFullJava(code, sourceRef)), nonBlank(sourceRef, "Java Source"));
    }

    private List<Finding> compileSources(List<SourceUnit> sources, String sourceRef) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return List.of(gateFinding(
                    "Java compiler unavailable",
                    nonBlank(sourceRef, "Java Source"),
                    1,
                    "No JDK JavaCompiler is available in the current runtime. Run CodeGuardian on a JDK, not a JRE.",
                    "Install or configure a JDK runtime before Java review."
            ));
        }

        Path outputDir = null;
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
            outputDir = Files.createTempDirectory("codeguardian-javac-");
            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            List<JavaFileObject> units = sources.stream()
                    .map(SourceJavaFileObject::new)
                    .map(JavaFileObject.class::cast)
                    .toList();
            List<String> options = List.of("-proc:none", "-d", outputDir.toString());
            Boolean success = compiler.getTask(null, fileManager, diagnostics, options, null, units).call();
            if (Boolean.TRUE.equals(success)) {
                return List.of();
            }
            return List.of(toCompileFinding(diagnostics.getDiagnostics(), sources, sourceRef));
        } catch (Exception e) {
            return List.of(gateFinding(
                    "Java compilation check failed",
                    nonBlank(sourceRef, "Java Source"),
                    1,
                    "Compilation gate failed before review: " + e.getMessage(),
                    "Fix the compilation gate runtime error, then retry review."
            ));
        } finally {
            deleteDirectoryQuietly(outputDir);
        }
    }

    private List<Finding> compileProject(Path projectRoot, String sourceRef) {
        Optional<BuildCommand> command = compileCommand(projectRoot);
        if (command.isEmpty()) {
            return List.of();
        }
        CommandResult result = runCommand(projectRoot, command.get().command(), BUILD_TIMEOUT);
        if (result.exitCode() == 0) {
            return List.of();
        }
        return List.of(gateFinding(
                "Project compilation failed",
                nonBlank(sourceRef, projectRoot.toString()),
                1,
                "Project compilation failed before review.\nCommand: " + String.join(" ", command.get().command())
                        + "\n\n" + trimOutput(result.output()),
                "Fix project compilation errors before running code review."
        ));
    }

    private List<Finding> runIncrementalTests(Path projectRoot, ReviewRequestDTO request, GitService gitService) {
        Optional<BuildCommand> command = testCommand(projectRoot, changedTestClasses(projectRoot, request, gitService));
        if (command.isEmpty()) {
            return List.of();
        }
        CommandResult result = runCommand(projectRoot, command.get().command(), TEST_TIMEOUT);
        if (result.exitCode() == 0) {
            return List.of();
        }
        return List.of(gateFinding(
                "Incremental unit tests failed",
                projectRoot.toString(),
                1,
                "Incremental unit tests failed before review.\nCommand: " + String.join(" ", command.get().command())
                        + "\n\n" + trimOutput(result.output()),
                "Fix or update the failing tests before running code review."
        ));
    }

    private Set<String> changedTestClasses(Path projectRoot, ReviewRequestDTO request, GitService gitService) {
        Set<String> changedFiles = changedFiles(projectRoot, request, gitService);
        Set<String> tests = new LinkedHashSet<>();
        for (String changedFile : changedFiles) {
            String normalized = changedFile.replace("\\", "/");
            if (!normalized.endsWith(".java")) {
                continue;
            }
            if (normalized.startsWith("src/test/java/")) {
                tests.add(toJavaClassName(normalized));
                continue;
            }
            if (normalized.startsWith("src/main/java/")) {
                String testBase = normalized.replaceFirst("^src/main/java/", "src/test/java/")
                        .replaceFirst("\\.java$", "");
                addExistingTest(projectRoot, tests, testBase + "Test.java");
                addExistingTest(projectRoot, tests, testBase + "Tests.java");
            }
        }
        if (tests.isEmpty() && !changedFiles.isEmpty()) {
            log.info("No matching existing unit tests found for changed files: {}", changedFiles);
        }
        return tests;
    }

    private Set<String> changedFiles(Path projectRoot, ReviewRequestDTO request, GitService gitService) {
        Set<String> changed = new LinkedHashSet<>();
        if (request.getChangedFiles() != null) {
            request.getChangedFiles().stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(this::normalizeRelativePath)
                    .forEach(changed::add);
        }
        if (!changed.isEmpty() || request.getBaseCommit() == null || request.getBaseCommit().isBlank() || gitService == null) {
            return changed;
        }
        try {
            gitService.getChangedFiles(projectRoot.toString(), request.getBaseCommit(), request.getHeadCommit()).stream()
                    .map(this::normalizeRelativePath)
                    .forEach(changed::add);
        } catch (Exception e) {
            log.warn("Unable to resolve changed files for incremental tests: {}", e.getMessage());
        }
        return changed;
    }

    private Optional<BuildCommand> compileCommand(Path root) {
        if (Files.isRegularFile(root.resolve("pom.xml"))) {
            return Optional.of(new BuildCommand(mavenCommand(root, List.of("-DskipTests", "compile"))));
        }
        if (Files.isRegularFile(root.resolve("build.gradle")) || Files.isRegularFile(root.resolve("build.gradle.kts"))) {
            return Optional.of(new BuildCommand(gradleCommand(root, List.of("classes"))));
        }
        return Optional.empty();
    }

    private Optional<BuildCommand> testCommand(Path root, Set<String> testClasses) {
        if (testClasses == null || testClasses.isEmpty()) {
            return Optional.empty();
        }
        if (Files.isRegularFile(root.resolve("pom.xml"))) {
            return Optional.of(new BuildCommand(mavenCommand(root, List.of("-Dtest=" + String.join(",", testClasses), "test"))));
        }
        if (Files.isRegularFile(root.resolve("build.gradle")) || Files.isRegularFile(root.resolve("build.gradle.kts"))) {
            List<String> args = new ArrayList<>();
            args.add("test");
            for (String testClass : testClasses) {
                args.add("--tests");
                args.add(testClass);
            }
            return Optional.of(new BuildCommand(gradleCommand(root, args)));
        }
        return Optional.empty();
    }

    private List<String> mavenCommand(Path root, List<String> args) {
        List<String> command = new ArrayList<>();
        if (isWindows() && Files.isRegularFile(root.resolve("mvnw.cmd"))) {
            command.add(root.resolve("mvnw.cmd").toString());
        } else if (!isWindows() && Files.isRegularFile(root.resolve("mvnw"))) {
            command.add(root.resolve("mvnw").toString());
        } else {
            command.add("mvn");
        }
        command.addAll(args);
        return command;
    }

    private List<String> gradleCommand(Path root, List<String> args) {
        List<String> command = new ArrayList<>();
        if (isWindows() && Files.isRegularFile(root.resolve("gradlew.bat"))) {
            command.add(root.resolve("gradlew.bat").toString());
        } else if (!isWindows() && Files.isRegularFile(root.resolve("gradlew"))) {
            command.add(root.resolve("gradlew").toString());
        } else {
            command.add("gradle");
        }
        command.addAll(args);
        return command;
    }

    private CommandResult runCommand(Path root, List<String> command, Duration timeout) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(root.toFile());
            builder.redirectErrorStream(true);
            Process process = builder.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append(System.lineSeparator());
                }
            }
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new CommandResult(-1, "Command timed out after " + timeout.toSeconds() + " seconds.");
            }
            return new CommandResult(process.exitValue(), output.toString());
        } catch (IOException e) {
            return new CommandResult(-1, "Command failed to start: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CommandResult(-1, "Command interrupted.");
        }
    }

    private SourceUnit sourceForSnippet(String code, String sourceRef) {
        if (TYPE_PATTERN.matcher(code).find()) {
            return sourceForFullJava(code, sourceRef);
        }
        if (METHOD_PATTERN.matcher(code).find()) {
            String source = "public class CodeGuardianSnippetCheck {\n" + code + "\n}\n";
            return new SourceUnit("CodeGuardianSnippetCheck.java", source, 1);
        }
        String source = "public class CodeGuardianSnippetCheck {\n"
                + "    public void run() throws Exception {\n"
                + code
                + (code.endsWith("\n") ? "" : "\n")
                + "    }\n"
                + "}\n";
        return new SourceUnit("CodeGuardianSnippetCheck.java", source, 2);
    }

    private SourceUnit sourceForFullJava(String code, String sourceRef) {
        String fileName = Optional.ofNullable(sourceRef)
                .filter(this::isJavaPath)
                .map(value -> Paths.get(value).getFileName().toString())
                .orElseGet(() -> extractPrimaryTypeName(code) + ".java");
        String packagePath = extractPackageName(code)
                .map(value -> value.replace('.', '/') + "/")
                .orElse("");
        return new SourceUnit(packagePath + fileName, code, 0);
    }

    private String extractPrimaryTypeName(String code) {
        Matcher publicMatcher = PUBLIC_TYPE_PATTERN.matcher(code);
        if (publicMatcher.find()) {
            return publicMatcher.group(1);
        }
        Matcher typeMatcher = TYPE_PATTERN.matcher(code);
        if (typeMatcher.find()) {
            return typeMatcher.group(2);
        }
        return "CodeGuardianCompilationUnit";
    }

    private Optional<String> extractPackageName(String code) {
        Matcher matcher = PACKAGE_PATTERN.matcher(code);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private Finding toCompileFinding(List<Diagnostic<? extends JavaFileObject>> diagnostics,
                                     List<SourceUnit> sources,
                                     String sourceRef) {
        Map<URI, SourceUnit> sourceByUri = sources.stream()
                .collect(java.util.stream.Collectors.toMap(SourceUnit::uri, source -> source, (left, right) -> left));
        StringBuilder description = new StringBuilder("Java compilation failed before review.");
        int line = 1;
        int included = 0;
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics) {
            if (diagnostic.getKind() != Diagnostic.Kind.ERROR) {
                continue;
            }
            SourceUnit source = diagnostic.getSource() != null ? sourceByUri.get(diagnostic.getSource().toUri()) : null;
            int sourceLine = adjustLine(diagnostic.getLineNumber(), source);
            if (included == 0 && sourceLine > 0) {
                line = sourceLine;
            }
            description.append("\n")
                    .append(source != null ? source.displayName() : nonBlank(sourceRef, "Java Source"))
                    .append(":")
                    .append(sourceLine > 0 ? sourceLine : "?")
                    .append(": ")
                    .append(diagnostic.getMessage(Locale.ROOT));
            included++;
            if (included >= 10) {
                description.append("\n... more diagnostics omitted");
                break;
            }
        }
        return gateFinding(
                "Java compilation failed",
                nonBlank(sourceRef, "Java Source"),
                line,
                description.toString(),
                "Fix Java compilation errors before running code review. For console output, use System.out.println(\"hello\")."
        );
    }

    private int adjustLine(long diagnosticLine, SourceUnit source) {
        if (diagnosticLine <= 0) {
            return 1;
        }
        int line = (int) diagnosticLine;
        if (source != null && source.lineOffset() > 0) {
            return Math.max(1, line - source.lineOffset());
        }
        return line;
    }

    private Finding gateFinding(String title, String location, int line, String description, String suggestion) {
        return Finding.builder()
                .severity(SeverityEnum.HIGH.getValue())
                .title(title)
                .location(nonBlank(location, "Java Review Gate"))
                .startLine(Math.max(1, line))
                .endLine(Math.max(1, line))
                .description(trimOutput(description))
                .suggestion(suggestion)
                .category("BUG")
                .source("CompileGate")
                .confidence(1.0)
                .build();
    }

    private Optional<Path> findProjectRootForFile(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return Optional.empty();
        }
        Path path = Paths.get(filePath).toAbsolutePath().normalize();
        Path start = Files.isDirectory(path) ? path : path.getParent();
        return findProjectRoot(start);
    }

    private Optional<Path> findProjectRoot(Path startPath) {
        if (startPath == null) {
            return Optional.empty();
        }
        Path current = startPath.toAbsolutePath().normalize();
        if (Files.isRegularFile(current)) {
            current = current.getParent();
        }
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    || Files.isRegularFile(current.resolve("build.gradle"))
                    || Files.isRegularFile(current.resolve("build.gradle.kts"))) {
                return Optional.of(current);
            }
            current = current.getParent();
        }
        return Optional.empty();
    }

    private void addExistingTest(Path projectRoot, Set<String> tests, String relativePath) {
        Path testPath = projectRoot.resolve(relativePath).normalize();
        if (Files.isRegularFile(testPath)) {
            tests.add(toJavaClassName(relativePath));
        }
    }

    private String toJavaClassName(String relativePath) {
        return relativePath
                .replace("\\", "/")
                .replaceFirst("^src/test/java/", "")
                .replaceFirst("\\.java$", "")
                .replace("/", ".");
    }

    private boolean isJavaLanguage(String language) {
        return language != null && language.toLowerCase(Locale.ROOT).startsWith("java");
    }

    private boolean isJavaPath(String path) {
        return path != null && path.toLowerCase(Locale.ROOT).endsWith(".java");
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private String normalizeRelativePath(String value) {
        return value.replace("\\", "/").replaceFirst("^/+", "");
    }

    private String nonBlank(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    private String trimOutput(String value) {
        if (value == null) {
            return "";
        }
        if (value.length() <= MAX_OUTPUT_CHARS) {
            return value;
        }
        return value.substring(0, MAX_OUTPUT_CHARS) + "\n... output truncated";
    }

    private void deleteDirectoryQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        } catch (Exception ignored) {
        }
    }

    private record SourceUnit(String displayName, String source, int lineOffset) {
        URI uri() {
            return URI.create("string:///" + displayName.replace("\\", "/"));
        }
    }

    private static final class SourceJavaFileObject extends SimpleJavaFileObject {
        private final SourceUnit source;

        private SourceJavaFileObject(SourceUnit source) {
            super(source.uri(), Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source.source();
        }
    }

    private record BuildCommand(List<String> command) {
    }

    private record CommandResult(int exitCode, String output) {
    }
}
