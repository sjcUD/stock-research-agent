package io.github.sjcud.stockagent.web;

import io.github.sjcud.stockagent.data.*;
import io.github.sjcud.stockagent.rag.application.*;
import io.github.sjcud.stockagent.research.MultiResearchService;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** 工作台能力与上传边界；不将模型密钥、供应商地址或本地路径返回页面。 */
@RestController
public class WorkspaceController {
    /** 当前数据策略。 */
    private final StockDataProvider provider;
    /** 是否已装配研究模型，不代表供应商网络健康探针。 */
    private final ObjectProvider<MultiResearchService> research;
    /** 按需文档入库服务。 */
    private final ObjectProvider<RagIngestionService> documents;
    /** 单个文件限制，与 Servlet multipart 配置一致。 */
    private static final long MAX_FILE=10L*1024*1024;
    /** 注入现有能力，不在页面刷新时调用真实模型。 */
    public WorkspaceController(StockDataProvider provider,ObjectProvider<MultiResearchService> research,
                               ObjectProvider<RagIngestionService> documents) {
        this.provider=provider; this.research=research; this.documents=documents;
    }
    /** 返回页面所需配置与真实默认日期，SAMPLE固定范围清晰展示。 */
    @GetMapping("/api/workspace")
    public Map<String,Object> status() {
        boolean sample=provider instanceof SampleStockProvider;
        ZonedDateTime now=ZonedDateTime.now(ZoneId.of("Asia/Shanghai"));
        LocalDate allowed=now.getHour()<16 ? now.toLocalDate().minusDays(1) : now.toLocalDate();
        Map<String,Object> value=new LinkedHashMap<String,Object>();
        value.put("dataMode",sample ? "SAMPLE" : "LIVE");
        value.put("defaultAsOf",sample ? SampleStockProvider.LAST_SAMPLE_DATE : allowed.toString());
        value.put("maxAsOf",allowed.toString()); value.put("defaultSymbol","600519");
        value.put("researchConfigured",research.getIfAvailable()!=null);
        value.put("ragConfigured",documents.getIfAvailable()!=null);
        value.put("maxUploadBytes",MAX_FILE);
        value.put("formats",Arrays.asList("pdf","doc","docx","xls","xlsx","html","txt","md"));
        return value;
    }
    /** 接收文件并在真正入库成功后返回INDEXED，不把传输完成冒充索引完成。 */
    @PostMapping(value="/api/rag/documents/upload",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String,Object> upload(@RequestParam("file") MultipartFile file) throws Exception {
        RagIngestionService service=documents.getIfAvailable();
        if (service==null) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"资料索引服务尚未配置"); }
        if (file==null || file.isEmpty() || file.getSize()>MAX_FILE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请选择不超过10MiB的非空文档");
        }
        String name=file.getOriginalFilename()==null ? "document" : file.getOriginalFilename();
        String type=contentType(name);
        byte[] content=file.getBytes();
        StringBuilder fingerprint=new StringBuilder();
        for (byte b:MessageDigest.getInstance("SHA-256").digest(content)) { fingerprint.append(String.format(Locale.ROOT,"%02x",b&255)); }
        try {
            String revision=fingerprint.toString();
            IngestionResult result=service.ingest("upload-"+revision,revision,content,type);
            if (result.getChunkCount()==0) { throw new IllegalArgumentException("未提取到文本"); }
            Map<String,Object> response=new LinkedHashMap<String,Object>();
            response.put("documentId",result.getDocumentId()); response.put("revision",result.getRevision());
            response.put("chunkCount",result.getChunkCount()); response.put("filename",name); response.put("status","INDEXED");
            return response;
        } catch (IllegalArgumentException exception) {
            String message=exception.getMessage()!=null && exception.getMessage().startsWith("文档提取文本超过")
                    ? "文档提取文本超过100万字符，请拆分后上传" : "未能解析有效文本；扫描图片和OCR尚不支持";
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,message,exception);
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"文档未完成索引，请检查向量或模型服务后重试",exception);
        }
    }
    /** 根据受支持的扩展名选择现有解析器类型；不接受任意脚本或图片格式。 */
    private String contentType(String filename) {
        String name=filename.toLowerCase(Locale.ROOT);
        if (name.endsWith(".pdf")) { return "application/pdf"; }
        if (name.endsWith(".docx")) { return "application/vnd.openxmlformats-officedocument.wordprocessingml.document"; }
        if (name.endsWith(".doc")) { return "application/msword"; }
        if (name.endsWith(".xlsx")) { return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"; }
        if (name.endsWith(".xls")) { return "application/vnd.ms-excel"; }
        if (name.endsWith(".html") || name.endsWith(".htm")) { return "text/html"; }
        if (name.endsWith(".md") || name.endsWith(".markdown")) { return "text/markdown"; }
        if (name.endsWith(".txt")) { return "text/plain"; }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"仅支持PDF、Word、Excel、HTML、TXT和Markdown");
    }
}
