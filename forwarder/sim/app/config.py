"""Simulator settings, read from the environment once at start. Chaos switches can change some at runtime."""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path

SIM_DIR = Path(__file__).resolve().parents[1]


def _env(name: str, default: str) -> str:
    return os.environ.get(name, default)


def _start_kit() -> Path:
    explicit = os.environ.get("SIM_START_KIT")
    if explicit:
        return Path(explicit)
    return SIM_DIR.parents[1] / "start-kit"  # repo checkout: forwarder/sim -> ../../start-kit


@dataclass
class Settings:
    seed: int = int(_env("SIM_SEED", "5"))
    team: str = _env("SIM_TEAM", "aeiou")
    mailbox: str = _env("SIM_MAILBOX", "aeiou@hackloop.mail")
    agents: bool = _env("SIM_AGENTS", "1") == "1"
    rfq_interval_s: float = float(_env("SIM_RFQ_INTERVAL_SECONDS", "360"))
    first_rfq_delay_s: float = float(_env("SIM_FIRST_RFQ_DELAY_SECONDS", "20"))
    rfq_lifetime_s: float = float(_env("SIM_RFQ_LIFETIME_SECONDS", "360"))
    carrier_delay_min_s: float = float(_env("SIM_CARRIER_DELAY_MIN", "5"))
    carrier_delay_max_s: float = float(_env("SIM_CARRIER_DELAY_MAX", "40"))
    rate_limit_per_min: int = int(_env("SIM_RATE_LIMIT", "10"))
    mail_limit_per_min: int = int(_env("SIM_MAIL_LIMIT", "60"))
    accept_book_prob: float = float(_env("SIM_ACCEPT_BOOK_PROB", "0.7"))
    follow_up_prob: float = float(_env("SIM_FOLLOW_UP_PROB", "0.15"))
    tracking_prob: float = float(_env("SIM_TRACKING_PROB", "0.6"))
    history_share: float = float(_env("SIM_HISTORY_SHARE", "0.25"))
    # chaos (runtime switchable via POST /sim/chaos)
    portal_503_share: float = float(_env("SIM_PORTAL_503_SHARE", "0"))
    booking_503_share: float = float(_env("SIM_BOOKING_503_SHARE", "0"))
    alt_phrasing: bool = _env("SIM_ALT_PHRASING", "0") == "1"
    start_kit: Path = field(default_factory=_start_kit)
    fixtures: Path = SIM_DIR / "fixtures"


settings = Settings()
