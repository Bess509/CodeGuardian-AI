package com.codeguardian.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis预热运行器
 * 用于解决Redis第一次访问超时的问题
 */
@Component
@ConditionalOnProperty(prefix = "sa-token.redis", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class RedisWarmUpRunner implements ApplicationRunner {

    private final StringRedisTemplate redisTemplate;

    @Override
    public void run(ApplicationArguments args) {
        log.info("开始Redis连接预热...");
        try {
            // 执行一次简单的操作来触发连接建立
            redisTemplate.hasKey("warmup-key");
            log.info("Redis连接预热成功！");
        } catch (Exception e) {
            log.warn("Redis连接预热失败: {}", e.getMessage());
            // 不抛出异常，避免影响应用启动，毕竟这只是优化
        }
    }
}
