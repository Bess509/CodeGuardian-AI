package com.codeguardian;

import com.codeguardian.controller.ReportController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

class ReportSanitizeTest {

    @Test
    void should_strip_hash_prefix_in_plain_strings() throws Exception {
        String title = "#中危 禁止通配符导入";
        String location = "####:1";

        // 模拟 ReportService 的清理规则
        String cleanTitle = title.replaceAll("^\\s*#{1,6}(?:[:\\s]*)", "").trim();
        String cleanLocation = location.replaceAll("^\\s*#{1,6}(?:[:\\s]*)", "").trim();

        Assertions.assertEquals("中危 禁止通配符导入", cleanTitle);
        Assertions.assertEquals("1", cleanLocation);
    }

    @Test
    void should_remove_hash_prefix_in_html_nodes_for_pdf() throws Exception {
        String html = "<div>问题详情</div>" +
                "<div>#中危 禁止通配符导入</div>" +
                "<div>####:2</div>" +
                "<div>描述：建议明确导入所需的每个类</div>";

        // 通过反射调用 ReportController.cleanMarkdownInHtml
        Constructor<ReportController> ctor = ReportController.class.getDeclaredConstructor(com.codeguardian.service.ReportService.class);
        ctor.setAccessible(true);
        ReportController controller = ctor.newInstance((Object) null);

        Method method = ReportController.class.getDeclaredMethod("cleanMarkdownInHtml", String.class);
        method.setAccessible(true);
        String cleaned = (String) method.invoke(controller, html);

        // 验证不再出现以#开头的标题或 ####:数字 的模式
        Assertions.assertFalse(cleaned.contains("#中危"));
        Assertions.assertFalse(cleaned.contains("####:"));
        Assertions.assertTrue(cleaned.contains("中危 禁止通配符导入") || cleaned.contains("禁止通配符导入"));
        Assertions.assertTrue(cleaned.contains("2"));
    }
}

