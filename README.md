# MinecraftAntiIPBypass

Anti-VPN and IP logger plugin for **Paper 1.21.1** (Java 21).

## Features

- **VPN / Proxy detection** — uses [ip-api.com](http://ip-api.com) to check every connecting IP against known VPN, proxy, and hosting-provider ranges.
- **IP logging** — records all IPs used by each player across sessions in `plugins/AntiIPBypass/ip_data.yml`.
- **Duplicate IP detection** — warns when multiple accounts connect from the same IP.
- **IP-switch detection** — alerts when a player logs in from a different IP than their last session.
- **Discord webhook** — sends rich embed notifications to a Discord channel for new IPs, IP switches, duplicate IPs, and suspected VPN connections.
- **Admin commands** — `/antiip lookup <player>`, `/antiip checkip <ip>`, `/antiip reload`.
- **File logging** — all events are written to `plugins/AntiIPBypass/logs/antiipbypass.log`.

## Installation

1. Drop `AntiIPBypass-1.0.0.jar` into your server's `plugins/` folder.
2. Restart the server — `plugins/AntiIPBypass/config.yml` is generated automatically.
3. (Optional) Configure your Discord webhook URL in `config.yml` and set `discord.enabled: true`.

## Building from source

Requirements: **Java 21**, **Maven 3.9+**, internet access to download Paper API from `repo.papermc.io`.

```bash
mvn clean package
# Output: target/AntiIPBypass-1.0.0.jar
```

## Configuration (`config.yml`)

```yaml
discord:
  enabled: false
  webhook-url: "https://discord.com/api/webhooks/YOUR_WEBHOOK_ID/YOUR_WEBHOOK_TOKEN"
  username: "AntiIPBypass"
  notify:
    new-ip: true
    vpn-detected: true
    duplicate-ip: true
    ip-switch: true

vpn-detection:
  enabled: true
  action: LOG          # LOG | WARN | KICK
  kick-message: "&cYou are not allowed to connect using a VPN or proxy."
  check-timeout: 5000  # milliseconds

tracking:
  warn-admins-duplicate: true
  warn-admins-ip-switch: true
  max-ips-per-player: 10

logging:
  console: true
  file: true
```

## Permissions

| Permission | Default | Description |
|---|---|---|
| `antiipbypass.admin` | OP | Use `/antiip` commands and receive in-game alerts |
| `antiipbypass.bypass` | false | Skip VPN checks |

## Commands

| Command | Description |
|---|---|
| `/antiip reload` | Reload `config.yml` and IP data |
| `/antiip lookup <player>` | Show all recorded IPs for a player |
| `/antiip checkip <ip>` | Manually run a VPN check on an IP address |
