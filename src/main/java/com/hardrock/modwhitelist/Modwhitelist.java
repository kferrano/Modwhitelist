package com.hardrock.modwhitelist;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonReader;
import com.hardrock.modwhitelist.network.Net;
import com.hardrock.modwhitelist.network.payload.ModScanChunkPayload;
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
import org.slf4j.Logger;
import net.minecraft.network.chat.MutableComponent;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Mod(Modwhitelist.MODID)
public class Modwhitelist {
    public static final String MODID = "modwhitelist";

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final String LEGACY_CONFIG_NAME = "modwhitelist.json";
    private static final String CONFIG_DIR_NAME = "modwhitelist";
    private static final String SETTINGS_FILE = "settings.json";
    private static final String BOTH_REQUIRED_FILE = "both_side_required.json";
    private static final String CLIENT_REQUIRED_FILE = "client_required.json";
    private static final String CLIENT_OPTIONAL_FILE = "client_optional.json";
    private static final String SERVER_ONLY_FILE = "server_only.json";
    private static final String DENY_FILE = "deny.json";

    private static final SecureRandom RNG = new SecureRandom();
    private static final Object CONFIG_LOCK = new Object();

    private static volatile RuntimeConfig runtimeConfig = null;
    private static volatile ConfigPaths configPaths = null;

    private static final Map<UUID, PendingScan> pendingScans = new ConcurrentHashMap<>();

    public Modwhitelist(IEventBus modBus) {
        NeoForge.EVENT_BUS.register(this);
        NeoForge.EVENT_BUS.register(CommandHandler.class);
        modBus.addListener(Net::register);

        LOGGER.info("[Modwhitelist] Initialized (multi-file config mode)");
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        loadConfig();
        RuntimeConfig cfg = runtimeConfig;
        if (cfg != null && !cfg.settings.strict) {
            LOGGER.warn("[Modwhitelist] STRICT MODE IS DISABLED (strict=false).");
        }
        if (cfg != null && cfg.settings.collectMode) {
            LOGGER.warn("[Modwhitelist] COLLECT MODE IS ENABLED (collectMode=true). Only whitelisted UUIDs may join.");
        }
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;

        if (runtimeConfig == null) loadConfig();
        RuntimeConfig cfg = runtimeConfig;
        if (cfg == null) return;

        if (cfg.settings.collectMode && !cfg.settings.collectWhitelistUuidSet().contains(sp.getUUID())) {
            sp.getServer().execute(() -> sp.connection.disconnect(Component.literal(
                    "Server is currently in modpack collection/setup mode.\nPlease try again later."
            )));
            return;
        }

        long nonce = RNG.nextLong();
        if (nonce == 0L) nonce = 1L;

        pendingScans.put(sp.getUUID(), new PendingScan(nonce, System.currentTimeMillis()));
        Net.sendScanRequest(sp, nonce);
        LOGGER.info("[Modwhitelist] Sent scan request to {} (nonce={})", sp.getGameProfile().getName(), nonce);
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        pendingScans.remove(event.getEntity().getUUID());
    }

    public static void handleScanChunk(ServerPlayer sp, ModScanChunkPayload payload) {
        RuntimeConfig cfg = runtimeConfig;
        if (cfg == null) {
            kickSimple(sp,
                    "Server config not loaded yet",
                    "Please rejoin.");
            return;
        }

        PendingScan pending = pendingScans.get(sp.getUUID());
        if (pending == null) {
            kickSimple(sp,
                    "Mod scan protocol error",
                    "No pending request.");
            return;
        }

        long ageMs = System.currentTimeMillis() - pending.createdAtMs;
        if (ageMs > 60_000L) {
            pendingScans.remove(sp.getUUID());
            kickSimple(sp,
                    "Mod scan protocol error",
                    "Scan timeout.");
            return;
        }

        if (payload.nonce() != pending.nonce) {
            pendingScans.remove(sp.getUUID());
            kickSimple(sp,
                    "Mod scan protocol error",
                    "Nonce mismatch.");
            return;
        }

        pending.modIds.addAll(payload.modIds());
        for (ModScanResponsePayload.FileHash file : payload.files()) {
            if (file != null) pending.files.add(file);
        }

        if (!payload.done()) return;

        pendingScans.remove(sp.getUUID());
        handleScanResponse(sp, new ModScanResponsePayload(
                pending.nonce,
                List.copyOf(pending.modIds),
                List.copyOf(pending.files)
        ));
    }

