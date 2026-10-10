package de.ethicbuilds.character.board;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Das Layout der Tafeln aus tafel.json (erzeugt von tools/tafel.js im EthicLobby-Repo). */
public final class Layout {

    /** Ein Stueck einer Grafik als Zeichen der Schrift ethicbuilds:tafel, Mitte und Groesse in Bloecken vom Element aus. */
    public record Tile(Component text, int widthPx, int heightPx, int advance, double x, double y, double width, double height) {

        /** Abstand Display-Ursprung bis Texturmitte in px (Text wird nach der Laufweite zentriert). */
        double middle() {
            return 1 + (widthPx - advance) / 2.0;
        }
    }

    public record Glyph(List<Tile> tiles) {
    }

    /** Streifen der Rang-Pille, der nach dem Rang des Spielers eingefaerbt wird. */
    public record Slice(String glyph, int index) {
    }

    public record Element(String id, String type, double x, double y, double width, double height, String text, String font,
                          int px, TextColor color, String state, String action, String tag, String visibleFor,
                          Map<String, String> images, List<Slice> slices, String fill, Map<String, String> extra) {

        /** Ein Klickbereich ohne eigene Grafik, den eine Seite zur Laufzeit anlegt. */
        public static Element region(String id, double x, double y, double width, double height, String action) {
            return new Element(id, "bereich", x, y, width, height, "", "ethicbuilds:ui", 0, TextColor.fromHexString("#E8EEF7"), "normal",
                    action, "", "alle", Map.of(), List.of(), "", Map.of());
        }

        /** Zusatzfeld aus dem Layout (z. B. zeilenhoehePx), sonst der Ersatzwert. */
        public String extra(String key, String fallback) {
            return extra.getOrDefault(key, fallback);
        }

        public boolean clickable() {
            if ("gesperrt".equals(state)) {
                return false;
            }
            // Knoepfe, Haken und Zeilen sind immer bedienbar, die Seite entscheidet nach der Kennung, was sie tun
            return type.equals("knopf") || type.equals("checkbox") || type.equals("zeile")
                    || (action != null && !action.isEmpty() && !action.equals("keine"));
        }
    }

    private final Map<String, Glyph> glyphs = new HashMap<>();
    private final Map<String, List<Element>> pages = new LinkedHashMap<>();
    private final Map<String, String> helpers = new HashMap<>();
    private double boardWidth = 7.2;
    private double boardHeight = 4.5;
    private final Map<String, double[]> sizes = new HashMap<>();

    public static Layout load(String resource) throws IOException {
        try (InputStream in = Layout.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException(resource + " fehlt im Plugin");
            }
            Layout layout = new Layout();
            layout.read(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
            return layout;
        }
    }

    private void read(JsonObject json) {
        JsonObject board = json.getAsJsonObject("tafel");
        boardWidth = board.get("breiteBloecke").getAsDouble();
        boardHeight = board.get("hoeheBloecke").getAsDouble();
        if (json.has("seitenGroesse")) {
            for (Map.Entry<String, JsonElement> e : json.getAsJsonObject("seitenGroesse").entrySet()) {
                JsonObject o = e.getValue().getAsJsonObject();
                sizes.put(e.getKey(), new double[]{o.get("breiteBloecke").getAsDouble(), o.get("hoeheBloecke").getAsDouble()});
            }
        }
        JsonObject glyphJson = json.getAsJsonObject("glyphen");
        Key font = Key.key(glyphJson.get("schrift").getAsString());
        for (Map.Entry<String, JsonElement> e : glyphJson.getAsJsonObject("tabelle").entrySet()) {
            List<Tile> tiles = new ArrayList<>();
            for (JsonElement t : e.getValue().getAsJsonObject().getAsJsonArray("kacheln")) {
                JsonObject o = t.getAsJsonObject();
                JsonArray opaque = o.getAsJsonArray("deckend_px");
                tiles.add(new Tile(Component.text(o.get("zeichen").getAsString()).font(font), o.get("b_px").getAsInt(),
                        o.get("h_px").getAsInt(), opaque.get(1).getAsInt() + 2, o.get("x").getAsDouble(), o.get("y").getAsDouble(),
                        o.get("b").getAsDouble(), o.get("h").getAsDouble()));
            }
            glyphs.put(e.getKey(), new Glyph(tiles));
        }
        for (Map.Entry<String, JsonElement> e : glyphJson.getAsJsonObject("hilfe").entrySet()) {
            helpers.put(e.getKey(), e.getValue().getAsString());
        }
        for (Map.Entry<String, JsonElement> page : json.getAsJsonObject("seiten").entrySet()) {
            List<Element> elements = new ArrayList<>();
            for (JsonElement e : page.getValue().getAsJsonArray()) {
                elements.add(element(e.getAsJsonObject()));
            }
            pages.put(page.getKey(), elements);
        }
    }

