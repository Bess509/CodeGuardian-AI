package com.codeguardian.service.auth;

import cn.dev33.satoken.dao.SaTokenDao;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Spring Data Redis 的 Sa-Token 持久层实现。
 *
 * <p>职责：提供 Token、Session、集合等数据在 Redis 中的读写与过期时间管理，
 * 与 Sa-Token 1.39+ 的 `SaTokenDao` 接口保持兼容。</p>
 */
public class RedisSaTokenDao implements SaTokenDao {

    private final StringRedisTemplate redis;
    private final RedisTemplate<String, Object> objectRedis;

    /**
     * 构造函数
     *
     * @param redis 字符串读写模板（用于简单键值）
     * @param objectRedis 对象读写模板（用于 Session 等对象）
     */
    public RedisSaTokenDao(StringRedisTemplate redis, RedisTemplate<String, Object> objectRedis) {
        this.redis = redis;
        this.objectRedis = objectRedis;
    }

    /**
     * 读取字符串值
     */
    @Override
    public String get(String key) {
        if (!StringUtils.hasText(key)) return null;
        ValueOperations<String, String> ops = redis.opsForValue();
        return ops.get(key);
    }

    /**
     * 写入字符串值并设置过期时间（-1 表示永不过期）
     */
    @Override
    public void set(String key, String value, long timeout) {
        if (!StringUtils.hasText(key)) return;
        ValueOperations<String, String> ops = redis.opsForValue();
        if (timeout == -1) {
            ops.set(key, value);
        } else {
            ops.set(key, value, Duration.ofSeconds(timeout));
        }
    }

    /**
     * 覆盖字符串值并保持原 TTL
     */
    @Override
    public void update(String key, String value) {
        set(key, value, getTimeout(key));
    }

    /**
     * 删除键
     */
    @Override
    public void delete(String key) {
        if (!StringUtils.hasText(key)) return;
        redis.delete(key);
    }

    /**
     * 获取键剩余过期秒数（-1 永不过期，-2 不存在）
     */
    @Override
    public long getTimeout(String key) {
        if (!StringUtils.hasText(key)) return -2;
        Long expire = redis.getExpire(key, TimeUnit.SECONDS);
        return expire == null ? -2 : expire;
    }

    /**
     * 更新键过期时间（-1 置为永不过期）
     */
    @Override
    public void updateTimeout(String key, long timeout) {
        if (!StringUtils.hasText(key)) return;
        if (timeout == -1) {
            redis.persist(key);
        } else {
            redis.expire(key, timeout, TimeUnit.SECONDS);
        }
    }

    /**
     * 读取对象值
     */
    public Object getObject(String key) {
        if (!StringUtils.hasText(key)) return null;
        return objectRedis.opsForValue().get(key);
    }

    /**
     * 写入对象并设置过期时间
     */
    public void setObject(String key, Object object, long timeout) {
        if (!StringUtils.hasText(key)) return;
        if (timeout == -1) {
            objectRedis.opsForValue().set(key, object);
        } else {
            objectRedis.opsForValue().set(key, object, timeout, TimeUnit.SECONDS);
        }
    }

    /**
     * 覆盖对象并保持原 TTL
     */
    public void updateObject(String key, Object object) {
        setObject(key, object, getObjectTimeout(key));
    }

    /**
     * 删除对象键
     */
    public void deleteObject(String key) {
        delete(key);
    }

    /**
     * 更新对象过期时间
     */
    @Override
    public void updateObjectTimeout(String key, long timeout) {
        updateTimeout(key, timeout);
    }

    /**
     * 获取对象剩余过期秒数
     */
    @Override
    public long getObjectTimeout(String key) {
        return getTimeout(key);
    }

    /**
     * 读取列表
     */
    public List<String> getList(String key) {
        ListOperations<String, String> ops = redis.opsForList();
        Long size = ops.size(key);
        if (size == null || size <= 0) return List.of();
        return ops.range(key, 0, -1);
    }

    /**
     * 写入列表并设置过期时间
     */
    public void setList(String key, List<String> list, long timeout) {
        delete(key);
        if (list == null || list.isEmpty()) return;
        ListOperations<String, String> ops = redis.opsForList();
        ops.rightPushAll(key, list);
        if (timeout != -1) {
            redis.expire(key, timeout, TimeUnit.SECONDS);
        }
    }

    /**
     * 覆盖列表并保持原 TTL
     */
    public void updateList(String key, List<String> list) {
        long ttl = getTimeout(key);
        setList(key, list, ttl);
    }

    /**
     * 删除列表键
     */
    public void deleteList(String key) {
        delete(key);
    }

    /**
     * 读取 Hash
     */
    public Map<String, String> getMap(String key) {
        HashOperations<String, String, String> ops = redis.opsForHash();
        Map<String, String> map = ops.entries(key);
        return map == null ? Map.of() : map;
    }

    /**
     * 写入 Hash 并设置过期时间
     */
    public void setMap(String key, Map<String, String> map, long timeout) {
        delete(key);
        if (map == null || map.isEmpty()) return;
        HashOperations<String, String, String> ops = redis.opsForHash();
        ops.putAll(key, map);
        if (timeout != -1) {
            redis.expire(key, timeout, TimeUnit.SECONDS);
        }
    }

    /**
     * 覆盖 Hash 并保持原 TTL
     */
    public void updateMap(String key, Map<String, String> map) {
        long ttl = getTimeout(key);
        setMap(key, map, ttl);
    }

    /**
     * 删除 Hash 键
     */
    public void deleteMap(String key) {
        delete(key);
    }

    /**
     * 搜索数据（按前缀+关键词），支持分页与可选排序
     */
    @Override
    public List<String> searchData(String prefix, String keyword, int start, int size, boolean sort) {
        var keys = redis.keys(prefix + "*" + (keyword == null ? "" : keyword) + "*");
        var list = keys == null ? List.<String>of() : List.copyOf(keys);
        if (sort && !list.isEmpty()) {
            list = list.stream().sorted().toList();
        }
        int from = Math.max(0, start);
        int to = size <= 0 ? list.size() : Math.min(list.size(), from + size);
        if (from >= list.size()) return List.of();
        return list.subList(from, to);
    }
}

