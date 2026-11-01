import test from 'node:test';
import assert from 'node:assert/strict';
import { TaskEventChannel } from '../stock-agent-web/src/main/resources/static/assets/api.js';
import { canApplySnapshot } from '../stock-agent-web/src/main/resources/static/assets/workspace.js';
class Source {
  static latest;
  constructor(url) { this.url = url; this.handlers = {}; Source.latest = this; }
  addEventListener(name, fn) { this.handlers[name] = fn; }
  close() { this.closed = true; }
  emit(view) { this.handlers.snapshot({ data: JSON.stringify(view) }); }
}
test('SSE uses one existing task and delivers incremental snapshots before terminal', async () => {
  const views = [], channel = new TaskEventChannel('task-1', (view) => views.push(view), Source);
  assert.match(Source.latest.url, /tasks\/task-1\/events$/);
  Source.latest.emit({id:'task-1', status:'RUNNING', draftAnswer:'first', version:1});
  assert.equal(views[0].draftAnswer, 'first');
  assert.equal(channel.closed, false);
  Source.latest.emit({id:'task-1', status:'COMPLETED', version:2});
  assert.equal(await channel.done, true); assert.equal(Source.latest.closed, true);
});
test('stream errors and idle timeout release connection for GET fallback', async () => {
  const failed = new TaskEventChannel('t', () => {}, Source);
  Source.latest.onerror(); assert.equal(await failed.done, false); assert.equal(Source.latest.closed,true);
  const idle = new TaskEventChannel('t', () => {}, Source, 5);
  assert.equal(await idle.done, false); assert.equal(Source.latest.closed,true);
});
test('wrong task snapshots are rejected and explicit cancellation releases waiter', async () => {
  let calls=0; const channel = new TaskEventChannel('a',()=>calls++,Source);
  Source.latest.emit({id:'b',status:'RUNNING'});
  assert.equal(await channel.done,false); assert.equal(calls,0);
  const cancelled=new TaskEventChannel('a',()=>{},Source); cancelled.close(true);
  assert.equal(await cancelled.done,true);
});
test('older versions cannot overwrite a newer draft or terminal state', () => {
  assert.equal(canApplySnapshot({status:'RUNNING',version:4},{status:'RUNNING',version:3}),false);
  assert.equal(canApplySnapshot({status:'CANCELLED',version:5},{status:'RUNNING',version:6}),false);
  assert.equal(canApplySnapshot({status:'RUNNING'},{status:'COMPLETED',version:1}),true);
});
