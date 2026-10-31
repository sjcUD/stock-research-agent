package io.github.sjcud.stockagent.web;

import io.github.sjcud.stockagent.rag.application.IngestionResult;
import io.github.sjcud.stockagent.rag.application.RagIngestionService;
import io.github.sjcud.stockagent.rag.retrieval.Citation;
import io.github.sjcud.stockagent.rag.retrieval.RetrievalHit;
import io.github.sjcud.stockagent.rag.retrieval.RetrievalQuery;
import io.github.sjcud.stockagent.rag.store.VectorStore;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** RAG 文档入库和检索 API。 */
@RestController
@RequestMapping("/api/rag")
@ConditionalOnBean(RagIngestionService.class)
public class RagController {

    private final RagIngestionService ingestionService;
    private final VectorStore vectorStore;

    public RagController(RagIngestionService ingestionService, VectorStore vectorStore) {
        this.ingestionService = ingestionService;
        this.vectorStore = vectorStore;
    }

    /** 接收纯文本或 Markdown 内容并写入 RAG 索引。 */
    @PostMapping("/documents")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public IngestionResponse ingest(@RequestBody IngestionRequest request) {
        byte[] content = request.getContent().getBytes(StandardCharsets.UTF_8);
        IngestionResult result = ingestionService.ingest(
                request.getDocumentId(), request.getRevision(), content, request.getContentType());
        return new IngestionResponse(result.getDocumentId(), result.getRevision(), result.getChunkCount());
    }

    /** 检索最相关片段，并返回来源定位信息。 */
    @GetMapping("/search")
    public SearchResponse search(@RequestParam("q") String query,
                                 @RequestParam(value = "topK", defaultValue = "5") int topK) {
        List<RetrievalHit> hits = vectorStore.search(new RetrievalQuery(query, topK));
        List<SearchHitResponse> results = new ArrayList<SearchHitResponse>();
        for (RetrievalHit hit : hits) {
            results.add(new SearchHitResponse(hit.getChunkId(), hit.getText(), hit.getScore(), hit.getCitation()));
        }
        return new SearchResponse(results);
    }

    /** 入库请求体。 */
    public static class IngestionRequest {
        private String documentId;
        private String revision;
        private String contentType = "text/plain";
        private String content;

        public String getDocumentId() { return documentId; }
        public void setDocumentId(String documentId) { this.documentId = documentId; }
        public String getRevision() { return revision; }
        public void setRevision(String revision) { this.revision = revision; }
        public String getContentType() { return contentType; }
        public void setContentType(String contentType) { this.contentType = contentType; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
    }

    /** 入库响应体。 */
    public static class IngestionResponse {
        private final String documentId;
        private final String revision;
        private final int chunkCount;

        public IngestionResponse(String documentId, String revision, int chunkCount) {
            this.documentId = documentId;
            this.revision = revision;
            this.chunkCount = chunkCount;
        }

        public String getDocumentId() { return documentId; }
        public String getRevision() { return revision; }
        public int getChunkCount() { return chunkCount; }
    }

    /** 检索响应体。 */
    public static class SearchResponse {
        private final List<SearchHitResponse> hits;

        public SearchResponse(List<SearchHitResponse> hits) { this.hits = hits; }
        public List<SearchHitResponse> getHits() { return hits; }
    }

    /** 带 Citation 的检索命中响应。 */
    public static class SearchHitResponse {
        private final String chunkId;
        private final String text;
        private final double score;
        private final Citation citation;

        public SearchHitResponse(String chunkId, String text, double score, Citation citation) {
            this.chunkId = chunkId;
            this.text = text;
            this.score = score;
            this.citation = citation;
        }

        public String getChunkId() { return chunkId; }
        public String getText() { return text; }
        public double getScore() { return score; }
        public Citation getCitation() { return citation; }
    }
}