    public static void handleScanResponse(ServerPlayer sp, ModScanResponsePayload payload) {
        RuntimeConfig cfg = runtimeConfig;
        if (cfg == null) {
            kickSimple(sp,
                    "Server config not loaded yet",
                    "Please rejoin.");
            return;
        }

        LinkedHashSet<String> clientIds = payload.modIds().stream()
                .filter(Objects::nonNull)
                .map(Modwhitelist::normalizeId)
                .filter(s -> !s.isBlank())
                .filter(id -> !isBuiltinId(id))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<String, String> clientFiles = toIncomingFileMap(payload.files());

        final RuntimeConfig cfgSnapshot1 = cfg;
        final List<String> denyModsLower = cfgSnapshot1.denyModsLower;
        final List<FileRule> denyFiles = cfgSnapshot1.deny.files;

        List<String> deniedMods = clientIds.stream()
                .filter(id -> idMatchesAnyGlob(id, denyModsLower))
                .toList();


        if (!deniedMods.isEmpty()) {
            kick(sp,
                    "Disallowed mods detected",
                    deniedMods,
                    "Please remove these mods and restart.");
            return;
        }

        List<String> deniedFiles = clientFiles.entrySet().stream()
                .filter(entry -> matchesAnyDenyFile(entry.getKey(), entry.getValue(), denyFiles))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        if (!deniedFiles.isEmpty()) {
            kick(sp,
                    "Disallowed files detected",
                    deniedFiles,
                    "Please remove these files and restart.");
            return;
        }

        boolean isCollector = cfg.settings.collectMode && cfg.settings.collectWhitelistUuidSet().contains(sp.getUUID());
        if (isCollector) {
            applyCollectedClassification(payload, cfg);
            cfg = runtimeConfig;
            clientFiles = toIncomingFileMap(payload.files());
        }

        final RuntimeConfig cfgSnapshot2 = cfg;
        final Set<String> requiredModIds = cfgSnapshot2.requiredModIds;
        final Set<String> allowedInStrictIds = cfgSnapshot2.allowedInStrictIds;
        final Map<String, String> requiredFileMap = cfgSnapshot2.requiredFileMap;
        final Map<String, String> clientOptionalFileMap = cfgSnapshot2.clientOptionalFileMap;
        final Set<String> allowedInStrictFileNames = cfgSnapshot2.allowedInStrictFileNames;
        final boolean strict = cfgSnapshot2.settings.strict;
        final boolean strictFiles = cfgSnapshot2.settings.strictFiles;

        List<String> missingRequiredMods = requiredModIds.stream()
                .filter(req -> !clientIds.contains(req))
                .toList();
        if (!missingRequiredMods.isEmpty()) {
            kick(sp,
                    "Missing required mods",
                    missingRequiredMods,
                    "Please install the official modpack.");
            return;
        }

        if (strict) {
            List<String> extras = clientIds.stream()
                    .filter(id -> !allowedInStrictIds.contains(id))
                    .toList();
            if (!extras.isEmpty()) {
                kick(sp,
                        "Additional not allowed mods",
                        extras,
                        "Use only the provided modpack.");
                return;
            }
        }

        if (strictFiles) {
            List<String> requiredIssues = validateRequiredFiles(requiredFileMap, clientFiles);
            if (!requiredIssues.isEmpty()) {
                kick(sp,
                        "Modpack integrity check failed - required files",
                        requiredIssues,
                        "Please reinstall the official modpack.");
                return;
            }

            List<String> optionalIssues = validateOptionalFiles(clientOptionalFileMap, clientFiles);
            if (!optionalIssues.isEmpty()) {
                kick(sp,
                        "Modpack integrity check failed - optional files",
                        optionalIssues,
                        "Please reinstall the official modpack.");
                return;
            }

            if (strict) {
                List<String> extraFiles = clientFiles.keySet().stream()
                        .filter(name -> !allowedInStrictFileNames.contains(name))
                        .sorted()
                        .toList();
                if (!extraFiles.isEmpty()) {
                    kick(sp,
                            "Modpack integrity check failed - extra files",
                            extraFiles,
                            "Please reinstall the official modpack.");
                    return;
                }
            }
        }

        LOGGER.info("[Modwhitelist] {} passed mod check ({} mods, {} files)",
                sp.getGameProfile().getName(), clientIds.size(), payload.files().size());
    }
    public static void reloadConfig() {
        loadConfig();
    }

    public static void initializeEmptyConfigs() {
        synchronized (CONFIG_LOCK) {
            try {
                ConfigPaths paths = ensureConfigPaths();
                Files.createDirectories(paths.dir());
                writeJson(paths.settings(), SettingsConfig.defaultConfig());
                writeJson(paths.bothRequired(), ManifestConfig.empty());
                writeJson(paths.clientRequired(), ManifestConfig.empty());
                writeJson(paths.clientOptional(), ManifestConfig.empty());
                writeJson(paths.serverOnly(), ManifestConfig.empty());
                writeJson(paths.deny(), DenyConfig.empty());
                loadConfig();
            } catch (IOException e) {
                throw new RuntimeException("Failed to initialize config files", e);
            }
        }
    }

