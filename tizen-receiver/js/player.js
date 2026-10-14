// DuoBeam player: stream from the local service (http://<TV>:8202/stream) -> MSE.
// Frames: [len u32][type u8][data]; type 0 = codec string + newline + init segment, 1 = keyframe, 2 = frame (moof+mdat),
// 5 = audio frame (moof+mdat of track 2; the codec string then has ",mp4a.40.2" and init contains both tracks).
// After the first 'playing', seek once to the buffer's live edge to drop the startup backlog.
// The web app cannot reach 127.0.0.1, so connect to the TV's own IP.
var TV_IP = '';
try { var ip = webapis.network.getIp(); if (ip) TV_IP = ip; } catch (e) {}
if (!TV_IP) { try { console.error('DuoBeam: cannot read TV IP'); } catch (e) {} }
var SERVICE_ID = 'DuoBeamRcv.DuoBeamService';
var STREAM_URL = 'http://' + TV_IP + ':8202/stream', REPORT_URL = 'http://' + TV_IP + ':8202/report';
try { tizen.application.launch(SERVICE_ID, function () {}, function () {}); } catch (e) {}
var v = document.getElementById('v'), hud = document.getElementById('hud');
v.muted = false; v.volume = 1;
// Seek cost: after a seek the player stalls briefly (~300 ms with audio) and the buffer grows meanwhile.
// Without accounting for this, "shrink buffer" seeks repeat in a loop every few seconds.
var lastSeekAt = 0, seekStall = 0;
// latency: mark {mt, cap, send, recv} (TV clock); once playback reaches mt, that frame is on screen
var marks = [], latSamples = [];
function median(a) { var s = a.slice().sort(function (x, y) { return x - y; }); return s.length ? s[s.length >> 1] : null; }
setInterval(function () {
  if (!played || (!useEmss && v.paused)) return;
  var ct = useEmss ? emssMediaTime() : v.currentTime, now = Date.now();
  while (marks.length && marks[0].mt <= ct) {
    var m = marks.shift(), late = ct - m.mt;
    if (late > 0.2) continue; // stale mark (e.g. after a seek)
    var shown = now - late * 1000;
    latSamples.push({ total: shown - m.cap, enc: m.send - m.cap, net: m.recv - m.send, tv: shown - m.recv });
    if (latSamples.length > 30) latSamples.shift();
  }
  if (latSamples.length >= 3) {
    var pick = function (k) { return Math.round(median(latSamples.map(function (x) { return x[k]; }))); };
    S.lat = { total: pick('total'), enc: pick('enc'), net: pick('net'), tv: pick('tv'), n: latSamples.length,
      max: Math.round(Math.max.apply(null, latSamples.map(function (x) { return x.total; }))) };
  }
}, 20);
function seekTo(t) { lastSeekAt = performance.now(); v.currentTime = t; }
// largest frame for the sender (the service passes it on in /status): what the panel shows. QE43Q67C (4K) plays
// 3160x1080 and 992x2160 but rejects 1080x2340 (2026-10-12). '' = unknown, the sender then uses its default.
var MAX_VIDEO = '';
try { MAX_VIDEO = webapis.productinfo.isUdPanelSupported() ? '3840x2160' : '1920x1080'; } catch (e) {}
var S = { kind: 'player', maxVideo: MAX_VIDEO, started: new Date().toISOString(), frames: 0, keyframes: 0, audio: 0, bytes: 0, inits: 0, waiting: 0, seeks: 0,
  events: [], codec: null, aheadMs: null, errors: {}, recoveries: [], quota: 0, diag: null };
var ms = null, sb = null, queue = [], jumped = false, t0 = performance.now(), hudOn = false;
// waiting screen: visible until video arrives; shown again when the handheld stops sending
var idle = document.getElementById('idle'), stateText = document.getElementById('stateText'), lastFrameAt = 0;
document.getElementById('addr').textContent = TV_IP;

