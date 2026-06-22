"""Allow running the bot with `python -m bot`."""
import asyncio

from .main import main

if __name__ == "__main__":
    asyncio.run(main())
