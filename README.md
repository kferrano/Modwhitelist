# Mods Whitelist

A secure modpack enforcement mod for **Minecraft Forge and NeoForge**.

Due to changes in modern Minecraft networking, reliable client mod verification is no longer possible using a server-only approach.  
This project therefore uses a **server + lightweight client verification model** to ensure accurate and tamper-resistant mod checks.

***

## ✨ Features

*   **Required mods (Whitelist)**  
    Enforce that specific mods must be present on the client.
*   **Denied mods (Blacklist)**  
    Instantly disconnect players using forbidden mods.
*   **Strict mode**  
    When enabled, _only_ explicitly allowed mods are permitted.
*   **Client-only mod support (optional)**  
    Allow selected client-only mods (e.g. performance or visual mods) without compromising security.
*   **File integrity verification (SHA-256)**  
    Optional hardcore mode that enforces exact modpack integrity by validating mod file names **and hashes**.
*   **Automatic modpack generation**  
    The server can generate its required mod and file manifest directly from the current `/mods` folder.
*   **Controlled setup / collect workflow**  
    Safely approve client-only mods using a temporary setup mode restricted to trusted admin UUIDs.
*   **Configurable kick messages**  
    Custom messages and clickable modpack links for players.
*   **Server-authoritative**  
    All decisions are enforced by the server. No analytics, tracking, or third-party services.

***

## 🔒 Privacy / Data Protection

*   Only technical mod identifiers and optional file hashes are transmitted
*   No personal data is collected or stored
*   No tracking, analytics, or third-party services
*   Server logs only contain minimal moderation information (e.g. kick reasons)

***

## 🛠 Recommended Setup & Collection Workflow (Hardcore Mode)

To safely approve client-only mods, Mod Whitelist uses a **controlled setup mode** that is restricted to explicitly whitelisted admin UUIDs.

This prevents unauthorized players or operators from injecting additional client-only mods into the approved modpack.

### 🔐 Admin UUID Whitelist (Required)

Before starting the setup process, add the UUID of a **trusted server admin** to the following config entry:

```
"collectWhitelist": [
  "1696566b-f0c6-473c-89b0-0fi6d41z9608"
]
```

***

## 📜 Command Overview

All commands require operator permissions (permission level 3).

*   `/modwhitelist reload` Reloads the configuration file from disk.  
    Use this after manually editing `modwhitelist.json`.
*   `/modwhitelist generate` Regenerates the required mod list and the file (SHA-256) manifest  
    based on the current server `/mods` folder.  
    ⚠️ Existing configuration entries will be overwritten.
*   `/modwhitelist collectclientonly`  
    Enables the controlled setup / collection mode.

This command will:

*   temporarily disable `strict`
*   enable `collectClientOnly`
*   restrict joining to UUIDs listed in `collectWhitelist`

After a successful collection, the server automatically:

*   restores `strict`
*   disables `collectClientOnly`