package io.github.sjcud.stockagent.rag.parser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;

/**
 * 基于 Apache Tika 的多格式文档解析器。
 *
 * <p>统一支持 PDF、Word、Excel、HTML、纯文本和 Markdown。Tika 只负责把
 * 文件内容转换成文本，页码、表格结构和图片 OCR 等增强信息留给后续专用
 * parser 扩展，避免污染 RAG 入库编排。</p>
 */
public class TikaDocumentParser implements DocumentParser {

    private static final Set<String> SUPPORTED_TYPES = new HashSet<String>(Arrays.asList(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "text/html",
            "text/plain",
            "text/markdown"));

    /** 文本显式上限，额外读取一个字符用于识别截断，不静默丢失尾部。 */
    private static final int MAX_TEXT_CHARACTERS = 1000000;
    /** 已固定文本提取边界的 Tika 实例。 */
    private final Tika tika;

    /** 创建使用 Tika 默认解析器注册表的解析器。 */
    public TikaDocumentParser() {
        this.tika = new Tika();
        this.tika.setMaxStringLength(MAX_TEXT_CHARACTERS + 1);
    }

    /** 将支持的文件内容解析成统一文本模型。 */
    @Override
    public ParsedDocument parse(String documentId, String revision, byte[] content, String contentType) {
        String normalizedType = normalizeContentType(contentType);
        if (!SUPPORTED_TYPES.contains(normalizedType)) {
            throw new IllegalArgumentException("暂不支持的文档类型: " + contentType);
        }
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("文档内容不能为空");
        }

        Metadata metadata = new Metadata();
        metadata.set("Content-Type", normalizedType);
        try {
            String extracted = tika.parseToString(new ByteArrayInputStream(content), metadata);
            if (extracted.length() > MAX_TEXT_CHARACTERS) {
                throw new IllegalArgumentException("文档提取文本超过100万字符，请拆分后上传");
            }
            String text = extracted.trim();
            return new ParsedDocument(documentId, revision, text);
        } catch (IOException exception) {
            throw new IllegalArgumentException("文档读取失败", exception);
        } catch (TikaException exception) {
            throw new IllegalArgumentException("文档解析失败", exception);
        }
    }

    private String normalizeContentType(String contentType) {
        if (contentType == null) {
            return "";
        }
        String value = contentType.trim().toLowerCase(Locale.ROOT);
        int parameterStart = value.indexOf(';');
        return parameterStart < 0 ? value : value.substring(0, parameterStart).trim();
    }
}
