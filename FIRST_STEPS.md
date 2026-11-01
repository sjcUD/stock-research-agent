# 第一个实验：从合成财报到可核验回答

目标：跑通“上传→解析/切片→Embedding→Qdrant检索→模型回答→原文核对”，再修改一个参数观察变化。这个实验不需要接入市场行情，所有财务数字都来自仓库内公开合成文档。

## 1. 启动与运行

先按[部署手册](DEPLOYMENT.md)配置Chat/Embedding服务并启动。模型仅支持特定扩展参数时再配置thinking；脚本会调用模型并消耗额度，没有自动付费重试。需要Python 3，无额外Python依赖。

```bash
./scripts/dev.sh start
python3 examples/first-research/run.py
# 自定义端口时：python3 examples/first-research/run.py http://127.0.0.1:18080
```

脚本仅上传[教学财报](examples/first-research/demo-report.md)，创建一个资料问答任务，打印模型实际答案和原文链接，并校验引用片段在归档原文中的偏移。重复运行会新建任务并再次调用模型；在独立演示库中复现能减少其他资料对检索的影响。

工作台的证券/日期仍是通用任务字段；示例中的600519不表示教学企业的真实证券身份。`DOCUMENTS`模式不读取市场行情。

## 2. 核对结果

固定问题：2025年收入与净利润同比变化、现金流风险，能否计算市盈率？

| 核验项 | 原文支持的答案 |
| --- | --- |
| 营业收入 | 100亿元→120亿元，同比+20% |
| 净利润 | 10亿元→9亿元，同比-10% |
| 现金流 | 经营现金流6亿元低于净利润9亿元；应收账款15亿元→24亿元，需核验回款 |
| 市盈率 | 缺少股价、每股收益等条件，不能计算 |
| 不应添加 | 股价预测、买卖建议，或把回款风险直接认定为造假 |

模型措辞不要求逐字一致。脚本PASS仅代表任务完成、文档被检索、引用可回链；回答中的数字、推断和拒答仍按上表人工核验。

## 3. 在界面复核

刷新工作台，在历史中打开本次问题，查看回答和来源。进入来源片段的原文预览，比较文档版本、逻辑页、片段内容。Markdown没有物理PDF页码，逻辑页1不应理解为PDF第一页。

## 4. 按顺序阅读代码

| 顺序 | 入口 | 本次只回答一个问题 |
| --- | --- | --- |
| 1 | [WorkspaceController](stock-agent-web/src/main/java/io/github/sjcud/stockagent/web/WorkspaceController.java) | 上传成功何时才算索引成功？ |
| 2 | [RagIngestionService](stock-agent-rag/src/main/java/io/github/sjcud/stockagent/rag/application/RagIngestionService.java) | 原文、解析、切片和向量写入如何衔接？ |
| 3 | [ResearchChatService](stock-agent-research-agent/src/main/java/io/github/sjcud/stockagent/research/ResearchChatService.java) | 检索片段如何进入模型上下文？ |
| 4 | [SpringAiChatModelGateway](stock-agent-llm/src/main/java/io/github/sjcud/stockagent/llm/SpringAiChatModelGateway.java) | 正文、usage和空响应怎样处理？ |
| 5 | [DocumentPreviewController](stock-agent-web/src/main/java/io/github/sjcud/stockagent/web/DocumentPreviewController.java) | 引用怎样回到指定版本的原文？ |

## 5. 动手改变一个变量

先不调用Chat模型，用检索API对照同一问题的topK：

```bash
curl -G http://127.0.0.1:8080/api/rag/search --data-urlencode 'q=晨星制造现金流风险' --data-urlencode 'topK=1'
curl -G http://127.0.0.1:8080/api/rag/search --data-urlencode 'q=晨星制造现金流风险' --data-urlencode 'topK=5'
```

观察命中数量、相似度、片段和引用；本样例在默认800字符窗口下只有一个片段，干净索引中topK=1与5得到相同结果是预期行为，topK只是数量上限。可再上传另一份明确标注的教学资料观察候选变化。检索API不调用Chat，但会调用Embedding。更多片段不等于更好的回答。该API参数不会改变聊天服务内固定的topK；需要改造聊天检索时，应进入`ResearchChatService`修改并测试。

最后运行相关确定性测试，不需要模型密钥：

```bash
mvn -pl stock-agent-rag,stock-agent-llm -am test
```

继续学习：[Graph对照](GRAPH.md)、[持久化](PERSISTENCE.md)、[评测实验](stock-agent-evaluation/README.md)。
