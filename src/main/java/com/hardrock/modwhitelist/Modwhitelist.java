package com.hardrock.modwhitelist;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonReader;
import com.hardrock.modwhitelist.network.Net;
import com.hardrock.modwhitelist.network.payload.ModScanResponsePayload;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.fml.ModContainer;

import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Hardcore + Option B:
 * - strictFiles=true enforces required allowedFiles + optional clientOnlyFiles by (name+sha256)
 * - collectClientOnly=true allows whitelisted UUIDs (collectWhitelist) to JOIN ONCE with extra client mods:
 *   - required files are still enforced (missing/mismatch -> kick)
 *   - extra files are collected into clientOnlyFiles and are NOT kicked for that collector join
 *
 * collectWhitelist is ONLY editable in the config file (no command API here).
 */
@Mod(Modwhitelist.MODID)
public class Modwhitelist {
    public static final String MODID = "modwhitelist";

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CONFIG_NAME = "modwhitelist.json";

    private static final SecureRandom RNG = new SecureRandom();

    private static final Object CONFIG_LOCK = new Object();
    private static volatile Config config = null;
    private static volatile Path configPath = null;


    /** pending nonce per player UUID */
    private static final Map<UUID, Long> pendingNonce = new ConcurrentHashMap<>();

    /**
     * IMPORTANT: In NeoForge 1.21.x the payload registration must be on the MOD bus.
     * We get the MOD bus injected into the mod constructor.
     */
    public Modwhitelist(IEventBus modBus) {
        // Game bus events
        NeoForge.EVENT_BUS.register(this);

        // Commands (if you use CommandHandler)
        NeoForge.EVENT_BUS.register(CommandHandler.class);

        // MOD bus: payload registration (this is REQUIRED or sendToPlayer will throw "may not be sent")
        modBus.addListener(Net::register);

        // Register config
        LOGGER.info("[Modwhitelist] Initialized (NeoForge 1.21.1 / NeoForge 21.1.x both-side mode)");
    }

