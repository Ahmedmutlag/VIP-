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
        0xFFF5F3FF, 0xFF7C3AED, 0xFFFFFFFF,
        0xFF7C3AED, 0xFF6D28D9,
        0xFF1F1F2E, 0xFF6B7280,
        0xFFEDE9FE, 0xFFF5F3FF,
        0xFFEEE9FF, 0xFF1E1B2E,
        0xFFEEE9FF, 0xFFC4B5FD,
        0xFFEEE9FF, 0xFFC4B5FD,
        0xFFE5E7EB
    );

    public static AppTheme getDefault() {
        return PURPLE;
    }
}
