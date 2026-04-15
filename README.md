# Mods Whitelist

A secure modpack enforcement mod for **Minecraft Forge and NeoForge**.

__**This description currently only applies to Minecraft 1.21.1-2.0.0 and newer. Other versions will be updated gradually to match the same feature and config structure over time.**__

Due to changes in modern Minecraft networking, reliable client mod verification is no longer possible using a server-only approach.  
This project therefore uses a **server + lightweight client verification model** to provide accurate and tamper-resistant mod checks.

***

## ✨ Features

*   **Both-side required mods**  
    Enforce mods that must exist on both server and client.
*   **Client-only required mods**  
    Require specific client-side mods even when they are not installed on the server.
*   **Client-only optional mods**  
    Allow selected client-side mods such as performance, QoL, or visual mods without making them mandatory.
*   **Server-only mod separation**  
    Keep server-only mods documented separately so they do not end up in the required client list.
*   **Denied mods and files**  
    Instantly reject players using forbidden mods or blocked jar files.
*   **Strict mode**  
    When enabled, only explicitly allowed mods and files are accepted.
*   **File integrity verification (SHA-256)**  
    Optional hardcore mode that verifies exact filenames and hashes.
*   **Controlled collect workflow**  
    Automatically classify mods and files using a trusted reference client.
*   **Legacy config migration**  
    Automatically migrates old single-file configs to the new multi-file structure.
*   **Configurable kick messages**  
    Supports custom messages, clickable pack links, and clearer formatted kick reasons.
*   **Server-authoritative**  
    All decisions are enforced by the server. No analytics, tracking, or third-party services.

***

## 📁 Config Structure

Modwhitelist uses a multi-file config layout:

```
config/modwhitelist/
  settings.json
  both_side_required.json
  client_required.json
  client_optional.json
  server_only.json
  deny.json
```

### File overview

#### `settings.json`

Global settings such as:

*   `strict`
*   `strictFiles`
*   `collectMode`
*   `collectWhitelist`
*   `customMessage`
*   `packLink`

#### `both_side_required.json`

Mods and files that must exist on both server and client.

#### `client_required.json`

Client-only mods and files that are still required.

#### `client_optional.json`

Client-only mods and files that are allowed but optional.

#### `server_only.json`

Mods and files that are only used on the server.

#### `deny.json`

Hard blacklist for forbidden mods and files.

***

## 🔒 Privacy / Data Protection

*   Only technical mod identifiers and optional file hashes are transmitted
*   No personal data is collected or stored
*   No tracking, analytics, or third-party services
*   Server logs only contain minimal moderation information such as kick reasons

***

## 🛠 Recommended Setup / Collect Workflow

Modwhitelist includes a controlled collect mode to help generate the initial config structure safely.

### 1\. Start the server once

On first start, Modwhitelist creates the config folder and the required JSON files automatically.

### 2\. Add a trusted admin UUID

Open `config/modwhitelist/settings.json` and add the UUID of a trusted admin to `collectWhitelist`.

Example:

```
{
  "collectWhitelist": [
    "1696566b-f0c6-473c-89b0-0f16d41a9608"
  ]
}
```

Only players listed there are allowed to join while collect mode is active.

### 3\. Enable collect mode

Run:

```
/modwhitelist collect on
```

This temporarily disables strict mode and allows the trusted setup client to join.

### 4\. Join with the reference client pack

Join the server once with the exact client setup you want to use as your baseline.

During this process, Modwhitelist compares:

*   server mods/files
*   client mods/files

### 5\. Automatic classification

After the trusted client joins successfully, Modwhitelist automatically classifies entries into:

*   `both_side_required.json`
*   `client_optional.json`
*   `server_only.json`

### 6\. Review client-only entries

Any client-only mods detected during collect are placed into `client_optional.json` by default.

If some of these client-only mods should be mandatory, move them manually from:

*   `client_optional.json`

to:

*   `client_required.json`

This is intentional, because only the server admin can decide which client-only mods should actually be required.

### 7\. Collect mode finishes automatically

After a successful collect run:

*   `collectMode` is disabled automatically
*   `strict` is restored automatically

### Recommended setup logic

Use the files like this:

*   `both_side_required.json` for mods required on both sides
*   `client_required.json` for client-only mandatory mods
*   `client_optional.json` for client-only allowed mods
*   `server_only.json` for documentation and separation of server-only mods
*   `deny.json` for mods or files that should never be allowed

***

## 📜 Commands

All commands require operator permissions.

### `/modwhitelist reload`

Reloads all config files from disk.

Use this after manually editing the JSON files.

### `/modwhitelist init`

Creates the multi-file config structure if it does not exist yet.

In most cases this is only needed for fresh setups. If configs already exist or were migrated automatically, you usually do not need this command.

### `/modwhitelist collect on`

Enables collect mode and temporarily disables strict mode.

### `/modwhitelist collect off`

Disables collect mode.

### `/modwhitelist collect clear`

Clears the automatically collected manifests:

*   `both_side_required.json`
*   `client_optional.json`
*   `server_only.json`

This does **not** clear `client_required.json`.

***

## 🚫 deny.json

`deny.json` is the hard blacklist.

Everything listed here is always blocked, even when `strict=false`.

Use it for:

*   cheat mods
*   xray mods
*   incompatible mods
*   specific jar files you never want on the server

### Format

```
{
  "mods": [],
  "files": []
}
```

### Block mods by mod ID

```
{
  "mods": [
    "xray",
    "freecam",
    "example*"
  ],
  "files": []
}
```

Notes:

*   `xray` blocks exactly that mod ID
*   `example*` blocks everything starting with `example`
*   wildcard matching with `*` is supported

### Block files by filename

```
{
  "mods": [],
  "files": [
    {
      "name": "badmod.jar",
      "sha256": "*"
    }
  ]
}
```

This blocks the file by filename only.

### Block one exact file version

```
{
  "mods": [],
  "files": [
    {
      "name": "badmod-1.0.0.jar",
      "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
  ]
}
```

This only blocks that exact file version.

### Recommended usage

*   Use `mods` for normal blacklist entries
*   Use `files` with `sha256: "*"` if you want to block by jar filename
*   Use a real SHA-256 hash only if you want to block one exact file version

`deny.json` is checked before normal whitelist and strict handling.

That means:

*   matching entries are rejected immediately
*   this also applies when `strict=false`

***


## Notes

*   Old single-file configs are migrated automatically on first start
*   Auto-collect overwrites:
    *   `both_side_required.json`
    *   `client_optional.json`
    *   `server_only.json`
*   `client_required.json` is intentionally kept manual so admins can decide which client-only mods are truly required
*   Colored kick messages help players immediately see what is missing, blocked, or not allowed