    public static void setCollectMode(boolean enabled) {
        RuntimeConfig cfg = runtimeConfig;
        if (cfg == null) loadConfig();
        cfg = runtimeConfig;
        if (cfg == null) return;

        cfg.settings.collectMode = enabled;
        if (enabled) {
            cfg.settings.strict = false;
        }
        saveAll(cfg);
    }

    public static void setStrict(boolean enabled) {
        RuntimeConfig cfg = runtimeConfig;
        if (cfg == null) loadConfig();
        cfg = runtimeConfig;
        if (cfg == null) return;

        cfg.settings.strict = enabled;
        saveAll(cfg);
    }

    public static void clearAutoCollectedManifests() {
        RuntimeConfig cfg = runtimeConfig;
        if (cfg == null) loadConfig();
        cfg = runtimeConfig;
        if (cfg == null) return;

        cfg.bothRequired = ManifestConfig.empty();
        cfg.clientOptional = ManifestConfig.empty();
        cfg.serverOnly = ManifestConfig.empty();
        saveAll(cfg);
    }

    public static void writeCurrentConfig(RuntimeConfig cfg) throws IOException {
        synchronized (CONFIG_LOCK) {
            ConfigPaths paths = ensureConfigPaths();
            writeJson(paths.settings(), cfg.settings);
            writeJson(paths.bothRequired(), cfg.bothRequired);
            writeJson(paths.clientRequired(), cfg.clientRequired);
            writeJson(paths.clientOptional(), cfg.clientOptional);
            writeJson(paths.serverOnly(), cfg.serverOnly);
            writeJson(paths.deny(), cfg.deny);
            runtimeConfig = buildRuntimeConfig(cfg.settings, cfg.bothRequired, cfg.clientRequired, cfg.clientOptional, cfg.serverOnly, cfg.deny);
        }
    }

    private static void loadConfig() {
        synchronized (CONFIG_LOCK) {
            try {
                ConfigPaths paths = ensureConfigPaths();
                Files.createDirectories(paths.dir());

                migrateLegacyConfigIfPresent(paths);
                ensureMissingFiles(paths);

                SettingsConfig settings = readJson(paths.settings(), SettingsConfig.class, SettingsConfig.defaultConfig());
                ManifestConfig bothRequired = readJson(paths.bothRequired(), ManifestConfig.class, ManifestConfig.empty());
                ManifestConfig clientRequired = readJson(paths.clientRequired(), ManifestConfig.class, ManifestConfig.empty());
                ManifestConfig clientOptional = readJson(paths.clientOptional(), ManifestConfig.class, ManifestConfig.empty());
                ManifestConfig serverOnly = readJson(paths.serverOnly(), ManifestConfig.class, ManifestConfig.empty());
                DenyConfig deny = readJson(paths.deny(), DenyConfig.class, DenyConfig.empty());

                runtimeConfig = buildRuntimeConfig(settings, bothRequired, clientRequired, clientOptional, serverOnly, deny);

                LOGGER.info("[Modwhitelist] Loaded configs: strict={}, strictFiles={}, collectMode={}, bothRequiredMods={}, clientRequiredMods={}, clientOptionalMods={}, serverOnlyMods={}, denyMods={}",
                        runtimeConfig.settings.strict,
                        runtimeConfig.settings.strictFiles,
                        runtimeConfig.settings.collectMode,
                        runtimeConfig.bothRequired.mods.size(),
                        runtimeConfig.clientRequired.mods.size(),
                        runtimeConfig.clientOptional.mods.size(),
                        runtimeConfig.serverOnly.mods.size(),
                        runtimeConfig.deny.mods.size());
            } catch (IOException e) {
                LOGGER.error("[Modwhitelist] Failed to load config files", e);
                runtimeConfig = buildRuntimeConfig(
                        SettingsConfig.defaultConfig(),
                        ManifestConfig.empty(),
                        ManifestConfig.empty(),
                        ManifestConfig.empty(),
                        ManifestConfig.empty(),
                        DenyConfig.empty()
                );
            }
        }
    }

    private static void saveAll(RuntimeConfig cfg) {
        synchronized (CONFIG_LOCK) {
            try {
                ConfigPaths paths = ensureConfigPaths();
                Files.createDirectories(paths.dir());
                writeJson(paths.settings(), cfg.settings);
                writeJson(paths.bothRequired(), cfg.bothRequired);
                writeJson(paths.clientRequired(), cfg.clientRequired);
                writeJson(paths.clientOptional(), cfg.clientOptional);
                writeJson(paths.serverOnly(), cfg.serverOnly);
                writeJson(paths.deny(), cfg.deny);
                runtimeConfig = buildRuntimeConfig(cfg.settings, cfg.bothRequired, cfg.clientRequired, cfg.clientOptional, cfg.serverOnly, cfg.deny);
            } catch (IOException e) {
                LOGGER.error("[Modwhitelist] Failed to save config files", e);
            }
        }
    }

