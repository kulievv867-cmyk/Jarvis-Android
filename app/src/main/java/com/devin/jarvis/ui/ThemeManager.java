package com.devin.jarvis.ui;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

import com.devin.jarvis.R;
import com.devin.jarvis.core.Settings;

/**
 * Resolves the user-selected theme name to a concrete {@code R.style.Theme_Jarvis_*}
 * resource id, applies it to an Activity, and toggles the launcher icon
 * activity-aliases so the home-screen icon matches the chosen palette.
 *
 * <p>Activities should call {@link #apply(Activity, Settings)} BEFORE
 * {@code super.onCreate()} so the theme attributes are available when layouts
 * are inflated.
 */
public final class ThemeManager {

    /** Package-relative names for each launcher activity-alias. */
    private static final String ALIAS_BLUE     = "com.devin.jarvis.ui.LauncherBlue";
    private static final String ALIAS_ORANGE   = "com.devin.jarvis.ui.LauncherOrange";
    private static final String ALIAS_GREEN    = "com.devin.jarvis.ui.LauncherGreen";
    private static final String ALIAS_GRAPHITE = "com.devin.jarvis.ui.LauncherGraphite";
    private static final String ALIAS_LIGHT    = "com.devin.jarvis.ui.LauncherLight";

    private static final String[] ALL_ALIASES = {
            ALIAS_BLUE, ALIAS_ORANGE, ALIAS_GREEN, ALIAS_GRAPHITE, ALIAS_LIGHT
    };

    private ThemeManager() {}

    public static int themeRes(String key) {
        if (key == null) return R.style.Theme_Jarvis_Blue;
        switch (key) {
            case Settings.THEME_ORANGE:   return R.style.Theme_Jarvis_Orange;
            case Settings.THEME_GREEN:    return R.style.Theme_Jarvis_Green;
            case Settings.THEME_GRAPHITE: return R.style.Theme_Jarvis_Graphite;
            case Settings.THEME_LIGHT:    return R.style.Theme_Jarvis_LightWarm;
            case Settings.THEME_BLUE:
            default:                       return R.style.Theme_Jarvis_Blue;
        }
    }

    public static void apply(Activity a, Settings s) {
        a.setTheme(themeRes(s.theme()));
    }

    /**
     * Enable the launcher activity-alias that matches {@code themeKey} and
     * disable the other four. Safe to call from any thread; PackageManager
     * is synchronous and does not need {@code DONT_KILL_APP} kept across
     * launchers — but we set it anyway so the current activity isn't
     * destroyed mid-transition.
     */
    public static void applyLauncherIcon(Context ctx, String themeKey) {
        if (ctx == null) return;
        String wanted = aliasFor(themeKey);
        PackageManager pm = ctx.getPackageManager();
        String pkg = ctx.getPackageName();
        for (String alias : ALL_ALIASES) {
            int newState = alias.equals(wanted)
                    ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            ComponentName cn = new ComponentName(pkg, alias);
            try {
                int current = pm.getComponentEnabledSetting(cn);
                if (current == newState) continue;
                pm.setComponentEnabledSetting(cn, newState, PackageManager.DONT_KILL_APP);
            } catch (Throwable ignored) {
                // Some OEM ROMs throw SecurityException for non-launcher
                // components; we just skip in that case.
            }
        }
    }

    private static String aliasFor(String themeKey) {
        if (themeKey == null) return ALIAS_BLUE;
        switch (themeKey) {
            case Settings.THEME_ORANGE:   return ALIAS_ORANGE;
            case Settings.THEME_GREEN:    return ALIAS_GREEN;
            case Settings.THEME_GRAPHITE: return ALIAS_GRAPHITE;
            case Settings.THEME_LIGHT:    return ALIAS_LIGHT;
            case Settings.THEME_BLUE:
            default:                       return ALIAS_BLUE;
        }
    }
}
