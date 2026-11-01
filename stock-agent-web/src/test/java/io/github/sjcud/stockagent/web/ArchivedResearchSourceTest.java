package io.github.sjcud.stockagent.web;
import static org.junit.jupiter.api.Assertions.*;
import io.github.sjcud.stockagent.runtime.workspace.WorkspaceRepository;
import org.junit.jupiter.api.Test;

/** 未完成、失败和取消不能作为可信完整报告来源。 */
public class ArchivedResearchSourceTest {
 @Test public void rejectsAllNonSuccessfulSourceStates() {
  for(String status:new String[]{"RUNNING","FAILED","CANCELLED","QUEUED","INTERRUPTED"})
   assertThrows(IllegalArgumentException.class,()->new ArchivedResearchSource(new WorkspaceRepository.Entry("id","c","q","{}","{}",status,1,1)));
 }
}
