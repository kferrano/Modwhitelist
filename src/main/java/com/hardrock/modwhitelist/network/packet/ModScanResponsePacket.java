package com.hardrock.modwhitelist.network.packet;

import java.util.List;

public final class ModScanResponsePacket {

    private final long nonce;
    private final List<String> modIds;
    private final List<FileHash> files;

    public ModScanResponsePacket(
            long nonce,
            List<String> modIds,
            List<FileHash> files
    ) {
        this.nonce = nonce;
        this.modIds = modIds;
        this.files = files;
    }

    public long nonce() {
        return nonce;
    }

    public List<String> modIds() {
        return modIds;
    }

    public List<FileHash> files() {
        return files;
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
}