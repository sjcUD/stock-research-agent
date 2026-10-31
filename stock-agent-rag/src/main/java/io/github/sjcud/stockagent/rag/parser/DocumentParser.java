package io.github.sjcud.stockagent.rag.parser;

/**
 * 文档解析器边界。
 *
 * <p>解析器只负责把外部字节转换成带来源标识的纯文本，不负责切片、
 * 向量化或写入向量库。不同文件格式可以在这里增加独立适配器。</p>
 */
public interface DocumentParser {

    /**
     * 解析一份文档。
     *
     * @param documentId 文档稳定标识
     * @param revision 文档版本标识
     * @param content 原始字节
     * @param contentType MIME 类型
     * @return 解析后的文本
     */
    ParsedDocument parse(String documentId, String revision, byte[] content, String contentType);
}
