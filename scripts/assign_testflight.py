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
# Apple's processing has taken longer than 15 minutes. A timeout fails the run, mails the owner and
# leaves the build outside the group, which matters most for the unattended scheduled refresh.
PROCESSING_DEADLINE_MINUTES = 30


def request(method: str, path: str, body: dict | None = None) -> dict:
    # Credentials are read per call, not at import, so the refresh guard can import this module
    # (and its tests can import the guard) without any secret in the environment.
    key_id = os.environ["ASC_API_KEY_ID"]
    issuer_id = os.environ["ASC_ISSUER_ID"]
    private_key = base64.b64decode(os.environ["ASC_API_KEY_B64"]).decode("utf-8")
    now = int(time.time())
    token = jwt.encode(
        {"iss": issuer_id, "iat": now, "exp": now + 300, "aud": "appstoreconnect-v1"},
        private_key,
        algorithm="ES256",
        headers={"kid": key_id, "typ": "JWT"},
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


def group_builds():
    """Every build resource in the internal group, page by page, attributes included."""
    path = f"/betaGroups/{GROUP_ID}/builds?limit=200"
    while path:
        page = request("GET", path)
        yield from page.get("data", [])
        next_url = page.get("links", {}).get("next")
        path = next_url.removeprefix(API) if next_url else ""


def group_has_build(build_id: str) -> bool:
    return any(item["id"] == build_id for item in group_builds())


def main() -> None:
    build_number = os.environ["ASC_BUILD_NUMBER"].strip()
    if not build_number.isdecimal():
        raise SystemExit("Build number must contain only digits")
    query = urllib.parse.urlencode(
        {"filter[app]": APP_ID, "filter[version]": build_number, "limit": "10"}
    )
    deadline = time.monotonic() + PROCESSING_DEADLINE_MINUTES * 60
    while True:
        builds = request("GET", f"/builds?{query}").get("data", [])
        matches = [
            build for build in builds
            if build.get("attributes", {}).get("version") == build_number
        ]
        if len(matches) > 1:
            raise RuntimeError("Multiple matching builds; refusing an ambiguous TestFlight assignment")
        if matches:
            build = matches[0]
            state = build.get("attributes", {}).get("processingState")
            print(f"Build {build_number}: {state}", flush=True)
            if state == "VALID":
                break
            if state in {"FAILED", "INVALID"}:
                raise RuntimeError(f"Apple rejected build {build_number}: {state}")
        else:
            print(f"Build {build_number}: waiting for App Store Connect", flush=True)
        if time.monotonic() >= deadline:
            raise TimeoutError(
                f"Build did not finish processing within {PROCESSING_DEADLINE_MINUTES} minutes"
            )
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
    print(f"Build {build_number} is in the owner's internal TestFlight group", flush=True)


if __name__ == "__main__":
    main()
