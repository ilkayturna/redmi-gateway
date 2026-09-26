"""Add one already uploaded iOS build to the owner's internal TestFlight group."""

import base64
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request

import jwt


API = "https://api.appstoreconnect.apple.com/v1"
APP_ID = "6816340544"
GROUP_ID = "2268c752-72d2-4f2f-8b7e-a707f3567b01"
BUILD_NUMBER = os.environ["ASC_BUILD_NUMBER"].strip()
KEY_ID = os.environ["ASC_API_KEY_ID"]
ISSUER_ID = os.environ["ASC_ISSUER_ID"]
PRIVATE_KEY = base64.b64decode(os.environ["ASC_API_KEY_B64"]).decode("utf-8")

if not BUILD_NUMBER.isdecimal():
    raise SystemExit("Build number must contain only digits")


def request(method: str, path: str, body: dict | None = None) -> dict:
    now = int(time.time())
    token = jwt.encode(
        {"iss": ISSUER_ID, "iat": now, "exp": now + 300, "aud": "appstoreconnect-v1"},
        PRIVATE_KEY,
        algorithm="ES256",
        headers={"kid": KEY_ID, "typ": "JWT"},
    )
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(
        API + path,
        data=data,
        method=method,
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/json",
            "Content-Type": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as response:
            payload = response.read()
            return json.loads(payload) if payload else {}
    except urllib.error.HTTPError as error:
        # Do not echo the request headers or bearer token into CI logs.
        try:
            detail = json.load(error).get("errors", [{}])[0]
            message = f"{detail.get('code', 'error')}: {detail.get('title', 'request failed')}"
        except (ValueError, IndexError, AttributeError):
            message = "request failed"
        raise RuntimeError(f"Apple API returned HTTP {error.code} ({message})") from None


def group_has_build(build_id: str) -> bool:
    path = f"/betaGroups/{GROUP_ID}/builds?limit=200"
    while path:
        page = request("GET", path)
        if any(item["id"] == build_id for item in page.get("data", [])):
            return True
        next_url = page.get("links", {}).get("next")
        path = next_url.removeprefix(API) if next_url else ""
    return False


def main() -> None:
    query = urllib.parse.urlencode(
        {"filter[app]": APP_ID, "filter[version]": BUILD_NUMBER, "limit": "10"}
    )
    deadline = time.monotonic() + 15 * 60
    while True:
        builds = request("GET", f"/builds?{query}").get("data", [])
        matches = [
            build for build in builds
            if build.get("attributes", {}).get("version") == BUILD_NUMBER
        ]
        if len(matches) > 1:
            raise RuntimeError("Multiple matching builds; refusing an ambiguous TestFlight assignment")
        if matches:
            build = matches[0]
            state = build.get("attributes", {}).get("processingState")
            print(f"Build {BUILD_NUMBER}: {state}", flush=True)
            if state == "VALID":
                break
            if state in {"FAILED", "INVALID"}:
                raise RuntimeError(f"Apple rejected build {BUILD_NUMBER}: {state}")
        else:
            print(f"Build {BUILD_NUMBER}: waiting for App Store Connect", flush=True)
        if time.monotonic() >= deadline:
            raise TimeoutError("Build did not finish processing within 15 minutes")
        time.sleep(30)

    build_id = build["id"]
    if not group_has_build(build_id):
        request(
            "POST",
            f"/betaGroups/{GROUP_ID}/relationships/builds",
            {"data": [{"type": "builds", "id": build_id}]},
        )
    if not group_has_build(build_id):
        raise RuntimeError("Build assignment was not visible in the TestFlight group")
    print(f"Build {BUILD_NUMBER} is in the owner's internal TestFlight group", flush=True)


if __name__ == "__main__":
    main()
