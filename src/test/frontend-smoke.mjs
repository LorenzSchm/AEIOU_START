// Run with Node 20+ against the local demo during frontend integration.
import assert from 'node:assert/strict';
import http from 'node:http';
import { Readable } from 'node:stream';

const base = process.env.DEMO_URL ?? 'http://127.0.0.1:8080';
const headers = {Authorization: 'Bearer demo'};
const proxy = http.createServer(async (req, res) => {
  const controller = new AbortController();
  res.on('close', () => controller.abort());
  try {
    const upstream = await fetch(new URL(req.url, base), {headers, signal: controller.signal});
    res.writeHead(upstream.status, {'content-type': upstream.headers.get('content-type')});
    Readable.fromWeb(upstream.body).on('error', () => res.destroy()).pipe(res);
  } catch { res.destroy(); }
});
await new Promise(resolve => proxy.listen(0, '127.0.0.1', resolve));

try {
  for (const [url, auth] of [[base, headers], [`http://127.0.0.1:${proxy.address().port}`, {}]]) {
    const response = await fetch(url + '/api/v1/dashboard?window=EVENT', {headers: auth});
    assert.equal(response.status, 200);
    const {snapshotCursor} = await response.json();
    assert.ok(BigInt(snapshotCursor) > 0n);
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 8000);
    try {
      const events = await fetch(url + '/api/v1/events/stream?after=' + (BigInt(snapshotCursor) - 1n), {headers: auth, signal: controller.signal});
      assert.equal(events.status, 200);
      const reader = events.body.pipeThrough(new TextDecoderStream()).getReader();
      let buffer = '';
      while (!buffer.includes('\n\n')) {
        const chunk = await reader.read();
        assert.ok(!chunk.done);
        buffer += chunk.value;
      }
      assert.match(buffer, /event:\s*change/);
      const event = JSON.parse(buffer.match(/^data:\s*(.+)$/m)[1]);
      assert.equal(event.sequence, snapshotCursor);
    } finally { clearTimeout(timeout); controller.abort(); }
  }
  console.log('Authenticated direct fetch and same-origin proxy both resumed the SSE stream.');
} finally { proxy.closeAllConnections(); proxy.close(); }