    private static RuntimeConfig buildRuntimeConfig(SettingsConfig settings,
                                                    ManifestConfig bothRequired,
                                                    ManifestConfig clientRequired,
                                                    ManifestConfig clientOptional,
                                                    ManifestConfig serverOnly,
                                                    DenyConfig deny) {
        RuntimeConfig cfg = new RuntimeConfig();
        cfg.settings = sanitizeSettings(settings);
        cfg.bothRequired = sanitizeManifest(bothRequired);
        cfg.clientRequired = sanitizeManifest(clientRequired);
        cfg.clientOptional = sanitizeManifest(clientOptional);
        cfg.serverOnly = sanitizeManifest(serverOnly);
        cfg.deny = sanitizeDeny(deny);

        cfg.requiredModIds = new LinkedHashSet<>();
        cfg.requiredModIds.addAll(lowerManifestModIds(cfg.bothRequired));
        cfg.requiredModIds.addAll(lowerManifestModIds(cfg.clientRequired));

        cfg.allowedInStrictIds = new LinkedHashSet<>();
        cfg.allowedInStrictIds.addAll(cfg.requiredModIds);
        cfg.allowedInStrictIds.addAll(lowerManifestModIds(cfg.clientOptional));

        cfg.denyModsLower = cfg.deny.mods.stream()
                .filter(Objects::nonNull)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .toList();

        cfg.requiredFileMap = new LinkedHashMap<>();
        cfg.requiredFileMap.putAll(toFileMap(cfg.bothRequired.files));
        cfg.requiredFileMap.putAll(toFileMap(cfg.clientRequired.files));

        cfg.clientOptionalFileMap = toFileMap(cfg.clientOptional.files);
        cfg.allowedInStrictFileNames = new HashSet<>();
        cfg.allowedInStrictFileNames.addAll(cfg.requiredFileMap.keySet());
        cfg.allowedInStrictFileNames.addAll(cfg.clientOptionalFileMap.keySet());

        return cfg;
    }

    private static void applyCollectedClassification(ModScanResponsePayload payload, RuntimeConfig cfg) {
        Set<String> serverIds = getLoadedServerModIds();
        Map<String, String> serverFiles = getServerModsFolderFiles();

        Set<String> clientIds = payload.modIds().stream()
                .filter(Objects::nonNull)
                .map(Modwhitelist::normalizeId)
                .filter(s -> !s.isBlank())
                .filter(id -> !isBuiltinId(id))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<String, String> clientFiles = toIncomingFileMap(payload.files());

        ManifestConfig bothRequired = new ManifestConfig();
        ManifestConfig clientOptional = new ManifestConfig();
        ManifestConfig serverOnly = new ManifestConfig();

        bothRequired.mods = serverIds.stream()
                .filter(clientIds::contains)
                .sorted()
                .map(Modwhitelist::manifestItem)
                .toList();
        clientOptional.mods = clientIds.stream()
                .filter(id -> !serverIds.contains(id))
                .sorted()
                .map(Modwhitelist::manifestItem)
                .toList();
        serverOnly.mods = serverIds.stream()
                .filter(id -> !clientIds.contains(id))
                .sorted()
                .map(Modwhitelist::manifestItem)
                .toList();

        bothRequired.files = intersectFileMaps(serverFiles, clientFiles);
        clientOptional.files = subtractFiles(clientFiles, serverFiles);
        serverOnly.files = subtractFiles(serverFiles, clientFiles);

        cfg.bothRequired = bothRequired;
        cfg.clientOptional = clientOptional;
        cfg.serverOnly = serverOnly;
        cfg.settings.collectMode = false;
        cfg.settings.strict = true;

        saveAll(cfg);
        LOGGER.warn("[Modwhitelist] Collect completed. both_side_required={}, client_optional={}, server_only={}",
                bothRequired.mods.size(), clientOptional.mods.size(), serverOnly.mods.size());
    }

    private static ConfigPaths ensureConfigPaths() throws IOException {
        ConfigPaths paths = configPaths;
        if (paths != null) return paths;

        Path baseConfigDir = FMLPaths.CONFIGDIR.get();
        Path dir = baseConfigDir.resolve(CONFIG_DIR_NAME);
        Path legacy = baseConfigDir.resolve(LEGACY_CONFIG_NAME);
        paths = new ConfigPaths(
                dir,
                dir.resolve(SETTINGS_FILE),
                dir.resolve(BOTH_REQUIRED_FILE),
                dir.resolve(CLIENT_REQUIRED_FILE),
                dir.resolve(CLIENT_OPTIONAL_FILE),
                dir.resolve(SERVER_ONLY_FILE),
                dir.resolve(DENY_FILE),
                legacy
        );
        configPaths = paths;
        return paths;
    }

