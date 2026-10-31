package io.github.sjcud.stockagent.rag.parser;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** 验证 Tika 多格式解析器的文本提取和类型边界。 */
public class TikaDocumentParserTest {

    @Test
    public void shouldExtractHtmlText() {
        TikaDocumentParser parser = new TikaDocumentParser();
        ParsedDocument result = parser.parse(
                "html-001",
                "rev-1",
                "<html><body><h1>收入报告</h1><p>利润增长</p></body></html>"
                        .getBytes(StandardCharsets.UTF_8),
                "text/html; charset=UTF-8");

        assertTrue(result.getText().contains("收入报告"));
        assertTrue(result.getText().contains("利润增长"));
    }

    @Test
    public void shouldRejectUnsupportedType() {
        TikaDocumentParser parser = new TikaDocumentParser();

        assertThrows(IllegalArgumentException.class, () -> parser.parse(
                "binary-001", "rev-1", new byte[] {1, 2, 3}, "image/png"));
    }
    @org.junit.jupiter.api.Test
    public void shouldKeepTailBeyondDefaultTikaLimit() {
        StringBuilder content=new StringBuilder();
        for(int index=0;index<120000;index++)content.append('a');
        content.append("TAIL_MARKER_COMPLETE");
        ParsedDocument result=new TikaDocumentParser().parse("long","v1",content.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),"text/plain");
        org.junit.jupiter.api.Assertions.assertTrue(result.getText().contains("TAIL_MARKER_COMPLETE"));
    }
    @org.junit.jupiter.api.Test
    public void shouldRejectTextAboveExplicitBoundInsteadOfSilentlyTruncating() {
        byte[] content=new byte[1000100]; java.util.Arrays.fill(content,(byte)'a');
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                ()->new TikaDocumentParser().parse("oversize","v1",content,"text/plain"));
    }
    @Test
    public void shouldExtractPdfTextWithUpdatedParser() throws Exception {
        java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream();
        try(org.apache.pdfbox.pdmodel.PDDocument pdf=new org.apache.pdfbox.pdmodel.PDDocument()) {
            org.apache.pdfbox.pdmodel.PDPage page=new org.apache.pdfbox.pdmodel.PDPage();pdf.addPage(page);
            try(org.apache.pdfbox.pdmodel.PDPageContentStream content=new org.apache.pdfbox.pdmodel.PDPageContentStream(pdf,page)) {
                content.beginText();content.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                        org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA),12);
                content.newLineAtOffset(40,700);content.showText("PDF_UPLOAD_TEXT");content.endText();
            }
            pdf.save(output);
        }
        assertTrue(new TikaDocumentParser().parse("pdf","v1",output.toByteArray(),"application/pdf").getText().contains("PDF_UPLOAD_TEXT"));
    }
    @Test
    public void shouldExtractOfficeTextWithUpdatedParser() throws Exception {
        java.io.ByteArrayOutputStream wordBytes=new java.io.ByteArrayOutputStream();
        try(org.apache.poi.xwpf.usermodel.XWPFDocument word=new org.apache.poi.xwpf.usermodel.XWPFDocument()) {
            word.createParagraph().createRun().setText("WORD_UPLOAD_TEXT");word.write(wordBytes);
        }
        assertTrue(new TikaDocumentParser().parse("word","v1",wordBytes.toByteArray(),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document").getText().contains("WORD_UPLOAD_TEXT"));
        java.io.ByteArrayOutputStream excelBytes=new java.io.ByteArrayOutputStream();
        try(org.apache.poi.xssf.usermodel.XSSFWorkbook excel=new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            excel.createSheet("sample").createRow(0).createCell(0).setCellValue("EXCEL_UPLOAD_TEXT");excel.write(excelBytes);
        }
        assertTrue(new TikaDocumentParser().parse("excel","v1",excelBytes.toByteArray(),
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet").getText().contains("EXCEL_UPLOAD_TEXT"));
    }
}
