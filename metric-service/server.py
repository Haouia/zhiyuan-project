import hmac
import json
import os
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def bigrams(text):
    chars = [character.lower() for character in text if character.isalnum()]
    if len(chars) == 1:
        return set(chars)
    return {chars[index - 1] + chars[index] for index in range(1, len(chars))}


def sentences(text):
    return [part.strip() for part in re.split(r"(?<=[。！？!?])|\r\n|\r|\n", text) if part.strip()]


def supported(sentence, contexts):
    terms = bigrams(sentence)
    if not terms:
        return False
    return any(len(terms & bigrams(context)) / len(terms) >= 0.5 for context in contexts)


def evaluate(sample):
    # shortcut: deterministic local metrics with RAGAS-compatible semantics; production runs the approved RAGAS model.
    response = sample["response"]
    contexts = sample["retrieved_contexts"]
    claims = sentences(response)
    faithfulness = (sum(supported(claim, contexts) for claim in claims) / len(claims)) if claims else None
    reference = sample.get("reference")
    recall = None
    if isinstance(reference, str) and reference.strip():
        facts = sentences(reference)
        recall = sum(supported(fact, contexts) for fact in facts) / len(facts) if facts else None
    return {"faithfulness": faithfulness, "context_recall": recall}


class MetricHandler(BaseHTTPRequestHandler):
    timeout = 5

    def authorized(self):
        authorization = self.headers.get("Authorization", "").encode("latin-1")
        return hmac.compare_digest(authorization, self.server.expected_authorization)

    def do_GET(self):
        if self.path != "/internal/v1/health":
            self.respond(404, {"code": "NOT_FOUND"})
            return
        if not self.authorized():
            self.respond(401, {"code": "UNAUTHORIZED"})
            return
        self.respond(200, {"service": "metric-service", "status": "UP"})

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
        if not 0 < length <= 256_000:
            raise ValueError("invalid body length")
        return self.rfile.read(length)

    def do_POST(self):
        if self.path != "/internal/v1/evaluations":
            self.respond(404, {"code": "NOT_FOUND"})
            return
        if not self.authorized():
            self.respond(401, {"code": "UNAUTHORIZED"})
            return
        try:
            sample = json.loads(self.read_body().decode("utf-8"))
            if not isinstance(sample, dict) or not isinstance(sample.get("user_input"), str) \
                    or not sample["user_input"].strip() or not isinstance(sample.get("response"), str) \
                    or not isinstance(sample.get("retrieved_contexts"), list) \
                    or any(not isinstance(item, str) for item in sample["retrieved_contexts"]) \
                    or ("reference" in sample and sample["reference"] is not None
                        and not isinstance(sample["reference"], str)):
                raise ValueError("invalid evaluation sample")
            self.respond(200, evaluate(sample))
        except (ValueError, UnicodeDecodeError, json.JSONDecodeError) as invalid:
            self.respond(400, {"code": "BAD_REQUEST", "message": str(invalid)})

    def respond(self, status, payload):
        body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        pass


if __name__ == "__main__":
    token = os.environ.get("RAG_METRIC_TOKEN")
    if not token:
        raise SystemExit("RAG_METRIC_TOKEN is required")

    # shortcut: the standard-library server runs local probes and offline evaluations; production uses a served runtime.
    with ThreadingHTTPServer(("0.0.0.0", 8090), MetricHandler) as server:
        server.expected_authorization = f"Bearer {token}".encode("utf-8")
        server.serve_forever()
