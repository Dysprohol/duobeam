// DuoBeam low-latency player: Samsung ElementaryMediaStreamSource (tizentvwasm) driven directly from JS.
// No MSE buffer: each frame goes straight to the hardware decoder (ultra-low latency mode, meant for game streaming).
// Input is the same as for MSE: init (hvcC) + moof/mdat fragments, converted here to Annex-B / raw audio frames.
// Uses globals from player.js (v, S, ev, err, setIdle, setSwitching); only called once both files are loaded.
var EMSS_OK = typeof tizentvwasm !== 'undefined' && !!tizentvwasm.ElementaryMediaStreamSource;
var useEmss = EMSS_OK;
try { if (localStorage.getItem('duobeam.player') === 'mse') useEmss = false; } catch (e) {}

var E = { src: null, vt: null, at: null, vOpen: false, aOpen: false, ps: null, w: 0, h: 0, base: null, vcodec: '', acodec: '',
  waitKey: true, vSent: 0, aSent: 0, vErr: 0, aErr: 0, lastErr: '', state: 'none', audio: 'none', validate: {} };

function u16(b, o) { return (b[o] << 8) | b[o + 1]; }
function u32(b, o) { return ((b[o] << 24) | (b[o + 1] << 16) | (b[o + 2] << 8) | b[o + 3]) >>> 0; }
function findType(b, t, from, last) {
  var c0 = t.charCodeAt(0), c1 = t.charCodeAt(1), c2 = t.charCodeAt(2), c3 = t.charCodeAt(3), found = -1;
  for (var i = from || 0; i + 4 <= b.length; i++) {
    if (b[i] === c0 && b[i + 1] === c1 && b[i + 2] === c2 && b[i + 3] === c3) { if (!last) return i; found = i; }
  }
  return found;
}
function concat(parts) {
  var n = 0, i; for (i = 0; i < parts.length; i++) n += parts[i].length;
  var out = new Uint8Array(n), o = 0;
  for (i = 0; i < parts.length; i++) { out.set(parts[i], o); o += parts[i].length; }
  return out;
}
var START = new Uint8Array([0, 0, 0, 1]);
// tfdt (version 1, 64-bit) in track timescale units
function tfdtRaw(d) {
  var i = findType(d, 'tfdt', 0, false);
  if (i < 0) return null;
  return u32(d, i + 8) * 4294967296 + u32(d, i + 12);
}
function mdatPayload(d) {
  var i = findType(d, 'mdat', 0, false);
  if (i < 4) return null;
  return d.subarray(i + 4, i - 4 + u32(d, i - 4));
}

// init: picture size and VPS/SPS/PPS (from hvcC) as Annex-B; sent to the decoder before every keyframe
function emssParseInit(init) {
  var hc = findType(init, 'hvcC', 0, false);
  E.w = u16(init, hc - 58); E.h = u16(init, hc - 56);
  var p = hc + 4 + 22, n = init[p++], parts = [];
  for (var a = 0; a < n; a++) {
    p++; var num = u16(init, p); p += 2;
    for (var j = 0; j < num; j++) { var len = u16(init, p); p += 2; parts.push(START, init.subarray(p, p + len)); p += len; }
  }
  E.ps = concat(parts);
}

function emssTry(label, fn) {
  try { return fn(); } catch (e) { E.lastErr = label + ': ' + (e && e.message ? e.message : e); err('emss ' + E.lastErr); return null; }
}

function audioConfigs() {
  var opusHead = new Uint8Array([0x4F, 0x70, 0x75, 0x73, 0x48, 0x65, 0x61, 0x64, 1, 2, 0x38, 0x01, 0x80, 0xBB, 0, 0, 0, 0, 0]);
  if (E.acodec === 'pcm') { // recommended by Samsung for low-latency mode (AAC frames are accepted but never played)
    return [{ mimeType: 'audio/webm; codecs="pcm"', extradata: new Uint8Array(0), decodingMode: 'hardware', sampleFormat: 'SampleFormatS16', channelLayout: 'ChannelLayoutStereo', samplesPerSecond: 48000 }];
  }
  if (E.acodec === 'opus') {
    return [
      { mimeType: 'audio/webm; codecs="opus"', extradata: opusHead, decodingMode: 'hardware', sampleFormat: 'SampleFormatPlanarF32', channelLayout: 'ChannelLayoutStereo', samplesPerSecond: 48000 },
      { mimeType: 'audio/mp4; codecs="opus"', extradata: opusHead, decodingMode: 'hardware', sampleFormat: 'SampleFormatS16', channelLayout: 'ChannelLayoutStereo', samplesPerSecond: 48000 }];
  }
  if (E.acodec === 'aac') {
    var asc = new Uint8Array([0x11, 0x90]);
    return [
      { mimeType: 'audio/mp4; codecs="mp4a.40.2"', extradata: asc, decodingMode: 'hardware', sampleFormat: 'SampleFormatPlanarF32', channelLayout: 'ChannelLayoutStereo', samplesPerSecond: 48000 },
      { mimeType: 'audio/mp4; codecs="mp4a.40.2"', extradata: asc, decodingMode: 'hardware', sampleFormat: 'SampleFormatS16', channelLayout: 'ChannelLayoutStereo', samplesPerSecond: 48000 }];
  }
  return [];
}

