package io.github.sjcud.stockagent.rag.chunking;

import java.util.ArrayList;
import java.util.List;

/**
 * 固定窗口切分策略。
 *
 * <p>这是 L5 的最小基线，用字符窗口保持确定性；后续可在同一接口下
 * 增加按标题、表格和 PDF 版面切分的策略。</p>
 */
public class FixedWindowChunkingStrategy {
    private final int windowSize;

    public FixedWindowChunkingStrategy(int windowSize) {
        if (windowSize <= 0) {
            throw new IllegalArgumentException("windowSize 必须大于0");
        }
        this.windowSize = windowSize;
    }

    /** 按窗口切分并保留文档版本、页码和原文偏移。 */
    public List<DocumentChunk> chunk(String documentId, String revision, int page, String text) {
        if (text == null || text.isEmpty()) {
            return new ArrayList<DocumentChunk>();
        }
        List<DocumentChunk> result = new ArrayList<DocumentChunk>();
        int offset = 0;
        while (offset < text.length()) {
            int end = Math.min(offset + windowSize, text.length());
            result.add(new DocumentChunk(documentId, revision, page, offset, text.substring(offset, end)));
            offset = end;
        }
        return result;
    }
}
