# NetMonitor

Non-root Android network monitor and per-app firewall helper.

NetMonitor routes device traffic through a local `VpnService`, shows live connections grouped by app, resolves hostnames (DNS + TLS SNI), and can block apps permanently or on a schedule.

**Min SDK:** Android 11 (API 30)  
**Package:** `dev.djoxer.netmonitor`  
**Version:** 1.0.0

---

## Features

- **Live monitoring** of TCP/UDP traffic without root
- **App grouping** with icons, outbound / inbound columns
- **Hostnames** via DNS response sniffing and TLS ClientHello SNI
- **Global block mode** – drop all forwarded traffic (observe-only)
- **Per-app permanent block**
- **Time schedules (mode A)** – during the window the app is blocked; outside it is allowed (unless permanently blocked)
- **Works for `uid:*` keys** when no package name is available (e.g. system UIDs)
- **Async event log** (Room) – does not block the capture path
- **Settings** – clear log, delete old logs, clear blocks/schedules, reload rules

### UI tabs

| Tab | Description |
|-----|-------------|
| **Monitor** | Start/Stop VPN, global block switch, app tiles (out / in) |
| **Log** | Recent CONNECT / RECV / BLOCKED events |
| **Settings** | Log and rule maintenance |
| **About** | Version and short project info |

Tap an app tile for connections, permanent block, and schedule management.

## How it works

1. Android establishes a local VPN (`VpnService`).
2. Outgoing packets are read from the TUN interface.
3. A userspace forwarder opens **protected** sockets to the real destination and relays data.
4. Connection metadata is tracked in memory; logs/rules are persisted with **Room** on a background thread.
5. Block decisions use an in-memory `BlockManager` (fast checks on the hot path).

## Requirements

- Android 11 or newer
- User approval of the VPN connection prompt
- No root required for the 1.0 feature set

**Note:** QUERY_ALL_PACKAGES is used so UID to app name resolution works reliably on Android 11+. Sideload / own distribution is assumed; Play policy for this permission is strict.

## Limitations (1.0)

- Userspace TCP is functional but not a full kernel stack (edge cases possible)
- Only one active VPN on the device at a time
- Inbound byte counts come from the forwarder RX path (not a second TUN read)
- Hostname coverage depends on visible DNS/SNI; some flows stay as raw IPs
- Not a substitute for a full enterprise MDM/firewall

## Privacy

All processing stays on device. No analytics backend is bundled in this project. The local VPN can see destination IPs/hosts and traffic metadata like any VPN-based monitor.

## License

© 2026 Daniel Orzalesi (Djoxer). All rights reserved.  
This project is publicly visible as a portfolio piece.  
No permission is granted to use, copy, modify, or distribute this code.
