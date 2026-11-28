"""One fresh, locally authenticated CLI turn per documentation question."""
import json
import os
import subprocess
import tempfile
from pathlib import Path


def read_answer(provider, model, prompt, effort="low", *, output_schema=None):
    with tempfile.TemporaryDirectory() as directory:
        env = os.environ.copy()
        for key in ("OPENAI_API_KEY", "ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN"):
            env.pop(key, None)
        instruction = "Answer only from the supplied document, in one short sentence. Use no tools."
        if provider == "codex":
            policy = Path(directory, "reader.txt")
            policy.write_text(instruction)
            cmd = ["codex", "exec", "--ignore-user-config", "--ephemeral", "--skip-git-repo-check",
                   "-s", "read-only", "-m", model, "-c", f"model_reasoning_effort={json.dumps(effort)}",
                   "-c", f"model_instructions_file={json.dumps(str(policy))}", "--json", "-"]
            if output_schema is not None:
                schema_path = Path(directory, "answer-schema.json")
                schema_path.write_text(json.dumps(output_schema))
                cmd[-1:-1] = ["--output-schema", str(schema_path)]
        else:
            cmd = ["claude", "-p", "--model", model, "--tools", "", "--strict-mcp-config",
                   "--system-prompt", instruction, "--no-session-persistence", "--output-format", "json"]
        result = subprocess.run(cmd, cwd=directory, env=env, input=prompt, text=True,
                                capture_output=True, timeout=120, check=True)
        if provider == "claude":
            event = json.loads(result.stdout)
            if event.get("is_error") or len(event["modelUsage"]) != 1:
                raise ValueError("Claude reader did not complete with a single model")
            usage = event["usage"]
            cached = usage.get("cache_read_input_tokens", 0)
            written = usage.get("cache_creation_input_tokens", 0)
            return dict(answer=event["result"], model=next(iter(event["modelUsage"])),
                        input_tokens=usage["input_tokens"] + cached + written,
                        cached_input_tokens=cached, cache_write_tokens=written,
                        output_tokens=usage["output_tokens"])
        events = [json.loads(line) for line in result.stdout.splitlines()]
        items = [event["item"] for event in events if event["type"] == "item.completed"]
        if any(item["type"] not in ("agent_message", "reasoning") for item in items):
            raise ValueError("Tool-assisted answer excluded from the closed-document evaluation")
        usage = next(event["usage"] for event in events if event["type"] == "turn.completed")
        answer = next(item["text"] for item in reversed(items) if item["type"] == "agent_message")
        return dict(answer=answer, model=model, input_tokens=usage["input_tokens"],
                    cached_input_tokens=usage.get("cached_input_tokens", 0),
                    cache_write_tokens=usage.get("cache_write_input_tokens", 0),
                    output_tokens=usage["output_tokens"])
