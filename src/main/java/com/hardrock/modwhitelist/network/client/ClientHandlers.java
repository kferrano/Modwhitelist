package com.hardrock.modwhitelist.network.client;

import com.hardrock.modwhitelist.network.Net;
import com.hardrock.modwhitelist.network.packet.ModScanChunkPacket;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;

import java.util.stream.Collectors;

import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class ClientHandlers {
    private static final int MAX_PACKET_BYTES = 24_000;

    private ClientHandlers() {}

    public static void init() {
        // no-op; class-load barrier only
    }

    public static void onScanRequest(long nonce) {
        // 1) Mod IDs
        List<String> ids = Loader.instance()
                .getModList()
                .stream()
                .map(ModContainer::getModId)
                .distinct()
                .sorted(String::compareToIgnoreCase)
                .collect(Collectors.toList());

        // 2) /mods file list (jar/zip) with sha256
        List<ModScanChunkPacket.FileHash> files = new ArrayList<>();
        try {
            Path modsDir = Loader.instance()
                    .getConfigDir()
                    .toPath()
                    .getParent()
                    .resolve("mods");
            scanDirectory(modsDir, "", files);
            scanDirectory(
                    modsDir.resolve("1.7.10"),
                    "1.7.10/",
                    files
            );
            if (Files.isDirectory(modsDir)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(modsDir)) {
                    for (Path p : ds) {
                        if (!Files.isRegularFile(p)) continue;
                        String name = p.getFileName().toString();
                        if (!(name.endsWith(".jar") || name.endsWith(".zip"))) continue;

                        String sha = sha256Hex(p);
                        files.add(new ModScanChunkPacket.FileHash(name, sha));
                    }
                }
            }
        } catch (Exception ignored) {}

        files.sort(Comparator.comparing(f -> f.name().toLowerCase()));

        sendChunkedResponse(nonce, ids, files);
    }
    private static void scanDirectory(
            Path dir,
            String prefix,
            List<ModScanChunkPacket.FileHash> files
    ) throws Exception {

        if (!Files.isDirectory(dir)) {
            return;
        }

        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                if (!Files.isRegularFile(p)) continue;

                String name = p.getFileName().toString();

                if (!(name.endsWith(".jar") || name.endsWith(".zip"))) {
                    continue;
                }

                files.add(new ModScanChunkPacket.FileHash(
                        prefix + name,
                        sha256Hex(p)
                ));
            }
        }
    }

    private static void sendChunkedResponse(long nonce, List<String> ids, List<ModScanChunkPacket.FileHash> files) {
        List<String> modChunk = new ArrayList<>();
        List<ModScanChunkPacket.FileHash> fileChunk = new ArrayList<>();
        int estimatedBytes = ModScanChunkPacket.basePacketBytes();

        for (String id : ids) {
            int itemBytes = ModScanChunkPacket.estimateModIdBytes(id);
            if ((!modChunk.isEmpty() || !fileChunk.isEmpty()) && estimatedBytes + itemBytes > MAX_PACKET_BYTES) {
                flushChunk(nonce, false, modChunk, fileChunk);
                estimatedBytes = ModScanChunkPacket.basePacketBytes();
            }
            modChunk.add(id);
            estimatedBytes += itemBytes;
        }

        for (ModScanChunkPacket.FileHash file : files) {
            int itemBytes = ModScanChunkPacket.estimateFileBytes(file);
            if ((!modChunk.isEmpty() || !fileChunk.isEmpty()) && estimatedBytes + itemBytes > MAX_PACKET_BYTES) {
                flushChunk(nonce, false, modChunk, fileChunk);
                estimatedBytes = ModScanChunkPacket.basePacketBytes();
            }
            fileChunk.add(file);
            estimatedBytes += itemBytes;
        }

        flushChunk(nonce, true, modChunk, fileChunk);
    }

    private static void flushChunk(long nonce,
                                   boolean done,
                                   List<String> modChunk,
                                   List<ModScanChunkPacket.FileHash> fileChunk) {
        Net.CHANNEL.sendToServer(new ModScanChunkPacket(
                nonce,
                done,
                new ArrayList<String>(modChunk),
                new ArrayList<ModScanChunkPacket.FileHash>(fileChunk)
        ));

        modChunk.clear();
        fileChunk.clear();
    }

    private static String sha256Hex(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file, StandardOpenOption.READ)) {
            byte[] buf = new byte[1024 * 1024];
            int r;
            while ((r = in.read(buf)) != -1) md.update(buf, 0, r);
        }
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}