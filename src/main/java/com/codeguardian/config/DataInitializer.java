package com.codeguardian.config;

import com.codeguardian.entity.Category;
import com.codeguardian.enums.CategoryEnum;
import com.codeguardian.repository.CategoryRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * 数据初始化器
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private final CategoryRepository categoryRepository;
    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public void run(String... args) throws Exception {
        initializeCategories();
        migrateFindingsSeverityValues();
    }

    private void initializeCategories() {
        if (categoryRepository.count() == 0) {
            log.info("Initializing categories...");
            Arrays.stream(CategoryEnum.values()).forEach(enumVal -> {
                Category category = Category.builder()
                        .code(enumVal.name())
                        .name(enumVal.getDesc())
                        .description(enumVal.getDesc() + " category")
                        .build();
                categoryRepository.save(category);
            });
            log.info("Categories initialized.");
        }
    }

    private void migrateFindingsSeverityValues() {
        try {
            int updated = entityManager.createNativeQuery(
                "UPDATE findings SET severity = CASE UPPER(severity::text) " +
                "WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1 WHEN 'MEDIUM' THEN 2 WHEN 'LOW' THEN 3 ELSE severity END " +
                "WHERE severity::text ~ '^[A-Za-z]+'"
            ).executeUpdate();
            if (updated > 0) {
                log.info("Migrated {} findings severity values from text to integers", updated);
            }
        } catch (Exception e) {
            log.warn("Severity migration skipped: {}", e.getMessage());
        }
    }
}
