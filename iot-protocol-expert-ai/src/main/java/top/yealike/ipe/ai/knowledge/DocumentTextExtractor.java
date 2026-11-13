package top.yealike.ipe.ai.knowledge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 文档文本抽取：PDF 按页抽取以保留页码；纯文本单页，页码标注为不可用。 */
@Component
public class DocumentTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(DocumentTextExtractor.class);

    public List<TextChunker.Page> extract(byte[] content, String contentType, String filename) {
        String type = contentType == null ? "" : contentType.toLowerCase();
        String name = filename == null ? "" : filename.toLowerCase();
        if (type.contains("pdf") || name.endsWith(".pdf")) {
            return extractPdf(content);
        }
        return List.of(new TextChunker.Page(null, decode(content)));
    }

    private List<TextChunker.Page> extractPdf(byte[] content) {
        List<TextChunker.Page> pages = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(content)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            int total = document.getNumberOfPages();
            for (int page = 1; page <= total; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text;
                try {
                    text = stripper.getText(document);
                } catch (IOException exception) {
                    log.warn("pdf page extraction failed: page={}", page, exception);
                    text = "";
                }
                pages.add(new TextChunker.Page(page, text));
            }
        } catch (IOException | RuntimeException exception) {
            log.warn("pdf extraction failed", exception);
            throw new IllegalArgumentException("协议文档解析失败，可能是加密或损坏的 PDF");
        }
        return pages;
    }

    private static String decode(byte[] content) {
        if (content.length >= 3
                && (content[0] & 0xFF) == 0xEF
                && (content[1] & 0xFF) == 0xBB
                && (content[2] & 0xFF) == 0xBF) {
            return new String(content, 3, content.length - 3, StandardCharsets.UTF_8);
        }
        return new String(content, StandardCharsets.UTF_8);
    }
}
