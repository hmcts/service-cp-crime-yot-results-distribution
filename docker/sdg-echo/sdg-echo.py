#!/usr/bin/env python3
"""Close the local generation loop: turn every stubbed `generate-document` into a `document-available`.

LOCAL ONLY, and a test double rather than a model of systemdocgenerator. The real SDG renders the
payload and then publishes `public.systemdocgenerator.events.document-available` onto the shared
`public.event` topic; the WireMock stub can only answer 202, so without this helper a local run
would leave every batch sitting in GENERATING until the next run released it as stale, and the
event-driven completion path (the one the deployed service actually uses) would never be
exercised locally at all.

It polls WireMock's request journal for `generate-document` POSTs, and for each one it has not seen
before publishes the matching `document-available` event to Artemis over STOMP. The event is built
from the request the service actually sent, so the two fields that correlate it back to a batch,
`sourceCorrelationId` (the batch id) and `payloadFileServiceId`, are the service's own values and
not invented here; `originatingSource` is echoed for the same reason, since the listener keeps only
the events whose source is YotResultsDistributionService.

The published body is a framework **JsonEnvelope**, not the bare payload: every public event on
`public.event` carries a top-level `_metadata` object alongside the payload fields, and
`_metadata.name` - here `public.systemdocgenerator.events.document-available` - is the field a
framework listener reads to decide what it has been handed. Publishing the bare payload would give
the listener an envelope with no name. `_metadata.stream.id` carries the `sourceCorrelationId` the
command sent, so the envelope's stream is the batch the event belongs to.

STOMP rather than a JMS client because it needs no broker library: Artemis's default acceptor
multiplexes CORE, AMQP, STOMP, MQTT and OpenWire on 61616, a SEND frame's destination is the bare
address name (`public.event`, with NO `/topic/` prefix, because the default acceptor declares no
`multicastPrefix` and would treat the prefix as part of a second, wrongly named address), and a SEND
frame's custom headers arrive as message properties - which is what makes the `CPPNAME` selector the
service subscribes with match.

Environment (all with local defaults, see docker-compose.yml):
  WIREMOCK_URL, GENERATE_DOCUMENT_PATH, ARTEMIS_HOST, ARTEMIS_PORT, ARTEMIS_USER,
  ARTEMIS_PASSWORD, PUBLIC_EVENT_TOPIC, POLL_INTERVAL_SECONDS
"""

import json
import os
import socket
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone

WIREMOCK_URL = os.environ.get("WIREMOCK_URL", "http://wiremock:8080").rstrip("/")
GENERATE_DOCUMENT_PATH = os.environ.get(
    "GENERATE_DOCUMENT_PATH",
    "/systemdocgenerator-command-api/command/api/rest/systemdocgenerator/generate-document",
)
ARTEMIS_HOST = os.environ.get("ARTEMIS_HOST", "artemis")
ARTEMIS_PORT = int(os.environ.get("ARTEMIS_PORT", "61616"))
ARTEMIS_USER = os.environ.get("ARTEMIS_USER", "admin")
ARTEMIS_PASSWORD = os.environ.get("ARTEMIS_PASSWORD", "admin")
PUBLIC_EVENT_TOPIC = os.environ.get("PUBLIC_EVENT_TOPIC", "public.event")
POLL_INTERVAL_SECONDS = float(os.environ.get("POLL_INTERVAL_SECONDS", "1"))

DOCUMENT_AVAILABLE = "public.systemdocgenerator.events.document-available"
NULL = "\x00"


def log(message):
    print("[sdg-echo] {}".format(message), flush=True)


def now_iso():
    """ISO-8601 with an offset, the shape the event schema's date-time fields carry."""
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


def now_utc():
    """ISO-8601 UTC with the `Z` designator, the shape `_metadata.createdAt` carries."""
    return now_iso().replace("+00:00", "Z")


def journal():
    """The WireMock request journal, newest first. Empty on any error: the loop must not die."""
    try:
        with urllib.request.urlopen(WIREMOCK_URL + "/__admin/requests?limit=200", timeout=5) as answer:
            return json.load(answer).get("requests", [])
    except (urllib.error.URLError, OSError, ValueError) as problem:
        log("could not read the WireMock journal ({}); retrying".format(problem))
        return []


