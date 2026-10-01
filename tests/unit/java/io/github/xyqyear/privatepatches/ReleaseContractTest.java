package io.github.xyqyear.privatepatches;

import com.google.gson.JsonParser;
import io.github.xyqyear.privatepatches.patches.bluemap.BlueMapPatch;
import io.github.xyqyear.privatepatches.patches.playerretention.PlayerRetentionPatch;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class ReleaseContractTest {
    private final Path root = Path.of(System.getProperty("repositoryRoot"));

    @Test
    void featureIsOptInAndHasOneSwitch() throws Exception {
        for (var feature : new Class<?>[]{PlayerRetentionPatch.class, BlueMapPatch.class}) {
            var fields = feature.getFields();
            assertEquals(1, fields.length);
            assertEquals(boolean.class, fields[0].getType());
            assertFalse(fields[0].getBoolean(null));
        }
    }

    @Test
    void advertisedVersionsHavePinnedBuildProfiles() throws Exception {
        var metadata = JsonParser.parseString(Files.readString(root.resolve("src/main/resources/fabric.mod.json")))
                .getAsJsonObject();
        assertEquals("server", metadata.get("environment").getAsString());
        var versions = metadata.getAsJsonObject("depends").getAsJsonArray("minecraft");
        assertEquals(2, versions.size());
        for (var version : versions) {
            var profile = new Properties();
            try (var input = Files.newInputStream(root.resolve("versions/" + version.getAsString() + ".properties"))) {
                profile.load(input);
            }
            assertEquals(version.getAsString(), profile.getProperty("minecraft"));
            assertTrue(profile.getProperty("fabric_api").endsWith("+" + version.getAsString()));
            assertTrue(profile.getProperty("carpet").startsWith(version.getAsString() + "+"));
        }
    }

    @Test
    void bothLanguagesDescribeTheFeatureAndItsLimit() throws Exception {
        for (var language : new String[]{"en_us", "zh_cn"}) {
            var translations = JsonParser.parseString(Files.readString(root.resolve(
                    "src/main/resources/assets/privatepatches/lang/" + language + ".json"))).getAsJsonObject();
            for (var rule : new String[]{"playerRetentionMemoryLeakFix", "fixBlueMap"}) {
                for (var suffix : new String[]{"desc", "extra.0", "extra.1"}) {
                    assertFalse(translations.get("carpet.rule." + rule + "." + suffix)
                            .getAsString().isBlank());
                }
            }
        }
    }
}
