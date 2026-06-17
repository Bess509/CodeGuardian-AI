package com.codeguardian.service.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

final class SemanticFingerprintCalculator {

    private static final long FNV_OFFSET_BASIS_64 = 0xcbf29ce484222325L;
    private static final long FNV_PRIME_64 = 0x100000001b3L;

    private SemanticFingerprintCalculator() {
    }

    static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return toHex(bytes);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    static long simHash64(String normalizedText) {
        if (normalizedText == null || normalizedText.isBlank()) {
            return 0L;
        }
        String[] tokens = normalizedText.split("[^a-zA-Z0-9_]+");
        int[] bitWeights = new int[64];
        for (String token : tokens) {
            if (token == null || token.isBlank()) {
                continue;
            }
            long hash = fnv1a64(token);
            for (int i = 0; i < 64; i++) {
                long bit = (hash >>> i) & 1L;
                bitWeights[i] += bit == 1L ? 1 : -1;
            }
        }
        long result = 0L;
        for (int i = 0; i < 64; i++) {
            if (bitWeights[i] > 0) {
                result |= (1L << i);
            }
        }
        return result;
    }

    static int hammingDistance64(long a, long b) {
        return Long.bitCount(a ^ b);
    }

    static List<String> bucketKeys(String scopePrefix, long simHash64, int segments) {
        int seg = Math.max(1, segments);
        if (seg > 8) {
            seg = 8;
        }
        int bitsPerSegment = 64 / seg;
        List<String> keys = new ArrayList<>(seg);
        for (int i = 0; i < seg; i++) {
            int shift = i * bitsPerSegment;
            long mask = bitsPerSegment >= 64 ? -1L : ((1L << bitsPerSegment) - 1L);
            long value = (simHash64 >>> shift) & mask;
            keys.add(scopePrefix + ":sim:" + i + ":" + Long.toHexString(value));
        }
        return keys;
    }

    private static long fnv1a64(String s) {
        long hash = FNV_OFFSET_BASIS_64;
        for (int i = 0; i < s.length(); i++) {
            hash ^= s.charAt(i);
            hash *= FNV_PRIME_64;
        }
        return hash;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
