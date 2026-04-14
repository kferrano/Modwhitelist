package com.hardrock.modwhitelist.network.payload;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ModScanRequestPayload(long nonce) implements CustomPacketPayload {

    public static final Type<ModScanRequestPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("modwhitelist", "scan_request"));

    public static final StreamCodec<ByteBuf, ModScanRequestPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG,
            ModScanRequestPayload::nonce,
            ModScanRequestPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
