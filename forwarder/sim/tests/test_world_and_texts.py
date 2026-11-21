from app.config import settings
from app.texts import money_amounts, parse_request
from app.world import ORIGINS, LaneRate, World

world = World(settings.start_kit, settings.fixtures, settings.seed)


def test_next_break_rule_matches_our_engine():
    lane = LaneRate("X", "CGN", "EZE", 5, 79.0, [(0, 5.41), (45, 4.17), (100, 3.21), (300, 2.89)], 0.7, 0.2)
    own, _ = lane.price(90, higher_breaks=False)
    best, billed = lane.price(90, higher_breaks=True)
    assert own == round(4.17 * 90 + 0.9 * 90, 2) and best == round(3.21 * 100 + 0.9 * 100, 2) and billed == 100


def test_world_has_three_kinds_of_airlines():
    kinds = set(world.kind.values())
    assert kinds == {"sheet", "portal", "email"}
    assert world.kind["Yellowtail Air Freight"] == "sheet" and world.kind["Juniper Cargo Lines"] == "portal"
    assert world.kind["Zephyr Steppe Cargo"] == "email"
    assert world.portal_lanes()


def test_truth_adds_the_cheapest_trucker_for_door_pickup():
    origin, dest = next((o, d) for (_, o, d) in world.lanes if o == "FRA")
    plain = world.truth(origin, dest, 500, "cheapest", None)
    door = world.truth(origin, dest, 500, "cheapest", "61")
    assert door.total > plain.total and door.trucker


def test_parses_our_bid_request_mail():
    body = ("Could you provide a spot rate for a shipment from MUC to LAX? We have 2 pieces of furniture samples "
            "weighing 998 kg and with a volume of 2.39 cbm, ready on 29 November 2026.")
    req = parse_request("Spot rate request MUC to LAX", body, set(ORIGINS), world.destinations())
    assert (req.origin, req.destination, req.weight_kg, req.volume_cbm) == ("MUC", "LAX", 998, 2.39)
    truck = parse_request("Pickup request", "Please collect 1.549 kg (8,26 cbm) at 61018 Bad Homburg for FRA.", set(ORIGINS), world.destinations())
    assert truck.zip == "61018" and truck.weight_kg == 1549 and truck.volume_cbm == 8.26


def test_money_in_the_history_phrasings():
    assert money_amounts("Our all-in rate is 556.00 EUR, valid until") == [(556.0, "EUR")]
    assert money_amounts("all-in EUR 1,234.50 | transit 4d") == [(1234.5, "EUR")]
