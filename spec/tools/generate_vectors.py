#!/usr/bin/env python3
"""Generates spec/vectors/*.json from the Python reference implementation (python/src/poslink).

    python/.venv/bin/python spec/tools/generate_vectors.py           # write the files
    python/.venv/bin/python spec/tools/generate_vectors.py --check   # fail if they are out of date

Every SDK's tests load these files and must reproduce every value.
Inputs are fixed, so the output never changes unless the protocol does.
"""

from __future__ import annotations

import base64
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "python" / "src"))

from poslink import crypto, frame  # noqa: E402

VECTORS = ROOT / "spec" / "vectors"


def fixed(label: str, length: int) -> bytes:
    """Deterministic 'random-looking' bytes for vector inputs."""
    return crypto.sha256(b"poslink/2 vector " + label.encode())[:length]


def b64(data: bytes) -> str:
    return base64.b64encode(data).decode()


def payload(obj) -> str:
    return json.dumps(obj, separators=(",", ":"))


def hkdf_rfc5869() -> dict:
    # RFC 5869, appendix A.1.
    ikm, salt, info = bytes([0x0B] * 22), bytes(range(0x0D)), bytes(range(0xF0, 0xFA))
    okm = crypto.hkdf(ikm, salt, info, 42)
    expected = "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"
    assert okm.hex() == expected, "HKDF does not match RFC 5869"
    return {"ikm": ikm.hex(), "salt": salt.hex(), "info": info.hex(), "length": 42, "okm": okm.hex()}


def crypto_vectors() -> dict:
    id_c, id_t = fixed("cashier device id", 16), fixed("terminal device id", 16)
    key_c = crypto.KeyPair.from_private_scalar(fixed("cashier pairing key", 32))
    key_t = crypto.KeyPair.from_private_scalar(fixed("terminal pairing key", 32))
    n_c, n_t = fixed("cashier pairing nonce", 16), fixed("terminal pairing nonce", 16)
    pk_c, pk_t = key_c.public_key, key_t.public_key

    commit = crypto.pairing_commit(pk_c, n_c)
    shared = key_c.shared_secret(pk_t)
    assert shared == key_t.shared_secret(pk_c)
    th = crypto.transcript_hash(id_c, id_t, pk_c, pk_t, n_c, n_t)
    k = crypto.pairing_key(shared, th)
    code = crypto.pairing_code(shared, th)

    messages = {
        "PAIR_REQUEST": payload({"deviceId": id_c.hex(), "name": "Front desk", "commit": b64(commit)}),
        "PAIR_KEY": payload({"deviceId": id_t.hex(), "name": "SUNMI P3", "publicKey": b64(pk_t), "nonce": b64(n_t)}),
        "PAIR_REVEAL": payload({"publicKey": b64(pk_c), "nonce": b64(n_c)}),
        "PAIR_CONFIRM_cashier": payload({"mac": b64(crypto.confirm_mac(k, crypto.ROLE_CASHIER, th))}),
        "PAIR_CONFIRM_terminal": payload({"mac": b64(crypto.confirm_mac(k, crypto.ROLE_TERMINAL, th))}),
    }
    types = {"PAIR_REQUEST": 0x30, "PAIR_KEY": 0x31, "PAIR_REVEAL": 0x32,
             "PAIR_CONFIRM_cashier": 0x33, "PAIR_CONFIRM_terminal": 0x33}

    link_c, link_t = fixed("cashier link nonce", 16), fixed("terminal link nonce", 16)
    sk = crypto.session_key(k, link_c, link_t, id_c, id_t)

    return {
        "hkdf_rfc5869_a1": hkdf_rfc5869(),
        "pairing": {
            "cashier": {"deviceId": id_c.hex(), "privateScalar": key_c.private_scalar.hex(),
                        "publicKey": pk_c.hex(), "nonce": n_c.hex()},
            "terminal": {"deviceId": id_t.hex(), "privateScalar": key_t.private_scalar.hex(),
                         "publicKey": pk_t.hex(), "nonce": n_t.hex()},
            "commit": commit.hex(),
            "sharedSecret": shared.hex(),
            "transcriptHash": th.hex(),
            "pairingKey": k.hex(),
            "code": code,
            "confirmMacCashier": crypto.confirm_mac(k, crypto.ROLE_CASHIER, th).hex(),
            "confirmMacTerminal": crypto.confirm_mac(k, crypto.ROLE_TERMINAL, th).hex(),
            "frames": [
                {"name": name, "type": types[name], "seq": 0, "payload": body,
                 "hex": frame.encode(types[name], 0, body.encode()).hex()}
                for name, body in messages.items()
            ],
        },
        "session": {
            "pairingKey": k.hex(),
            "cashierDeviceId": id_c.hex(),
            "terminalDeviceId": id_t.hex(),
            "cashierLinkNonce": link_c.hex(),
            "terminalLinkNonce": link_t.hex(),
            "sessionKey": sk.hex(),
        },
    }


