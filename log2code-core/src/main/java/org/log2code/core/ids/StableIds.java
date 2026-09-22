package org.log2code.core.ids;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Stable identifiers (0.8): the first 32 hex characters of the SHA-256 digest of the
 * given parts, joined with U+001F (INFORMATION SEPARATOR ONE). The first part is
 * always a type tag. A {@code null} part is encoded as an empty string.
 */
public final class StableIds {

    private static final char UNIT_SEPARATOR = '';
    private static final int ID_BYTES = 16;

    private StableIds() {
    }

    public static String hash(String... parts) {
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                joined.append(UNIT_SEPARATOR);
            }
            String part = parts[i];
            joined.append(part == null ? "" : part);
        }
        byte[] digest = sha256(joined.toString().getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest, 0, ID_BYTES);
    }

    public static String statementId(
        String codeUnitName, String codeUnitVersion, String filePath,
        String classFqn, String methodSignature, String template, int ordinal
    ) {
        return hash("stmt", codeUnitName, codeUnitVersion, filePath, classFqn, methodSignature, template, Integer.toString(ordinal));
    }

    public static String logicalId(
        String codeUnitName, String filePath, String classFqn, String methodSignature, String template, int ordinal
    ) {
        return hash("stmt", codeUnitName, filePath, classFqn, methodSignature, template, Integer.toString(ordinal));
    }

    public static String fileId(String codeUnitName, String codeUnitVersion, String filePath) {
        return hash("file", codeUnitName, codeUnitVersion, filePath);
    }

    public static String methodId(String codeUnitName, String codeUnitVersion, String classFqn, String methodSignature) {
        return hash("method", codeUnitName, codeUnitVersion, classFqn, methodSignature);
    }

    public static String typeId(String codeUnitName, String codeUnitVersion, String classFqn) {
        return hash("type", codeUnitName, codeUnitVersion, classFqn);
    }

    public static String runId(String kind, String codeUnitName, String codeUnitVersion, String analyzerVersion) {
        return hash("run", kind, codeUnitName, codeUnitVersion, analyzerVersion);
    }

    public static String logId(String datasetId, String sourceFile, int lineNumber) {
        return hash("log", datasetId, sourceFile, Integer.toString(lineNumber));
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
