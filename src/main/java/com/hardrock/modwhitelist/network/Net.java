package com.hardrock.modwhitelist.network;

import com.hardrock.modwhitelist.Modwhitelist;
import com.hardrock.modwhitelist.network.payload.ModScanRequestPayload;
import com.hardrock.modwhitelist.network.payload.ModScanResponsePayload;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;

import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;

public final class Net {
    private static final Logger LOGGER = LogUtils.getLogger();

    private Net() {}

    public static final String PROTOCOL = "1";

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar(PROTOCOL);

        // Server -> Client (Request). Handler MUSS existieren (NeoForge 1.21.1)
        registrar.playToClient(
                ModScanRequestPayload.TYPE,
                ModScanRequestPayload.STREAM_CODEC,
                (payload, ctx) -> {
                    // Läuft nur auf dem Client, wenn der Server den Request sendet
                    try {
                        List<String> modIds = ModList.get().getMods().stream()
                                .map(m -> m.getModId())
                                .filter(Objects::nonNull)
                                .map(s -> s.toLowerCase(Locale.ROOT))
                                .distinct()
                                .sorted()
                                .collect(Collectors.toList());

                        List<ModScanResponsePayload.FileHash> files = scanModsFolder();

                        PacketDistributor.sendToServer(
                                new ModScanResponsePayload(payload.nonce(), modIds, files)
                        );
                    } catch (Exception ex) {
                        LOGGER.error("[Modwhitelist] Client scan failed", ex);
                        // Server kann optional später timeouten/kicken, falls keine Antwort kommt
                    }
                }
        );

        // Client -> Server (Response)
        registrar.playToServer(
                ModScanResponsePayload.TYPE,
                ModScanResponsePayload.STREAM_CODEC,
                (payload, ctx) -> {
                    if (ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp) {
                        Modwhitelist.handleScanResponse(sp, payload);
                    }
                }
        );
    }

    public static void sendScanRequest(net.minecraft.server.level.ServerPlayer player, long nonce) {
        PacketDistributor.sendToPlayer(player, new ModScanRequestPayload(nonce));
    }

    private static List<ModScanResponsePayload.FileHash> scanModsFolder() throws Exception {
        Path modsDir = Paths.get("").toAbsolutePath().resolve("mods");
        if (!Files.isDirectory(modsDir)) return List.of();

        List<ModScanResponsePayload.FileHash> out = new ArrayList<>();

        try (DirectoryStream<Path> ds = Files.newDirectoryStream(modsDir)) {
            for (Path p : ds) {
                if (!Files.isRegularFile(p)) continue;

                String name = p.getFileName().toString();
                if (!(name.endsWith(".jar") || name.endsWith(".zip"))) continue;

                out.add(new ModScanResponsePayload.FileHash(name, sha256Hex(p)));
            }
        }

        out.sort(Comparator.comparing(ModScanResponsePayload.FileHash::name));
        return out;
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
}
