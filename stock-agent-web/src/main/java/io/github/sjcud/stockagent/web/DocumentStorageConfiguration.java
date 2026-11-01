package io.github.sjcud.stockagent.web;

import io.github.sjcud.stockagent.rag.document.*;
import io.github.sjcud.stockagent.rag.parser.*;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

import java.nio.file.Paths;

/** 原文读取不依赖向量库在线；解析器和OCR策略保持在单一RAG模块。 */
@Configuration
public class DocumentStorageConfiguration {
    @Bean
    public DocumentRepository documentRepository(
            @Value("${stock.agent.rag.documents.directory:data/documents}") String directory,
            @Value("${stock.agent.rag.documents.max-bytes:268435456}") long maxBytes,
            @Value("${stock.agent.rag.documents.max-versions:200}") int versions) {
        return new FileDocumentRepository(Paths.get(directory), maxBytes, versions);
    }

    @Bean
    @ConditionalOnProperty(
            name = "stock.agent.rag.ocr.enabled",
            havingValue = "true",
            matchIfMissing = true)
    public OcrEngine ocrEngine(
            @Value("${stock.agent.rag.ocr.command:tesseract}") String command,
            @Value("${stock.agent.rag.ocr.languages:chi_sim+eng}") String languages,
            @Value("${stock.agent.rag.ocr.data-path:}") String dataPath) {
        return new TesseractOcrEngine(command, languages, dataPath);
    }

    @Bean
    public DocumentParser documentParser(ObjectProvider<OcrEngine> ocr) {
        return new PageAwareDocumentParser(new TikaDocumentParser(), ocr.getIfAvailable());
    }
}
