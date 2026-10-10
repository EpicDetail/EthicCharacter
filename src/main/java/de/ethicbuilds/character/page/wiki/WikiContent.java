package de.ethicbuilds.character.page.wiki;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Die Artikel des Wikis aus wiki.json (Kategorien, Artikel, Beliebt-Liste) und die Suche darin. */
public final class WikiContent {

    public record Category(String name, String shortName) {
    }

    public record Article(String id, int category, String title, List<String> keywords, List<String> text) {
    }

    /** Ein Suchtreffer; mark markiert die passenden Buchstaben im Titel. */
    public record Hit(Article article, boolean[] mark, int score) {
    }

    private final List<Category> categories = new ArrayList<>();
    private final List<Article> articles = new ArrayList<>();
    private final List<String> popular = new ArrayList<>();

    public static WikiContent load(String resource) throws IOException {
        try (InputStream in = WikiContent.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException(resource + " fehlt im Plugin");
            }
            WikiContent content = new WikiContent();
            content.read(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
            return content;
        }
    }

    private void read(JsonObject json) throws IOException {
        for (JsonElement e : json.getAsJsonArray("kategorien")) {
            JsonObject o = e.getAsJsonObject();
            categories.add(new Category(o.get("name").getAsString(), o.get("kurz").getAsString()));
        }
        for (JsonElement e : json.getAsJsonArray("artikel")) {
            JsonObject o = e.getAsJsonObject();
            String name = o.get("kategorie").getAsString();
            int category = -1;
            for (int i = 0; i < categories.size(); i++) {
                if (categories.get(i).name().equals(name)) {
                    category = i;
                }
            }
            if (category < 0) {
                throw new IOException("wiki.json: unbekannte Kategorie " + name);
            }
            articles.add(new Article(o.get("id").getAsString(), category, o.get("titel").getAsString(),
                    strings(o.getAsJsonArray("stichworte")), strings(o.getAsJsonArray("text"))));
        }
        for (JsonElement e : json.getAsJsonArray("beliebt")) {
            popular.add(e.getAsString());
        }
    }

    private static List<String> strings(JsonArray array) {
        List<String> list = new ArrayList<>();
        if (array != null) {
            array.forEach(e -> list.add(e.getAsString()));
        }
        return list;
    }

    public List<Category> categories() {
        return categories;
    }

    /** Artikel einer Kategorie in der Reihenfolge der Datei. */
    public List<Article> articles(int category) {
        return articles.stream().filter(a -> a.category() == category).toList();
    }

    /** Artikel der Beliebt-Chips (Reihenfolge wie die Chips), unbekannte Kennungen fehlen. */
    public List<Article> popular() {
        List<Article> list = new ArrayList<>();
        for (String id : popular) {
            articles.stream().filter(a -> a.id().equals(id)).findFirst().ifPresent(list::add);
        }
        return list;
    }

    // ------------------------------------------------------------ Suche

    // Kleinschreibung ohne Umlaute, Laenge bleibt gleich, damit Positionen im Original stimmen
    static String fold(String text) {
        StringBuilder b = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            char l = Character.toLowerCase(c);
            b.append(switch (l) {
                case 'ä' -> 'a';
                case 'ö' -> 'o';
                case 'ü' -> 'u';
                case 'ß' -> 's';
                default -> l;
            });
        }
        return b.toString();
    }

    /** Artikel, deren Titel oder Stichworte zur Eingabe passen; Titeltreffer zuerst. */
    public List<Hit> search(String query) {
        String q = fold(query.trim());
        if (q.isEmpty()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        for (String t : q.split("\\s+")) {
            if (!t.isEmpty()) {
                tokens.add(t);
            }
        }
        List<Hit> hits = new ArrayList<>();
        for (Article a : articles) {
            String title = fold(a.title());
            boolean[] mark = new boolean[title.length()];
            int score;
            if (title.contains(q)) {
                mark(mark, title, q);
                score = 0;
            } else {
                boolean all = true;
                for (String t : tokens) {
                    boolean inTitle = title.contains(t);
                    if (inTitle) {
                        mark(mark, title, t);
                    } else if (a.keywords().stream().noneMatch(k -> fold(k).contains(t))) {
                        all = false;
                        break;
                    }
                }
                if (!all) {
                    continue;
                }
                score = 1;
            }
            hits.add(new Hit(a, mark, score));
        }
        hits.sort(Comparator.comparingInt(Hit::score));
        return hits;
    }

    private static void mark(boolean[] mark, String title, String part) {
        for (int i = title.indexOf(part); i >= 0; i = title.indexOf(part, i + 1)) {
            for (int j = i; j < i + part.length(); j++) {
                mark[j] = true;
            }
        }
    }
}
