package com.devin.jarvis.commands;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Launch / "close" apps by spoken name.
 *
 * The matcher works against every installed package that exposes a launch
 * intent (not just the ones declared in CATEGORY_LAUNCHER) and uses a Russian
 * → Latin transliteration plus Levenshtein fuzzy matching so it tolerates
 * Vosk-style speech-recognition errors (e.g. "тэлэгрома" → "telegram").
 */
public class AppLauncher {

    public static class Match {
        public final String label;
        public final String pkg;
        public Match(String l, String p) { this.label = l; this.pkg = p; }
    }

    private static class Candidate {
        final String label;
        final String pkg;
        final String labelNorm;
        final String labelLat;
        final String pkgNorm;
        Candidate(String label, String pkg) {
            this.label = label;
            this.pkg = pkg;
            this.labelNorm = normalize(label);
            this.labelLat = transliterate(this.labelNorm);
            this.pkgNorm = normalize(pkg.replace('.', ' '));
        }
    }

    private final Context ctx;

    public AppLauncher(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    private List<Candidate> collectCandidates() {
        PackageManager pm = ctx.getPackageManager();
        Map<String, Candidate> byPkg = new HashMap<>();

        // 1) Apps with a LAUNCHER activity — gives proper labels.
        Intent main = new Intent(Intent.ACTION_MAIN, null);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        try {
            List<ResolveInfo> apps = pm.queryIntentActivities(main, 0);
            for (ResolveInfo ri : apps) {
                String pkg = ri.activityInfo.applicationInfo.packageName;
                String label = "";
                try {
                    CharSequence cs = ri.loadLabel(pm);
                    if (cs != null) label = cs.toString();
                } catch (Exception ignored) {}
                if (label.isEmpty()) label = pkg;
                byPkg.put(pkg, new Candidate(label, pkg));
            }
        } catch (Exception ignored) {}

        // 2) Every installed application that has a launch intent — picks up
        // apps that the user can actually open even if they aren't in the
        // launcher (e.g. settings sub-apps).
        try {
            List<ApplicationInfo> all = pm.getInstalledApplications(0);
            for (ApplicationInfo ai : all) {
                if (byPkg.containsKey(ai.packageName)) continue;
                if (pm.getLaunchIntentForPackage(ai.packageName) == null) continue;
                String label = ai.packageName;
                try {
                    CharSequence cs = pm.getApplicationLabel(ai);
                    if (cs != null) label = cs.toString();
                } catch (Exception ignored) {}
                byPkg.put(ai.packageName, new Candidate(label, ai.packageName));
            }
        } catch (Exception ignored) {}

        return new ArrayList<>(byPkg.values());
    }

    /** Returns the best match for a spoken name, or null. */
    public Match findApp(String spoken) {
        if (spoken == null) return null;
        String needle = normalize(spoken);
        if (needle.isEmpty()) return null;

        // Variants we score each candidate against. We keep multiple to stay
        // tolerant of Russian → English brand-name confusion.
        String needleLat = transliterate(needle);
        String needleAlt = phoneticAliases(needleLat);
        // Also strip trivial stopwords like "приложение" / "программа" if they
        // sneaked in.
        String needleClean = stripStopwords(needleAlt);

        HashSet<String> variants = new HashSet<>();
        variants.add(needle);
        variants.add(needleLat);
        variants.add(needleAlt);
        variants.add(needleClean);

        Match best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Candidate c : collectCandidates()) {
            int score = 0;
            for (String v : variants) {
                if (v == null || v.isEmpty()) continue;
                score = Math.max(score, score(c.labelNorm, v));
                score = Math.max(score, score(c.labelLat, v));
                score = Math.max(score, score(c.pkgNorm, v));
                // Levenshtein-based similarity for noisy matches.
                score = Math.max(score, fuzzy(c.labelNorm, v));
                score = Math.max(score, fuzzy(c.labelLat, v));
                score = Math.max(score, fuzzy(c.pkgNorm, v));
            }
            if (score > bestScore) {
                bestScore = score;
                best = new Match(c.label, c.pkg);
            }
        }
        if (bestScore < 25) return null;
        return best;
    }

    private static String stripStopwords(String s) {
        if (s == null) return "";
        return s.replaceAll("\\b(prilozhenie|prilozheniya|prilozhenia|programma|programmu|app)\\b", " ")
                .replaceAll("\\s+", " ").trim();
    }

    private static final Map<Character, String> CYR2LAT = new HashMap<>();
    static {
        String[][] m = {
                {"а","a"},{"б","b"},{"в","v"},{"г","g"},{"д","d"},{"е","e"},
                {"ё","yo"},{"ж","zh"},{"з","z"},{"и","i"},{"й","y"},{"к","k"},
                {"л","l"},{"м","m"},{"н","n"},{"о","o"},{"п","p"},{"р","r"},
                {"с","s"},{"т","t"},{"у","u"},{"ф","f"},{"х","h"},{"ц","ts"},
                {"ч","ch"},{"ш","sh"},{"щ","sch"},{"ъ",""},{"ы","y"},{"ь",""},
                {"э","e"},{"ю","yu"},{"я","ya"}
        };
        for (String[] p : m) CYR2LAT.put(p[0].charAt(0), p[1]);
    }

