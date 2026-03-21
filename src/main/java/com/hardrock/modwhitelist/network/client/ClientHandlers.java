package com.hardrock.modwhitelist.network.client;

import com.hardrock.modwhitelist.network.Net;
import com.hardrock.modwhitelist.network.packet.ModScanResponsePacket;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class ClientHandlers {
    private ClientHandlers() {}

    public static void init() {
        // no-op; class-load barrier only
    }

    public static void onScanRequest(long nonce) {
        // 1) Mod IDs
        List<String> ids = ModList.get().getMods().stream()
                .map(m -> m.getModId())
                .distinct()
                .sorted(String::compareToIgnoreCase)
                .toList();

        // 2) /mods file list (jar/zip) with sha256
        List<ModScanResponsePacket.FileHash> files = new ArrayList<>();
        try {
            Path modsDir = FMLPaths.MODSDIR.get();
            if (Files.isDirectory(modsDir)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(modsDir)) {
                    for (Path p : ds) {
                        if (!Files.isRegularFile(p)) continue;
                        String name = p.getFileName().toString();
                        if (!(name.endsWith(".jar") || name.endsWith(".zip"))) continue;

                        String sha = sha256Hex(p);
                        files.add(new ModScanResponsePacket.FileHash(name, sha));
                    }
                }
            }
        } catch (Exception ignored) {}

        files.sort(Comparator.comparing(f -> f.name().toLowerCase()));

        Net.CHANNEL.sendToServer(new ModScanResponsePacket(nonce, ids, files));
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
