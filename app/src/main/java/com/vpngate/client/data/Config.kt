package com.vpngate.client.data

object Config {
    const val APP_NAME = "VPN Gate"
    const val APP_ID = "com.vpngate.client"
    const val VERSION = "1.0.1"

    // Default VPN credentials for all public VPN Gate relays
    const val VPN_USERNAME = "vpn"
    const val VPN_PASSWORD = "vpn"
    const val VPN_PSK = "vpn"
    const val SOFTETHER_HUB = "VPNGATE"

    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    // Default timeouts & retries
    const val DEFAULT_CONNECT_TIMEOUT_SEC = 30
    const val DEFAULT_CONNECT_RETRIES = 1
    const val DEFAULT_PROBE_TIMEOUT_SEC = 3.5
    const val DEFAULT_WATCHDOG_INTERVAL_SEC = 20
    const val DEFAULT_WATCHDOG_FAILURES = 2
    const val DEFAULT_AUTO_RECONNECT = true
    const val DEFAULT_AUTO_RECONNECT_RETRIES = 3
    const val DEFAULT_AUTO_RECONNECT_DELAY_SEC = 5

    // Default mirrors
    val DEFAULT_MIRRORS = listOf(
        "http://150.40.105.11:4917",
        "http://150.40.105.5:32536",
        "http://150.40.105.6:11803",
        "http://178.32.222.148:18609",
        "http://150.40.105.17:50406",
        "https://www.vpngate.net"
    )

    // Watchdog target connectivity endpoints
    val DEFAULT_CONNECTIVITY_TARGETS = listOf(
        "youtube.com",
        "google.com",
        "cloudflare.com",
        "1.1.1.1",
        "8.8.8.8"
    )

    const val GITHUB_REPO = "Badgertronics/vpngate-client"
    const val REPO_BRANCH = "main"

    // CDN & proxy prefixes for raw data (mirrors.json, servers.csv)
    val REPO_RAW_PREFIXES = listOf(
        "https://raw.githubusercontent.com/$GITHUB_REPO/$REPO_BRANCH",
        "https://cdn.jsdelivr.net/gh/$GITHUB_REPO@$REPO_BRANCH",
        "https://fastly.jsdelivr.net/gh/$GITHUB_REPO@$REPO_BRANCH",
        "https://gcore.jsdelivr.net/gh/$GITHUB_REPO@$REPO_BRANCH",
        "https://gh-proxy.com/https://raw.githubusercontent.com/$GITHUB_REPO/$REPO_BRANCH",
        "https://ghproxy.net/https://raw.githubusercontent.com/$GITHUB_REPO/$REPO_BRANCH",
        "https://ghproxy.cc/https://raw.githubusercontent.com/$GITHUB_REPO/$REPO_BRANCH"
    )

    const val REPO_CSV_PATH = "data/servers.csv"
    const val REPO_MIRROR_LIST_PATH = "data/mirrors.json"

    val REPO_CSV_URLS = REPO_RAW_PREFIXES.map { "$it/$REPO_CSV_PATH" }
    val REPO_MIRROR_LIST_URLS = REPO_RAW_PREFIXES.map { "$it/$REPO_MIRROR_LIST_PATH" }

    // GitHub release API endpoints & mirrors
    val RELEASE_API_MIRRORS = listOf(
        "https://api.github.com/repos/Badgertronics/vpngate-android/releases/latest",
        "https://gh-proxy.com/https://api.github.com/repos/Badgertronics/vpngate-android/releases/latest",
        "https://ghproxy.net/https://api.github.com/repos/Badgertronics/vpngate-android/releases/latest"
    )

    // Buy me a coffee donation addresses
    const val DEVELOPER_NAME = "Mr. Honey Badger (Badgertronics)"
    const val DONATE_BITCOIN = "bc1q3l2gxktlc3r9f6z325d920kqhv8kwctqdyvxmh"
    const val DONATE_ETHEREUM = "0x9C2Cde3575769e104E93bC934abbfE2D883e8dCa"
    const val DONATE_BEP20 = "0x9C2Cde3575769e104E93bC934abbfE2D883e8dCa"
}
