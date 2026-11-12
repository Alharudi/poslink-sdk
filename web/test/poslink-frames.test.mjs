// Checks poslink-frames.js against the protocol's test vectors.
//   node web/test/poslink-frames.test.mjs     (from the repository root; Node 18+)

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { crc16, encodeFrame, FrameDecoder, fromHex, toHex, HEADER_LENGTH, MAC_LENGTH } from "./poslink-frames.js";

const vectors = JSON.parse(readFileSync(new URL("../../spec/vectors/frames.json", import.meta.url), "utf8"));
let checks = 0;

// CRC check value from the spec.
assert.equal(crc16(new TextEncoder().encode("123456789")), 0x29b1);
checks++;

for (const v of vectors.frames) {
  const payload = new TextEncoder().encode(v.payload);
  const expected = fromHex(v.hex);
  // The mac comes from the vector: computing it needs the crypto, which this page doesn't do.
  const macAt = HEADER_LENGTH + payload.length;
  const mac = expected.slice(macAt, macAt + MAC_LENGTH);
  const encoded = encodeFrame({ type: v.type, seq: v.seq, counter: v.counter, payload, mac });
  assert.equal(toHex(encoded), v.hex, `encode ${v.name}`);

  const frames = new FrameDecoder().add(expected);
  assert.equal(frames.length, 1, `decode ${v.name}`);
  assert.deepEqual(
    { type: frames[0].type, seq: frames[0].seq, counter: frames[0].counter, payload: new TextDecoder().decode(frames[0].payload) },
    { type: v.type, seq: v.seq, counter: v.counter, payload: v.payload },
    `decode ${v.name}`,
  );
  checks += 2;
}

// Split, merged and corrupted input.
const decoder = new FrameDecoder();
const found = vectors.decoding.chunks.flatMap((chunk) => decoder.add(fromHex(chunk)));
assert.deepEqual(
  found.map((f) => ({ version: f.version, type: f.type, seq: f.seq, counter: f.counter })),
  vectors.decoding.expected,
  "decoding chunks",
);
assert.equal(decoder.crcErrors, vectors.decoding.crcErrors, "crc errors");
checks += 2;

// One byte at a time gives the same frames.
const byteByByte = new FrameDecoder();
const all = vectors.decoding.chunks.map(fromHex).flatMap((c) => Array.from(c));
const single = all.flatMap((b) => byteByByte.add(new Uint8Array([b])));
assert.equal(single.length, vectors.decoding.expected.length, "byte by byte");
checks++;

console.log(`poslink-frames: ${checks} checks passed`);
