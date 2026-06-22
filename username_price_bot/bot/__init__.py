"""Telegram bot that estimates the real price of a Telegram NFT username.

Data is aggregated from:
  * TonAPI (tonapi.io) — on-chain truth: current owner, wallet/ownership history,
    sale history with prices. This is the reliable backbone.
  * Fragment (fragment.com) — current marketplace price / auction status (scraped,
    best-effort because Fragment has no official public API).
  * GetGems (api.getgems.io) — secondary marketplace data (best-effort).
"""

__version__ = "1.0.0"
