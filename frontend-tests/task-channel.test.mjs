import test from "node:test";
import assert from "node:assert/strict";
import {
  TaskChannel,
  request,
} from "../stock-agent-web/src/main/resources/static/assets/api.js";

test("cancel does not wait for a hanging status request", async () => {
  const calls = [];
  const channel = new TaskChannel({
    cancel: async (id) => {
      calls.push(id);
      return { status: "CANCELLED" };
    },
  });
  channel.id = "task-1";
  assert.equal((await channel.cancel()).status, "CANCELLED");
  assert.deepEqual(calls, ["task-1"]);
});
test("cancel requested before ID is sent as soon as the ID arrives", async () => {
  const calls = [];
  const channel = new TaskChannel({
    cancel: async (id) => {
      calls.push(id);
      return { status: "CANCELLED" };
    },
  });
  assert.equal(await channel.cancel(), null);
  assert.equal((await channel.attach("task-2")).status, "CANCELLED");
  assert.deepEqual(calls, ["task-2"]);
});
test("network request has a finite timeout", async () => {
  const original = globalThis.fetch;
  globalThis.fetch = (_path, options) =>
    new Promise((_resolve, reject) =>
      options.signal.addEventListener("abort", () =>
        reject(new Error("abort")),
      ),
    );
  try {
    await assert.rejects(request("/test", { timeoutMs: 20 }), /连接中断|超时/);
  } finally {
    globalThis.fetch = original;
  }
});
