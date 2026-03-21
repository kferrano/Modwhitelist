package com.hardrock.modwhitelist.network.packet;

import com.hardrock.modwhitelist.network.client.ClientHandlers;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record ModScanRequestPacket(long nonce) {

    public static void encode(ModScanRequestPacket pkt, FriendlyByteBuf buf) {
        buf.writeLong(pkt.nonce);
    }

    public static ModScanRequestPacket decode(FriendlyByteBuf buf) {
        return new ModScanRequestPacket(buf.readLong());
    }

    public static void handle(ModScanRequestPacket pkt, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        ctx.enqueueWork(() -> ClientHandlers.onScanRequest(pkt.nonce));
        ctx.setPacketHandled(true);
    }
}