def document_available_from(generate_document):
    """The JsonEnvelope the real systemdocgenerator would publish for this generate-document command.

    A framework public event is `_metadata` plus the payload fields at the same level, so this is
    the envelope and not just the `document-available` payload: `_metadata.name` is what a listener
    reads to know which event it holds, and `_metadata.stream.id` is the batch the event belongs to.
    """
    return {
        "_metadata": {
            "id": str(uuid.uuid4()),
            "name": DOCUMENT_AVAILABLE,
            "createdAt": now_utc(),
            "source": "systemdocgenerator",
            # The stream an event belongs to. The batch id the command correlated on, so a reader
            # of the envelope alone can still tell which batch was rendered.
            "stream": {"id": generate_document.get("sourceCorrelationId")},
            "correlation": {"client": "sdg-echo"},
        },
        "payloadFileServiceId": generate_document["payloadFileServiceId"],
        "templateIdentifier": generate_document.get("templateIdentifier", "OEE_Layout5"),
        "conversionFormat": generate_document.get("conversionFormat", "pdf"),
        "requestedTime": now_iso(),
        "documentFileServiceId": str(uuid.uuid4()),
        "generatedTime": now_iso(),
        "generateVersion": 1,
        # Correlation. Absent from the command only if the service stopped sending it, in which
        # case the listener could not match the event either - so it is copied, never defaulted.
        "sourceCorrelationId": generate_document.get("sourceCorrelationId"),
        "originatingSource": generate_document.get("originatingSource"),
    }


def frame(command, headers, body=""):
    lines = [command] + ["{}:{}".format(key, value) for key, value in headers.items()]
    return ("\n".join(lines) + "\n\n" + body + NULL).encode("utf-8")


def publish(envelope):
    """One STOMP connection per event: this is a helper for a local loop, not a throughput path."""
    body = json.dumps(envelope)
    with socket.create_connection((ARTEMIS_HOST, ARTEMIS_PORT), timeout=10) as broker:
        broker.sendall(frame("CONNECT", {
            "accept-version": "1.2",
            "host": "/",
            "login": ARTEMIS_USER,
            "passcode": ARTEMIS_PASSWORD,
        }))
        answer = broker.recv(4096).decode("utf-8", "replace")
        if not answer.startswith("CONNECTED"):
            raise RuntimeError("broker refused the STOMP connection: {}".format(answer.strip()))

        broker.sendall(frame("SEND", {
            # The bare address name, NOT `/topic/public.event`: Artemis only strips a `/topic/`
            # prefix on an acceptor that declares `multicastPrefix=/topic/`, and the default one
            # does not - the prefix would become part of a second, wrongly named address, and the
            # service's subscription to `public.event` would hear nothing. A bare name routes by
            # the address's own routing type, and docker-compose.yml creates `public.event`
            # multicast for exactly this reason.
            "destination": PUBLIC_EVENT_TOPIC,
            # The property the service's JMS selector filters on, and the only reason a subscriber
            # to a topic the whole estate publishes to ever sees this message. It repeats
            # `_metadata.name` in the body, exactly as the framework's own publisher does.
            "CPPNAME": DOCUMENT_AVAILABLE,
            # No `content-length`, deliberately: Artemis reads a STOMP frame that carries one as a
            # BytesMessage and one that does not as a TextMessage, and the estate publishes public
            # events as JSON TextMessages. The listener's converter would otherwise be handed bytes.
            "content-type": "application/json",
        }, body))
        broker.sendall(frame("DISCONNECT", {"receipt": str(uuid.uuid4())}))


def main():
    log("watching {} for POSTs to {}".format(WIREMOCK_URL, GENERATE_DOCUMENT_PATH))
    log("publishing {} as a JsonEnvelope to {}:{} address {} (bare name, no /topic/ prefix)".format(
        DOCUMENT_AVAILABLE, ARTEMIS_HOST, ARTEMIS_PORT, PUBLIC_EVENT_TOPIC))

    # Everything already in the journal at start-up is history: echoing it would publish a second
    # event for a batch that completed before this container was restarted.
    echoed = {entry.get("id") for entry in journal()}
    log("ignoring {} request(s) already in the journal".format(len(echoed)))

    while True:
        for entry in reversed(journal()):
            identifier = entry.get("id")
            request = entry.get("request", {})
            if identifier in echoed:
                continue
            echoed.add(identifier)
            if request.get("method") != "POST" or request.get("url", "").split("?")[0] != GENERATE_DOCUMENT_PATH:
                continue

            try:
                command = json.loads(request.get("body") or "{}")
                envelope = document_available_from(command)
                publish(envelope)
                log("echoed generate-document payloadFileServiceId={} as document-available "
                    "documentFileServiceId={} sourceCorrelationId={}".format(
                        envelope["payloadFileServiceId"],
                        envelope["documentFileServiceId"],
                        envelope["sourceCorrelationId"]))
            except (ValueError, KeyError) as problem:
                log("skipping an unreadable generate-document body: {}".format(problem))
            except (OSError, RuntimeError) as problem:
                # Publishing failed, so this request has NOT been echoed: forget it and let the next
                # poll try again, which is what makes a broker restart survivable.
                echoed.discard(identifier)
                log("could not publish to the broker ({}); retrying".format(problem))

        time.sleep(POLL_INTERVAL_SECONDS)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(0)
