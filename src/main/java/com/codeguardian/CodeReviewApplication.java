package com.codeguardian;

import com.codeguardian.mcp.StdioMcpLauncher;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 代码审查AI Agent主应用
 * 
 * @author CodeGuardian Team
 * @version 1.0.0
 */
@SpringBootApplication(exclude = {
    org.springframework.ai.autoconfigure.openai.OpenAiAutoConfiguration.class
})
public class CodeReviewApplication {
    
    public static void main(String[] args) {
        if (StdioMcpLauncher.shouldStart(args)) {
            StdioMcpLauncher.run(args, System.in, System.out, System.err);
            return;
        }
        SpringApplication.run(CodeReviewApplication.class, args);
    }
}

