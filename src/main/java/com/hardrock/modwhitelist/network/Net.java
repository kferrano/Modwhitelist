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
import net.neoforged.neoforge.network.handling.IPayloadContext;

import org.slf4j.Logger;

import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class Net {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_PACKET_BYTES = 24_000;
    private static final ExecutorService CLIENT_SCAN_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "ModWhitelist-ClientScan");
        thread.setDaemon(true);
        return thread;
    });

    private Net() {}

    public static final String PROTOCOL = "3";

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar(PROTOCOL);

        // Server -> Client (Request)
        registrar.playToClient(
                ModScanRequestPayload.TYPE,
                ModScanRequestPayload.STREAM_CODEC,
                (payload, ctx) -> {
                    List<ModScanResponsePayload.ModEntry> mods =
                            ModList.get().getMods().stream()
                                    .filter(Objects::nonNull)
                                    .map(m -> new ModScanResponsePayload.ModEntry(
                                            m.getModId().toLowerCase(Locale.ROOT),
                                            m.getVersion().toString()
                                    ))
                                    .filter(m -> !m.modid().isBlank())
                                    .distinct()
                                    .sorted(Comparator.comparing(
                                            ModScanResponsePayload.ModEntry::modid
                                    ))
                                    .collect(Collectors.toList());

                    long started = System.nanoTime();

                    CompletableFuture.supplyAsync(() -> {
                        try {
                            return scanModsFolder();
                        } catch (Exception ex) {
                            throw new CompletionException(ex);
                        }
                    }, CLIENT_SCAN_EXECUTOR).thenAccept(files -> {
                        long durationMs = (System.nanoTime() - started) / 1_000_000L;

                        LOGGER.info(
                                "[Modwhitelist] Client scan completed in {} ms ({} mods, {} files)",
                                durationMs,
                                mods.size(),
                                files.size()
                        );

                        ctx.enqueueWork(() -> {
                            if (!ctx.connection().isConnected()) {
                                LOGGER.debug("[Modwhitelist] Client disconnected before scan response could be sent. Discarding scan result.");
                                return;
                            }
                            sendChunkedResponse(ctx, payload.nonce(), mods, files);
                        });
                    }).exceptionally(ex -> {
                        LOGGER.error("[Modwhitelist] Client scan failed", ex);
                        return null;
                    });
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

    private static void sendChunkedResponse(
            IPayloadContext ctx,
            long nonce,
            List<ModScanResponsePayload.ModEntry> mods,
            List<ModScanResponsePayload.FileHash> files
    ) {
        List<ModScanResponsePayload.ModEntry> modChunk = new ArrayList<>();

        List<ModScanResponsePayload.FileHash> fileChunk = new ArrayList<>();

        int estimatedBytes = ModScanChunkPayload.basePacketBytes();

        for (ModScanResponsePayload.ModEntry mod : mods) {
            int itemBytes = ModScanChunkPayload.estimateModBytes(mod);

            if ((!modChunk.isEmpty() || !fileChunk.isEmpty()) && estimatedBytes + itemBytes > MAX_PACKET_BYTES) {

                if (!flushChunk(ctx, nonce, false, modChunk, fileChunk)) {
                    return;
                }

                estimatedBytes = ModScanChunkPayload.basePacketBytes();
            }

            modChunk.add(mod);
            estimatedBytes += itemBytes;
        }

        for (ModScanResponsePayload.FileHash file : files) {
            int itemBytes = ModScanChunkPayload.estimateFileBytes(file);

            if ((!modChunk.isEmpty() || !fileChunk.isEmpty()) && estimatedBytes + itemBytes > MAX_PACKET_BYTES) {

                if (!flushChunk(ctx, nonce, false, modChunk, fileChunk)) {
                    return;
                }

                estimatedBytes = ModScanChunkPayload.basePacketBytes();
            }

            fileChunk.add(file);
            estimatedBytes += itemBytes;
        }

        flushChunk(ctx, nonce, true, modChunk, fileChunk);
    }

    private static boolean flushChunk(IPayloadContext ctx, long nonce, boolean done, List<ModScanResponsePayload.ModEntry> modChunk, List<ModScanResponsePayload.FileHash> fileChunk) {
        if (!ctx.connection().isConnected()) {
            LOGGER.debug("[Modwhitelist] Client disconnected during scan response. Remaining chunks discarded.");
            return false;
        }

        ctx.reply(new ModScanChunkPayload(nonce, done, List.copyOf(modChunk), List.copyOf(fileChunk)));

        modChunk.clear();
        fileChunk.clear();
        return true;
    }

    private static List<ModScanResponsePayload.FileHash> scanModsFolder() throws Exception {
        Path modsDir = Paths.get("").toAbsolutePath().resolve("mods");
        if (!Files.isDirectory(modsDir)) return List.of();

        List<ModScanResponsePayload.FileHash> out = new ArrayList<>();

        try (DirectoryStream<Path> ds = Files.newDirectoryStream(modsDir)) {
            for (Path p : ds) {
                if (!Files.isRegularFile(p)) continue;

                String name = p.getFileName().toString();
                String lowerName = name.toLowerCase(Locale.ROOT);
                if (!(lowerName.endsWith(".jar") || lowerName.endsWith(".zip"))) continue;

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