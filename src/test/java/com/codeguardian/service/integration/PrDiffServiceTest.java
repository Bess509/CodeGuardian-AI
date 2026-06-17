package com.codeguardian.service.integration;

import com.codeguardian.entity.Finding;
import com.codeguardian.enums.SeverityEnum;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PrDiffServiceTest {

    private final PrDiffService service = new PrDiffService();

    @Test
    void should_parse_added_lines_by_file() {
        String diff = """
                diff --git a/src/App.java b/src/App.java
                --- a/src/App.java
                +++ b/src/App.java
                @@ -10,0 +11,2 @@
                +String token = request.getParameter("token");
                +log.info(token);
                diff --git a/src/Other.java b/src/Other.java
                --- a/src/Other.java
                +++ b/src/Other.java
                @@ -5 +5 @@
                -old();
                +newCall();
                """;

        Map<String, Set<Integer>> added = service.addedLinesByFile(diff);

        assertThat(added.get("src/App.java")).containsExactly(11, 12);
        assertThat(added.get("src/Other.java")).containsExactly(5);
    }

    @Test
    void should_publish_only_findings_on_added_lines() {
        String diff = """
                diff --git a/src/App.java b/src/App.java
                --- a/src/App.java
                +++ b/src/App.java
                @@ -10,0 +11,1 @@
                +String token = request.getParameter("token");
                """;
        Finding publishable = Finding.builder()
                .id(1L)
                .severity(SeverityEnum.HIGH.getValue())
                .title("Token exposure")
                .location("src/App.java:11")
                .startLine(11)
                .description("Sensitive token is logged.")
                .suggestion("Avoid logging secrets.")
                .evidenceHash("abc123")
                .build();
        Finding notPublishable = Finding.builder()
                .id(2L)
                .severity(SeverityEnum.MEDIUM.getValue())
                .title("Existing issue")
                .location("src/App.java:10")
                .startLine(10)
                .description("Existing context line.")
                .build();

        List<PrInlineComment> comments = service.buildInlineComments(List.of(publishable, notPublishable), diff);

        assertThat(comments).hasSize(2);
        assertThat(comments.get(0).getPublishable()).isTrue();
        assertThat(comments.get(0).getReason()).isEqualTo("mapped_to_added_diff_line");
        assertThat(comments.get(0).getBody()).contains("Evidence: `abc123`");
        assertThat(comments.get(1).getPublishable()).isFalse();
        assertThat(comments.get(1).getReason()).isEqualTo("line_not_added_in_diff");
    }
}
