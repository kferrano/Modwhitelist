package com.hardrock.modwhitelist.network.payload;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.nio.charset.StandardCharsets;
import java.util.List;

public record ModScanChunkPayload(
        long nonce,
        boolean done,
        List<String> modIds,
        List<ModScanResponsePayload.FileHash> files
) implements CustomPacketPayload {

    public static final Type<ModScanChunkPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("modwhitelist", "scan_chunk"));

    private static final StreamCodec<ByteBuf, ModScanResponsePayload.FileHash> FILE_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, ModScanResponsePayload.FileHash::name,
            ByteBufCodecs.STRING_UTF8, ModScanResponsePayload.FileHash::sha256,
            ModScanResponsePayload.FileHash::new
    );

    public static final StreamCodec<ByteBuf, ModScanChunkPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, ModScanChunkPayload::nonce,
            ByteBufCodecs.BOOL, ModScanChunkPayload::done,
            ByteBufCodecs.collection(java.util.ArrayList::new, ByteBufCodecs.STRING_UTF8), ModScanChunkPayload::modIds,
            ByteBufCodecs.collection(java.util.ArrayList::new, FILE_CODEC), ModScanChunkPayload::files,
            ModScanChunkPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static int basePacketBytes() {
        return Long.BYTES + 1 + 5 + 5;
    }

    public static int estimateModIdBytes(String modId) {
        return estimateUtfBytes(modId);
    }

    public static int estimateFileBytes(ModScanResponsePayload.FileHash file) {
        if (file == null) return estimateUtfBytes("") + estimateUtfBytes("");
        return estimateUtfBytes(file.name()) + estimateUtfBytes(file.sha256());
    }

    private static int estimateUtfBytes(String s) {
        String value = (s == null) ? "" : s;
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