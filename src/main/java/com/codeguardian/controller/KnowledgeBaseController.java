package com.codeguardian.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.codeguardian.service.rag.KnowledgeBaseService;
import com.codeguardian.service.rag.KnowledgeDocument;
import com.codeguardian.util.ViewModelUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/admin/knowledge")
@RequiredArgsConstructor
@Slf4j
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    @GetMapping
    @SaCheckPermission("ADMIN")
    public String list(Model model, HttpSession session,
                       @RequestParam(defaultValue = "1") int page,
                       @RequestParam(defaultValue = "6") int size,
                       @RequestParam(required = false) String keyword) {
        ViewModelUtils.populateUserInfo(model, session);
        Page<KnowledgeDocument> docPage = knowledgeBaseService.getDocuments(page, size, keyword);
        model.addAttribute("page", docPage);
        model.addAttribute("keyword", keyword);
        model.addAttribute("activeTab", "documents");
        return "admin/knowledge";
    }

    @GetMapping("/bases")
    @SaCheckPermission("ADMIN")
    public String bases(Model model, HttpSession session) {
        ViewModelUtils.populateUserInfo(model, session);
        Map<String, Object> stats = knowledgeBaseService.getStats();
        model.addAttribute("kb", stats);
        model.addAttribute("activeTab", "bases");
        return "admin/knowledge-bases";
    }

    @GetMapping("/detail/{id}")
    @SaCheckPermission("ADMIN")
    public String detail(@PathVariable String id, Model model, HttpSession session) {
        ViewModelUtils.populateUserInfo(model, session);
        KnowledgeDocument doc = knowledgeBaseService.getDocumentById(id);
        if (doc == null) {
            return "redirect:/admin/knowledge";
        }
        model.addAttribute("doc", doc);
        return "admin/knowledge-detail";
    }

    @GetMapping("/download/{id}")
    @SaCheckPermission("ADMIN")
    public ResponseEntity<InputStreamResource> download(@PathVariable String id) {
        KnowledgeDocument doc = knowledgeBaseService.getDocumentById(id);
        if (doc == null || doc.getMinioObjectName() == null) {
            return ResponseEntity.notFound().build();
        }
        
        try {
            InputStream inputStream = knowledgeBaseService.getFileStream(doc.getMinioObjectName());
            InputStreamResource resource = new InputStreamResource(inputStream);
            
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=" + doc.getTitle())
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(doc.getFileSize() != null ? doc.getFileSize() : 0)
                    .body(resource);
        } catch (Exception e) {
            log.error("Download failed", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/upload")
    @SaCheckPermission("ADMIN")
    public String upload(@RequestParam("file") MultipartFile file, RedirectAttributes redirectAttributes) {
        if (file.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "请选择文件");
            return "redirect:/admin/knowledge";
        }
        
        try {
            // Support generic document upload (Tika handles formats)
            knowledgeBaseService.uploadDocument(file);
            redirectAttributes.addFlashAttribute("success", "文档上传成功并已处理");
        } catch (Exception e) {
            log.error("Upload failed", e);
            redirectAttributes.addFlashAttribute("error", "上传失败: " + e.getMessage());
        }
        
        return "redirect:/admin/knowledge";
    }

    @PostMapping("/delete/{id}")
    @SaCheckPermission("ADMIN")
    public String delete(@PathVariable String id, RedirectAttributes redirectAttributes) {
        try {
            knowledgeBaseService.deleteDocument(id);
            redirectAttributes.addFlashAttribute("success", "文档删除成功");
        } catch (Exception e) {
            log.error("Delete failed", e);
            redirectAttributes.addFlashAttribute("error", "删除失败: " + e.getMessage());
        }
        return "redirect:/admin/knowledge";
    }
}
