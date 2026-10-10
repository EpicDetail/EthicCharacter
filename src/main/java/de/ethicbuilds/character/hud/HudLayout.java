package de.ethicbuilds.character.hud;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.key.Key;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Die Zeichen des HUDs aus hud.json (erzeugt von tools/survival-hud.js im EthicLobby-Repo). */
public final class HudLayout {

    /** chars = das Zeichen, advance = Laufweite in px, width = Breite der Grafik. */
    public record Glyph(String chars, int width, int advance) {
    }

    public final Key font;
    public final int rowWidth;
    public final int coinsX;
    public final int levelX;
    public final int trackX;
    public final Glyph coinsPlate;
    public final Glyph levelPlate;
    public final Glyph track;
    public final Glyph coin;
    public final List<Glyph> fill = new ArrayList<>();
    public final Map<Character, Glyph> big = new HashMap<>();
    public final Map<Character, Glyph> small = new HashMap<>();
    public final String[] plus = new String[8];
    public final String[] minus = new String[8];

    private HudLayout(JsonObject json) {
        font = Key.key(json.get("schrift").getAsString());
        rowWidth = json.get("zeileBreite").getAsInt();
        JsonObject place = json.getAsJsonObject("lage");
        coinsX = place.get("coins").getAsInt();
        levelX = place.get("level").getAsInt();
        trackX = place.get("spur").getAsInt();
        JsonObject plates = json.getAsJsonObject("plaketten");
        coinsPlate = glyph(plates.getAsJsonObject("coins"));
        levelPlate = glyph(plates.getAsJsonObject("level"));
        track = glyph(plates.getAsJsonObject("spur"));
        coin = glyph(json.getAsJsonObject("muenze"));
        for (JsonElement e : json.getAsJsonArray("fuellung")) {
            fill.add(glyph(e.getAsJsonObject()));
        }
        JsonObject digits = json.getAsJsonObject("ziffern");
        digits.getAsJsonObject("gross").entrySet().forEach(e -> big.put(e.getKey().charAt(0), glyph(e.getValue().getAsJsonObject())));
        digits.getAsJsonObject("klein").entrySet().forEach(e -> small.put(e.getKey().charAt(0), glyph(e.getValue().getAsJsonObject())));
        JsonObject space = json.getAsJsonObject("abstand");
        JsonArray p = space.getAsJsonArray("plus");
        JsonArray m = space.getAsJsonArray("minus");
        for (int i = 0; i < 8; i++) {
            plus[i] = p.get(i).getAsString();
            minus[i] = m.get(i).getAsString();
        }
    }

    private static Glyph glyph(JsonObject o) {
        return new Glyph(o.get("zeichen").getAsString(), o.get("breite").getAsInt(), o.get("lauf").getAsInt());
    }

    public static HudLayout load(String resource) throws IOException {
        try (InputStream in = HudLayout.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException(resource + " fehlt im Plugin");
            }
            return new HudLayout(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
        }
    }

    /** Breite eines Textes in px (ohne den letzten Abstand), unbekannte Zeichen zaehlen nicht. */
    public int width(String text, boolean large) {
        Map<Character, Glyph> map = large ? big : small;
        int width = 0;
        for (char c : text.toCharArray()) {
            Glyph g = map.get(c);
            if (g != null) {
                width += g.advance();
            }
        }
        return Math.max(0, width - 1);
    }
}
