"""Validate a running demo and the checked-in spec. Install openapi-spec-validator first."""
import argparse
import json
import time
from urllib import request, error
from pathlib import Path
from uuid import uuid4

from jsonschema import Draft202012Validator, FormatChecker
from openapi_spec_validator import validate


def verify(base, token):
    def call(path, data=None, key=None):
        headers = {"Authorization": "Bearer " + token}
        if data is not None:
            headers.update({"Idempotency-Key": key or str(uuid4()), "Content-Type": "application/json"})
        return request.urlopen(request.Request(base + path, json.dumps(data).encode() if data is not None else None, headers), timeout=8)

    with call("/openapi.json") as response:
        content = response.read()
    assert content == Path("docs/forwarder.openapi.json").read_bytes()
    spec = json.loads(content)
    validate(spec)
    checked = set()

    def validate_payload(payload, schema):
        Draft202012Validator({**schema, "components": spec["components"]}, format_checker=FormatChecker()).validate(payload)

    def operation(template, path=None, body=None, expected=200):
        method = "get" if body is None else "post"
        op = spec["paths"][template][method]
        try:
            resp = call("/api/v1" + (path or template), body)
        except error.HTTPError as e:
            resp = e
        with resp:
            assert resp.status == expected, (path, resp.status)
            payload = json.load(resp)
        response = op["responses"][str(expected)]
        if "$ref" in response:
            response = spec["components"]["responses"][response["$ref"].split("/")[-1]]
        validate_payload(payload, next(iter(response["content"].values()))["schema"])
        checked.add(op["operationId"])
        return payload

    dashboard = operation("/dashboard", "/dashboard?window=EVENT")
    sessions = operation("/sessions")
    sid = "s_100"
    prefix = "/sessions/" + sid
    template = "/sessions/{sessionId}"
    detail = operation(template, prefix)
    pause = operation(template + "/control", prefix + "/control", {"expectedVersion": detail["session"]["version"], "reason": "Contract verification", "action": "PAUSE"})
    operation(template + "/control", prefix + "/control", {"expectedVersion": pause["version"], "reason": "Continue demo", "action": "RESUME"})
    for _ in range(120):
        detail = operation(template, prefix)
        if detail.get("booking", {}).get("state") == "CONFIRMED":
            break
        time.sleep(.5)
    messages = operation(template + "/messages", prefix + "/messages")
    operation(template + "/events", prefix + "/events")
    with call("/api/v1" + prefix + "/attachments/" + sid + "_manifest") as response:
        assert response.headers["Content-Type"].startswith("application/octet-stream")
        assert b"100 kg" in response.read()
        checked.add(spec["paths"][template + "/attachments/{attachmentId}"]["get"]["operationId"])
    tariffs = operation("/tariffs")["items"]
    assert len(tariffs) == 10
    for tariff in tariffs:
        operation("/tariffs/{tariffId}/lanes", "/tariffs/" + tariff["id"] + "/lanes")
    operation("/scores")
    for scenario in [{"scenario": "RECORDED"}, {"scenario": "EXCLUDE_SOURCE", "source": "TARIFF"}]:
        operation("/replays", body={"sessionId": sid, **scenario})
    operation(template, "/sessions/no-such-session", expected=404)
    with call("/api/v1/events/stream?after=0") as stream:
        assert stream.headers["Content-Type"].startswith("text/event-stream")
        while True:
            line = stream.readline()
            assert line
            if line.startswith(b"data:"):
                event = json.loads(line[5:])
                validate_payload(event, {"$ref": "#/components/schemas/Event"})
                checked.add(spec["paths"]["/events/stream"]["get"]["operationId"])
                break
    expected = {op["operationId"] for path in spec["paths"].values() for op in path.values()}
    assert checked == expected
    print(f"{len(checked)} operations passed, {sum(t['laneCount'] for t in tariffs)} tariff lanes, quote {detail['quote']['total']}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://127.0.0.1:8080")
    args = parser.parse_args()
    verify(args.base, "demo")
