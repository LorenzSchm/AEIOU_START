import os

os.environ.setdefault("SIM_AGENTS", "0")  # no background customers in tests; agents are driven explicitly
os.environ.setdefault("SIM_RATE_LIMIT", "10")
