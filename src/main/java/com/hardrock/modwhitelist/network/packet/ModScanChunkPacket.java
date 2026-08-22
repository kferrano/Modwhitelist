package com.hardrock.modwhitelist.network.packet;

import com.hardrock.modwhitelist.Modwhitelist;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;

import io.netty.buffer.ByteBuf;

import net.minecraft.entity.player.EntityPlayerMP;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class ModScanChunkPacket implements IMessage {

    private long nonce;
    private boolean done;
    private List<String> modIds = new ArrayList<String>();
    private List<FileHash> files = new ArrayList<FileHash>();

    public ModScanChunkPacket() {}

    public ModScanChunkPacket(
            long nonce,
            boolean done,
            List<String> modIds,
            List<FileHash> files
    ) {
        this.nonce = nonce;
        this.done = done;
        this.modIds = modIds;
        this.files = files;
    }

    public long nonce() {
        return nonce;
    }

    public boolean done() {
        return done;
    }

    public List<String> modIds() {
        return modIds;
    }

    public List<FileHash> files() {
        return files;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(nonce);
        buf.writeBoolean(done);

        ByteBufUtils.writeVarInt(buf, modIds.size(), 5);

        for (String s : modIds) {
            ByteBufUtils.writeUTF8String(
                    buf,
                    s == null ? "" : s
            );
        }

        ByteBufUtils.writeVarInt(buf, files.size(), 5);

        for (FileHash f : files) {
            ByteBufUtils.writeUTF8String(
                    buf,
                    f == null || f.name() == null ? "" : f.name()
            );

            ByteBufUtils.writeUTF8String(
                    buf,
                    f == null || f.sha256() == null ? "" : f.sha256()
            );
        }
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        nonce = buf.readLong();
        done = buf.readBoolean();

        int modCount = ByteBufUtils.readVarInt(buf, 5);

        modIds = new ArrayList<String>(Math.max(0, modCount));

        for (int i = 0; i < modCount; i++) {
            modIds.add(ByteBufUtils.readUTF8String(buf));
        }

        int fileCount = ByteBufUtils.readVarInt(buf, 5);

        files = new ArrayList<FileHash>(Math.max(0, fileCount));

        for (int i = 0; i < fileCount; i++) {
            files.add(new FileHash(
                    ByteBufUtils.readUTF8String(buf),
                    ByteBufUtils.readUTF8String(buf)
            ));
        }
    }

    public static int basePacketBytes() {
        return Long.BYTES + 1 + 5 + 5;
    }

    public static int estimateModIdBytes(String modId) {
        return estimateUtfBytes(modId);
    }

    public static int estimateFileBytes(FileHash file) {
        if (file == null) {
            return estimateUtfBytes("") + estimateUtfBytes("");
        }

        return estimateUtfBytes(file.name())
                + estimateUtfBytes(file.sha256());
    }

    private static int estimateUtfBytes(String s) {
        String value = s == null ? "" : s;

        int utf8Len =
                value.getBytes(StandardCharsets.UTF_8).length;

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

    public static final class FileHash {

        private final String name;
        private final String sha256;

        public FileHash(String name, String sha256) {
            this.name = name;
            this.sha256 = sha256;
        }

        public String name() {
            return name;
        }

        public String sha256() {
            return sha256;
        }
    }

    public static final class Handler
            implements IMessageHandler<ModScanChunkPacket, IMessage> {

        @Override
        public IMessage onMessage(
                final ModScanChunkPacket pkt,
                MessageContext ctx
        ) {
            final EntityPlayerMP sender =
                    ctx.getServerHandler().playerEntity;

            /*
             * Wieder auf den Server-Thread.
             */
            Modwhitelist.enqueueServerTask(new Runnable() {
                @Override
                public void run() {
                    Modwhitelist.handleScanChunk(sender, pkt);
                }
            });

            return null;
        }
    }
}