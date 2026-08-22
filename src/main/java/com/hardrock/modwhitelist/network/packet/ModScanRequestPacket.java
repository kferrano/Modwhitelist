package com.hardrock.modwhitelist.network.packet;

import com.hardrock.modwhitelist.network.client.ClientHandlers;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;

import io.netty.buffer.ByteBuf;

public final class ModScanRequestPacket implements IMessage {

    private long nonce;

    public ModScanRequestPacket() {}

    public ModScanRequestPacket(long nonce) {
        this.nonce = nonce;
    }

    public long nonce() {
        return nonce;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        nonce = buf.readLong();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(nonce);
    }

    public static final class Handler
            implements IMessageHandler<ModScanRequestPacket, IMessage> {

        @Override
        public IMessage onMessage(
                final ModScanRequestPacket pkt,
                MessageContext ctx
        ) {
            /*
             * Hashing kann bei großen Packs dauern.
             * Nicht den Netty-Thread damit blockieren.
             */
            Thread scanThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    ClientHandlers.onScanRequest(pkt.nonce());
                }
            }, "ModWhitelist-Scan");

            scanThread.setDaemon(true);
            scanThread.start();

            return null;
        }
    }
}