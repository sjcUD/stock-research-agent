package io.github.sjcud.stockagent.web;

import static org.junit.jupiter.api.Assertions.*;
import io.github.sjcud.stockagent.data.SampleStockProvider;
import io.github.sjcud.stockagent.research.MultiResearchService;
import io.github.sjcud.stockagent.rag.application.RagIngestionService;
import io.github.sjcud.stockagent.rag.chunking.*;
import io.github.sjcud.stockagent.rag.parser.TikaDocumentParser;
import io.github.sjcud.stockagent.rag.retrieval.*;
import io.github.sjcud.stockagent.rag.store.VectorStore;
import java.util.*;
import java.io.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** 上传成功需真的完成切片写入，文件类型与空内容在边界拒绝。 */
public class WorkspaceControllerTest {
    @Test
    public void shouldIndexTextBeforeReturningIndexedState() throws Exception {
        List<DocumentChunk> indexed=new ArrayList<DocumentChunk>();
        VectorStore store=new VectorStore() {
            public void upsert(List<DocumentChunk> chunks) { indexed.addAll(chunks); }
            public List<RetrievalHit> search(RetrievalQuery query) { return Collections.emptyList(); }
        };
        StaticListableBeanFactory beans=new StaticListableBeanFactory();
        beans.addBean("documents",new RagIngestionService(new TikaDocumentParser(),new FixedWindowChunkingStrategy(200),store));
        WorkspaceController controller=new WorkspaceController(new SampleStockProvider(),beans.getBeanProvider(MultiResearchService.class),beans.getBeanProvider(RagIngestionService.class));
        Map<String,Object> response=controller.upload(new FileFixture("example.md","# 学习资料\n预算由父任务统一控制。"));
        assertEquals("INDEXED",response.get("status")); assertFalse(indexed.isEmpty());
        assertTrue(response.get("documentId").toString().startsWith("upload-"));
        assertThrows(ResponseStatusException.class,()->controller.upload(new FileFixture("unsafe.exe","x")));
        assertThrows(ResponseStatusException.class,()->controller.upload(new FileFixture("empty.txt","")));
        assertEquals("SAMPLE",controller.status().get("dataMode"));
    }
    @Test
    public void shouldIndexTailOfLongDocumentInsteadOfReturningFalseSuccess() throws Exception {
        List<DocumentChunk> indexed=new ArrayList<DocumentChunk>();
        VectorStore store=new VectorStore() {
            public void upsert(List<DocumentChunk> chunks) { indexed.addAll(chunks); }
            public List<RetrievalHit> search(RetrievalQuery query) { return Collections.emptyList(); }
        };
        StaticListableBeanFactory beans=new StaticListableBeanFactory();
        beans.addBean("documents",new RagIngestionService(new TikaDocumentParser(),new FixedWindowChunkingStrategy(800),store));
        WorkspaceController controller=new WorkspaceController(new SampleStockProvider(),beans.getBeanProvider(MultiResearchService.class),beans.getBeanProvider(RagIngestionService.class));
        StringBuilder text=new StringBuilder();for(int i=0;i<120000;i++)text.append('a');text.append("TAIL_INDEXED_MARKER");
        assertEquals("INDEXED",controller.upload(new FileFixture("long.txt",text.toString())).get("status"));
        assertTrue(indexed.stream().anyMatch(chunk->chunk.getText().contains("TAIL_INDEXED_MARKER")));
    }
    /** 仅在测试中模拟浏览器文件，不访问磁盘或外部模型。 */
    private static class FileFixture implements MultipartFile {
        private final String name;
        private final byte[] content;
        FileFixture(String name,String content) { this.name=name;this.content=content.getBytes(java.nio.charset.StandardCharsets.UTF_8); }
        public String getName() { return "file"; }
        public String getOriginalFilename() { return name; }
        public String getContentType() { return "application/octet-stream"; }
        public boolean isEmpty() { return content.length==0; }
        public long getSize() { return content.length; }
        public byte[] getBytes() { return content.clone(); }
        public InputStream getInputStream() { return new ByteArrayInputStream(content); }
        public void transferTo(File dest) { throw new UnsupportedOperationException(); }
    }
}
