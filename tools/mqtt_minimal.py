"""A very small MQTT 3.1.1 client, QoS 0 only, using nothing but the standard library.

WallPanel's test tooling needs to read what the application publishes and to publish
commands back at it. Both are a handful of packet types, and pulling in paho-mqtt would
mean the tools stop working on a machine that only has Python -- which is the same
reason the rest of tools/ is plain bash.
"""
import socket
import struct
import time


def _encode_length(n):
    out = b""
    while True:
        digit = n % 128
        n //= 128
        if n:
            digit |= 0x80
        out += bytes([digit])
        if not n:
            return out


def _encode_string(s):
    b = s.encode("utf-8")
    return struct.pack("!H", len(b)) + b


class MqttError(RuntimeError):
    pass


class MqttClient:
    """Connects on construction; use as a context manager to disconnect cleanly."""

    CONNACK = 2
    PUBLISH = 3
    SUBACK = 9

    def __init__(self, host, port=1883, client_id="wallpanel-tools",
                 username=None, password=None, timeout=10):
        self._buf = b""
        self._packet_id = 0
        try:
            self._sock = socket.create_connection((host, port), timeout=timeout)
        except OSError as e:
            raise MqttError("could not reach the broker at %s:%s (%s)" % (host, port, e))
        self._sock.settimeout(timeout)

        flags = 0x02  # clean session
        payload = _encode_string(client_id)
        if username:
            flags |= 0x80
            payload += _encode_string(username)
        if password:
            flags |= 0x40
            payload += _encode_string(password)
        variable_header = _encode_string("MQTT") + bytes([4, flags]) + struct.pack("!H", 60)
        self._send(0x10, variable_header + payload)

        packet_type, _, body = self._read_packet()
        if packet_type != self.CONNACK:
            raise MqttError("expected CONNACK, got packet type %d" % packet_type)
        if body[1] != 0:
            raise MqttError("broker refused the connection (CONNACK return code %d)" % body[1])

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()

    def _send(self, type_and_flags, body):
        self._sock.sendall(bytes([type_and_flags]) + _encode_length(len(body)) + body)

    def _take_packet(self):
        """The first whole packet in the buffer, or None while it is still arriving."""
        if not self._buf:
            return None
        multiplier, length, i = 1, 0, 1
        while True:
            if i >= len(self._buf):
                return None
            digit = self._buf[i]
            length += (digit & 127) * multiplier
            i += 1
            if not digit & 0x80:
                break
            multiplier *= 128
        if len(self._buf) < i + length:
            return None
        header, body = self._buf[0], self._buf[i:i + length]
        self._buf = self._buf[i + length:]
        return header >> 4, header & 0x0F, body

    def _read_packet(self):
        """Reads one packet, leaving a partly arrived one in the buffer.

        A timeout part way through a packet propagates with the buffer intact, so the
        next call picks the packet up where it left off instead of reading the rest of
        it as a new header.
        """
        while True:
            packet = self._take_packet()
            if packet is not None:
                return packet
            chunk = self._sock.recv(65536)
            if not chunk:
                raise MqttError("the broker closed the connection")
            self._buf += chunk

    def subscribe(self, *topic_filters):
        self._packet_id += 1
        body = struct.pack("!H", self._packet_id)
        for topic in topic_filters:
            body += _encode_string(topic) + b"\x00"
        self._send(0x82, body)
        while True:
            packet_type, _, _ = self._read_packet()
            if packet_type == self.SUBACK:
                return

    def publish(self, topic, payload, retain=False):
        self._send(0x30 | (0x01 if retain else 0),
                   _encode_string(topic) + payload.encode("utf-8"))

    def messages(self, seconds):
        """Yields (topic, payload, retained) for up to `seconds`."""
        deadline = time.time() + seconds
        while True:
            remaining = deadline - time.time()
            if remaining <= 0:
                return
            self._sock.settimeout(min(remaining, 1.0))
            try:
                packet_type, flags, body = self._read_packet()
            except socket.timeout:
                continue
            except (OSError, MqttError):
                return
            if packet_type != self.PUBLISH:
                continue
            topic_len = struct.unpack("!H", body[:2])[0]
            topic = body[2:2 + topic_len].decode("utf-8", "replace")
            payload = body[2 + topic_len:].decode("utf-8", "replace")
            yield topic, payload, bool(flags & 0x01)

    def collect_retained(self, *topic_filters, seconds=6):
        """Returns {topic: payload} for the retained messages under these filters.

        A cleared retained message is delivered as an empty payload and is dropped here,
        so the result is what a subscriber joining now would actually see.
        """
        self.subscribe(*topic_filters)
        found = {}
        for topic, payload, _ in self.messages(seconds):
            if payload:
                found[topic] = payload
            else:
                found.pop(topic, None)
        return found

    def close(self):
        try:
            self._send(0xE0, b"")
        except OSError:
            pass
        try:
            self._sock.close()
        except OSError:
            pass
