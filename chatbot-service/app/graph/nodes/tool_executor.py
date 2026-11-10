from __future__ import annotations

import asyncio

from app.graph.state import AgentState
from app.tools.registry import registry
from app.observability.trace_logger import get_logger, log_tool_call

logger = get_logger(__name__)


async def _run_one(session_id: str, tool_name: str, args: dict) -> tuple[str, dict | None, str | None]:
    """Execute one tool. Returns (name, result_or_None, error_or_None). Never raises."""
    entry = registry.get(tool_name)
    if entry is None:
        msg = f"Tool '{tool_name}' not found in registry"
        logger.warning(f"[{session_id}] {msg}")
        return tool_name, None, msg

    _, fn = entry
    log_tool_call(logger, session_id, tool_name, args)
    try:
        call_args = dict(args)
        call_args["_session_id"] = session_id
        result = await fn(call_args)
        logger.info(f"[{session_id}] tool_executed | tool={tool_name} ok=true")
        return tool_name, result, None
    except Exception as exc:
        msg = f"Tool '{tool_name}' failed: {exc}"
        logger.error(f"[{session_id}] {msg}")
        return tool_name, None, msg


async def tool_executor_node(state: AgentState) -> dict:
    # Phase 2: skip execution if policy blocked
    if state["execution_blocked"]:
        logger.info(
            f"[{state['session_id']}] tool_executor | skipped reason={state['policy_reason']}"
        )
        return {"tool_result": {}}

    primary = state["selected_tool"]
    if primary == "none":
        return {"tool_result": {}}

    session_id = state["session_id"]
    secondary_tools = state.get("secondary_tools") or []

    # Primary + any read-only secondaries run concurrently (max latency, not sum).
    coros = [_run_one(session_id, primary, state["tool_args"])]
    coros += [_run_one(session_id, st["tool"], st["args"]) for st in secondary_tools]

    outcomes = await asyncio.gather(*coros)

    tool_result: dict = {}
    secondary_results: dict = {}
    errors = list(state["errors"])

    for i, (name, result, err) in enumerate(outcomes):
        if err:
            errors.append(err)
        if i == 0:                       # primary
            tool_result = result or {}
        elif result is not None:         # secondary (read-only)
            secondary_results[name] = result

    out: dict = {"tool_result": tool_result, "errors": errors}
    if secondary_results:
        out["secondary_results"] = secondary_results
    return out
