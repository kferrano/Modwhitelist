package com.hardrock.modwhitelist;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonReader;
import com.hardrock.modwhitelist.network.Net;
import com.hardrock.modwhitelist.network.packet.ModScanRequestPacket;
import com.hardrock.modwhitelist.network.packet.ModScanResponsePacket;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.InputStreamReader;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Mod(Modwhitelist.MODID)
public final class Modwhitelist {
    public static final String MODID = "modwhitelist";
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String CONFIG_NAME = "modwhitelist.json";

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    // Pending nonce per player for scan request correlation
    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    // Loaded config singleton
    private static volatile Config config;

    public Modwhitelist() {
        Net.init();
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(new CommandHandler());
    }

    // ------------------------------------------------------------
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        loadConfig();
        if (config != null && !config.strict) {
            LOGGER.warn("[Modwhitelist] STRICT MODE IS DISABLED (strict=false). This should only be temporary for setup/collect.");
        }
        if (config != null && config.collectClientOnly) {
            LOGGER.warn("[Modwhitelist] COLLECT MODE IS ENABLED (collectClientOnly=true). Disable it after collecting client-only mods.");
        }
    }

    // ------------------------------------------------------------
    // Join: send scan request to player
    // ------------------------------------------------------------
    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer sp)) return;

        if (config == null) loadConfig();
        Config cfg = config;
        if (cfg == null) return;

        // Collect-Setup-Mode: only whitelisted UUIDs may join
        if (cfg.collectClientOnly) {
            if (!cfg.collectWhitelistUuidSet().contains(sp.getUUID())) {
                sp.getServer().execute(() -> {
                    Component msg = new TextComponent(
                            "Server is currently in modpack collection/setup mode.\n" +
                                    "Please try again later."
                    );
                    sp.connection.disconnect(msg);
                });
                return;
            }
        }

        long nonce = new Random().nextLong();
        if (nonce == 0L) nonce = 1L;

        PENDING.put(sp.getUUID(), new Pending(nonce, Instant.now().toEpochMilli()));

        Net.sendTo(sp, new ModScanRequestPacket(nonce));
    }

    // ------------------------------------------------------------
    // Called by Net on server thread (enqueueWork)
    // ------------------------------------------------------------
    public static void handleScanResponse(ServerPlayer sp, ModScanResponsePacket pkt) {
        if (sp == null) return;
        if (config == null) loadConfig();
        Config cfg = config;
        if (cfg == null) return;

        Pending pending = PENDING.remove(sp.getUUID());
        if (pending == null) {
            kick(sp, "Missing server scan context.\nPlease rejoin.");
            return;
        }

        // Expire pending quickly (prevents replay)
        long ageMs = Instant.now().toEpochMilli() - pending.createdAtMs;
        if (ageMs > 60_000L) {
            kick(sp, "Client scan timed out.\nPlease rejoin.");
            return;
        }

        if (pending.nonce != pkt.nonce()) {
            kick(sp, "Client scan nonce mismatch.\nPlease rejoin.");
            return;
        }

        // If strict=false: allow but keep deny enforcement optional (matches Neo behavior: strict governs enforcement)
        if (!cfg.strict) {
            // Still apply DENY if you want; Neo version applies deny inside strict path.
            // We keep it aligned: strict=false => no enforcement at all.
            return;
        }

        // ------------------------------------------------------------
        // 0) Deny list with globs
        // ------------------------------------------------------------
        LinkedHashSet<String> clientIds = pkt.modIds().stream()
                .filter(Objects::nonNull)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<String> foundDenied = clientIds.stream()
                .filter(id -> idMatchesAnyGlob(id, cfg.denyLower()))
                .toList();

        if (!foundDenied.isEmpty()) {
            kick(sp, "Disallowed mods detected:\n- " + String.join("\n- ", foundDenied));
            return;
        }

        // ------------------------------------------------------------
        // 1) Required mod IDs
        // ------------------------------------------------------------
        Set<String> required = cfg.allowed.stream()
                .filter(Objects::nonNull)
                .map(it -> it.modid)
                .filter(Objects::nonNull)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<String> missingRequired = required.stream()
                .filter(req -> !clientIds.contains(req))
                .toList();

        if (!missingRequired.isEmpty()) {
            kickWithPack(sp, cfg, "Missing required mods:\n- " + String.join("\n- ", missingRequired));
            return;
        }

        // ------------------------------------------------------------
// 2) Extra mod IDs (allowed client-only)
// ------------------------------------------------------------
        Set<String> allowedClientOnly = cfg.allowClientOnly.stream()
                .filter(Objects::nonNull)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        List<String> extras = clientIds.stream()
                .filter(id -> !required.contains(id))
                .filter(id -> !allowedClientOnly.contains(id))
                .toList();

        boolean collectedAnything = false;

        if (cfg.collectClientOnly) {
            // Collect extra mod IDs into allowClientOnly
            for (String extraId : extras) {
                String lower = extraId.toLowerCase(Locale.ROOT);
                if (!allowedClientOnly.contains(lower)) {
                    cfg.allowClientOnly.add(extraId); // keep original casing as received
                    allowedClientOnly.add(lower);
                    collectedAnything = true;
                }
            }
        } else {
            if (!extras.isEmpty()) {
                kickWithPack(sp, cfg,
                        "You have not allowed mods:\n- " + String.join("\n- ", extras));
                return;
            }
        }


        // ------------------------------------------------------------
        // 3) Hardcore file manifest (name+sha256)
        // ------------------------------------------------------------
        if (cfg.strictFiles) {
            Map<String, String> requiredFiles = toFileMap(cfg.allowedFiles);
            Map<String, String> optionalFiles = toFileMap(cfg.clientOnlyFiles);

            Map<String, String> gotFiles = new HashMap<>();
            for (ModScanResponsePacket.FileHash f : pkt.files()) {
                if (f == null || f.name() == null || f.sha256() == null) continue;
                gotFiles.put(f.name(), f.sha256().toLowerCase(Locale.ROOT));
            }

            // required: missing or mismatch => kick
            List<String> missing = requiredFiles.keySet().stream()
                    .filter(name -> !gotFiles.containsKey(name))
                    .toList();

            if (!missing.isEmpty()) {
                kickWithPack(sp, cfg,
                        "Missing required mod files:\n- " + String.join("\n- ", missing));
                return;
            }

            List<String> mismatch = requiredFiles.entrySet().stream()
                    .filter(e -> {
                        String got = gotFiles.get(e.getKey());
                        return got == null || !got.equalsIgnoreCase(e.getValue());
                    })
                    .map(Map.Entry::getKey)
                    .toList();

            if (!mismatch.isEmpty()) {
                kickWithPack(sp, cfg,
                        "Mod file hash mismatch:\n- " + String.join("\n- ", mismatch));
                return;
            }

            // optional: if present, mismatch => kick
            List<String> optMismatch = optionalFiles.entrySet().stream()
                    .filter(e -> gotFiles.containsKey(e.getKey()))
                    .filter(e -> !gotFiles.get(e.getKey()).equalsIgnoreCase(e.getValue()))
                    .map(Map.Entry::getKey)
                    .toList();

            if (!optMismatch.isEmpty()) {
                kickWithPack(sp, cfg,
                        "Client-only mod file hash mismatch:\n- " + String.join("\n- ", optMismatch));
                return;
            }

            // extras: any file not in required+optional => kick OR collect
            Set<String> allowedNames = new HashSet<>(requiredFiles.keySet());
            allowedNames.addAll(optionalFiles.keySet());

            List<Map.Entry<String, String>> extraFiles = gotFiles.entrySet().stream()
                    .filter(e -> !allowedNames.contains(e.getKey()))
                    .sorted(Map.Entry.comparingByKey())
                    .toList();

            if (!extraFiles.isEmpty()) {
                if (cfg.collectClientOnly) {
                    for (var entry : extraFiles) {
                        Config.FileRule r = new Config.FileRule();
                        r.name = entry.getKey();
                        r.sha256 = entry.getValue();
                        cfg.clientOnlyFiles.add(r);
                        collectedAnything = true;
                    }
                    LOGGER.warn("[Modwhitelist] Collected {} client-only files from {}",
                            extraFiles.size(), sp.getGameProfile().getName());
                } else {
                    kickWithPack(sp, cfg,
                            "Extra mod files detected:\n- " + extraFiles.stream()
                                    .map(Map.Entry::getKey)
                                    .collect(Collectors.joining("\n- ")));
                    return;
                }
            }
        }
        // ------------------------------------------------------------
// Collect mode auto-finalize:
// After first successful whitelisted join+scan, lock down again.
// ------------------------------------------------------------
        if (cfg.collectClientOnly) {
            // Always finalize after a successful scan in collect mode (whether or not we collected something),
            // because the user intent is: one-time setup phase.
            cfg.strict = true;
            cfg.collectClientOnly = false;

            // Optional: keep strictFiles as-is; typically true in hardcore mode.
            // cfg.strictFiles = true;
            normalizeAndSortConfig(cfg);
            saveConfig(cfg);
            config = cfg;

            LOGGER.warn("[Modwhitelist] Collect mode finalized by {}: strict=true, collectClientOnly=false, allowClientOnly={}, clientOnlyFiles={}",
                    sp.getGameProfile().getName(),
                    cfg.allowClientOnly.size(),
                    cfg.clientOnlyFiles.size());
        }

    }

    private static void normalizeAndSortConfig(Config cfg) {
        if (cfg == null) return;

        // --------------------
        // allowed (required mods)
        // --------------------
        cfg.allowed = cfg.allowed.stream()
                .filter(Objects::nonNull)
                .filter(it -> it.modid != null && !it.modid.isBlank())
                .collect(Collectors.toMap(
                        it -> it.modid.toLowerCase(Locale.ROOT),
                        it -> it,
                        (a, b) -> a,
                        LinkedHashMap::new
                ))
                .values().stream()
                .sorted(Comparator.comparing(it -> it.modid.toLowerCase(Locale.ROOT)))
                .toList();

        // --------------------
        // allowClientOnly
        // --------------------
        cfg.allowClientOnly = cfg.allowClientOnly.stream()
                .filter(Objects::nonNull)
                .filter(s -> !s.isBlank())
                .map(String::trim)
                .collect(Collectors.toCollection(() -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER)))
                .stream()
                .toList();

        // --------------------
        // deny (optional but recommended)
        // --------------------
        cfg.deny = cfg.deny.stream()
                .filter(Objects::nonNull)
                .filter(s -> !s.isBlank())
                .map(String::trim)
                .collect(Collectors.toCollection(() -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER)))
                .stream()
                .toList();

        // --------------------
        // allowedFiles
        // --------------------
        cfg.allowedFiles = cfg.allowedFiles.stream()
                .filter(Objects::nonNull)
                .filter(f -> f.name != null && f.sha256 != null)
                .collect(Collectors.toMap(
                        f -> f.name.toLowerCase(Locale.ROOT),
                        f -> f,
                        (a, b) -> a,
                        LinkedHashMap::new
                ))
                .values().stream()
                .sorted(Comparator.comparing(f -> f.name.toLowerCase(Locale.ROOT)))
                .toList();

        // --------------------
        // clientOnlyFiles
        // --------------------
        cfg.clientOnlyFiles = cfg.clientOnlyFiles.stream()
                .filter(Objects::nonNull)
                .filter(f -> f.name != null && f.sha256 != null)
                .collect(Collectors.toMap(
                        f -> f.name.toLowerCase(Locale.ROOT),
                        f -> f,
                        (a, b) -> a,
                        LinkedHashMap::new
                ))
                .values().stream()
                .sorted(Comparator.comparing(f -> f.name.toLowerCase(Locale.ROOT)))
                .toList();
    }


    // ------------------------------------------------------------
    // Commands API (called from CommandHandler)
    // ------------------------------------------------------------
    public static void reloadConfig() {
        loadConfig();
    }

    public static Config generateHardcoreConfigForCommand() {
        return generateHardcoreConfigFromServer();
    }

    public static void setCollectClientOnly(boolean enabled) {
        Config cfg = config;
        if (cfg == null) loadConfig();
        cfg = config;
        if (cfg == null) return;
        cfg.collectClientOnly = enabled;
        saveConfig(cfg);
    }

    public static void setStrict(boolean enabled) {
        Config cfg = config;
        if (cfg == null) loadConfig();
        cfg = config;
        if (cfg == null) return;
        cfg.strict = enabled;
        saveConfig(cfg);
    }

    public static void clearClientOnlyFiles() {
        Config cfg = config;
        if (cfg == null) loadConfig();
        cfg = config;
        if (cfg == null) return;
        cfg.clientOnlyFiles.clear();
        normalizeAndSortConfig(cfg);
        saveConfig(cfg);
    }

    // ------------------------------------------------------------
    // Config load/save
    // ------------------------------------------------------------
    private static Path configPath() {
        return FMLPaths.CONFIGDIR.get().resolve(CONFIG_NAME);
    }

    private static synchronized void loadConfig() {
        try {
            Path cfg = configPath();

            if (!Files.exists(cfg)) {
                Config created = Config.defaultConfig();
                saveConfig(created);
                config = created;
                LOGGER.warn("[Modwhitelist] Created default config at {}", cfg.toAbsolutePath());
                return;
            }

            try (JsonReader r = new JsonReader(new InputStreamReader(Files.newInputStream(cfg)))) {
                Config loaded = GSON.fromJson(r, Config.class);
                if (loaded == null) loaded = Config.defaultConfig();
                config = loaded;
            }

            LOGGER.info("[Modwhitelist] Loaded config: strict={}, strictFiles={}, allowedFiles={}, clientOnlyFiles={}, collectClientOnly={}, collectWhitelist={}",
                    config.strict,
                    config.strictFiles,
                    config.allowedFiles.size(),
                    config.clientOnlyFiles.size(),
                    config.collectClientOnly,
                    config.collectWhitelist.size()
            );
        } catch (Exception ex) {
            LOGGER.error("[Modwhitelist] Failed to load config", ex);
            config = Config.defaultConfig();
        }
    }

    private static synchronized void saveConfig(Config cfg) {
        try {
            Path p = configPath();
            Files.createDirectories(p.getParent());
            Files.writeString(p, GSON.toJson(cfg), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (Exception ex) {
            LOGGER.error("[Modwhitelist] Failed to save config", ex);
        }
    }

    private static Config generateHardcoreConfigFromServer() {
        Config c = new Config();
        c._comment = Config.defaultConfig()._comment;
        c.strict = true;
        c.strictFiles = true;

        c.customMessage = "Please use the official modpack.";
        c.packLink = "";

        // Loaded mods (IDs + Version) - informational
        ModList.get().getMods().forEach(mod -> {
            Config.Item item = new Config.Item();
            item.modid = mod.getModId();
            item.version = (mod.getVersion() != null) ? mod.getVersion().toString() : "*";
            c.allowed.add(item);
        });

        // Required files manifest (/mods name+sha256)
        c.allowedFiles = generateFileManifestFromModsFolder();

        // clientOnlyFiles stays empty; can be collected by whitelisted UUIDs
        return c;
    }

    private static List<Config.FileRule> generateFileManifestFromModsFolder() {
        try {
            Path modsDir = FMLPaths.MODSDIR.get();
            if (!Files.isDirectory(modsDir)) return List.of();

            List<Config.FileRule> out = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(modsDir)) {
                for (Path p : ds) {
                    if (!Files.isRegularFile(p)) continue;
                    String name = p.getFileName().toString();
                    if (!(name.endsWith(".jar") || name.endsWith(".zip"))) continue;

                    Config.FileRule r = new Config.FileRule();
                    r.name = name;
                    r.sha256 = sha256Hex(p);
                    out.add(r);
                }
            }
            out.sort(Comparator.comparing(a -> a.name.toLowerCase(Locale.ROOT)));
            return out;
        } catch (Exception ex) {
            LOGGER.error("[Modwhitelist] Failed to generate file manifest", ex);
            return List.of();
        }
    }

    // ------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------
    private static void kick(ServerPlayer sp, String reason) {
        sp.getServer().execute(() -> {
            Component msg = new TextComponent(reason);
            sp.connection.disconnect(msg);
        });
    }

    private static void kickWithPack(ServerPlayer sp, Config cfg, String reason) {
        String msg = reason;

        if (cfg.customMessage != null && !cfg.customMessage.isBlank()) {
            msg += "\n\n" + cfg.customMessage;
        }

        if (cfg.packLink != null && !cfg.packLink.isBlank()) {
            msg += "\n" + cfg.packLink;
        }

        Component component = new TextComponent(msg)
                .withStyle(ChatFormatting.RED);

        sp.getServer().execute(() -> sp.connection.disconnect(component));
    }

    private static boolean idMatchesAnyGlob(String idLower, List<String> globsLower) {
        for (String glob : globsLower) {
            if (glob == null || glob.isBlank()) continue;
            if (globMatch(idLower, glob.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    // Simple glob matcher: '*' any, '?' one char
    private static boolean globMatch(String text, String glob) {
        int t = 0, g = 0, star = -1, mark = -1;
        while (t < text.length()) {
            if (g < glob.length() && (glob.charAt(g) == '?' || glob.charAt(g) == text.charAt(t))) {
                t++; g++;
            } else if (g < glob.length() && glob.charAt(g) == '*') {
                star = g++;
                mark = t;
            } else if (star != -1) {
                g = star + 1;
                t = ++mark;
            } else {
                return false;
            }
        }
        while (g < glob.length() && glob.charAt(g) == '*') g++;
        return g == glob.length();
    }

    private static Map<String, String> toFileMap(List<Config.FileRule> rules) {
        Map<String, String> m = new HashMap<>();
        if (rules == null) return m;
        for (Config.FileRule r : rules) {
            if (r == null || r.name == null || r.sha256 == null) continue;
            m.put(r.name, r.sha256.toLowerCase(Locale.ROOT));
        }
        return m;
    }

    private static String sha256Hex(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(file, StandardOpenOption.READ)) {
            byte[] buf = new byte[1024 * 1024];
            int r;
            while ((r = in.read(buf)) != -1) md.update(buf, 0, r);
        }
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private record Pending(long nonce, long createdAtMs) {}

    // ------------------------------------------------------------
    // Config model (matches Neo logic)
    // ------------------------------------------------------------
    public static final class Config {
        public List<String> _comment = new ArrayList<>();

        public boolean strict = true;

        /** Required mod IDs (players must have them). Version is informational. */
        public List<Item> allowed = new ArrayList<>();

        /** Optional client-only mod IDs allowed in strict mode (ID-level only). */
        public List<String> allowClientOnly = new ArrayList<>();

        /** Hard-banned mod IDs (supports * and ? wildcards). */
        public List<String> deny = new ArrayList<>();

        public String packLink = "";
        public String customMessage = "";

        /** Hardcore: enforce /mods folder via SHA-256 */
        public boolean strictFiles = true;

        /** Required files (server pack) */
        public List<FileRule> allowedFiles = new ArrayList<>();

        /** Optional client-only files allowed in addition to allowedFiles */
        public List<FileRule> clientOnlyFiles = new ArrayList<>();

        /**
         * Collect-mode switch.
         * IMPORTANT: The whitelist is ONLY in this config (no command for it).
         */
        public boolean collectClientOnly = false;

        /** UUIDs allowed to collect client-only files while collectClientOnly=true */
        public List<String> collectWhitelist = new ArrayList<>();

        public List<String> denyLower() {
            return deny.stream()
                    .filter(Objects::nonNull)
                    .map(s -> s.toLowerCase(Locale.ROOT))
                    .toList();
        }

        public Set<UUID> collectWhitelistUuidSet() {
            if (collectWhitelist == null || collectWhitelist.isEmpty()) return Set.of();
            Set<UUID> s = new HashSet<>();
            for (String u : collectWhitelist) {
                if (u == null || u.isBlank()) continue;
                try {
                    s.add(UUID.fromString(u.trim()));
                } catch (IllegalArgumentException ignored) {}
            }
            return s;
        }

        public static final class Item {
            public String modid;
            public String version = "*";
        }

        public static final class FileRule {
            public String name;
            public String sha256;
        }

        public static Config defaultConfig() {
            Config c = new Config();
            c._comment = List.of(
                    "Option B (Hardcore + client-only allowed):",
                    "- strictFiles=true enforces allowedFiles (required) + clientOnlyFiles (optional) by name+sha256",
                    "- required: missing/mismatch kicks",
                    "- optional: if present, hash mismatch kicks",
                    "- extras (not in required+optional) kicks",
                    "",
                    "Collect mode (solves chicken-egg):",
                    "- collectClientOnly=true AND your UUID is in collectWhitelist",
                    "  -> you may join once with extra client mods; extras are collected into clientOnlyFiles",
                    "  -> required files are STILL enforced; you cannot bypass the server pack",
                    "",
                    "collectWhitelist is ONLY editable in this config file."
            );

            c.strict = true;
            c.strictFiles = true;
            c.customMessage = "Please use the official modpack.";
            c.packLink = "https://www.curseforge.com/minecraft/modpacks/YOURPACK";

            // Example:
            // c.collectClientOnly = true;
            // c.collectWhitelist = List.of("00000000-0000-0000-0000-000000000000");
            return c;
        }
    }

    public static void writeConfig(Modwhitelist.Config cfg) {
        if (cfg == null) return;
        // überschreibt config/modwhitelist.json
        saveConfig(cfg);
        config = cfg; // direkt aktiv setzen
    }

    public static void generateAndWriteHardcoreConfig() {
        Config c = generateHardcoreConfigFromServer();
        normalizeAndSortConfig(c);
        saveConfig(c);
        config = c;
    }

}