def frame_vectors(session_key: bytes) -> dict:
    cases = []

    def add(name, msg_type, seq, body, counter=0, role=None, note=None):
        raw = body.encode()
        auth = counter > 0
        hexed = frame.encode(msg_type, seq, raw, counter=counter,
                             session_key=session_key if auth else None,
                             sender_role=role).hex()
        case = {"name": name, "type": msg_type, "seq": seq, "counter": counter, "payload": body,
                "authenticated": auth, "hex": hexed}
        if role:
            case["senderRole"] = role.decode()
        if note:
            case["note"] = note
        cases.append(case)

    hello = payload({"protocol": 2, "role": "cashier", "deviceId": fixed("cashier device id", 16).hex(),
                     "name": "Front desk", "sessionId": "5f0b3c1e-2d4a-4b8e-9c6f-0a1b2c3d4e5f",
                     "nonce": b64(fixed("cashier link nonce", 16)), "needReply": True})
    add("hello", 0x01, 0, hello)
    add("auth", 0x02, 0, "", counter=1, role=frame.ROLE_CASHIER)
    add("sale_request", 0x20, 42, payload({"txnId": "7d3f9a2e-1b4c-4d5e-8f6a-9b0c1d2e3f40", "amount": 12500,
                                           "currency": "LYD", "reference": "INV-1001",
                                           "createdAt": "2026-11-11T12:00:00Z"}),
        counter=7, role=frame.ROLE_CASHIER)
    add("ack", 0x05, 42, "", counter=3, role=frame.ROLE_TERMINAL)
    add("sale_result", 0x23, 9, payload({"txnId": "7d3f9a2e-1b4c-4d5e-8f6a-9b0c1d2e3f40", "outcome": "approved",
                                         "amount": 12500, "currency": "LYD", "timestamp": "2026-11-11T12:00:31Z",
                                         "authCode": "A1B2C3", "maskedPan": "476173******0119"}),
        counter=12, role=frame.ROLE_TERMINAL)
    # 29 + 35 = 64 bytes, so one 0x00 pad byte follows.
    status = payload({"state": "busy", "x": "123456789012"})
    assert len(status) == 35, len(status)
    add("padded", 0x10, 5, status, counter=2, role=frame.ROLE_TERMINAL, note="exact multiple of 64: one 0x00 pad byte")

    # A stream: garbage, a frame split across chunks, a v1 frame, and a corrupted frame.
    good = bytes.fromhex(cases[2]["hex"])
    v1 = bytes.fromhex("020120000700107b22616d6f756e74223a31323530307dbe65")
    corrupted = bytearray(bytes.fromhex(cases[3]["hex"]))
    corrupted[12] ^= 0xFF
    stream = b"\xff\x00garbage" + good + v1 + bytes(corrupted) + bytes.fromhex(cases[0]["hex"])
    chunks = [stream[:5], stream[5:40], stream[40:41], stream[41:]]
    decoding = {
        "note": "Feed the chunks in order. Expect exactly these frames; the corrupted ACK counts as one CRC error.",
        "chunks": [c.hex() for c in chunks],
        "expected": [
            {"version": 2, "type": 0x20, "seq": 42, "counter": 7},
            {"version": 1, "type": 0x20, "seq": 7, "counter": 0},
            {"version": 2, "type": 0x01, "seq": 0, "counter": 0},
        ],
        "crcErrors": 1,
    }
    return {"sessionKey": session_key.hex(), "frames": cases, "decoding": decoding}


def main() -> int:
    c = crypto_vectors()
    f = frame_vectors(bytes.fromhex(c["session"]["sessionKey"]))
    outputs = {"crypto.json": c, "frames.json": f}
    check = "--check" in sys.argv
    stale = []
    for name, data in outputs.items():
        text = json.dumps(data, indent=2) + "\n"
        path = VECTORS / name
        if check:
            if not path.exists() or path.read_text() != text:
                stale.append(name)
        else:
            path.write_text(text)
            print(f"wrote {path.relative_to(ROOT)}")
    if stale:
        print("out of date: " + ", ".join(stale) + " (run without --check)")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
