package io.github.sjcud.stockagent.rag.parser;

/** 一页提取文本，page是原文件中的1起始页号；空白页仍保留编号。 */
public final class ParsedPage {
    private final int page;
    private final String text;
    private final boolean ocr;

    /** OCR标记只说明识别方式，不保证识别结果正确。 */
    public ParsedPage(int page, String text, boolean ocr) {
        if (page < 1 || text == null) throw new IllegalArgumentException("页信息无效");
        this.page = page;
        this.text = text;
        this.ocr = ocr;
    }

    /** 返回原文件页号。 */
    public int getPage() {
        return page;
    }

    /** 返回页内原始提取文本，切片偏移以此字符串为准。 */
    public String getText() {
        return text;
    }

    /** 是否经OCR识别。 */
    public boolean isOcr() {
        return ocr;
    }
}
