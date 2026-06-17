package com.codeguardian.controller;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

@Slf4j
final class ReportPdfRenderer {

    private static final String FONT_PATH = "fonts/ArialUnicode.ttf";
    private static final long MIN_FONT_BYTES = 1024;

    private ReportPdfRenderer() {
    }

    static byte[] render(String html) throws Exception {
        String preparedHtml = ReportPdfHtmlPreparer.prepare(html);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfRendererBuilder builder = new PdfRendererBuilder();
        builder.useFastMode();

        boolean fontLoaded = tryRegisterFont(builder);
        if (!fontLoaded) {
            preparedHtml = removeCustomFontReferences(preparedHtml);
        }

        builder.withHtmlContent(preparedHtml, null);
        builder.toStream(out);

        try {
            builder.run();
        } catch (Exception e) {
            if (fontLoaded && e.getMessage() != null && e.getMessage().contains("font")) {
                log.warn("PDF generation failed with custom font, retrying with system font: {}", e.getMessage());
                out.reset();
                builder = new PdfRendererBuilder();
                builder.useFastMode();
                builder.withHtmlContent(removeCustomFontReferences(preparedHtml), null);
                builder.toStream(out);
                builder.run();
            } else {
                throw e;
            }
        }
        return out.toByteArray();
    }

    private static boolean tryRegisterFont(PdfRendererBuilder builder) {
        try {
            Resource fontResource = new ClassPathResource(FONT_PATH);
            if (!fontResource.exists()) {
                log.warn("PDF font file is missing: {}", FONT_PATH);
                return false;
            }
            File fontFile = resolveFontFile(fontResource);
            if (!isUsableFontFile(fontFile)) {
                log.warn("PDF font file is not usable: {}", fontFile != null ? fontFile.getAbsolutePath() : "null");
                return false;
            }
            builder.useFont(fontFile, "ArialUnicode");
            log.debug("Loaded PDF font: {} ({} bytes)", fontFile.getAbsolutePath(), fontFile.length());
            return true;
        } catch (Exception e) {
            log.warn("Unable to load PDF font, system font will be used: {}", e.getMessage());
            return false;
        }
    }

    private static File resolveFontFile(Resource fontResource) throws IOException {
        try {
            File fontFile = fontResource.getFile();
            if (fontFile.exists() && fontFile.canRead() && fontFile.length() >= MIN_FONT_BYTES) {
                return fontFile;
            }
        } catch (IOException ignored) {
            // In packaged jars resources are not always addressable as files.
        }

        try (InputStream is = fontResource.getInputStream()) {
            if (is == null) {
                throw new IOException("font input stream is unavailable");
            }
            File fontFile = File.createTempFile("ArialUnicode", ".ttf");
            fontFile.deleteOnExit();
            long copied = Files.copy(is, fontFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            if (copied == 0 || fontFile.length() < MIN_FONT_BYTES) {
                throw new IOException("temporary font file is too small: " + fontFile.length());
            }
            return fontFile;
        }
    }

    private static boolean isUsableFontFile(File fontFile) {
        if (fontFile == null || !fontFile.exists() || !fontFile.canRead() || fontFile.length() < MIN_FONT_BYTES) {
            return false;
        }
        try (FileInputStream fis = new FileInputStream(fontFile)) {
            byte[] header = new byte[4];
            if (fis.read(header) != 4) {
                return false;
            }
            boolean ttf = header[0] == 0x00 && header[1] == 0x01 && header[2] == 0x00 && header[3] == 0x00;
            boolean otf = header[0] == 'O' && header[1] == 'T' && header[2] == 'T' && header[3] == 'O';
            boolean ttc = header[0] == 't' && header[1] == 't' && header[2] == 'c' && header[3] == 'f';
            return ttf || otf || ttc;
        } catch (IOException e) {
            return false;
        }
    }

    private static String removeCustomFontReferences(String html) {
        return html.replace("font-family:ArialUnicode,", "font-family:")
                .replace("font-family:ArialUnicode;", "font-family:sans-serif;")
                .replaceAll("ArialUnicode", "sans-serif");
    }
}
