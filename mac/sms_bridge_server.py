#!/usr/bin/env python3
"""SMS Bridge - Mac 接收端。

接收手机 App 发来的短信事件，弹出 macOS 通知，并把验证码复制到剪贴板。
只依赖 Python 标准库。

    python3 sms_bridge_server.py              # HTTP，仅用于局域网初步测试
    python3 sms_bridge_server.py --tls        # HTTPS（自签名证书 + 指纹锁定），真实验证码请用这个
"""

import argparse
import hashlib
import hmac
import json
import os
import re
import secrets
import ssl
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

CONFIG_DIR = Path.home() / ".config" / "sms-bridge"
MAX_BODY = 64 * 1024
MAX_SKEW_MS = 5 * 60 * 1000  # 允许手机与 Mac 的时钟偏差 / 请求有效期


def log(msg: str) -> None:
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def mask(code: str) -> str:
    """日志里绝不出现完整验证码。"""
    return code[:2] + "*" * (len(code) - 2)


def load_or_create_token() -> str:
    path = CONFIG_DIR / "token"
    if path.exists():
        return path.read_text().strip()
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    token = secrets.token_hex(10)
    path.write_text(token + "\n")
    path.chmod(0o600)
    return token


def ensure_cert() -> tuple[Path, Path, str]:
    """生成（或复用）自签名证书，返回 (cert, key, sha256 指纹)。"""
    cert, key = CONFIG_DIR / "cert.pem", CONFIG_DIR / "key.pem"
    if not (cert.exists() and key.exists()):
        CONFIG_DIR.mkdir(parents=True, exist_ok=True)
        subprocess.run(
            ["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "3650",
             "-subj", "/CN=sms-bridge", "-keyout", str(key), "-out", str(cert)],
            check=True, capture_output=True,
        )
        key.chmod(0o600)
    der = ssl.PEM_cert_to_DER_cert(cert.read_text())
    return cert, key, hashlib.sha256(der).hexdigest()


def lan_ips() -> list[str]:
    """本机所有局域网 IPv4（带广播地址的网卡），排除 VPN / 隧道这类点对点接口。"""
    try:
        out = subprocess.run(["ifconfig"], capture_output=True, text=True).stdout
    except OSError:
        return []
    return re.findall(r"inet (\d+\.\d+\.\d+\.\d+) netmask \S+ broadcast", out)


def copy_to_clipboard(text: str) -> None:
    subprocess.run(["pbcopy"], input=text.encode(), check=True)


def notify(title: str, message: str) -> None:
    # 文本通过 argv 传入，不拼进 AppleScript 源码，短信内容无法注入脚本
    script = (
        "on run argv\n"
        '  display notification (item 2 of argv) with title (item 1 of argv) sound name "Glass"\n'
        "end run"
    )
    subprocess.run(["osascript", "-e", script, title, message], check=False, capture_output=True)


class ReplayGuard:
    """记住有效期内见过的签名，拒绝重放。"""

    def __init__(self) -> None:
        self._seen: dict[str, int] = {}
        self._lock = threading.Lock()

    def check_and_add(self, signature: str, now_ms: int) -> bool:
        with self._lock:
            for sig in [s for s, t in self._seen.items() if now_ms - t > 2 * MAX_SKEW_MS]:
                del self._seen[sig]
            if signature in self._seen:
                return False
            self._seen[signature] = now_ms
            return True


class Server(ThreadingHTTPServer):
    daemon_threads = True

    def handle_error(self, request, client_address) -> None:
        # TLS 握手失败（例如手机端指纹不匹配）、连接中断等：一行日志即可，不打印堆栈
        exc = sys.exc_info()[1]
        log(f"连接异常 {client_address[0]}：{type(exc).__name__}: {exc}")


