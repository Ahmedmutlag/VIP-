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

    public static final AppTheme PURPLE = new AppTheme(
        "purple", "بنفسجي",
        /* bg, header, card    */ 0xFF0a0a0f, 0xFF16161f, 0xFF16161f,
        /* accent, accentDark  */ 0xFFa855f7, 0xFF7c3aed,
        /* textPrimary, muted  */ 0xFFf0f0f8, 0xFF8888aa,
        /* heroStart, heroEnd  */ 0xFF14101f, 0xFF0a0a0f,
        /* progressTrack, footer */ 0xFF2a2a3a, 0xFF0d0d14,
        /* inputBg, inputBorder  */ 0xFF16161f, 0xFF2a2a3a,
        /* chipBg, chipBorder    */ 0xFF1e1530, 0xFF4c2d9e,
        /* divider */ 0xFF2a2a3a
    );

    public static AppTheme getDefault() {
        return PURPLE;
    }
}
