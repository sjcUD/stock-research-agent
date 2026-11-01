package io.github.sjcud.stockagent.rag.document;

import com.fasterxml.jackson.databind.*;

import io.github.sjcud.stockagent.rag.parser.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Stream;

/** 学习项目本地原文仓储：哈希路径、不可变版本、有界容量和原子文件替换。 */
public final class FileDocumentRepository implements DocumentRepository {
    /** 只在配置的目录下读写，不把客户端documentId直接当路径。 */
    private final Path root;

    private final long maxBytes;
    private final int maxVersions;
    private final ObjectMapper mapper = new ObjectMapper();

    public FileDocumentRepository(Path root, long maxBytes, int maxVersions) {
        if (root == null || maxBytes < 1 || maxVersions < 1)
            throw new IllegalArgumentException("原文仓储配置无效");
        this.root = root.toAbsolutePath().normalize();
        this.maxBytes = maxBytes;
        this.maxVersions = maxVersions;
        try {
            Files.createDirectories(this.root);
        } catch (IOException ex) {
            throw new IllegalStateException("原文仓储目录不可写");
        }
    }

    /** 同版本原始字节和解析页均须一致，防止引用仍指旧版本而正文已改变。 */
    @Override
    public synchronized void stage(ParsedDocument doc, byte[] original, String type) {
        if (original == null
                || original.length == 0
                || original.length > 10 * 1024 * 1024
                || type == null
                || type.length() > 256) throw new IllegalArgumentException("原文参数无效");
        Path target = directory(doc.getDocumentId(), doc.getRevision()), temp = null;
        try {
            Map<String, Object> metadata = metadata(doc, original, type);
            if (Files.exists(target)) {
                JsonNode existing = readMetadata(target);
                if (doc.isPhysicalPages() != existing.path("physicalPages").asBoolean()
                        || !metadata.get("contentType")
                                .equals(existing.path("contentType").asText())
                        || !hash(original).equals(existing.path("sha256").asText())
                        || !mapper.valueToTree(metadata.get("pages")).equals(existing.get("pages")))
                    throw new IllegalArgumentException("同一文档版本内容不同，请使用新版本");
                return;
            }
            byte[] json = mapper.writeValueAsBytes(metadata);
            long used = 0;
            int count = 0;
            try (Stream<Path> paths = Files.list(root)) {
                for (Iterator<Path> it = paths.iterator(); it.hasNext(); ) {
                    Path p = it.next();
                    if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)
                            && !p.getFileName().toString().startsWith(".")) {
                        count++;
                        for (String f : Arrays.asList("source.bin", "metadata.json")) {
                            Path data = p.resolve(f);
                            if (Files.isRegularFile(data, LinkOption.NOFOLLOW_LINKS))
                                used += Files.size(data);
                        }
                    }
                }
            }
            if (count >= maxVersions || used + original.length + json.length > maxBytes)
                throw new IllegalArgumentException("原文仓储容量已满，请归档旧资料后再上传");
            temp = Files.createTempDirectory(root, ".stage-");
            Files.write(temp.resolve("source.bin"), original);
            Files.write(temp.resolve("metadata.json"), json);
            move(temp, target, false);
            temp = null;
        } catch (IOException ex) {
            throw new IllegalStateException("原文保存失败");
        } finally {
            if (temp != null) {
                for (String f : Arrays.asList("source.bin", "metadata.json", ""))
                    try {
                        Files.deleteIfExists(f.isEmpty() ? temp : temp.resolve(f));
                    } catch (IOException ignored) {
                    }
            }
        }
    }

    /** 仅在向量索引成功后确认状态。 */
    @Override
    public synchronized void indexed(String id, String revision, int chunks) {
        if (chunks < 1) throw new IllegalArgumentException("索引片段为空");
        update(id, revision, "INDEXED", chunks);
    }

    @Override
    public synchronized void failed(String id, String revision) {
        StoredDocument old = find(id, revision);
        if (!"INDEXED".equals(old.getStatus())) update(id, revision, "FAILED", 0);
    }

    /** 页文本保留在metadata快照，API只选择所需页返回。 */
    @Override
    public synchronized StoredDocument find(String id, String revision) {
        try {
            JsonNode m = readMetadata(directory(id, revision));
            if (!id.equals(m.path("id").asText()) || !revision.equals(m.path("revision").asText()))
                throw new IllegalStateException("原文身份不一致");
            List<ParsedPage> pages = new ArrayList<ParsedPage>();
            for (JsonNode page : m.path("pages"))
                pages.add(
                        new ParsedPage(
                                page.path("page").asInt(),
                                page.path("text").asText(),
                                page.path("ocr").asBoolean()));
            return new StoredDocument(
                    new ParsedDocument(id, revision, pages, m.path("physicalPages").asBoolean()),
                    m.path("contentType").asText(),
                    m.path("status").asText(),
                    m.path("sha256").asText(),
                    m.path("size").asLong(),
                    m.path("chunkCount").asInt());
        } catch (IOException ex) {
            throw new IllegalStateException("原文归档无法读取");
        }
    }

    @Override
    public synchronized byte[] original(String id, String revision) {
        StoredDocument record = find(id, revision);
        Path file = directory(id, revision).resolve("source.bin");
        try {
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                throw new NoSuchElementException("原文不存在");
            byte[] bytes = Files.readAllBytes(file);
            if (!hash(bytes).equals(record.getSha256())) throw new IllegalStateException("原文校验失败");
            return bytes;
        } catch (IOException ex) {
            throw new IllegalStateException("原文无法读取");
        }
    }

    /** 更新小型状态快照不改写原始文件。 */
    private void update(String id, String revision, String status, int chunks) {
        Path dir = directory(id, revision), temp = null;
        try {
            JsonNode node = readMetadata(dir);
            ((com.fasterxml.jackson.databind.node.ObjectNode) node)
                    .put("status", status)
                    .put("chunkCount", chunks);
            temp = Files.createTempFile(dir, "state-", ".tmp");
            mapper.writeValue(temp.toFile(), node);
            move(temp, dir.resolve("metadata.json"), true);
            temp = null;
        } catch (IOException ex) {
            throw new IllegalStateException("原文状态无法保存");
        } finally {
            if (temp != null)
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                }
        }
    }

    private JsonNode readMetadata(Path directory) throws IOException {
        Path file = directory.resolve("metadata.json");
        if (Files.isSymbolicLink(directory)
                || Files.isSymbolicLink(file)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            throw new NoSuchElementException("此版本未保存原文");
        return mapper.readTree(file.toFile());
    }

    private Path directory(String id, String revision) {
        if (id == null
                || revision == null
                || !id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
                || !revision.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))
            throw new IllegalArgumentException("文档键无效");
        return root.resolve(hash((id + "\u0000" + revision).getBytes(StandardCharsets.UTF_8)));
    }

    private Map<String, Object> metadata(ParsedDocument doc, byte[] original, String type) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("id", doc.getDocumentId());
        m.put("revision", doc.getRevision());
        m.put("contentType", doc.isPhysicalPages() ? "application/pdf" : type);
        m.put("physicalPages", doc.isPhysicalPages());
        m.put("sha256", hash(original));
        m.put("size", original.length);
        m.put("status", "PENDING");
        m.put("chunkCount", 0);
        m.put("pages", doc.getPages());
        return m;
    }

    private String hash(byte[] bytes) {
        try {
            StringBuilder value = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes))
                value.append(String.format(Locale.ROOT, "%02x", b & 255));
            return value.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("文档指纹失败");
        }
    }

    private void move(Path from, Path to, boolean replace) throws IOException {
        try {
            if (replace)
                Files.move(
                        from,
                        to,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            else Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            if (replace) Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
            else Files.move(from, to);
        }
    }
}
