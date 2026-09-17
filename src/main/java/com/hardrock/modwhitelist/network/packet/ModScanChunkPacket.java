package com.hardrock.modwhitelist.network.packet;

import com.hardrock.modwhitelist.Modwhitelist;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public record ModScanChunkPacket(long nonce, boolean done, List<String> modIds, List<FileHash> files) {

    public record FileHash(String name, String sha256) {}

    public static void encode(ModScanChunkPacket pkt, FriendlyByteBuf buf) {
        buf.writeLong(pkt.nonce);
        buf.writeBoolean(pkt.done);

        buf.writeVarInt(pkt.modIds.size());
        for (String s : pkt.modIds) buf.writeUtf(s == null ? "" : s);

        buf.writeVarInt(pkt.files.size());
        for (FileHash f : pkt.files) {
            buf.writeUtf(f == null || f.name() == null ? "" : f.name());
            buf.writeUtf(f == null || f.sha256() == null ? "" : f.sha256());
        }
    }

    public static ModScanChunkPacket decode(FriendlyByteBuf buf) {
        long nonce = buf.readLong();
        boolean done = buf.readBoolean();

        int m = buf.readVarInt();
        List<String> modIds = new ArrayList<>(Math.max(0, m));
        for (int i = 0; i < m; i++) modIds.add(buf.readUtf(32767));

        int f = buf.readVarInt();
        List<FileHash> files = new ArrayList<>(Math.max(0, f));
        for (int i = 0; i < f; i++) {
            String name = buf.readUtf(32767);
            String sha = buf.readUtf(32767);
            files.add(new FileHash(name, sha));
        }

        return new ModScanChunkPacket(nonce, done, modIds, files);
    }

    public static void handle(ModScanChunkPacket pkt, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        ServerPlayer sender = ctx.getSender();
        ctx.enqueueWork(() -> Modwhitelist.handleScanChunk(sender, pkt));
        ctx.setPacketHandled(true);
    }

    public static int basePacketBytes() {
        return Long.BYTES + 1 + 5 + 5;
    }

    public static int estimateModIdBytes(String modId) {
        return estimateUtfBytes(modId);
    }

    public static int estimateFileBytes(FileHash file) {
        if (file == null) return estimateUtfBytes("") + estimateUtfBytes("");
        return estimateUtfBytes(file.name()) + estimateUtfBytes(file.sha256());
    }

    private static int estimateUtfBytes(String s) {
        String value = (s == null) ? "" : s;
        int utf8Len = value.getBytes(StandardCharsets.UTF_8).length;
        return varIntSize(utf8Len) + utf8Len;
    }

    private static int varIntSize(int value) {
        int size = 1;
        while ((value & -128) != 0) {
            value >>>= 7;
            size++;
        }
        return size;
    }
}