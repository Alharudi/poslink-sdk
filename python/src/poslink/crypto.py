"""POS Link v2 security: pairing math and session keys (spec/PROTOCOL.md, section 6)."""

from __future__ import annotations

import hashlib
import hmac

from cryptography.hazmat.primitives.asymmetric import ec

PUBLIC_KEY_LENGTH = 65
NONCE_LENGTH = 16
DEVICE_ID_LENGTH = 16

ROLE_CASHIER = b"C"
ROLE_TERMINAL = b"T"


def sha256(*parts: bytes) -> bytes:
    h = hashlib.sha256()
    for p in parts:
        h.update(p)
    return h.digest()


def hmac_sha256(key: bytes, *parts: bytes) -> bytes:
    m = hmac.new(key, digestmod=hashlib.sha256)
    for p in parts:
        m.update(p)
    return m.digest()


def hkdf(ikm: bytes, salt: bytes, info: bytes, length: int) -> bytes:
    """HKDF-SHA256 (RFC 5869): extract, then expand."""
    prk = hmac_sha256(salt or bytes(32), ikm)
    okm, block, counter = b"", b"", 1
    while len(okm) < length:
        block = hmac_sha256(prk, block, info, bytes([counter]))
        okm += block
        counter += 1
    return okm[:length]


class KeyPair:
    """A P-256 key pair. Public keys travel as 65-byte uncompressed points."""

    def __init__(self, private_key: ec.EllipticCurvePrivateKey) -> None:
        self._private = private_key

    @classmethod
    def generate(cls) -> "KeyPair":
        return cls(ec.generate_private_key(ec.SECP256R1()))

    @classmethod
    def from_private_scalar(cls, scalar: bytes) -> "KeyPair":
        return cls(ec.derive_private_key(int.from_bytes(scalar, "big"), ec.SECP256R1()))

    @property
    def private_scalar(self) -> bytes:
        return self._private.private_numbers().private_value.to_bytes(32, "big")

    @property
    def public_key(self) -> bytes:
        n = self._private.public_key().public_numbers()
        return b"\x04" + n.x.to_bytes(32, "big") + n.y.to_bytes(32, "big")

    def shared_secret(self, peer_public_key: bytes) -> bytes:
        """ECDH: the 32-byte X coordinate. Raises ValueError if the peer key isn't a valid point."""
        if len(peer_public_key) != PUBLIC_KEY_LENGTH or peer_public_key[0] != 0x04:
            raise ValueError("public key must be a 65-byte uncompressed P-256 point")
        peer = ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), peer_public_key)
        return self._private.exchange(ec.ECDH(), peer)


def pairing_commit(public_key: bytes, nonce: bytes) -> bytes:
    return sha256(public_key, nonce)


def transcript_hash(id_cashier: bytes, id_terminal: bytes, pk_cashier: bytes, pk_terminal: bytes,
                    nonce_cashier: bytes, nonce_terminal: bytes) -> bytes:
    return sha256(b"poslink/2 pair", id_cashier, id_terminal, pk_cashier, pk_terminal,
                  nonce_cashier, nonce_terminal)


def pairing_key(shared: bytes, transcript: bytes) -> bytes:
    return hkdf(shared, transcript, b"poslink/2 pairing key", 32)


def pairing_code(shared: bytes, transcript: bytes) -> str:
    value = int.from_bytes(hkdf(shared, transcript, b"poslink/2 code", 4), "big")
    return f"{value % 1_000_000:06d}"


def confirm_mac(key: bytes, sender_role: bytes, transcript: bytes) -> bytes:
    return hmac_sha256(key, b"poslink/2 confirm", sender_role, transcript)


def session_key(key: bytes, nonce_cashier: bytes, nonce_terminal: bytes,
                id_cashier: bytes, id_terminal: bytes) -> bytes:
    return hkdf(key, nonce_cashier + nonce_terminal, b"poslink/2 session" + id_cashier + id_terminal, 32)
