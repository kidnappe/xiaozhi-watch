#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
官方 OTA 探针（零依赖）：看某台设备在官方服务端的**绑定状态**。

为什么要它：官方云对"未绑定"和"已绑定"的设备，OTA 响应的差别是明确的 ——
  · 未绑定：响应里有 `activation` 字段（6 位激活码 + challenge），`websocket.token` 是占位符；
  · 已绑定：`activation` 消失，`websocket.token` 变成真正可用的 token。
所以拿这台设备的 Device-Id 打一次 OTA，就能判断"服务器到底认不认它"，
而不用靠猜 WS 为什么被关。

⚠️ Device-Id 必须**小写**（官方按字符串精确匹配、大小写敏感）。
   大写会让 WS 侧在收到 hello 后立刻 CLOSE（code=1000），但不是 OTA 的问题 —— OTA 会归一化。

用法：
  python tools/ota_probe.py --device-id 02:20:**:**:**:f6
  python tools/ota_probe.py --device-id 02:20:**:**:**:f6 --activate
"""
import argparse
import json
import os
import ssl
import sys
import urllib.request
import uuid as uuidlib

DEFAULT_OTA = "https://api.tenclass.net/xiaozhi/ota/"


def post(url, body, headers, timeout=20):
    data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=data, method="POST")
    for k, v in headers.items():
        req.add_header(k, v)
    ctx = ssl.create_default_context()
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=ctx) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ota-url", default=DEFAULT_OTA)
    ap.add_argument("--device-id", required=True, help="MAC 形式，必须小写")
    ap.add_argument("--client-id", default=None, help="默认现场生成一个 UUID")
    ap.add_argument("--activate", action="store_true",
                    help="额外打一次 /ota/activate（用响应里的 challenge 长轮询）")
    ap.add_argument("--code", default=None, help="绑定时用的 6 位激活码（配合 --activate）")
    a = ap.parse_args()

    dev = a.device_id
    if dev != dev.lower():
        print("!! Device-Id 含大写字母 —— 官方服务端大小写敏感，WS 侧会立刻被 CLOSE。"
              "这里先给你转成小写再打：%s" % dev.lower())
        dev = dev.lower()

    cid = a.client_id or str(uuidlib.uuid4())
    uid = str(uuidlib.uuid4())

    body = {
        "version": 2,
        "uuid": uid,
        "application": {"name": "xiaozhi-watch", "version": "0.1", "id": uid},
        "board": {"type": "oppo-watch-2", "name": "OPPO Watch 2", "mac": dev},
        "chip": {"model": "android-armv7", "cores": 4},
    }
    headers = {
        "Content-Type": "application/json",
        "Activation-Version": "1",     # 无 efuse、无序列号 → 必须 1
        "User-Agent": "xiaozhi-watch/0.1 (Android 8.1; OWW202)",
        "Device-Id": dev,
        "Client-Id": cid,
    }

    print("→ POST %s" % a.ota_url)
    print("  Device-Id = %s" % dev)
    print("  Client-Id = %s" % cid)
    code, text = post(a.ota_url, body, headers)
    print("← HTTP %d" % code)
    try:
        j = json.loads(text)
        print(json.dumps(j, ensure_ascii=False, indent=2))
        act = j.get("activation")
        ws = j.get("websocket") or {}
        tok = ws.get("token")
        print("\n--- 判读 ---")
        if act:
            print("· 有 activation 字段 → **未绑定**。激活码 = %s" % act.get("code"))
            print("  去 xiaozhi.me 控制台「添加设备」输入这个码。")
        else:
            print("· 没有 activation 字段 → 已绑定/已激活。")
        print("· websocket.token = %r" % tok)
        if tok and tok != "test-token":
            print("  token 是真实值 → 这台设备在服务端是**可用的**。")
        else:
            print("  token 仍是占位符 test-token。")
        chal = (act or {}).get("challenge")
        if a.activate and chal:
            print("\n→ POST %s/activate（challenge 长轮询，最多等 30s）" %
                  a.ota_url.rstrip("/").rsplit("/", 1)[0] + "/ota")
            ab = {"challenge": chal}
            ah = dict(headers)
            if a.code:
                ab["code"] = a.code
            code2, text2 = post(a.ota_url.rstrip("/") + "/activate", ab, ah, timeout=35)
            print("← HTTP %d %s" % (code2, text2[:400]))
    except Exception as e:
        print("（响应不是 JSON：%s）" % e)
        print(text[:1000])


if __name__ == "__main__":
    sys.exit(main())
