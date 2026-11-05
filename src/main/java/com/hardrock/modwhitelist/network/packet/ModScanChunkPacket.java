package com.hardrock.modwhitelist.network.packet;

import com.hardrock.modwhitelist.Modwhitelist;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public record ModScanChunkPacket(long nonce, boolean done, List<ModScanResponsePacket.ModEntry> mods, List<FileHash> files) {
    private static final int MAX_ENTRIES_PER_PACKET = 512;

    public record FileHash(String name, String sha256) {}

    public static void encode(ModScanChunkPacket pkt, FriendlyByteBuf buf) {
        buf.writeLong(pkt.nonce);
        buf.writeBoolean(pkt.done);
        buf.writeVarInt(pkt.mods.size());
        for (ModScanResponsePacket.ModEntry mod : pkt.mods) {
            buf.writeUtf(mod == null || mod.modid() == null ? "" : mod.modid(), 128);
            buf.writeUtf(mod == null || mod.version() == null ? "" : mod.version(), 128);
        }
        buf.writeVarInt(pkt.files.size());
        for (FileHash file : pkt.files) {
            buf.writeUtf(file == null || file.name() == null ? "" : file.name(), 512);
            buf.writeUtf(file == null || file.sha256() == null ? "" : file.sha256(), 64);
        }
    }

    public static ModScanChunkPacket decode(FriendlyByteBuf buf) {
        long nonce = buf.readLong();
        boolean done = buf.readBoolean();
        int modCount = readCount(buf, "mods");
        List<ModScanResponsePacket.ModEntry> mods = new ArrayList<>(modCount);
        for (int i = 0; i < modCount; i++) mods.add(new ModScanResponsePacket.ModEntry(buf.readUtf(128), buf.readUtf(128)));
        int fileCount = readCount(buf, "files");
        List<FileHash> files = new ArrayList<>(fileCount);
        for (int i = 0; i < fileCount; i++) files.add(new FileHash(buf.readUtf(512), buf.readUtf(64)));
        return new ModScanChunkPacket(nonce, done, mods, files);
    }

    private static int readCount(FriendlyByteBuf buf, String type) {
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_ENTRIES_PER_PACKET) throw new DecoderException("Invalid ModWhitelist " + type + " count: " + count);
        return count;
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

    public static int estimateModBytes(ModScanResponsePacket.ModEntry mod) {
        if (mod == null) return estimateUtfBytes("") + estimateUtfBytes("");
        return estimateUtfBytes(mod.modid()) + estimateUtfBytes(mod.version());
    }

    public static int estimateFileBytes(FileHash file) {
        if (file == null) return estimateUtfBytes("") + estimateUtfBytes("");
        return estimateUtfBytes(file.name()) + estimateUtfBytes(file.sha256());
    }

    private static int estimateUtfBytes(String s) {
        String value = s == null ? "" : s;
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