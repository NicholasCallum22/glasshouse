package com.gathin.tvhome;

import android.content.Context;

/**
 * The colour Home's backdrop is washed with right now (glow_style wash), already softened to its pastel blush.
 * Home notes it whenever the focused app's colour changes; the Quick panel and the volume overlay take it, so they
 * match the room even when shown over another app. 0 until Home has shown a wash.
 */
final class HomeWash {
    private static final String PREFS = "home_wash", KEY = "blush";

    private HomeWash() {
    }

    static void save(Context ctx, int blush) {
        if (get(ctx) != blush) ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY, blush).apply();
    }

    static int get(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, 0);
    }
}
