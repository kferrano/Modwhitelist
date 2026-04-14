package com.hardrock.modwhitelist.network;

import com.hardrock.modwhitelist.Modwhitelist;
import com.hardrock.modwhitelist.network.payload.ModScanChunkPayload;
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
    private static final int MAX_PACKET_BYTES = 24_000;

    private Net() {}

    public static final String PROTOCOL = "2";

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar(PROTOCOL);

        // Server -> Client (Request)
        registrar.playToClient(
                ModScanRequestPayload.TYPE,
                ModScanRequestPayload.STREAM_CODEC,
                (payload, ctx) -> {
                    try {
                        List<String> modIds = ModList.get().getMods().stream()
                                .map(m -> m.getModId())
                                .filter(Objects::nonNull)
                                .map(s -> s.toLowerCase(Locale.ROOT))
                                .distinct()
                                .sorted()
                                .collect(Collectors.toList());

                        List<ModScanResponsePayload.FileHash> files = scanModsFolder();

                        sendChunkedResponse(payload.nonce(), modIds, files);
                    } catch (Exception ex) {
                        LOGGER.error("[Modwhitelist] Client scan failed", ex);
                    }
                }
        );

        // Client -> Server (Chunked response)
        registrar.playToServer(
                ModScanChunkPayload.TYPE,
                ModScanChunkPayload.STREAM_CODEC,
                (payload, ctx) -> {
                    if (ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp) {
                        Modwhitelist.handleScanChunk(sp, payload);
                    }
                }
        );
    }

    public static void sendScanRequest(net.minecraft.server.level.ServerPlayer player, long nonce) {
        PacketDistributor.sendToPlayer(player, new ModScanRequestPayload(nonce));
    }

    private static void sendChunkedResponse(long nonce,
                                            List<String> modIds,
                                            List<ModScanResponsePayload.FileHash> files) {
        List<String> modChunk = new ArrayList<>();
        List<ModScanResponsePayload.FileHash> fileChunk = new ArrayList<>();
        int estimatedBytes = ModScanChunkPayload.basePacketBytes();

        for (String modId : modIds) {
            int itemBytes = ModScanChunkPayload.estimateModIdBytes(modId);
            if ((!modChunk.isEmpty() || !fileChunk.isEmpty()) && estimatedBytes + itemBytes > MAX_PACKET_BYTES) {
                flushChunk(nonce, false, modChunk, fileChunk);
                estimatedBytes = ModScanChunkPayload.basePacketBytes();
            }
            modChunk.add(modId);
            estimatedBytes += itemBytes;
        }

        for (ModScanResponsePayload.FileHash file : files) {
            int itemBytes = ModScanChunkPayload.estimateFileBytes(file);
            if ((!modChunk.isEmpty() || !fileChunk.isEmpty()) && estimatedBytes + itemBytes > MAX_PACKET_BYTES) {
                flushChunk(nonce, false, modChunk, fileChunk);
                estimatedBytes = ModScanChunkPayload.basePacketBytes();
            }
            fileChunk.add(file);
            estimatedBytes += itemBytes;
        }

        flushChunk(nonce, true, modChunk, fileChunk);
    }

    private static void flushChunk(long nonce,
                                   boolean done,
                                   List<String> modChunk,
                                   List<ModScanResponsePayload.FileHash> fileChunk) {
        PacketDistributor.sendToServer(new ModScanChunkPayload(
                nonce,
                done,
                List.copyOf(modChunk),
                List.copyOf(fileChunk)
        ));

        modChunk.clear();
        fileChunk.clear();
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