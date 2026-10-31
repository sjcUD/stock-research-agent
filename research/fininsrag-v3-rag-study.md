# FinInsRAG-V3 RAG 研究报告

研究日期：2026-10-31。路径：`/Users/sjc/Documents/FinInsRAG-V3/FinInsRAG-V3/swxy(1)/swxy`。本报告只读源码，未启动 Docker、未调用模型/Embedding/Elasticsearch，也未读取密钥。项目中部分 parser/rag 代码来自 RAGFlow 风格实现；保留其 Apache 2.0 版权头时，不应把整套代码直接复制到 Java 项目。

## 1. 总体调用链

系统同时有快速上传解析和知识库问答两条路径：

```text
前端上传文档
  → FastAPI upload_files / quick_parse
  → 本地 storage + 数据库文档记录
  → deepdoc/naive 解析与切片
  → 批量 Embedding
  → Elasticsearch 文档/向量索引
  → 用户问题
  → BM25/关键词 + 向量召回
  → 加权融合 + 本地 token/向量重排
  → 引用片段
  → LLM 流式回答 + 推荐追问
```

主要证据：`backend/app/router/chat_rt.py` 中 `/upload_files`、`/quick_parse`、`/chat_on_docs`；`backend/app/service/core/file_parse.py` 的 `parse → process_items → ESConnection.insert`；`backend/app/service/core/retrieval.py` 的 `Dealer.retrieval` 调用。

## 2. 文档来源与上传交互

### 2.1 来源

- 主要来源是用户上传文件。`/upload_files` 按用户和 session 创建 `storage/file/<session_id>`，检查数据库是否已有同名文件，再保存并进入解析/索引流程。
- `/quick_parse` 是会话级快速解析，限制 docx/pdf/txt 和不超过 4 页，结果暂存 Redis 约 2 小时；适合上传后快速预览，不等同完整知识库索引。
- 前端 `frontend/src/pages/repository/index.tsx` 有“知识库”页面：文件名、切片数量、更新时间、切片方式、状态、删除；上传使用 Modal。聊天页 `frontend/src/pages/chat/index.tsx` 通过 SSE 接收回答和引用；引用组件 `frontend/src/pages/chat/component/citations.tsx` 显示文档名、页码、片段和“阅读”抽屉。
- 项目自带 `【兴证电子】世运电路2023中报点评.pdf`、`backend/test_pdf.pdf` 等测试材料，但默认流程仍是上传后入库，而不是应用启动时自动构建固定语料库。

### 2.2 可借鉴的交互

1. 知识库页先显示文件状态，提示“等待文档解析完成”。
2. 聊天消息保存 `reference`、`documents`、`recommended_questions`，SSE 中间结果可以逐步追加回答、思考文本和引用。
3. 引用卡片展示文档名、序号、片段预览和页码；点击后打开大抽屉阅读 Markdown 片段。
4. 推荐问题基于当前问题和最多 3 个文档名，由 LLM 输出 JSON 数组；失败时返回空列表，聊天仍可完成。

改进边界：项目的 `document_upload` 记录和完整 ES 文档状态存在脱节，切片数量/状态在前端部分使用占位值；用户级索引以 ES index/knowledge-base 条件隔离，但 Java 版应把 document revision、index generation、embedding fingerprint 和状态作为明确业务对象。

## 3. 解析器与结构化切割

### 3.1 格式分发

`backend/app/service/core/rag/app/naive.py:194-308` 按扩展名选择：PDF、DOCX、XLS/XLSX、TXT/代码、Markdown、HTML、JSON、DOC；PDF 默认 DeepDOC layout parser，也支持 Plain Text 模式。解析器入口在 `deepdoc/parser/__init__.py`，包括 `pdf_parser.py`、`excel_parser.py`、`markdown_parser.py` 等。

PDF 解析会产出 sections、tables 和 page/position 信息；DOCX 读取段落和图片；Excel 可按 HTML 表格或普通文本；Markdown/HTML/JSON 也走专用解析器。表格不是简单丢弃：`tokenize_table` 将表格转成检索文本，并可保留图片。

### 3.2 切割算法

`backend/app/service/core/rag/nlp/__init__.py`：

