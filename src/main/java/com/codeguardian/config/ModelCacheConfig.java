package com.codeguardian.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Configuration;
import lombok.extern.slf4j.Slf4j;

@Configuration
@Slf4j
public class ModelCacheConfig implements ApplicationRunner {

    @Value("${djl.cache.dir:${user.home}/.cache/djl}")
    private String djlCacheDir;

    @Override
    public void run(ApplicationArguments args) {
        try {
            System.setProperty("DJL_CACHE_DIR", djlCacheDir);
            log.info("DJL cache directory set to: {}", djlCacheDir);
        } catch (Exception e) {
            log.warn("Failed to set DJL cache directory: {}", e.getMessage());
        }
    }
}
