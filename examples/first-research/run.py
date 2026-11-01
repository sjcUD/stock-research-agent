#!/usr/bin/env python3
"""公开合成资料的端到端演示；会调用已配置的 Embedding 和 Chat 服务。

仅使用标准库。上传固定样例后创建一个任务，不自动重试模型请求。
验证本次文档确实成为检索证据，并通过版本/页接口逐字复核引用。
"""
import json
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8080").rstrip("/")
QUESTION = "仅根据晨星制造教学财报，说明2025年收入和净利润的同比变化、现金流风险。能否计算市盈率？请引用片段，200字以内。"


def request(path, body=None, content_type="application/json"):
    """发送一次请求；超时/失败直接退出，避免隐藏的付费重试。"""
    payload = json.dumps(body, ensure_ascii=False).encode() if isinstance(body, dict) else body
    req = urllib.request.Request(BASE + path, data=payload,
                                 headers={"Content-Type": content_type})
    with urllib.request.urlopen(req, timeout=60) as response:
        return json.load(response)


def main():
    """上传→索引确认→问答→引用原文核对；不读取任何私有本地文件。"""
    content = Path(__file__).with_name("demo-report.md").read_bytes()
    boundary = "StockLearningDemoBoundary"
    multipart = ("--" + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="demo-report.md"\r\nContent-Type: text/markdown\r\n\r\n').encode()
    multipart += content + ("\r\n--" + boundary + "--\r\n").encode()
    indexed = request("/api/rag/documents/upload", multipart, "multipart/form-data; boundary=" + boundary)
    document_id = indexed["documentId"]
    task = request("/api/research/tasks", {"question": QUESTION, "symbol": "600519",
                   "asOf": "2026-10-30", "scope": "DOCUMENTS", "engine": "JAVA_EXECUTOR"})
    print("任务ID:", task["id"], flush=True)
    deadline = time.monotonic() + 180
    while task["status"] in ("QUEUED", "RUNNING") and time.monotonic() < deadline:
        time.sleep(1)
        task = request("/api/research/tasks/" + task["id"])
    if task["status"] != "COMPLETED":
        raise RuntimeError("任务未完成: " + task["status"] + " " + task.get("error", ""))
    result = task["result"]
    hits = [hit for hit in result["evidence"]
            if hit.get("citation", {}).get("documentId") == document_id]
    if not hits:
        raise RuntimeError("本次演示文档未进入检索结果，请检查索引和检索配置")
    print("\n模型实际回答:\n" + result["answer"])
    for hit in hits:
        citation = hit["citation"]
        path = "/api/rag/documents/{}/revisions/{}/pages/{}".format(
            urllib.parse.quote(citation["documentId"], safe=""),
            urllib.parse.quote(citation["revision"], safe=""), citation["page"])
        page = request(path)
        offset = citation["offset"]
        # 服务端偏移是Java UTF-16 code unit；按同样单位切片，兼容非BMP字符。
        raw = page["text"].encode("utf-16-le")
        size = len(hit["text"].encode("utf-16-le"))
        if raw[offset * 2:offset * 2 + size].decode("utf-16-le") != hit["text"]:
            raise RuntimeError("引用偏移与原文不一致")
        print("\n引用:", hit["chunkId"], "\n原文核对:", BASE + path)
    print("\nPASS: 任务完成、命中本次文档、引用偏移与归档原文一致。")
    print("人工核对：收入+20%；净利润-10%；经营现金流6亿元低于净利润9亿元；缺少PE计算条件。")
    print("该检查证明链路和引用可复核，不自动证明模型回答语义正确。")


if __name__ == "__main__":
    main()
