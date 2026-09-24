"""
Simulated fleet data generator for service 190, direction 1
(Choa Chu Kang Int -> Kampong Bahru Ter), weekday AM peak.

Produces three scenarios from the same random seed so they are directly
comparable:

  normal              no disruption, headways stay near the planned value
  bunching            a vehicle breakdown delays one bus (A); the bus behind
                      it (B) catches up and the pair bunches downstream
  bunching_mitigated  same as `bunching`, but after the bunching rule confirms,
                      B is held at designated control stops (<= 120 s each),
                      A proceeds with no hold. Nobody is ever told to speed up.

Each scenario folder contains:

  fleet_telemetry.jsonl   one row per bus per minute (vehicle-level ground truth,
                          same field names as SimulatedData/bus_telemetry_normal.jsonl
                          on the my-changes branch, plus direction/trip fields)
  bus_arrivals_v3.jsonl   one row per monitored stop per minute, shaped like a
                          row of ingestion.raw_observation whose `payload` is an
                          LTA DataMall Bus Arrival v3 response. No plates in here,
                          exactly like the real feed.
  traffic_incidents.json  DataMall TrafficIncidents-shaped list (empty if none)
  scenario.json           parameters, fleet roster, events, the expected
                          bunching detection, reference directives and metrics

Why a physical model instead of hand-written rows: bunching is self-reinforcing
(a late bus finds more people waiting, dwells longer and gets later still, while
the bus behind finds fewer people and catches up). Simulating dwell from boarding
makes the data behave that way instead of just asserting it.

Run from the repo root:   py SimulatedData/generate_route190.py
Stdlib only; output is deterministic for a given --seed.
"""

import argparse
import json
import math
import random
from datetime import datetime, timedelta, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
TOPOLOGY_FILE = HERE / "reference" / "route_190_topology.json"
OUT_DIR = HERE / "route190"

SGT = timezone(timedelta(hours=8))
SERVICE_DATE = datetime(2026, 9, 28, tzinfo=SGT)  # a Monday
SERVICE_ID = "190"
DIRECTION_ID = 1
# Operator code as it would appear in Bus Arrival v3. Verify against DataMall
# BusServices before relying on it; nothing in the repo records it yet.
OPERATOR = "SBST"

# --- Planned service (route-profile values; assumptions, see README) ---------
PLANNED_HEADWAY_S = 600  # 10-minute AM-peak headway
FIRST_DISPATCH = SERVICE_DATE.replace(hour=6, minute=30)
LAST_DISPATCH = SERVICE_DATE.replace(hour=8, minute=20)
WINDOW_START = SERVICE_DATE.replace(hour=7, minute=30)
WINDOW_END = SERVICE_DATE.replace(hour=8, minute=30)

# --- Bunching rule (project-v2.md section 6) ----------------------------------
BUNCHING_RATIO = 0.50       # candidate when headway <= 0.5 * H
GAP_RATIO = 1.50            # following headway >= 1.5 * H raises severity
CONSECUTIVE_POLLS = 2       # confirmed after two consecutive valid polls

# --- Mitigation limits (project-v2.md section 9) ------------------------------
MAX_HOLD_S = 120
APPROVAL_LATENCY_S = 90     # detection -> AI recommendation -> controller approval
HOLD_TARGET_GAP_S = int(0.75 * PLANNED_HEADWAY_S)

# Stops where the fixture allows holding / monitoring. Demo assumptions: the
# repo has no control-stop configuration yet.
CONTROL_STOPS = {"44241", "40239", "09037", "04229"}
MONITORED_STOPS = ["44251", "40229", "09047", "08057", "04149"]

# --- Vehicles ------------------------------------------------------------------
VEHICLES = {  # seated, total capacity
    "DD": (80, 125),
    "SD": (43, 85),
}

# --- Demand and running-time model (AM peak, towards the city) ----------------
def boarding_rate_per_min(seq):
    if seq == 1:
        return 2.0          # interchange
    if seq == 2:
        return 1.5          # Choa Chu Kang MRT
    if seq in (7, 8):
        return 1.0          # Phoenix LRT, Bukit Panjang MRT
    if seq <= 11:
        return 0.6          # Teck Whye / Bukit Panjang estates
    return 0.25             # city stops