    private static Element element(JsonObject o) {
        Map<String, String> images = new HashMap<>();
        if (o.has("bild")) {
            JsonElement image = o.get("bild");
            if (image.isJsonPrimitive()) {
                images.put("normal", image.getAsString());
            } else {
                image.getAsJsonObject().entrySet().forEach(e -> images.put(e.getKey(), e.getValue().getAsString()));
            }
        }
        List<Slice> slices = new ArrayList<>();
        if (o.has("scheiben")) {
            for (JsonElement s : o.getAsJsonArray("scheiben")) {
                slices.add(new Slice(s.getAsJsonObject().get("bild").getAsString(), s.getAsJsonObject().get("nach").getAsInt()));
            }
        }
        String color = string(o, "farbe", "#E8EEF7");
        int px = (int) number(o, "px");
        // Ohne Angabe: grosse Zahlen fett, kleine Texte normal
        String font = string(o, "schrift", px >= 30 ? "ethicbuilds:ui_bold" : "ethicbuilds:ui");
        Map<String, String> extra = new HashMap<>();
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            if (e.getValue().isJsonPrimitive()) {
                extra.put(e.getKey(), e.getValue().getAsString());
            }
        }
        return new Element(o.get("id").getAsString(), o.get("typ").getAsString(), number(o, "x"), number(o, "y"), number(o, "w"),
                number(o, "h"), string(o, "text", ""), font, px,
                color.startsWith("#") ? TextColor.fromHexString(color) : TextColor.fromHexString("#E8EEF7"),
                string(o, "zustand", "normal"), string(o, "aktion", ""), string(o, "tag", ""), string(o, "sichtbarFuer", "alle"),
                images, slices, string(o, "fuellstand", ""), extra);
    }

    private static double number(JsonObject o, String field) {
        return o.has(field) ? o.get(field).getAsDouble() : 0;
    }

    private static String string(JsonObject o, String field, String fallback) {
        return o.has(field) && !o.get(field).isJsonNull() ? o.get(field).getAsString() : fallback;
    }

    public double boardWidth() {
        return boardWidth;
    }

    public double boardHeight() {
        return boardHeight;
    }

    /** Breite der Tafel dieser Seite in Bloecken. */
    public double width(String page) {
        double[] size = sizes.get(page);
        return size != null ? size[0] : boardWidth;
    }

    /** Hoehe der Tafel dieser Seite in Bloecken. */
    public double height(String page) {
        double[] size = sizes.get(page);
        return size != null ? size[1] : boardHeight;
    }

    public Glyph glyph(String name) {
        return glyphs.get(name);
    }

    /** Hilfsgrafik wie "spur", "orange" oder "gold" (Fortschrittslinien). */
    public Glyph helper(String name) {
        return glyphs.get(helpers.get(name));
    }

    public List<Element> page(String name) {
        return pages.getOrDefault(name, List.of());
    }

    public boolean hasPage(String name) {
        return pages.containsKey(name);
    }
}
