# Application record v1

All multibyte integers are big-endian. Value bodies are strict UTF-8 strings; embedded JSON is never interpreted or normalized.

| Offset | Bytes | Meaning |
| --- | --- | --- |
| 0 | 4 | Magic `JELY` (0x4a454c59) |
| 4 | 1 | Version 1 |
| 5 | 1 | Codec: 0 plain, 1 JDK gzip |
| 6 | 4 | Uncompressed UTF-8 length, 0..1,048,576 |
| 10 | 4 | CRC32C of uncompressed bytes |
| 14 | remaining | Stored value representation |

Codec 1 is considered only for >=512-byte inputs, and used only when compressed bytes plus the 14-byte header are smaller than plain bytes. Incompressible/tiny values remain plain. Decompression is bounded to declared length + 1 before validation; unknown versions/codecs, bad UTF-8, truncation and integrity mismatch are 503 `corrupt_record`, never missing. v1 remains readable during future upgrades. CRC is accidental-corruption detection, not cryptographic authenticity. Callers can store locally encrypted strings; ciphertext typically compresses poorly.

This envelope carries value-decoding information. Authoritative lifecycle is committed inseparably with its locator by Candybox managed namespace operations; adding an application timestamp alone would be unsafe. Future record versions need a compatibility test and explicit mixed-version support before rollout. No Zstandard dependency or claim of a measured benefit.

Production physical keys use Candybox managed namespaces: `l/hex(address)` for lifecycle and `i/hex(address)/hex(key)` for values. All route by the lifecycle address. A 22-byte REST namespace ID and 256-byte user key occupy 559 physical bytes, under the 1024-byte backend key bound. REST key tokens remain `~` plus base64url UTF-8; encoding layers are distinct. The old `n/id/~key` raw adapter is test-only and cannot be called from HTTP.
