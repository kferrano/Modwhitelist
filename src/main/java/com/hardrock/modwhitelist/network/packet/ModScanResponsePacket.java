package com.hardrock.modwhitelist.network.packet;

import com.hardrock.modwhitelist.Modwhitelist;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public record ModScanResponsePacket(long nonce, List<ModEntry> mods, List<FileHash> files) {
    private static final int MAX_ENTRIES_PER_PACKET = 512;

    public record ModEntry(String modid, String version) {}
    public record FileHash(String name, String sha256) {}

    public static void encode(ModScanResponsePacket pkt, FriendlyByteBuf buf) {
        buf.writeLong(pkt.nonce);
        buf.writeVarInt(pkt.mods.size());
        for (ModEntry mod : pkt.mods) {
            buf.writeUtf(mod == null || mod.modid() == null ? "" : mod.modid(), 128);
            buf.writeUtf(mod == null || mod.version() == null ? "" : mod.version(), 128);
        }
        buf.writeVarInt(pkt.files.size());
        for (FileHash file : pkt.files) {
            buf.writeUtf(file == null || file.name() == null ? "" : file.name(), 512);
            buf.writeUtf(file == null || file.sha256() == null ? "" : file.sha256(), 64);
        }
    }

    public static ModScanResponsePacket decode(FriendlyByteBuf buf) {
        long nonce = buf.readLong();
        int modCount = readCount(buf, "mods");
        List<ModEntry> mods = new ArrayList<>(modCount);
        for (int i = 0; i < modCount; i++) mods.add(new ModEntry(buf.readUtf(128), buf.readUtf(128)));
        int fileCount = readCount(buf, "files");
        List<FileHash> files = new ArrayList<>(fileCount);
        for (int i = 0; i < fileCount; i++) files.add(new FileHash(buf.readUtf(512), buf.readUtf(64)));
        return new ModScanResponsePacket(nonce, mods, files);
    }

    private static int readCount(FriendlyByteBuf buf, String type) {
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_ENTRIES_PER_PACKET) throw new DecoderException("Invalid ModWhitelist " + type + " count: " + count);
        return count;
    }

    public static void handle(ModScanResponsePacket pkt, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        ServerPlayer sender = ctx.getSender();
        ctx.enqueueWork(() -> Modwhitelist.handleScanResponse(sender, pkt));
        ctx.setPacketHandled(true);
    }
}