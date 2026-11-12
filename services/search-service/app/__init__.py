"""Unified text and image search service."""
import asyncio
import sys


# psycopg async connections require a selector loop on Windows. Linux containers
# keep their normal event-loop policy.
if sys.platform == "win32":
    asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())