    private static void migrateLegacyConfigIfPresent(ConfigPaths paths) throws IOException {
        boolean newFilesExist = Files.exists(paths.settings()) || Files.exists(paths.bothRequired()) || Files.exists(paths.clientRequired())
                || Files.exists(paths.clientOptional()) || Files.exists(paths.serverOnly()) || Files.exists(paths.deny());
        if (newFilesExist || !Files.exists(paths.legacyFile())) return;

        try (JsonReader reader = new JsonReader(new InputStreamReader(Files.newInputStream(paths.legacyFile())))) {
            LegacyConfig legacy = GSON.fromJson(reader, LegacyConfig.class);
            if (legacy == null) legacy = LegacyConfig.defaultConfig();

            SettingsConfig settings = new SettingsConfig();
            settings.strict = legacy.strict;
            settings.strictFiles = legacy.strictFiles;
            settings.collectMode = legacy.collectClientOnly;
            settings.collectWhitelist = legacy.collectWhitelist == null ? new ArrayList<>() : new ArrayList<>(legacy.collectWhitelist);
            settings.customMessage = defaultString(legacy.customMessage);
            settings.packLink = defaultString(legacy.packLink);

            ManifestConfig bothRequired = new ManifestConfig();
            bothRequired.mods = sanitizeLegacyItems(legacy.allowed);
            bothRequired.files = sanitizeLegacyFiles(legacy.allowedFiles);

            ManifestConfig clientOptional = new ManifestConfig();
            clientOptional.mods = sanitizeLegacyOptionalMods(legacy.allowClientOnly);
            clientOptional.files = sanitizeLegacyFiles(legacy.clientOnlyFiles);

            DenyConfig deny = new DenyConfig();
            deny.mods = legacy.deny == null ? new ArrayList<>() : legacy.deny.stream()
                                                                  .filter(Objects::nonNull)
                                                                  .map(String::trim)
                                                                  .filter(s -> !s.isBlank())
                                                                  .toList();
            deny.files = new ArrayList<>();

            writeJson(paths.settings(), sanitizeSettings(settings));
            writeJson(paths.bothRequired(), sanitizeManifest(bothRequired));
            writeJson(paths.clientRequired(), ManifestConfig.empty());
            writeJson(paths.clientOptional(), sanitizeManifest(clientOptional));
            writeJson(paths.serverOnly(), ManifestConfig.empty());
            writeJson(paths.deny(), sanitizeDeny(deny));

            LOGGER.warn("[Modwhitelist] Migrated legacy {} to multi-file config directory {}",
                    paths.legacyFile().getFileName(), paths.dir().toAbsolutePath());
        }
    }

    private static void ensureMissingFiles(ConfigPaths paths) throws IOException {
        if (!Files.exists(paths.settings())) writeJson(paths.settings(), SettingsConfig.defaultConfig());
        if (!Files.exists(paths.bothRequired())) writeJson(paths.bothRequired(), ManifestConfig.empty());
        if (!Files.exists(paths.clientRequired())) writeJson(paths.clientRequired(), ManifestConfig.empty());
        if (!Files.exists(paths.clientOptional())) writeJson(paths.clientOptional(), ManifestConfig.empty());
        if (!Files.exists(paths.serverOnly())) writeJson(paths.serverOnly(), ManifestConfig.empty());
        if (!Files.exists(paths.deny())) writeJson(paths.deny(), DenyConfig.empty());
    }

    private static <T> T readJson(Path path, Class<T> clazz, T fallback) throws IOException {
        try (JsonReader reader = new JsonReader(new InputStreamReader(Files.newInputStream(path)))) {
            T loaded = GSON.fromJson(reader, clazz);
            return loaded != null ? loaded : fallback;
        }
    }

    private static void writeJson(Path path, Object value) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, GSON.toJson(value), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static SettingsConfig sanitizeSettings(SettingsConfig settings) {
        SettingsConfig out = settings == null ? SettingsConfig.defaultConfig() : settings;
        if (out.collectWhitelist == null) out.collectWhitelist = new ArrayList<>();
        if (out.customMessage == null) out.customMessage = "";
        if (out.packLink == null) out.packLink = "";
        return out;
    }

    private static ManifestConfig sanitizeManifest(ManifestConfig manifest) {
        ManifestConfig out = manifest == null ? ManifestConfig.empty() : manifest;
        out.mods = dedupeItems(out.mods);
        out.files = dedupeFiles(out.files);
        return out;
    }

    private static DenyConfig sanitizeDeny(DenyConfig deny) {
        DenyConfig out = deny == null ? DenyConfig.empty() : deny;
        if (out.mods == null) out.mods = new ArrayList<>();
        out.mods = out.mods.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        out.files = dedupeFiles(out.files);
        return out;
    }

