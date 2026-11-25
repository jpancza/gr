package hu.qrolvaso;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Karakterek átalakítása billentyűleütésekké a Windows billentyűzetkiosztásától függően.
 * Egy leütés: {módosító, billentyűkód}. Ha módosító == ALT_CODE, akkor a második szám
 * egy Windows Alt+0NNN kód (ezt a HidKeyboard gépeli be a numerikus billentyűzettel).
 */
final class KeyMap {

    static final int LAYOUT_HU = 0;
    static final int LAYOUT_US = 1;
    static final int LAYOUT_ALT = 2;

    static final int SHIFT = 0x02;
    static final int ALTGR = 0x40;   // jobb Alt = AltGr
    static final int ALT_CODE = -1;

    static final int KEY_ENTER = 0x28;
    static final int KEY_TAB = 0x2B;
    static final int KEY_SPACE = 0x2C;
    static final int KEY_NUMPAD_STAR = 0x55;

    private static final Map<Character, int[]> HU = new HashMap<>();
    private static final Map<Character, int[]> US = new HashMap<>();

    private static void put(Map<Character, int[]> m, char c, int mod, int key) {
        m.put(c, new int[]{mod, key});
    }

    static {
        // ---- közös: betűk (az y/z a magyar kiosztásnál felcserélve, lent felülírjuk) ----
        for (char c = 'a'; c <= 'z'; c++) {
            int key = 0x04 + (c - 'a');
            put(HU, c, 0, key);
            put(HU, Character.toUpperCase(c), SHIFT, key);
            put(US, c, 0, key);
            put(US, Character.toUpperCase(c), SHIFT, key);
        }
        for (Map<Character, int[]> m : new Map[]{HU, US}) {
            put(m, ' ', 0, KEY_SPACE);
            put(m, '\n', 0, KEY_ENTER);
            put(m, '\t', 0, KEY_TAB);
        }

        // ---- amerikai (US) ----
        for (char c = '1'; c <= '9'; c++) put(US, c, 0, 0x1E + (c - '1'));
        put(US, '0', 0, 0x27);
        String usShifted = "!@#$%^&*()";
        for (int i = 0; i < usShifted.length(); i++) put(US, usShifted.charAt(i), SHIFT, 0x1E + i);
        put(US, '-', 0, 0x2D); put(US, '_', SHIFT, 0x2D);
        put(US, '=', 0, 0x2E); put(US, '+', SHIFT, 0x2E);
        put(US, '[', 0, 0x2F); put(US, '{', SHIFT, 0x2F);
        put(US, ']', 0, 0x30); put(US, '}', SHIFT, 0x30);
        put(US, '\\', 0, 0x31); put(US, '|', SHIFT, 0x31);
        put(US, ';', 0, 0x33); put(US, ':', SHIFT, 0x33);
        put(US, '\'', 0, 0x34); put(US, '"', SHIFT, 0x34);
        put(US, '`', 0, 0x35); put(US, '~', SHIFT, 0x35);
        put(US, ',', 0, 0x36); put(US, '<', SHIFT, 0x36);
        put(US, '.', 0, 0x37); put(US, '>', SHIFT, 0x37);
        put(US, '/', 0, 0x38); put(US, '?', SHIFT, 0x38);

        // ---- magyar (QWERTZ) ----
        put(HU, 'z', 0, 0x1C); put(HU, 'Z', SHIFT, 0x1C);
        put(HU, 'y', 0, 0x1D); put(HU, 'Y', SHIFT, 0x1D);
        put(HU, '0', 0, 0x35);
        for (char c = '1'; c <= '9'; c++) put(HU, c, 0, 0x1E + (c - '1'));
        String huShifted = "'\"+!%/=()";          // Shift + 1..9
        for (int i = 0; i < huShifted.length(); i++) put(HU, huShifted.charAt(i), SHIFT, 0x1E + i);
        String acc = "öüóőúűéáí";
        int[] accKeys = {0x27, 0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x33, 0x34, 0x64};
        for (int i = 0; i < acc.length(); i++) {
            put(HU, acc.charAt(i), 0, accKeys[i]);
            put(HU, Character.toUpperCase(acc.charAt(i)), SHIFT, accKeys[i]);
        }
        put(HU, ',', 0, 0x36); put(HU, '?', SHIFT, 0x36);
        put(HU, '.', 0, 0x37); put(HU, ':', SHIFT, 0x37);
        put(HU, '-', 0, 0x38); put(HU, '_', SHIFT, 0x38);
        put(HU, '*', 0, KEY_NUMPAD_STAR);
        put(HU, ';', ALTGR, 0x36);
        put(HU, '<', ALTGR, 0x64);
        put(HU, '>', ALTGR, 0x1D);
        put(HU, '#', ALTGR, 0x1B);
        put(HU, '&', ALTGR, 0x06);
        put(HU, '@', ALTGR, 0x19);
        put(HU, '{', ALTGR, 0x05);
        put(HU, '}', ALTGR, 0x11);
        put(HU, '[', ALTGR, 0x09);
        put(HU, ']', ALTGR, 0x0A);
        put(HU, '\\', ALTGR, 0x14);
        put(HU, '|', ALTGR, 0x1A);
        put(HU, '$', ALTGR, 0x33);
        // ^ ` ~ a magyar kiosztásban "halott billentyűk" – ezeket Alt-kóddal küldjük
    }

    /** Igaz, ha a billentyű betű, amire a Caps Lock hat (a Shift-et ilyenkor meg kell fordítani). */
    static boolean capsAffected(int key, int layout) {
        if (key >= 0x04 && key <= 0x1D) return true;
        if (layout == LAYOUT_HU) {
            switch (key) {
                case 0x27: case 0x2D: case 0x2E: case 0x2F: case 0x30:
                case 0x31: case 0x33: case 0x34: case 0x64:
                    return true;
            }
        }
        return false;
    }

    static List<int[]> strokes(String text, int layout) {
        List<int[]> out = new ArrayList<>();
        Map<Character, int[]> map = layout == LAYOUT_US ? US : layout == LAYOUT_HU ? HU : null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r') continue;
            if (c == '\n') { out.add(new int[]{0, KEY_ENTER}); continue; }
            if (c == '\t') { out.add(new int[]{0, KEY_TAB}); continue; }
            int[] s = map != null ? map.get(c) : null;
            if (s != null) {
                out.add(s);
            } else {
                int code = altCode(c);
                if (code > 0) out.add(new int[]{ALT_CODE, code});
            }
        }
        return out;
    }

    /** Windows ANSI (1250 – közép-európai) kód az Alt+0NNN beíráshoz; 0, ha nem írható be. */
    private static int altCode(char c) {
        if (c >= 32 && c < 127) return c;
        try {
            byte[] b = String.valueOf(c).getBytes("windows-1250");
            if (b.length == 1 && b[0] != '?') return b[0] & 0xFF;
        } catch (Exception ignored) {
        }
        return 0;
    }

    private KeyMap() {
    }
}
