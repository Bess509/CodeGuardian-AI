package com.codeguardian.service.integration;

import com.codeguardian.entity.Finding;
import com.codeguardian.entity.ReviewTask;
import com.codeguardian.enums.ReviewTypeEnum;
import com.codeguardian.enums.SeverityEnum;
import com.codeguardian.repository.FindingRepository;
import com.codeguardian.repository.ReviewTaskRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CicdArtifactServiceTest {

    private final ReviewTaskRepository taskRepository = mock(ReviewTaskRepository.class);
    private final FindingRepository findingRepository = mock(FindingRepository.class);
    private final CicdArtifactService service = new CicdArtifactService(
            taskRepository,
            findingRepository,
            null,
            null,
            null,
            null,
            null
    );

    @Test
    void should_use_task_file_scope_when_finding_location_only_has_line_number() {
        ReviewTask task = ReviewTask.builder()
                .id(40L)
                .name("Single file review")
                .reviewType(ReviewTypeEnum.FILE.getValue())
                .scope("F:\\codegraph\\sample\\src\\main\\java\\UserDao.java")
                .build();
        Finding finding = Finding.builder()
                .severity(SeverityEnum.CRITICAL.getValue())
                .title("SQL injection")
                .location("Line 13")
                .startLine(13)
                .endLine(13)
                .description("Do not concatenate user input into SQL.")
                .category("SECURITY")
                .build();
        when(taskRepository.findById(40L)).thenReturn(Optional.of(task));
        when(findingRepository.findByTaskId(40L)).thenReturn(List.of(finding));

        Map<String, Object> sarif = service.buildSarif(40L);

        assertThat(firstArtifactUri(sarif)).isEqualTo("F:/codegraph/sample/src/main/java/UserDao.java");
    }

    @Test
    void should_extract_file_path_from_location_with_line_suffix() {
        ReviewTask task = ReviewTask.builder()
                .id(41L)
                .name("Project review")
                .reviewType(ReviewTypeEnum.PROJECT.getValue())
                .scope("F:\\codegraph\\sample")
                .build();
        Finding finding = Finding.builder()
                .severity(SeverityEnum.CRITICAL.getValue())
                .title("SQL injection")
                .location("src/main/java/UserDao.java: Line 28")
                .startLine(28)
                .endLine(28)
                .description("Do not concatenate user input into SQL.")
                .category("SECURITY")
                .build();
        when(taskRepository.findById(41L)).thenReturn(Optional.of(task));
        when(findingRepository.findByTaskId(41L)).thenReturn(List.of(finding));

        Map<String, Object> sarif = service.buildSarif(41L);

        assertThat(firstArtifactUri(sarif)).isEqualTo("src/main/java/UserDao.java");
    }

    @SuppressWarnings("unchecked")
    private String firstArtifactUri(Map<String, Object> sarif) {
        List<Map<String, Object>> runs = (List<Map<String, Object>>) sarif.get("runs");
        List<Map<String, Object>> results = (List<Map<String, Object>>) runs.get(0).get("results");
        List<Map<String, Object>> locations = (List<Map<String, Object>>) results.get(0).get("locations");
        Map<String, Object> physicalLocation = (Map<String, Object>) locations.get(0).get("physicalLocation");
        Map<String, Object> artifactLocation = (Map<String, Object>) physicalLocation.get("artifactLocation");
        return (String) artifactLocation.get("uri");
    }
}
