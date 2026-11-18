package io.github.liangforstudy.entitytracker.search;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Several items in one search: "obby, echest" or "obby; echest" is searched as "obby" and
 * "echest", each on its own (aliases and typo tolerance per term), and the results combined.
 */
public final class Terms {
    private Terms() {}

    /** The lowercase query split on commas and semicolons; a plain query gives itself. */
    public static List<String> of(String query) {
        Set<String> out = new LinkedHashSet<>();
        for (String part : query.split("[,;]")) {
            String t = part.trim();
            if (t.startsWith("@")) t = t.substring(1);
            if (!t.isEmpty()) out.add(t);
        }
        return out.isEmpty() ? List.of(query) : new ArrayList<>(out);
    }
}
