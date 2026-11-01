package io.github.sjcud.stockagent.rag.document;

import io.github.sjcud.stockagent.rag.parser.ParsedDocument;

/** 原文与提取页的归档边界，独立于向量数据库，方便替换对象存储。 */
public interface DocumentRepository {
    /** 索引前保存原文和页文本，同一id/revision不可改写为另一份内容。 */
    void stage(ParsedDocument document, byte[] original, String contentType);

    /** 向量库成功后才标为INDEXED。 */
    void indexed(String id, String revision, int chunkCount);

    /** 失败保留原文供核查；已成功版本不被同内容重试降级。 */
    void failed(String id, String revision);

    /** 未找到时抛NoSuchElementException。 */
    StoredDocument find(String id, String revision);

    /** 读取由服务端键定位的原始字节，不接受文件系统路径。 */
    byte[] original(String id, String revision);
}
