"""POS Link v2 frames: encoding, stream decoding and frame macs (spec/PROTOCOL.md, sections 2 and 6.3)."""

from __future__ import annotations

import hashlib
import hmac
import struct
from dataclasses import dataclass

STX = 0x02
VERSION = 2
HEADER_LENGTH = 11
MAC_LENGTH = 16
CRC_LENGTH = 2
MAX_PAYLOAD = 8192
PACKET_ALIGN = 64

# v1 layout, only parsed far enough to skip it and report the peer's version.
V1_HEADER_LENGTH = 7

ROLE_CASHIER = b"C"
ROLE_TERMINAL = b"T"


def crc16(data: bytes) -> int:
    """CRC-16/CCITT-FALSE: poly 0x1021, init 0xFFFF."""
    crc = 0xFFFF
    for b in data:
        crc ^= b << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) & 0xFFFF if crc & 0x8000 else (crc << 1) & 0xFFFF
    return crc


def frame_mac(session_key: bytes, sender_role: bytes, authenticated_bytes: bytes) -> bytes:
    """First 16 bytes of HMAC-SHA256(SK, role ‖ bytes 1 … 10+n)."""
    return hmac.new(session_key, sender_role + authenticated_bytes, hashlib.sha256).digest()[:MAC_LENGTH]


def encode(msg_type: int, seq: int, payload: bytes = b"", *, counter: int = 0,
           session_key: bytes | None = None, sender_role: bytes | None = None) -> bytes:
    """Encodes one frame. With a session key the frame is authenticated (counter must be ≥ 1)."""
    if len(payload) > MAX_PAYLOAD:
        raise ValueError(f"payload of {len(payload)} bytes exceeds {MAX_PAYLOAD}")
    if not 0 <= seq <= 0xFFFF or not 0 <= counter <= 0xFFFFFFFF:
        raise ValueError("seq or counter out of range")
    body = bytes([VERSION, msg_type]) + struct.pack(">HIH", seq, counter, len(payload)) + payload
    if session_key is None:
        if counter != 0:
            raise ValueError("unauthenticated frames carry counter 0")
        mac = bytes(MAC_LENGTH)
    else:
        if counter < 1 or sender_role not in (ROLE_CASHIER, ROLE_TERMINAL):
            raise ValueError("authenticated frames need counter ≥ 1 and a sender role")
        mac = frame_mac(session_key, sender_role, body)
    protected = body + mac
    frame = bytes([STX]) + protected + struct.pack(">H", crc16(protected))
    if len(frame) % PACKET_ALIGN == 0:
        frame += b"\x00"
    return frame


@dataclass(frozen=True)
class Frame:
    version: int
    type: int
    seq: int
    payload: bytes
    counter: int = 0
    mac: bytes = bytes(MAC_LENGTH)
    # Bytes 1 … 10+n, which the mac covers (empty for v1 frames).
    authenticated_bytes: bytes = b""

    @property
    def is_unauthenticated(self) -> bool:
        return self.counter == 0 and self.mac == bytes(MAC_LENGTH)

    def verify(self, session_key: bytes, sender_role: bytes) -> bool:
        expected = frame_mac(session_key, sender_role, self.authenticated_bytes)
        return self.version == VERSION and hmac.compare_digest(expected, self.mac)


class FrameDecoder:
    """Rebuilds frames from an arbitrary byte stream; resynchronises on the next STX."""

    def __init__(self) -> None:
        self._buffer = bytearray()
        self.crc_errors = 0
        self.discarded_bytes = 0

    def add(self, chunk: bytes) -> list[Frame]:
        buf = self._buffer
        buf.extend(chunk)
        frames: list[Frame] = []
        i = 0
        while True:
            while i < len(buf) and buf[i] != STX:
                if buf[i] != 0:
                    self.discarded_bytes += 1
                i += 1
            if len(buf) - i < 2:
                break
            version = buf[i + 1]
            if version == VERSION:
                if len(buf) - i < HEADER_LENGTH:
                    break
                seq, counter, length = struct.unpack_from(">HIH", buf, i + 3)
                total = HEADER_LENGTH + length + MAC_LENGTH + CRC_LENGTH
            elif version == 1:
                if len(buf) - i < V1_HEADER_LENGTH:
                    break
                seq, length = struct.unpack_from(">HH", buf, i + 3)
                counter = 0
                total = V1_HEADER_LENGTH + length + CRC_LENGTH
            else:
                self.discarded_bytes += 1
                i += 1
                continue
            if length > MAX_PAYLOAD:
                self.discarded_bytes += 1
                i += 1
                continue
            if len(buf) - i < total:
                break
            crc_end = i + total - CRC_LENGTH
            if crc16(bytes(buf[i + 1:crc_end])) != struct.unpack_from(">H", buf, crc_end)[0]:
                self.crc_errors += 1
                i += 1
                continue
            if version == VERSION:
                payload_end = i + HEADER_LENGTH + length
                frames.append(Frame(
                    version=version, type=buf[i + 2], seq=seq, counter=counter,
                    payload=bytes(buf[i + HEADER_LENGTH:payload_end]),
                    mac=bytes(buf[payload_end:payload_end + MAC_LENGTH]),
                    authenticated_bytes=bytes(buf[i + 1:payload_end]),
                ))
            else:
                frames.append(Frame(version=1, type=buf[i + 2], seq=seq,
                                    payload=bytes(buf[i + V1_HEADER_LENGTH:crc_end])))
            i += total
        del buf[:i]
        return frames

    def reset(self) -> None:
        self._buffer.clear()
