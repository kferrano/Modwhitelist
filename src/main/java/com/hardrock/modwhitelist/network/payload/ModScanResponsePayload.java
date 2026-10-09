package com.hardrock.modwhitelist.network.payload;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

public record ModScanResponsePayload(
        long nonce,
        List<ModEntry> mods,
        List<FileHash> files
) implements CustomPacketPayload {

    public record ModEntry(
            String modid,
            String version
    ) {}

    public record FileHash(
            String name,
            String sha256
    ) {}

    public static final Type<ModScanResponsePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    "modwhitelist",
                    "scan_response"
            ));

    public static final StreamCodec<ByteBuf, ModEntry> MOD_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(128), ModEntry::modid,
                    ByteBufCodecs.stringUtf8(128), ModEntry::version,
                    ModEntry::new
            );

    public static final StreamCodec<ByteBuf, FileHash> FILE_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(512), FileHash::name,
                    ByteBufCodecs.stringUtf8(64), FileHash::sha256,
                    FileHash::new
            );

    public static final StreamCodec<ByteBuf, ModScanResponsePayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_LONG,
                    ModScanResponsePayload::nonce,

                    ByteBufCodecs.collection(java.util.ArrayList::new,MOD_CODEC,512),
                    ModScanResponsePayload::mods,ByteBufCodecs.collection(java.util.ArrayList::new,FILE_CODEC,512),
                    ModScanResponsePayload::files,
                    ModScanResponsePayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}