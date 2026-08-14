# Security Policy

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| 0.3.x   | :white_check_mark: |

## Reporting a Vulnerability

Please open a private vulnerability report via **Security → Report a vulnerability** on GitHub, or open a private issue and we will triage it there. Do not file public issues for suspected vulnerabilities.

We aim to acknowledge within 72 hours and ship a fix or mitigation promptly.

## Hardening notes (this fork)

- API keys are stored in `EncryptedSharedPreferences` (AndroidKeyStore, AES256-GCM) when available; plain `SharedPreferences` is only used as a fallback on devices without a functional keystore. The encrypted file is excluded from auto-backup and device transfer.
- `network_security_config.xml` blocks cleartext (`https` required) except for loopback `http://localhost` / `http://127.0.0.1` for self-hosted custom endpoints.
- Custom provider URLs are validated in the UI (`https://` required except loopback) before persisting.
- CodeQL runs on every push to `main`.

## Upstream

This is a fork of [kafkasl/phone-whisper](https://github.com/kafkasl/phone-whisper). Security issues that also affect upstream should be reported there as well.
