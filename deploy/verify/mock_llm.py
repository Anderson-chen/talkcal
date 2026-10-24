# 假的 llama-server：只給 deploy/verify/ 在沒有 GPU 的機器上驗證「整套起得來」用，不是正式服務。
#
# 跟真的 llama-server 一樣的地方（app、healthcheck 看得到的那些）：
#   GET  /health                —— 模型檔不在 /models 就回 503（llama-server 載入中也是 503）。
#                                  所以 model-seed 沒把模型播進 volume，這個服務就不會 healthy，app 也不會起。
#   POST /v1/chat/completions   —— OpenAI 的格式，回一句固定開頭的話，不呼叫工具
#   GET  /metrics               —— 空的 Prometheus 格式，ops/ 的 Alloy 抓得到東西
# 不一樣的地方：完全不讀模型的內容，回答跟問題無關。驗的是部署，不是模型。
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MODEL = "/models/" + os.environ.get("MOCK_MODEL_FILE", "Qwen3-8B-Q4_K_M.gguf")
REPLY_PREFIX = "（mock LLM）"


class Handler(BaseHTTPRequestHandler):
    def _send(self, status, body, content_type="application/json"):
        data = body.encode() if isinstance(body, str) else json.dumps(body, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == "/health":
            if os.path.isfile(MODEL):
                self._send(200, {"status": "ok"})
            else:
                self._send(503, {"error": {"message": f"{MODEL} 不存在：model-seed 沒播好"}})
        elif self.path == "/metrics":
            self._send(200, "# mock llama-server：沒有指標\n", "text/plain; version=0.0.4")
        else:
            self._send(404, {"error": {"message": "not found"}})

    def do_POST(self):
        if self.path != "/v1/chat/completions":
            self._send(404, {"error": {"message": "not found"}})
            return
        request = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        last_user = next((m.get("content") for m in reversed(request.get("messages", []))
                          if m.get("role") == "user"), "")
        self._send(200, {
            "id": "mock-1",
            "object": "chat.completion",
            "created": 0,
            "model": request.get("model", "mock"),
            "choices": [{
                "index": 0,
                "message": {"role": "assistant", "content": f"{REPLY_PREFIX}收到：{last_user}"},
                "finish_reason": "stop",
            }],
            "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
        })

    def log_message(self, fmt, *args):
        print(f"{self.address_string()} {fmt % args}", flush=True)


if __name__ == "__main__":
    print(f"mock llama-server on :8080，模型檔 {MODEL}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