    // ------------------------------------------------------------
    // Server lifecycle -> Config nur serverseitig laden/erstellen
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
    // Login -> Request senden
    // ------------------------------------------------------------
    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer sp)) return;

        if (config == null) loadConfig();

        // Collect-Setup-Mode: nur UUIDs aus der Config-Whitelist dürfen joinen
        if (config != null && config.collectClientOnly) {
            if (!config.collectWhitelistUuidSet().contains(sp.getUUID())) {
                sp.getServer().execute(() -> {
                    sp.connection.disconnect(
                            Component.literal(
                                    "Server is currently in modpack collection/setup mode.\n" +
                                            "Please try again later."
                            )
                    );
                });

                return;
            }
        }

        long nonce = RNG.nextLong();
        pendingNonce.put(sp.getUUID(), nonce);

        Net.sendScanRequest(sp, nonce);
        LOGGER.info("[Modwhitelist] Sent scan request to {} (nonce={})", sp.getGameProfile().getName(), nonce);
    }

    // ------------------------------------------------------------
    // Response vom Client kommt über Net.java (playToServer handler)
    // ------------------------------------------------------------
    public static void handleScanResponse(ServerPlayer sp, ModScanResponsePayload payload) {
        Config cfg = config;
        if (cfg == null) {
            kick(sp, "Server config not loaded yet. Please rejoin.");
            return;
        }

        Long expected = pendingNonce.remove(sp.getUUID());
        if (expected == null) {
            kick(sp, "Mod scan protocol error: no pending request.");
            return;
        }
        if (payload.nonce() != expected) {
            kick(sp, "Mod scan protocol error: nonce mismatch.");
            return;
        }

        // Mod IDs (vom Client gemeldet)
        LinkedHashSet<String> clientIds = payload.modIds().stream()
                .filter(Objects::nonNull)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // 1) Deny (mit Wildcards)
        List<String> foundDenied = clientIds.stream()
                .filter(id -> idMatchesAnyGlob(id, cfg.denyLower()))
                .toList();

        if (!foundDenied.isEmpty()) {
            kick(sp, "Disallowed mods detected: " + String.join(", ", foundDenied)
                    + "\nPlease remove these mods and restart.");
            return;
        }

        // 2) Required (allowed)
        List<String> requiredLower = cfg.allowedLower();
        if (!requiredLower.isEmpty()) {
            List<String> missing = requiredLower.stream()
                    .filter(req -> !clientIds.contains(req))
                    .toList();

            if (!missing.isEmpty()) {
                kick(sp, "Missing required mods: " + String.join(", ", missing)
                        + "\nPlease install the official modpack.");
                return;
            }
        }

        // 3) Strict: alles blocken, was nicht allowed/optional ist (ID-level)
        if (cfg.strict) {
            Set<String> allowedAll = new HashSet<>();
            allowedAll.addAll(requiredLower);
            allowedAll.addAll(cfg.allowClientOnlyLower());

            List<String> extras = clientIds.stream()
                    .filter(id -> !allowedAll.contains(id))
                    .filter(id -> !(id.equals("minecraft") || id.equals("neoforge") || id.startsWith("fml")))
                    .toList();

            if (!extras.isEmpty()) {
                kick(sp, "Additional not allowed mods: " + String.join(", ", extras)
                        + "\nUse only the provided modpack.");
                return;
            }
        }

        // 4) Hardcore Option B: File-Manifest (Name + SHA256)
        if (cfg.strictFiles) {
            Map<String, String> requiredFiles = toFileMap(cfg.allowedFiles);
            Map<String, String> optionalFiles = toFileMap(cfg.clientOnlyFiles);

            Map<String, String> gotFiles = new HashMap<>();
            for (ModScanResponsePayload.FileHash f : payload.files()) {
                if (f == null || f.name() == null || f.sha256() == null) continue;
                gotFiles.put(f.name(), f.sha256().toLowerCase(Locale.ROOT));
            }

            // === COLLECT GRACE (Henne-Ei Fix) ===
            // Whitelisted collector may join once with extra client-only files:
            // - still enforces required files
            // - collects extras into clientOnlyFiles
            // - does NOT kick for extras on that join
            boolean isCollector = cfg.collectClientOnly && cfg.collectWhitelistUuidSet().contains(sp.getUUID());

            if (isCollector) {
                int added = collectClientOnlyFiles(cfg, requiredFiles, optionalFiles, gotFiles);
                if (added > 0) {
                    cfg.collectClientOnly = false;
                    cfg.strict = true;

                    saveConfig(cfg);
                    // rebuild optional map so checks see the new entries
                    optionalFiles = toFileMap(cfg.clientOnlyFiles);

                    sp.sendSystemMessage(Component.literal(
                            "[Modwhitelist] Collected " + added + " client-only file(s). Collect mode is now disabled and strict mode is enabled."
                    ));

                    LOGGER.warn("[Modwhitelist] Collect completed and auto-disabled.");
                }
            }

            // Missing or hash mismatch ONLY for requiredFiles (always hard)
            List<String> missingOrMismatch = new ArrayList<>();
            for (var ex : requiredFiles.entrySet()) {
                String name = ex.getKey();
                String expHash = ex.getValue();
                String gotHash = gotFiles.get(name);

                if (gotHash == null) {
                    missingOrMismatch.add(name + " (missing)");
                } else if (!gotHash.equals(expHash)) {
                    missingOrMismatch.add(name + " (hash mismatch)");
                }
            }

            // Optional files: if present, must match hash too (but in collect-mode we allow building list)
            List<String> optionalMismatch = new ArrayList<>();
            for (var opt : optionalFiles.entrySet()) {
                String name = opt.getKey();
                String expHash = opt.getValue();
                String gotHash = gotFiles.get(name);
                if (gotHash != null && !gotHash.equals(expHash)) {
                    optionalMismatch.add(name + " (hash mismatch)");
                }
            }

            // Extras: files not in (required ∪ optional)
            Set<String> expectedAllNames = new HashSet<>();
            expectedAllNames.addAll(requiredFiles.keySet());
            expectedAllNames.addAll(optionalFiles.keySet());

            List<String> extras = gotFiles.keySet().stream()
                    .filter(name -> !expectedAllNames.contains(name))
                    .sorted()
                    .toList();

            // === Enforcements with collector exception ===

            // REQUIRED always hard
            if (!missingOrMismatch.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                sb.append("Modpack integrity check failed.\n");
                sb.append("Required issues: ").append(String.join(", ", missingOrMismatch)).append("\n");
                sb.append("Please reinstall the official modpack.");
                kick(sp, sb.toString());
                return;
            }

            // OPTIONAL mismatches: hard unless collector (during collection we allow transitions)
            if (!optionalMismatch.isEmpty() && !isCollector) {
                StringBuilder sb = new StringBuilder();
                sb.append("Modpack integrity check failed.\n");
                sb.append("Client-only issues: ").append(String.join(", ", optionalMismatch)).append("\n");
                sb.append("Please reinstall the official modpack.");
                kick(sp, sb.toString());
                return;
            }

            // EXTRAS: hard unless collector (this is the key to allow first collect join)
            if (!extras.isEmpty() && !isCollector) {
                StringBuilder sb = new StringBuilder();
                sb.append("Modpack integrity check failed.\n");
                sb.append("Extra files: ").append(String.join(", ", extras)).append("\n");
                sb.append("Please reinstall the official modpack.");
                kick(sp, sb.toString());
                return;
            }
        }

        LOGGER.info("[Modwhitelist] {} passed mod check ({} mods, {} files)",
                sp.getGameProfile().getName(), clientIds.size(), payload.files().size());
    }

    // ------------------------------------------------------------
    // Public helpers for CommandHandler
    // ------------------------------------------------------------
    public static void reloadConfig() {
        loadConfig();
    }

    /** Generates hardcore config (required mods + required file manifest), clientOnlyFiles stays empty. */
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
        saveConfig(cfg);
    }

    public static void writeConfig(Config cfg) throws IOException {
        Path p = ensureConfigPath();
        Files.createDirectories(p.getParent());
        Files.writeString(p, GSON.toJson(cfg), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        config = cfg;
    }

    // ------------------------------------------------------------
    // Config load/save
    // ------------------------------------------------------------
    private static void loadConfig() {
        synchronized (CONFIG_LOCK) {
            try {
                Path cfg = ensureConfigPath();

                if (!Files.exists(cfg)) {
                    Files.createDirectories(cfg.getParent());
                    Config gen = generateHardcoreConfigFromServer();
                    Files.writeString(cfg, GSON.toJson(gen));
                    config = gen;
                    LOGGER.warn("[Modwhitelist] Created generated {}", cfg.toAbsolutePath());
                    return;
                }

                try (JsonReader r = new JsonReader(new InputStreamReader(Files.newInputStream(cfg)))) {
                    Config loaded = GSON.fromJson(r, Config.class);
                    if (loaded == null) loaded = Config.defaultConfig();
                    config = loaded;
                }

                LOGGER.info("[Modwhitelist] Loaded config: strict={}, strictFiles={}, requiredFiles={}, clientOnlyFiles={}, collectClientOnly={}, collectWhitelist={}",
                        config.strict,
                        config.strictFiles,
                        config.allowedFiles.size(),
                        config.clientOnlyFiles.size(),
                        config.collectClientOnly,
                        config.collectWhitelist.size()
                );
            } catch (IOException e) {
                LOGGER.error("[Modwhitelist] Failed to load config, using generated fallback", e);
                config = generateHardcoreConfigFromServer();
            }
        }
    }

    private static void saveConfig(Config cfg) {
        synchronized (CONFIG_LOCK) {
            try {
                Path p = ensureConfigPath();
                Files.createDirectories(p.getParent());
                Files.writeString(p, GSON.toJson(cfg), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                config = cfg;
            } catch (IOException e) {
                LOGGER.error("[Modwhitelist] Failed to save config", e);
            }
        }
    }

    private static Path ensureConfigPath() throws IOException {
        Path p = configPath;
        if (p != null) return p;
        Path cfgDir = FMLPaths.CONFIGDIR.get();
        Path cfg = cfgDir.resolve(CONFIG_NAME);
        configPath = cfg;
        return cfg;
    }

    // ------------------------------------------------------------
    // Hardcore generator: Loaded Mods + Required File Manifest from server /mods
    // ------------------------------------------------------------
    private static Config generateHardcoreConfigFromServer() {
        Config c = new Config();
        c._comment = Config.defaultConfig()._comment;
        c.strict = true;
        c.strictFiles = true;
        c.ENABLE_UPDATE_CHECK = true;
        c.UPDATE_CHECK_TIMEOUT_MS = 4000;

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

        // Option B: clientOnlyFiles stays empty; can be collected by whitelisted UUIDs
        return c;
    }

    private static List<Config.FileRule> generateFileManifestFromModsFolder() {
        try {
            Path modsDir = Paths.get("").toAbsolutePath().resolve("mods");
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
        } catch (Exception e) {
            LOGGER.error("[Modwhitelist] Failed to generate required file manifest", e);
            return List.of();
        }
    }

    private static String sha256Hex(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file, StandardOpenOption.READ)) {
            byte[] buf = new byte[1024 * 1024];
            int r;
            while ((r = in.read(buf)) != -1) {
                md.update(buf, 0, r);
            }
        }
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    // ------------------------------------------------------------
    // File helpers (Option B)
    // ------------------------------------------------------------
    private static Map<String, String> toFileMap(List<Config.FileRule> rules) {
        Map<String, String> m = new HashMap<>();
        if (rules == null) return m;
        for (Config.FileRule r : rules) {
            if (r == null || r.name == null || r.sha256 == null) continue;
            m.put(r.name, r.sha256.toLowerCase(Locale.ROOT));
        }
        return m;
    }

    /**
     * Adds any client files not in requiredFiles/optionalFiles into cfg.clientOnlyFiles.
     * Returns number of added entries.
     */
    private static int collectClientOnlyFiles(Config cfg,
                                              Map<String, String> requiredFiles,
                                              Map<String, String> optionalFiles,
                                              Map<String, String> gotFiles) {
        int added = 0;
        Set<String> optionalNames = new HashSet<>(optionalFiles.keySet());

        for (var entry : gotFiles.entrySet()) {
            String name = entry.getKey();
            String hash = entry.getValue();

            if (requiredFiles.containsKey(name)) continue;
            if (optionalNames.contains(name)) continue;

            Config.FileRule r = new Config.FileRule();
            r.name = name;
            r.sha256 = hash;
            cfg.clientOnlyFiles.add(r);

            optionalNames.add(name);
            added++;
        }

        cfg.clientOnlyFiles.sort(Comparator.comparing(a -> a.name.toLowerCase(Locale.ROOT)));
        return added;
    }

    // ------------------------------------------------------------
    // Kick with link + optional customMessage
    // ------------------------------------------------------------
    private static void kick(ServerPlayer sp, String reason) {
        Config cfg = config;

        Component msg = Component.literal(reason);

        if (cfg != null && cfg.customMessage != null && !cfg.customMessage.isBlank()) {
            msg = Component.literal(cfg.customMessage + "\n").append(msg);
        }

        if (cfg != null && cfg.packLink != null && !cfg.packLink.isBlank()) {
            Component label = Component.literal("\nOfficial Modpack: ");
            Component url = Component.literal(cfg.packLink)
                    .setStyle(Style.EMPTY.withColor(ChatFormatting.BLUE).withUnderlined(true));
            msg = Component.literal("").append(msg).append(label).append(url);
        }

        sp.connection.disconnect(msg);
        LOGGER.warn("[Modwhitelist] Kicked {}: {}", sp.getGameProfile().getName(), reason);
    }

    // ------------------------------------------------------------
    // Glob-Wildcards (* und ?)
    // ------------------------------------------------------------
    private static String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder(glob.length() * 2);
        sb.append('^');
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append('.');
                case '.', '(', ')', '+', '|', '^', '$', '@', '%', '{', '}', '[', ']', '\\' -> sb.append('\\').append(c);
                default -> sb.append(c);
            }
        }
        sb.append('$');
        return sb.toString();
    }

    private static boolean idMatchesAnyGlob(String idLower, List<String> patternsLower) {
        for (String p : patternsLower) {
            if (idLower.matches(globToRegex(p))) return true;
        }
        return false;
    }

    // ------------------------------------------------------------
    // Configuration (JSON)
    // ------------------------------------------------------------
    public static class Config {
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

        public boolean ENABLE_UPDATE_CHECK = true;
        public int UPDATE_CHECK_TIMEOUT_MS = 4000;

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

        public static class Item {
            public String modid;
            public String version = "*";
        }

        public static class FileRule {
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

        public List<String> allowedLower() {
            return allowed.stream()
                    .map(i -> i.modid == null ? "" : i.modid.toLowerCase(Locale.ROOT))
                    .filter(s -> !s.isBlank())
                    .toList();
        }

        public List<String> allowClientOnlyLower() {
            return allowClientOnly.stream()
                    .filter(Objects::nonNull)
                    .map(s -> s.toLowerCase(Locale.ROOT))
                    .toList();
        }

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
                } catch (IllegalArgumentException ignored) {
                }
            }
            return s;
        }
    }
}