class Handler(BaseHTTPRequestHandler):
    server_version = "SMSBridge/0.1"
    timeout = 10
    token = ""
    copy = True
    replay = ReplayGuard()

    def log_message(self, *args) -> None:  # 关掉默认访问日志
        pass

    def reply(self, status: int, text: str) -> None:
        data = text.encode()
        self.send_response(status)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self) -> None:
        # 用手机浏览器打开 http://<Mac>:端口/ping 可以确认网络是通的
        self.reply(200, "ok\n") if self.path == "/ping" else self.reply(404, "not found\n")

    def do_POST(self) -> None:
        peer = self.client_address[0]
        if self.path != "/sms":
            return self.reply(404, "not found\n")
        try:
            length = int(self.headers.get("Content-Length", ""))
        except ValueError:
            return self.reply(411, "length required\n")
        if not 0 < length <= MAX_BODY:
            return self.reply(413, "too large\n")
        raw = self.rfile.read(length)

        # ---- 认证：HMAC-SHA256(token, "<时间戳>." + 正文) ----
        ts = self.headers.get("X-Timestamp", "")
        sig = self.headers.get("X-Signature", "").lower()
        expected = hmac.new(self.token.encode(), ts.encode() + b"." + raw, hashlib.sha256).hexdigest()
        now_ms = int(time.time() * 1000)
        if not hmac.compare_digest(sig.encode(), expected.encode()):
            log(f"拒绝 {peer}：签名错误（Token 不一致？）")
            return self.reply(401, "unauthorized\n")
        if not ts.isdigit() or abs(now_ms - int(ts)) > MAX_SKEW_MS:
            log(f"拒绝 {peer}：时间戳过期（手机与 Mac 时间相差过大？）")
            return self.reply(401, "unauthorized\n")
        if not self.replay.check_and_add(sig, now_ms):
            log(f"拒绝 {peer}：重复请求")
            return self.reply(401, "unauthorized\n")

        try:
            data = json.loads(raw)
            sender = str(data.get("sender") or "未知")
            body = str(data.get("body") or "")
            code = data.get("code")
            if code is not None and not (isinstance(code, str) and code.isdigit() and 4 <= len(code) <= 8):
                raise ValueError("code 格式不对")
        except (ValueError, AttributeError):
            return self.reply(400, "bad request\n")

        self.reply(200, "ok\n")

        tag = "[测试] " if data.get("test") else ""
        if code:
            if self.copy:
                copy_to_clipboard(code)
            notify(f"{tag}验证码 {code}", f"{'已复制到剪贴板 · ' if self.copy else ''}来自 {sender}")
            log(f"{tag}验证码 {mask(code)}（来自 {sender}）→ 已通知{'并复制' if self.copy else ''}")
        else:
            notify(f"{tag}短信 · {sender}", body[:120] or "（空）")
            log(f"{tag}短信（来自 {sender}，{len(body)} 字）未识别出验证码 → 已通知")


def main() -> None:
    ap = argparse.ArgumentParser(description="SMS Bridge Mac 接收端")
    ap.add_argument("--port", type=int, default=int(os.environ.get("SMS_BRIDGE_PORT", "8765")))
    ap.add_argument("--bind", default="0.0.0.0", help="监听地址，默认所有网卡")
    ap.add_argument("--token", default=os.environ.get("SMS_BRIDGE_TOKEN"),
                    help="认证 Token；不指定则自动生成并保存在 ~/.config/sms-bridge/token")
    ap.add_argument("--tls", action="store_true", help="启用 HTTPS（自动生成自签名证书）")
    ap.add_argument("--no-copy", action="store_true", help="只通知，不写剪贴板")
    args = ap.parse_args()

    Handler.token = args.token or load_or_create_token()
    Handler.copy = not args.no_copy
    if len(Handler.token) < 8:
        sys.exit("Token 太短，至少 8 个字符")

    httpd = Server((args.bind, args.port), Handler)
    scheme, fingerprint = "http", None
    if args.tls:
        cert, key, fingerprint = ensure_cert()
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.load_cert_chain(cert, key)
        # 握手放到各连接自己的线程里做，卡住的客户端不会堵住 accept
        httpd.socket = ctx.wrap_socket(httpd.socket, server_side=True, do_handshake_on_connect=False)
        scheme = "https"

    print("SMS Bridge 接收端已启动，在手机 App 里填写：")
    for ip in lan_ips() or ["<本机局域网 IP>"]:
        print(f"  Mac 地址 : {scheme}://{ip}:{args.port}")
    if len(lan_ips()) > 1:
        print("             （有多个网卡，选与手机同一网段的那个）")
    print(f"  Token    : {Handler.token}")
    if fingerprint:
        print(f"  证书指纹 : {fingerprint}")
    else:
        print("  ⚠️  当前为明文 HTTP，仅用于初步测试；真实验证码请加 --tls")
    print("按 Ctrl+C 停止。\n", flush=True)

    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\n已停止。")


if __name__ == "__main__":
    main()
