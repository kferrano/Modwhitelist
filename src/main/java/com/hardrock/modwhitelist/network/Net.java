package com.hardrock.modwhitelist.network;

import com.hardrock.modwhitelist.Modwhitelist;
import com.hardrock.modwhitelist.network.packet.ModScanChunkPacket;
import com.hardrock.modwhitelist.network.packet.ModScanRequestPacket;

import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;

import net.minecraft.entity.player.EntityPlayerMP;

public final class Net {

    private static int id = 0;

    public static final SimpleNetworkWrapper CHANNEL =
            NetworkRegistry.INSTANCE.newSimpleChannel(Modwhitelist.MODID);

    private Net() {}

    public static void init() {
        CHANNEL.registerMessage(
                ModScanRequestPacket.Handler.class,
                ModScanRequestPacket.class,
                id++,
                Side.CLIENT
        );

        CHANNEL.registerMessage(
                ModScanChunkPacket.Handler.class,
                ModScanChunkPacket.class,
                id++,
                Side.SERVER
        );
    }

    public static void sendTo(EntityPlayerMP player, IMessage msg) {
        CHANNEL.sendTo(msg, player);
    }
}