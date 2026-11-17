import com.weartak.version.WearVersion;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public final class WearVersionTest {
    private static final String CORE_SHA = "1".repeat(40);
    private static final String PLUGIN_SHA = "abcdef01" + "2".repeat(32);

    private static void equal(Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected " + expected + ", got " + actual);
        }
    }

    private interface CheckedAction {
        void run() throws Exception;
    }

    private static void rejects(CheckedAction action) throws Exception {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Expected invalid version inputs to fail closed");
    }

    private static Map<String, String> pipeline() {
        Map<String, String> env = new HashMap<>();
        env.put("CI", "true");
        env.put("CI_PIPELINE_IID", "42");
        env.put("CI_COMMIT_SHA", PLUGIN_SHA);
        return env;
    }

    public static void main(String[] args) throws Exception {
        WearVersion version = WearVersion.calculate("5.8.4", "584", "42", PLUGIN_SHA);
        equal("5.8.4-p42+abcdef01", version.name);
        equal(5840042, version.code);
        equal(5840001, WearVersion.calculate("5.8.4", "584", "1", PLUGIN_SHA).code);
        equal(5849999, WearVersion.calculate("5.8.4", "584", "9999", PLUGIN_SHA).code);
        equal(2099999999, WearVersion.calculate("9.0.0", "209999", "9999", PLUGIN_SHA).code);
        for (String iid : new String[] {"0", "-1", "10000", "01", "x", "9999999999999999999"}) {
            rejects(() -> WearVersion.calculate("5.8.4", "584", iid, PLUGIN_SHA));
        }
        for (String base : new String[] {"0", "-584", "0584", "210000", "9999999999999999999"}) {
            rejects(() -> WearVersion.calculate("5.8.4", base, "1", PLUGIN_SHA));
        }
        rejects(() -> WearVersion.calculate("5.8.4-p42", "584", "1", PLUGIN_SHA));
        rejects(() -> WearVersion.calculate("5.8.4", "584", "1", "abcdef01"));
        Path root = Files.createTempDirectory("ditto-version-test-");
        Path directory = root.resolve(".gradle");
        Path canonical = directory.resolve("wear-version.properties");
        try {
            equal("5.8.4-dev", WearVersion.resolve(root, Map.of(), false).name);
            rejects(() -> WearVersion.resolve(root, Map.of(), true));
            rejects(() -> WearVersion.resolve(root, pipeline(), false));
            Map<String, String> forwarded = pipeline();
            forwarded.put("CI_PIPELINE_SOURCE", "pipeline");
            forwarded.put("WEARTAK_VERSION_SOURCE_PROJECT_ID", "6739");
            forwarded.put("WEARTAK_VERSION_SOURCE_SHA", CORE_SHA);
            forwarded.put("WEARTAK_VERSION_NAME", "5.8.4");
            forwarded.put("WEARTAK_BASE_VERSION_CODE", "584");
            rejects(() -> WearVersion.resolve(root, forwarded, true));
            Files.createDirectory(directory);
            String contents = "versionName=5.8.4\nbaseVersionCode=584\n"
                    + "sourceProjectId=6739\nsourceSha=" + CORE_SHA + "\n";
            Files.writeString(canonical, contents);
            equal("5.8.4-p42+abcdef01", WearVersion.resolve(root, pipeline(), true).name);
            equal(5840042, WearVersion.resolve(root, forwarded, true).code);
            for (String key : new String[] {"WEARTAK_VERSION_SOURCE_PROJECT_ID",
                    "WEARTAK_VERSION_SOURCE_SHA", "WEARTAK_VERSION_NAME", "WEARTAK_BASE_VERSION_CODE"}) {
                Map<String, String> wrong = new HashMap<>(forwarded);
                wrong.put(key, "untrusted");
                rejects(() -> WearVersion.resolve(root, wrong, true));
                wrong.remove(key);
                rejects(() -> WearVersion.resolve(root, wrong, true));
            }
            Map<String, String> missing = pipeline();
            missing.put("CI_PIPELINE_SOURCE", "pipeline");
            rejects(() -> WearVersion.resolve(root, missing, true));
            rejects(() -> WearVersion.resolve(root, Map.of(), true));
            Files.writeString(canonical, contents.replace("6739", "6740"));
            rejects(() -> WearVersion.resolve(root, forwarded, true));
            Files.writeString(canonical, contents.replace(CORE_SHA, "main"));
            rejects(() -> WearVersion.resolve(root, forwarded, true));
        } finally {
            Files.deleteIfExists(canonical);
            Files.deleteIfExists(directory);
            Files.delete(root);
        }
        System.out.println("WearVersion tests passed");
    }
}
