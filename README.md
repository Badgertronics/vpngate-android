# VPN Gate Client for Android

A modern, high-performance, open-source Android VPN client for the [VPN Gate](https://www.vpngate.net) Academic Experiment Project (University of Tsukuba, Japan).

Designed with Material Design 3 (Material You) for mobile devices, offering complete protocol support, resilient multi-tier server fetching, parallel latency testing, and network watchdog automation.

---

## Features

### 1. Full Protocol Support
- **SSL-VPN (SoftEther)**: Native HTTPS VPN tunneling to `VPNGATE` Virtual Hub over TCP 443 / custom ports.
- **MS-SSTP**: Microsoft Secure Socket Tunneling Protocol running PPP over HTTPS/TLS.
- **L2TP / IPsec**: Pure user-space L2TP/PPP over UDP 1701.
- **OpenVPN**: Full OpenVPN over UDP and TCP utilizing embedded `.ovpn` profiles.

### 2. Multi-Tier Server Fetching
- **Priority 1**: Direct `servers.csv` from GitHub raw and multi-CDN mirrors (Fastly, Cloudflare, jsDelivr, gh-proxy).
- **Priority 2**: Live VPN Gate mirror sites scraped from `mirrors.json`.
- **Priority 3**: Instant offline startup using pre-packaged fallback seed cache.

### 3. Parallel Real Latency Measurement
- **Ping All**: Concurrently measures real device-to-relay TCP round-trip latency.
- **Real-Time Progress**: Live progress indicator with instant Stop/Cancel capability.
- **Preserved Latencies**: Ping values are cached and maintained across connects, disconnects, and filter changes.

### 4. Advanced Sorting & Filtering
- Filter by Country with normalized names (e.g. Thailand, India, Iran) and native emoji flag badges.
- Filter by Protocol (SSL-VPN, L2TP, MS-SSTP, OpenVPN).
- Search by country, hostname, IP address, or operator.
- Multi-criteria sorting:
  - **Recommended** (Weighted algorithm combining real ping, bandwidth, and session counts)
  - **Real Ping** (Lowest latency first)
  - **Speed** (Highest bandwidth first)
  - **VPN Gate Ping**
  - **Sessions**
  - **Uptime**
  - **Total Traffic**
  - **Country**
  - **Hostname**

### 5. Watchdog & Auto-Reconnect
- **Connectivity Watchdog**: Probes target endpoints (YouTube, Google, Cloudflare, 1.1.1.1, 8.8.8.8) to verify active internet throughput.
- **Configurable Targets**: Enable or disable any endpoint individually, or add custom endpoints.
- **Configurable Failure Threshold**: Triggers disconnect/reconnect after $N$ consecutive failed checks.
- **Configurable Check Interval**: Adjust probe frequency in seconds.
- **Auto-Reconnect**: Automatically reconnects with backoff delay if tunnel drops unexpectedly.

### 6. Modern Mobile UI / UX (Material Design 3)
- **Servers Tab**: Search, filter, ping, sort, and inspect servers with expandable detail sheets.
- **Connect Hero Tab**: Animated pulse ring, one-tap power toggle, server card, protocol chips, and live transfer counters (Rx/Tx bytes, speed, duration).
- **Logs Tab**: Real-time diagnostic terminal with timestamps and copy/clear actions.
- **Settings Tab**: Appearance (System, Dark, Light), Timeouts, Watchdog, Updates, and Crypto Donations.

---

## Requirements & Building

- **Android SDK**: compileSdk 34, minSdk 26 (Android 8.0 Oreo+), targetSdk 34
- **Java**: JDK 17 or JDK 21
- **Gradle**: 8.10+

### Build Debug APK
```bash
./gradlew assembleDebug
```
The resulting APK will be located at:
`app/build/outputs/apk/debug/app-debug.apk`

---

## License & Credits

- Developed by **Mr. Honey Badger (Badgertronics)**
- Powered by the VPN Gate Academic Experiment Project by University of Tsukuba.
