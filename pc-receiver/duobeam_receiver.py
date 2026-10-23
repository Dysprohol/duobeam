"""DuoBeam receiver for Windows: shows the handheld's stream in a window, with its sound.

Same network contract as the TV receivers:
  TCP 8201  the handheld sends [len u32][type u8][payload]: 0 codec line + '\\n' + fMP4 init, 1 keyframe, 2 frame,
            4 layout is changing, 5 audio (PCM in fMP4), 6 frame timestamps, 9 clock reply.
            We send 8 = clock ping (every second) and 10 = keyframe request on the same socket.
  HTTP 8202 /hello for "Find receiver" in the handheld app, /status for diagnostics.
Keys: Enter info box, Up/Down delay vs smoothness, Left/Right or L language, F11 or double-click fullscreen,
T on top, S fit/fill/1:1, P smooth/sharp, M mute, -/+ or the wheel volume, F12 screenshot, Esc leaves fullscreen or quits.
"""
import collections
import ctypes
import json
import locale
import os
import queue
import socket
import struct
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

os.environ.setdefault('PYGAME_HIDE_SUPPORT_PROMPT', '1')
import av  # noqa: E402
import pygame  # noqa: E402
from pygame._sdl2 import video  # noqa: E402

VERSION = '0.8.9'  # follows version.properties; the handheld build checks it
IN_PORT, HTTP_PORT = 8201, 8202
RATE = 48000  # audio from the handheld: S16LE stereo PCM

TEXTS = {
    'waiting': ('Waiting for the handheld...', 'Czekam na konsolę...'),
    'connected': ('Handheld connected - starting picture...', 'Konsola połączona - uruchamiam obraz...'),
    'gone': ('Handheld disconnected - waiting...', 'Konsola rozłączona - czekam...'),
    'stopped': ('Handheld stopped sending - waiting...', 'Konsola przestała nadawać - czekam...'),
    'switching': ('Switching layout...', 'Zmieniam układ...'),
    'addr': ("This receiver's address", 'Adres tego odbiornika'),
    'addr_hint': ('The DuoBeam app finds it with "Find receiver", or type it in.',
                  'Aplikacja DuoBeam znajdzie go przyciskiem "Znajdź odbiornik" albo wpisz go ręcznie.'),
    'steps': ('On the handheld: open DuoBeam and tap Start.', 'Na konsoli: otwórz DuoBeam i naciśnij Start.'),
    'first': ('First time only: tap "Set up" in DuoBeam and follow the steps.',
              'Tylko za pierwszym razem: naciśnij w DuoBeam "Konfiguruj" i postępuj według kroków.'),
    'firewall': ('Not found? Allow DuoBeam in Windows Firewall for private networks.',
                 'Konsola go nie widzi? Zezwól DuoBeam w Zaporze Windows dla sieci prywatnych.'),
    'keys': ('Move the mouse for controls  ·  Enter info  ·  ↑/↓ delay vs smoothness  ·  F11 fullscreen  ·  Esc exit',
             'Rusz myszą: przyciski  ·  Enter informacje  ·  ↑/↓ opóźnienie vs płynność  ·  F11 pełny ekran  ·  Esc wyjście'),
    'delay': ('delay', 'opóźnienie'),
    'receiver': ('receiver', 'odbiornik'),
    'mode': ('Mode', 'Tryb'),
    'mode0': ('lowest delay', 'najmniejsze opóźnienie'), 'mode1': ('balanced', 'zrównoważony'), 'mode2': ('smoothest', 'najpłynniejszy'),
    'b_mode0': ('Fastest', 'Najszybciej'), 'b_mode1': ('Balanced', 'Pośrodku'), 'b_mode2': ('Smoothest', 'Najpłynniej'),
    'dec_trouble': ('The decoder keeps failing with this stream. On the handheld pick another layout or lower quality. '
                    'Still stuck? Press Enter and send a screenshot of the info box to the DuoBeam developer.',
                    'Dekoder ciągle się wysypuje przy tym obrazie. Na konsoli wybierz inny układ albo niższą jakość. '
                    'Dalej nie działa? Naciśnij Enter i wyślij zrzut okienka z informacjami twórcy DuoBeam.'),
    'b_full': ('Fullscreen', 'Pełny ekran'), 'b_window': ('Window', 'Okno'),
    'b_top': ('On top', 'Na wierzchu'),
    'b_fit': ('Fit', 'Dopasuj'), 'b_fill': ('Fill', 'Wypełnij'), 'b_1to1': ('1:1', '1:1'),
    'b_smooth': ('Smooth', 'Gładko'), 'b_sharp': ('Sharp', 'Ostro'),
    'b_mute': ('Muted', 'Wyciszony'),
    'b_shot': ('Screenshot', 'Zrzut'), 'b_info': ('Info', 'Informacje'), 'b_lang': ('Polski', 'English'),
    't_shot': ('Screenshot saved: %s', 'Zrzut zapisany: %s'),
    't_top_on': ('Always on top', 'Zawsze na wierzchu'), 't_top_off': ('Not on top', 'Nie na wierzchu'),
    't_fit': ('Fit: whole picture', 'Dopasuj: cały obraz'), 't_fill': ('Fill: picture fills the window, edges cut',
                                                                         'Wypełnij: obraz na całe okno, brzegi ucięte'),
    't_1to1': ('1:1: one stream pixel per screen pixel', '1:1: piksel strumienia na piksel ekranu'),
    't_smooth': ('Smooth scaling', 'Gładkie skalowanie'), 't_sharp': ('Sharp scaling (pixel art)', 'Ostre skalowanie (pixel art)'),
    't_vol': ('Volume %d%%', 'Głośność %d%%'), 't_muted': ('Sound off', 'Dźwięk wyłączony'),
}
lang = 1 if (locale.getlocale()[0] or '').lower().startswith(('pl', 'polish')) else 0


def tr(k):
    return TEXTS[k][lang]


# ---------- fMP4 pieces the sender sends (same as the Android TV receiver's Mp4.java) ----------

START = b'\x00\x00\x00\x01'


def find(b, typ, start=0, end=None):
    i = b.find(typ, start, end if end is not None else len(b))
    return i


def parse_init(b):
    """hvcC -> (width, height, parameter sets in Annex-B), plus whether the stream carries PCM audio."""
    hc = find(b, b'hvcC')
    if hc < 0:
        raise ValueError('no hvcC')
    w, h = struct.unpack('>HH', b[hc - 58:hc - 54])
    p = hc + 4 + 22
    arrays = b[p]
    p += 1
    csd = bytearray()
    for _ in range(arrays):
        p += 1  # NAL type
        num = struct.unpack('>H', b[p:p + 2])[0]
        p += 2
        for _ in range(num):
            n = struct.unpack('>H', b[p:p + 2])[0]
            p += 2
            csd += START + b[p:p + n]
            p += n
    return w, h, bytes(csd)


def tfdt(b):
    i = find(b, b'tfdt', 0, 256)
    return -1 if i < 0 else struct.unpack('>Q', b[i + 8:i + 16])[0]


def mdat(b):
    i = find(b, b'mdat')
    if i < 4:
        return None
    size = struct.unpack('>I', b[i - 4:i])[0]
    return b[i + 4:i + 4 + min(size - 8, len(b) - (i + 4))]


def annexb(payload, prefix=b''):
    out = bytearray(prefix)
    p = 0
    while p + 4 <= len(payload):
        n = struct.unpack('>I', payload[p:p + 4])[0]
        if n <= 0 or p + 4 + n > len(payload):
            break
        out += START + payload[p + 4:p + 4 + n]
        p += 4 + n
    return bytes(out)


# ---------- receiver state shared by the threads ----------

class State:
    def __init__(self):
        self.lock = threading.Lock()
        self.source = None  # socket of the handheld that is streaming
        self.source_addr = None
        self.last_data = 0.0
        self.frames = 0
        self.connected = False
        self.switching_until = 0.0
        self.clock = collections.deque(maxlen=15)  # (rtt, offset = sender - ours) from clock pings
        self.clock_offset = None
        self.clock_rtt = None
        self.marks = {}  # media time us -> (capture, send, receive) in our clock, ms
        self.lat = collections.deque(maxlen=120)  # (total, handheld, wifi, pc)
        self.codec = None
        self.size = None
        self.decoder = ''
        self.decode_ms = collections.deque(maxlen=240)
        self.dropped = 0
        self.catchups = 0
        self.shown = 0
        self.problem = ''  # 'decoder' while the decoder failed 3+ times in the last minute, shown by the handheld app
        self.decoder_fails = collections.deque(maxlen=10)  # times of decoder failures
        self.ever_connected = False
        self.last_frame = 0.0  # when the last video frame arrived
        self.allow_hw = True  # false after the hardware decoder failed on this PC
        self.new_stream = False  # set by a new stream's init, cleared when its first picture is shown
        self.switch_signals = 0
        self.dropped_at = 0.0  # when the active handheld's connection ended
        self.sw_ms = collections.deque(maxlen=240)  # software decode + convert time per megapixel, real stream
        self.audio_underruns = 0
        self.audio_drops = 0
        self.audio_block_ms = 0.0
        self.audio_codec = '-'
        self.last_mark = None  # media time of the newest frame mark, us
        self.last_pts = None  # media time of the newest picture shown, us
        self.audio_queue_ms = 0.0


