/** 同源 API 适配器；POST 不自动重试，避免重复启动收费研究。 */
export async function request(path, options = {}) {
  const { timeoutMs = 15000, ...fetchOptions } = options;
  const controller = new AbortController(),
    timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    let response;
    try {
      response = await fetch(path, {
        ...fetchOptions,
        signal: controller.signal,
      });
    } catch {
      throw new Error(
        controller.signal.aborted
          ? "请求超时，请重新连接任务状态。"
          : "连接中断，请检查本地服务后重新连接。",
      );
    }
    let body = {};
    try {
      body = await response.json();
    } catch {
      if (response.ok) throw new Error("服务响应未完成，请重新连接任务状态。");
    }
    if (!response.ok) {
      const messages = {
        400: "输入或文件不符合要求，请检查代码、日期和文件格式。",
        404: "记录已过期或接口未启用。",
        413: "文件超过10MiB，请缩小后重试。",
        429: "研究队列已满，请稍后重试。",
        502: "数据或模型服务未完成请求，请稍后重试。",
        503: "研究或资料服务尚未配置。",
        504: "研究超时，请稍后重试。",
      };
      const detail =
        typeof body.message === "string" && body.message.length < 300
          ? body.message
          : null;
      const error = new Error(
        detail || messages[response.status] || "请求未完成，请检查服务后重试。",
      );
      error.status = response.status;
      throw error;
    }
    return body;
  } finally {
    clearTimeout(timer);
  }
}
export const api = {
  status: () => request("/api/workspace"),
  start: (body) =>
    request("/api/research/tasks", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    }),
  task: (id) => request(`/api/research/tasks/${encodeURIComponent(id)}`),
  cancel: (id) =>
    request(`/api/research/tasks/${encodeURIComponent(id)}`, {
      method: "DELETE",
    }),
  upload: (file) => {
    const body = new FormData();
    body.append("file", file);
    return request("/api/rag/documents/upload", {
      method: "POST",
      body,
      timeoutMs: 180000,
    });
  },
  search: (query) =>
    request(`/api/rag/search?q=${encodeURIComponent(query)}&topK=5`),
};

/** 独立取消通道，不等待状态GET成功；ID尚未到达时保留取消意图。 */
export class TaskChannel {
  constructor(transport = api) {
    this.transport = transport;
    this.id = null;
    this.cancelRequested = false;
    this.cancelSent = false;
  }
  async attach(id) {
    this.id = id;
    return this.cancelRequested ? this.cancel() : null;
  }
  async cancel() {
    this.cancelRequested = true;
    if (!this.id || this.cancelSent) return null;
    this.cancelSent = true;
    try {
      return await this.transport.cancel(this.id);
    } catch (error) {
      this.cancelSent = false;
      throw error;
    }
  }
}