function emssSetup(codecLine, init) {
  E.lastErr = null; // a failure from an earlier stream must not be reported for this one
  var cs = codecLine.split(',');
  E.vcodec = cs[0].replace(/^hvc1/, 'hev1'); // parameter sets in-band (Annex-B), not in extradata
  E.acodec = !cs[1] ? '' : (cs[1].indexOf('pcm') >= 0 ? 'pcm' : cs[1].indexOf('opus') >= 0 ? 'opus' : 'aac');
  emssParseInit(init);
  E.state = 'creating';
  var src = emssTry('create', function () { return new tizentvwasm.ElementaryMediaStreamSource('latency-mode-ultra-low', 'rendering-mode-media-element'); });
  if (!src) return false;
  E.src = src;
  src.addEventListener('sourceclosed', function () { if (!E.vt) emssAddTracks(); });
  src.addEventListener('sourceopenpending', function () {
    E.state = 'openpending'; ev('emss openpending');
    var p = v.play(); if (p && p.catch) p.catch(function (e) { ev('emss playErr ' + e.message); });
  });
  src.addEventListener('sourceopen', function () { E.state = 'open'; ev('emss open'); });
  src.addEventListener('sourceended', function () { E.state = 'ended'; ev('emss ended'); });
  src.addEventListener('error', function (e) { E.lastErr = 'source error ' + (e && (e.message || e.error)); err('emss ' + E.lastErr); });
  v.src = URL.createObjectURL(src);
  ev('emss attach ' + E.w + 'x' + E.h + ' ' + E.vcodec + (E.acodec ? ' + ' + E.acodec : ''));
  return true;
}

function emssAddTracks() {
  var src = E.src;
  var vcfg = { mimeType: 'video/mp4; codecs="' + E.vcodec + '"', extradata: new Uint8Array(0), decodingMode: 'hardware',
    width: E.w, height: E.h, framerateNum: 60, framerateDen: 1 };
  E.vt = emssTry('addVideoTrack', function () { return src.addVideoTrack(vcfg); });
  if (!E.vt) return;
  E.vt.addEventListener('trackopen', function () { E.vOpen = true; E.waitKey = true; ev('emss video open'); });
  E.vt.addEventListener('trackclosed', function (e) { E.vOpen = false; ev('emss video closed ' + (e && e.reason)); });
  E.vt.addEventListener('appenderror', function (e) { E.vErr++; E.lastErr = 'video append ' + (e && (e.error || e.message)); });
  // audio: use the first config the TV accepts in low-latency mode
  var cfgs = audioConfigs();
  for (var i = 0; i < cfgs.length && !E.at; i++) {
    var c = cfgs[i];
    try { E.validate[c.mimeType + ' ' + c.sampleFormat] = String(tizentvwasm.ElementaryMediaTrack.ValidateAudioConfig(c)); } catch (x) {}
    E.at = emssTry('addAudioTrack ' + c.mimeType + ' ' + c.sampleFormat, function () { return src.addAudioTrack(c); });
    if (E.at) E.audio = c.mimeType + ' ' + c.sampleFormat;
  }
  if (E.at) {
    E.at.addEventListener('trackopen', function () { E.aOpen = true; ev('emss audio open'); });
    E.at.addEventListener('trackclosed', function () { E.aOpen = false; });
    E.at.addEventListener('appenderror', function (e) { E.aErr++; E.lastErr = 'audio append ' + (e && (e.error || e.message)); });
  } else if (cfgs.length) E.audio = 'rejected';
  var r = emssTry('open', function () { return src.open(); });
  if (r && r.then) r.then(function () { ev('emss open() ok'); }, function (e) { E.lastErr = 'open ' + e; err('emss open ' + e); });
}

