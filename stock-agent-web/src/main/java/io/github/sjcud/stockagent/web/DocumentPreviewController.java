package io.github.sjcud.stockagent.web;

import io.github.sjcud.stockagent.rag.document.*;
import io.github.sjcud.stockagent.rag.parser.ParsedPage;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/** 原文/页文本按文档版本访问，不暴露服务器路径，不执行上传HTML。 */
@RestController
@RequestMapping("/api/rag/documents/{id}/revisions/{revision}")
public final class DocumentPreviewController {
    private final DocumentRepository repository;

    public DocumentPreviewController(DocumentRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public Map<String, Object> metadata(
            @PathVariable("id") String id, @PathVariable("revision") String revision) {
        StoredDocument record = load(id, revision);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("documentId", id);
        result.put("revision", revision);
        result.put("status", record.getStatus());
        result.put("pageCount", record.getDocument().getPages().size());
        result.put("physicalPages", record.getDocument().isPhysicalPages());
        result.put("contentType", record.getContentType());
        result.put("chunkCount", record.getChunkCount());
        result.put("size", record.getSize());
        int ocr = 0;
        for (ParsedPage page : record.getDocument().getPages()) if (page.isOcr()) ocr++;
        result.put("ocrPages", ocr);
        return result;
    }

    @GetMapping("/pages/{page}")
    public ParsedPage page(
            @PathVariable("id") String id,
            @PathVariable("revision") String revision,
            @PathVariable("page") int page) {
        StoredDocument record = load(id, revision);
        if (page < 1 || page > record.getDocument().getPages().size())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "原文页不存在");
        return record.getDocument().getPages().get(page - 1);
    }

    /** PDF可内嵌；其他原件以附件返回，尤其HTML不能在应用同源上下文执行。 */
    @GetMapping("/file")
    public ResponseEntity<byte[]> file(
            @PathVariable("id") String id, @PathVariable("revision") String revision) {
        StoredDocument record = load(id, revision);
        byte[] bytes = repository.original(id, revision);
        boolean pdf = record.getDocument().isPhysicalPages();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(
                pdf ? MediaType.APPLICATION_PDF : MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentDisposition(
                (pdf ? ContentDisposition.inline() : ContentDisposition.attachment())
                        .filename(pdf ? "document.pdf" : "document-original")
                        .build());
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Content-Security-Policy", "sandbox; default-src 'none'");
        headers.setCacheControl("no-store");
        return new ResponseEntity<byte[]>(bytes, headers, HttpStatus.OK);
    }

    private StoredDocument load(String id, String revision) {
        try {
            return repository.find(id, revision);
        } catch (NoSuchElementException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "此版本未保存原文，历史片段仍可查看引用文本");
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文档版本参数无效");
        }
    }
}
