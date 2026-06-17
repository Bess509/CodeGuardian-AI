package com.codeguardian.service;

import com.codeguardian.dto.ReviewRequestDTO;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

public class ReviewServiceGitNameGenerationTest {

    private String invokeGenerate(ReviewRequestDTO req) throws Exception {
        ReviewService svc = new ReviewService(null, null, null, null, null, null, null, null, null, null, null);
        Method m = ReviewService.class.getDeclaredMethod("generateTaskName", ReviewRequestDTO.class);
        m.setAccessible(true);
        return (String) m.invoke(svc, req);
    }

    @Test
    void should_prefix_git_and_use_project_base_when_projectPath_present() throws Exception {
        ReviewRequestDTO req = ReviewRequestDTO.builder()
                .reviewType("GIT")
                .projectPath("/home/user/repos/my-app")
                .build();
        String name = invokeGenerate(req);
        assertEquals("my-app", name);
    }

    @Test
    void should_prefix_git_and_use_repo_name_from_url_when_no_projectPath() throws Exception {
        ReviewRequestDTO req = ReviewRequestDTO.builder()
                .reviewType("GIT")
                .gitUrl("https://github.com/org/service-api.git")
                .build();
        String name = invokeGenerate(req);
        assertEquals("service-api", name);
    }
}
