package io.github.sjcud.stockagent.rag.parser;

import java.awt.image.BufferedImage;

/** OCR策略边界，解析器不依赖本地命令或云供应商协议。 */
public interface OcrEngine {
    /** 能力标识不是识别准确率。 */
    default boolean available() {
        return true;
    }

    /** 在本页剩余时限内返回识别文本，空白图可返回空串。 */
    String recognize(BufferedImage image, long timeoutMillis);
}
