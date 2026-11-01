package io.github.sjcud.stockagent.rag.parser;

import java.awt.geom.Point2D;
import java.io.IOException;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.cos.COSName;

/** 只遍历绘图操作确认栅格图片；不解码图片，向量装饰和空白内容不触发OCR。 */
final class PdfRasterDetector extends PDFGraphicsStreamEngine {
    /** 页面含图片即补充OCR，避免页眉或不完整文字层掩盖扫描正文。 */
    private boolean raster;
    /** 仅供解析策略按页创建，状态不跨页共享。 */
    private PdfRasterDetector(PDPage page) { super(page); }
    /** PDFBox同时遍历嵌套Form及内联图片，避免只查资源字典遗漏实际绘制内容。 */
    static boolean containsRaster(PDPage page) throws IOException {
        PdfRasterDetector detector = new PdfRasterDetector(page); detector.processPage(page); return detector.raster;
    }
    @Override public void drawImage(PDImage image) { raster = true; }
    // 以下向量路径仅为解释器契约，不参与文字或图片判断，无需实际绘制。
    @Override public void appendRectangle(Point2D a, Point2D b, Point2D c, Point2D d) { }
    @Override public void clip(int rule) { }
    @Override public void moveTo(float x, float y) { }
    @Override public void lineTo(float x, float y) { }
    @Override public void curveTo(float a,float b,float c,float d,float e,float f) { }
    @Override public Point2D getCurrentPoint() { return new Point2D.Float(); }
    @Override public void closePath() { }
    @Override public void endPath() { }
    @Override public void strokePath() { }
    @Override public void fillPath(int rule) { }
    @Override public void fillAndStrokePath(int rule) { }
    @Override public void shadingFill(COSName name) { }
}
