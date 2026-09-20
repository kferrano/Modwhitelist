package com.hardrock.modwhitelist.network.client;

import com.hardrock.modwhitelist.network.Net;
import com.hardrock.modwhitelist.network.packet.ModScanChunkPacket;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class ClientHandlers {
    private static final int MAX_PACKET_BYTES = 24_000;

    private ClientHandlers() {}

    public static void init() {
        // no-op; class-load barrier only
    }

    public static void onScanRequest(long nonce) {
        List<String> ids = ModList.get().getMods().stream()
                .map(mod -> mod.getModId())
                .distinct()
                .sorted(String::compareToIgnoreCase)
                .toList();

        List<ModScanChunkPacket.FileHash> files = new ArrayList<>();

        try {
            Path modsDir = FMLPaths.MODSDIR.get();

            if (Files.isDirectory(modsDir)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(modsDir)) {
                    for (Path path : ds) {
                        if (!Files.isRegularFile(path)) continue;

                        String name = path.getFileName().toString();
                        String lower = name.toLowerCase(Locale.ROOT);

                        if (!lower.endsWith(".jar") && !lower.endsWith(".zip")) continue;

                        files.add(new ModScanChunkPacket.FileHash(name, sha256Hex(path)));
                    }
                }
            }
        } catch (Exception ignored) {
        }

        files.sort(Comparator.comparing(file -> file.name().toLowerCase(Locale.ROOT)));
        sendChunkedResponse(nonce, ids, files);
    }

    private static void sendChunkedResponse(long nonce, List<String> ids, List<ModScanChunkPacket.FileHash> files) {
        List<String> modChunk = new ArrayList<>();
        List<ModScanChunkPacket.FileHash> fileChunk = new ArrayList<>();
        int estimatedBytes = ModScanChunkPacket.basePacketBytes();

        for (String id : ids) {
            int itemBytes = ModScanChunkPacket.estimateModIdBytes(id);

            if ((!modChunk.isEmpty() || !fileChunk.isEmpty())
                    && estimatedBytes + itemBytes > MAX_PACKET_BYTES) {
                flushChunk(nonce, false, modChunk, fileChunk);
                estimatedBytes = ModScanChunkPacket.basePacketBytes();
            }

            modChunk.add(id);
            estimatedBytes += itemBytes;
        }

        for (ModScanChunkPacket.FileHash file : files) {
            int itemBytes = ModScanChunkPacket.estimateFileBytes(file);

            if ((!modChunk.isEmpty() || !fileChunk.isEmpty())
                    && estimatedBytes + itemBytes > MAX_PACKET_BYTES) {
                flushChunk(nonce, false, modChunk, fileChunk);
                estimatedBytes = ModScanChunkPacket.basePacketBytes();
            }

            fileChunk.add(file);
            estimatedBytes += itemBytes;
        }

        flushChunk(nonce, true, modChunk, fileChunk);
    }

    private static void flushChunk(
            long nonce,
            boolean done,
            List<String> modChunk,
            List<ModScanChunkPacket.FileHash> fileChunk
    ) {
        Net.CHANNEL.sendToServer(new ModScanChunkPacket(
                nonce,
                done,
                List.copyOf(modChunk),
                List.copyOf(fileChunk)
        ));

        modChunk.clear();
        fileChunk.clear();
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

        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }

        return sb.toString();
    }
}