var stateKey = 'waiting';
var switchEl = document.getElementById('switching');
function switchingRecent() { try { return Date.now() - parseInt(sessionStorage.getItem('duobeam.switching') || '0', 10) < 10000; } catch (e) { return false; } }
function setSwitching(on) {
  try { if (on) sessionStorage.setItem('duobeam.switching', String(Date.now())); else sessionStorage.removeItem('duobeam.switching'); } catch (e) {}
  document.getElementById('switchingText').textContent = tr('switching');
  if (on) { switchEl.classList.add('on'); idle.classList.add('hidden'); } else switchEl.classList.remove('on');
}
function applyLang() {
  stateText.textContent = tr(stateKey);
  document.getElementById('addrLabel').textContent = tr('addrLabel');
  document.getElementById('steps').innerHTML = tr('steps');
  document.getElementById('keys').innerHTML = tr('keys');
  document.getElementById('flag').textContent = tr('flag');
  document.getElementById('ver').textContent = tr('receiver') + ' v' + RECEIVER_VERSION + ' (Samsung Tizen)';
}
function setLang(l) { lang = l; try { localStorage.setItem('duobeam.lang', l); } catch (e) {} applyLang(); }
if (switchingRecent()) setSwitching(true); // page was reloaded during a layout switch
setTimeout(function () { if (!played && switchingRecent() === false) { switchEl.classList.remove('on'); setIdle(true); } }, 10500);
function setIdle(on, key) {
  if (key) { stateKey = key; stateText.textContent = tr(key); }
  if (on && switchingRecent()) return; // during a layout switch keep the spinner, not the welcome card
  if (on) { idle.classList.remove('hidden'); setSwitching(false); } else idle.classList.add('hidden');
}
applyLang();

function ev(name) { S.events.push(name + '@' + Math.round(performance.now() - t0)); if (S.events.length > 80) S.events.shift(); }
// errors are counted per message so a repeating one doesn't flood the event log
function err(e) { e = String(e); if (!S.errors[e]) ev('ERR ' + e); S.errors[e] = (S.errors[e] || 0) + 1; lastErr = e; }
var lastErr = '';
function ranges() { var r = []; if (sb) for (var i = 0; i < sb.buffered.length; i++) r.push([+sb.buffered.start(i).toFixed(3), +sb.buffered.end(i).toFixed(3)]); return r; }
function diag() { return { ct: +v.currentTime.toFixed(3), paused: v.paused, rs: v.readyState, ns: v.networkState, ranges: ranges(), q: queue.length, upd: sb && sb.updating }; }
function report() {
  S.player = useEmss ? 'emss' : 'mse';
  if (useEmss) S.emss = { aUnder: E.aUnder, aDrop: E.aDrop, aLead: E.aLead === null ? null : +E.aLead.toFixed(3), aShift: +aShift.toFixed(2), aQ: AQ.length,
    state: E.state, vOpen: E.vOpen, aOpen: E.aOpen, audio: E.audio, vSent: E.vSent, aSent: E.aSent,
    vErr: E.vErr, aErr: E.aErr, lastErr: E.lastErr, size: E.w + 'x' + E.h, base: E.base, validate: E.validate, ct: +v.currentTime.toFixed(3), paused: v.paused };
  try { var x = new XMLHttpRequest(); x.open('POST', REPORT_URL, true); x.setRequestHeader('Content-Type', 'text/plain'); x.send(JSON.stringify(S)); } catch (e) {}
}
function pump() {
  if (!sb || sb.updating || !queue.length) return;
  var d = queue[0];
  try { sb.appendBuffer(d); queue.shift(); }
  catch (e) {
    if (e.name === 'QuotaExceededError' && sb.buffered.length) {
      // buffer full: drop everything but the last 2 s and retry on updateend
      S.quota++; var end = sb.buffered.end(sb.buffered.length - 1);
      try { sb.remove(0, Math.max(0, Math.min(v.currentTime, end) - 2)); } catch (x) { err('remove ' + x.message); queue.shift(); }
    } else { err('append ' + e.name + ' ' + e.message); queue.shift(); }
  }
}
// Keyframe times (s): the buffer may only be cut at a keyframe, otherwise MSE also removes the dependent frames up to
// the next keyframe, including what is currently playing (shows up as periodic picture jumps).
var keyTimes = [];
function tfdtSeconds(d) {
  for (var i = 8; i + 20 < d.length && i < 200; i++) {
    if (d[i] === 0x74 && d[i + 1] === 0x66 && d[i + 2] === 0x64 && d[i + 3] === 0x74) { // 'tfdt', version 1 -> 64-bit
      var hi = ((d[i + 8] << 24) | (d[i + 9] << 16) | (d[i + 10] << 8) | d[i + 11]) >>> 0;
      var lo = ((d[i + 12] << 24) | (d[i + 13] << 16) | (d[i + 14] << 8) | d[i + 15]) >>> 0;
      return (hi * 4294967296 + lo) / 1e6;
    }
  }
  return null;
}
function trim() {
  if (!sb || sb.updating || !sb.buffered.length) return;
  var start = sb.buffered.start(0), cut = null;
  for (var i = 0; i < keyTimes.length; i++) if (keyTimes[i] <= v.currentTime - 2) cut = keyTimes[i];
  if (cut !== null && cut - start > 5) { try { sb.remove(start, cut); ev('trim ' + start.toFixed(1) + '-' + cut.toFixed(1)); } catch (e) {} }
  while (keyTimes.length > 1 && keyTimes[1] <= start) keyTimes.shift();
}
function setupMse(codec, init) {
  S.codec = codec; S.inits++; jumped = false; played = false; keyTimes = []; queue = [init];
  ms = new MediaSource();
  ms.addEventListener('sourceopen', function () {
    try { sb = ms.addSourceBuffer('video/mp4; codecs="' + codec + '"'); }
    catch (e) { return err('addSourceBuffer ' + e.message); }
    sb.addEventListener('updateend', pump);
    // joining mid-stream: media time starts at e.g. 267 s while the player sits at 0 -> jump to the start of the data
    sb.addEventListener('updateend', function () {
      if (!played && sb.buffered.length && v.currentTime < sb.buffered.start(0)) { ev('start at ' + sb.buffered.start(0).toFixed(2)); v.currentTime = sb.buffered.start(0); }
    });
    pump();
    var p = v.play(); if (p && p.catch) p.catch(function (e) { ev('playErr ' + e.message); });
  });
  v.src = URL.createObjectURL(ms);
  ev('init ' + codec);
}
var played = false;
v.addEventListener('playing', function () {
  ev('playing'); played = true; setIdle(false); setSwitching(false);
  if (lastSeekAt && performance.now() - lastSeekAt < 2000) {
    var st = performance.now() - lastSeekAt; lastSeekAt = 0;
    seekStall = seekStall ? Math.round(seekStall * 0.6 + st * 0.4) : Math.round(st); S.seekStallMs = seekStall;
  }
  if (!jumped && sb && sb.buffered.length) {
    jumped = true;
    var end = sb.buffered.end(sb.buffered.length - 1);
    if (end - v.currentTime > 0.1) { seekTo(end - 0.05); S.seeks++; ev('jump ' + Math.round((end - v.currentTime) * 1000)); }
  }
});
v.addEventListener('waiting', function () { S.waiting++; ev('waiting'); });
v.addEventListener('error', function () { err('video ' + (v.error && v.error.code)); });

