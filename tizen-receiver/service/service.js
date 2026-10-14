// DuoBeam receiver service (Node.js on Tizen TV), launched by the DuoBeam web app when it opens.
// :8201 - the sender pushes frames [len u32][type u8][data]; type 0 = init (codec + '\n' + init segment), 1 = keyframe, 2 = frame,
//         5 = audio frame (track 2 of the same fMP4), 6 = frame mark (media time, capture, send - sender clock),
//         9 = reply to our clock ping (type 8, sent every 1 s) -> sender-TV clock offset
// Type 6 is forwarded to the player as JSON in TV clock: {mt, cap, send, recv, rtt}
// :8202 - GET /stream for the player (a new sink gets the init and starts at the next keyframe),
//         GET /hello for receiver discovery, GET /status for diagnostics, POST/GET /report - player report
var http = require('http');
var net = require('net');
var VERSION = '0.8.7';
var switchingUntil = 0; // after a layout-switch signal, a sender disconnect does not end the stream
// type 3 (service -> player only) = sender disconnected
var IN_PORT = 8201, OUT_PORT = 8202;
var stream = { init: null, sinks: [], src: 0, lastSrc: null, frames: 0, bytes: 0, dropped: 0, started: new Date().toISOString() };
var lastReport = null;
var clockSamples = [];
function i64(b, o) { return b.readInt32BE(o) * 4294967296 + b.readUInt32BE(o + 4); }
function frameMsg(type, payload) { var m = Buffer.alloc(5 + payload.length); m.writeUInt32BE(payload.length + 1, 0); m[4] = type; payload.copy(m, 5); return m; }

function fanout(msg, type) {
  stream.sinks.forEach(function (k) {
    if (k.waitKey) { if (type !== 1) return; k.waitKey = false; }
    if (k.res.writableLength > 4 * 1024 * 1024) { k.waitKey = true; stream.dropped++; return; } // sink too slow: skip to the next keyframe
    k.res.write(msg);
  });
}

// The handheld that is streaming. A second one while it sends is turned away: both used to be forwarded into the one
// stream. The same handheld reconnecting (stream restart), or a new one after 2 s of silence, replaces it.
var active = null;

var ingest = net.createServer(function (c) {
  var addr = (c.remoteAddress || '').replace(/^::ffff:/, '');
  if (active && active.addr !== addr && Date.now() - active.lastData < 2000) { c.destroy(); return; }
  if (active) active.sock.destroy();
  var me = { sock: c, addr: addr, lastData: Date.now() };
  active = me;
  c.setNoDelay(true); stream.src++; stream.lastSrc = addr;
  // clock ping: the sender echoes our time plus its own (type 9); the lowest-RTT sample gives the most accurate offset
  var pingT = setInterval(function () {
    var b = Buffer.alloc(8); b.writeDoubleBE(Date.now(), 0);
    try { c.write(frameMsg(8, b)); } catch (e) {}
  }, 1000);
  var buf = Buffer.alloc(0);
  c.on('data', function (d) {
    me.lastData = Date.now();
    buf = buf.length ? Buffer.concat([buf, d]) : d;
    while (buf.length >= 5) {
      var len = buf.readUInt32BE(0);
      if (len < 1 || len > 64 * 1024 * 1024) { c.destroy(); return; } // garbage, not a frame (0 misread the next one)
      if (buf.length < 4 + len) break;
      var msg = buf.slice(0, 4 + len), type = buf[4];
      buf = buf.slice(4 + len);
      if (type === 0) { stream.init = Buffer.from(msg); stream.sinks.forEach(function (k) { k.res.write(msg); k.waitKey = true; }); continue; }
      if (type === 9 && len === 17) {
        var t0 = msg.readDoubleBE(5), now = Date.now(), thor = i64(msg, 13);
        clockSamples.push({ rtt: now - t0, off: thor - (t0 + now) / 2 }); if (clockSamples.length > 15) clockSamples.shift();
        stream.clock = clockSamples.reduce(function (a, x) { return x.rtt < a.rtt ? x : a; });
        continue;
      }
      if (type === 6 && len === 25) {
        if (!stream.clock) continue;
        var off = stream.clock.off;
        var j = { mt: i64(msg, 5) / 1e6, cap: i64(msg, 13) - off, send: i64(msg, 21) - off, recv: Date.now(), rtt: stream.clock.rtt };
        stream.lastMark = j;
        var jm = frameMsg(6, Buffer.from(JSON.stringify(j)));
        stream.sinks.forEach(function (k) { k.res.write(jm); });
        continue;
      }
      if (type === 4) { switchingUntil = Date.now() + 8000; stream.sinks.forEach(function (k) { k.res.write(msg); }); continue; }
      if (type !== 5) stream.frames++;
      else { // average audio frame size: AAC silence is ~10 B, real audio a few hundred B
        stream.audio = (stream.audio || 0) + 1;
        stream.audioAvg = Math.round((stream.audioAvg || len) * 0.98 + len * 0.02);
      }
      stream.bytes += len;
      fanout(msg, type);
    }
  });
  c.on('error', function () {});
  c.on('close', function () {
    if (active === me) active = null;
    clearInterval(pingT); clockSamples = []; stream.clock = null;
    stream.src--;
    if (stream.src > 0) return;
    // sender disconnected: a stale init must not look like an active stream
    stream.init = null;
    if (Date.now() < switchingUntil) return; // layout switch - the sender reconnects shortly
    var bye = Buffer.from([0, 0, 0, 1, 3]);
    stream.sinks.forEach(function (k) { k.res.write(bye); k.waitKey = true; });
  });
});

