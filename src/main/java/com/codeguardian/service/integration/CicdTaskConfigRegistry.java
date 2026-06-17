package com.codeguardian.service.integration;

import com.codeguardian.dto.integration.CicdTaskConfig;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class CicdTaskConfigRegistry {

    private final ConcurrentMap<Long, CicdTaskConfig> configs = new ConcurrentHashMap<>();

    public void put(CicdTaskConfig config) {
        if (config != null && config.getTaskId() != null) {
            configs.put(config.getTaskId(), config);
        }
    }

    public Optional<CicdTaskConfig> get(Long taskId) {
        if (taskId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(configs.get(taskId));
    }
}
