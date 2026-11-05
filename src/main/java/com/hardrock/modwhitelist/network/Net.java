package com.hardrock.modwhitelist.network;

import com.hardrock.modwhitelist.Modwhitelist;
import com.hardrock.modwhitelist.network.packet.ModScanChunkPacket;
import com.hardrock.modwhitelist.network.packet.ModScanRequestPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;

public final class Net {
    public static final String PROTOCOL = "3";
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
                ModScanRequestPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        CHANNEL.registerMessage(
                id++,
                ModScanChunkPacket.class,
                ModScanChunkPacket::encode,
                ModScanChunkPacket::decode,
                ModScanChunkPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> com.hardrock.modwhitelist.network.client.ClientHandlers::init);
    }

    public static void sendTo(ServerPlayer player, Object msg) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), msg);
    }
}