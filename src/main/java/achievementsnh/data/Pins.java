package achievementsnh.data;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import achievementsnh.AchievementsNH;

/**
 * Pinned achievements: one statId per line in {@code config/achievementsnh-pinned.txt}, shared by all worlds and
 * servers on this client.
 */
public final class Pins {

    private static File file;

    private Pins() {}

    public static void init(File configDir) {
        file = new File(configDir, "achievementsnh-pinned.txt");
    }

    public static List<String> load() {
        if (file == null || !file.isFile()) return Collections.emptyList();
        try {
            List<String> out = new ArrayList<>();
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                String id = line.trim();
                if (!id.isEmpty() && !id.startsWith("#")) out.add(id);
            }
            return out;
        } catch (IOException e) {
            AchievementsNH.LOG.warn("Could not read {}", file, e);
            return Collections.emptyList();
        }
    }

    public static void save(List<String> ids) {
        if (file == null) return;
        List<String> lines = new ArrayList<>();
        lines.add("# Achievements NH: pinned achievements, one statId per line");
        lines.addAll(ids);
        try {
            Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            AchievementsNH.LOG.warn("Could not write {}", file, e);
        }
    }
}
