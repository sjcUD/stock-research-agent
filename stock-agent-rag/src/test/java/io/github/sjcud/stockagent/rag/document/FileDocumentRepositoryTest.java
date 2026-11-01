package io.github.sjcud.stockagent.rag.document;

import static org.junit.jupiter.api.Assertions.*;

import io.github.sjcud.stockagent.rag.parser.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

/** 同版本原文、解析页和索引状态必须一致，读路径不能由客户端任意指定。 */
public class FileDocumentRepositoryTest {
    @TempDir Path directory;

    @Test
    public void storesImmutableOriginalAndDoesNotDowngradeIndexedVersion() {
        FileDocumentRepository repository = new FileDocumentRepository(directory, 1000000, 3);
        ParsedDocument doc =
                new ParsedDocument(
                        "d",
                        "v",
                        Arrays.asList(
                                new ParsedPage(1, "page one", false),
                                new ParsedPage(2, "page two", true)),
                        true);
        byte[] original = "original bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        repository.stage(doc, original, "application/pdf");
        assertEquals("PENDING", repository.find("d", "v").getStatus());
        repository.indexed("d", "v", 2);
        repository.failed("d", "v");
        assertEquals(
                "INDEXED",
                new FileDocumentRepository(directory, 1000000, 3).find("d", "v").getStatus());
        assertArrayEquals(original, repository.original("d", "v"));
        assertEquals(2, repository.find("d", "v").getDocument().getPages().size());
        assertThrows(
                IllegalArgumentException.class,
                () -> repository.stage(doc, new byte[] {9}, "application/pdf"));
        assertThrows(IllegalArgumentException.class, () -> repository.find("../secret", "v"));
        assertThrows(NoSuchElementException.class, () -> repository.find("missing", "v"));
    }

    @Test
    public void refusesCapacityBeforeAnyIndexCanBeSubmitted() {
        FileDocumentRepository repository = new FileDocumentRepository(directory, 2, 1);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        repository.stage(
                                new ParsedDocument("d", "v", "text"),
                                new byte[] {1},
                                "text/plain"));
    }
}
