package com.weartak.version;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

public final class WearVersion {
    private static final String PROJECT_ID = "6739";
    private static final String SHA_PATTERN = "[0-9a-f]{40}";
    public final String name;
    public final int code;

    private WearVersion(String name, int code) {
        this.name = name;
        this.code = code;
    }

    public static WearVersion resolve(Path root, Map<String, String> env, boolean signing)
            throws IOException {
        Path canonical = root.resolve(".gradle/wear-version.properties");
        boolean ci = "true".equals(env.get("CI"));
        boolean forwarded = "pipeline".equals(env.get("CI_PIPELINE_SOURCE"))
                || env.keySet().stream().anyMatch(key -> key.startsWith("WEARTAK_VERSION_"))
                || env.containsKey("WEARTAK_BASE_VERSION_CODE");
        if (!Files.isRegularFile(canonical)) {
            if (ci || signing || forwarded) {
                throw new IllegalArgumentException("Authenticated canonical WearTAK version is required.");
            }
            return new WearVersion("5.8.4-dev", 5840000);
        }
        Properties properties = load(canonical);
        String version = required(properties, "versionName");
        String base = required(properties, "baseVersionCode");
        String sha = required(properties, "sourceSha");
        if (!PROJECT_ID.equals(required(properties, "sourceProjectId")) || !sha.matches(SHA_PATTERN)) {
            throw new IllegalArgumentException("Invalid canonical WearTAK source identity.");
        }
        if (forwarded) {
            requireEqual(env, "WEARTAK_VERSION_SOURCE_PROJECT_ID", PROJECT_ID);
            requireEqual(env, "WEARTAK_VERSION_SOURCE_SHA", sha);
            requireEqual(env, "WEARTAK_VERSION_NAME", version);
            requireEqual(env, "WEARTAK_BASE_VERSION_CODE", base);
        }
        String iid = env.get("CI_PIPELINE_IID");
        String commit = env.get("CI_COMMIT_SHA");
        if (iid == null || commit == null || !commit.matches(SHA_PATTERN)) {
            throw new IllegalArgumentException("Canonical builds require pipeline IID and full plugin SHA.");
        }
        return calculate(version, base, iid, commit);
    }

    public static WearVersion calculate(String version, String base, String iid, String commit) {
        if (!version.matches("[0-9]+\\.[0-9]+\\.[0-9]+")
                || !base.matches("[1-9][0-9]{0,5}") || !iid.matches("[1-9][0-9]{0,3}")
                || !commit.matches(SHA_PATTERN)) {
            throw new IllegalArgumentException("Invalid canonical version, base code, pipeline IID or SHA.");
        }
        long code = Long.parseLong(base) * 10000 + Integer.parseInt(iid);
        if (code > 2100000000L) {
            throw new IllegalArgumentException("WearTAK version code exceeds Android's 2100000000 limit.");
        }
        return new WearVersion(version + "-p" + iid + "+" + commit.substring(0, 8), (int) code);
    }

    private static Properties load(Path path) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        }
        return properties;
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Canonical version is missing " + key + ".");
        }
        return value;
    }

    private static void requireEqual(Map<String, String> env, String key, String expected) {
        if (!expected.equals(env.get(key))) {
            throw new IllegalArgumentException(key + " does not match authenticated main.");
        }
    }

    // Called only after prepare_wear_version.sh authenticates the fixed main ref.
    public static void main(String[] args) throws IOException {
        if (args.length != 3 || !args[1].matches(SHA_PATTERN)) {
            throw new IllegalArgumentException("Expected canonical file, authenticated main SHA, output file.");
        }
        Properties canonical = load(Path.of(args[0]));
        String version = required(canonical, "versionName");
        String base = required(canonical, "baseVersionCode");
        calculate(version, base, "1", "0".repeat(40));
        // Copy only canonical keys; source identity must never come from file contents.
        Properties verified = new Properties();
        verified.setProperty("versionName", version);
        verified.setProperty("baseVersionCode", base);
        verified.setProperty("sourceProjectId", PROJECT_ID);
        verified.setProperty("sourceSha", args[1]);
        Path output = Path.of(args[2]);
        try (var stream = Files.newOutputStream(output)) {
            verified.store(stream, "Authenticated core main version; generated by CI, not a user override");
        }
        try {
            resolve(output.getParent().getParent(), System.getenv(), false);
        } catch (IOException | RuntimeException error) {
            Files.deleteIfExists(output);
            throw error;
        }
    }
}
