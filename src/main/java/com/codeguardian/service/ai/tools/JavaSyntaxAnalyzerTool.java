package com.codeguardian.service.ai.tools;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.Problem;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Description;
import org.springframework.stereotype.Component;

import com.github.javaparser.ParserConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Java语法分析工具
 * 
 * <p>使用JavaParser分析Java代码的语法结构和错误</p>
 */
@Component("javaSyntaxAnalysis")
@Description("使用JavaParser分析Java代码的语法结构和错误")
@Slf4j
public class JavaSyntaxAnalyzerTool implements Function<JavaSyntaxAnalyzerTool.Request, JavaSyntaxAnalyzerTool.Response> {

    @Override
    public Response apply(Request request) {
        log.info("[Function Calling] AI 模型请求执行 Java语法分析工具...");
        long startTime = System.currentTimeMillis();
        
        if (request.code == null || request.code.trim().isEmpty()) {
            log.warn("Java语法分析中止：代码为空");
            return new Response(false, List.of("代码为空"), 0, new ArrayList<>(), 0, "未提供代码");
        }

        try {
            ParserConfiguration configuration = new ParserConfiguration();
            configuration.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
            JavaParser javaParser = new JavaParser(configuration);
            log.debug("正在解析Java代码...");
            
            // 1. 尝试直接解析 (CompilationUnit)
            ParseResult<CompilationUnit> result = javaParser.parse(request.code);
            
            // 2. 如果失败，尝试作为类成员解析 (Wrapping in class)
            if (!result.isSuccessful()) {
                log.debug("直接解析失败，尝试包裹在类中解析...");
                String classWrappedCode = "public class DummyWrapper { \n" + request.code + "\n}";
                ParseResult<CompilationUnit> classResult = javaParser.parse(classWrappedCode);
                
                if (classResult.isSuccessful()) {
                    log.info("包裹在类中解析成功");
                    result = classResult;
                } else {
                    // 3. 如果还失败，尝试作为方法体内容解析 (Wrapping in method)
                    log.debug("包裹在类中解析失败，尝试包裹在方法中解析...");
                    String methodWrappedCode = "public class DummyWrapper { public void dummyMethod() { \n" + request.code + "\n} }";
                    ParseResult<CompilationUnit> methodResult = javaParser.parse(methodWrappedCode);
                    
                    if (methodResult.isSuccessful()) {
                        log.info("包裹在方法中解析成功");
                        result = methodResult;
                    }
                }
            }

            Response response = new Response();
            
            if (!result.isSuccessful()) {
                response.valid = false;
                response.problems = result.getProblems().stream()
                        .map(p -> {
                            String location = p.getLocation()
                                .flatMap(l -> l.toRange())
                                .map(r -> String.format("line %d, col %d", r.begin.line, r.begin.column))
                                .orElse("未知位置");
                            return String.format("[%s] %s", location, p.getMessage());
                        })
                        .collect(Collectors.toList());
                response.summary = "发现语法错误: " + response.problems.size();
                log.warn("Java语法分析发现 {} 个错误: {}", response.problems.size(), response.problems);
            } else {
                response.valid = true;
                response.problems = new ArrayList<>();
                result.getResult().ifPresent(cu -> {
                    List<String> methodNames = new ArrayList<>();
                    cu.findAll(MethodDeclaration.class).forEach(method -> {
                        // 过滤掉我们添加的 wrapper 方法
                        if (!method.getNameAsString().equals("dummyMethod")) {
                            methodNames.add(method.getDeclarationAsString(true, true));
                        }
                    });
                    response.methods = methodNames;
                    response.methodCount = methodNames.size();
                    
                    // 过滤掉 wrapper 类
                    long classCount = cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                            .filter(c -> !c.getNameAsString().equals("DummyWrapper"))
                            .count();
                    response.classCount = (int) classCount;
                    
                    response.summary = String.format("分析成功。发现 %d 个类和 %d 个方法。", 
                            response.classCount, response.methodCount);
                });
                log.info("Java语法分析成功: {} 个类, {} 个方法", response.classCount, response.methodCount);
            }
            
            log.info("Java语法分析完成，耗时: {} ms", System.currentTimeMillis() - startTime);
            return response;
        } catch (Exception e) {
            log.error("JavaParser分析过程中发生异常", e);
            return new Response(false, List.of("分析失败: " + e.getMessage()), 0, new ArrayList<>(), 0, "分析过程中发生错误");
        }
    }

    @Data
    @JsonClassDescription("Java语法分析请求")
    public static class Request {
        @JsonPropertyDescription("需要分析的Java源代码")
        @JsonProperty(required = true)
        public String code;
    }

    @Data
    public static class Response {
        public boolean valid;
        public List<String> problems = new ArrayList<>();
        public int methodCount;
        public List<String> methods = new ArrayList<>();
        public int classCount;
        public String summary;

        public Response() {}

        public Response(boolean valid, List<String> problems, int methodCount, List<String> methods, int classCount, String summary) {
            this.valid = valid;
            this.problems = problems;
            this.methodCount = methodCount;
            this.methods = methods;
            this.classCount = classCount;
            this.summary = summary;
        }
    }
}
