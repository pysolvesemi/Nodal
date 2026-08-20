# Release checksums

Each file in this directory records the SHA-256 of one archive declared in
`toolchains/lock.json`. The value is copied from the matching checksum asset
published with the exact CIRCT release.

The lock checker requires byte-for-byte agreement between the JSON entry and
the corresponding file. Do not update a checksum without updating and
reviewing the release tag, URL, and immutable commit pair.
