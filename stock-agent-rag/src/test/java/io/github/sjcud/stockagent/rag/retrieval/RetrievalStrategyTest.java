package io.github.sjcud.stockagent.rag.retrieval;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** 验证运行时拒答策略的边界。 */
public class RetrievalStrategyTest {
    @Test public void abstentionIsExplicitAndDisabledByDefault() {
        assertTrue(new RetrievalAbstentionPolicy(true, 1).shouldAbstain(Collections.<RetrievalHit>emptyList()));
        assertFalse(new RetrievalAbstentionPolicy(false, 1).shouldAbstain(Collections.<RetrievalHit>emptyList()));
    }
}
