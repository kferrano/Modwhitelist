package com.hardrock.modwhitelist;

import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;

import net.neoforged.fml.ModList;

import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public final class UpdateChecker {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String VERSION_URL = "https://raw.githubusercontent.com/kferrano/Curseforge_Updates/main/modwhitelist1.21.txt";
    private static final String DOWNLOAD_URL = "https://www.curseforge.com/minecraft/mc-mods/mod-whitelist-forgeneoforge";
    private static final long CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L;
    private static final long FAILURE_RETRY_MS = 15L * 60L * 1000L;
    private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)(?:[-+].*)?$");
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final Object LOCK = new Object();
    private static final Map<UUID, String> LAST_NOTIFIED_VERSION = new ConcurrentHashMap<>();
    private static volatile String latestVersion;
    private static volatile long lastSuccessfulCheckMs;
    private static volatile long lastAttemptMs;
    private static volatile boolean checkRunning;
    private static volatile String lastLoggedVersion;

    private UpdateChecker() {}

    public static void checkAndNotify(ServerPlayer player, boolean allowNonOp) {
        if (!allowNonOp && !player.hasPermissions(2) ) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        String cachedLatest = latestVersion;
        if (cachedLatest != null) notifyIfNewer(player, cachedLatest);
        long now = System.currentTimeMillis();
        if (lastSuccessfulCheckMs > 0L && now - lastSuccessfulCheckMs < CHECK_INTERVAL_MS) return;
        synchronized (LOCK) {
            now = System.currentTimeMillis();
            if (checkRunning) return;
            if (lastSuccessfulCheckMs > 0L && now - lastSuccessfulCheckMs < CHECK_INTERVAL_MS) return;
            if (lastAttemptMs > 0L && now - lastAttemptMs < FAILURE_RETRY_MS) return;
            checkRunning = true;
            lastAttemptMs = now;
        }
        UUID playerId = player.getUUID();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(VERSION_URL))
                .timeout(Duration.ofSeconds(5))
                .header("User-Agent", "ModWhitelist-UpdateChecker")
                .GET()
                .build();
        HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete((response, error) -> {
            try {
                if (error != null) {
                    LOGGER.debug("[Modwhitelist] Update check failed", error);
                    return;
                }
                if (response.statusCode() != 200) {
                    LOGGER.debug("[Modwhitelist] Update check returned HTTP {}", response.statusCode());
                    return;
                }
                String remoteVersion = normalizeVersion(response.body());
                if (remoteVersion == null) {
                    LOGGER.warn("[Modwhitelist] Update check returned an invalid version: {}", response.body().trim());
                    return;
                }
                latestVersion = remoteVersion;
                lastSuccessfulCheckMs = System.currentTimeMillis();
                String currentVersion = getInstalledVersion();
                if (currentVersion != null && compareVersions(remoteVersion, currentVersion) > 0 && !remoteVersion.equals(lastLoggedVersion)) {
                    lastLoggedVersion = remoteVersion;
                    LOGGER.warn("[Modwhitelist] Update available: installed={}, latest={} | {}", currentVersion, remoteVersion, DOWNLOAD_URL);
                }
                server.execute(() -> {
                    ServerPlayer onlinePlayer = server.getPlayerList().getPlayer(playerId);
                    if (onlinePlayer != null && (allowNonOp || onlinePlayer.hasPermissions(2))) notifyIfNewer(onlinePlayer, remoteVersion);
                });
            } finally {
                synchronized (LOCK) {
                    checkRunning = false;
                }
            }
        });
    }

    private static void notifyIfNewer(ServerPlayer player, String remoteVersion) {
        String currentVersion = getInstalledVersion();
        if (currentVersion == null || compareVersions(remoteVersion, currentVersion) <= 0) return;
        String previous = LAST_NOTIFIED_VERSION.put(player.getUUID(), remoteVersion);
        if (remoteVersion.equals(previous)) return;
        MutableComponent message = Component.literal("[ModWhitelist] ").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)
                .append(Component.literal("A new version is available!\n").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal("Installed: " + currentVersion + "\n").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("Latest: " + remoteVersion + "\n").withStyle(ChatFormatting.GREEN))
                .append(Component.literal("Download: Click here ").withStyle(style -> style
                        .withColor(ChatFormatting.BLUE).withUnderlined(true).withClickEvent(
                                new ClickEvent(ClickEvent.Action.OPEN_URL, DOWNLOAD_URL))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Open CurseForge")
                        ))
                ));
        player.sendSystemMessage(message);
    }

    private static String getInstalledVersion() {
        String rawVersion = ModList.get().getModContainerById(Modwhitelist.MODID)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse(null);
        if (rawVersion == null) return null;

        Matcher matcher = Pattern.compile("^\\d+\\.\\d+(?:\\.\\d+)?-(\\d+\\.\\d+\\.\\d+(?:[-+].*)?)$")
                .matcher(rawVersion.trim());

        if (matcher.matches()) return normalizeVersion(matcher.group(1));
        return normalizeVersion(rawVersion);
    }

    private static String normalizeVersion(String value) {
        if (value == null) return null;
        Matcher matcher = VERSION_PATTERN.matcher(value.trim());
        if (!matcher.find()) return null;
        return matcher.group(1) + "." + matcher.group(2) + "." + matcher.group(3);
    }

    private static int compareVersions(String left, String right) {
        int[] a = parseVersion(left);
        int[] b = parseVersion(right);
        for (int i = 0; i < 3; i++) {
            int result = Integer.compare(a[i], b[i]);
            if (result != 0) return result;
        }
        return 0;
    }

    private static int[] parseVersion(String version) {
        Matcher matcher = VERSION_PATTERN.matcher(version.trim());
        if (!matcher.find()) return new int[]{0, 0, 0};
        return new int[]{
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3))
        };
    }
}