    private static List<ManifestItem> sanitizeLegacyItems(List<LegacyConfig.Item> items) {
        if (items == null) return new ArrayList<>();
        List<ManifestItem> mapped = new ArrayList<>();
        for (LegacyConfig.Item item : items) {
            if (item == null || item.modid == null || item.modid.isBlank()) continue;
            ManifestItem target = new ManifestItem();
            target.modid = normalizeId(item.modid);
            target.version = item.version == null || item.version.isBlank() ? "*" : item.version;
            if (!isBuiltinId(target.modid)) mapped.add(target);
        }
        return dedupeItems(mapped);
    }

    private static List<ManifestItem> sanitizeLegacyOptionalMods(List<String> mods) {
        if (mods == null) return new ArrayList<>();
        List<ManifestItem> mapped = new ArrayList<>();
        for (String mod : mods) {
            if (mod == null || mod.isBlank()) continue;
            ManifestItem target = new ManifestItem();
            target.modid = normalizeId(mod);
            target.version = "*";
            if (!isBuiltinId(target.modid)) mapped.add(target);
        }
        return dedupeItems(mapped);
    }

    private static List<FileRule> sanitizeLegacyFiles(List<LegacyConfig.FileRule> files) {
        if (files == null) return new ArrayList<>();
        List<FileRule> mapped = new ArrayList<>();
        for (LegacyConfig.FileRule file : files) {
            if (file == null || file.name == null || file.name.isBlank() || file.sha256 == null || file.sha256.isBlank()) continue;
            FileRule target = new FileRule();
            target.name = file.name.trim();
            target.sha256 = file.sha256.toLowerCase(Locale.ROOT);
            mapped.add(target);
        }
        return dedupeFiles(mapped);
    }

    private static List<ManifestItem> dedupeItems(List<ManifestItem> items) {
        Map<String, ManifestItem> map = new LinkedHashMap<>();
        if (items != null) {
            for (ManifestItem item : items) {
                if (item == null || item.modid == null) continue;
                String modid = normalizeId(item.modid);
                if (modid.isBlank() || isBuiltinId(modid)) continue;
                ManifestItem clean = new ManifestItem();
                clean.modid = modid;
                clean.version = item.version == null || item.version.isBlank() ? "*" : item.version;
                map.put(modid, clean);
            }
        }
        return map.values().stream()
                .sorted(Comparator.comparing(a -> a.modid))
                .toList();
    }

    private static List<FileRule> dedupeFiles(List<FileRule> files) {
        Map<String, FileRule> map = new LinkedHashMap<>();
        if (files != null) {
            for (FileRule file : files) {
                if (file == null || file.name == null || file.name.isBlank()) continue;
                FileRule clean = new FileRule();
                clean.name = file.name.trim();
                clean.sha256 = file.sha256 == null ? "" : file.sha256.toLowerCase(Locale.ROOT).trim();
                map.put(clean.name, clean);
            }
        }
        return map.values().stream()
                .sorted(Comparator.comparing(a -> a.name.toLowerCase(Locale.ROOT)))
                .toList();
    }

    private static ManifestItem manifestItem(String modid) {
        ManifestItem item = new ManifestItem();
        item.modid = normalizeId(modid);
        item.version = "*";
        return item;
    }