- 默认 `chunk_token_num=128`，分隔符包含中文句号、分号、问号和换行。
- `naive_merge` 按 parser 输出的 section 累加 token 数，超过阈值后开始新 chunk；短 section 追加到当前 chunk。
- DOCX 有 `naive_merge_docx`，在 chunk 旁保存对应图片。
- `tokenize_chunks` / `tokenize_chunks_docx` 将 chunk 包装为文档记录，并保留 `content_with_weight`、粗粒度 token、细粒度 token。
- PDF/表格的 `page_num_int`、`position_int`、`top_int` 来源于解析器坐标，供排序和引用页码使用。

可借鉴经验：切片不是只按固定字符数，先保留文档结构、表格和页坐标，再做 token 窗口；中文粗/细粒度 token 同时服务关键词检索、highlight 和 rerank。需要修正：`naive_merge` 的窗口是简单累加，未证明严格 token 上限，也没有统一 overlap；Java 版应将 `ChunkingStrategy` 抽象为结构感知、固定窗口、Markdown 标题和表格等策略，记录 chunkerVersion 与页码/offset。

## 4. Embedding、Rerank 与模型配置

`backend/app/service/core/rag/nlp/model.py:66-130` 使用 OpenAI-compatible 客户端请求 DashScope `text-embedding-v3`，默认维度 1024、批量 10；单文本直接请求，列表按 10 分批。项目通过 `DASHSCOPE_BASE_URL`、`DASHSCOPE_API_KEY` 注入，不是硬绑定 Java SDK。

同文件的 `rerank_similarity` 使用 LlamaIndex DashScopeRerank，对候选文本再次排序。`get_chat_completion_block` 和 `service/core/chat.py` 使用独立的 `LLM_API_KEY`、`LLM_BASE_URL`、`LLM_MODEL`，说明 LLM、Embedding、Reranker 已有分离趋势，但模型管理仍是环境变量函数级调用，缺少统一 Provider registry。

重要边界：Embedding 失败的批次以 `None` 占位并继续返回，`file_parse.process_items` 后续 `len(embedding)` 可能失败或导致部分数据缺失；Java 版应改成明确 INGEST_FAILED/PARTIAL，不静默把失败向量写成空值。向量字段名动态生成 `q_<dimension>_vec`，迁移到多向量后端时应由 `EmbeddingProfile` 固定 model/version/dimension/metric。

## 5. 检索链

### 5.1 召回

`backend/app/service/core/rag/nlp/search_v2.py:45-176` 的 `Dealer.search`：

1. 对问题做中文/英文 token 分词，形成全文 `MatchTextExpr`。
2. 调 `generate_embedding` 生成问题向量，构造 `MatchDenseExpr`，默认 cosine、similarity 约 0.1。
3. 用 `FusionExpr("weighted_sum", weights="0.05,0.95")` 让 ES 结合关键词和向量，偏向向量分数。
4. 首次无结果时把 minimum match 从 0.3 放宽到 0.1，并调整向量门槛再查一次。
5. filter 允许 kb_id、doc_id、available_int 等字段，返回 chunk 字段、position、文档名、来源和向量。

### 5.2 重排和引用

`Dealer.retrieval` 继续对候选做 token similarity、vector similarity 和 DashScope rerank 的组合，再按 `vector_similarity_weight` 截取。`ESConnection.search` 使用 Elasticsearch KNN + query_string，并支持 highlight、aggregation、分页和排序。

`insert_citations` 把回答按句号/换行拆分，依据句子与候选 chunk 相似度插入 `[ID]` 引用；前端根据 reference 的 `document_id`、`document_name`、`content_with_weight`、`positions` 展示引用。这个“回答后插引用”很适合学习，但它是启发式句子匹配，不是严格 claim-evidence 验证；Java 版应让模型输出 claim/citation 结构，再由程序验证 citation 属于本次检索候选。

## 6. 存储与外部依赖

Docker Compose 包含：FastAPI、PostgreSQL 15、Elasticsearch 8.11.3、Redis 7。PostgreSQL 保存用户、会话、知识库、文档上传记录；Elasticsearch 保存 chunk、token、向量和检索字段；Redis 保存快速解析内容和临时会话数据。项目不是本地 SimpleVectorStore 方案。

不存在 Qdrant、pgvector 或本地向量实现；`DocStoreConnection` 与 `ESConnection` 是向后扩展的方向。检索接口与 `MatchTextExpr`/`MatchDenseExpr`/`FusionExpr` 已经形成可抽象的后端无关表达式，但实际 query translation 仍在 ESConnection 内。

## 7. 对 Java 多模块项目的借鉴

建议抽象为以下 Maven 模块：

