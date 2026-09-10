package com.nazzilhaplus.app;

public class AppTheme {

    public static final String PREF_KEY = "app_theme";

    public final String id;
    public final String name;
    public final int bgColor;
    public final int headerColor;
    public final int cardBgColor;
    public final int accentColor;
    public final int accentDarkColor;
    public final int textPrimaryColor;
    public final int textSecondaryColor;
    public final int heroStartColor;
    public final int heroEndColor;
    public final int progressTrackColor;
    public final int footerBgColor;
    public final int inputBgColor;
    public final int inputBorderColor;
    public final int chipBgColor;
    public final int chipBorderColor;
    public final int dividerColor;

    public AppTheme(String id, String name,
                    int bgColor, int headerColor, int cardBgColor,
                    int accentColor, int accentDarkColor,
                    int textPrimaryColor, int textSecondaryColor,
                    int heroStartColor, int heroEndColor,
                    int progressTrackColor, int footerBgColor,
                    int inputBgColor, int inputBorderColor,
                    int chipBgColor, int chipBorderColor,
                    int dividerColor) {
        this.id = id; this.name = name;
        this.bgColor = bgColor; this.headerColor = headerColor;
        this.cardBgColor = cardBgColor;
        this.accentColor = accentColor; this.accentDarkColor = accentDarkColor;
        this.textPrimaryColor = textPrimaryColor;
        this.textSecondaryColor = textSecondaryColor;
        this.heroStartColor = heroStartColor; this.heroEndColor = heroEndColor;
        this.progressTrackColor = progressTrackColor;
        this.footerBgColor = footerBgColor;
        this.inputBgColor = inputBgColor; this.inputBorderColor = inputBorderColor;
        this.chipBgColor = chipBgColor; this.chipBorderColor = chipBorderColor;
        this.dividerColor = dividerColor;
    }

    // ─── Purple (default) ────────────────────────────────────────────────────
    public static final AppTheme PURPLE = new AppTheme(
        "purple", "💜 بنفسجي (افتراضي)",
        0xFFF5F3FF, 0xFF7C3AED, 0xFFFFFFFF,
        0xFF7C3AED, 0xFF6D28D9,
        0xFF1F1F2E, 0xFF6B7280,
        0xFFEDE9FE, 0xFFF5F3FF,
        0xFFEEE9FF, 0xFF1E1B2E,
        0xFFEEE9FF, 0xFFC4B5FD,
        0xFFEEE9FF, 0xFFC4B5FD,
        0xFFE5E7EB
    );

    // ─── Neon Flame ──────────────────────────────────────────────────────────
    public static final AppTheme NEON_FLAME = new AppTheme(
        "neon_flame", "🔥 Neon Flame",
        0xFF0A0500, 0xFF1F0900, 0xFF120800,
        0xFFFF4500, 0xFFCC3800,
        0xFFFFF5EE, 0xFF7A5540,
        0xFF1F0900, 0xFF0A0500,
        0xFF2D0F00, 0xFF0A0500,
        0xFF1A0800, 0xFFFF4500,
        0xFF1A0800, 0xFFFF4500,
        0xFF2D1500
    );

    // ─── Ocean Deep ──────────────────────────────────────────────────────────
    public static final AppTheme OCEAN_DEEP = new AppTheme(
        "ocean_deep", "🌊 Ocean Deep",
        0xFF020D1A, 0xFF031526, 0xFF061E38,
        0xFF00B4D8, 0xFF0077B6,
        0xFFE0F7FF, 0xFF6EA8C0,
        0xFF041528, 0xFF020D1A,
        0xFF041528, 0xFF020D1A,
        0xFF061E38, 0xFF00B4D8,
        0xFF061E38, 0xFF00B4D8,
        0xFF0A2540
    );

    // ─── Cyber Matrix ────────────────────────────────────────────────────────
    public static final AppTheme CYBER_MATRIX = new AppTheme(
        "cyber_matrix", "⚡ Cyber Matrix",
        0xFF010901, 0xFF010D01, 0xFF021202,
        0xFF00FF41, 0xFF00CC33,
        0xFFCCFFCC, 0xFF3D7A3D,
        0xFF021202, 0xFF010901,
        0xFF021202, 0xFF010901,
        0xFF021202, 0xFF00FF41,
        0xFF021202, 0xFF00FF41,
        0xFF043004
    );

    public static final AppTheme[] ALL = { PURPLE, NEON_FLAME, OCEAN_DEEP, CYBER_MATRIX };

    public static AppTheme fromId(String id) {
        for (AppTheme t : ALL) {
            if (t.id.equals(id)) return t;
        }
        return PURPLE;
    }
}
