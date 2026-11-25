"""本地验收用可控 Chat Completions 桩：不访问任何外部模型，不用于生产。"""

import json
import os
import re
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("PORT", "8095"))
TIMEOUT_SLEEP = float(os.environ.get("TIMEOUT_SLEEP", "12"))
LOCK = threading.Lock()
STATE = {"mode": "ok", "attempts": [], "errors": []}


def bigrams(text):
    chars = [character.lower() for character in text if character.isalnum()]
    if len(chars) == 1:
        return set(chars)
    return {chars[index - 1] + chars[index] for index in range(1, len(chars))}


def completion(question, evidence):
    query = bigrams(question)
    minimum = min(len(query), max(2, (len(query) + 4) // 5))
    best, selected, quote = 0, None, ""
    for chunk in evidence:
        for part in re.split(r"(?<=[。！？!?])|\r\n|\r|\n", chunk.get("text") or ""):
            phrase = part.strip()
            matches = len(bigrams(phrase) & query)
            if matches > best:
                best, selected, quote = matches, chunk, phrase
    if selected is None or best < minimum:
        return {"status": "INSUFFICIENT_EVIDENCE", "answer": "", "citations": []}
    answer = re.sub(r"\[\s*(\d+)\s*]", r"［\1］", quote)
    return {"status": "ANSWERED", "answer": answer,
            "citations": [{"chunkId": selected["chunkId"], "version": selected["version"], "quote": quote}]}


class Handler(BaseHTTPRequestHandler):
    timeout = 30

    def read_body(self):
        if "chunked" in self.headers.get("Transfer-Encoding", "").lower():
            chunks = []
            while True:
                line = self.rfile.readline().strip()
                if not line:
                    raise ValueError("invalid chunked request")
                size = int(line.split(b";")[0], 16)
                if size == 0:
                    while self.rfile.readline().strip():
                        pass
                    return b"".join(chunks)
                chunks.append(self.rfile.read(size))
                self.rfile.read(2)
        length = int(self.headers.get("Content-Length", "0"))
        return self.rfile.read(length) if 0 < length <= 512_000 else b""

    def do_GET(self):
        if self.path == "/health":
            self.respond(200, {"status": "UP"})
        elif self.path == "/state":
            with LOCK:
                self.respond(200, {"mode": STATE["mode"], "attempts": list(STATE["attempts"]),
                                   "errors": list(STATE["errors"])})
        else:
            self.respond(404, {"code": "NOT_FOUND"})

    def do_POST(self):
        body = self.read_body()
        if self.path == "/control":
            try:
                command = json.loads(body)
                with LOCK:
                    if command.get("reset"):
                        STATE["attempts"] = []
                        STATE["errors"] = []
                    if "mode" in command:
                        if command["mode"] not in ("ok", "timeout", "429", "500"):
                            raise ValueError("invalid mode")
                        STATE["mode"] = command["mode"]
                    self.respond(200, {"mode": STATE["mode"], "attempts": len(STATE["attempts"])})
            except (ValueError, json.JSONDecodeError) as invalid:
                self.respond(400, {"code": "BAD_REQUEST", "message": str(invalid)})
            return
        if self.path != "/v1/chat/completions":
            self.respond(404, {"code": "NOT_FOUND"})
            return
        try:
            data = json.loads(body)
            messages = data.get("messages", [])
            payload = json.loads(messages[-1]["content"])
            question = payload["question"]
            evidence = payload["evidence"]
        except (ValueError, KeyError, IndexError, TypeError, json.JSONDecodeError) as invalid:
            with LOCK:
                STATE["errors"].append(str(invalid) + " | " + body[:600].decode("utf-8", errors="replace"))
            self.respond(400, {"code": "BAD_REQUEST", "message": str(invalid)})
            return
        with LOCK:
            STATE["attempts"].append({"attemptId": self.headers.get("X-Attempt-Id"),
                                     "model": data.get("model"), "mode": STATE["mode"]})
            mode = STATE["mode"]
        if mode == "timeout":
            time.sleep(TIMEOUT_SLEEP)
        elif mode == "429":
            self.respond(429, {"error": {"message": "rate limited"}})
            return
        elif mode == "500":
            self.respond(500, {"error": {"message": "upstream failure"}})
            return
        content = json.dumps(completion(question, evidence), ensure_ascii=False)
        reply = {"choices": [{"message": {"role": "assistant", "content": content}}],
                 "usage": {"prompt_tokens": max(1, len(body) // 4),
                           "completion_tokens": max(1, len(content.encode("utf-8")) // 4)}}
        self.respond(200, reply)

    def respond(self, status, payload):
        raw = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def log_message(self, format, *args):
        pass


if __name__ == "__main__":
    with ThreadingHTTPServer(("0.0.0.0", PORT), Handler) as server:
        server.serve_forever()