S = State()
video_q = queue.Queue()  # (pts_us, data, key) or ('init', w, h, csd) or ('reset',)
frames_q = collections.deque(maxlen=30)  # decoded pictures (rgb array, pts us, decoded at ms), scaled for the window
transit = collections.deque(maxlen=120)  # decoded-at minus stream time, ms, for the last 2 s of pictures
latest_lock = threading.Lock()
# window drawing area and picture settings, read by the decoder for scaling
view = {'w': 1280, 'h': 720, 'mode': 'fit', 'smooth': True, 'buffer': 0, 'max_tex': (16384, 16384)}
# delay vs smoothness, as on the TVs (Up/Down): decoder backlog in frames before jumping to a fresh keyframe,
# and how far behind the newest frame the picture plays, which absorbs uneven Wi-Fi delivery
# audio_ms: sound buffered before playing. 3 blocks (about 30 ms) crackled with 73 gaps in a minute of 20+ ms Wi-Fi
# jitter (2026-10-23); the Android TV receiver holds about 100 ms (its 64 ms queue plus a 50 ms track buffer). The
# picture buffer is added on top so sound stays as far behind as the picture in every mode.
MODES = [{'backlog': 3, 'buffer_ms': 0, 'audio_ms': 80}, {'backlog': 8, 'buffer_ms': 34, 'audio_ms': 114},
         {'backlog': 18, 'buffer_ms': 100, 'audio_ms': 180}]
BYTES_PER_MS = RATE * 4 // 1000


def now_ms():
    return time.time() * 1000.0


def local_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(('10.255.255.255', 1))  # no packet is sent; picks the interface of the default route
        ip = s.getsockname()[0]
        s.close()
        return ip
    except OSError:
        return '?'


# ---------- network ----------

def send_msg(sock, typ, payload=b''):
    try:
        sock.sendall(struct.pack('>IB', len(payload) + 1, typ) + payload)
    except OSError:
        pass


def request_keyframe():
    s = S.source
    if s is not None:
        send_msg(s, 10)


def exclusive(sock):
    # Windows lets a second program bind a port taken with SO_REUSEADDR; a second receiver then got half the connections
    if hasattr(socket, 'SO_EXCLUSIVEADDRUSE'):
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
    else:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)


def ingest_socket():
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    exclusive(srv)
    srv.bind(('0.0.0.0', IN_PORT))
    srv.listen(2)
    return srv


class HttpServer(ThreadingHTTPServer):
    allow_reuse_address = False
    daemon_threads = True

    def server_bind(self):
        exclusive(self.socket)
        super().server_bind()


def ingest_loop(srv):
    while True:
        c, addr = srv.accept()
        with S.lock:
            old = S.source
            # a second handheld while one streams is turned away (two senders kept replacing each other on the TVs);
            # the same handheld reconnecting replaces its old connection
            if old is not None and S.source_addr != addr[0] and time.time() - S.last_data < 2:
                c.close()
                continue
            if old is not None:
                # the same handheld reconnecting while it streams is a restart (layout change): show the switch
                # screen even if its "switching" message was still unread on the old connection when it is closed
                S.switching_until = max(S.switching_until, time.time() + 8)
                S.switch_signals += 1
                try:
                    old.close()
                except OSError:
                    pass
            S.source, S.source_addr, S.last_data = c, addr[0], time.time()
            S.clock.clear()
            S.clock_offset = S.clock_rtt = None
        threading.Thread(target=serve, args=(c,), daemon=True).start()


def read_exact(c, n):
    buf = bytearray()
    while len(buf) < n:
        chunk = c.recv(n - len(buf))
        if not chunk:
            raise ConnectionError('closed')
        buf += chunk
    return bytes(buf)


def serve(c):
    c.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    S.connected = S.ever_connected = True
    try:
        while True:
            n = struct.unpack('>I', read_exact(c, 4))[0]
            if n < 1 or n > 64 << 20:
                raise ValueError('bad message length %d' % n)
            typ = read_exact(c, 1)[0]
            p = read_exact(c, n - 1)
            S.last_data = time.time()
            on_message(typ, p)
    except (OSError, ValueError, ConnectionError):
        pass
    finally:
        try:
            c.close()
        except OSError:
            pass
        with S.lock:
            if S.source is c:
                S.source = None
                S.connected = False
                S.dropped_at = time.time()
                # only the active connection resets the decoder: a replaced one closing late used to land after the
                # new stream's init and stop its picture
                video_q.put(('reset',))


def ping_loop():
    while True:
        s = S.source
        if s is not None:
            send_msg(s, 8, struct.pack('>d', now_ms()))
        time.sleep(1)


def on_message(typ, p):
    if typ == 0:
        nl = p.find(b'\n')
        codec, init = p[:nl].decode('ascii', 'replace'), p[nl + 1:]
        w, h, csd = parse_init(init)
        S.codec, S.size = codec, (w, h)
        sound.start(codec)
        S.marks.clear()
        S.lat.clear()
        S.new_stream = True  # the layout-switch screen stays until this stream's first picture is on screen
        video_q.put(('init', w, h, csd))
        if AUDIO:
            AUDIO.clear()
    elif typ in (1, 2):
        S.frames += 1
        S.last_frame = time.time()
        t = tfdt(p)
        m = mdat(p)
        if t >= 0 and m is not None:
            video_q.put((t, m, typ == 1))
    elif typ == 4:
        S.switching_until = time.time() + 8
        S.switch_signals += 1
    elif typ == 5:
        m = mdat(p)
        if m and AUDIO:
            sound.frame(m)
    elif typ == 6 and len(p) == 24 and S.clock_offset is not None:
        mt, cap, snd = struct.unpack('>qqq', p)
        off = S.clock_offset
        S.marks[mt] = (cap - off, snd - off, now_ms())
        S.last_mark = mt
        if len(S.marks) > 64:
            S.marks.clear()
    elif typ == 9 and len(p) == 16:
        t0, handheld = struct.unpack('>dq', p)
        t1 = now_ms()
        S.clock.append((t1 - t0, handheld - (t0 + t1) / 2))
        best = min(S.clock)
        S.clock_rtt, S.clock_offset = best


class Sound:
    """Turns the handheld's audio track into PCM for the output. The codec line says what it carries: ",pcm" is raw
    S16LE 48 kHz stereo and plays as it is; ",mp4a.40.2" (AAC-LC) and ",opus" are decoded here. The bytes used to go
    to the output whatever the codec, and AAC played as PCM was a loud screech (2026-10-23)."""

    # decoder setup the sender writes into its init segment (Fmp4.java): AAC-LC AudioSpecificConfig for 48 kHz stereo,
    # and an OpusHead with 2 channels, pre-skip 312, 48 kHz
    AAC_CONFIG = bytes([0x11, 0x90])
    OPUS_HEAD = b'OpusHead' + struct.pack('<BBHIhB', 1, 2, 312, RATE, 0, 0)

    def __init__(self):
        self.kind, self.ctx, self.resampler = None, None, None

    def start(self, codec):
        parts = codec.split(',')[1:]
        self.kind = 'pcm' if 'pcm' in parts else 'opus' if 'opus' in parts else 'aac' if any(x.startswith('mp4a') for x in parts) else None
        self.ctx = self.resampler = None
        S.audio_codec = self.kind or '-'
        if self.kind in ('aac', 'opus'):
            try:
                self.ctx = av.CodecContext.create(self.kind, 'r')
                self.ctx.extradata = self.AAC_CONFIG if self.kind == 'aac' else self.OPUS_HEAD
                self.resampler = av.AudioResampler(format='s16', layout='stereo', rate=RATE)
            except Exception as e:
                print('no %s decoder: %s' % (self.kind, e))
                self.kind = None

    def frame(self, data):
        if self.kind == 'pcm':
            AUDIO.block(data)
        elif self.ctx is not None:
            try:
                for f in self.ctx.decode(av.Packet(data)):
                    for r in self.resampler.resample(f):
                        AUDIO.block(bytes(r.planes[0])[:r.samples * 4])
            except Exception as e:
                print('audio %s: %s' % (self.kind, e))


sound = Sound()


# ---------- HTTP: discovery and status ----------

SCREEN = ''  # size of the monitor the window is on, kept current by the window; the sender scales to fit it


def screen_size():
    return SCREEN


# ---------- what this PC can decode ----------

