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

    // Zweite Ebene: Leben links, Hunger rechts (gespiegelt)
    public final Glyph lifePlate;
    public final Glyph lifeTrack;
    public final Glyph lifeRim;
    public final List<Glyph> lifeFill = new ArrayList<>();
    public final List<Glyph> lifeFillSquare = new ArrayList<>();
    public final List<Glyph> lifeOver = new ArrayList<>();
    public final Map<Character, Glyph> lifeBig = new HashMap<>();
    public final Map<Character, Glyph> lifeSmall = new HashMap<>();
    public final int lifePlateX;
    public final int lifePlateWidth;
    public final int lifeTrackX;
    public final int lifeTrackWidth;
    public final Glyph foodPlate;
    public final Glyph foodTrack;
    public final Glyph foodIcon;
    public final List<Glyph> foodFill = new ArrayList<>();
    public final int foodPlateX;
    public final int foodPlateWidth;
    public final int foodTrackX;
    public final int foodTrackWidth;
    public final int foodStep;

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
        JsonObject life = json.getAsJsonObject("leben");
        lifePlate = glyph(life.getAsJsonObject("plakette"));
        lifeTrack = glyph(life.getAsJsonObject("spur"));
        lifeRim = glyph(life.getAsJsonObject("rand"));
        fill(life.getAsJsonArray("fuellung"), lifeFill);
        fill(life.getAsJsonArray("eck"), lifeFillSquare);
        fill(life.getAsJsonArray("ueber"), lifeOver);
        JsonObject lifeDigits = life.getAsJsonObject("ziffern");
        lifeDigits.getAsJsonObject("gross").entrySet().forEach(e -> lifeBig.put(e.getKey().charAt(0), glyph(e.getValue().getAsJsonObject())));
        lifeDigits.getAsJsonObject("klein").entrySet().forEach(e -> lifeSmall.put(e.getKey().charAt(0), glyph(e.getValue().getAsJsonObject())));
        lifePlateX = life.getAsJsonObject("lage").get("plakette").getAsInt();
        lifeTrackX = life.getAsJsonObject("lage").get("spur").getAsInt();
        lifePlateWidth = life.get("plaketteBreite").getAsInt();
        lifeTrackWidth = life.get("spurBreite").getAsInt();
        JsonObject food = json.getAsJsonObject("hunger");
        foodPlate = glyph(food.getAsJsonObject("plakette"));
        foodTrack = glyph(food.getAsJsonObject("spur"));
        foodIcon = glyph(food.getAsJsonObject("icon"));
        fill(food.getAsJsonArray("fuellung"), foodFill);
        foodPlateX = food.getAsJsonObject("lage").get("plakette").getAsInt();
        foodTrackX = food.getAsJsonObject("lage").get("spur").getAsInt();
        foodPlateWidth = food.get("plaketteBreite").getAsInt();
        foodTrackWidth = food.get("spurBreite").getAsInt();
        foodStep = food.get("schritt").getAsInt();
    }

    private static void fill(JsonArray array, List<Glyph> target) {
        for (JsonElement e : array) {
            target.add(glyph(e.getAsJsonObject()));
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
        return width(text, large ? big : small);
    }

    /** Wie oben, aber mit einer frei gewaehlten Zeichentabelle. */
    public int width(String text, Map<Character, Glyph> map) {
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
