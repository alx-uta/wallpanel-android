#!/usr/bin/env python3
"""Checks WallPanel's MQTT discovery end to end, against a real broker and Home Assistant.

Publishing a well formed discovery config and Home Assistant actually building a working
entity out of it are two different claims. The unit tests cover the first; only a live
Home Assistant covers the second, because it is the one that validates the schema,
renders the templates and decides whether an entity is available.

What this checks:

  1. Which discovery configs the application currently has retained on the broker.
  2. That Home Assistant created a device, and an entity for every one of those configs.
  3. That no entity is unavailable, which is what a wrong availability topic looks like.
  4. That the states Home Assistant holds match what the published payloads should
     render to, which is what catches a wrong value_template.

With --exercise it also calls Home Assistant services against the controls and checks the
device reacted. That is the only way to cover the command templates, because Home
Assistant is what renders them into the JSON the application receives.

Usage:
    ./tools/ha-verify.py                    # uses clientId from local.testconfig.properties
    ./tools/ha-verify.py --client-id wptest
    ./tools/ha-verify.py --device "WallPanel Test"
    ./tools/ha-verify.py --exercise         # also drives the controls, then restores them

Reads local.testconfig.properties for the broker and Home Assistant details, including
`hassToken`. Needs Python 3 and nothing else. Exits non-zero if any check fails.
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mqtt_minimal import MqttClient, MqttError  # noqa: E402

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CONFIG_FILE = os.path.join(REPO_ROOT, "local.testconfig.properties")

# The longest state Home Assistant will hold, which is where MqttDiscovery cuts the
# Current URL sensor.
URL_STATE_MAX_LENGTH = 255


def read_config():
    if not os.path.exists(CONFIG_FILE):
        sys.exit("error: %s not found.\n"
                 "       cp local.testconfig.properties.example local.testconfig.properties"
                 % CONFIG_FILE)
    config = {}
    with open(CONFIG_FILE) as handle:
        for line in handle:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                key, value = line.split("=", 1)
                config[key.strip()] = value.strip()
    return config


class HomeAssistant:
    def __init__(self, base_url, token):
        self.base_url = base_url.rstrip("/")
        self.token = token

    def _request(self, path, payload=None):
        request = urllib.request.Request(
            self.base_url + path,
            data=json.dumps(payload).encode() if payload is not None else None,
            headers={"Authorization": "Bearer " + self.token,
                     "Content-Type": "application/json"},
        )
        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                body = response.read().decode()
        except urllib.error.HTTPError as e:
            raise RuntimeError("%s -> HTTP %s: %s" % (path, e.code, e.read().decode()[:200]))
        except urllib.error.URLError as e:
            raise RuntimeError("could not reach Home Assistant at %s (%s)" % (self.base_url, e))
        try:
            return json.loads(body)
        except json.JSONDecodeError:
            return body

    def template(self, template):
        """Renders a template, returning it as text. This is how the device and entity
        registries are reached without a websocket connection, since neither is exposed
        over REST."""
        rendered = self._request("/api/template", {"template": template})
        # A rendered template that happens to be valid JSON comes back already decoded.
        return rendered if isinstance(rendered, str) else json.dumps(rendered)

    def template_list(self, template):
        rendered = self._request("/api/template", {"template": template})
        return rendered if isinstance(rendered, list) else json.loads(rendered)

    def states(self):
        return {s["entity_id"]: s for s in self._request("/api/states")}

    def state_of(self, entity_id):
        return self._request("/api/states/" + entity_id)["state"]

    def call(self, domain, service, entity_id, **data):
        self._request("/api/services/%s/%s" % (domain, service),
                      dict(data, entity_id=entity_id))

    def wait_for_state(self, entity_id, want, timeout=25, tolerance=0):
        """Home Assistant publishes the command, the device acts on it and publishes new
        state, and only then does the entity settle -- so this is a poll, not a read.

        A tolerance accepts a number landing near the value asked for, which is what the
        controls backed by a stepped device setting can do.
        """
        deadline = time.time() + timeout
        state = None
        while time.time() < deadline:
            state = self.state_of(entity_id)
            if state == str(want):
                return True, state
            if tolerance:
                try:
                    if abs(float(state) - float(want)) <= tolerance:
                        return True, state
                except (TypeError, ValueError):
                    pass
            time.sleep(1)
        return False, state

    def version(self):
        return self._request("/api/config").get("version")


def expected_state(config, payloads):
    """What Home Assistant should be holding, derived from the published payload.

    Only the template shapes MqttDiscovery emits are handled; anything else returns None
    so the check is skipped rather than reported as a mismatch.
    """
    topic = config.get("state_topic")
    template = config.get("value_template")
    if not topic or not template or topic not in payloads:
        return None
    try:
        data = json.loads(payloads[topic])
    except json.JSONDecodeError:
        return None

    template = template.strip()
    if template.startswith("{% if value_json."):
        field = template.split("value_json.", 1)[1].split(" ", 1)[0].rstrip("%}").strip()
        on = template.split("%}", 1)[1].split("{%", 1)[0]
        off = template.rsplit("%}", 2)[1].split("{%", 1)[0]
        if field not in data:
            return None
        # The payloads render ON/OFF, which is what state_on/state_off and payload_on/
        # payload_off are set to, and Home Assistant holds the result as on/off.
        return (on if data[field] else off).strip().lower()
    if template.startswith("{{ value_json."):
        field = template[len("{{ value_json."):].split("}", 1)[0].split("|")[0].strip()
        if field not in data:
            return None
        value = data[field]
        if "| float" in template:
            return str(float(value))
        return str(value)
    return None


def exercise(ha, by_name, device_name):
    """Drives the controls through Home Assistant and checks the device acted on them.

    Everything changed here is put back afterwards -- these run against real kiosk
    devices. Text to speech is left alone because verifying it means making the device
    talk out loud, and the camera because turning it on is not something a test should
    do to somebody's wall panel.
    """
    failures = []

    def entity(name):
        state = by_name.get("%s %s" % (device_name, name))
        return state["entity_id"] if state else None

    print()
    print("Driving controls from Home Assistant:")
    restore = {}

    # The write-only text controls have no state to read back, so the URL to return to
    # comes from the Current URL sensor instead. Without this the device would be left
    # sitting on whatever the test navigated it to.
    current_url_entity = entity("Current URL")
    original_url = ha.state_of(current_url_entity) if current_url_entity else None
    # That sensor cuts the URL at the longest state Home Assistant accepts, so a URL at
    # the limit is not enough to navigate back to.
    url_restorable = original_url not in (None, "unknown", "unavailable") \
        and len(original_url) < URL_STATE_MAX_LENGTH

    # Volume carries a tolerance because it is a percentage of however many steps the
    # device's media stream has: asking for 60% of a 7 step stream gives 4 steps, which
    # reads back as 57%. One step is 15 percentage points at that end of the range.
    checks = [
        ("Brightness", "number", "set_value", {"value": 200}, 200, 0),
        ("Volume", "number", "set_value", {"value": 60}, 60, 15),
        ("Screensaver", "switch", "turn_on", {}, "on", 0),
        ("Screensaver", "switch", "turn_off", {}, "off", 0),
    ]
    for name, domain, service, data, want, tolerance in checks:
        entity_id = entity(name)
        if entity_id is None:
            print("  %-28s SKIPPED (no entity)" % name)
            continue
        restore.setdefault(entity_id, (domain, ha.state_of(entity_id)))
        try:
            ha.call(domain, service, entity_id, **data)
        except RuntimeError as e:
            failures.append("%s: service call failed (%s)" % (entity_id, e))
            print("  %-28s FAILED to call: %s" % (name, e))
            continue
        ok, got = ha.wait_for_state(entity_id, want, tolerance=tolerance)
        if not ok:
            failures.append("%s did not reach %r after %s.%s (stuck at %r)"
                            % (entity_id, want, domain, service, got))
        print("  %-28s %-22s -> %-22s %s"
              % (name, "%s.%s" % (domain, service), got, "OK" if ok else "FAIL"))

    # Navigating is checked through the Current URL sensor rather than the text entity,
    # which is write-only and would only ever report back what was typed into it.
    url_entity = entity("Navigate URL")
    if url_entity and current_url_entity and not url_restorable:
        print("  %-28s SKIPPED (current URL is %r, nothing to navigate back to)"
              % ("Navigate URL", original_url))
    elif url_entity and current_url_entity and urllib.parse.urlsplit(original_url).fragment:
        # A URL differing only after the '#' is the same document to a browser, so it would
        # navigate nowhere and the check would read as a failure.
        print("  %-28s SKIPPED (current URL carries a fragment)" % "Navigate URL")
    elif url_entity and current_url_entity:
        # Added as a query parameter rather than glued on, which a URL that already has a
        # query string would turn into a second '?'.
        parts = urllib.parse.urlsplit(original_url)
        query = parts.query + "&ha-verify" if parts.query else "ha-verify"
        target = urllib.parse.urlunsplit((parts.scheme, parts.netloc, parts.path, query, ""))
        ha.call("text", "set_value", url_entity, value=target)
        ok, got = ha.wait_for_state(current_url_entity, target[:URL_STATE_MAX_LENGTH])
        if not ok:
            failures.append("%s did not follow the Navigate URL control (stuck at %r)"
                            % (current_url_entity, got))
        print("  %-28s %-22s -> %-22s %s"
              % ("Navigate URL", "text.set_value", got, "OK" if ok else "FAIL"))

    # A button press has no state to settle on, so the check is that the device is still
    # connected and answering afterwards rather than that something changed.
    for name in ("Reload Page", "Clear Cache"):
        entity_id = entity(name)
        if entity_id is None:
            continue
        try:
            ha.call("button", "press", entity_id)
            print("  %-28s button.press           -> accepted" % name)
        except RuntimeError as e:
            failures.append("%s: %s" % (entity_id, e))
            print("  %-28s button.press           -> FAILED %s" % (name, e))

    # The shell box is the one control whose effect comes back as its own sensor.
    shell, result = entity("Shell Command"), entity("Shell Result")
    if shell and result:
        ha.call("text", "set_value", shell, value="getprop ro.product.model")
        deadline = time.time() + 25
        state = "unknown"
        while time.time() < deadline:
            state = ha.state_of(result)
            if state not in ("unknown", "unavailable", ""):
                break
            time.sleep(1)
        ok = state not in ("unknown", "unavailable", "")
        if not ok:
            failures.append("%s never received a result after a shell command" % result)
        print("  %-28s text.set_value         -> %-22s %s"
              % ("Shell Command", state, "OK" if ok else "FAIL"))

    print("  restoring...")
    for entity_id, (domain, previous) in restore.items():
        try:
            if domain == "number":
                ha.call(domain, "set_value", entity_id, value=float(previous))
            elif domain == "switch":
                ha.call(domain, "turn_on" if previous == "on" else "turn_off", entity_id)
        except (RuntimeError, ValueError):
            pass
        time.sleep(1)
    if url_entity and url_restorable:
        ha.call("text", "set_value", url_entity, value=original_url)
        # Longer than the checks above: Clear Cache was pressed a moment ago, so the
        # dashboard is loading from scratch rather than out of the browser cache, which on
        # a slow tablet takes well past the usual wait.
        restored, got = ha.wait_for_state(current_url_entity, original_url, timeout=120)
        if not restored:
            failures.append("could not put the dashboard back to %r (left on %r)"
                            % (original_url, got))
        print("  dashboard back on %s" % got)
    return failures


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--client-id", help="MQTT client id (default: clientId from the config)")
    parser.add_argument("--base-topic",
                        help="command and state topic base (default: baseTopic from the config, "
                             "or wallpanel/<client id>/ when --client-id is given)")
    parser.add_argument("--discovery-topic", default="homeassistant",
                        help="discovery base topic (default: homeassistant)")
    parser.add_argument("--device", help="Home Assistant device name (default: read from the configs)")
    parser.add_argument("--seconds", type=float, default=8.0,
                        help="how long to gather retained messages (default: 8)")
    parser.add_argument("--exercise", action="store_true",
                        help="also drive the controls from Home Assistant and check the "
                             "device reacts; changes device state and puts it back")
    args = parser.parse_args()

    config = read_config()
    client_id = args.client_id or config.get("clientId")
    token = config.get("hassToken", "")
    if not token:
        sys.exit("error: hassToken is not set in local.testconfig.properties.\n"
                 "       See local.testconfig.properties.example for how to create one.")
    if not client_id:
        sys.exit("error: no client id given and clientId is not set in the config")

    base_url = config["hassUrl"].split("/lovelace")[0]
    # The state topics carry the payloads the entity states are checked against. A client
    # id given on the command line belongs to a different device than the one in the
    # config, so its base topic is the default one built from that id rather than the
    # config's, unless --base-topic says otherwise.
    base_topic = args.base_topic or (config.get("baseTopic") if not args.client_id else None) \
        or "wallpanel/%s/" % client_id
    failures = []

    # 1. what the application currently advertises
    try:
        with MqttClient(config["broker"], int(config.get("brokerPort", 1883)),
                        client_id="wallpanel-ha-verify",
                        username=config.get("brokerUsername") or None,
                        password=config.get("brokerPass") or None) as mqtt:
            configs = mqtt.collect_retained(
                "%s/+/%s/+/config" % (args.discovery_topic, client_id), seconds=args.seconds)
            payloads = mqtt.collect_retained("%s#" % base_topic, seconds=args.seconds)
    except MqttError as e:
        sys.exit("error: %s" % e)

    if not configs:
        sys.exit("error: no discovery configs retained for client id %r.\n"
                 "       Is MQTT Discovery enabled on the device, and is it connected?" % client_id)

    # Without these the state comparison has nothing to compare against and every entity
    # passes on the strength of being present alone.
    if not payloads:
        sys.exit("error: nothing retained under %r, so the states could not be checked.\n"
                 "       Pass --base-topic with the base topic the device publishes to."
                 % ("%s#" % base_topic))

    parsed = {}
    for topic, payload in configs.items():
        parts = topic.split("/")
        parsed[(parts[1], parts[3])] = json.loads(payload)
    print("Discovery configs retained for %r: %d" % (client_id, len(parsed)))

    device_name = args.device or next(
        (c["device"]["name"] for c in parsed.values() if "device" in c), None)
    print("Home Assistant: %s" % base_url)

    ha = HomeAssistant(base_url, token)
    print("  version: %s" % ha.version())

    # 2. the device and its entities
    device_id = ha.template("{{ device_id('%s') }}" % device_name)
    if not device_id or device_id == "None":
        sys.exit("FAIL: Home Assistant has no device named %r.\n"
                 "      The configs are on the broker, so this is Home Assistant rejecting\n"
                 "      them -- check its logs for mqtt discovery errors." % device_name)
    entity_ids = ha.template_list("{{ device_entities('%s') | list | tojson }}" % device_id)
    print("  device %r -> %s (%d entities)" % (device_name, device_id, len(entity_ids)))

    states = ha.states()
    by_name = {}
    for entity_id in entity_ids:
        state = states.get(entity_id)
        if state:
            by_name[state["attributes"].get("friendly_name", entity_id)] = state

    # 3 and 4. every advertised entity exists, is available, and holds the right state
    print()
    print("%-14s %-18s %-34s %s" % ("COMPONENT", "OBJECT ID", "ENTITY", "STATE"))
    for (component, object_id), entity_config in sorted(parsed.items()):
        name = entity_config.get("name", "")
        full_name = name if name.startswith(device_name) else "%s %s" % (device_name, name)
        state = by_name.get(full_name)
        if component == "tag":
            continue  # tags are scanner triggers, not entities
        if state is None:
            failures.append("%s/%s (%r) has no entity in Home Assistant" % (component, object_id, full_name))
            print("%-14s %-18s %-34s %s" % (component, object_id, "-", "!! MISSING"))
            continue

        note = ""
        if state["state"] == "unavailable":
            failures.append("%s is unavailable (check the availability topic)" % state["entity_id"])
            note = "  !! UNAVAILABLE"
        else:
            want = expected_state(entity_config, payloads)
            if want is not None and state["state"] != want:
                failures.append("%s is %r, expected %r from the published payload"
                                % (state["entity_id"], state["state"], want))
                note = "  !! expected %r" % want
        print("%-14s %-18s %-34s %s%s" % (component, object_id, state["entity_id"], state["state"], note))

    if args.exercise:
        failures += exercise(ha, by_name, device_name)

    print()
    if failures:
        print("FAILURES (%d):" % len(failures))
        for failure in failures:
            print("  - %s" % failure)
        return 1
    print("OK: %d entities, all present, available and matching their published payloads."
          % (len(parsed) - sum(1 for c, _ in parsed if c == "tag")))
    return 0


if __name__ == "__main__":
    sys.exit(main())