    static String transliterate(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char c = Character.toLowerCase(s.charAt(i));
            String r = CYR2LAT.get(c);
            if (r != null) sb.append(r);
            else sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Common ad-hoc aliases for app names — Russians often pronounce English
     * brand names in ways that don't transliterate one-to-one.
     */
    private static String phoneticAliases(String s) {
        if (s == null) return "";
        String t = s;
        t = t.replace("yutub", "youtube");
        t = t.replace("ytub", "youtube");
        t = t.replace("yu tub", "youtube");
        t = t.replace("vatsap", "whatsapp");
        t = t.replace("vats ap", "whatsapp");
        t = t.replace("yandeks", "yandex");
        t = t.replace("vkontakte", "vk");
        t = t.replace("v kontakte", "vk");
        t = t.replace("odnoklassniki", "ok ru odnoklassniki");
        t = t.replace("vayber", "viber");
        t = t.replace("dzen", "zen dzen");
        t = t.replace("plei market", "play google");
        t = t.replace("plei marketa", "play google");
        t = t.replace("nastroyki", "settings");
        t = t.replace("kalkulyator", "calculator");
        t = t.replace("kamera", "camera");
        t = t.replace("galereya", "gallery photos");
        t = t.replace("foto", "photos gallery");
        t = t.replace("muzyka", "music");
        t = t.replace("muzyku", "music");
        t = t.replace("kalendar", "calendar");
        t = t.replace("zametki", "notes keep");
        t = t.replace("karta", "maps");
        t = t.replace("karty", "maps");
        t = t.replace("brauzer", "browser chrome");
        t = t.replace("hrom", "chrome");
        t = t.replace("dzhi meyl", "gmail");
        t = t.replace("dzh meyl", "gmail");
        t = t.replace("gmeyl", "gmail");
        t = t.replace("pochtu", "mail");
        t = t.replace("pochta", "mail");
        t = t.replace("tinkoff", "t bank tinkoff");
        t = t.replace("sberbank", "sberbank sber");
        t = t.replace("alfabank", "alfa bank alfa");
        t = t.replace("uber", "uber");
        t = t.replace("yandeks taksi", "yandex go");
        t = t.replace("yandeks navigator", "yandex navi");
        t = t.replace("dzhitkhab", "github");
        t = t.replace("redzhi", "reddit");
        t = t.replace("tiktok", "tiktok");
        t = t.replace("tik tok", "tiktok");
        t = t.replace("diskord", "discord");
        t = t.replace("steym", "steam");
        t = t.replace("teleg", "telegram");
        return t;
    }

    public boolean open(Match m) {
        if (m == null) return false;
        Intent i = ctx.getPackageManager().getLaunchIntentForPackage(m.pkg);
        if (i == null) return false;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public void killBackground(Match m) {
        if (m == null) return;
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) am.killBackgroundProcesses(m.pkg);
        } catch (Exception ignored) {}
    }

    public void openAppInfo(Match m) {
        if (m == null) return;
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", m.pkg, null));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception ignored) {}
    }

    public List<Match> listAll() {
        List<Match> out = new ArrayList<>();
        for (Candidate c : collectCandidates()) out.add(new Match(c.label, c.pkg));
        return out;
    }

    private static String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase()
                .replaceAll("[^\\p{L}\\p{Nd}\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /** Substring/word-overlap scoring (0..100). */
    private static int score(String label, String needle) {
        if (label == null || needle == null || label.isEmpty() || needle.isEmpty()) return 0;
        if (label.equals(needle)) return 100;
        if (label.startsWith(needle)) return 85;
        if (label.contains(" " + needle) || label.endsWith(" " + needle)) return 75;
        if (label.contains(needle)) return 65;
        if (needle.contains(label) && label.length() >= 4) return 55;
        // Word overlap
        String[] lw = label.split(" ");
        String[] nw = needle.split(" ");
        int hits = 0;
        for (String n : nw) {
            if (n.length() < 2) continue;
            for (String l : lw) {
                if (l.length() < 2) continue;
                if (l.startsWith(n) || n.startsWith(l)) { hits++; break; }
            }
        }
        if (hits > 0) return 25 + hits * 12;
        return 0;
    }

    /**
     * Levenshtein-based similarity, returns 0..100. Helps when Vosk gives a
     * near-miss like "телеграма" vs "telegram" → after transliteration
     * "telegrama" vs "telegram" → distance 1, score ≈ 88.
     */
    private static int fuzzy(String label, String needle) {
        if (label == null || needle == null) return 0;
        if (label.isEmpty() || needle.isEmpty()) return 0;
        // Compare against the closest single word inside the label, plus the
        // whole label. This handles "Яндекс Карты" vs "карты".
        int best = scoreLev(label, needle);
        for (String w : label.split(" ")) {
            if (w.length() < 2) continue;
            best = Math.max(best, scoreLev(w, needle));
        }
        return best;
    }

    private static int scoreLev(String a, String b) {
        int d = levenshtein(a, b);
        int m = Math.max(a.length(), b.length());
        if (m == 0) return 0;
        // Map distance to a 0..100 score; only very close matches are useful.
        int sim = (int) Math.round((1.0 - (double) d / m) * 100);
        // Strict cutoff so unrelated short labels don't accidentally match.
        if (sim < 60) return 0;
        // Slight boost when one string is a prefix of the other.
        if (a.startsWith(b) || b.startsWith(a)) sim = Math.min(100, sim + 10);
        return sim;
    }

    private static int levenshtein(String a, String b) {
        int n = a.length();
        int m = b.length();
        if (n == 0) return m;
        if (m == 0) return n;
        int[] prev = new int[m + 1];
        int[] curr = new int[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = j;
        for (int i = 1; i <= n; i++) {
            curr[0] = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                int cost = (ca == b.charAt(j - 1)) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev; prev = curr; curr = t;
        }
        return prev[m];
    }
}
