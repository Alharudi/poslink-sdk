"""Reference implementation tests: python/.venv/bin/python -m unittest discover -s python/tests"""

import json
import pathlib
import sys
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "python" / "src"))

from poslink import crypto, frame  # noqa: E402

VECTORS = ROOT / "spec" / "vectors"


class FrameTests(unittest.TestCase):
    def test_crc_check_value(self):
        self.assertEqual(frame.crc16(b"123456789"), 0x29B1)

    def test_vectors_encode_and_verify(self):
        v = json.loads((VECTORS / "frames.json").read_text())
        sk = bytes.fromhex(v["sessionKey"])
        for case in v["frames"]:
            role = case.get("senderRole", "").encode() or None
            got = frame.encode(case["type"], case["seq"], case["payload"].encode(), counter=case["counter"],
                               session_key=sk if case["authenticated"] else None, sender_role=role)
            self.assertEqual(got.hex(), case["hex"], case["name"])
            decoded = frame.FrameDecoder().add(got)
            self.assertEqual(len(decoded), 1)
            if case["authenticated"]:
                self.assertTrue(decoded[0].verify(sk, role))

    def test_tampered_frame_fails_mac(self):
        sk = bytes(32)
        f = bytearray(frame.encode(0x20, 1, b'{"amount":1}', counter=1, session_key=sk, sender_role=b"C"))
        f[frame.HEADER_LENGTH + 2] ^= 1
        protected = bytes(f[1:-2])
        f[-2:] = frame.crc16(protected).to_bytes(2, "big")
        decoded = frame.FrameDecoder().add(bytes(f))
        self.assertEqual(len(decoded), 1)
        self.assertFalse(decoded[0].verify(sk, b"C"))


class PairingTests(unittest.TestCase):
    def test_full_pairing_agrees_on_key_and_code(self):
        id_c, id_t = b"c" * 16, b"t" * 16
        kc, kt = crypto.KeyPair.generate(), crypto.KeyPair.generate()
        nc, nt = b"1" * 16, b"2" * 16
        commit = crypto.pairing_commit(kc.public_key, nc)
        self.assertEqual(commit, crypto.pairing_commit(kc.public_key, nc))
        th = crypto.transcript_hash(id_c, id_t, kc.public_key, kt.public_key, nc, nt)
        k1 = crypto.pairing_key(kc.shared_secret(kt.public_key), th)
        k2 = crypto.pairing_key(kt.shared_secret(kc.public_key), th)
        self.assertEqual(k1, k2)
        code = crypto.pairing_code(kc.shared_secret(kt.public_key), th)
        self.assertRegex(code, r"^\d{6}$")

    def test_man_in_the_middle_sees_different_codes(self):
        # Cashier <-> attacker and attacker <-> terminal end up with unrelated codes.
        id_c, id_t = b"c" * 16, b"t" * 16
        kc, ka, kt = crypto.KeyPair.generate(), crypto.KeyPair.generate(), crypto.KeyPair.generate()
        nc, na, nt = b"1" * 16, b"3" * 16, b"2" * 16
        code_c = crypto.pairing_code(kc.shared_secret(ka.public_key),
                                     crypto.transcript_hash(id_c, id_t, kc.public_key, ka.public_key, nc, na))
        code_t = crypto.pairing_code(kt.shared_secret(ka.public_key),
                                     crypto.transcript_hash(id_c, id_t, ka.public_key, kt.public_key, na, nt))
        self.assertNotEqual(code_c, code_t)

    def test_invalid_point_rejected(self):
        with self.assertRaises(ValueError):
            crypto.KeyPair.generate().shared_secret(b"\x04" + bytes(64))


class VectorFreshness(unittest.TestCase):
    def test_vectors_are_up_to_date(self):
        import subprocess
        r = subprocess.run([sys.executable, str(ROOT / "spec" / "tools" / "generate_vectors.py"), "--check"],
                           capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)


if __name__ == "__main__":
    unittest.main()
