#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
裸 TCP 监听器：把客户端**实际发出去的 HTTP 升级请求**逐字节打印出来。

用途：诊断"同一份 hello 内容，为什么 Java-WebSocket 打过去被服务端 CLOSE，
而手写裸 WS 探针打过去却正常"。拿这里的输出和 tools/ws_probe.py 的握手请求做 diff，
差异项就是候选元凶（多余 header / 大小写 / 顺序）。

配合 adb reverse：
    adb reverse tcp:8000 tcp:8000
    手表 config.json → {"ws_url":"ws://127.0.0.1:8000/xiaozhi/v1/","token":"test-token"}
"""
import base64
import hashlib
import socket
import sys
import threading
import time

GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

HIGHLIGHT = ("user-agent", "sec-websocket-extensions", "origin", "device-id",
             "client-id", "authorization", "protocol-version", "cookie")


def handle(conn, addr, n):
    print("\n" + "=" * 78)
    print("### 连接 #%d  来自 %s" % (n, addr))
    print("=" * 78)
    try:
        conn.settimeout(8.0)
        buf = b""
        while b"\r\n\r\n" not in buf:
            d = conn.recv(4096)
            if not d:
                break
            buf += d
        head, _, rest = buf.partition(b"\r\n\r\n")

        raw = head.decode("utf-8", "replace")
        print("--- 原始请求（逐字节，转义不可见字符）---")
        print(repr(raw))
        print("\n--- 逐行 ---")
        lines = raw.split("\r\n")
        for i, l in enumerate(lines):
            mark = "  "
            lk = l.split(":", 1)[0].strip().lower()
            if lk in HIGHLIGHT:
                mark = "**"
            print("%s %2d | %s" % (mark, i, l))

        hdrs = {}
        for l in lines[1:]:
            if ":" in l:
                k, v = l.split(":", 1)
                hdrs[k.strip().lower()] = v.strip()

        key = hdrs.get("sec-websocket-key")
        if key:
            accept = base64.b64encode(hashlib.sha1((key + GUID).encode()).digest()).decode()
            conn.sendall(("HTTP/1.1 101 Switching Protocols\r\n"
                          "Upgrade: websocket\r\n"
                          "Connection: Upgrade\r\n"
                          "Sec-WebSocket-Accept: %s\r\n\r\n" % accept).encode())
            print("\n--- 已回 101，接下来 dump 客户端发来的帧（前 3 帧）---")
            # 简单帧解析（客户端帧带 mask）
            rbuf = rest
            for i in range(3):
                while len(rbuf) < 2:
                    d = conn.recv(65536)
                    if not d:
                        print("    (连接结束)")
                        return
                    rbuf += d
                b0, b1 = rbuf[0], rbuf[1]
                op = b0 & 0x0F
                masked = bool(b1 & 0x80)
                ln = b1 & 0x7F
                off = 2
                while len(rbuf) < off + (4 if masked else 0) + ln:
                    d = conn.recv(65536)
                    if not d:
                        print("    (连接结束)")
                        return
                    rbuf += d
                if ln == 126:
                    ln = int.from_bytes(rbuf[2:4], "big"); off = 4
                    while len(rbuf) < off + (4 if masked else 0) + ln:
                        rbuf += conn.recv(65536)
                m_off, d_off = off, off + (4 if masked else 0)
                payload = rbuf[d_off:d_off + ln]
                if masked:
                    m = rbuf[m_off:m_off + 4]
                    payload = bytes(b ^ m[j % 4] for j, b in enumerate(payload))
                rbuf = rbuf[d_off + ln:]
                print("    [帧 %d] opcode=%d len=%d payload=%s"
                      % (i + 1, op, ln, payload[:300]))
                if op == 0x1:
                    print("        ↑ 这就是客户端发的文本消息")
        else:
            print("\n!! 没有 Sec-WebSocket-Key（不是 WS 升级请求？）")
            conn.sendall(b"HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n")
    except Exception as e:
        print("!! 异常:", type(e).__name__, e)
    finally:
        try:
            conn.close()
        except Exception:
            pass


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8000
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", port))
    srv.listen(8)
    print("[%s] 裸请求 DUMP 监听中：0.0.0.0:%d" % (time.strftime("%H:%M:%S"), port), flush=True)
    n = 0
    while True:
        conn, addr = srv.accept()
        n += 1
        threading.Thread(target=handle, args=(conn, addr, n), daemon=True).start()


if __name__ == "__main__":
    main()
