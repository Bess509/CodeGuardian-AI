package com.codeguardian.service;

import com.codeguardian.entity.Category;
import com.codeguardian.enums.CategoryEnum;
import com.codeguardian.repository.CategoryRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 类别服务
 * 提供类别的缓存查询
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CategoryService {
    
    private final CategoryRepository categoryRepository;
    private final Map<String, Category> cache = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        refreshCache();
    }

    public void refreshCache() {
        try {
            categoryRepository.findAll().forEach(c -> cache.put(c.getCode(), c));
            log.info("Loaded {} categories into cache.", cache.size());
        } catch (Exception e) {
            log.error("Failed to load categories into cache", e);
        }
    }

    public Category getByCode(String code) {
        return cache.get(code);
    }
    
    public Category getByEnum(CategoryEnum enumVal) {
        if (enumVal == null) return null;
        return cache.get(enumVal.name());
    }
    
    public Category getDefaultCategory() {
        // 默认为 CODE_STYLE 或其他
        Category cat = getByEnum(CategoryEnum.CODE_STYLE);
        if (cat == null && !cache.isEmpty()) {
            return cache.values().iterator().next();
        }
        return cat;
    }
}
