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
        for (String modId : pkt.modIds) {
            buf.writeUtf(modId == null ? "" : modId);
        }

        buf.writeVarInt(pkt.files.size());
        for (FileHash file : pkt.files) {
            buf.writeUtf(file == null || file.name() == null ? "" : file.name());
            buf.writeUtf(file == null || file.sha256() == null ? "" : file.sha256());
        }
    }

    public static ModScanResponsePacket decode(FriendlyByteBuf buf) {
        long nonce = buf.readLong();

        int modCount = buf.readVarInt();
        List<String> modIds = new ArrayList<>(Math.max(0, modCount));

        for (int i = 0; i < modCount; i++) {
            modIds.add(buf.readUtf(32767));
        }

        int fileCount = buf.readVarInt();
        List<FileHash> files = new ArrayList<>(Math.max(0, fileCount));

        for (int i = 0; i < fileCount; i++) {
            String name = buf.readUtf(32767);
            String sha256 = buf.readUtf(32767);
            files.add(new FileHash(name, sha256));
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