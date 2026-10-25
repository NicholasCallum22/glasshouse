package com.gathin.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * App tiles. The first {@link MainActivity#COLUMNS} positions are the pinned dock (favourites, fixed order,
 * then empty slots up to a full row, see {@link DockSlots}), then the module cards the owner chose to show
 * (by label), the rest is alphabetical.
 */
public class AppAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    interface TileListener {
        void onTileFocused(int position, View card);

        /** Called for a D-pad direction key on a tile. Return true when handled (focus stays put). */
        boolean onTileVerticalKey(int position, int keyCode);

        void onLibraryRequested();
        void onLibraryFocused();
        /** Hold OK on a tile: the activity shows the tile menu (move, remove from Home, app info). */
        void onTileLongPress(int position, View card);
        void onLibraryUp();
    }

    static final class Entry {
        static final int APP = 0, HOME_SETTINGS = 1, CARD = 2, EMPTY = 3;

        final int kind;
        final String label;
        final ComponentName component;
        final String pkg;
        final Drawable banner;
        final Drawable icon;
        /** Text under the tile when it is focused in the grid: the label, plus the caption for a card. */
        final String name;
        /** Ambient glow colour; 0 until sampled from the banner, or the card's accent from the start. */
        int ambient;
        /** uniform_tiles card fill, top and bottom colour; null until sampled from the icon. */
        int[] backdrop;
        /** glass_tiles card fill, top-left and bottom-right colour; null until sampled from the icon. */
        int[] glassFill;
        /** glass_tiles: what the tile shows (the mark, the glyph lifted off its plate, or the icon) and how; null until made. */
        Drawable glassIcon;
        int glassMode;

        Entry(String label, ComponentName component, Drawable banner, Drawable icon) {
            this(APP, label, component, banner, icon, null, 0);
        }

        Entry(int kind, String label, ComponentName component, Drawable banner, Drawable icon, String caption, int accent) {
            this.kind = kind;
            this.label = label;
            this.component = component;
            this.pkg = component.getPackageName();
            this.banner = banner;
            this.icon = icon;
            this.name = caption == null ? label : label + " \u00B7 " + caption;
            this.ambient = accent;
        }
    }

    static final float FOCUS_SCALE = 1.10f;
    /** Design size of a tile (dp); tiles shrink from here when the columns don't fit, see tileWidthPx. */
    static final float DESIGN_W_DP = 180f, DESIGN_H_DP = 101f;

    /**
     * Tile width for a grid of this inner width: the cell minus room for the focus zoom and the glass
     * highlight, capped at the design size so 4 columns on a 1080p TV look exactly as before.
     */
    static int tileWidthPx(int innerWidthPx, int columns, float density) {
        int design = Math.round(DESIGN_W_DP * density);
        if (innerWidthPx <= 0 || columns <= 0) return design;
        int cell = innerWidthPx / columns;
        int fit = (int) ((cell - 12 * density) / FOCUS_SCALE);
        return Math.max(Math.round(80 * density), Math.min(design, fit));
    }

    static int tileHeightPx(int tileWidthPx) {
        return Math.round(tileWidthPx * tileAspect());
    }

    /**
     * A tile's height over its width: a 16:9 banner, or with glass_tiles a taller slot, since the square card
     * takes the slot's height: the card comes out about GLASS_ASPECT of the slot's width instead of 56%.
     */
    static float tileAspect() {
        return squircleShare > 0f ? GLASS_ASPECT : DESIGN_H_DP / DESIGN_W_DP;
    }

    /** glass_tiles: the square card's side as a share of the slot's width. */
    static final float GLASS_ASPECT = 0.74f;

    /** Height of one grid item: the tile, the name label under it and the item's own padding. */
    static int itemHeightPx(int tileHeightPx, float density) {
        return itemHeightPx(tileHeightPx, true, density);
    }

    /** The same for either layout: the plain grid (dock_row false) uses the compact spacing. */
    static int itemHeightPx(int tileHeightPx, boolean dockRow, float density) {
        return tileHeightPx + Math.round((dockRow ? ITEM_EXTRA_DP : COMPACT_ITEM_EXTRA_DP) * density);
    }

    /**
     * dock_row false: 4dp padding above and below, a 4dp gap and a 20dp name label, and 16dp kept under the last
     * row. The plain grid fits more rows on the screen, and with item_app.xml's roomier spacing the tiles came
     * out small with wide gaps between them.
     */
    static final float COMPACT_ITEM_EXTRA_DP = 4 + 4 + 4 + 20;
    static final float COMPACT_BOTTOM_ROOM_DP = 16;

    /** item_app.xml: 8dp padding above and below, a 7dp gap and the 22dp name label under the tile. */
    static final float ITEM_EXTRA_DP = 45;
    /** DockDecoration's offsets above and below the dock row. */
    static final float DOCK_EXTRA_DP = 6 + 12;
    /** Room kept under the last row rows_below_dock asks for (MainActivity places the dock with the same). */
    static final float BOTTOM_ROOM_DP = 30;
    /** Room kept above the dock for the dock title (48sp hero name plus its 40dp gap) when dock_title is on. */
    static final float TITLE_ROOM_DP = 104;
    /** dock_row false: room above the first row for its zoom and glass (MainActivity places the grid with it). */
    static final float TOP_ROOM_DP = 12;

    /**
     * Tile width that fits the columns across and, with rows_below_dock > 0, the dock (with its title above it,
     * if shown) plus that many rows down the grid's height; the smaller wins. With rows_below_dock = 0, or before
     * the grid has a height, only the width counts, as before. Without this, 2 or 3 rows below the dock did not
     * fit: the dock was pushed to the top and its title drew over the clock. With dock_row false there is no band
     * or title to make room for: the first row plus rows_below_dock rows fit under a small top margin.
     */
    static int tileWidthPx(int innerWidthPx, int gridHeightPx, int columns, int rowsBelowDock, boolean dockTitle,
                           boolean dockRow, float density) {
        int w = tileWidthPx(innerWidthPx, columns, density);
        if (gridHeightPx <= 0 || rowsBelowDock <= 0) return w;
        float reserved = (dockRow ? DOCK_EXTRA_DP + BOTTOM_ROOM_DP + (dockTitle ? TITLE_ROOM_DP : 0)
                : TOP_ROOM_DP + COMPACT_BOTTOM_ROOM_DP) * density;
        float perItem = (gridHeightPx - reserved) / (rowsBelowDock + 1);
        float tileH = perItem - (dockRow ? ITEM_EXTRA_DP : COMPACT_ITEM_EXTRA_DP) * density;
        int byHeight = Math.round(tileH / tileAspect());
        // Shrink at most to MIN_HEIGHT_FIT of the width-only size. Below that the tiles look lost in their wide
        // columns (2 rows under a titled dock came out at 80dp); the rows that still don't fit run off the bottom
        // and scroll, and MainActivity keeps the title's room above the dock.
        return Math.max(Math.max(Math.round(80 * density), Math.round(w * MIN_HEIGHT_FIT)), Math.min(w, byHeight));
    }

    /** The smallest share of the width-only tile size that fitting rows_below_dock into the height may go to. */
    static final float MIN_HEIGHT_FIT = 0.85f;

    private final Context ctx;
    private final int rowsBelowDock;
    private final boolean dockTitle;
    /** The owner's saved order (reorder button, Order): key -> rank. Empty when there is none or it no longer applies. */
    private Map<String, Integer> savedRanks = Collections.emptyMap();
    /** Dock size recorded with the saved order (Order.docked), or -1: then dock_apps says which tiles are docked. */
    private int savedDocked = -1;
    /** Reorder mode: no empty dock slots, so every position holds a tile that can move. */
    private boolean reordering;
    private final TileListener listener;
    private final int dockSize;
    /** config dock_row: false makes the dock apps the first tiles of one plain grid (names shown, no empty slots). */
    private final boolean dockRow;
    /** config single_row: tiles sit in one sideways row, each as wide as a grid column would be. */
    private final boolean singleRow;
    /** config always_show_names: the alpha of an unfocused tile's name (0 hides it). */
    private final float restNameAlpha;
    /** cinematic_home: only then does a launch record the last-opened app the shelf uses. */
    private final boolean cinematic;
    /** Dock order (config.xml dock_apps, or a theme's config.json). */
    private final List<String> favourites;
    /** Grid apps fill the dock slots dock_apps leaves free (config.xml, fill_dock). */
    private final boolean fillDock;
    /** Dock apps found at the last reload, and the empty slots after them. */
    private int docked, emptySlots;
    /** Never shown as tiles (res/values/config.xml, hidden_apps). */
    private final Set<String> hidden;
    /** Apps pre-seeded into the App library's "added" set (config.xml library_apps); empty by default. */
    private final Set<String> libraryPreload;
    private final boolean animations;
    private final boolean advancedEffects;
    private final boolean showLibrary;
    /** Packages with a leanback entry at the last reload(): their tiles launch with the leanback category. */
    private Set<String> leanbackPackages = new HashSet<>();
    private final boolean showHomeSettings;
    /** Calmer motion for weak GPUs (config.xml, reduced_motion): no overshoot, shorter fades. */
    private final boolean reducedMotion;
    /** The focus zoom may overshoot (config.xml, focus_overshoot). */
    private final boolean focusOvershoot;
    /** Package to the action its tile sends instead of the start screen (config.xml, launch_actions). */
    private final Map<String, String> launchActions;
    /** Hold OK opens the activity's tile menu (config.xml, tile_menu); false opens App info, as before. */
    private final boolean tileMenu;
    /** Tile names and bundled banners that replace the app's own (config.xml, app_labels and app_banners). */
    private final Map<String, String> appLabels, appBanners;
    /** Every app tile is its icon on a card tinted from that icon, never its banner (config.xml, uniform_tiles). */
    private final boolean uniformTiles;
    /** Square, rounded dark-glass tiles showing the app's own icon (theme key glass_tiles). */
    private final boolean glassTiles;
    /** Note every app Home opens, for the Home center's Continue row (theme key home_center). */
    private final boolean recordOpened;
    /** glass_tiles in one colour (theme key icon_tint); 0 keeps the apps' own colours. */
    private final int iconTint;
    private final List<Entry> items = new ArrayList<>();

    AppAdapter(Context ctx, TileListener listener, int dockSize, Config cfg) {
        this.ctx = ctx;
        this.listener = listener;
        this.dockSize = dockSize;
        this.favourites = cfg.dockApps;
        this.fillDock = cfg.fillDock;
        this.hidden = new HashSet<>(cfg.hiddenApps);
        this.libraryPreload = new HashSet<>(cfg.libraryApps);
        this.animations = cfg.animations;
        this.advancedEffects = cfg.animations && cfg.advancedEffects;
        this.showHomeSettings = cfg.showHomeSettingsShortcut;
        this.showLibrary = LibraryApps.showPill(cfg.appLibrary, cfg.libraryButton, cfg.showLibraryPill);
        this.reducedMotion = cfg.reducedMotion;
        this.focusOvershoot = cfg.focusOvershoot;
        this.rowsBelowDock = cfg.rowsBelowDock;
        this.dockTitle = cfg.dockTitle;
        this.dockRow = cfg.dockRow;
        this.singleRow = cfg.singleRow;
        this.restNameAlpha = cfg.alwaysShowNames ? 0.75f : 0f;
        this.cinematic = cfg.cinematicHome;
        this.launchActions = cfg.launchActions;
        this.tileMenu = cfg.tileMenu;
        this.appLabels = cfg.appLabels;
        this.appBanners = cfg.appBanners;
        this.uniformTiles = cfg.uniformTiles;
        this.glassTiles = cfg.glassTiles;
        this.recordOpened = cfg.homeCenter;
        this.iconTint = cfg.iconTint;
        parallax = cfg.parallax && cfg.glassTiles && cfg.animations && cfg.advancedEffects && !cfg.reducedMotion;
        // The tile corners, the focus highlight and the dock band all take their radius from cornerRadius().
        squircleShare = cfg.glassTiles ? SQUIRCLE_SHARE : 0f;
        // Tiles keep their identity across reload(), so a focused tile stays on its app instead of
        // its view being rebound to whatever app now sits in that slot.
        setHasStableIds(true);
    }

    /**
     * Rebuilds the tiles. A shown module card stands in for every tile of its package (same component,
     * so focus follows it); hidden_apps wins over a card, and a card's package in dock_apps takes that slot.
     */
    void reload(List<Cards.Card> cards, List<ResolveInfo> leanback) {
        PackageManager pm = ctx.getPackageManager();
        // The LEANBACK query is shared with the module-card check (MainActivity); copy it before adding
        // library apps so the caller's list stays as it was.
        List<ResolveInfo> found = new ArrayList<>(leanback);
        Set<String> tvPackages = new HashSet<>();
        for (ResolveInfo ri : found) if (ri.activityInfo != null) tvPackages.add(ri.activityInfo.packageName);
        leanbackPackages = tvPackages;
        // Copy before merging: LibraryApps.load() may return an immutable empty set (#419).
        Set<String> added = new HashSet<>(LibraryApps.load(ctx));
        added.addAll(libraryPreload);
        Set<String> removed = LibraryApps.loadRemoved(ctx);
        if (!added.isEmpty()) {
            Set<String> seen = new HashSet<>(tvPackages);
            for (ResolveInfo ri : pm.queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
                if (ri.activityInfo != null && added.contains(ri.activityInfo.packageName)
                        && seen.add(ri.activityInfo.packageName)) found.add(ri);
            }
        }
        Map<String, Cards.Card> cardByPkg = new HashMap<>();
        for (Cards.Card c : cards) cardByPkg.put(c.pkg, c);
        List<Entry> fresh = new ArrayList<>();
        // Some system apps declare two leanback entry points under one name (Sony's Timers & Clock on the
        // BRAVIA VU31), which would show as two identical tiles: keep the first per package and label.
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || !ri.activityInfo.exported
                    || !LibraryApps.visible(tvPackages.contains(pkg), hidden.contains(pkg), added.contains(pkg), removed.contains(pkg))) continue;
            ComponentName component = new ComponentName(pkg, ri.activityInfo.name);
            Cards.Card card = cardByPkg.get(pkg);
            if (card != null && !card.component.equals(component)) continue;   // the card stands in for this tile
            // Same cleaner as the card prompt: an app label with a bidi override or line separator must not
            // redraw the tile name as something else (#117).
            String label = card != null ? card.label : Cards.cleanLabel(
                    appLabels.containsKey(pkg) ? appLabels.get(pkg) : String.valueOf(ri.loadLabel(pm)), pkg);
            if (!seen.add(pkg + '\n' + label)) continue;
            Drawable banner = null;
            // uniform_tiles draws the icon, so an app's banner is never loaded. A module card keeps its banner.
            if (!((uniformTiles || glassTiles) && card == null)) {
                banner = bundledBanner(appBanners.get(pkg));
                if (banner == null) banner = loadBanner(pm, ri);
            }
            // The icon is only drawn (and sampled for the glow) when there is no banner, so a tile with a
            // banner skips loading and decoding it.
            Drawable icon = banner == null ? ri.loadIcon(pm) : null;
            if (glassTiles && icon != null && !(icon instanceof AdaptiveIconDrawable)) {
                // A TV activity often carries an old square bitmap while the app itself has an adaptive icon,
                // whose mark (or monochrome layer) sits on the glass far better.
                Drawable app = ri.activityInfo.applicationInfo.loadIcon(pm);
                if (app instanceof AdaptiveIconDrawable) icon = app;
            }
            if (card != null) {
                fresh.add(new Entry(Entry.CARD, label, component, banner, icon, card.caption, card.accent));
            } else {
                fresh.add(new Entry(label, component, banner, icon));
            }
        }
        Collections.sort(fresh, (a, b) -> {
            // The owner's own order first; tiles it does not know (new installs) follow by the rule below.
            int saved = Order.compare(savedRanks, a.component.flattenToShortString(), b.component.flattenToShortString());
            if (saved != 0) return saved;
            int fa = favourites.indexOf(a.pkg), fb = favourites.indexOf(b.pkg);
            if (fa >= 0 || fb >= 0) {
                if (fa < 0) return 1;
                if (fb < 0) return -1;
                return Integer.compare(fa, fb);
            }
            // Cards come right after the configured dock apps, before the alphabetical grid.
            boolean ca = a.kind == Entry.CARD, cb = b.kind == Entry.CARD;
            if (ca != cb) return ca ? -1 : 1;
            return a.label.compareToIgnoreCase(b.label);
        });
        // Retain the optional Default-home tile until dependent tile-ordering work is merged.
        if (showHomeSettings
                && pm.resolveActivity(new Intent(Settings.ACTION_HOME_SETTINGS), 0) != null) {
            fresh.add(new Entry(Entry.HOME_SETTINGS, ctx.getString(R.string.home_settings),
                    new ComponentName(ctx, HomeSettingsActivity.class), null,
                    ctx.getDrawable(R.drawable.ic_home_settings), null, 0));
        }
        docked = 0;
        while (docked < fresh.size() && isDocked(fresh.get(docked))) docked++;
        emptySlots = dockRow && !reordering ? DockSlots.empty(docked, dockSize, fresh.size(), fillDock) : 0;
        for (int i = 0; i < emptySlots; i++) {
            fresh.add(docked + i, new Entry(Entry.EMPTY, "", new ComponentName(ctx, "empty-dock-slot-" + i),
                    null, null, null, 0));
        }
        items.clear();
        items.addAll(fresh);
        notifyDataSetChanged();
    }

    /** A banner_* drawable bundled with the launcher, or null for none or a name that is not one. */
    private Drawable bundledBanner(String name) {
        if (name == null || !name.startsWith("banner_")) return null;
        int id = ctx.getResources().getIdentifier(name, "drawable", ctx.getPackageName());
        return id != 0 ? ctx.getDrawable(id) : null;
    }

    private Drawable loadBanner(PackageManager pm, ResolveInfo ri) {
        int id = ri.activityInfo.banner;
        if (id == 0) id = ri.activityInfo.applicationInfo.banner;
        Drawable banner = null;
        if (id != 0) {
            try {
                Resources resources = pm.getResourcesForApplication(ri.activityInfo.applicationInfo);
                // Keep at least xhdpi detail rather than downsampling artwork before the focus zoom.
                int density = Math.max(DisplayMetrics.DENSITY_XHIGH, ctx.getResources().getDisplayMetrics().densityDpi);
                banner = resources.getDrawableForDensity(id, density, null);
            } catch (PackageManager.NameNotFoundException | Resources.NotFoundException ignored) {
            }
        }
        if (banner == null) banner = ri.activityInfo.loadBanner(pm);
        // A square "banner" counts as none: the tile shows the app's icon and name instead of a blur.
        return banner != null && isBannerShaped(banner.getIntrinsicWidth(), banner.getIntrinsicHeight()) ? banner : null;
    }

    /**
     * Some apps declare a small square image as their banner (Rockchip's Media Center ships a 64 px one). Cropped
     * over a 16:9 tile it is a blur. A drawable with no intrinsic size (a colour, a shape) still fills the tile.
     */
    static boolean isBannerShaped(int width, int height) {
        return width <= 0 || height <= 0 || width >= height * 1.4f;
    }

    /** Lets go of every tile and its artwork (Home is in the background and memory is short). */
    void clear() {
        items.clear();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == 1) {
            View row = LayoutInflater.from(ctx).inflate(R.layout.row_app_library, parent, false);
            View button = row.findViewById(R.id.btnLibrary);
            button.setOnClickListener(v -> listener.onLibraryRequested());
            button.setOnFocusChangeListener((v, focused) -> { if (focused) listener.onLibraryFocused(); });
            button.setOnKeyListener((v, key, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (key == KeyEvent.KEYCODE_DPAD_UP) listener.onLibraryUp();
                return key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN
                        || key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT;
            });
            return new RecyclerView.ViewHolder(row) { };
        }
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        float density = parent.getResources().getDisplayMetrics().density;
        int width = parent.getWidth() > 0 ? parent.getWidth() : parent.getMeasuredWidth();
        int inner = width - parent.getPaddingLeft() - parent.getPaddingRight();
        int w = tileWidthPx(inner, parent.getHeight(), dockSize, rowsBelowDock, dockTitle, dockRow, density), h = tileHeightPx(w);
        View card = v.findViewById(R.id.card);
        ViewGroup.LayoutParams cardLp = card.getLayoutParams();
        // glass_tiles: a square card centred in the slot, so rows, the dock band and spacing keep their sizes.
        cardLp.width = glassTiles ? h : w;
        cardLp.height = h;
        card.setLayoutParams(cardLp);
        View name = v.findViewById(R.id.name);
        ViewGroup.MarginLayoutParams nameLp = (ViewGroup.MarginLayoutParams) name.getLayoutParams();
        nameLp.width = w;
        nameLp.topMargin = h + Math.round((dockRow ? 7 : 4) * density);
        if (!dockRow) {
            // Compact spacing (COMPACT_ITEM_EXTRA_DP): 4dp item padding and a 20dp name instead of 8dp and 22dp.
            nameLp.height = Math.round(20 * density);
            int pad = Math.round(4 * density);
            v.setPadding(v.getPaddingLeft(), pad, v.getPaddingRight(), pad);
        }
        name.setLayoutParams(nameLp);
        // A sideways row would stretch a match_parent item across the whole screen: give it a grid column's width.
        if (singleRow) v.getLayoutParams().width = inner / dockSize;
        if (glassTiles) {
            // Glass tile: the icon view nearly fills the card. An adaptive icon's foreground layer is drawn on
            // a 108 dp canvas whose mark sits in the middle 72 dp, so the mark ends up about 60% of the card; a
            // plain icon is shrunk to the same size in onBindViewHolder and its corners rounded here.
            ImageView icon = v.findViewById(R.id.icon);
            FrameLayout.LayoutParams iconLp = (FrameLayout.LayoutParams) icon.getLayoutParams();
            iconLp.width = iconLp.height = Math.round(h * GLASS_ICON_SHARE);
            iconLp.bottomMargin = 0;
            icon.setLayoutParams(iconLp);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), view.getWidth() * 0.24f);
                }
            });
        } else if (uniformTiles) {
            // tvOS-style tile: a large centred mark, no caption inside the card.
            View icon = v.findViewById(R.id.icon);
            FrameLayout.LayoutParams iconLp = (FrameLayout.LayoutParams) icon.getLayoutParams();
            iconLp.width = iconLp.height = Math.round(h * ICON_SHARE);
            iconLp.bottomMargin = 0;
            icon.setLayoutParams(iconLp);
        }
        return new Holder(v, listener, animations, advancedEffects, reducedMotion, restNameAlpha, focusOvershoot);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (isLibraryAt(position)) return;
        Holder h = (Holder) holder;
        Entry e = items.get(position);
        if (e.kind == Entry.EMPTY) {
            // A free dock slot keeps its place in the row and the band, but shows nothing and takes no focus.
            h.card.setVisibility(View.INVISIBLE);
            h.card.setFocusable(false);
            h.name.setVisibility(View.INVISIBLE);
            h.banner.setImageDrawable(null);
            h.icon.setImageDrawable(null);
            h.card.setOnClickListener(null);
            h.card.setOnLongClickListener(null);
            return;
        }
        h.card.setVisibility(View.VISIBLE);
        h.card.setFocusable(true);
        h.name.setText(e.name);
        // The dock has no labels, unless always_show_names asks for them (DockDecoration then makes room in the band).
        // In a single row every tile is the dock's kind: the big title above names it.
        boolean dockKind = dockRow && (singleRow || position < dockSize);
        h.name.setVisibility(dockKind && restNameAlpha == 0f ? View.INVISIBLE : View.VISIBLE);
        boolean focused = h.card.isFocused();   // a rebind after reload() can hit the focused tile
        h.name.setAlpha(focused ? 1f : restNameAlpha);
        h.card.setScaleX(focused && animations ? focusScale() : 1f);
        h.card.setScaleY(focused && animations ? focusScale() : 1f);
        h.card.setTranslationY(focused && advancedEffects ? -4f * ctx.getResources().getDisplayMetrics().density : 0f);
        h.card.setTranslationZ(focused && advancedEffects ? 8f * ctx.getResources().getDisplayMetrics().density : 0f);
        h.banner.setScaleX(focused && advancedEffects ? 1.035f : 1f);
        h.banner.setScaleY(focused && advancedEffects ? 1.035f : 1f);
        // A fully opaque banner covers the whole card (centerCrop), so the card's own fill under it is drawn for
        // nothing on every frame; keep it under banners with transparent parts and under icon tiles.
        h.card.setBackground(e.banner != null && e.banner.getOpacity() == PixelFormat.OPAQUE ? null : h.cardBackground);
        if (glassTiles && e.banner == null) {
            if (e.glassIcon == null) prepareGlass(e);
            float d = ctx.getResources().getDisplayMetrics().density;
            GradientDrawable glass = new GradientDrawable(GradientDrawable.Orientation.TL_BR, e.glassFill);
            glass.setCornerRadius(cornerRadius(h.card.getLayoutParams().width, d));
            glass.setStroke(Math.max(1, Math.round(d)), 0x2EFFFFFF);
            h.card.setBackground(glass);
        } else if (uniformTiles && e.banner == null) {
            if (e.backdrop == null) e.backdrop = backdrop(e.icon, ambientColorAt(position));
            h.card.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, e.backdrop));
        }
        if (e.banner != null) {
            h.banner.setImageDrawable(e.banner);
            h.banner.setVisibility(View.VISIBLE);
            h.icon.setVisibility(View.GONE);
            h.label.setVisibility(View.GONE);
        } else {
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.GONE);
            if (glassTiles) {
                if (e.glassIcon == null) prepareGlass(e);
                new GlassArt(e.glassIcon, e.glassMode, e.glassFill).apply(h.icon, iconTint);
            } else {
                h.icon.setImageDrawable(e.icon);
            }
            h.icon.setVisibility(View.VISIBLE);
            h.label.setText(e.label);
            h.label.setVisibility(uniformTiles || glassTiles ? View.GONE : View.VISIBLE);
        }
        h.card.setOnClickListener(v -> launch(e));
        h.card.setOnLongClickListener(v -> {
            if (tileMenu) {
                int pos = h.getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) listener.onTileLongPress(pos, v);
                return true;
            }
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + e.pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ctx.startActivity(i);
            } catch (ActivityNotFoundException ignored) {
            }
            return true;
        });
    }

    /** Opens the tile's activity by explicit component, with no extras: a card can name nothing else. */
    private void launch(Entry e) {
        if (e.kind == Entry.HOME_SETTINGS) {
            ctx.startActivity(new Intent(ctx, HomeSettingsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        Intent i = new Intent(Intent.ACTION_MAIN)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        // TV apps launch with the same leanback intent as from the stock launcher or the remote, so an app
        // already running resumes instead of restarting; only phone apps added from the library use plain MAIN.
        if (leanbackPackages.contains(e.pkg)) i.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        String action = launchActions.get(e.pkg);
        if (action != null) {
            // NEW_TASK only: with RESET_TASK_IF_NEEDED, Android just brings a running task of the app to the front
            // ("Activity not started"), so the screen never opens while the app is up, e.g. MagentaTV's boot-time task.
            Intent byAction = new Intent(action).setPackage(e.pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (ctx.getPackageManager().resolveActivity(byAction, 0) != null) i = byAction;
        }
        try {
            ctx.startActivity(i);
            if (cinematic || recordOpened) LastOpened.record(ctx, e.pkg);
            if (advancedEffects && !reducedMotion && ctx instanceof Activity) {
                ((Activity) ctx).overridePendingTransition(Motion.soft ? R.anim.soft_open_enter : R.anim.app_open_enter,
                        Motion.soft ? R.anim.soft_open_exit : R.anim.app_open_exit);
            }
        } catch (Exception ex) {
            Toast.makeText(ctx, ctx.getString(R.string.cant_open, e.label), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public int getItemCount() {
        return items.size() + (showLibrary ? 1 : 0);
    }

    int appCount() { return items.size(); }

    boolean isLibraryAt(int position) { return showLibrary && position == items.size(); }

    @Override public int getItemViewType(int position) { return isLibraryAt(position) ? 1 : 0; }

    boolean containsPackage(String pkg) {
        for (Entry entry : items) if (entry.pkg.equals(pkg)) return true;
        return false;
    }

    @Override
    public long getItemId(int position) {
        return isLibraryAt(position) ? Long.MIN_VALUE : items.get(position).component.flattenToShortString().hashCode();
    }

    /** Uses this saved order and dock size from the next reload() (Order.ranks, Order.docked). */
    void setSavedOrder(Map<String, Integer> ranks, int docked) {
        savedRanks = ranks;
        savedDocked = docked;
    }

    /**
     * A tile in the dock: one of the first savedDocked tiles of the saved order when one was recorded, so an app
     * the owner moved into the dock stays there (#499); else a dock_apps entry.
     */
    private boolean isDocked(Entry e) {
        if (savedDocked < 0) return favourites.contains(e.pkg);
        Integer rank = savedRanks.get(e.component.flattenToShortString());
        return rank != null && rank < savedDocked;
    }

    /** Reorder mode on or off; takes effect at the next reload() (empty dock slots are left out while on). */
    void setReordering(boolean on) {
        reordering = on;
    }

    boolean hasEmptySlots() {
        return emptySlots > 0;
    }

    /** Tiles that can be moved: everything but the "Default home" tile, which always stays last. */
    int movableCount() {
        int n = items.size();
        return n > 0 && items.get(n - 1).kind == Entry.HOME_SETTINGS ? n - 1 : n;
    }

    /** Keys of the movable tiles in their current order, for Order (empty dock slots are not tiles). */
    List<String> movableKeys() {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < movableCount(); i++) {
            Entry e = items.get(i);
            if (e.kind != Entry.EMPTY) keys.add(e.component.flattenToShortString());
        }
        return keys;
    }

    /** Moves one tile; the others shift by one. Stable ids keep the moved tile's view (and its focus). */
    void move(int from, int to) {
        if (from == to || from < 0 || to < 0 || from >= movableCount() || to >= movableCount()) return;
        items.add(to, items.remove(from));
        notifyItemMoved(from, to);
        // The dock row hides names by position: rebind the tiles whose side of the dock edge changed.
        if (from < dockSize || to < dockSize) {
            int lo = Math.min(from, to), hi = Math.max(from, to);
            notifyItemRangeChanged(lo, hi - lo + 1);
        }
    }

    /** Stable key for the app at a position (its component), or null. */
    String keyAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).component.flattenToShortString() : null;
    }

    /** Position of the app with this key, or -1 when it is gone. */
    int positionOf(String key) {
        if (key == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).component.flattenToShortString().equals(key)) return i;
        }
        return -1;
    }

    /** Dock slots that hold an app: the whole dock unless some slots are empty. */
    int dockFilled() {
        return emptySlots > 0 ? docked : dockSize;
    }

    /** A position focus can land on: an empty dock slot gives the last dock app. */
    int focusablePosition(int position) {
        return DockSlots.focusable(position, docked, emptySlots);
    }

    String labelAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).label : "";
    }

    String packageAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).pkg : null;
    }

    /** The tile's artwork, its banner or else its icon, for the cinematic shelf's backdrop (HeroArt). */
    Drawable artworkAt(int position) {
        if (position < 0 || position >= items.size()) return null;
        Entry e = items.get(position);
        return e.banner != null ? e.banner : e.icon;
    }

    float focusScale() {
        return !animations ? 1f : Motion.soft ? 1.07f : advancedEffects ? 1.12f : 1.10f;
    }

    /** A vivid colour sampled from the app's banner (or icon), cached per entry; a card's accent when it set one. */
    int ambientColorAt(int position) {
        if (position < 0 || position >= items.size()) return 0xFF444444;
        Entry e = items.get(position);
        if (e.ambient == 0) {
            e.ambient = (uniformTiles || glassTiles) && e.banner == null ? dominantColor(e.icon) : vividColor(e.banner != null ? e.banner : e.icon);
        }
        return e.ambient;
    }

    /** Share of the tile height the icon takes in uniform_tiles mode (onCreateViewHolder). */
    private static final float ICON_SHARE = 0.62f;

    /**
     * The colour covering most of an icon, for a card that should read as that app: a small saturated badge
     * (a red "new" corner on a blue icon) must not win, as it can with {@link #vividColor}. Grey if none.
     */
    static int dominantColor(Drawable live) {
        if (live == null) return 0xFF6E6E73;
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        int n = 24;
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, n, n);
        d.draw(new Canvas(bmp));
        d.setBounds(saved);
        float[] weight = new float[12];
        float[][] sum = new float[12][3];
        float[] hsv = new float[3];
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int p = bmp.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                Color.colorToHSV(p, hsv);
                if (hsv[1] < 0.3f || hsv[2] < 0.25f) continue;   // white, grey and black carry no hue
                int bin = (int) (hsv[0] / 30f) % 12;
                float w = hsv[1] * hsv[2];
                weight[bin] += w;
                sum[bin][0] += Color.red(p) * w;
                sum[bin][1] += Color.green(p) * w;
                sum[bin][2] += Color.blue(p) * w;
            }
        }
        bmp.recycle();
        int best = 0;
        for (int i = 1; i < 12; i++) if (weight[i] > weight[best]) best = i;
        if (weight[best] < n * n * 0.03f) return 0xFF6E6E73;   // a monochrome icon, like a grey gear
        float w = weight[best];
        return Color.rgb((int) (sum[best][0] / w), (int) (sum[best][1] / w), (int) (sum[best][2] / w));
    }

    /**
     * The uniform tile's fill. Where the icon sits on a flat colour of its own (a white or coloured rounded
     * square), the card takes that colour, so the mark reads as filling the tile as on tvOS. Otherwise a
     * gradient of the icon's main colour, deepened so the mark stands out. The card clips the corners.
     */
    private static int[] backdrop(Drawable live, int fallback) {
        int[] plate = iconPlate(live);
        if (plate != null) return plate;
        float[] hsv = new float[3];
        Color.colorToHSV(fallback, hsv);
        // Yellow darkened turns olive: lean it to amber instead.
        if (hsv[0] > 42f && hsv[0] < 70f && hsv[1] > 0.3f) hsv[0] = 36f;
        hsv[2] = Math.min(hsv[2], 0.62f);
        int top = Color.HSVToColor(hsv);
        hsv[2] *= 0.6f;
        return new int[]{top, Color.HSVToColor(hsv)};
    }

    /**
     * The plate an icon's artwork sits on (a flat or vertically graded rounded square), carried out to the
     * card's top and bottom edges so the mark reads as filling the tile; null when the icon has no
     * opaque plate (a bare mark on transparency).
     */
    private static int[] iconPlate(Drawable live) {
        if (live == null) return null;
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        int n = 48;
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, n, n);
        d.draw(new Canvas(bmp));
        d.setBounds(saved);
        // Just inside a rounded square's corners; one side may go a step further in, past a corner badge.
        // An icon padded on both sides is a small plate in the middle, which no fill would meet.
        int a = plateColor(bmp, 7, n), b = plateColor(bmp, n - 8, n);
        int topRow = 7, bottomRow = n - 8;
        if (a == 0 && b != 0) a = plateColor(bmp, topRow = 10, n);
        else if (b == 0 && a != 0) b = plateColor(bmp, bottomRow = n - 11, n);
        bmp.recycle();
        if (a == 0 || b == 0) return null;
        // The sampled rows sit at these heights of the tile; extend the line through them to its edges. A row
        // taken further in sits on artwork-side plate, so its slope is less certain: cap how far it reaches.
        float margin = (1f - ICON_SHARE) / 2f;
        float yA = margin + ICON_SHARE * topRow / n, yB = margin + ICON_SHARE * bottomRow / n;
        float t0 = -yA / (yB - yA), t1 = (1f - yA) / (yB - yA);
        if (topRow != 7 || bottomRow != n - 8) {
            t0 = Math.max(-0.35f, t0);
            t1 = Math.min(1.35f, t1);
        }
        return new int[]{mix(a, b, t0), mix(a, b, t1)};
    }

    /** {@link #plateColor(int[])} of row y, between the same insets as the row's distance from the edge. */
    private static int plateColor(Bitmap bmp, int y, int n) {
        int inset = Math.min(y, n - 1 - y);
        int[] row = new int[n - 2 * inset];
        bmp.getPixels(row, 0, row.length, inset, y, row.length, 1);
        return plateColor(row);
    }

    /** The colour most of a row shares, ignoring artwork that crosses it; 0 if the row is not mostly plate. */
    private static int plateColor(int[] row) {
        int[] r = new int[row.length], g = new int[row.length], b = new int[row.length];
        int opaque = 0;
        for (int p : row) {
            if (Color.alpha(p) < 240) continue;
            r[opaque] = Color.red(p);
            g[opaque] = Color.green(p);
            b[opaque] = Color.blue(p);
            opaque++;
        }
        if (opaque < row.length * 0.9f) return 0;
        int mr = median(r, opaque), mg = median(g, opaque), mb = median(b, opaque);
        long sr = 0, sg = 0, sb = 0;
        int close = 0;
        for (int i = 0; i < opaque; i++) {
            if (Math.abs(r[i] - mr) + Math.abs(g[i] - mg) + Math.abs(b[i] - mb) >= 48) continue;
            sr += r[i];
            sg += g[i];
            sb += b[i];
            close++;
        }
        if (close < row.length * 0.7f) return 0;
        return Color.rgb((int) (sr / close), (int) (sg / close), (int) (sb / close));
    }

    private static int median(int[] values, int count) {
        int[] sorted = Arrays.copyOf(values, count);
        Arrays.sort(sorted);
        return sorted[count / 2];
    }

    /** a + (b - a) * t per channel, clamped; t outside 0..1 extrapolates. */
    private static int mix(int a, int b, float t) {
        return Color.rgb(clamp(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                clamp(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                clamp(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    private static int clamp(float v) {
        return Math.max(0, Math.min(255, Math.round(v)));
    }

    private static int vividColor(Drawable live) {
        if (live == null) return 0xFF444444;
        // Draw a private copy: changing the bounds of the drawable an ImageView shows would blank the tile.
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        int n = 12;
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, n, n);
        d.draw(c);
        d.setBounds(saved);
        int[] px = new int[n * n];
        bmp.getPixels(px, 0, n, 0, 0, n, n);
        bmp.recycle();
        float[] hsv = new float[3];
        int best = 0xFF444444;
        float bestScore = -1f;
        long r = 0, g = 0, b = 0;
        for (int p : px) {
            int pr = (p >> 16) & 0xFF, pg = (p >> 8) & 0xFF, pb = p & 0xFF;
            r += pr;
            g += pg;
            b += pb;
            int max = Math.max(pr, Math.max(pg, pb));
            int min = Math.min(pr, Math.min(pg, pb));
            float sat = max == 0 ? 0f : (max - min) / (float) max;
            float val = max / 255f;
            float score = sat * Math.min(1f, val * 1.4f);   // saturated and not too dark
            if (score > bestScore) {
                bestScore = score;
                best = p;
            }
        }
        if (bestScore < 0.25f) {   // nearly monochrome banner (e.g. white): use its average, lifted
            int avg = Color.rgb((int) (r / (n * n)), (int) (g / (n * n)), (int) (b / (n * n)));
            Color.colorToHSV(avg, hsv);
            hsv[2] = Math.max(hsv[2], 0.55f);
            return Color.HSVToColor(hsv);
        }
        Color.colorToHSV(best, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.1f);
        hsv[2] = Math.max(0.5f, hsv[2]);
        return Color.HSVToColor(hsv);
    }

    /** Corner radius for a tile of this width: 16dp at the design width, proportionally less when smaller. */
    static float cornerRadius(int tileWidthPx, float density) {
        if (squircleShare > 0f) return tileWidthPx * squircleShare;
        return 16f * density * Math.min(1f, tileWidthPx / (DESIGN_W_DP * density));
    }

    /** parallax: a focused glass tile arrives tilted towards the move and settles, with a sweep of light. */
    private static boolean parallax;
    /** The arrow key that last moved focus off a tile, for the direction of the tilt. */
    private static int lastMoveKey;

    /**
     * parallax: the tile arrives leaning a few degrees the way focus travelled and settles flat over about half a
     * second, while a soft band of light sweeps across it once, as tvOS tiles catch the light.
     */
    private static void arrive(View card, float density) {
        settle(card);
        float rx = 0f, ry = 0f;
        float lean = Motion.soft ? 0.5f : 1f;   // soft_motion: half the lean, a hint rather than a tilt
        switch (lastMoveKey) {
            case KeyEvent.KEYCODE_DPAD_RIGHT: ry = 8f * lean; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: ry = -8f * lean; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: rx = -7f * lean; break;
            case KeyEvent.KEYCODE_DPAD_UP: rx = 7f * lean; break;
            default: break;
        }
        card.setCameraDistance(10000 * density);
        card.setRotationX(rx);
        card.setRotationY(ry);
        ObjectAnimator tilt = ObjectAnimator.ofPropertyValuesHolder(card,
                PropertyValuesHolder.ofFloat(View.ROTATION_X, 0f), PropertyValuesHolder.ofFloat(View.ROTATION_Y, 0f));
        tilt.setDuration(Motion.ms(560, 720));
        tilt.setInterpolator(new DecelerateInterpolator(2.2f));
        tilt.start();
        card.setTag(R.id.card, tilt);

        int w = card.getWidth(), h = card.getHeight();
        if (w <= 0 || h <= 0) return;
        int band = Math.round(w * 0.55f);
        GradientDrawable glint = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0x00FFFFFF, Motion.soft ? 0x22FFFFFF : 0x38FFFFFF, 0x00FFFFFF});
        glint.setBounds(-band, 0, 0, h);
        card.getOverlay().add(glint);
        ValueAnimator sweep = ValueAnimator.ofFloat(0f, 1f);
        sweep.setDuration(Motion.ms(720, 1000));
        sweep.setStartDelay(90);
        sweep.setInterpolator(new DecelerateInterpolator(1.4f));
        sweep.addUpdateListener(a -> {
            int x = Math.round(-band + (w + band) * (float) a.getAnimatedValue());
            glint.setBounds(x, 0, x + band, h);
            card.invalidate();
        });
        sweep.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                card.getOverlay().remove(glint);
            }
        });
        sweep.start();
        card.setTag(R.id.label, sweep);
    }

    /** Stops a tilt and a sweep in progress and lays the tile flat. */
    private static void settle(View card) {
        Object tilt = card.getTag(R.id.card), sweep = card.getTag(R.id.label);
        if (tilt instanceof Animator) ((Animator) tilt).cancel();
        if (sweep instanceof Animator) ((Animator) sweep).cancel();
        card.setTag(R.id.card, null);
        card.setTag(R.id.label, null);
        card.getOverlay().clear();
        card.setRotationX(0f);
        card.setRotationY(0f);
    }

    /** Corner radius as a share of the tile's width while glass_tiles is on (set by the adapter); 0 otherwise. */
    private static float squircleShare;
    /** glass_tiles corners: close to the 30% of an iOS icon, a touch less so a TV tile still reads as square. */
    private static final float SQUIRCLE_SHARE = 0.27f;
    /** glass_tiles: the icon view's share of the card (an adaptive foreground layer, margins included). */
    static final float GLASS_ICON_SHARE = 0.92f;
    /** glass_tiles: the box a glyph lifted off its plate is fitted into, as a share of the card. */
    private static final float GLYPH_SHARE = 0.54f;
    /** glass_tiles: a picture icon (no plate to lift a glyph off) sits inset in the squircle, embossed. */
    private static final float FILL_SHARE = 0.66f;
    static final int GLASS_MARK = 0, GLASS_GLYPH = 1, GLASS_FILL = 2, GLASS_MONO = 3;

    /**
     * Works out what a glass tile shows: an adaptive icon's mark, else the icon's glyph lifted off a flat plate,
     * else the whole icon filling the tile; and the glass tint that goes with it.
     */
    private void prepareGlass(Entry e) {
        GlassArt art = glassArt(ctx.getResources(), e.icon, iconTint != 0);
        e.glassIcon = art.drawable;
        e.glassMode = art.mode;
        e.glassFill = art.fill;
    }

    /** What a glass tile shows for an icon, how it is sized, and the glass behind it (also the Home center's). */
    static final class GlassArt {
        final Drawable drawable;
        final int mode;
        final int[] fill;

        GlassArt(Drawable drawable, int mode, int[] fill) {
            this.drawable = drawable;
            this.mode = mode;
            this.fill = fill;
        }

        /**
         * The scale for an image view {@link #GLASS_ICON_SHARE} of the card's side. A lifted glyph is sized by its
         * shape: a square mark fills GLYPH_SHARE of the card, a wide wordmark (NETFLIX) gets more width, up to
         * 72%, so every glyph carries about the same weight.
         */
        float scale() {
            if (mode == GLASS_MARK || mode == GLASS_MONO) return 1f;
            if (mode != GLASS_GLYPH) return FILL_SHARE / GLASS_ICON_SHARE;
            int w = drawable == null ? 0 : drawable.getIntrinsicWidth(), h = drawable == null ? 0 : drawable.getIntrinsicHeight();
            if (w <= 0 || h <= 0) return GLYPH_SHARE / GLASS_ICON_SHARE;
            float aspect = w / (float) h;
            // FIT_CENTER makes the glyph's longer side the view's side; size that side by the glyph's shape.
            float longSide = aspect >= 1f ? Math.min(0.72f, GLYPH_SHARE * (float) Math.sqrt(aspect))
                    : Math.min(0.62f, GLYPH_SHARE / (float) Math.sqrt(aspect));
            return longSide / GLASS_ICON_SHARE;
        }

        /**
         * Shows the art in an image view {@link #GLASS_ICON_SHARE} of the card's side. A picture icon sits inset in
         * the squircle with a soft emboss (a light top edge, a faint shade below). With a tint, a glyph becomes a
         * flat silhouette in that colour; a mark and a picture keep their shading in it, like iOS's tinted icons.
         */
        void apply(ImageView v, int tint) {
            v.setImageDrawable(drawable);
            float k = scale();
            v.setScaleX(k);
            v.setScaleY(k);
            boolean embed = mode == GLASS_FILL;
            v.setOutlineProvider(ROUNDED_ICON);
            v.setClipToOutline(embed);
            v.setForeground(embed ? emboss(v.getLayoutParams().width) : null);
            if (tint == 0) v.clearColorFilter();
            else if (mode == GLASS_GLYPH || mode == GLASS_MONO) v.setColorFilter(new PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN));
            else v.setColorFilter(tintFilter(tint, mode == GLASS_MARK ? 0.55f : 0.30f));
        }
    }

    /** The inset picture icon's corners: about an iOS icon's, at the image view's size. */
    private static final ViewOutlineProvider ROUNDED_ICON = new ViewOutlineProvider() {
        @Override
        public void getOutline(View view, Outline outline) {
            outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), view.getWidth() * 0.24f);
        }
    };

    /** A flat emboss over an inset icon: lighter at the top, a faint shade at the bottom, and a thin light rim. */
    private static Drawable emboss(int sizePx) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x30FFFFFF, 0x00FFFFFF, 0x2E000000});
        g.setCornerRadius(Math.max(0, sizePx) * 0.24f);
        g.setStroke(1, 0x47FFFFFF);
        return g;
    }

    /**
     * The tint carrying the art's light and shade: each pixel becomes the tint colour at a brightness between
     * {@code floor} (black) and full (white), its alpha kept.
     */
    private static ColorMatrixColorFilter tintFilter(int tint, float floor) {
        float span = 1f - floor;
        float[] m = new float[20];
        int[] c = {Color.red(tint), Color.green(tint), Color.blue(tint)};
        for (int row = 0; row < 3; row++) {
            float t = c[row] / 255f;
            m[row * 5] = t * span * 0.299f;
            m[row * 5 + 1] = t * span * 0.587f;
            m[row * 5 + 2] = t * span * 0.114f;
            m[row * 5 + 4] = c[row] * floor;
        }
        m[18] = 1f;   // alpha unchanged
        return new ColorMatrixColorFilter(new ColorMatrix(m));
    }

    static GlassArt glassArt(Resources res, Drawable icon, boolean neutral) {
        // Tinted tiles take the app's own monochrome icon first (Android 13's themed icons), drawn like a mark.
        Drawable mono = neutral ? monochromeMark(icon) : null;
        if (mono != null) return new GlassArt(mono, GLASS_MONO, glassFill(0xFF6E6E73));
        Drawable mark = glassMark(icon);
        int[] plate = new int[1];
        Drawable glyph = mark == null ? plateGlyph(res, icon, plate) : null;
        // A coloured plate tints the glass (FanCode's orange behind its white letters); a white or black plate does
        // not, so the glyph's own colour does (Netflix's red).
        float[] hsv = new float[3];
        Color.colorToHSV(plate[0], hsv);
        boolean colouredPlate = glyph != null && hsv[1] > 0.35f && hsv[2] > 0.25f;
        // A tinted tile sits on neutral glass: the colour is the background's (glow_style wash), not the tile's.
        int[] fill = glassFill(neutral ? 0xFF6E6E73 : colouredPlate ? plate[0] : dominantColor(icon));
        if (mark != null) return new GlassArt(mark, GLASS_MARK, fill);
        if (glyph != null) return new GlassArt(glyph, GLASS_GLYPH, fill);
        return new GlassArt(icon, GLASS_FILL, fill);
    }

    /**
     * The artwork of an icon drawn on a flat plate (a white, orange or black rounded square), with the plate taken
     * away: pixels near the plate's colour become transparent, with a soft edge, and the rest is cropped tight. A
     * glyph too dark for the glass is lightened. Null when the icon has no flat plate (a photo, a gradient) or
     * nothing would be left. {@code plateOut[0]} receives the plate's colour.
     */
    private static Drawable plateGlyph(Resources res, Drawable live, int[] plateOut) {
        if (live == null) return null;
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        int n = 144;
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, n, n);
        d.draw(new Canvas(bmp));
        d.setBounds(saved);
        int a = plateColor(bmp, 18, n), b = plateColor(bmp, n - 19, n);
        if (a == 0 || b == 0 || colourDistance(a, b) > 36) {
            bmp.recycle();
            return null;   // no flat plate at the top and bottom: a photo, a gradient or a bare mark
        }
        int plate = mix(a, b, 0.5f);
        plateOut[0] = plate;
        int[] px = new int[n * n];
        bmp.getPixels(px, 0, n, 0, 0, n, n);
        bmp.recycle();
        int left = n, top = n, right = -1, bottom = -1, kept = 0;
        long lum = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int i = y * n + x, p = px[i];
                int alpha = Color.alpha(p);
                if (alpha < 160) {   // outside the plate, or its anti-aliased rim
                    px[i] = 0;
                    continue;
                }
                float keep = (colourDistance(p, plate) - 30f) / 46f;   // soft edge between 30 and 76
                if (keep <= 0f) {
                    px[i] = 0;
                    continue;
                }
                int out = Math.round(alpha * Math.min(1f, keep));
                if (keep < 1f) {
                    // An edge pixel is part glyph, part plate: take the plate's share out, or the glyph keeps a halo.
                    float k = Math.max(0.2f, keep);
                    p = Color.rgb(unmix(Color.red(p), Color.red(plate), k), unmix(Color.green(p), Color.green(plate), k),
                            unmix(Color.blue(p), Color.blue(plate), k));
                }
                px[i] = (out << 24) | (p & 0x00FFFFFF);
                if (out > 48) {
                    kept++;
                    lum += Color.red(p) * 3L + Color.green(p) * 6L + Color.blue(p);
                    if (x < left) left = x;
                    if (x > right) right = x;
                    if (y < top) top = y;
                    if (y > bottom) bottom = y;
                }
            }
        }
        // Nothing left, or almost everything left (the "plate" was artwork after all).
        if (kept < n * n * 0.012f || kept > n * n * 0.70f) return null;
        if (lum / (kept * 10L) < 70) {
            // A dark glyph (black letters on a white plate) would vanish on dark glass: draw it in a soft white.
            for (int i = 0; i < px.length; i++) if (px[i] != 0) px[i] = (px[i] & 0xFF000000) | 0x00EDEDEF;
        }
        int w = right - left + 1, h = bottom - top + 1;
        Bitmap glyph = Bitmap.createBitmap(px, top * n + left, n, w, h, Bitmap.Config.ARGB_8888);
        return new BitmapDrawable(res, glyph);
    }

    /** One channel of an edge pixel with {@code (1 - k)} of the plate's colour taken out. */
    private static int unmix(int pixel, int plate, float k) {
        return Math.max(0, Math.min(255, Math.round((pixel - plate * (1f - k)) / k)));
    }

    /** Distance between two colours in RGB, 0 to about 441. */
    private static float colourDistance(int p, int q) {
        int dr = Color.red(p) - Color.red(q), dg = Color.green(p) - Color.green(q), db = Color.blue(p) - Color.blue(q);
        return (float) Math.sqrt(dr * dr + dg * dg + db * db);
    }

    /**
     * The mark of an adaptive icon (its foreground layer, which sits on transparency), so it can be drawn on the
     * glass in its own colours: YouTube's red play button, Netflix's red N. Null for a plain icon, which keeps its
     * own plate, or for an adaptive icon whose foreground is a full-bleed picture.
     */
    /** The monochrome layer an adaptive icon carries for themed icons (Android 13 and later), or null. */
    private static Drawable monochromeMark(Drawable icon) {
        if (Build.VERSION.SDK_INT < 33 || !(icon instanceof AdaptiveIconDrawable)) return null;
        return ((AdaptiveIconDrawable) icon).getMonochrome();
    }

    private static Drawable glassMark(Drawable icon) {
        if (!(icon instanceof AdaptiveIconDrawable)) return null;
        Drawable fg = ((AdaptiveIconDrawable) icon).getForeground();
        if (fg == null || fg.getOpacity() == PixelFormat.OPAQUE) return null;
        return fg;
    }

    /**
     * glass_tiles card fill: near-black glass, faintly tinted with the app's main colour at the top left and
     * fading out towards the bottom right; a little translucent so the wallpaper shows through.
     */
    private static int[] glassFill(int accent) {
        float[] hsv = new float[3];
        Color.colorToHSV(accent, hsv);
        boolean grey = accent == 0xFF6E6E73 || hsv[1] < 0.15f;
        // Yellow darkened turns olive: lean it to amber, as backdrop() does.
        if (hsv[0] > 42f && hsv[0] < 70f && hsv[1] > 0.3f) {
            hsv[0] = 38f;
            accent = Color.HSVToColor(hsv);
        }
        int top = blend(accent, 0xFF202022, grey ? 0.10f : 0.30f);
        int bottom = blend(accent, 0xFF141416, grey ? 0.04f : 0.10f);
        return new int[]{(top & 0x00FFFFFF) | 0xE6000000, (bottom & 0x00FFFFFF) | 0xF0000000};
    }

    /** {@code share} of {@code a} over {@code b}, per channel. */
    private static int blend(int a, int b, float share) {
        float k = 1f - share;
        return Color.rgb(Math.round(Color.red(a) * share + Color.red(b) * k),
                Math.round(Color.green(a) * share + Color.green(b) * k),
                Math.round(Color.blue(a) * share + Color.blue(b) * k));
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        /** item_app.xml's tile_bg: the dark card behind an icon tile, or behind a banner that has transparent parts. */
        final Drawable cardBackground;
        final ImageView banner;
        final ImageView icon;
        final TextView label;
        final TextView name;
        final boolean animations;
        final boolean advancedEffects;
        final boolean reducedMotion;
        /** Alpha of the name while the tile is not focused (always_show_names). */
        final float restNameAlpha;
        final boolean overshoot;

        Holder(View v, TileListener listener, boolean animations, boolean advancedEffects,
               boolean reducedMotion, float restNameAlpha, boolean overshoot) {
            super(v);
            card = v.findViewById(R.id.card);
            cardBackground = card.getBackground();
            banner = v.findViewById(R.id.banner);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            name = v.findViewById(R.id.name);
            this.animations = animations;
            this.advancedEffects = advancedEffects;
            this.reducedMotion = reducedMotion;
            this.restNameAlpha = restNameAlpha;
            this.overshoot = overshoot;

            final float density = v.getResources().getDisplayMetrics().density;
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    // 16dp at the design width, scaled down with the tile so small tiles keep the same shape.
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerRadius(view.getWidth(), density));
                }
            });
            card.setClipToOutline(true);

            card.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN
                        && keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                lastMoveKey = keyCode;
                int pos = getBindingAdapterPosition();
                return listener != null && pos != RecyclerView.NO_POSITION && listener.onTileVerticalKey(pos, keyCode);
            });

            card.setOnFocusChangeListener((view, focused) -> {
                view.animate().cancel();
                banner.animate().cancel();
                name.animate().cancel();
                float scale = !animations ? 1f : Motion.soft ? 1.07f : advancedEffects ? 1.12f : 1.10f;
                float s = focused ? scale : 1f;
                if (!animations) {
                    view.setScaleX(1f);
                    view.setScaleY(1f);
                    view.setTranslationY(0f);
                    view.setTranslationZ(0f);
                    banner.setScaleX(1f);
                    banner.setScaleY(1f);
                    name.setAlpha(focused ? 1f : restNameAlpha);
                    name.setTranslationY(0f);
                } else if (reducedMotion) {
                    // A short zoom with no hardware layer: building a layer for so short an animation costs more
                    // frames than it saves. The name just appears instead of fading.
                    view.animate().scaleX(s).scaleY(s)
                            .translationY(focused && advancedEffects ? -4f * density : 0f)
                            .translationZ(focused && advancedEffects ? 8f * density : 0f)
                            .setDuration(focused ? Motion.REDUCED_MS : 60)
                            .setInterpolator(new DecelerateInterpolator())
                            .start();
                    name.setAlpha(focused ? 1f : restNameAlpha);
                    // Same place as the full motion's slide ends: the layout's -6dp is only its starting point, and
                    // left there the name overlaps the zoomed tile.
                    // Only on focus: snapping it back to -6dp while it fades out would jump it up over the tile.
                    if (focused) name.setTranslationY(density * 4f);
                } else {
                    view.animate().scaleX(s).scaleY(s)
                            .translationY(focused && advancedEffects ? -4f * density : 0f)
                            .translationZ(focused && advancedEffects ? 8f * density : 0f)
                            .setDuration(Motion.soft ? (focused ? 320 : 280) : advancedEffects ? (focused ? 220 : 170) : (focused ? 260 : 180))
                            .setInterpolator(Motion.soft ? new DecelerateInterpolator(2f) : focused && !overshoot ? new DecelerateInterpolator(1.5f)
                                    : new OvershootInterpolator(focused ? (advancedEffects ? 0.7f : 1.6f) : 0f))
                            .withLayer().start();
                    if (advancedEffects) {
                        banner.animate().scaleX(focused ? 1.035f : 1f).scaleY(focused ? 1.035f : 1f)
                                .setDuration(280).start();
                    }
                    name.animate().alpha(focused ? 1f : restNameAlpha)
                            .translationY(focused ? density * (Motion.soft ? 2f : 4f) : -density * (Motion.soft ? 3f : 6f))
                            .setDuration(Motion.ms(220, 320)).start();
                    if (parallax) {
                        if (focused) arrive(view, density);
                        else settle(view);
                    }
                }
                int pos = getBindingAdapterPosition();
                if (focused && listener != null && pos != RecyclerView.NO_POSITION) {
                    listener.onTileFocused(pos, view);
                }
            });
        }
    }
}