function emssAppend(track, data, pts, dur, key, video) {
  var pkt = { pts: pts, dts: pts, duration: dur, isKeyFrame: key, isEncrypted: false };
  try { var sid = track.sessionId; if (typeof sid === 'number') pkt.sessionId = sid; } catch (e) {}
  if (video) { pkt.width = E.w; pkt.height = E.h; pkt.framerateNum = 60; pkt.framerateDen = 1; }
  try { track.appendPacket(pkt, data); return true; }
  catch (e) {
    if (video) E.vErr++; else E.aErr++;
    E.lastErr = (video ? 'video ' : 'audio ') + (e && e.message ? e.message : e);
    if ((video ? E.vErr : E.aErr) < 5) err('emss append ' + E.lastErr);
    return false;
  }
}

// video frame (moof+mdat, 4-byte NAL lengths) -> Annex-B; keyframes are prefixed with VPS/SPS/PPS
function emssVideo(d, key) {
  if (!E.vOpen) return;
  if (E.waitKey) { if (!key) return; E.waitKey = false; }
  var t = tfdtRaw(d), m = mdatPayload(d);
  if (t === null || !m) return;
  var parts = key ? [E.ps] : [], p = 0;
  while (p + 4 <= m.length) { var len = u32(m, p); parts.push(START, m.subarray(p + 4, p + 4 + len)); p += 4 + len; }
  var sec = t / 1e6;
  if (E.base === null) E.base = sec; // player clock starts at 0 on the first keyframe
  if (emssAppend(E.vt, concat(parts), sec - E.base, 1 / 60, key, true)) {
    E.vSent++;
    if (!played) { played = true; S.problem = ''; setIdle(false); setSwitching(false); ev('emss first frame'); }
  }
}

// Audio: video is unbuffered, but audio with gaps crackles, so audio gets a small buffer (A_TARGET frames)
// and is fed at an even pace from the TV's local clock, independent of Wi-Fi arrival jitter.
var AQ = [], aClock = null, aIdx = 0, A_TARGET = 3, A_MAX = 12, aShift = 0.06; // start slightly ahead: fewer adjustment steps (each one clicks)
E.aUnder = 0; E.aDrop = 0; E.aLead = null;
function aFrameSec() { return (E.acodec === 'opus' ? 960 : 1024) / 48000; }
function emssAudio(d) {
  if (!E.at || !E.aOpen || E.base === null) return;
  var t = tfdtRaw(d), m = mdatPayload(d);
  if (t === null || !m) return;
  var pts = t / 48000 - E.base;
  if (pts < 0) return;
  AQ.push({ pts: pts, data: new Uint8Array(m) });
  if (AQ.length > A_MAX) { AQ.splice(0, AQ.length - A_TARGET); E.aDrop++; aClock = null; } // too much backlog: catch up
}
setInterval(function () {
  if (!E.at || !E.aOpen) { AQ = []; aClock = null; return; }
  var now = performance.now(), fs = aFrameSec();
  if (aClock === null) { if (AQ.length < A_TARGET) return; aClock = now; aIdx = 0; } // prefill
  var due = Math.floor((now - aClock) / (fs * 1000)) + 1;
  while (aIdx < due) {
    if (!AQ.length) { aClock = null; E.aUnder++; break; } // underrun: wait for A_TARGET frames again
    var p = AQ.shift(), pts = p.pts + aShift;
    // audio that is late relative to the player clock may be skipped, so nudge it ahead
    var lead = pts - v.currentTime;
    E.aLead = E.aLead === null ? lead : E.aLead * 0.95 + lead * 0.05;
    if (E.aLead < 0.02 && aShift < 0.25) { aShift = Math.min(0.25, aShift + 0.03); E.aLead = null; }
    if (emssAppend(E.at, p.data, pts, fs, true, false)) E.aSent++;
    aIdx++;
  }
}, 5);

// stream change (different layout/resolution): close the old source without reloading the page
function emssTeardown() {
  var src = E.src;
  try { v.pause(); } catch (e) {}
  try { v.removeAttribute('src'); v.load(); } catch (e) {}
  try { if (src && src.destroy) src.destroy(); } catch (e) {}
  aShift = 0.06; AQ = []; aClock = null; E.src = null; E.vt = null; E.at = null; E.vOpen = false; E.aOpen = false; E.base = null; E.waitKey = true; E.state = 'none';
}

// sender media time currently on screen (for latency measurement)
function emssMediaTime() { return E.base === null ? -1 : v.currentTime + E.base; }