def alighting_fraction(seq, last_seq):
    if seq == last_seq:
        return 1.0
    if seq <= 11:
        return 0.02
    if seq <= 16:
        return 0.08         # Stevens
    if seq <= 31:
        return 0.18         # Orchard, Dhoby Ghaut, City Hall, Chinatown
    return 0.40


def segment_speed_kmh(from_seq):
    if from_seq == 11:
        return 60.0         # expressway, Blk 270 -> Stevens
    if from_seq < 11:
        return 22.0
    return 15.0             # city streets in the AM peak


ROAD_FACTOR = 1.25          # straight-line stop spacing -> road distance
STOP_PENALTY_S = 12         # braking + pulling out when a stop is served
DOOR_BASE_S = 6
BOARD_S_PER_PAX = 2.2
ALIGHT_S_PER_PAX = 1.4

# --- Disruption for the bunching scenarios ------------------------------------
INCIDENT = {
    "type": "Vehicle breakdown",
    "from_seq": 5,          # Blk 6 Teck Whye Ave -> Blk 157 Teck Whye Ave
    "start": SERVICE_DATE.replace(hour=7, minute=34),
    "end": SERVICE_DATE.replace(hour=7, minute=44),
    "delay_s": 300,
}


# ------------------------------------------------------------------------------
def rng(seed, *key):
    """Independent, reproducible stream per (bus, stop, ...) so that holding one
    bus in the mitigated scenario does not reshuffle everyone else's randomness."""
    return random.Random(":".join(str(k) for k in (seed,) + key))


def poisson(r, lam):
    if lam <= 0:
        return 0
    if lam > 40:
        return max(0, round(r.gauss(lam, math.sqrt(lam))))
    limit, k, p = math.exp(-lam), 0, 1.0
    while True:
        p *= r.random()
        if p <= limit:
            return k
        k += 1


