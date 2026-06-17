package com.codeguardian.service.ai.tool;

import com.codeguardian.service.ai.dto.ToolDefinition;
import com.codeguardian.service.ai.dto.FunctionDefinition;
import com.codeguardian.service.ai.context.ReviewContextHolder;
import com.codeguardian.service.provenance.EvidenceDraft;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Description;
import org.springframework.stereotype.Service;
import org.springframework.ai.model.function.FunctionCallback;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

@Service
@Slf4j
@RequiredArgsConstructor
public class ToolRegistry {

    private final ApplicationContext applicationContext;
    private final ObjectMapper objectMapper;
    private final Map<String, ToolWrapper> tools = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        // 扫描所有 Function 类型的 Bean
        String[] beanNames = applicationContext.getBeanNamesForType(Function.class);
        
        for (String beanName : beanNames) {
            Object bean = applicationContext.getBean(beanName);
            
            Description description = applicationContext.findAnnotationOnBean(beanName, Description.class);
            if (description != null) {
                registerFunctionBean(beanName, (Function<?, ?>) bean, description.value());
            }
        }
    }

    private void registerFunctionBean(String name, Function<?, ?> function, String description) {
        try {
            // 通过反射查找输入类型
            Method applyMethod = Function.class.getMethod("apply", Object.class);
            // 由于泛型擦除，获取泛型类型比较复杂。
            // 简化策略：假设函数 Bean 在配置中声明了具体类型
            
            // 目前尝试查看实现类或接口
            Type[] genericInterfaces = function.getClass().getGenericInterfaces();
            // 这对于 Lambda 表达式或代理对象可能无效。
            
            // 针对已知 Bean 的特定处理方式（临时方案）
            Class<?> inputType = Object.class;
            
            // 针对已知 Bean 的特定处理方式（临时方案）
            if (name.equals("javaSyntaxAnalysis")) {
                inputType = com.codeguardian.service.ai.tools.JavaSyntaxAnalyzerTool.Request.class;
            } else if (name.equals("semgrepAnalysis")) {
                inputType = com.codeguardian.service.ai.tools.SemgrepAnalyzerTool.Request.class;
            } else {
                // 尝试从泛型接口推断类型，否则跳过
                // 在真实框架中，此逻辑需要更加健壮
                try {
                     for (Type type : genericInterfaces) {
                         if (type instanceof ParameterizedType) {
                             ParameterizedType pt = (ParameterizedType) type;
                             if (pt.getRawType().equals(Function.class)) {
                                 Type[] args = pt.getActualTypeArguments();
                                 if (args.length > 0 && args[0] instanceof Class) {
                                     inputType = (Class<?>) args[0];
                                     break;
                                 }
                             }
                         }
                     }
                     if (inputType == Object.class) {
                         log.warn("未知函数 Bean {}, 跳过 Schema 生成", name);
                         return;
                     }
                } catch (Exception e) {
                    log.warn("无法推断 {} 的输入类型, 跳过", name);
                    return;
                }
            }

            Map<String, Object> parameters = generateJsonSchema(inputType);
            
            ToolDefinition toolDefinition = ToolDefinition.builder()
                    .type("function")
                    .function(FunctionDefinition.builder()
                            .name(name)
                            .description(description)
                            .parameters(parameters)
                            .build())
                    .build();
            
            tools.put(name, new ToolWrapper(toolDefinition, function, inputType));
            log.info("已注册工具: {}", name);
            
        } catch (Exception e) {
            log.error("注册工具 {} 失败", name, e);
        }
    }

    private Map<String, Object> generateJsonSchema(Class<?> clazz) {
        try {
            // 使用 Jackson 的简化 JSON Schema 生成器
            // 注意：理想情况下应使用 jackson-module-jsonSchema 或类似库
            // 这里我们手动为 Record 类构建一个简单的 Schema
            
            Map<String, Object> schema = new HashMap<>();
            schema.put("type", "object");
            
            Map<String, Object> properties = new HashMap<>();
            List<String> required = new ArrayList<>();
            
            for (Field field : clazz.getDeclaredFields()) {
                Map<String, Object> prop = new HashMap<>();
                prop.put("type", "string"); // 默认为 String 类型
                
                if (field.getType().equals(int.class) || field.getType().equals(Integer.class)) {
                    prop.put("type", "integer");
                } else if (field.getType().equals(boolean.class) || field.getType().equals(Boolean.class)) {
                    prop.put("type", "boolean");
                }
                
                JsonPropertyDescription desc = field.getAnnotation(JsonPropertyDescription.class);
                if (desc != null) {
                    prop.put("description", desc.value());
                }
                
                JsonProperty jsonProp =
                        field.getAnnotation(JsonProperty.class);
                if (jsonProp != null && jsonProp.required()) {
                    required.add(field.getName());
                }
                
                // 添加到属性列表
                properties.put(field.getName(), prop);
            }
            
            schema.put("properties", properties);
            if (!required.isEmpty()) {
                schema.put("required", required);
            }
            
            return schema;
        } catch (Exception e) {
            log.error("为 {} 生成 Schema 失败", clazz, e);
            return Map.of();
        }
    }

    public List<ToolDefinition> getTools() {
        return tools.values().stream()
                .map(ToolWrapper::getDefinition)
                .collect(java.util.stream.Collectors.toList());
    }

    public java.util.Set<String> getToolNames() {
        return tools.keySet();
    }

    public List<FunctionCallback> getFunctionCallbacks() {
        return tools.values().stream()
                .map(wrapper -> new FunctionCallback() {
                    @Override
                    public String getName() {
                        return wrapper.getDefinition().getFunction().getName();
                    }

                    @Override
                    public String getDescription() {
                        return wrapper.getDefinition().getFunction().getDescription();
                    }

                    @Override
                    public String getInputTypeSchema() {
                        try {
                            return objectMapper.writeValueAsString(wrapper.getDefinition().getFunction().getParameters());
                        } catch (Exception e) {
                            log.error("Failed to serialize schema for tool {}", getName(), e);
                            return "{}";
                        }
                    }

                    @Override
                    public String call(String functionInput) {
                        log.info("[Function Calling] 收到模型调用请求: 工具={}, 参数={}", getName(), functionInput);
                        try {
                            Object result = execute(getName(), functionInput);
                            String resultJson = objectMapper.writeValueAsString(result);
                            String excerpt = trimForEvidence("input:\n" + functionInput + "\n\noutput:\n" + resultJson);
                            ReviewContextHolder.addEvidence(EvidenceDraft.builder()
                                    .evidenceType("TOOL_CALL")
                                    .sourceName(getName())
                                    .sourceRef("function:" + getName())
                                    .excerpt(excerpt)
                                    .contentHash(sha256(excerpt))
                                    .metadata(Map.of(
                                            "toolName", getName(),
                                            "inputHash", sha256(functionInput),
                                            "outputHash", sha256(resultJson)
                                    ))
                                    .build());
                            return resultJson;
                        } catch (Exception e) {
                            log.error("Failed to execute or serialize result for tool {}", getName(), e);
                            throw new RuntimeException(e);
                        }
                    }
                })
                .collect(java.util.stream.Collectors.toList());
    }

    public Object execute(String toolName, String arguments) {
        ToolWrapper wrapper = tools.get(toolName);
        if (wrapper == null) {
            throw new IllegalArgumentException("Tool not found: " + toolName);
        }
        
        try {
            Object input = objectMapper.readValue(arguments, wrapper.getInputType());
            return wrapper.getFunction().apply((Object)input); // Cast to raw Object to satisfy compiler
        } catch (Exception e) {
            log.error("Error executing tool {}", toolName, e);
            throw new RuntimeException("Tool execution failed: " + e.getMessage());
        }
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    private static class ToolWrapper {
        private ToolDefinition definition;
        private Function function;
        private Class<?> inputType;
    }

    private static String trimForEvidence(String value) {
        if (value == null || value.length() <= 4000) {
            return value;
        }
        return value.substring(0, 4000) + "\n... [truncated]";
    }

    private static String sha256(String value) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((value != null ? value : "").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to calculate SHA-256", e);
        }
    }
}
