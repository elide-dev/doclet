"""Read one evaluation prompt on stdin and answer with an installed local Ollama model."""
import json
import os
import sys
from urllib.request import Request, urlopen

model = os.environ["OLLAMA_MODEL"]
payload = {
    "model": model,
    "prompt": sys.stdin.read(),
    "stream": False,
    "options": {"temperature": 0, "seed": 42, "num_predict": 96},
}
request = Request("http://127.0.0.1:11434/api/generate", data=json.dumps(payload).encode(),
                  headers={"Content-Type": "application/json"})
with urlopen(request, timeout=85) as response:
    result = json.load(response)
if not result.get("done") or not result.get("response", "").strip():
    raise ValueError("Local reader returned an incomplete or empty response")
print(result["response"])