var initAt = 0;
function onMessage(type, data) {
  if (type === 4) { ev('switching'); setSwitching(true); return; } // the handheld is switching layout
  if (type === 3) { // sender disconnected
    ev('source gone'); report();
    if (ms) { setTimeout(function () { location.reload(); }, 50); ms = null; sb = null; queue = []; }
    else setIdle(true, 'gone');
    return;
  }
  if (type === 0) {
    // new stream (e.g. different resolution or sender restart): reload the whole page instead of re-wiring MSE
    if (E.src && useEmss) {
      ev('new init -> emss restart'); setSwitching(true);
      var nl0 = data.indexOf(10), codec0 = String.fromCharCode.apply(null, data.subarray(0, nl0)), init0 = data.subarray(nl0 + 1);
      emssTeardown(); played = false; S.codec = codec0; S.inits++;
      setTimeout(function () { if (!emssSetup(codec0, init0)) location.reload(); }, 30);
      return;
    }
    if (ms || E.src) { ev('new init -> reload'); report(); setTimeout(function () { location.reload(); }, 50); ms = null; sb = null; queue = []; return; }
    setIdle(true, 'connected'); initAt = performance.now();
    var nl = data.indexOf(10);
    var codec = String.fromCharCode.apply(null, data.subarray(0, nl));
    S.codec = codec; S.inits++;
    if (useEmss && emssSetup(codec, data.subarray(nl + 1))) return;
    useEmss = false; // EMSS unavailable or failed: fall back to MSE
    setupMse(codec.replace(',pcm', ''), data.subarray(nl + 1));
    return;
  }
  if (type === 6) { try { marks.push(JSON.parse(String.fromCharCode.apply(null, data))); if (marks.length > 40) marks.shift(); } catch (e) {} return; }
  if (type === 5) {
    S.audio++; S.bytes += data.length;
    if (useEmss) { emssAudio(data); return; }
    if ((sb || ms) && S.codec && S.codec.indexOf('pcm') < 0) { queue.push(data); pump(); } // PCM is EMSS-only
    return;
  }
  S.frames++; S.bytes += data.length; lastFrameAt = performance.now();
  if (type === 1) { S.keyframes++; var kt = tfdtSeconds(data); if (kt !== null) keyTimes.push(kt); }
  if (useEmss) { emssVideo(data, type === 1); return; }
  if (!sb && !ms) return;
  queue.push(data); pump();
}
function connect() {
  ev('connect');
  fetch(STREAM_URL, { cache: 'no-store' }).then(function (r) {
    var reader = r.body.getReader(), buf = new Uint8Array(0);
    function read() {
      return reader.read().then(function (res) {
        if (res.done) { ev('stream end'); return setTimeout(connect, 1000); }
        var c = res.value;
        var nb = new Uint8Array(buf.length + c.length); nb.set(buf); nb.set(c, buf.length); buf = nb;
        var off = 0;
        while (buf.length - off >= 5) {
          var len = ((buf[off] << 24) | (buf[off + 1] << 16) | (buf[off + 2] << 8) | buf[off + 3]) >>> 0;
          if (buf.length - off < 4 + len) break;
          onMessage(buf[off + 4], buf.slice(off + 5, off + 4 + len));
          off += 4 + len;
        }
        buf = buf.slice(off);
        return read();
      });
    }
    return read();
  }).catch(function (e) { err('fetch ' + e.message); setTimeout(connect, 1000); });
}