```text
rag-contracts       Document/Chunk/Evidence/EmbeddingProfile/RetrievalQuery/Result
rag-parser          ParserRegistry、Pdf/Docx/Markdown/Text、Layout、ChunkingStrategy
rag-embedding       EmbeddingProvider、RerankProvider、LLM Provider metadata
rag-retrieval       HybridRetriever、KeywordRetriever、VectorRetriever、Reranker
rag-store-api       DocumentStore/VectorStore/IndexGeneration/Filter DSL
rag-store-simple    本地片段+SimpleVectorStore adapter
rag-store-qdrant    Qdrant adapter
rag-store-es        Elasticsearch adapter
rag-store-pgvector  PostgreSQL+pgvector adapter
rag-llm             独立 LLM 管理、ModelProfile、ChatModel、Embedding、Reranker
rag-application     导入、检索、引用、Chat/Graph tool 适配
research-agent      股票 Graph、角色 Agent 和上下文治理
```

不要让 parser 直接依赖 ES，也不要让 Agent 直接调用具体 provider。接口应该返回统一 `DocumentChunk`（文本、docRevision、page、offset、metadata、quality）和 `RetrievalHit`（chunkId、score、retriever、source）。

## 8. FinInsRAG-V3 最值得学习的 RAG 经验

1. **解析先于切片：** PDF layout、表格、图片、页坐标是引用质量的基础。
2. **同一 chunk 保存多种视图：** 原始带权文本、粗粒度 token、细粒度 token，分别服务 LLM、BM25、highlight 和 rerank。
3. **混合召回再重排：** 关键词和 dense 向量先融合，随后结合词项/向量/reranker；不是只看余弦相似度。
4. **来源元数据不是附加字段：** 文档 ID、知识库、页码、position、可用状态需要与 chunk 一起进入索引。
5. **模型职责拆分：** Chat LLM、Embedding、Reranker 配置不同，批量 Embedding 限制需要显式学习。
6. **交互反馈检索链：** 聊天回答实时流式，引用卡片可读原文，知识库页面展示上传和索引状态，推荐问题使用当前文档主题。
7. **降级要可见：** 无文档时允许普通对话，检索失败不让整个聊天接口崩溃；但生产版必须将降级原因写到事件/状态，不能静默吞异常。

## 9. 不直接照搬的地方

- 使用 Python + RAGFlow 风格代码，不能直接转译成 Java；关注接口与状态，而非复制实现。
- `ESConnection` 硬编码用户名/密码和全局环境变量方式不应进入新项目。
- Embedding 批量失败以 `None` 继续、快速解析与完整索引状态分离、前端切片数量占位等行为应在 Java 版修复。
- 回答后按句子匹配引用可以作为 L5 教学实验，但不能当严格事实验证。
- DashScope reranker 与 Elasticsearch KNN 是一种实现，不应阻塞 Qdrant、pgvector、SimpleVectorStore 适配。

## 10. 对用户提出的新需求的直接结论

1. 数据库改为 MySQL：作为业务元数据和会话持久化；不承担所有向量检索后端。
2. RAG 抽象：以 `VectorStoreAdapter`、`KeywordRetriever`、`HybridRetriever`、`Reranker` 和 `DocumentParser/Chunker` 接口接入 SimpleVectorStore、Qdrant、Elasticsearch、PostgreSQL+pgvector。
3. Maven 多模块：按 contracts/parser/embedding/retrieval/store/llm/research 分层，先只实现 SimpleVectorStore 或 ES 的一个 adapter，其他 adapter 先用契约测试和 stub，避免一开始同时运维四套后端。
4. 独立 LLM 管理：统一 Chat/Embedding/Reranker 的 provider、model、baseUrl、dimension、maxBatchSize、timeout、retry；Agent 只拿能力接口，不读取环境变量。
5. 数据模式：`SAMPLE/LIVE`；行情、财务、新闻、公告各自声明 Provider capability 与 asOf 支持。先使用固定可分发样例和一个 A 股 provider，再逐个新增来源。
6. Harness 与上下文工程：L0 工具/模型拦截与基本预算，L1 数据错误/有限重试，L2 任务取消/事件/上下文初限，L3 子 Agent 隔离与共享预算，L4 结构化降级和有界循环，L5 RAG 结果外置/引用/摘要压缩，L6 checkpoint/恢复/确认，L7 轨迹评测与故障注入。

这份报告支撑“可抽象、多模块、可替换后端”的 Java 设计，但不意味着已实现任何 Java 模块或已验证 FinInsRAG-V3 的线上效果。