// The player page runs on the TV and connects to the TV's own address. /stream (the picture) and POST /report (which
// can switch the player and so restart the sender) answered anyone on the network; now only the TV itself.
// A request from the TV itself comes from the same address it arrives at (os.networkInterfaces() failed inside the
// Tizen service and the page got no picture, QE43Q67C 2026-10-14).
function fromThisTv(req) {
  var s = req.socket;
  if (!s) return false;
  var from = (s.remoteAddress || '').replace(/^::ffff:/, ''), to = (s.localAddress || '').replace(/^::ffff:/, '');
  return from !== '' && (from === to || from === '127.0.0.1' || from === '::1');
}

var out = http.createServer(function (req, res) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  if ((req.url === '/stream' || (req.url === '/report' && req.method === 'POST')) && !fromThisTv(req)) {
    stream.denied = { n: ((stream.denied && stream.denied.n) || 0) + 1, from: (req.socket && req.socket.remoteAddress) || '', url: req.url };
    res.statusCode = 403; return res.end('{"error":"only the TV itself"}');
  }
  if (req.url === '/stream') {
    res.writeHead(200, { 'Content-Type': 'application/octet-stream', 'Cache-Control': 'no-store' });
    if (res.socket) res.socket.setNoDelay(true);
    var k = { res: res, waitKey: true };
    if (stream.init) res.write(stream.init);
    stream.sinks.push(k);
    req.on('close', function () { stream.sinks = stream.sinks.filter(function (x) { return x !== k; }); });
    return;
  }
  res.setHeader('Content-Type', 'application/json');
  if (req.url === '/hello') {
    return res.end(JSON.stringify({ app: 'DuoBeam', role: 'receiver', version: VERSION, platform: 'tizen', ingestPort: IN_PORT, busy: stream.src > 0,
      player: stream.player || 'mse', maxVideo: stream.maxVideo || '', maxPixels: stream.maxPixels || 0, screen: stream.screen || '' }));
  }
  if (req.url === '/status') {
    // maxVideo early: the handheld app reads only the first 1 KB
    return res.end(JSON.stringify({ player: stream.player || 'mse', version: VERSION, maxVideo: stream.maxVideo || '', maxPixels: stream.maxPixels || 0, screen: stream.screen || '', problem: stream.problem || '', started: stream.started, src: stream.src, lastSrc: stream.lastSrc, sinks: stream.sinks.length,
      frames: stream.frames, audio: stream.audio || 0, audioAvgBytes: stream.audioAvg || 0, bytes: stream.bytes, dropped: stream.dropped, hasInit: !!stream.init,
      clock: stream.clock || null, lastMark: stream.lastMark || null, denied: stream.denied || null }));
  }
  if (req.url === '/report') {
    if (req.method === 'POST') { var b = ''; req.on('data', function (c) { b += c; }); req.on('end', function () {
      lastReport = b;
      try {
        var j = JSON.parse(b);
        if (j.player) stream.player = j.player; // emss = PCM audio, mse = AAC/Opus
        if (j.maxVideo) stream.maxVideo = j.maxVideo; // largest frame the TV takes, from the page
        if (j.maxPixels) stream.maxPixels = j.maxPixels; // largest frame area of its HEVC level
        if (j.screen) stream.screen = j.screen;
        stream.problem = j.problem || ''; // what failed after the automatic fixes, shown by the handheld app
      } catch (e) {}
      res.end('{"ok":true}'); }); return; }
    return res.end(lastReport || 'null');
  }
  res.statusCode = 404; res.end('{"error":"not found"}');
});

module.exports.onStart = function () {
  try { ingest.listen(IN_PORT, '0.0.0.0'); out.listen(OUT_PORT, '0.0.0.0'); } catch (e) {}
};
module.exports.onRequest = function () {};
module.exports.onStop = function () { try { ingest.close(); out.close(); } catch (e) {} };
