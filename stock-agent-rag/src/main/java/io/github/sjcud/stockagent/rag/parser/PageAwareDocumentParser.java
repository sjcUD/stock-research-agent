package io.github.sjcud.stockagent.rag.parser;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;

/** PDF实体页与扫描图专用策略，Office/HTML等继续交给现有Tika全文解析。 */
public final class PageAwareDocumentParser implements DocumentParser {
    private final DocumentParser fallback;
    private final OcrEngine ocr;

    /** OCR可禁用，原生PDF提取不依赖OCR。 */
    public PageAwareDocumentParser(DocumentParser fallback, OcrEngine ocr) {
        this.fallback = Objects.requireNonNull(fallback);
        this.ocr = ocr;
    }

    /** 按实际PDF签名或受支持图片类型分流，不根据模型猜测页码。 */
    @Override
    public ParsedDocument parse(String id, String revision, byte[] content, String type) {
        if (content == null || content.length == 0 || content.length > 10 * 1024 * 1024)
            throw new IllegalArgumentException("文档大小必须在10MiB内");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        if (content.length > 5
                && content[0] == '%'
                && content[1] == 'P'
                && content[2] == 'D'
                && content[3] == 'F'
                && content[4] == '-') return pdf(id, revision, content, deadline);
        if ("image/png".equals(type) || "image/jpeg".equals(type)) {
            try (ImageInputStream input =
                    ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
                Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw new IllegalArgumentException("图片无法读取");
                ImageReader reader = readers.next();
                try {
                    reader.setInput(input);
                    String format = reader.getFormatName();
                    if (!("PNG".equalsIgnoreCase(format)
                            || "JPEG".equalsIgnoreCase(format)
                            || "JPG".equalsIgnoreCase(format)))
                        throw new IllegalArgumentException("仅支持PNG或JPEG图片");
                    checkPixels(reader.getWidth(0), reader.getHeight(0));
                    BufferedImage image = reader.read(0);
                    return new ParsedDocument(
                            id,
                            revision,
                            Collections.singletonList(
                                    new ParsedPage(1, recognize(image, deadline), true)),
                            false);
                } finally {
                    reader.dispose();
                }
            } catch (IOException ex) {
                throw new IllegalArgumentException("图片读取失败");
            }
        }
        return fallback.parse(id, revision, content, type);
    }

    /** 逐页读取并限制页数、总文本、OCR页数与渲染面积，空白页保留原编号。 */
    private ParsedDocument pdf(String id, String revision, byte[] content, long deadline) {
        try (PDDocument document = Loader.loadPDF(content)) {
            if (document.isEncrypted()) throw new IllegalArgumentException("暂不支持加密PDF");
            if (document.getNumberOfPages() > 100)
                throw new IllegalArgumentException("PDF超过100页，请拆分后上传");
            List<ParsedPage> pages = new ArrayList<ParsedPage>();
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            PDFRenderer renderer = new PDFRenderer(document);
            renderer.setSubsamplingAllowed(true);
            int total = 0, ocrPages = 0;
            for (int i = 0; i < document.getNumberOfPages(); i++) {
                checkDeadline(deadline);
                stripper.setStartPage(i + 1);
                stripper.setEndPage(i + 1);
                String text = stripper.getText(document).trim();
                boolean usedOcr = false;
                if (PdfRasterDetector.containsRaster(document.getPage(i))) {
                    if (++ocrPages > 20) throw new IllegalArgumentException("PDF含图片页超过20页，请拆分后上传");
                    PDPage page = document.getPage(i);
                    checkPixels(
                            (int) Math.ceil(page.getCropBox().getWidth() * 150 / 72),
                            (int) Math.ceil(page.getCropBox().getHeight() * 150 / 72));
                    String recognized = recognize(renderer.renderImageWithDPI(i, 150), deadline);
                    if (recognized.isEmpty()) throw new IllegalArgumentException("图片页未识别到文字，请核验原文或更换清晰文档");
                    // 保留原生文本及OCR补充，不能用页眉代替扫描正文；重复文字可由后续检索策略处理。
                    text = text.isEmpty() || recognized.equals(text) ? recognized : text + "\n" + recognized;
                    usedOcr = true;
                }
                total += text.length();
                if (total > 1000000) throw new IllegalArgumentException("文档提取文本超过100万字符，请拆分后上传");
                pages.add(new ParsedPage(i + 1, text, usedOcr));
            }
            return new ParsedDocument(id, revision, pages, true);
        } catch (IOException ex) {
            throw new IllegalArgumentException("PDF无法读取或已加密");
        }
    }

    /** OCR无配置时明确失败，不能把扫描页当作完整索引成功。 */
    private String recognize(BufferedImage image, long deadline) {
        if (ocr == null) throw new IllegalArgumentException("扫描文档需要启用OCR");
        checkDeadline(deadline);
        String text = ocr.recognize(
                image, Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
        if (text == null || text.length() > 1000000)
            throw new IllegalArgumentException("文档提取文本超过100万字符，请拆分后上传");
        checkDeadline(deadline);
        return text;
    }

    private void checkDeadline(long deadline) {
        if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("解析已中断");
        if (System.nanoTime() >= deadline) throw new IllegalArgumentException("文档解析超过时限，请拆分后上传");
    }

    private void checkPixels(int width, int height) {
        if (width < 1 || height < 1 || (long) width * height > 20000000)
            throw new IllegalArgumentException("页面图片超过20MP限制");
    }
}
