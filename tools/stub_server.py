#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
桩服务器（stub server）—— 零依赖的裸 WebSocket 服务端，用来**在真机上验通整条音频链路**。

它不接任何 AI，只做三件事：
  1. 正常完成 WS 握手 + hello（并把下行格式声明成 pcm，这样服务端不需要 Opus 编码器）
  2. 收客户端上行的 Opus 帧，统计帧数/字节/码率 → 证明"麦 → Opus 编码 → WS"这段是通的
  3. 收到 listen stop 后，回 stt/tts 事件并**下发一段 24kHz 的 440Hz 正弦 PCM** → 证明
     "WS → 解码 → 喇叭"这段是通的（能听到"嘟——"一声就是全链路通了）

配合 adb reverse 用，完全绕开 Wi-Fi / 内网 / 公网：
    adb reverse tcp:8000 tcp:8000
    手机上 config.json → {"ws_url":"ws://127.0.0.1:8000/xiaozhi/v1/","token":""}
"""
import argparse
import base64
import hashlib
import json
import math
import os
import socket
import struct
import sys
import threading
import time

GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"


def log(*a):
    print("[%s] %s" % (time.strftime("%H:%M:%S"), " ".join(str(x) for x in a)), flush=True)


# ---------------------------------------------------------------- WS 帧
def send_frame(sock, opcode, payload):
    h = bytearray([0x80 | opcode])
    n = len(payload)
    if n < 126:
        h.append(n)
    elif n < 65536:
        h.append(126); h += struct.pack(">H", n)
    else:
        h.append(127); h += struct.pack(">Q", n)
    sock.sendall(bytes(h) + payload)


def send_text(sock, obj):
    js = json.dumps(obj, ensure_ascii=False)
    log("→", js[:200])
    send_frame(sock, 0x1, js.encode())


class Reader:
    def __init__(self, sock, buf=b""):
        self.s = sock
        self.buf = buf

    def need(self, n):
        while len(self.buf) < n:
            d = self.s.recv(65536)
            if not d:
                raise EOFError("peer closed")
            self.buf += d

    def read(self):
        self.need(2)
        b0, b1 = self.buf[0], self.buf[1]
        op = b0 & 0x0F
        masked = bool(b1 & 0x80)
        ln = b1 & 0x7F
        off = 2
        if ln == 126:
            self.need(4); ln = struct.unpack(">H", self.buf[2:4])[0]; off = 4
        elif ln == 127:
            self.need(10); ln = struct.unpack(">Q", self.buf[2:10])[0]; off = 10
        # ⚠️ 帧布局是 [b0][b1][扩展长度][4 字节 mask][payload] —— mask 在 payload **之前**。
        # 第一版把 mask 写在 payload 之后，结果解出来全是乱码（"se}}G _GSG…"），
        # 而且因为位置错位，会连续误读下一帧。这个 bug 只有真发一次带 mask 的帧才会暴露。
        m_off = off
        d_off = off + (4 if masked else 0)
        total = d_off + ln
        self.need(total)
        payload = self.buf[d_off:d_off + ln]
        if masked:
            m = self.buf[m_off:m_off + 4]
            payload = bytes(b ^ m[i % 4] for i, b in enumerate(payload))
        self.buf = self.buf[total:]
        return op, payload


# ---------------------------------------------------------------- 会话
def handle(conn, addr, args):
    log("客户端连上:", addr)
    try:
        # --- 读 HTTP 升级请求 ---
        buf = b""
        while b"\r\n\r\n" not in buf:
            d = conn.recv(4096)
            if not d:
                return
            buf += d
        head, _, rest = buf.partition(b"\r\n\r\n")
        lines = head.decode("utf-8", "replace").split("\r\n")
        log("← 请求行:", lines[0])
        hdrs = {}
        for l in lines[1:]:
            if ":" in l:
                k, v = l.split(":", 1)
                hdrs[k.strip().lower()] = v.strip()
        for k in ("device-id", "client-id", "authorization", "protocol-version"):
            if k in hdrs:
                log("     header %s = %s" % (k, hdrs[k]))
        key = hdrs.get("sec-websocket-key")
        if not key:
            log("!! 没有 Sec-WebSocket-Key，回 400")
            conn.sendall(b"HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n")
            return
        accept = base64.b64encode(hashlib.sha1((key + GUID).encode()).digest()).decode()
        conn.sendall(("HTTP/1.1 101 Switching Protocols\r\n"
                      "Upgrade: websocket\r\n"
                      "Connection: Upgrade\r\n"
                      "Sec-WebSocket-Accept: %s\r\n\r\n" % accept).encode())
        log("WS 已升级（101）")

        r = Reader(conn, rest)
        state = {"frames": 0, "bytes": 0, "first": None, "last": None,
                 "listening": False, "hello_at": None}

        while True:
            op, payload = r.read()
            if op == 0x1:                                    # 文本
                txt = payload.decode("utf-8", "replace")
                log("← 文本:", txt[:300])
                try:
                    j = json.loads(txt)
                except Exception:
                    continue
                t = j.get("type")
                if t == "hello":
                    state["hello_at"] = time.time()
                    send_text(conn, {
                        "type": "hello", "transport": "websocket",
                        "session_id": "stub-%d" % int(time.time()),
                        "audio_params": {"format": args.downlink, "sample_rate": 24000,
                                         "channels": 1, "frame_duration": 60},
                    })
                elif t == "listen":
                    st = j.get("state")
                    log("   listen state=%s mode=%s" % (st, j.get("mode")))
                    if st == "start":
                        state["listening"] = True
                        state["frames"] = 0
                        state["bytes"] = 0
                    elif st == "stop":
                        state["listening"] = False
                        dur = 0.0
                        if state["first"] and state["last"]:
                            dur = state["last"] - state["first"] + 0.06
                        log("★ 收到 %d 帧 / %d 字节，时长约 %.2fs，平均 %.0f 字节/帧，约 %.1f kbps"
                            % (state["frames"], state["bytes"], dur,
                               (state["bytes"] / state["frames"]) if state["frames"] else 0,
                               (state["bytes"] * 8 / dur / 1000) if dur > 0.1 else 0))
                        threading.Thread(target=respond, args=(conn, state, args), daemon=True).start()
                elif t == "abort":
                    log("   客户端打断")
                continue

            if op == 0x2:                                    # 二进制（上行音频）
                n = len(payload)
                state["frames"] += 1
                state["bytes"] += n
                now = time.time()
                if state["first"] is None:
                    state["first"] = now
                state["last"] = now
                if state["frames"] <= 5 or state["frames"] % 30 == 0:
                    log("← 音频帧 #%d  %d 字节  时长 %.2fs"
                        % (state["frames"], n, now - state["first"]))
                continue

            if op == 0x9:
                send_frame(conn, 0xA, payload)
                continue
            if op == 0x8:
                log("客户端关闭连接")
                return
            if op == 0xA:
                continue
            log("未处理的 opcode=%d len=%d" % (op, len(payload)))
    except EOFError:
        log("连接断开")
    except Exception as e:
        log("!! 异常:", type(e).__name__, e)
    finally:
        try:
            conn.close()
        except Exception:
            pass


def respond(conn, state, args):
    """模拟一轮 AI 回复：stt → tts → 下发音频 → tts stop"""
    time.sleep(0.15)
    send_text(conn, {"type": "stt", "text": "（桩服务器：我收到了你的 %d 帧音频）" % state["frames"]})
    send_text(conn, {"type": "tts", "state": "start", "text": "这是一段测试音"})

    if args.downlink == "pcm":
        # 24kHz / mono / 16bit，60ms 一包 = 1440 样本
        rate, chunk_ms, secs = 24000, 60, 1.5
        spc = rate * chunk_ms // 1000
        total = int(secs * 1000 / chunk_ms)
        for k in range(total):
            pcm = bytearray()
            for i in range(spc):
                idx = k * spc + i
                v = int(12000 * math.sin(2 * math.pi * 440 * idx / rate))
                pcm += struct.pack("<h", v)
            send_frame(conn, 0x2, bytes(pcm))
            time.sleep(chunk_ms / 1000.0)
        log("已下发 %d 包 24k PCM（440Hz，%.1fs）" % (total, secs * 1.0))
    else:
        log("downlink=opus 模式下桩服务器不发音频（Python 没 Opus 编码器）")

    time.sleep(0.1)
    send_text(conn, {"type": "tts", "state": "stop"})
    log("本轮回复结束")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8000)
    ap.add_argument("--downlink", default="pcm", choices=["pcm", "opus"])
    a = ap.parse_args()
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", a.port))
    srv.listen(4)
    log("桩服务器已启动：ws://127.0.0.1:%d/xiaozhi/v1/  （下行格式=%s）" % (a.port, a.downlink))
    log("手表端要先做端口反向映射： adb reverse tcp:%d tcp:%d" % (a.port, a.port))
    while True:
        conn, addr = srv.accept()
        threading.Thread(target=handle, args=(conn, addr, a), daemon=True).start()


if __name__ == "__main__":
    main()
