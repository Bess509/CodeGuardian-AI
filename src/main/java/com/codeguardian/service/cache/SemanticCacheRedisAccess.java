package com.codeguardian.service.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Optional;

@Slf4j
final class SemanticCacheRedisAccess {

    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;

    SemanticCacheRedisAccess(ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        this.redisTemplateProvider = redisTemplateProvider;
    }

    boolean isAvailable() {
        return redisTemplateProvider.getIfAvailable() != null;
    }

    <T> Optional<T> read(RedisReadCallback<T> callback) {
        StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
        if (redis == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(callback.read(redis));
        } catch (Exception e) {
            log.warn("语义指纹缓存 Redis 读取失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    void write(RedisWriteCallback callback) {
        StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
        if (redis == null) {
            return;
        }
        try {
            callback.write(redis);
        } catch (Exception e) {
            log.warn("语义指纹缓存 Redis 写入失败: {}", e.getMessage());
        }
    }

    @FunctionalInterface
    interface RedisReadCallback<T> {
        T read(StringRedisTemplate redis);
    }

    @FunctionalInterface
    interface RedisWriteCallback {
        void write(StringRedisTemplate redis);
    }
}
