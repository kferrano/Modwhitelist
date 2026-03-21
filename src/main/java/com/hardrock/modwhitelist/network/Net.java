package com.hardrock.modwhitelist.network;

import com.hardrock.modwhitelist.Modwhitelist;
import com.hardrock.modwhitelist.network.packet.ModScanChunkPacket;
import com.hardrock.modwhitelist.network.packet.ModScanRequestPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public final class Net {
    private static final String PROTOCOL = "2";
    private static int id = 0;

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.tryBuild(Modwhitelist.MODID, "main"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals
    );

    private Net() {}

    public static void init() {
        CHANNEL.registerMessage(
                id++,
                ModScanRequestPacket.class,
                ModScanRequestPacket::encode,
                ModScanRequestPacket::decode,
                ModScanRequestPacket::handle
        );

        CHANNEL.registerMessage(
                id++,
                ModScanChunkPacket.class,
                ModScanChunkPacket::encode,
                ModScanChunkPacket::decode,
                ModScanChunkPacket::handle
        );

        DistExecutor.safeRunWhenOn(Dist.CLIENT,
                () -> com.hardrock.modwhitelist.network.client.ClientHandlers::init
        );
    }

    public static void sendTo(ServerPlayer player, Object msg) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), msg);
    }
}