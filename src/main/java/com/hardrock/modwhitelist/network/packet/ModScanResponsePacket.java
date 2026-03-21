package com.hardrock.modwhitelist.network.packet;

import com.hardrock.modwhitelist.Modwhitelist;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public record ModScanResponsePacket(long nonce, List<String> modIds, List<FileHash> files) {

    public record FileHash(String name, String sha256) {}

    public static void encode(ModScanResponsePacket pkt, FriendlyByteBuf buf) {
        buf.writeLong(pkt.nonce);

        buf.writeVarInt(pkt.modIds.size());
        for (String s : pkt.modIds) buf.writeUtf(s == null ? "" : s);

        buf.writeVarInt(pkt.files.size());
        for (FileHash f : pkt.files) {
            buf.writeUtf(f == null || f.name() == null ? "" : f.name());
            buf.writeUtf(f == null || f.sha256() == null ? "" : f.sha256());
        }
    }

    public static ModScanResponsePacket decode(FriendlyByteBuf buf) {
        long nonce = buf.readLong();

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

        return new ModScanResponsePacket(nonce, modIds, files);
    }

    public static void handle(ModScanResponsePacket pkt, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        ServerPlayer sender = ctx.getSender();
        ctx.enqueueWork(() -> Modwhitelist.handleScanResponse(sender, pkt));
        ctx.setPacketHandled(true);
    }
}
