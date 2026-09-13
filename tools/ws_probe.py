#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
裸 WebSocket 协议探针（不依赖任何第三方库）—— 小智 WS 通道的"取证工具"。

存在的理由：手表端出问题时，需要能在电脑上**把发出去的报文逐字 dump 出来**，
再把责任划到"是我们发错了"还是"服务端不接受"。原生 socket + TLS + 手搓 WS 帧，
看到的每一字节都是我们自己造的，没有库在中间做手脚。

用法:
  python ws_probe.py                     # 用默认参数连官方服务器
  python ws_probe.py --token xxx --url wss://host/xiaozhi/v1/
  python ws_probe.py --listen manual --hold 5
"""
import argparse
import base64
import json
import os
import socket
import ssl
import struct
import sys
import time
from urllib.parse import urlparse

DEFAULT_URL = "wss://api.tenclass.net/xiaozhi/v1/"
DEFAULT_DEVICE_ID = "02:20:**:**:**:F6"
DEFAULT_CLIENT_ID = "7876b0b4-0168-47f8-95d6-f7f164c3b983"
DEFAULT_TOKEN = "test-token"

T0 = time.time()


def ts():
    """时间戳：判断"握手即被拒"还是"空闲超时"全靠它"""
    return "+%5dms" % int((time.time() - T0) * 1000)


# ---------------------------------------------------------------- 帧
def send_frame(sock, opcode, payload):
    """客户端发出的帧必须加掩码（RFC 6455 硬规定）"""
    header = bytearray([0x80 | opcode])
    n = len(payload)
    if n < 126:
        header.append(0x80 | n)
    elif n < 65536:
        header.append(0x80 | 126)
        header += struct.pack(">H", n)
    else:
        header.append(0x80 | 127)
        header += struct.pack(">Q", n)
    mask = os.urandom(4)
    header += mask
    sock.sendall(bytes(header) + bytes(b ^ mask[i % 4] for i, b in enumerate(payload)))


class FrameReader:
    def __init__(self, sock):
        self.sock = sock
        self.buf = b""

    def _need(self, n):
        while len(self.buf) < n:
            d = self.sock.recv(65536)
            if not d:
                raise EOFError("连接被对端关闭")
            self.buf += d

    def read(self):
        """返回 (opcode, payload)；opcode 8 = CLOSE"""
        self._need(2)
        b0, b1 = self.buf[0], self.buf[1]
        opcode = b0 & 0x0F
        masked = bool(b1 & 0x80)
        ln = b1 & 0x7F
        off = 2
        if ln == 126:
            self._need(4)
            ln = struct.unpack(">H", self.buf[2:4])[0]
            off = 4
        elif ln == 127:
            self._need(10)
            ln = struct.unpack(">Q", self.buf[2:10])[0]
            off = 10
        self._need(off + ln + (4 if masked else 0))
        payload = self.buf[off:off + ln]
        if masked:
            m = self.buf[off + ln:off + ln + 4]
            payload = bytes(b ^ m[i % 4] for i, b in enumerate(payload))
        self.buf = self.buf[off + ln + (4 if masked else 0):]
        return opcode, payload


# ---------------------------------------------------------------- 握手
def handshake(url, token, device_id, client_id, protocol_version="1", timeout=15, no_auth=False):
    u = urlparse(url)
    host = u.hostname
    port = u.port or (443 if u.scheme == "wss" else 80)
    path = u.path or "/"
    if u.query:
        path += "?" + u.query

    raw = socket.create_connection((host, port), timeout=timeout)
    if u.scheme == "wss":
        ctx = ssl.create_default_context()
        sock = ctx.wrap_socket(raw, server_hostname=host)
        print("[TLS] %s / %s" % (sock.version(), sock.cipher()[0]))
    else:
        sock = raw

    key = base64.b64encode(os.urandom(16)).decode()
    lines = [
        "GET %s HTTP/1.1" % path,
        "Host: %s" % host,
        "Upgrade: websocket",
        "Connection: Upgrade",
        "Sec-WebSocket-Key: %s" % key,
        "Sec-WebSocket-Version: 13",
    ]
    if not no_auth:
        lines.append("Authorization: Bearer %s" % token)
    lines += [
        "Protocol-Version: %s" % protocol_version,
        "Device-Id: %s" % device_id,
        "Client-Id: %s" % client_id,
    ]
    req = "\r\n".join(lines) + "\r\n\r\n"
    print("[%s] [→ 握手请求]" % ts())
    for l in lines:
        print("   " + (l if "Authorization" not in l else "   Authorization: Bearer " + token))
    sock.sendall(req.encode())

    buf = b""
    while b"\r\n\r\n" not in buf:
        d = sock.recv(4096)
        if not d:
            break
        buf += d
    head, _, rest = buf.partition(b"\r\n\r\n")
    print("[%s] [← 握手响应]" % ts())
    for l in head.decode("utf-8", "replace").split("\r\n"):
        print("   " + l)

    r = FrameReader(sock)
    r.buf = rest
    return sock, r, head.decode("utf-8", "replace").split("\r\n")[0]


# ---------------------------------------------------------------- 主流程
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default=DEFAULT_URL)
    ap.add_argument("--token", default=DEFAULT_TOKEN)
    ap.add_argument("--device-id", default=DEFAULT_DEVICE_ID)
    ap.add_argument("--client-id", default=DEFAULT_CLIENT_ID)
    ap.add_argument("--listen", default="manual", help="manual / auto / realtime；none=只握手")
    ap.add_argument("--hold", type=float, default=6.0, help="handshake 后听多久（秒）")
    ap.add_argument("--send-audio", type=int, default=0, help="发多少个静音 Opus 帧（0=不发）")
    ap.add_argument("--stop-after", type=float, default=0.0,
                    help="发了 listen start 之后隔多少秒自动补一个 listen stop（0=不补）。"
                         "用来观察服务端对一轮完整「听→答」的真实反应，不需要音频数据。")
    ap.add_argument("--no-auth", action="store_true", help="完全不发 Authorization 头")
    ap.add_argument("--skip-hello", action="store_true", help="握手后什么都不发，看服务端会不会自己关")
    ap.add_argument("--hello-variant", default="full",
                    choices=["full", "nofeatures", "minimal", "v2"],
                    help="hello 报文的几种变体，用来做 A/B 隔离")
    a = ap.parse_args()

    sock, r, status = handshake(a.url, a.token, a.device_id, a.client_id, no_auth=a.no_auth)
    print("[%s] [握手结论] %s  %s" % (ts(), status, "← 101 才算通" if "101" in status else "← 没通！"))

    if a.skip_hello:
        print("[%s] [A/B] skip-hello：握手后不发任何东西，观察 %ss" % (ts(), a.hold))
    else:
        ap_params = {"format": "opus", "sample_rate": 16000, "channels": 1, "frame_duration": 60}
        if a.hello_variant == "full":
            hello = {"type": "hello", "version": 1, "transport": "websocket",
                     "audio_params": ap_params, "features": {"mcp": False}}
        elif a.hello_variant == "nofeatures":
            hello = {"type": "hello", "version": 1, "transport": "websocket",
                     "audio_params": ap_params}
        elif a.hello_variant == "minimal":
            hello = {"type": "hello", "version": 1}
        else:
            hello = {"type": "hello", "version": 2, "transport": "websocket",
                     "audio_params": ap_params}
        js = json.dumps(hello, ensure_ascii=False)
        print("[%s] [→ hello variant=%s] " % (ts(), a.hello_variant) + js)
        send_frame(sock, 0x1, js.encode())

    # 先收 hello
    deadline = time.time() + a.hold
    stop_at = None
    got_hello = False
    sock.settimeout(2.0)
    while time.time() < deadline:
        if stop_at is not None and time.time() >= stop_at:
            sj = json.dumps({"type": "listen", "state": "stop", "mode": a.listen})
            print("[%s] [→ listen stop] " % ts() + sj)
            send_frame(sock, 0x1, sj.encode())
            stop_at = None
            deadline = time.time() + a.hold
        try:
            op, payload = r.read()
        except (socket.timeout, ssl.SSLWantReadError):
            continue
        except EOFError as e:
            print("[%s] [← 连接结束] " % ts() + str(e))
            return
        if op == 0x1:
            txt = payload.decode("utf-8", "replace")
            print("[%s] [← 文本] " % ts() + txt[:500])
            try:
                if json.loads(txt).get("type") == "hello":
                    got_hello = True
                    if a.listen != "none":
                        lj = json.dumps({"type": "listen", "state": "start", "mode": a.listen})
                        print("[→ listen start mode=%s] " % a.listen + lj)
                        send_frame(sock, 0x1, lj.encode())
                        deadline = time.time() + a.hold
                        if a.stop_after > 0:
                            stop_at = time.time() + a.stop_after
                            print("[%s] [→ 计划 %.1fs 后补 listen stop]" % (ts(), a.stop_after))
            except Exception:
                pass
        elif op == 0x2:
            print("[%s] [← 二进制] %d 字节" % (ts(), len(payload)))
        elif op == 0x8:
            code = struct.unpack(">H", payload[:2])[0] if len(payload) >= 2 else -1
            print("[%s] [← CLOSE] code=%d reason=%s" % (ts(), code, payload[2:].decode("utf-8", "replace")))
            print("   code=1000 主动关闭 / 1006 异常断开 / 4xxx 服务器拒绝")
            return
        elif op == 0x9:
            print("[%s] [← PING] 回 PONG" % ts())
            send_frame(sock, 0xA, payload)
        else:
            print("[← opcode=%d] %d 字节" % (op, len(payload)))

    print("[结果] %s" % ("★ 服务器接受了连接并回了 hello（通道可用）" if got_hello
                        else "✗ 没等到 hello —— 看上面的 CLOSE code"))
    try:
        send_frame(sock, 0x8, struct.pack(">H", 1000))
        sock.close()
    except Exception:
        pass


if __name__ == "__main__":
    sys.exit(main())
