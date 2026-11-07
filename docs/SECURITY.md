# Security architecture

## Key hierarchy
- **VMK** (vault master key): 256-bit random. Exists only in RAM while unlocked; zeroed on lock.
- At rest the VMK is stored as:
  - **Password blob** = Keystore-AES-GCM( AES-GCM( PBKDF2-HMAC-SHA256(secret, salt, 400k iterations), VMK ) )
  - **Recovery blob** = same construction with a 120-bit random recovery key
  - **Biometric blob** (optional) = AES-GCM under an auth-bound Keystore key (strong biometric, invalidated on new enrollment)
- The master secret is never stored. Verification = successful AEAD unwrap.
- Wrong-attempt counter with exponential lockout after 5 failures.

## Data
- **Files**: random 256-bit key per file; chunked AES-256-GCM (64 KiB chunks, 12-byte nonce = 8-byte random prefix + counter,
  AAD = chunk index + final flag, so reorder/truncation is detected). Per-file key wrapped by the VMK. Written to `.part`, renamed atomically.
- **Database**: Room stores ids/sizes/timestamps in clear; names, mime, folder metadata, credentials and notes are single AES-GCM blobs
  with AAD bound to the row id. Credentials are never sent anywhere.
- **Thumbnails**: sealed with the file key.
- **Temp plaintext**: only in `cache/sbx_tmp` while viewing; overwritten+deleted on close, on lock and on start.

## Backup (.sbxbak)
`SBB1 | salt | iter | AES-GCM chunk stream( ZIP{manifest(VMK), db.json, blobs, thumbs} )`, key = PBKDF2(backup password).
Verify authenticates the whole stream; restore verifies first, rejects unsafe entry names, then swaps data and re-wraps the VMK under a new master lock.

## Shadow Sync
Local TCP. QR carries IP, port and a 256-bit one-time secret. Mutual HMAC-SHA256 challenge, session key by HMAC, AES-GCM frames with
direction-separated nonces. Encrypted blobs are sent as-is; per-file keys travel inside the channel and are re-wrapped by the receiver's VMK.
Resume by blob offset, SHA-256 verification per file, conflict copies for divergent ids.

## Platform hardening
FLAG_SECURE, `allowBackup=false` + extraction rules, sensitive clipboard flag + auto-clear, minimal permissions
(USE_BIOMETRIC, INTERNET + ACCESS_NETWORK_STATE for local sync, CAMERA for QR scan), no analytics, no logging of secrets, SAF for all file access.

## Not claimed
No protection against a compromised/rooted OS, malware with accessibility rights, or a user who loses both secret and recovery key/backup.
