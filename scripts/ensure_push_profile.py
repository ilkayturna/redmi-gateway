"""Make the iPhone app's App Store profile include Push Notifications (VoIP calls while away).

Enables the capability on the app ID when missing, finds the distribution certificate the
pipeline signs with (by serial), and creates or reuses an App Store profile for both. The
profile is installed for xcodebuild and its name is written to GITHUB_OUTPUT as
`push_profile`. Nothing secret is printed.
"""

import base64
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request

import jwt

API = "https://api.appstoreconnect.apple.com/v1"
BUNDLE_IDENTIFIER = "com.ilkay.redmigateway.ios"
PROFILE_NAME = "Telefon Gecidi App Store Push"
KEY_ID = os.environ["ASC_API_KEY_ID"]
ISSUER_ID = os.environ["ASC_ISSUER_ID"]
PRIVATE_KEY = base64.b64decode(os.environ["ASC_API_KEY_B64"]).decode("utf-8")
CERT_SERIAL = os.environ.get("CERT_SERIAL", "").strip().upper().lstrip("0")


def request(method: str, path: str, body: dict | None = None) -> dict:
    now = int(time.time())
    token = jwt.encode(
        {"iss": ISSUER_ID, "iat": now, "exp": now + 300, "aud": "appstoreconnect-v1"},
        PRIVATE_KEY,
        algorithm="ES256",
        headers={"kid": KEY_ID, "typ": "JWT"},
    )
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(API + path, data=data, method=method, headers={
        "Authorization": f"Bearer {token}",
        "Accept": "application/json",
        "Content-Type": "application/json",
    })
    try:
        with urllib.request.urlopen(req, timeout=30) as response:
            payload = response.read()
            return json.loads(payload) if payload else {}
    except urllib.error.HTTPError as error:
        try:
            detail = json.load(error).get("errors", [{}])[0]
            message = f"{detail.get('code', 'error')}: {detail.get('title', 'request failed')}"
        except (ValueError, IndexError, AttributeError):
            message = "request failed"
        raise RuntimeError(f"Apple API {method} {path.split('?')[0]} returned HTTP {error.code} ({message})") from None


def bundle_id() -> str:
    query = urllib.parse.urlencode({"filter[identifier]": BUNDLE_IDENTIFIER, "limit": 200})
    for item in request("GET", f"/bundleIds?{query}")["data"]:
        if item["attributes"]["identifier"] == BUNDLE_IDENTIFIER:
            return item["id"]
    raise RuntimeError("app ID not found")


def ensure_push(bundle: str) -> None:
    capabilities = request("GET", f"/bundleIds/{bundle}/bundleIdCapabilities?limit=200")["data"]
    if any(c["attributes"].get("capabilityType") == "PUSH_NOTIFICATIONS" for c in capabilities):
        print("Push Notifications already enabled on the app ID")
        return
    request("POST", "/bundleIdCapabilities", {"data": {
        "type": "bundleIdCapabilities",
        "attributes": {"capabilityType": "PUSH_NOTIFICATIONS"},
        "relationships": {"bundleId": {"data": {"type": "bundleIds", "id": bundle}}},
    }})
    print("Push Notifications enabled on the app ID")


def distribution_certificate() -> str:
    certificates = []
    for kind in ("DISTRIBUTION", "IOS_DISTRIBUTION"):
        query = urllib.parse.urlencode({"filter[certificateType]": kind, "limit": 200})
        certificates += request("GET", f"/certificates?{query}")["data"]
    if CERT_SERIAL:
        for cert in certificates:
            if cert["attributes"].get("serialNumber", "").upper().lstrip("0") == CERT_SERIAL:
                return cert["id"]
    if len(certificates) == 1:
        return certificates[0]["id"]
    raise RuntimeError(f"cannot tell which of {len(certificates)} distribution certificates signs builds")


def profile(bundle: str, certificate: str) -> dict:
    query = urllib.parse.urlencode({"filter[name]": PROFILE_NAME, "limit": 50})
    for item in request("GET", f"/profiles?{query}")["data"]:
        if item["attributes"].get("profileState") == "ACTIVE":
            print("Reusing the active push profile")
            return item["attributes"]
        request("DELETE", f"/profiles/{item['id']}")
    created = request("POST", "/profiles", {"data": {
        "type": "profiles",
        "attributes": {"name": PROFILE_NAME, "profileType": "IOS_APP_STORE"},
        "relationships": {
            "bundleId": {"data": {"type": "bundleIds", "id": bundle}},
            "certificates": {"data": [{"type": "certificates", "id": certificate}]},
        },
    }})
    print("Created a new push profile")
    return created["data"]["attributes"]


def main() -> None:
    bundle = bundle_id()
    certificate = distribution_certificate()
    ensure_push(bundle)
    attributes = profile(bundle, certificate)
    content = base64.b64decode(attributes["profileContent"])
    if b"aps-environment" not in content:
        raise RuntimeError("the profile does not carry the push entitlement yet")
    folder = os.path.expanduser("~/Library/MobileDevice/Provisioning Profiles")
    os.makedirs(folder, exist_ok=True)
    with open(os.path.join(folder, f"{attributes['uuid']}.mobileprovision"), "wb") as handle:
        handle.write(content)
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as out:
        out.write(f"push_profile={PROFILE_NAME}\n")
    print("Push profile installed")


if __name__ == "__main__":
    main()
