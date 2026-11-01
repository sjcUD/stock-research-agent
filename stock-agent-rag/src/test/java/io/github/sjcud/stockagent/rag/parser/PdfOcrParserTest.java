package io.github.sjcud.stockagent.rag.parser;

import static org.junit.jupiter.api.Assertions.*;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.*;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;

/** 原生文本、扫描图和空白页必须保留原PDF的物理次序。 */
public class PdfOcrParserTest {
    @Test
    public void keepsBlankPageAndExtractsActualThirdPage() throws Exception {
        byte[] pdf = sample();
        java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        ParsedDocument doc =
                new PageAwareDocumentParser(
                                new TikaDocumentParser(),
                                (image, time) -> {
                                    calls.incrementAndGet();
                                    return "SCANNED 12345";
                                })
                        .parse("d", "v", pdf, "application/pdf");
        assertEquals(3, doc.getPages().size());
        assertEquals("", doc.getPages().get(1).getText());
        assertTrue(doc.getPages().get(2).getText().contains("NATIVE THIRD"));
        assertEquals(1, calls.get());
        assertTrue(doc.isPhysicalPages());
    }

    @Test
    public void shouldRecognizeActualScannedPdfWhenExplicitlyEnabled() throws Exception {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("OCR_INTEGRATION")));
        TesseractOcrEngine ocr =
                new TesseractOcrEngine(
                        "tesseract", "eng", System.getenv("STOCK_AGENT_RAG_OCR_DATA_PATH"));
        assertTrue(ocr.available());
        ParsedDocument doc =
                new PageAwareDocumentParser(new TikaDocumentParser(), ocr)
                        .parse("d", "v", sample(), "application/pdf");
        assertTrue(doc.getPages().get(0).getText().contains("12345"));
        assertTrue(doc.getPages().get(0).isOcr());
    }

    @Test public void imageOcrMustEnforceSameMillionCharacterLimit() throws Exception {
        BufferedImage image=new BufferedImage(10,10,BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out=new ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",out);
        String huge=new String(new char[1000001]).replace('\0','a');
        assertThrows(IllegalArgumentException.class,()->new PageAwareDocumentParser(new TikaDocumentParser(),(page,time)->huge).parse("d","v",out.toByteArray(),"image/png"));
    }
    @Test public void mixedPageHeaderMustNotHideScannedBody() throws Exception {
        try(PDDocument doc=org.apache.pdfbox.Loader.loadPDF(sample());ByteArrayOutputStream out=new ByteArrayOutputStream()){
            try(PDPageContentStream stream=new PDPageContentStream(doc,doc.getPage(0),PDPageContentStream.AppendMode.APPEND,true)){
                stream.beginText();stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);stream.newLineAtOffset(50,740);stream.showText("Page 1");stream.endText();
            }
            doc.save(out);
            ParsedDocument parsed=new PageAwareDocumentParser(new TikaDocumentParser(),(page,time)->"OCR BODY 12345").parse("d","v",out.toByteArray(),"application/pdf");
            assertTrue(parsed.getPages().get(0).getText().contains("12345"));assertTrue(parsed.getPages().get(0).isOcr());
        }
    }
    @Test public void vectorOnlyBlankPageMustNotRequireOcr() throws Exception {
        try(PDDocument doc=org.apache.pdfbox.Loader.loadPDF(sample());ByteArrayOutputStream out=new ByteArrayOutputStream()){
            doc.removePage(0);
            try(PDPageContentStream stream=new PDPageContentStream(doc,doc.getPage(0))){stream.addRect(10,10,100,100);stream.stroke();}
            doc.save(out);
            ParsedDocument parsed=new PageAwareDocumentParser(new TikaDocumentParser(),null).parse("d","v",out.toByteArray(),"application/pdf");
            assertEquals("",parsed.getPages().get(0).getText());assertEquals(2,parsed.getPages().get(1).getPage());
        }
    }
    private byte[] sample() throws Exception {
        try (PDDocument pdf = new PDDocument();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage first = new PDPage(), blank = new PDPage(), third = new PDPage();
            pdf.addPage(first);
            pdf.addPage(blank);
            pdf.addPage(third);
            BufferedImage image = new BufferedImage(1200, 220, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, 1200, 220);
            g.setColor(Color.BLACK);
            g.setFont(new Font("SansSerif", Font.PLAIN, 74));
            g.drawString("OCR LEARNING 12345", 30, 130);
            g.dispose();
            try (PDPageContentStream stream = new PDPageContentStream(pdf, first)) {
                stream.drawImage(LosslessFactory.createFromImage(pdf, image), 40, 400, 540, 100);
            }
            try (PDPageContentStream stream = new PDPageContentStream(pdf, third)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 18);
                stream.newLineAtOffset(50, 600);
                stream.showText("NATIVE THIRD PAGE");
                stream.endText();
            }
            pdf.save(out);
            return out.toByteArray();
        }
    }
}
