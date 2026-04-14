package com.hardrock.modwhitelist.network.payload;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

public record ModScanResponsePayload(
        long nonce,
        List<String> modIds,
        List<FileHash> files
) implements CustomPacketPayload {

    public record FileHash(String name, String sha256) {}

    public static final Type<ModScanResponsePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("modwhitelist", "scan_response"));

    private static final StreamCodec<ByteBuf, FileHash> FILE_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, FileHash::name,
            ByteBufCodecs.STRING_UTF8, FileHash::sha256,
            FileHash::new
    );

    public static final StreamCodec<ByteBuf, ModScanResponsePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, ModScanResponsePayload::nonce,
            ByteBufCodecs.collection(java.util.ArrayList::new, ByteBufCodecs.STRING_UTF8), ModScanResponsePayload::modIds,
            ByteBufCodecs.collection(java.util.ArrayList::new, FILE_CODEC), ModScanResponsePayload::files,
            ModScanResponsePayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
