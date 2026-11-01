package io.github.sjcud.stockagent.rag.document;
import static org.junit.jupiter.api.Assertions.*;
import io.github.sjcud.stockagent.rag.application.*;
import io.github.sjcud.stockagent.rag.parser.*;
import io.github.sjcud.stockagent.rag.chunking.*;
import io.github.sjcud.stockagent.rag.retrieval.*;
import io.github.sjcud.stockagent.rag.store.*;
import java.util.*;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
/** 模拟后批次失败，未完成版本不得进入回答；完成版本重传不再写入。 */
public class IncompleteIngestionTest {
 @TempDir Path path;
 @Test public void partialWritesMustBeInvisibleAndIndexedRetryMustBeIdempotent(){
  DocumentRepository repo=new FileDocumentRepository(path,100000,20);
  List<RetrievalHit> saved=new ArrayList<RetrievalHit>();java.util.concurrent.atomic.AtomicInteger writes=new java.util.concurrent.atomic.AtomicInteger();
  VectorStore backend=new VectorStore(){
   public void upsert(List<DocumentChunk> chunks){writes.incrementAndGet();DocumentChunk c=chunks.get(0);saved.add(new RetrievalHit(c.getChunkId(),c.getText(),1,new Citation(c.getDocumentId(),c.getDocumentRevision(),c.getPage(),c.getOffset())));if(c.getDocumentId().equals("bad"))throw new IllegalStateException("later batch failed");}
   public List<RetrievalHit> search(RetrievalQuery q){return saved;}
  };
  VectorStore guarded=new IndexedDocumentVectorStore(backend,repo);
  RagIngestionService ingest=new RagIngestionService(new TikaDocumentParser(),new FixedWindowChunkingStrategy(100),guarded,repo);
  assertThrows(IllegalStateException.class,()->ingest.ingestText("bad","v1","partial"));
  assertTrue(guarded.search(new RetrievalQuery("partial",5)).isEmpty());
  ingest.ingestText("good","v1","complete");ingest.ingestText("good","v1","complete");
  assertEquals(2,writes.get());assertEquals(1,guarded.search(new RetrievalQuery("complete",5)).size());
 }
}
