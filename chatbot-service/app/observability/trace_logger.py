import logging
import sys
from typing import Any
from app.observability.http_middleware import current_correlation_id


def setup_logging(level: str = "INFO") -> None:
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(
        logging.Formatter(
            fmt="%(asctime)s [%(levelname)-8s] %(name)s — %(message)s",
            datefmt="%Y-%m-%dT%H:%M:%S",
        )
    )
    root = logging.getLogger()
    root.setLevel(getattr(logging, level.upper(), logging.INFO))
    root.handlers.clear()
    root.addHandler(handler)


def get_logger(name: str) -> logging.Logger:
    return logging.getLogger(name)


def log_chat_request(
    logger: logging.Logger,
    session_id: str,
    user_id: str | None,
    channel: str,
    message: str,
) -> None:
    logger.info(
        f"[{session_id}] chat_request | correlation_id={current_correlation_id()} "
        f"user={user_id} channel={channel} len={len(message)}"
    )


def log_tool_call(
    logger: logging.Logger,
    session_id: str,
    tool_name: str,
    args: dict[str, Any],
) -> None:
    safe_args = {k: v for k, v in args.items() if k != "access_token"}
    logger.debug(f"[{session_id}] tool_call | tool={tool_name} args={safe_args}")


def log_pending_event(
    logger: logging.Logger,
    session_id: str,
    event: str,
    tool: str,
    display: str = "",
) -> None:
    logger.info(
        f"[{session_id}] pending_action | event={event} tool={tool} display={display!r}"
    )
