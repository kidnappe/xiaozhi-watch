package com.xiaozhi.watch;

/**
 * 颜色小工具：情绪球用的 hex 解析 / 明暗调整 / 通道插值。
 *
 * <p>统一用 ARGB int（Android 原生格式），避免 String 反复解析。</p>
 */
public final class Colorx {

    /** "#RGB" / "#RRGGBB" → 0xAARRGGBB。解析失败返回 fallback。 */
    public static int parse(String hex, int fallback) {
        if (hex == null) return fallback;
        String h = hex.trim();
        if (h.startsWith("#")) h = h.substring(1);
        if (h.length() == 3) {
            h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
        }
        if (h.length() != 6) return fallback;
        try {
            return 0xFF000000 | Integer.parseInt(h, 16);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** amount &gt; 0 向白靠拢，&lt; 0 向黑靠拢（对标 ball.js 的 shade）。 */
    public static int shade(int color, float amount) {
        int target = amount < 0 ? 0 : 255;
        float a = Math.abs(amount);
        int r = (color >> 16) & 255, g = (color >> 8) & 255, b = color & 255;
        r = Math.round(r + (target - r) * a);
        g = Math.round(g + (target - g) * a);
        b = Math.round(b + (target - b) * a);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** 逐通道线性插值。 */
    public static int lerp(int a, int b, float t) {
        if (a == b) return b;
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        int r = Math.round(ar + (br - ar) * t);
        int g = Math.round(ag + (bg - ag) * t);
        int bl = Math.round(ab + (bb - ab) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private Colorx() { }
}