// watchdog: video time stuck > 700 ms while buffered data lies ahead -> seek to unstick
var lastCt = -1, lastCtAt = performance.now();
setInterval(function () {
  // only after the first start: before that the player buffers on its own and a seek would restart its startup
  if (!played || !sb || !sb.buffered.length || sb.updating) return;
  var now = performance.now(), ct = v.currentTime;
  if (ct !== lastCt) { lastCt = ct; lastCtAt = now; return; }
  var end = sb.buffered.end(sb.buffered.length - 1);
  if (now - lastCtAt < 700 || end - ct < 0.3) return;
  var d = diag(), target = end - 0.05, why = 'stall';
  for (var i = 0; i < sb.buffered.length; i++) if (sb.buffered.start(i) > ct + 0.001) { why = 'gap'; break; }
  S.recoveries.push({ at: Math.round(now - t0), why: why, diag: d, frames: S.frames });
  if (S.recoveries.length > 20) S.recoveries.shift();
  ev('recover ' + why + ' ct ' + ct.toFixed(2) + ' -> ' + target.toFixed(2));
  seekTo(target); lastCtAt = now;
  if (v.paused) { var p = v.play(); if (p && p.catch) p.catch(function () {}); }
}, 100);
// Buffer modes (up/down on the remote): a bigger buffer means fewer stutters on Wi-Fi bursts, at the cost of latency.
// The buffer grows through a short playback pause and shrinks with a single seek (playbackRate changes play poorly on the TV).
var MODES = [{ ms: 150 }, { ms: 300 }, { ms: 500 }];
function modeName(i) { return tr('modes')[i]; }
var mode = 0;
try { mode = Math.min(MODES.length - 1, Math.max(0, parseInt(localStorage.getItem('duobeam.mode') || '0', 10) || 0)); } catch (e) {}
var aheadHist = [], holding = false, highSince = 0, toast = '', toastUntil = 0;
function setMode(m) {
  mode = (m + MODES.length) % MODES.length;
  try { localStorage.setItem('duobeam.mode', String(mode)); } catch (e) {}
  toast = tr('mode') + ': ' + modeName(mode) + ' (' + MODES[mode].ms + ' ms)'; toastUntil = performance.now() + 3000;
  aheadHist = []; S.mode = I18N.en.modes[mode]; ev('mode ' + S.mode);
}
S.mode = I18N.en.modes[mode];
setInterval(function () {
  if (!played || holding || !sb || !sb.buffered.length || sb.updating || v.paused) return;
  var a = (sb.buffered.end(sb.buffered.length - 1) - v.currentTime) * 1000;
  aheadHist.push(a); if (aheadHist.length > 12) aheadHist.shift();
  if (aheadHist.length < 8) return;
  var med = aheadHist.slice().sort(function (x, y) { return x - y; })[aheadHist.length >> 1], target = MODES[mode].ms;
  if (S.audio > 0) { // with audio: catch up by speeding up instead of seeking (no stall)
    if (v.playbackRate > 1) {
      if (a <= target + 30) { v.playbackRate = 1; ev('rate 1 at ' + Math.round(a)); aheadHist = []; }
      return;
    }
    if (med > target + 120) { v.playbackRate = 1.1; S.rateUps = (S.rateUps || 0) + 1; ev('rate 1.1 from ' + Math.round(med)); return; }
  }
  if (med < target - 80) {
    var hold = Math.min(1000, target - med);
    holding = true; v.pause(); ev('hold ' + Math.round(hold));
    setTimeout(function () { holding = false; aheadHist = []; var p = v.play(); if (p && p.catch) p.catch(function () {}); }, hold);
  } else if (med > target + 200 + seekStall) { // seek only when the gain exceeds the stall it causes
    if (!highSince) highSince = performance.now();
    if (performance.now() - highSince > 3000) {
      var end = sb.buffered.end(sb.buffered.length - 1);
      seekTo(end - Math.max(0.03, (target - seekStall) / 1000)); S.seeks++; ev('shrink ' + Math.round(med) + '->' + target + ' stall ' + seekStall);
      highSince = 0; aheadHist = [];
    }
  } else highSince = 0;
}, 250);
setInterval(function () {
  S.diag = diag();
  if (sb && sb.buffered.length) S.aheadMs = Math.round((sb.buffered.end(sb.buffered.length - 1) - v.currentTime) * 1000);
  trim();
  // one fact per line, like the Android TV receiver
  var t = [(useEmss ? 'EMSS ' : 'MSE ') + (S.codec || (tr('waiting') + ' ' + TV_IP + ':8201')),
    S.lat ? tr('latency') + ' ' + S.lat.total + ' ms  (handheld ' + S.lat.enc + ' / Wi-Fi ' + S.lat.net + ' / TV ' + S.lat.tv + ')' : '',
    tr('frames') + ' ' + S.frames + ' (key ' + S.keyframes + ')  ' + tr('buffer') + ' ' + S.aheadMs + ' ms  ' + (S.bytes / 1048576).toFixed(1) + ' MB',
    (S.audio ? '\u266A ' + S.audio + '  ' : '') + 'waiting ' + S.waiting + '  recover ' + S.recoveries.length + (lastErr ? '  ' + lastErr : ''),
    tr('mode') + ': ' + modeName(mode)].filter(Boolean).join('\n');
  if (played && performance.now() - lastFrameAt > 3000) setIdle(true, 'stopped');
  else if (!played && initAt && !lastFrameAt && performance.now() - initAt > 8000) setIdle(true, 'novideo');
  else if (played && lastFrameAt) setIdle(false);
  if (performance.now() < toastUntil) { hud.style.display = 'block'; hud.textContent = toast; }
  else { hud.style.display = hudOn ? 'block' : 'none'; if (hudOn) hud.textContent = t; }
}, 250);
setInterval(report, 2000);
// OK = toggle HUD, up/down = buffer mode, Return = exit
document.addEventListener('keydown', function (e) {
  if (e.keyCode === 13) { hudOn = !hudOn; }
  if (e.keyCode === 403) { // red: toggle low-latency (EMSS) <-> regular (MSE) player
    try { localStorage.setItem('duobeam.player', useEmss ? 'mse' : 'emss'); } catch (x) {}
    report(); setTimeout(function () { location.reload(); }, 100);
  }
  if (e.keyCode === 37 || e.keyCode === 39) setLang(lang === 'en' ? 'pl' : 'en');
  if (e.keyCode === 38) setMode(mode + 1);
  if (e.keyCode === 40) setMode(mode - 1);
  if (e.keyCode === 10009) { try { tizen.application.getCurrentApplication().exit(); } catch (x) {} }
});
try { tizen.tvinputdevice.registerKey('ColorF0Red'); } catch (e) {}
connect();
