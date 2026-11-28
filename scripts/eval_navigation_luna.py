"""Isolated Luna low conversations using the locally signed-in Codex CLI."""
from collections import Counter
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import uuid


MODEL = "gpt-6-luna"
EFFORT = "low"


class LunaSession:
    """A fresh native Codex thread per reader. Each turn receives only new observations."""

    def __init__(self, instructions):
        self.instructions = instructions
        self.thread_id = None
        self.work = None
        self.usage = {}

    def __enter__(self):
        if self.work is not None:
            raise RuntimeError("LunaSession is already open")
        self.usage = {}
        self.work = tempfile.TemporaryDirectory(prefix="doclet-luna-")
        self.policy = Path(self.work.name, "reader.txt")
        self.policy.write_text(self.instructions, encoding="utf-8")
        self.env = os.environ.copy()
        for key in ("OPENAI_API_KEY", "CODEX_API_KEY", "ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN",
                    "CODEX_THREAD_ID", "CODEX_SESSION_ID", "CODEX_TOOL_CALL_ID"):
            self.env.pop(key, None)
        return self

    def __exit__(self, exc_type, exc, traceback):
        try:
            if self.thread_id is not None:
                # Delete only the thread this context created, including its saved document history.
                result = subprocess.run(["codex", "delete", "--force", self.thread_id],
                                        cwd=self.work.name, env=self.env, capture_output=True, timeout=30)
                if result.returncode and exc is None:
                    raise RuntimeError(f"Could not remove temporary Codex thread {self.thread_id}")
        finally:
            self.work.cleanup()
            self.work = None
            self.thread_id = None

    def turn(self, prompt):
        if self.work is None:
            raise RuntimeError("Open a LunaSession context before submitting a turn")
        command = ["codex", "-a", "never", "exec", "-s", "read-only"]
        if self.thread_id:
            command.append("resume")
        command += ["--json", "--ignore-user-config", "--ignore-rules", "--skip-git-repo-check",
                    "-m", MODEL, "-c", f'model_reasoning_effort="{EFFORT}"',
                    "-c", f'model_instructions_file={json.dumps(str(self.policy))}',
                    "-c", 'web_search="disabled"', "-c", "suppress_unstable_features_warning=true",
                    "--enable", "skip_host_skill_discovery"]
        for feature in ("shell_tool", "unified_exec", "view_image", "apps", "plugins", "hooks", "multi_agent"):
            command.extend(("--disable", feature))
        if self.thread_id:
            command.append(self.thread_id)
        command.append("-")
        start = time.monotonic()
        try:
            proc = subprocess.run(command, cwd=self.work.name, env=self.env, input=prompt,
                                  text=True, capture_output=True, timeout=120)
        except subprocess.TimeoutExpired as error:
            # Recover the newly allocated ID so context exit can clean up even a partial turn.
            for line in (error.stdout or b"").splitlines():
                try:
                    event = json.loads(line)
                except ValueError:
                    continue
                if event.get("type") == "thread.started":
                    self._record_thread(event["thread_id"])
                    break
            raise RuntimeError("Luna turn timed out") from None
        wall_ms = (time.monotonic() - start) * 1000
        events = [json.loads(line) for line in proc.stdout.splitlines() if line.strip()]
        counts = dict(Counter(event.get("type") for event in events))
        starts = [event["thread_id"] for event in events if event.get("type") == "thread.started"]
        if len(starts) != 1:
            raise RuntimeError(f"Codex did not identify one native thread (exit {proc.returncode}, events={counts})")
        self._record_thread(starts[0])
        if proc.returncode:
            raise RuntimeError(f"Luna turn failed (Codex exit {proc.returncode}, events={counts})")
        completed = [event.get("usage") for event in events if event.get("type") == "turn.completed"]
        if len(completed) != 1 or not isinstance(completed[0], dict):
            raise RuntimeError(f"Expected one completed Luna turn with usage (events={counts})")
        # Codex exec resume emits cumulative thread counters, including previous cached input.
        usage = completed[0]
        delta = {}
        for key in ("input_tokens", "cached_input_tokens", "output_tokens", "cache_write_input_tokens",
                    "reasoning_output_tokens"):
            value = usage.get(key) if key in ("input_tokens", "output_tokens") else usage.get(key, 0)
            if type(value) is not int or value < self.usage.get(key, 0):
                raise RuntimeError(f"Invalid cumulative Codex usage for {key}")
            delta[key] = value - self.usage.get(key, 0)
        if delta["input_tokens"] < delta["cached_input_tokens"] or delta["input_tokens"] == 0:
            raise RuntimeError("Invalid per-turn input token counters")
        self.usage = usage
        cost = dict(model=MODEL, effort=EFFORT, input_tokens=delta["input_tokens"], cached_input_tokens=delta["cached_input_tokens"],
                    output_tokens=delta["output_tokens"], cache_write_tokens=delta["cache_write_input_tokens"],
                    reasoning_output_tokens=delta["reasoning_output_tokens"], wall_ms=wall_ms)
        item_types = {event["item"]["type"] for event in events
                      if event.get("type") in ("item.started", "item.updated", "item.completed")}
        unexpected = item_types - {"agent_message", "reasoning"}
        if unexpected:
            # Wait for the CLI's completed-turn counters, but expose none of this turn's content.
            return dict(**cost, unsupported_item_types=sorted(unexpected))

        messages = [event["item"] for event in events if event.get("type") == "item.completed"
                    and event["item"]["type"] == "agent_message"]
        answer = ""
        commentary = messages
        # A CLI without phase information preserves emission order in item.completed.
        if messages and messages[-1].get("phase") != "commentary":
            text = messages[-1].get("text", "")
            answer = text if text.strip() else ""
            commentary = messages[:-1]
        return dict(**cost, prompt=prompt, answer=answer, empty_reply=not answer,
                    commentary=[message["text"] for message in commentary])

    def _record_thread(self, thread_id):
        if not isinstance(thread_id, str) or str(uuid.UUID(thread_id)) != thread_id:
            raise ValueError("Codex returned an invalid thread UUID")
        if self.thread_id and self.thread_id != thread_id:
            raise RuntimeError("Codex resumed a different thread")
        self.thread_id = thread_id
