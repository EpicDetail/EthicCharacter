package de.ethicbuilds.character.page;

import de.ethicbuilds.character.board.Board;
import de.ethicbuilds.character.board.Board.Session;
import de.ethicbuilds.character.board.Layout.Element;
import net.kyori.adventure.text.Component;

import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Set;

/** Eine oder mehrere Seiten der Tafel mit eigenem Inhalt, eigenen Klicks und eigenen Zeichnungen. */
public interface PageHandler {

    /** Namen der Seiten im Layout, die dieser Handler betreut. */
    Set<String> pages();

    /** Laedt die Daten der Seite (Datenbank), laeuft nicht im Hauptthread. Ergebnis kommt in data (spaeter session.data()). */
    default void load(Player player, Map<String, Object> data, String page) {
    }

    /** Zeichnet die dynamischen Teile (Listen, Items, Linien) nach dem Layout. */
    void draw(Board board, Session session, String page);

    /** Text eines Layout-Elements, null = Standardtext des Layouts (Platzhalter wie {name} werden dann ersetzt). */
    default Component content(Session session, Element element) {
        return null;
    }

    /** Klick auf ein Element oder einen Klickbereich; true, wenn behandelt. */
    boolean click(Board board, Session session, Element element);

    /** Soll das Element gezeichnet werden? */
    default boolean visible(Session session, Element element) {
        return true;
    }
}