def haversine_km(a, b):
    lat1, lon1, lat2, lon2 = map(math.radians, (a["latitude"], a["longitude"], b["latitude"], b["longitude"]))
    h = math.sin((lat2 - lat1) / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin((lon2 - lon1) / 2) ** 2
    return 2 * 6371 * math.asin(math.sqrt(h))


def plate_checksum(prefix, number):
    """Singapore registration check letter (fictional plates, valid format)."""
    letters = prefix[-2:]
    vals = [ord(c) - 64 for c in letters] + [int(d) for d in f"{number:04d}"]
    total = sum(v * w for v, w in zip(vals, (9, 4, 5, 4, 3, 2)))
    return "AZYXUTSRPMLKJHGEDCB"[total % 19]


def iso(t):
    return t.isoformat(timespec="seconds")


def load_category(onboard, vtype):
    seated, capacity = VEHICLES[vtype]
    if onboard < 0.9 * seated:
        return "SEA"
    if onboard < 0.85 * capacity:
        return "SDA"
    return "LSD"


# ------------------------------------------------------------------------------
def build_fleet(seed):
    r = rng(seed, "fleet")
    fleet, t, k, used = [], FIRST_DISPATCH, 0, set()
    while t <= LAST_DISPATCH:
        while True:
            number = r.randint(1000, 9999)
            plate = f"SBS{number}{plate_checksum('SBS', number)}"
            if plate not in used:
                used.add(plate)
                break
        vtype = "DD" if r.random() < 0.75 else "SD"
        jitter = r.randint(-20, 20)
        fleet.append({
            "index": k,
            "bus_plate": plate,
            "vehicle_type": vtype,
            "bus_captain_id": f"BC-{SERVICE_ID}-{k + 1:02d}",
            "trip_id": f"{SERVICE_ID}-{DIRECTION_ID}-{t.strftime('%H%M')}",
            "scheduled_dispatch": t,
            "dispatch": t + timedelta(seconds=jitter),
        })
        t += timedelta(seconds=PLANNED_HEADWAY_S)
        k += 1
    return fleet


def simulate(stops, fleet, seed, incident=None, hold_policy=None):
    """Runs buses in dispatch order. Each bus's boarding depends on the time
    since the previous bus left the stop, which is what makes bunching grow.
    Buses cannot overtake: a follower that catches up queues behind the leader."""
    n = len(stops)
    cum_km = [0.0]
    for i in range(1, n):
        cum_km.append(cum_km[-1] + haversine_km(stops[i - 1], stops[i]) * ROAD_FACTOR)

    runs, holds = [], []
    for bus in fleet:
        k = bus["index"]
        leader = runs[-1] if runs else None
        _, capacity = VEHICLES[bus["vehicle_type"]]
        arr, dep, onboard_after, left_behind = [], [], [], 0
        onboard = 0
        t = bus["dispatch"]
        for i, stop in enumerate(stops):
            seq = i + 1
            r = rng(seed, "stop", k, seq)
            if i > 0:
                seg = rng(seed, "seg", k, seq)
                km = cum_km[i] - cum_km[i - 1]
                speed = segment_speed_kmh(seq - 1) * math.exp(seg.gauss(0, 0.10))
                travel = km / speed * 3600
                if incident and seq - 1 == incident["from_seq"] and incident["start"] <= t < incident["end"]:
                    travel += incident["delay_s"]
                t = t + timedelta(seconds=travel)
                if leader and t < leader["dep"][i] + timedelta(seconds=5):
                    t = leader["dep"][i] + timedelta(seconds=5)
            a = t

            since_last = PLANNED_HEADWAY_S if leader is None else max(0.0, (a - leader["dep"][i]).total_seconds())
            waiting = poisson(r, boarding_rate_per_min(seq) * since_last / 60)
            if leader is not None:
                waiting += leader["left_behind_at"][i]
            alight = 0
            if onboard:
                p = alighting_fraction(seq, n)
                alight = onboard if p >= 1 else sum(1 for _ in range(onboard) if r.random() < p)
            onboard -= alight
            board = 0 if seq == n else min(waiting, capacity - onboard)
            stranded = 0 if seq == n else waiting - board
            onboard += board

            dwell = 0.0
            if board or alight or i == 0:
                dwell = DOOR_BASE_S + max(board * BOARD_S_PER_PAX, alight * ALIGHT_S_PER_PAX)
                if i > 0:
                    dwell += STOP_PENALTY_S
            d = a + timedelta(seconds=dwell)

            if hold_policy and stop["stop_code"] in CONTROL_STOPS:
                hold = hold_policy(bus, leader, i, d)
                if hold:
                    holds.append({
                        "bus_plate": bus["bus_plate"],
                        "stop_code": stop["stop_code"],
                        "stop_sequence": seq,
                        "hold_seconds": hold,
                        "held_from": iso(d),
                    })
                    d += timedelta(seconds=hold)

            arr.append(a)
            dep.append(d)
            onboard_after.append(onboard)
            left_behind += stranded
            bus.setdefault("_stranded", []).append(stranded)
            t = d

        runs.append({
            "bus": bus,
            "arr": arr,
            "dep": dep,
            "onboard_after": onboard_after,
            "left_behind_at": bus.pop("_stranded"),
            "left_behind_total": left_behind,
        })
    return runs, cum_km, holds


# ------------------------------------------------------------------------------
def position_at(run, stops, cum_km, t):
    """Returns (stop_index_last_served, at_stop, km, lat, lon, speed_kmh) or None
    if the bus is not on the road at time t."""
    arr, dep = run["arr"], run["dep"]
    if t < run["bus"]["dispatch"] or t >= dep[-1]:
        return None
    for i in range(len(stops)):
        if arr[i] <= t < dep[i]:
            s = stops[i]
            return i, True, cum_km[i], s["latitude"], s["longitude"], 0.0
        if i + 1 < len(stops) and dep[i] <= t < arr[i + 1]:
            span = (arr[i + 1] - dep[i]).total_seconds()
            f = (t - dep[i]).total_seconds() / span if span else 1.0
            a, b = stops[i], stops[i + 1]
            km = cum_km[i] + f * (cum_km[i + 1] - cum_km[i])
            speed = (cum_km[i + 1] - cum_km[i]) / (span / 3600) if span else 0.0
            return (i, False, km,
                    a["latitude"] + f * (b["latitude"] - a["latitude"]),
                    a["longitude"] + f * (b["longitude"] - a["longitude"]),
                    speed)
    return None


def time_leader_reached(leader, cum_km, km):
    """First time the leader was at distance `km` along the route."""
    pts = [(leader["bus"]["dispatch"], 0.0)]
    for i in range(len(cum_km)):
        pts.append((leader["arr"][i], cum_km[i]))
        pts.append((leader["dep"][i], cum_km[i]))
    for (t0, x0), (t1, x1) in zip(pts, pts[1:]):
        if x0 <= km <= x1 and x1 > x0:
            return t0 + (t1 - t0) * ((km - x0) / (x1 - x0))
        if x0 == km:
            return t0
    return None


def minutes(start, end):
    t = start
    while t <= end:
        yield t
        t += timedelta(minutes=1)


def telemetry_rows(runs, stops, cum_km):
    rows = []
    for tick in minutes(WINDOW_START, WINDOW_END):
        for k, run in enumerate(runs):
            bus = run["bus"]
            # GPS reports are not perfectly aligned to the minute.
            t = tick + timedelta(seconds=(k * 7) % 23)
            pos = position_at(run, stops, cum_km, t)
            if pos is None:
                continue
            i, at_stop, km, lat, lon, speed = pos
            headway = None
            if k > 0:
                reached = time_leader_reached(runs[k - 1], cum_km, km)
                if reached is not None:
                    headway = round((t - reached).total_seconds())
            onboard = run["onboard_after"][i]
            rows.append({
                "service_no": SERVICE_ID,
                "direction": DIRECTION_ID,
                "trip_id": bus["trip_id"],
                "bus_plate": bus["bus_plate"],
                "current_stop_id": stops[i]["stop_code"],
                "next_stop_id": stops[i + 1]["stop_code"] if i + 1 < len(stops) else None,
                "stop_sequence": i + 1,
                "at_stop": at_stop,
                "headway_seconds": headway,
                "load_status": load_category(onboard, bus["vehicle_type"]),
                "passenger_count": onboard,
                "vehicle_type": bus["vehicle_type"],
                "speed_kmh": round(speed, 1),
                "location": {"type": "Point", "coordinates": [round(lon, 6), round(lat, 6)]},
                "is_sos_triggered": False,
                "recorded_at": iso(t),
            })
    return rows


NOMINAL_DWELL_S = 25        # what an ETA predictor assumes per intermediate stop


def projected_eta(run, stops, cum_km, i, now):
    """ETA the way a real predictor would make it: from where the bus is now,
    at nominal speeds, knowing nothing about incidents or future crowding."""
    pos = position_at(run, stops, cum_km, now)
    if pos is None:
        start, km, last = run["bus"]["scheduled_dispatch"], 0.0, 0
        if start < now:
            start = now
    else:
        start, km, last = now, pos[2], pos[0]
    t = 0.0
    for j in range(last, i):
        seg_from = max(km, cum_km[j])
        seg_km = cum_km[j + 1] - seg_from
        if seg_km > 0:
            t += seg_km / segment_speed_kmh(j + 1) * 3600
        if j + 1 < i:
            t += NOMINAL_DWELL_S
    return start + timedelta(seconds=t)


EMPTY_NEXT_BUS = {k: "" for k in (
    "OriginCode", "DestinationCode", "EstimatedArrival", "Monitored",
    "Latitude", "Longitude", "VisitNumber", "Load", "Feature", "Type")}


def arrival_rows(runs, stops, cum_km, seed):
    """Bus Arrival v3 snapshots for monitored stops, wrapped like a
    raw_observation row. `_truth` is simulation-only metadata (which plate each
    NextBus really was) so tests can score detection; strip it before feeding
    anything that is meant to behave like the live system."""
    rows = []
    idx = {s["stop_code"]: i for i, s in enumerate(stops)}
    origin, dest = stops[0]["stop_code"], stops[-1]["stop_code"]
    for poll in minutes(WINDOW_START, WINDOW_END):
        for code in MONITORED_STOPS:
            i = idx[code]
            upcoming = []
            for k, run in enumerate(runs):
                if run["dep"][i] <= poll:
                    continue  # already served this stop
                bus = run["bus"]
                r = rng(seed, "eta", k, code, poll.isoformat())
                monitored = poll >= bus["dispatch"]
                eta = projected_eta(run, stops, cum_km, i, poll)
                eta += timedelta(seconds=r.gauss(0, 5) if monitored else r.gauss(0, 30))
                if monitored:
                    pos = position_at(run, stops, cum_km, poll)
                    lat, lon = (pos[3], pos[4]) if pos else (stops[0]["latitude"], stops[0]["longitude"])
                    last = pos[0] if pos else 0
                    load = load_category(run["onboard_after"][last], bus["vehicle_type"])
                else:
                    lat = lon = 0.0
                    load = "SEA"
                upcoming.append((eta, k, {
                    "OriginCode": origin,
                    "DestinationCode": dest,
                    "EstimatedArrival": iso(eta),
                    "Monitored": 1 if monitored else 0,
                    "Latitude": f"{lat:.7f}" if monitored else "0.0",
                    "Longitude": f"{lon:.7f}" if monitored else "0.0",
                    "VisitNumber": "1",
                    "Load": load,
                    "Feature": "WAB",
                    "Type": bus["vehicle_type"],
                }))
            upcoming.sort(key=lambda x: x[0])
            nb = [u[2] for u in upcoming[:3]] + [dict(EMPTY_NEXT_BUS)] * (3 - min(3, len(upcoming)))
            payload = {
                "odata.metadata": "https://datamall2.mytransport.sg/ltaodataservice/v3/$metadata#BusArrival/@Element",
                "BusStopCode": code,
                "Services": [{
                    "ServiceNo": SERVICE_ID,
                    "Operator": OPERATOR,
                    "NextBus": nb[0],
                    "NextBus2": nb[1],
                    "NextBus3": nb[2],
                }],
            }
            rows.append({
                "source": "SIMULATION",
                "source_key": f"stop:{code}",
                "request_parameters": {"BusStopCode": code},
                "http_status": 200,
                "fetched_at": iso(poll + timedelta(seconds=2)),
                "payload": payload,
                "_truth": {"next_bus_plates": [runs[u[1]]["bus"]["bus_plate"] for u in upcoming[:3]]},
            })
    return rows


def detect_bunching(arrivals):
    """Reference implementation of the project-v2.md section 6 rule, used only
    to label the fixture with its expected outcome."""
    threshold = BUNCHING_RATIO * PLANNED_HEADWAY_S
    streak = {}
    for row in arrivals:
        code = row["source_key"].split(":", 1)[1]
        svc = row["payload"]["Services"][0]
        b1, b2, b3 = svc["NextBus"], svc["NextBus2"], svc["NextBus3"]
        plates = row["_truth"]["next_bus_plates"]
        if b1.get("Monitored") != 1 or b2.get("Monitored") != 1:
            streak.pop(code, None)
            continue
        e1, e2 = datetime.fromisoformat(b1["EstimatedArrival"]), datetime.fromisoformat(b2["EstimatedArrival"])
        h = (e2 - e1).total_seconds()
        pair = tuple(plates[:2])
        if h <= threshold:
            prev = streak.get(code)
            if prev and prev["pair"] == pair:
                prev["obs"].append((row["fetched_at"], h))
            else:
                streak[code] = {"pair": pair, "obs": [(row["fetched_at"], h)]}
            s = streak[code]
            if len(s["obs"]) >= CONSECUTIVE_POLLS:
                following = None
                if b3.get("Monitored") == 1:
                    following = (datetime.fromisoformat(b3["EstimatedArrival"]) - e2).total_seconds()
                return {
                    "state": "BUNCHING",
                    "service_id": SERVICE_ID,
                    "direction_id": DIRECTION_ID,
                    "monitored_stop": code,
                    "leader_plate": pair[0],
                    "follower_plate": pair[1],
                    "expected_headway_s": PLANNED_HEADWAY_S,
                    "threshold_s": threshold,
                    "observations": [{"fetched_at": f, "headway_s": round(x)} for f, x in s["obs"]],
                    "following_headway_s": round(following) if following is not None else None,
                    "severity": "HIGH" if following is not None and following >= GAP_RATIO * PLANNED_HEADWAY_S else "NORMAL",
                    "first_seen": s["obs"][0][0],
                    "confirmed_at": s["obs"][-1][0],
                }
        else:
            streak.pop(code, None)
    return None


def make_hold_policy(detection):
    confirmed = datetime.fromisoformat(detection["confirmed_at"])
    earliest = confirmed + timedelta(seconds=APPROVAL_LATENCY_S)
    follower = detection["follower_plate"]

    def policy(bus, leader, i, ready_to_depart):
        if bus["bus_plate"] != follower or leader is None or ready_to_depart < earliest:
            return 0
        gap = (ready_to_depart - leader["dep"][i]).total_seconds()
        return int(min(MAX_HOLD_S, max(0, HOLD_TARGET_GAP_S - gap)))

    return policy


def passenger_wait_minutes(runs, stops):
    """Expected total waiting time at stops for passengers arriving at random:
    sum over stops and consecutive departures of rate * gap^2 / 2. Covers every
    trip in the fleet (not just the output window) so scenarios compare fairly."""
    total = 0.0
    for i in range(len(stops) - 1):
        lam = boarding_rate_per_min(i + 1) / 60
        for a, b in zip(runs, runs[1:]):
            g = (b["dep"][i] - a["dep"][i]).total_seconds()
            total += lam * g * g / 2
    return round(total / 60)


def headway_profile(runs, stops, plates):
    out = []
    by_plate = {r["bus"]["bus_plate"]: (k, r) for k, r in enumerate(runs)}
    for plate in plates:
        k, run = by_plate[plate]
        if k == 0:
            continue
        leader = runs[k - 1]
        out.append({
            "bus_plate": plate,
            "headway_to_bus_ahead_s": [
                {"stop_sequence": i + 1, "stop_code": s["stop_code"],
                 "seconds": round((run["arr"][i] - leader["arr"][i]).total_seconds())}
                for i, s in enumerate(stops)],
        })
    return out


# ------------------------------------------------------------------------------
def write_scenario(name, description, stops, fleet, runs, cum_km, holds, seed,
                   incident=None, detection=None, directives=None, highlight=()):
    folder = OUT_DIR / name
    folder.mkdir(parents=True, exist_ok=True)

    tel = telemetry_rows(runs, stops, cum_km)
    arr = arrival_rows(runs, stops, cum_km, seed)
    with open(folder / "fleet_telemetry.jsonl", "w", encoding="utf-8", newline="\n") as f:
        for row in tel:
            f.write(json.dumps(row) + "\n")
    with open(folder / "bus_arrivals_v3.jsonl", "w", encoding="utf-8", newline="\n") as f:
        for row in arr:
            f.write(json.dumps(row) + "\n")

    incidents = {"value": []}
    if incident:
        a, b = stops[incident["from_seq"] - 1], stops[incident["from_seq"]]
        incidents["value"].append({
            "Type": incident["type"],
            "Latitude": round((a["latitude"] + b["latitude"]) / 2, 6),
            "Longitude": round((a["longitude"] + b["longitude"]) / 2, 6),
            "Message": f"({incident['start'].day}/{incident['start'].month}){incident['start'].strftime('%H:%M')} "
                       f"{incident['type']} on {a['road_name']} after {a['description']}.",
        })
    with open(folder / "traffic_incidents.json", "w", encoding="utf-8", newline="\n") as f:
        json.dump(incidents, f, indent=2)
        f.write("\n")

    scenario = {
        "scenario_id": f"route190-{name}",
        "description": description,
        "service_id": SERVICE_ID,
        "operator": OPERATOR,
        "direction_id": DIRECTION_ID,
        "service_date": SERVICE_DATE.date().isoformat(),
        "window": {"start": iso(WINDOW_START), "end": iso(WINDOW_END)},
        "seed": seed,
        "route_profile": {
            "planned_headway_s": PLANNED_HEADWAY_S,
            "bunching_threshold_s": BUNCHING_RATIO * PLANNED_HEADWAY_S,
            "gap_threshold_s": GAP_RATIO * PLANNED_HEADWAY_S,
            "consecutive_polls": CONSECUTIVE_POLLS,
            "max_hold_s": MAX_HOLD_S,
            "control_stops": sorted(CONTROL_STOPS),
            "monitored_stops": MONITORED_STOPS,
        },
        "fleet": [{
            "bus_plate": b["bus_plate"],
            "vehicle_type": b["vehicle_type"],
            "bus_captain_id": b["bus_captain_id"],
            "trip_id": b["trip_id"],
            "scheduled_dispatch": iso(b["scheduled_dispatch"]),
            "actual_dispatch": iso(b["dispatch"]),
        } for b in fleet],
        "events": [] if not incident else [{
            "type": "TRAFFIC_INCIDENT",
            "incident_type": incident["type"],
            "between_stops": [stops[incident["from_seq"] - 1]["stop_code"], stops[incident["from_seq"]]["stop_code"]],
            "active_from": iso(incident["start"]),
            "active_to": iso(incident["end"]),
            "added_delay_s": incident["delay_s"],
        }],
        "expected_detection": detection,
        "reference_directives": directives,
        "applied_holds": holds,
        "metrics": {
            "passenger_wait_minutes": passenger_wait_minutes(runs, stops),
            "passengers_left_behind": sum(r["left_behind_total"] for r in runs),
            "telemetry_rows": len(tel),
            "arrival_snapshots": len(arr),
        },
        "headway_profile": headway_profile(runs, stops, highlight),
    }
    with open(folder / "scenario.json", "w", encoding="utf-8", newline="\n") as f:
        json.dump(scenario, f, indent=2)
        f.write("\n")
    return scenario


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--seed", type=int, default=190)
    args = ap.parse_args()
    seed = args.seed

    topo = json.loads(TOPOLOGY_FILE.read_text(encoding="utf-8"))
    stops = next(d for d in topo["directions"] if d["direction_id"] == DIRECTION_ID)["stops"]

    # bunching (run first so the normal scenario can highlight the same pair)
    fleet = build_fleet(seed)
    runs, cum_km, holds = simulate(stops, fleet, seed, incident=INCIDENT)
    arrivals = arrival_rows(runs, stops, cum_km, seed)
    detection = detect_bunching(arrivals)
    if detection is None:
        raise SystemExit("Bunching scenario did not trigger the rule; adjust INCIDENT or the seed.")

    policy = make_hold_policy(detection)
    m_fleet = build_fleet(seed)
    m_runs, _, m_holds = simulate(stops, m_fleet, seed, incident=INCIDENT, hold_policy=policy)
    first_hold = m_holds[0] if m_holds else None
    directives = [
        {
            "bus_plate": detection["leader_plate"],
            "role": "LEADER",
            "action": "PROCEED_NO_HOLD",
            "text": "Continue service. No holding. Keep dwell to boarding/alighting only.",
        },
        {
            "bus_plate": detection["follower_plate"],
            "role": "FOLLOWER",
            "action": "HOLD",
            "stop_code": first_hold["stop_code"] if first_hold else None,
            "hold_seconds": first_hold["hold_seconds"] if first_hold else None,
            "text": (f"Hold {first_hold['hold_seconds']} s at stop {first_hold['stop_code']}, then continue."
                     if first_hold else "No approved control stop reachable; monitor only."),
        },
    ]
    pair = (detection["leader_plate"], detection["follower_plate"])

    n_fleet = build_fleet(seed)
    n_runs, _, n_holds = simulate(stops, n_fleet, seed)
    if detect_bunching(arrival_rows(n_runs, stops, cum_km, seed)) is not None:
        raise SystemExit("Normal scenario triggered the bunching rule; it must not.")
    normal = write_scenario("normal", "Weekday AM peak, no disruption.",
                            stops, n_fleet, n_runs, cum_km, n_holds, seed, highlight=pair)
    bunching = write_scenario(
        "bunching",
        f"Vehicle breakdown on Teck Whye Ave delays {pair[0]}; {pair[1]} catches up.",
        stops, fleet, runs, cum_km, holds, seed, incident=INCIDENT, detection=detection,
        directives=directives, highlight=pair)
    mitigated = write_scenario(
        "bunching_mitigated",
        f"Same as bunching, with the follower {pair[1]} held at control stops after controller approval.",
        stops, m_fleet, m_runs, cum_km, m_holds, seed, incident=INCIDENT, detection=detection,
        directives=directives, highlight=pair)

    for s in (normal, bunching, mitigated):
        print(f"{s['scenario_id']:<30} wait={s['metrics']['passenger_wait_minutes']:>6} pax-min  "
              f"left_behind={s['metrics']['passengers_left_behind']:>4}  holds={len(s['applied_holds'])}")
    d = detection
    print(f"\nBunching confirmed at stop {d['monitored_stop']} {d['confirmed_at']}: "
          f"leader {d['leader_plate']}, follower {d['follower_plate']}, "
          f"headways {[o['headway_s'] for o in d['observations']]} s (threshold {d['threshold_s']:.0f} s)")


if __name__ == "__main__":
    main()
