from app.scoring import quoted_total, score


def test_reads_the_totals_our_writer_produces():
    assert quoted_total("I can offer you Fjordline Air Cargo at EUR 2,780.00 all-in")[:2] == (2780.0, "EUR")
    assert quoted_total("Gesamtpreis: 1.387,35 EUR")[:2] == (1387.35, "EUR")
    assert quoted_total("all in EUR 3316.54")[:2] == (3316.54, "EUR")
    assert quoted_total("No price yet, 998 kg and 2.39 cbm")[2] == "no_total"
    assert quoted_total("EUR 100.00 or EUR 120.00")[2] == "multiple_totals"
    assert quoted_total("USD 900.00")[2] == "wrong_currency"


def test_price_and_time_points():
    s = score("EUR 1,000.00", 1000.0, 60)
    assert s.verdict == "accepted" and s.points == {"price": 45.0, "time": 15.0, "cost": 40.0}
    half = score("EUR 1,025.00", 1000.0, 240)
    assert half.points["price"] == 22.5 and half.points["time"] == 7.5
    assert score("EUR 1,051.00", 1000.0, 60).reason == "too_high"
    assert score("EUR 940.00", 1000.0, 60).reason == "too_low"
    assert score("EUR 1,000.00", 1000.0, 400).reason == "late"
