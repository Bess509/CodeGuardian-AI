package com.codeguardian.controller;

import com.codeguardian.entity.ReviewReport;
import com.codeguardian.service.ReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 报告控制器
 */
@RestController
@RequestMapping("/api/report")
@RequiredArgsConstructor
@Slf4j
public class ReportController {
    
    private final ReportService reportService;
    
    /**
     * 生成审查报告
     */
    @PostMapping("/{taskId}")
    public ResponseEntity<String> generateReport(@PathVariable("taskId") Long taskId) {
        try {
            ReviewReport report = reportService.generateReport(taskId);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("{\"message\":\"报告生成成功\",\"reportId\":" + report.getId() + "}");
        } catch (Exception e) {
            log.error("生成报告失败: taskId={}", taskId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("{\"error\":\"" + e.getMessage() + "\"}");
        }
    }
    
    /**
     * 获取HTML格式报告
     */
    @GetMapping("/{taskId}/html")
    public ResponseEntity<String> getHTMLReport(@PathVariable("taskId") Long taskId) {
        try {
            ReviewReport report = reportService.generateReport(taskId);
            if (report.getHtmlContent() == null || report.getHtmlContent().isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_HTML_VALUE)
                    .body(report.getHtmlContent());
        } catch (Exception e) {
            log.error("获取HTML报告失败: taskId={}", taskId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
    
    /**
     * 获取Markdown格式报告
     */
    @GetMapping("/{taskId}/markdown")
    public ResponseEntity<String> getMarkdownReport(@PathVariable("taskId") Long taskId) {
        try {
            ReviewReport report = reportService.generateReport(taskId);
            if (report.getMarkdownContent() == null || report.getMarkdownContent().isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN_VALUE)
                    .body(report.getMarkdownContent());
        } catch (Exception e) {
            log.error("获取Markdown报告失败: taskId={}", taskId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * 获取PDF格式报告
     */
    @GetMapping("/{taskId}/pdf")
    public ResponseEntity<byte[]> getPdfReport(@PathVariable("taskId") Long taskId) {
        try {
            ReviewReport report = reportService.generateReport(taskId);
            if (report.getHtmlContent() == null || report.getHtmlContent().isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            // 将HTML转换为PDF
            byte[] pdfBytes = ReportPdfRenderer.render(report.getHtmlContent());
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_PDF);
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            headers.setContentDisposition(org.springframework.http.ContentDisposition
                    .attachment()
                    .filename("review_report_" + taskId + "_" + timestamp + ".pdf")
                    .build());

            return new org.springframework.http.ResponseEntity<>(pdfBytes, headers, org.springframework.http.HttpStatus.OK);
        } catch (Exception e) {
            log.error("获取PDF报告失败: taskId= {}", taskId, e);
            return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
    
    /**
     * 预处理HTML以适配PDF生成
     * 1. 移除外部资源引用
     * 2. 展开CSS变量为实际颜色值
     * 3. 内联代码高亮样式
     * 4. 替换图标为文本
     * 5. 规范化自闭合标签
     * 6. 清理Markdown语法
     * 7. 转义未转义的&符号
     */
    private String prepareHtmlForPdf(String html) {
        return ReportPdfHtmlPreparer.prepare(html);
    }

    private String cleanMarkdownInHtml(String html) {
        return ReportPdfHtmlPreparer.cleanMarkdownInHtml(html);
    }
}