# Like the TVs, the receiver tells the handheld the largest picture it plays. It used to claim 8192x4320 on any PC.
# Hardware: a test picture of each size goes through the D3D11 decoder at start. Software: the time to decode and
# convert a 1080p test clip, and later the real stream, sets the largest picture area that leaves half of a 60 fps
# frame time free.
CAPS = {'largest': (1920, 1080), 'hw': None, 'sw_ms_per_mp': None, 'sw_pixels': 1920 * 1080}
caps_ready = threading.Event()
SW_BUDGET_MS = 8.0
MIN_PIXELS = 1280 * 720


def test_clip(w, h, frames, kbps=None):
    """HEVC packets of a w x h test picture from the bundled x265 encoder."""
    import numpy as np
    from fractions import Fraction
    enc = av.CodecContext.create('libx265', 'w')
    enc.width, enc.height, enc.pix_fmt = w, h, 'yuv420p'
    enc.time_base, enc.framerate = Fraction(1, 60), Fraction(60, 1)
    params = 'log-level=0:keyint=60:bframes=0:repeat-headers=1'
    if kbps:
        params += ':bitrate=%d:vbv-maxrate=%d:vbv-bufsize=%d' % (kbps, kbps, kbps // 30)
    enc.options = {'preset': 'ultrafast', 'tune': 'zerolatency', 'x265-params': params}
    enc.open()
    rng = np.random.default_rng(3)
    base = rng.integers(0, 255, (h // 8 + 1, w // 8 + 1, 3), dtype=np.uint8).repeat(8, 0).repeat(8, 1)[:h, :w]
    packets = []
    for i in range(frames):
        f = av.VideoFrame.from_ndarray(np.ascontiguousarray(np.roll(base, i * 12, axis=1)), format='rgb24').reformat(format='yuv420p')
        f.pts = i
        packets += [bytes(x) for x in enc.encode(f)]
    return packets + [bytes(x) for x in enc.encode(None)]


def hw_decodes(w, h):
    try:
        ctx, name = make_decoder(True)
        if name == 'software':
            return False
        out = []
        for pk in test_clip(w, h, 1):
            out += ctx.decode(av.Packet(pk))
        out += ctx.decode(None)
        return bool(out) and out[0].width == w and out[0].height == h
    except Exception:
        return False


def sw_pixels(ms_per_mp):
    lw, lh = CAPS['largest']
    return int(max(MIN_PIXELS, min(lw * lh, SW_BUDGET_MS / ms_per_mp * 1e6)))


def probe_caps(largest):
    CAPS['largest'] = largest
    try:
        # the stream is never larger than the biggest monitor, so that is the size to check first
        sizes = sorted({largest, (3840, 2160), (2560, 1440), (1920, 1080)}, key=lambda x: x[0] * x[1], reverse=True)
        for w, h in sizes:
            if w * h <= largest[0] * largest[1] or (w, h) == (1920, 1080):
                if hw_decodes(w, h):
                    CAPS['hw'] = (w, h)
                    break
        ctx, _ = make_decoder(False)
        times = []
        for pk in test_clip(1920, 1080, 30, kbps=12000):
            t = time.perf_counter()
            for f in ctx.decode(av.Packet(pk)):
                f.reformat(format='bgra')
            times.append((time.perf_counter() - t) * 1000)
        times.sort()
        ms = times[len(times) * 9 // 10] / 2.0736
        saved = settings.get('sw_ms_per_mp')  # measured on the real stream earlier: content is harder than the test clip
        CAPS['sw_ms_per_mp'] = max(ms, saved) if saved else ms
        CAPS['sw_pixels'] = sw_pixels(CAPS['sw_ms_per_mp'])
    except Exception as e:
        print('decoder check: %s' % e)
    finally:
        caps_ready.set()


def current_caps():
    """(maxVideo, maxPixels, kind) to tell the handheld now."""
    caps_ready.wait(3)  # the first /hello can come while the check still runs
    if CAPS['hw'] and S.allow_hw:
        w, h = CAPS['hw']
        return '%dx%d' % (w, h), w * h, 'hardware'
    lw, lh = CAPS['largest']
    return '%dx%d' % (lw, lh), CAPS['sw_pixels'], 'software'


class Http(BaseHTTPRequestHandler):
    def do_GET(self):
        busy = S.source is not None
        max_video, max_pixels, kind = current_caps()
        common = {'version': VERSION, 'maxVideo': max_video, 'maxPixels': max_pixels, 'screen': screen_size()}
        if self.path.startswith('/hello'):
            # "player":"android" = decodes HEVC itself and plays PCM, so the handheld app sends PCM sound
            body = dict(app='DuoBeam', role='receiver', platform='pc', ingestPort=IN_PORT, busy=busy, player='android', **common)
        elif self.path.startswith('/status'):
            body = dict(player='android', problem=S.problem, src=1 if busy else 0, frames=S.frames, **common)
            body.update(lat=lat_summary(), decoder=S.decoder, shown=S.shown, dropped=S.dropped, catchUps=S.catchups,
                        caps={'kind': kind, 'hardware': '%dx%d' % CAPS['hw'] if CAPS['hw'] else None,
                              'softwareMsPerMp': round(CAPS['sw_ms_per_mp'], 2) if CAPS['sw_ms_per_mp'] else None},
                        mode=view['buffer'], switchSignals=S.switch_signals, audioUnderruns=S.audio_underruns, audioDrops=S.audio_drops,
                        clock=None if S.clock_rtt is None or S.clock_offset is None else {'rtt': round(S.clock_rtt, 1), 'off': round(S.clock_offset)},
                        lastMark=S.last_mark, lastPts=S.last_pts, marksWaiting=len(S.marks),
                        codec=S.codec, audioCodec=S.audio_codec, audioBlockMs=round(S.audio_block_ms, 1), audioQueueMs=round(S.audio_queue_ms),
                        audioGapMaxMs=round(max(AUDIO.gaps)) if AUDIO and AUDIO.gaps else None)
        else:
            self.send_response(404)
            self.end_headers()
            return
        # compact like the TV receivers: the handheld app reads fields with patterns such as "player":"(\w+)", and with
        # json.dumps' spaces it found none, so it sent AAC instead of PCM and no size limits (2026-10-23)
        data = json.dumps(body, separators=(',', ':')).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        self.send_header('Access-Control-Allow-Origin', '*')
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *a):
        pass


def lat_summary():
    if not S.lat:
        return None
    rows = list(S.lat)

    def med(k):
        v = sorted(r[k] for r in rows)
        return round(v[len(v) // 2])

    return {'total': med(0), 'handheld': med(1), 'wifi': med(2), 'pc': med(3), 'n': len(rows)}


# ---------- video decoding ----------

def make_decoder(allow_hw=True):
    """Hardware decoding through D3D11 when this FFmpeg build has it, software otherwise."""
    for hw in (('d3d11va', None) if allow_hw else (None,)):
        try:
            if hw:
                from av.codec.hwaccel import HWAccel
                ctx = av.CodecContext.create('hevc', 'r', hwaccel=HWAccel(device_type=hw, allow_software_fallback=False))
            else:
                ctx = av.CodecContext.create('hevc', 'r')
                ctx.thread_type = 'SLICE'  # frame threads would hold frames back
        except Exception:
            continue
        try:
            ctx.options = {'flags': 'low_delay'}  # output each frame as soon as it is decoded
        except Exception:
            pass
        return ctx, (hw or 'software')
    raise RuntimeError('no HEVC decoder')


def decode_loop():
    ctx, csd, wait_key = None, b'', True
    while True:
        item = video_q.get()
        if item[0] == 'reset':
            ctx, wait_key = None, True
            with latest_lock:
                frames_q.clear()
                transit.clear()
            continue
        if item[0] == 'init':
            _, w, h, csd = item
            ctx, name = make_decoder(S.allow_hw)
            S.decoder = name
            wait_key = True
            with latest_lock:
                transit.clear()  # a new stream starts its times again
            continue
        if ctx is None:
            continue
        pts, data, key = item
        if wait_key and not key:
            S.dropped += 1
            continue
        # the decoder fell behind and frames queued up: that is pure delay, so jump to a fresh keyframe
        if video_q.qsize() > MODES[view['buffer']]['backlog']:
            while not video_q.empty():
                nxt = video_q.queue[0]
                if nxt[0] in ('init', 'reset'):
                    break
                video_q.get()
                S.dropped += 1
            S.catchups += 1
            wait_key = True
            request_keyframe()
            continue
        wait_key = False
        packet = av.Packet(annexb(data, csd if key else b''))
        packet.pts = pts
        t0 = time.perf_counter()
        try:
            for f in ctx.decode(packet):
                tw, th = frame_size(f.width, f.height)
                img = f.reformat(width=tw, height=th, format='bgra', interpolation='AREA').to_ndarray()
                ms = (time.perf_counter() - t0) * 1000
                S.decode_ms.append(ms)
                if S.decoder == 'software':
                    S.sw_ms.append(ms / (f.width * f.height / 1e6))
                    if len(S.sw_ms) == S.sw_ms.maxlen:  # every 4 s: what the real stream costs sets the next limit
                        v = sorted(S.sw_ms)
                        settings['sw_ms_per_mp'] = CAPS['sw_ms_per_mp'] = round(v[len(v) * 9 // 10], 3)
                        CAPS['sw_pixels'] = sw_pixels(CAPS['sw_ms_per_mp'])
                        S.sw_ms.clear()
                with latest_lock:
                    at = now_ms()
                    frames_q.append((img, f.pts, at))
                    transit.append(at - f.pts / 1000.0)
        except Exception as e:
            print('decoder %s: %s' % (S.decoder, e))
            if S.decoder != 'software':
                S.allow_hw = False  # hardware decoding failed here: stay on software for this run, and say so in the limits
            ctx, S.decoder = make_decoder(S.allow_hw)
            S.decoder_fails.append(time.time())
            wait_key = True
            request_keyframe()


def frame_size(w, h):
    """Size of the texture for a w x h picture. The graphics card scales it to the window, so it stays at the stream's
    size; only a picture with over 4 times the pixels the window shows is made smaller first (GPU scaling skips pixels
    then), and none goes over the largest texture the renderer takes."""
    bw, bh = view['w'], view['h']
    mw, mh = view['max_tex']
    f = 1.0
    if view['mode'] != '1:1':
        shown = max(bw / w, bh / h) if view['mode'] == 'fill' else min(bw / w, bh / h)
        if shown < 0.5:
            f = shown * 2
    f = min(f, mw / w, mh / h)
    return (w, h) if f >= 1 else (max(2, int(w * f) // 2 * 2), max(2, int(h * f) // 2 * 2))




# ---------- settings (remembered between runs) ----------

SETTINGS_FILE = os.path.join(os.environ.get('APPDATA') or os.path.expanduser('~'), 'DuoBeam', 'pc-receiver.json')
settings = {'mode': 'fit', 'smooth': True, 'buffer': 0, 'on_top': False, 'volume': 100, 'muted': False, 'lang': None, 'size': [1280, 720], 'pos': None,
            'sw_ms_per_mp': None}


def load_settings():
    try:
        with open(SETTINGS_FILE, encoding='utf-8') as f:
            saved = json.load(f)
        settings.update({k: v for k, v in saved.items() if k in settings})
    except (OSError, ValueError):
        pass


def save_settings():
    try:
        os.makedirs(os.path.dirname(SETTINGS_FILE), exist_ok=True)
        with open(SETTINGS_FILE, 'w', encoding='utf-8') as f:
            json.dump(settings, f, indent=1)
    except OSError:
        pass


def res(name):
    """A bundled file: inside the .exe, next to this script, or in the repository's image folders."""
    here = os.path.dirname(os.path.abspath(__file__))
    for d in (getattr(sys, '_MEIPASS', here), here, os.path.join(here, '..', 'docs', 'img'), os.path.join(here, '..', 'tizen-receiver', 'img')):
        p = os.path.join(d, name)
        if os.path.exists(p):
            return p
    return None


# ---------- audio ----------

def sdl_lib():
    name = 'SDL2.dll' if sys.platform == 'win32' else 'libSDL2-2.0.so.0'
    path = os.path.join(os.path.dirname(pygame.__file__), name)
    return ctypes.CDLL(path if os.path.exists(path) else name)  # the copy pygame already loaded


class SdlDisplayMode(ctypes.Structure):
    _fields_ = [('format', ctypes.c_uint32), ('w', ctypes.c_int), ('h', ctypes.c_int), ('refresh_rate', ctypes.c_int),
                ('driverdata', ctypes.c_void_p)]


class SdlAudioSpec(ctypes.Structure):
    _fields_ = [('freq', ctypes.c_int), ('format', ctypes.c_uint16), ('channels', ctypes.c_uint8), ('silence', ctypes.c_uint8),
                ('samples', ctypes.c_uint16), ('padding', ctypes.c_uint16), ('size', ctypes.c_uint32),
                ('callback', ctypes.c_void_p), ('userdata', ctypes.c_void_p)]


class Audio:
    """Plays the PCM blocks on the default output through SDL's own queue. pygame's AudioDevice needs a device name and
    cannot open "the default one", so the sound never played (2026-10-23); SDL_OpenAudioDevice(NULL) follows the
    device chosen in Windows. 3 blocks of buffer even out Wi-Fi jitter and more than 12 are cut back, like the TVs."""

    def __init__(self):
        import numpy
        self.np = numpy
        sdl = sdl_lib()
        sdl.SDL_OpenAudioDevice.restype = ctypes.c_uint32
        sdl.SDL_OpenAudioDevice.argtypes = [ctypes.c_char_p, ctypes.c_int, ctypes.POINTER(SdlAudioSpec), ctypes.c_void_p, ctypes.c_int]
        sdl.SDL_QueueAudio.argtypes = [ctypes.c_uint32, ctypes.c_char_p, ctypes.c_uint32]
        sdl.SDL_GetQueuedAudioSize.restype = ctypes.c_uint32
        sdl.SDL_GetQueuedAudioSize.argtypes = [ctypes.c_uint32]
        sdl.SDL_ClearQueuedAudio.argtypes = [ctypes.c_uint32]
        sdl.SDL_PauseAudioDevice.argtypes = [ctypes.c_uint32, ctypes.c_int]
        if sdl.SDL_InitSubSystem(0x10) != 0:  # SDL_INIT_AUDIO
            raise RuntimeError('no audio')
        spec = SdlAudioSpec(freq=RATE, format=0x8010, channels=2, samples=1024)  # AUDIO_S16LSB, 21 ms per device period
        self.dev = sdl.SDL_OpenAudioDevice(None, 0, ctypes.byref(spec), None, 0)
        if not self.dev:
            raise RuntimeError('no audio device')
        self.sdl, self.playing, self.lock = sdl, False, threading.Lock()
        self.retarget = False  # set when the mode changes
        self.last_at, self.gaps = 0.0, collections.deque(maxlen=200)  # ms between blocks, for the info box

    def block(self, pcm):
        gain = 0 if settings['muted'] else settings['volume'] / 100
        if gain < 1:
            pcm = (self.np.frombuffer(pcm, dtype='<i2') * gain).astype('<i2').tobytes()
        target = MODES[view['buffer']]['audio_ms'] * BYTES_PER_MS
        with self.lock:
            now = time.time()
            if self.last_at:
                self.gaps.append((now - self.last_at) * 1000)
            self.last_at = now
            S.audio_block_ms = len(pcm) / BYTES_PER_MS
            queued = self.sdl.SDL_GetQueuedAudioSize(self.dev)
            S.audio_queue_ms = queued / BYTES_PER_MS
            if self.playing and queued == 0:
                S.audio_underruns += 1  # ran dry: fill the buffer again before playing on
                self.playing = False
                self.sdl.SDL_PauseAudioDevice(self.dev, 1)
            elif queued > target + 25 * BYTES_PER_MS:
                # a burst after a Wi-Fi stall left more queued than the buffer needs, and the level stays there (213 ms
                # after start, 2026-10-23), which is pure delay: skip one block (one short jump) instead of clearing the
                # queue (a gap); a few such skips bring it back to the target
                S.audio_drops += 1
                return
            elif self.playing and self.retarget and queued + len(pcm) < target:
                # a mode with a longer buffer: one stretch of silence moves the sound back as far as the picture
                gap = (target - queued - len(pcm)) // 4 * 4
                self.sdl.SDL_QueueAudio(self.dev, bytes(gap), gap)
            self.retarget = False
            self.sdl.SDL_QueueAudio(self.dev, pcm, len(pcm))
            if not self.playing and queued + len(pcm) >= target:
                self.playing = True
                self.sdl.SDL_PauseAudioDevice(self.dev, 0)

    def clear(self):
        with self.lock:
            self.sdl.SDL_ClearQueuedAudio(self.dev)
            self.sdl.SDL_PauseAudioDevice(self.dev, 1)
            self.playing = False


AUDIO = None  # set at start when an output device opens


# ---------- window ----------

BG = (7, 4, 26)
PANEL = (22, 12, 50, 225)
ACCENT = (138, 63, 252)
PINK = (255, 140, 220)
TEXT = (240, 236, 255)
MUTED = (175, 165, 215)
BAR_HIDE_S = 3.0
FADE_S = 0.3  # old picture (or the waiting screen) fading into the new one
MOVE_S = 0.2  # picture moving to its new size after fit / fill / 1:1
OVERLAY_IN_S = 0.2
# Handhelds before 0.8.9 send no "switching" message on a layout change: the picture stays this long after the
# connection drops, so the same handheld coming back fades in instead of flashing the waiting screen
RECONNECT_GRACE_S = 1.5


def ease(t):
    return 1 - (1 - max(0.0, min(1.0, t))) ** 3


def load_font(files, size, fallback='segoeui,arial'):
    fonts = os.path.join(os.environ.get('WINDIR', r'C:\Windows'), 'Fonts')
    for name in files:
        p = os.path.join(fonts, name)
        if os.path.exists(p):
            try:
                return pygame.font.Font(p, size)
            except Exception:
                pass
    return pygame.font.SysFont(fallback, size)


def panel(size, color=PANEL, radius=14):
    s = pygame.Surface(size, pygame.SRCALPHA)
    pygame.draw.rect(s, color, s.get_rect(), border_radius=radius)
    return s


class SdlRect(ctypes.Structure):
    _fields_ = [('x', ctypes.c_int), ('y', ctypes.c_int), ('w', ctypes.c_int), ('h', ctypes.c_int)]


class SdlRendererInfo(ctypes.Structure):
    _fields_ = [('name', ctypes.c_char_p), ('flags', ctypes.c_uint32), ('num_texture_formats', ctypes.c_uint32),
                ('texture_formats', ctypes.c_uint32 * 16), ('max_texture_width', ctypes.c_int), ('max_texture_height', ctypes.c_int)]


class Sdl:
    """The SDL calls pygame 2.6 does not wrap: always on top, monitor bounds, DPI, refresh rate, renderer facts."""

    def __init__(self):
        lib = self.lib = sdl_lib()
        lib.SDL_GetWindowFromID.restype = ctypes.c_void_p
        lib.SDL_GetWindowFromID.argtypes = [ctypes.c_uint32]
        lib.SDL_SetWindowAlwaysOnTop.argtypes = [ctypes.c_void_p, ctypes.c_int]
        lib.SDL_GetDisplayDPI.argtypes = [ctypes.c_int] + [ctypes.POINTER(ctypes.c_float)] * 3
        lib.SDL_GetDisplayBounds.argtypes = [ctypes.c_int, ctypes.POINTER(SdlRect)]
        lib.SDL_GetDesktopDisplayMode.argtypes = [ctypes.c_int, ctypes.POINTER(SdlDisplayMode)]
        lib.SDL_GetRenderer.restype = ctypes.c_void_p
        lib.SDL_GetRenderer.argtypes = [ctypes.c_void_p]
        lib.SDL_GetRendererInfo.argtypes = [ctypes.c_void_p, ctypes.POINTER(SdlRendererInfo)]

    def renderer(self, window):
        """(name, uses the graphics card, largest texture) of the window's renderer."""
        info = SdlRendererInfo()
        try:
            r = self.lib.SDL_GetRenderer(self.lib.SDL_GetWindowFromID(window.id))
            if r and self.lib.SDL_GetRendererInfo(r, ctypes.byref(info)) == 0:
                return info.name.decode(), bool(info.flags & 0x2), (info.max_texture_width or 16384, info.max_texture_height or 16384)
        except Exception:
            pass
        return '?', True, (4096, 4096)

    def on_top(self, window, on):
        try:
            self.lib.SDL_SetWindowAlwaysOnTop(self.lib.SDL_GetWindowFromID(window.id), 1 if on else 0)
        except Exception as e:
            print('on top: %s' % e)

    def scale(self, index):
        """UI scale of a monitor: its DPI over the 96 DPI of 100 % scaling."""
        d, h, v = ctypes.c_float(), ctypes.c_float(), ctypes.c_float()
        try:
            if self.lib.SDL_GetDisplayDPI(index, ctypes.byref(d), ctypes.byref(h), ctypes.byref(v)) == 0 and h.value > 0:
                return max(1.0, min(3.0, round(h.value / 96 * 4) / 4))
        except Exception:
            pass
        return 1.0

    def refresh(self, index):
        m = SdlDisplayMode()
        try:
            return m.refresh_rate if self.lib.SDL_GetDesktopDisplayMode(index, ctypes.byref(m)) == 0 else None
        except Exception:
            return None

    def bounds(self):
        out = []
        for i in range(len(pygame.display.get_desktop_sizes())):
            r = SdlRect()
            if self.lib.SDL_GetDisplayBounds(i, ctypes.byref(r)) == 0:
                out.append(pygame.Rect(r.x, r.y, r.w, r.h))
        return out


class App:
    """The receiver window. Pictures go into a GPU texture at the stream's own size and the graphics card scales them
    to the window; the controls are small textures drawn on top. Before, every picture was scaled and copied by the
    CPU (2026-10-23)."""

    def __init__(self):
        self.sdl = Sdl()
        self.fullscreen = False
        self.hud = False
        self.last_move = time.time()
        self.toast, self.toast_until = '', 0.0
        self.frame_img = None  # keeps the decoded array alive while its surface is shown
        self.frame_surf = None
        self.frame_at = 0.0
        self.last_click = 0.0
        self.last_ui = 0.0
        self.hover = None
        self.buttons = []
        self.layers = {}  # name -> (key, texture): a layer is drawn again only when its key changes
        self.video_tex, self.video_key = None, None
        self.fade_tex, self.fade_rect, self.fade_at = None, None, 0.0
        self.rect_from, self.rect_at, self.last_rect = None, 0.0, None
        self.switch_seen, self.switch_out_at, self.was_switching = 0.0, 0.0, False
        self.was_streaming = False
        self.bg_scaled = None
        self.fps, self.fps_count, self.fps_at = 0, 0, time.time()
        self.display_at = 0.0
        self.display = -1
        self.k = 0.0  # UI scale of the window's monitor
        self.refresh = '?'
        self.ip = local_ip()
        icon = res('icon.png')
        self.icon = pygame.image.load(icon) if icon else None
        bg = res('bg.jpg')
        self.bg = pygame.image.load(bg) if bg else None

    def s(self, v):
        """A size in 100 %-scaling pixels, for this monitor."""
        return max(1, int(round(v * self.k)))

    def make_fonts(self):
        s = self.s
        self.f_title = load_font(['seguisb.ttf'], s(44))
        self.f_big = load_font(['seguisb.ttf'], s(38))
        self.f_text = load_font(['segoeui.ttf'], s(20))
        self.f_small = load_font(['segoeui.ttf'], s(15))
        self.f_label = load_font(['segoeui.ttf'], s(13))
        self.f_mono = load_font(['consola.ttf'], s(16), 'consolas,couriernew')
        self.f_icon = load_font(['segmdl2.ttf', 'SegoeIcons.ttf'], s(22))
        self.logo = pygame.transform.smoothscale(self.icon, (s(88), s(88))) if self.icon else None
        self.layers.clear()
        self.bg_scaled = None

    # --- window mode ---
    def open_window(self):
        pos = settings.get('pos')
        # a saved place on a monitor that is no longer there would open the window off screen
        if not (pos and any(r.collidepoint(pos[0] + 40, pos[1] + 40) for r in self.sdl.bounds())):
            pos = video.WINDOWPOS_CENTERED
        self.window = video.Window('DuoBeam receiver', size=tuple(settings['size']), position=tuple(pos) if pos != video.WINDOWPOS_CENTERED else pos,
                                   resizable=True)
        if self.icon:
            self.window.set_icon(self.icon)
        # accelerated=-1: the graphics card when there is one, SDL's software renderer otherwise (virtual machines, RDP)
        self.renderer = video.Renderer(self.window, accelerated=-1, vsync=False)
        self.renderer.draw_blend_mode = 1  # SDL_BLENDMODE_BLEND for the shaded overlays
        self.renderer_info = self.sdl.renderer(self.window)
        view['max_tex'] = self.renderer_info[2]  # older cards take textures of 4096 at most; the decoder keeps under it
        self.update_display()
        self.apply_on_top()

    def update_display(self):
        """Monitor-dependent things: the screen size the handheld scales to, UI scale and refresh rate. The window can
        be moved to another monitor at any time, so this runs every second."""
        global SCREEN
        self.display_at = time.time()
        try:
            idx = self.window.display_index
            w, h = pygame.display.get_desktop_sizes()[idx]
        except Exception:
            return
        SCREEN = '%dx%d' % (max(w, h), min(w, h))
        k = self.sdl.scale(idx)
        if idx != self.display or k != self.k:
            self.display, self.k = idx, k
            self.refresh = self.sdl.refresh(idx) or '?'
            self.make_fonts()

    def remember_window(self):
        if self.fullscreen:
            return
        settings['size'] = list(self.window.size)
        settings['pos'] = list(self.window.position)

    def toggle_fullscreen(self):
        self.remember_window()
        self.fullscreen = not self.fullscreen
        # desktop fullscreen fills the monitor the window is on and keeps Alt+Tab instant; leaving it restores the
        # window's own size and place
        if self.fullscreen:
            self.window.set_fullscreen(desktop=True)
        else:
            self.window.set_windowed()
        self.layers.clear()
        save_settings()

    def apply_on_top(self):
        self.sdl.on_top(self.window, settings['on_top'])

    # --- actions ---
    def say(self, text):
        self.toast, self.toast_until = text, time.time() + 2.2

    def toggle_top(self):
        settings['on_top'] = not settings['on_top']
        self.apply_on_top()
        save_settings()
        self.say(tr('t_top_on' if settings['on_top'] else 't_top_off'))

    def cycle_mode(self):
        order = ['fit', 'fill', '1:1']
        settings['mode'] = order[(order.index(settings['mode']) + 1) % 3] if settings['mode'] in order else 'fit'
        view['mode'] = settings['mode']
        self.rect_from, self.rect_at = self.last_rect, time.time()  # the picture moves to its new size
        save_settings()
        self.say(tr({'fit': 't_fit', 'fill': 't_fill', '1:1': 't_1to1'}[settings['mode']]))

    def toggle_smooth(self):
        settings['smooth'] = not settings['smooth']
        view['smooth'] = settings['smooth']
        self.video_key = None  # the texture keeps its scaling mode, so it is made again
        save_settings()
        self.say(tr('t_smooth' if settings['smooth'] else 't_sharp'))

    def toggle_mute(self):
        settings['muted'] = not settings['muted']
        save_settings()
        self.say(tr('t_muted') if settings['muted'] else tr('t_vol') % settings['volume'])

    def change_volume(self, step):
        settings['volume'] = max(0, min(100, settings['volume'] + step))
        settings['muted'] = False
        save_settings()
        self.say(tr('t_vol') % settings['volume'])

    def screenshot(self):
        if self.frame_surf is None:
            return
        folder = os.path.join(os.path.expanduser('~'), 'Pictures', 'DuoBeam')
        os.makedirs(folder, exist_ok=True)
        path = os.path.join(folder, time.strftime('DuoBeam %Y-%m-%d %H-%M-%S.png'))
        pygame.image.save(self.frame_surf, path)  # the picture as it came from the handheld, not as scaled on screen
        self.say(tr('t_shot') % os.path.basename(path))

    def toggle_lang(self):
        global lang
        lang = 1 - lang
        settings['lang'] = lang
        save_settings()

    def toggle_hud(self):
        self.hud = not self.hud

    def set_buffer(self, m):
        settings['buffer'] = view['buffer'] = m % len(MODES)
        if AUDIO:
            AUDIO.retarget = True
        save_settings()
        self.say('%s: %s' % (tr('mode'), tr('mode%d' % settings['buffer'])))

    def next_frame(self):
        """The decoded picture to show now, or None. Lowest delay shows the newest one; the other modes play the
        pictures in order on the stream's own timing, a fixed time behind, so uneven delivery does not stutter."""
        buf = MODES[view['buffer']]['buffer_ms']
        now = now_ms()
        with latest_lock:
            if not frames_q:
                return None
            if buf == 0:
                img, pts, _ = frames_q.pop()
                S.dropped += len(frames_q)
                frames_q.clear()
                return img, pts
            if not transit:
                return None
            # each picture plays at its stream time plus the quickest delivery of the last 2 s plus the mode's buffer:
            # deliveries up to that much slower still play on time, and one later than that plays at once without
            # moving the rest (resetting the schedule on late pictures kept raising the delay, to 138 ms in a
            # simulation with 25 ms Wi-Fi jitter, 2026-10-23)
            img, pts, _ = frames_q[0]
            due = pts / 1000.0 + min(transit) + buf
            if now < due and len(frames_q) <= MODES[view['buffer']]['backlog']:
                return None
            frames_q.popleft()
            return img, pts

    # --- layers ---
    def layer(self, name, key, build):
        """The texture of a UI layer; build() returns its Surface and runs only when key changes."""
        cached = self.layers.get(name)
        if cached is None or cached[0] != key:
            cached = (key, video.Texture.from_surface(self.renderer, build()))
            self.layers[name] = cached
        return cached[1]

    def button_defs(self):
        mode_label = {'fit': tr('b_fit'), 'fill': tr('b_fill'), '1:1': tr('b_1to1')}[settings['mode']]
        vol = tr('b_mute') if settings['muted'] else '%d%%' % settings['volume']
        return [
            ('full', '\uE73F' if self.fullscreen else '\uE740', tr('b_window') if self.fullscreen else tr('b_full'), self.fullscreen, self.toggle_fullscreen),
            ('top', '\uE840' if settings['on_top'] else '\uE718', tr('b_top'), settings['on_top'], self.toggle_top),
            ('buffer', '\uEC4A', tr('b_mode%d' % settings['buffer']), settings['buffer'] != 0, lambda: self.set_buffer(settings['buffer'] + 1)),
            ('mode', '\uE9A6', mode_label, settings['mode'] != 'fit', self.cycle_mode),
            ('smooth', '\uEB9F', tr('b_smooth') if settings['smooth'] else tr('b_sharp'), not settings['smooth'], self.toggle_smooth),
            ('vol', '\uE74F' if settings['muted'] else '\uE767', vol, settings['muted'], self.toggle_mute),
            ('shot', '\uE722', tr('b_shot'), False, self.screenshot),
            ('info', '\uE946', tr('b_info'), self.hud, self.toggle_hud),
            ('lang', '\uE774', tr('b_lang'), False, self.toggle_lang),
        ]

    def bar_visible(self, mx, my):
        # pygame keeps the last position after the pointer leaves the window, so a pointer that left over the bar kept
        # it on screen for good (2026-10-23): hovering counts only while the pointer is inside
        hovering = pygame.mouse.get_focused() and any(r.inflate(12, 12).collidepoint(mx, my) for r, *_ in self.buttons)
        return time.time() - self.last_move < BAR_HIDE_S or hovering

    def draw_bar(self, sw, sh):
        s = self.s
        defs = self.button_defs()
        gap, pad, bh = s(6), s(10), s(64)
        bw = max(s(56), min(s(86), (sw - s(40) - 2 * pad - (len(defs) - 1) * gap) // len(defs)))
        total = len(defs) * bw + (len(defs) - 1) * gap + 2 * pad
        x0, y0 = (sw - total) // 2, sh - bh - 2 * pad - s(18)
        self.buttons = [(pygame.Rect(x0 + pad + i * (bw + gap), y0 + pad, bw, bh), d[0], d[4]) for i, d in enumerate(defs)]

        def build():
            surf = panel((total, bh + 2 * pad), radius=s(18))
            for i, (key, glyph, label, active, _) in enumerate(defs):
                r = pygame.Rect(pad + i * (bw + gap), pad, bw, bh)
                color = ACCENT + (235,) if active else ((70, 50, 120, 210) if key == self.hover else (40, 28, 80, 160))
                surf.blit(panel(r.size, color, s(12)), r.topleft)
                m = self.f_icon.metrics(glyph)
                g = self.f_icon.render(glyph, True, TEXT) if m and m[0] is not None else self.f_text.render(label[:2], True, TEXT)
                surf.blit(g, (r.centerx - g.get_width() // 2, r.y + s(10)))
                t = self.f_label.render(label, True, TEXT if active or key == self.hover else MUTED)
                if t.get_width() > bw - s(6):
                    t = pygame.transform.smoothscale(t, (bw - s(6), t.get_height()))
                surf.blit(t, (r.centerx - t.get_width() // 2, r.bottom - t.get_height() - s(7)))
            return surf

        key = (tuple((d[1], d[2], d[3]) for d in defs), self.hover, total, self.k, lang)
        self.layer('bar', key, build).draw(dstrect=(x0, y0))

    def idle_state(self):
        if time.time() < S.switching_until:
            return tr('switching')
        if S.connected and self.frame_at and time.time() - S.last_frame > 3:
            return tr('stopped')
        if S.connected:
            return tr('connected')
        return tr('gone') if S.ever_connected else tr('waiting')

    def draw_idle(self, sw, sh):
        s = self.s
        state = self.idle_state()

        def build():
            surf = pygame.Surface((sw, sh))
            if self.bg is not None:
                bw, bh = self.bg.get_size()
                f = max(sw / bw, sh / bh)
                img = pygame.transform.smoothscale(self.bg, (int(bw * f) + 1, int(bh * f) + 1))
                surf.blit(img, ((sw - img.get_width()) // 2, (sh - img.get_height()) // 2))
                shade = pygame.Surface((sw, sh), pygame.SRCALPHA)
                shade.fill(BG + (120,))
                surf.blit(shade, (0, 0))
            else:
                surf.fill(BG)
            y = max(s(16), sh // 2 - s(250))
            if self.logo:
                surf.blit(self.logo, ((sw - self.logo.get_width()) // 2, y))
                y += self.logo.get_height() + s(12)
            for font, text, color, gap in ((self.f_title, 'DuoBeam', TEXT, s(4)), (self.f_text, state, (205, 195, 255), s(24))):
                r = font.render(text, True, color)
                surf.blit(r, ((sw - r.get_width()) // 2, y))
                y += r.get_height() + gap
            addr = self.f_big.render('%s:%d' % (self.ip, IN_PORT), True, PINK)
            label, hint = self.f_small.render(tr('addr'), True, MUTED), self.f_small.render(tr('addr_hint'), True, MUTED)
            cw = max(addr.get_width(), label.get_width(), hint.get_width()) + s(60)
            ch = s(28) + label.get_height() + addr.get_height() + hint.get_height() + s(8)
            surf.blit(panel((cw, ch), radius=s(18)), ((sw - cw) // 2, y))
            yy = y + s(14)
            for r in (label, addr, hint):
                surf.blit(r, ((sw - r.get_width()) // 2, yy))
                yy += r.get_height() + s(4)
            y += ch + s(22)
            for text, font, color in ((tr('steps'), self.f_text, TEXT), (tr('first'), self.f_small, MUTED), (tr('firewall'), self.f_small, MUTED),
                                      (tr('keys'), self.f_small, MUTED)):
                r = font.render(text, True, color)
                surf.blit(r, ((sw - r.get_width()) // 2, y))
                y += r.get_height() + s(8)
            v = self.f_label.render('%s v%s (PC)' % (tr('receiver'), VERSION), True, MUTED)
            surf.blit(v, (sw - v.get_width() - s(14), sh - v.get_height() - s(10)))
            return surf

        self.layer('idle', (state, self.ip, lang, sw, sh, self.k), build).draw(dstrect=(0, 0))

    def hud_lines(self):
        lat = lat_summary()
        d = sorted(S.decode_ms)
        lines = ['%s  ·  %d fps  ·  %s  ·  %s Hz' % ('%dx%d' % S.size if S.size else '-', self.fps, S.decoder or '-', self.refresh)]
        if lat:
            lines.append('%s %d ms   handheld %d · Wi-Fi %d · PC %d' % (tr('delay'), lat['total'], lat['handheld'], lat['wifi'], lat['pc']))
        if d:
            lines.append('decode + convert  p50 %.0f ms · p95 %.0f ms' % (d[len(d) // 2], d[min(len(d) - 1, len(d) * 95 // 100)]))
        lines.append('dropped %d · catch-ups %d · clock rtt %s' % (S.dropped, S.catchups, '-' if S.clock_rtt is None else '%d ms' % S.clock_rtt))
        if AUDIO:
            g = sorted(AUDIO.gaps) or [0]
            lines.append(S.audio_codec + ' · audio block %.0f ms · queue %.0f ms · gap p95 %.0f max %.0f ms · underruns %d · drops %d' % (
                S.audio_block_ms, S.audio_queue_ms, g[min(len(g) - 1, len(g) * 95 // 100)], g[-1], S.audio_underruns, S.audio_drops))
        else:
            lines.append('no sound output')
        mv, mp, kind = current_caps()
        lines.append('limits for the handheld: %s, %.1f MP (%s) · screen %s · waiting frames %d' % (mv, mp / 1e6, kind, SCREEN, len(frames_q)))
        lines.append('%s: %s' % (tr('mode'), tr('mode%d' % settings['buffer'])))
        if self.frame_surf is not None:
            lines.append('%dx%d · %s · %s · %s · %s' % (self.frame_surf.get_width(), self.frame_surf.get_height(),
                                                       {'fit': tr('b_fit'), 'fill': tr('b_fill'), '1:1': tr('b_1to1')}[settings['mode']],
                                                       tr('b_smooth') if settings['smooth'] else tr('b_sharp'), self.renderer_name(),
                                                       S.source_addr or self.ip))
        return lines

    def renderer_name(self):
        name, gpu, _ = self.renderer_info
        return name if gpu else name + ' (no graphics card)'

    def draw_hud(self):
        s = self.s

        def build():
            lines = self.hud_lines()
            lh = self.f_mono.get_linesize() + s(2)
            surf = panel((max(self.f_mono.size(t)[0] for t in lines) + s(28), len(lines) * lh + s(20)))
            for i, t in enumerate(lines):
                surf.blit(self.f_mono.render(t, True, TEXT if i else PINK), (s(14), s(10) + i * lh))
            return surf

        # twice a second, like the TVs' info box
        self.layer('hud', (int(time.time() * 2), lang, self.k), build).draw(dstrect=(s(16), s(16)))

    def start_fade(self, texture, rect):
        """The given picture fades out over what is drawn beneath it."""
        texture.blend_mode = 1
        self.fade_tex, self.fade_rect, self.fade_at = texture, rect, time.time()

    def draw_fade(self):
        t = (time.time() - self.fade_at) / FADE_S
        if self.fade_tex is None or t >= 1:
            self.fade_tex = None
            return
        self.fade_tex.alpha = int(255 * (1 - ease(t)))
        self.fade_tex.draw(dstrect=self.fade_rect)
        self.fade_tex.alpha = 255  # the waiting screen's texture is drawn again later

    def overlay_alpha(self, switching):
        """Strength of the layout-switch screen: fades in when the handheld announces a switch, out when the new
        layout's first picture is shown."""
        now = time.time()
        if switching:
            if not self.was_switching:
                self.switch_seen = now
            self.was_switching = True
            return ease((now - self.switch_seen) / OVERLAY_IN_S)
        if self.was_switching:
            self.was_switching = False
            self.switch_out_at = now
        return 1 - ease((now - self.switch_out_at) / FADE_S)

    def draw_switching(self, sw, sh, a):
        s = self.s
        self.renderer.draw_color = (20, 10, 48, int(235 * a))
        self.renderer.fill_rect(pygame.Rect(0, 0, sw, sh))
        size = s(60)
        ring = pygame.Surface((size, size), pygame.SRCALPHA)
        angle = (time.time() * 5) % 6.283
        pygame.draw.circle(ring, (60, 40, 110), (size // 2, size // 2), size // 2, s(5))
        pygame.draw.arc(ring, ACCENT, ring.get_rect(), angle, angle + 1.8, s(5))
        ring_tex = video.Texture.from_surface(self.renderer, ring)
        ring_tex.alpha = int(255 * a)
        ring_tex.draw(dstrect=((sw - size) // 2, sh // 2 - size))
        t = self.layer('switching', (lang, self.k), lambda: self.f_text.render(tr('switching'), True, TEXT))
        t.alpha = int(255 * a)
        t.draw(dstrect=((sw - t.width) // 2, sh // 2 + s(18)))

    def draw_problem(self, sw, sh):
        """A plain message when the automatic decoder fixes did not help, like the TVs."""
        s = self.s

        def build():
            words, lines, cur = tr('dec_trouble').split(), [], ''
            for w in words:
                if self.f_text.size(cur + ' ' + w)[0] > min(s(760), sw - s(80)) and cur:
                    lines.append(cur)
                    cur = w
                else:
                    cur = (cur + ' ' + w).strip()
            lines.append(cur)
            rs = [self.f_text.render(t, True, TEXT) for t in lines]
            w = max(r.get_width() for r in rs) + s(40)
            surf = panel((w, sum(r.get_height() for r in rs) + s(28)), (110, 30, 70, 235), s(14))
            y = s(14)
            for r in rs:
                surf.blit(r, ((w - r.get_width()) // 2, y))
                y += r.get_height()
            return surf

        t = self.layer('problem', (lang, sw, self.k), build)
        t.draw(dstrect=((sw - t.width) // 2, s(76)))

    def draw_toast(self, sw):
        if time.time() > self.toast_until:
            return
        s = self.s

        def build():
            t = self.f_text.render(self.toast, True, TEXT)
            h = t.get_height() + s(18)
            surf = panel((t.get_width() + s(36), h), radius=h // 2)
            surf.blit(t, (s(18), s(9)))
            return surf

        t = self.layer('toast', (self.toast, self.k), build)
        t.draw(dstrect=((sw - t.width) // 2, s(20)))

    def draw_video(self, sw, sh):
        fw, fh = self.frame_surf.get_size()
        key = (fw, fh, settings['smooth'])
        if self.video_key != key:
            # the scaling mode is fixed when a texture is made: linear for smooth, nearest for sharp (pixel art)
            os.environ['SDL_RENDER_SCALE_QUALITY'] = '1' if settings['smooth'] else '0'
            self.video_tex = video.Texture(self.renderer, (fw, fh), streaming=True)
            self.video_key = key
            self.video_dirty = True
        if self.video_dirty:
            self.video_tex.update(self.frame_surf)
            self.video_dirty = False
        nw, nh = S.size if S.size else (fw, fh)  # the stream's own size; the texture may be a smaller copy of it
        if view['mode'] == '1:1':
            w, h = nw, nh
        else:
            f = max(sw / nw, sh / nh) if view['mode'] == 'fill' else min(sw / nw, sh / nh)
            w, h = int(nw * f), int(nh * f)
        rect = pygame.Rect((sw - w) // 2, (sh - h) // 2, w, h)
        t = (time.time() - self.rect_at) / MOVE_S
        if self.rect_from is not None and t < 1:
            e, a = ease(t), self.rect_from
            rect = pygame.Rect(a.x + (rect.x - a.x) * e, a.y + (rect.y - a.y) * e, a.w + (rect.w - a.w) * e, a.h + (rect.h - a.h) * e)
        else:
            self.rect_from = None
        self.last_rect = rect
        self.video_tex.draw(dstrect=rect)

    def snapshot(self):
        """What the window shows, for tests."""
        return self.renderer.to_surface()

    # --- main loop ---
    def handle(self, e):
        """One input event; False ends the program."""
        if e.type in (pygame.QUIT, pygame.WINDOWCLOSE):
            return False
        if e.type == pygame.MOUSEMOTION:
            self.last_move = time.time()
        elif e.type == pygame.MOUSEWHEEL:
            self.last_move = time.time()
            self.change_volume(5 if e.y > 0 else -5)
        elif e.type == pygame.MOUSEBUTTONDOWN and e.button == 1:
            hit = next((b for b in self.buttons if b[0].collidepoint(e.pos)), None) if self.bar_visible(*e.pos) else None
            self.last_move = time.time()
            if hit:
                hit[2]()
            elif time.time() - self.last_click < 0.35:
                self.toggle_fullscreen()
            self.last_click = 0.0 if hit else time.time()
        elif e.type == pygame.KEYDOWN:
            keys = {pygame.K_RETURN: self.toggle_hud, pygame.K_KP_ENTER: self.toggle_hud, pygame.K_SPACE: self.toggle_hud,
                    pygame.K_F11: self.toggle_fullscreen, pygame.K_f: self.toggle_fullscreen, pygame.K_t: self.toggle_top,
                    pygame.K_s: self.cycle_mode, pygame.K_p: self.toggle_smooth, pygame.K_m: self.toggle_mute,
                    pygame.K_MINUS: lambda: self.change_volume(-10), pygame.K_KP_MINUS: lambda: self.change_volume(-10),
                    pygame.K_EQUALS: lambda: self.change_volume(10), pygame.K_PLUS: lambda: self.change_volume(10),
                    pygame.K_KP_PLUS: lambda: self.change_volume(10), pygame.K_F12: self.screenshot, pygame.K_l: self.toggle_lang,
                    pygame.K_LEFT: self.toggle_lang, pygame.K_RIGHT: self.toggle_lang,
                    pygame.K_UP: lambda: self.set_buffer(settings['buffer'] + 1), pygame.K_DOWN: lambda: self.set_buffer(settings['buffer'] - 1)}
            if e.key == pygame.K_ESCAPE:
                if not self.fullscreen:
                    return False
                self.toggle_fullscreen()
            elif e.key in keys:
                keys[e.key]()
        return True

    def run(self):
        clock = pygame.time.Clock()
        self.video_dirty = False
        while True:
            for e in pygame.event.get():
                if not self.handle(e):
                    return
            if time.time() - self.display_at > 1:
                self.update_display()
            mx, my = pygame.mouse.get_pos()
            sw, sh = self.window.size
            view['w'], view['h'] = sw, sh
            item = self.next_frame()
            new_frame = item is not None
            if new_frame:
                img, pts = item
                if S.new_stream:  # first picture of a new stream
                    S.new_stream = False
                    restart = time.time() < S.switching_until or time.time() - S.dropped_at < RECONNECT_GRACE_S
                    if restart and self.frame_surf is not None and self.last_rect is not None:
                        # layout switch: the old layout fades into the new one (copied to the GPU before it is replaced)
                        self.start_fade(video.Texture.from_surface(self.renderer, self.frame_surf), self.last_rect)
                    S.switching_until = 0
                self.frame_img = img if img.flags['C_CONTIGUOUS'] else img.copy()
                self.frame_surf = pygame.image.frombuffer(self.frame_img.data, (img.shape[1], img.shape[0]), 'BGRA')
                self.frame_at = now_ms()
                self.video_dirty = True
            switching = time.time() < S.switching_until
            grace = time.time() - S.dropped_at < RECONNECT_GRACE_S
            # the handheld reconnects during a layout switch; the old picture stays under the switch screen meanwhile
            held = switching or grace
            streaming = (S.connected or held) and self.frame_surf is not None and (held or now_ms() - self.frame_at < 3000)
            fails = [t for t in S.decoder_fails if time.time() - t < 60]
            S.problem = 'decoder' if len(fails) >= 3 else ''  # stays until a minute passes without a failure
            overlay = self.overlay_alpha(switching)
            if streaming and not self.was_streaming and 'idle' in self.layers:
                self.start_fade(self.layers['idle'][1], pygame.Rect(0, 0, sw, sh))  # waiting screen fades into the picture
            elif self.was_streaming and not streaming and self.frame_surf is not None and self.last_rect is not None:
                self.start_fade(video.Texture.from_surface(self.renderer, self.frame_surf), self.last_rect)  # and back
            self.was_streaming = streaming
            animating = self.fade_tex is not None or self.rect_from is not None or overlay > 0
            bar = self.bar_visible(mx, my)
            if not bar:
                self.buttons = []
            hover = next((b[1] for b in self.buttons if b[0].collidepoint(mx, my)), None) if pygame.mouse.get_focused() else None
            busy_ui = bar or self.hud or switching or time.time() < self.toast_until or not streaming
            ui_due = time.time() - self.last_ui > (1 / 60 if animating else 1 / 30 if busy_ui else 0.5)
            if new_frame or ui_due or hover != self.hover or (sw, sh) != getattr(self, 'drawn_size', None):
                self.hover = hover
                self.last_ui = time.time()
                self.drawn_size = (sw, sh)
                self.renderer.draw_color = (0, 0, 0, 255)
                self.renderer.clear()
                if streaming:
                    self.draw_video(sw, sh)
                else:
                    self.draw_idle(sw, sh)
                self.draw_fade()
                if overlay > 0:
                    self.draw_switching(sw, sh, overlay)
                if S.problem and not self.hud:
                    self.draw_problem(sw, sh)
                if self.hud:
                    self.draw_hud()
                if bar:
                    self.draw_bar(sw, sh)
                self.draw_toast(sw)
                self.renderer.present()
                if new_frame:
                    shown = now_ms()
                    S.shown += 1
                    self.fps_count += 1
                    S.last_pts = pts
                    m = S.marks.pop(pts, None)
                    if m is not None:
                        S.lat.append((shown - m[0], m[1] - m[0], m[2] - m[1], shown - m[2]))
            pygame.mouse.set_visible(not self.fullscreen or bar)
            if time.time() - self.fps_at >= 1:
                self.fps, self.fps_count, self.fps_at = self.fps_count, 0, time.time()
            title = 'DuoBeam receiver' + (' - ' + S.source_addr if streaming and S.source_addr else '')
            if self.window.title != title:
                self.window.title = title
            clock.tick(240)  # wakes often enough to show each new frame right away

    def close(self):
        self.remember_window()
        save_settings()


def main():
    global lang, AUDIO
    load_settings()
    if settings['lang'] in (0, 1):
        lang = settings['lang']
    view['mode'], view['smooth'] = settings['mode'], settings['smooth']
    view['buffer'] = settings['buffer'] if settings['buffer'] in range(len(MODES)) else 0
    # ports first: a second copy of the receiver stops here instead of opening a dead window
    srv = ingest_socket()
    http = HttpServer(('0.0.0.0', HTTP_PORT), Http)
    # real pixels on scaled monitors (125-200 %): without this Windows reported 1280x720 for a 1920x1080 laptop
    # screen at 150 %, the handheld scaled the picture down to that and Windows blew the window up again, blurred
    os.environ.setdefault('SDL_WINDOWS_DPI_AWARENESS', 'permonitorv2')
    pygame.init()
    sizes = pygame.display.get_desktop_sizes() or [(1920, 1080)]
    largest = max(sizes, key=lambda x: x[0] * x[1])
    threading.Thread(target=probe_caps, args=((max(largest), min(largest)),), daemon=True).start()
    app = App()
    app.open_window()
    threading.Thread(target=ingest_loop, args=(srv,), daemon=True).start()
    threading.Thread(target=ping_loop, daemon=True).start()
    threading.Thread(target=decode_loop, daemon=True).start()
    threading.Thread(target=http.serve_forever, daemon=True).start()
    try:
        AUDIO = Audio()
    except Exception as e:
        print('no sound: %s' % e)
    try:
        app.run()
    finally:
        app.close()


if __name__ == '__main__':
    try:
        main()
    except OSError as e:
        if getattr(e, 'winerror', None) == 10048 or 'address already in use' in str(e).lower():
            msg = 'Port %d or %d is in use: is another DuoBeam receiver already running?' % (IN_PORT, HTTP_PORT)
            if sys.platform == 'win32':
                ctypes.windll.user32.MessageBoxW(None, msg, 'DuoBeam receiver', 0x30)
            else:
                print(msg)
        else:
            raise
    finally:
        pygame.quit()