    private static Set<String> lowerManifestModIds(ManifestConfig manifest) {
        return manifest.mods.stream()
                .map(item -> normalizeId(item.modid))
                .filter(s -> !s.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Map<String, String> toFileMap(List<FileRule> files) {
        Map<String, String> out = new LinkedHashMap<>();
        if (files == null) return out;
        for (FileRule file : files) {
            if (file == null || file.name == null || file.name.isBlank()) continue;
            if (file.sha256 == null || file.sha256.isBlank()) continue;
            out.put(file.name.trim(), file.sha256.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private static Map<String, String> toIncomingFileMap(Collection<ModScanResponsePayload.FileHash> incoming) {
        Map<String, String> out = new LinkedHashMap<>();
        if (incoming == null) return out;
        for (ModScanResponsePayload.FileHash file : incoming) {
            if (file == null || file.name() == null || file.name().isBlank() || file.sha256() == null || file.sha256().isBlank()) {
                continue;
            }
            out.put(file.name().trim(), file.sha256().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private static List<FileRule> intersectFileMaps(Map<String, String> left, Map<String, String> right) {
        List<FileRule> out = new ArrayList<>();
        for (Map.Entry<String, String> entry : left.entrySet()) {
            String otherHash = right.get(entry.getKey());
            if (otherHash == null) continue;
            FileRule rule = new FileRule();
            rule.name = entry.getKey();
            rule.sha256 = otherHash;
            out.add(rule);
        }
        return dedupeFiles(out);
    }

    private static List<FileRule> subtractFiles(Map<String, String> left, Map<String, String> right) {
        List<FileRule> out = new ArrayList<>();
        for (Map.Entry<String, String> entry : left.entrySet()) {
            if (right.containsKey(entry.getKey())) continue;
            FileRule rule = new FileRule();
            rule.name = entry.getKey();
            rule.sha256 = entry.getValue();
            out.add(rule);
        }
        return dedupeFiles(out);
    }

    private static List<String> validateRequiredFiles(Map<String, String> expected, Map<String, String> got) {
        List<String> issues = new ArrayList<>();
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            String name = entry.getKey();
            String expectedHash = entry.getValue();
            String gotHash = got.get(name);
            if (gotHash == null) {
                issues.add(name + " (missing)");
            } else if (!gotHash.equals(expectedHash)) {
                issues.add(name + " (hash mismatch)");
            }
        }
        return issues;
    }

    private static List<String> validateOptionalFiles(Map<String, String> expectedOptional, Map<String, String> got) {
        List<String> issues = new ArrayList<>();
        for (Map.Entry<String, String> entry : expectedOptional.entrySet()) {
            String gotHash = got.get(entry.getKey());
            if (gotHash != null && !gotHash.equals(entry.getValue())) {
                issues.add(entry.getKey() + " (hash mismatch)");
            }
        }
        return issues;
    }

    private static boolean matchesAnyDenyFile(String fileName, String fileHash, List<FileRule> denyRules) {
        if (denyRules == null || denyRules.isEmpty()) return false;
        for (FileRule rule : denyRules) {
            if (rule == null || rule.name == null || rule.name.isBlank()) continue;
            if (!fileName.matches(globToRegex(rule.name.toLowerCase(Locale.ROOT)))) continue;

            String expectedHash = rule.sha256 == null ? "" : rule.sha256.trim().toLowerCase(Locale.ROOT);
            if (expectedHash.isBlank() || expectedHash.equals("*")) return true;
            if (expectedHash.equals(fileHash)) return true;
        }
        return false;
    }

    private static Set<String> getLoadedServerModIds() {
        return ModList.get().getMods().stream()
                .map(mod -> normalizeId(mod.getModId()))
                .filter(s -> !s.isBlank())
                .filter(id -> !isBuiltinId(id))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Map<String, String> getServerModsFolderFiles() {
        try {
            Path modsDir = Paths.get("").toAbsolutePath().resolve("mods");
            if (!Files.isDirectory(modsDir)) return Map.of();

            Map<String, String> out = new LinkedHashMap<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(modsDir)) {
                for (Path path : ds) {
                    if (!Files.isRegularFile(path)) continue;
                    String name = path.getFileName().toString();
                    if (!(name.endsWith(".jar") || name.endsWith(".zip"))) continue;
                    out.put(name, sha256Hex(path));
                }
            }
            return out.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            Map.Entry::getValue,
                            (a, b) -> b,
                            LinkedHashMap::new
                    ));
        } catch (Exception e) {
            LOGGER.error("[Modwhitelist] Failed to scan server mods folder", e);
            return Map.of();
        }
    }

    private static String sha256Hex(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file, StandardOpenOption.READ)) {
            byte[] buf = new byte[1024 * 1024];
            int read;
            while ((read = in.read(buf)) != -1) {
                md.update(buf, 0, read);
            }
        }
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static void kick(ServerPlayer sp, String title, Collection<String> entries, String footer) {
        RuntimeConfig cfg = runtimeConfig;

        MutableComponent msg = Component.empty();

        if (cfg != null && cfg.settings.customMessage != null && !cfg.settings.customMessage.isBlank()) {
            msg.append(Component.literal(cfg.settings.customMessage + "\n\n")
                    .withStyle(ChatFormatting.AQUA));
        }

        msg.append(Component.literal(title + "\n")
                .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));

        if (entries != null && !entries.isEmpty()) {
            boolean first = true;
            for (String entry : entries) {
                if (!first) msg.append(Component.literal("\n"));
                msg.append(Component.literal("• " + entry).withStyle(ChatFormatting.YELLOW));
                first = false;
            }
            msg.append(Component.literal("\n\n"));
        }

        if (footer != null && !footer.isBlank()) {
            msg.append(Component.literal(footer).withStyle(ChatFormatting.GRAY));
        }

        if (cfg != null && cfg.settings.packLink != null && !cfg.settings.packLink.isBlank()) {
            msg.append(Component.literal("\n\nOfficial Modpack: ").withStyle(ChatFormatting.GRAY));
            msg.append(Component.literal(cfg.settings.packLink)
                    .setStyle(Style.EMPTY.withColor(ChatFormatting.BLUE).withUnderlined(true)));
        }

        sp.connection.disconnect(msg);
        LOGGER.warn("[Modwhitelist] Kicked {}: {} | entries={}",
                sp.getGameProfile().getName(), title, entries == null ? 0 : entries.size());
    }

    private static void kickSimple(ServerPlayer sp, String title, String footer) {
        kick(sp, title, List.of(), footer);
    }

    private static String normalizeId(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String defaultString(String value) {
        return value == null ? "" : value;
    }

    private static boolean isBuiltinId(String id) {
        return "minecraft".equals(id) || "neoforge".equals(id) || id.startsWith("fml");
    }

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
        for (String pattern : patternsLower) {
            if (idLower.matches(globToRegex(pattern))) return true;
        }
        return false;
    }

    private static final class PendingScan {
        private final long nonce;
        private final long createdAtMs;
        private final List<String> modIds = new ArrayList<>();
        private final List<ModScanResponsePayload.FileHash> files = new ArrayList<>();

        private PendingScan(long nonce, long createdAtMs) {
            this.nonce = nonce;
            this.createdAtMs = createdAtMs;
        }
    }

    private record ConfigPaths(
            Path dir,
            Path settings,
            Path bothRequired,
            Path clientRequired,
            Path clientOptional,
            Path serverOnly,
            Path deny,
            Path legacyFile
    ) {}

    public static final class RuntimeConfig {
        public SettingsConfig settings;
        public ManifestConfig bothRequired;
        public ManifestConfig clientRequired;
        public ManifestConfig clientOptional;
        public ManifestConfig serverOnly;
        public DenyConfig deny;

        private Set<String> requiredModIds = Set.of();
        private Set<String> allowedInStrictIds = Set.of();
        private List<String> denyModsLower = List.of();
        private Map<String, String> requiredFileMap = Map.of();
        private Map<String, String> clientOptionalFileMap = Map.of();
        private Set<String> allowedInStrictFileNames = Set.of();
    }

    public static class SettingsConfig {
        public List<String> _comment = new ArrayList<>();
        public boolean strict = true;
        public boolean strictFiles = true;
        public boolean collectMode = false;
        public List<String> collectWhitelist = new ArrayList<>();
        public String customMessage = "Please use the official modpack.";
        public String packLink = "";

        public static SettingsConfig defaultConfig() {
            SettingsConfig cfg = new SettingsConfig();
            cfg._comment = List.of(
                    "Multi-file config layout:",
                    "- both_side_required.json - mods/files required on both server and client",
                    "- client_required.json   - client-only mods/files that are still required",
                    "- client_optional.json   - client-only mods/files that are allowed but optional",
                    "- server_only.json       - server-only mods/files for overview/setup",
                    "- deny.json              - hard block list",
                    "",
                    "Collect workflow:",
                    "- set collectMode:true and add your UUID to collectWhitelist",
                    "- join once with the reference client pack",
                    "- the mod overwrites both_side_required.json, client_optional.json and server_only.json",
                    "- move any client-only mandatory mods manually from client_optional.json to client_required.json"
            );
            return cfg;
        }

        public Set<UUID> collectWhitelistUuidSet() {
            if (collectWhitelist == null || collectWhitelist.isEmpty()) return Set.of();
            Set<UUID> out = new HashSet<>();
            for (String entry : collectWhitelist) {
                if (entry == null || entry.isBlank()) continue;
                try {
                    out.add(UUID.fromString(entry.trim()));
                } catch (IllegalArgumentException ignored) {
                }
            }
            return out;
        }
    }

    public static class ManifestConfig {
        public List<ManifestItem> mods = new ArrayList<>();
        public List<FileRule> files = new ArrayList<>();

        public static ManifestConfig empty() {
            return new ManifestConfig();
        }
    }

    public static class DenyConfig {
        public List<String> mods = new ArrayList<>();
        public List<FileRule> files = new ArrayList<>();

        public static DenyConfig empty() {
            return new DenyConfig();
        }
    }

    public static class ManifestItem {
        public String modid;
        public String version = "*";
    }

    public static class FileRule {
        public String name;
        public String sha256;
    }

    private static class LegacyConfig {
        public boolean strict = true;
        public List<Item> allowed = new ArrayList<>();
        public List<String> allowClientOnly = new ArrayList<>();
        public List<String> deny = new ArrayList<>();
        public String packLink = "";
        public String customMessage = "";
        public boolean strictFiles = true;
        public List<FileRule> allowedFiles = new ArrayList<>();
        public List<FileRule> clientOnlyFiles = new ArrayList<>();
        public boolean collectClientOnly = false;
        public List<String> collectWhitelist = new ArrayList<>();

        public static LegacyConfig defaultConfig() {
            return new LegacyConfig();
        }

        public static class Item {
            public String modid;
            public String version = "*";
        }

        public static class FileRule {
            public String name;
            public String sha256;
        }
    }

}