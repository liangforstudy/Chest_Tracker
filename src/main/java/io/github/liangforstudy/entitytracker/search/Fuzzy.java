package io.github.liangforstudy.entitytracker.search;

/**
 * Typo-tolerant matching, used only when a search finds nothing exactly ("wheet" -> wheat).
 * Every word of the query must appear in the text, or be within a small edit distance of one of
 * its words (or of a word's start, so "diamon" still finds diamond_sword).
 */
public final class Fuzzy {
    private Fuzzy() {}

    /** Both arguments lowercase. */
    public static boolean matches(String text, String query) {
        String[] words = text.split("[^\\p{L}\\p{N}]+");
        for (String q : query.split("[^\\p{L}\\p{N}]+")) {
            if (q.isEmpty() || text.contains(q)) continue;
            int max = q.length() >= 7 ? 2 : q.length() >= 4 ? 1 : 0;
            if (max == 0 || !nearWord(words, q, max)) return false;
        }
        return true;
    }

    private static boolean nearWord(String[] words, String q, int max) {
        for (String w : words) {
            if (w.isEmpty()) continue;
            if (Math.abs(w.length() - q.length()) <= max && distance(w, q, max) <= max) return true;
            if (w.length() > q.length() && distance(w.substring(0, q.length()), q, max) <= max) return true;
        }
        return false;
    }

    /** Edit distance counting a swap of two neighbouring letters as one edit; stops early past max. */
    static int distance(String a, String b, int max) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) d[i][0] = i;
        for (int j = 0; j <= b.length(); j++) d[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int rowMin = Integer.MAX_VALUE;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                int v = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    v = Math.min(v, d[i - 2][j - 2] + 1);
                }
                d[i][j] = v;
                rowMin = Math.min(rowMin, v);
            }
            if (rowMin > max) return max + 1;
        }
        return d[a.length()][b.length()];
    }
}
