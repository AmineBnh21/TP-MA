package taxi;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Environnement partagé : une grille 10x10, la distance de Manhattan, les embouteillages,
 * le calcul d'itinéraire, les stations-service et les options de simulation.
 * Aucun agent central : cette classe ne contient que des constantes, des réglages et des fonctions pures.
 */
public final class City {

    public static final int SIZE = 10;                       // grille 10 x 10, coordonnées 0..9
    public static final String SERVICE_TYPE = "taxi-service";
    public static final String SERVICE_COVOIT = "taxi-covoiturage";
    public static final String SERVICE_DISPATCH = "dispatch-service";
    public static final String PROTOCOL = "taxi-cnp";

    /** Stations-service où les taxis se ravitaillent. */
    public static final int[][] STATIONS = {{0, 0}, {9, 9}};

    // ---------------- réglages de simulation (modifiables par Lancement / la fenêtre) ----------------
    /** Durée d'un tick en ms : un tick = une case parcourue sur route fluide. */
    public static volatile long tickMs = Long.getLong("city.tick", 400);
    public static volatile boolean pause = false;
    /** "decentralise" (DF + Contract Net) ou "centralise" (agent dispatcher). */
    public static volatile String mode = System.getProperty("taxi.mode", "decentralise");
    /** Le taxi réserve sa première offre et refuse les autres CFP tant qu'elle n'a pas de réponse. */
    public static volatile boolean reservation = Boolean.getBoolean("taxi.reservation");
    public static volatile boolean covoiturage = !"false".equals(System.getProperty("taxi.covoiturage"));
    public static volatile boolean repositionnement = !"false".equals(System.getProperty("taxi.repositionnement"));
    public static volatile boolean trafic = !"false".equals(System.getProperty("taxi.trafic"));

    /** Coût en ticks pour entrer dans chaque case (1 = fluide, 3 = embouteillage). */
    private static final int[][] COUT = new int[SIZE][SIZE];
    static {
        for (int[] ligne : COUT) Arrays.fill(ligne, 1);
        // centre-ville embouteillé
        for (int x = 3; x <= 6; x++) for (int y = 4; y <= 5; y++) COUT[x][y] = 3;
    }

    private City() {}

    public static boolean centralise() { return "centralise".equals(mode); }

    public static long tick() { return tickMs; }

    public static int cout(int x, int y) { return trafic ? COUT[x][y] : 1; }

    public static boolean embouteillee(int x, int y) { return COUT[x][y] > 1; }

    public static int manhattan(int x1, int y1, int x2, int y2) {
        return Math.abs(x1 - x2) + Math.abs(y1 - y2);
    }

    public static int clamp(int v) {
        return Math.max(0, Math.min(SIZE - 1, v));
    }

    /** Station la plus proche de (x, y) : {sx, sy}. */
    public static int[] nearestStation(int x, int y) {
        int[] best = STATIONS[0];
        for (int[] s : STATIONS) {
            if (manhattan(x, y, s[0], s[1]) < manhattan(x, y, best[0], best[1])) best = s;
        }
        return best;
    }

    public static String pos(int x, int y) {
        return "(" + x + "," + y + ")";
    }

    // ---------------- itinéraire (Dijkstra sur le coût en ticks) ----------------

    /** Itinéraire le plus rapide de (x1,y1) à (x2,y2), sans la case de départ. Chaque élément vaut {x, y}. */
    public static List<int[]> route(int x1, int y1, int x2, int y2) {
        int n = SIZE * SIZE;
        int[] dist = new int[n];
        int[] prev = new int[n];
        Arrays.fill(dist, Integer.MAX_VALUE);
        Arrays.fill(prev, -1);
        int src = x1 * SIZE + y1, dst = x2 * SIZE + y2;
        dist[src] = 0;
        PriorityQueue<int[]> pq = new PriorityQueue<>((a, b) -> a[1] != b[1] ? a[1] - b[1] : a[2] - b[2]);
        pq.add(new int[]{src, 0, 0});
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!pq.isEmpty()) {
            int[] cur = pq.poll();
            int u = cur[0];
            if (cur[1] > dist[u]) continue;
            if (u == dst) break;
            int ux = u / SIZE, uy = u % SIZE;
            for (int[] d : dirs) {
                int vx = ux + d[0], vy = uy + d[1];
                if (vx < 0 || vy < 0 || vx >= SIZE || vy >= SIZE) continue;
                int v = vx * SIZE + vy;
                // départage : à coût égal, préférer le chemin le plus court en cases
                int nd = dist[u] + cout(vx, vy);
                if (nd < dist[v]) {
                    dist[v] = nd;
                    prev[v] = u;
                    pq.add(new int[]{v, nd, cur[2] + 1});
                }
            }
        }
        List<int[]> chemin = new ArrayList<>();
        for (int v = dst; v != src && v != -1; v = prev[v]) chemin.add(new int[]{v / SIZE, v % SIZE});
        Collections.reverse(chemin);
        return chemin;
    }

    /** Temps de parcours en ticks (embouteillages compris). */
    public static int duree(int x1, int y1, int x2, int y2) {
        int t = 0;
        for (int[] c : route(x1, y1, x2, y2)) t += cout(c[0], c[1]);
        return t;
    }

    /** Nombre de cases parcourues (= carburant consommé). */
    public static int longueur(int x1, int y1, int x2, int y2) {
        return route(x1, y1, x2, y2).size();
    }

    // ---------------- arguments de lancement ----------------

    public static int intArg(Object[] args, int i, int def) {
        if (args == null || args.length <= i || args[i].toString().trim().isEmpty()) return def;
        return Integer.parseInt(args[i].toString().trim());
    }

    public static double doubleArg(Object[] args, int i, double def) {
        if (args == null || args.length <= i || args[i].toString().trim().isEmpty()) return def;
        return Double.parseDouble(args[i].toString().trim());
    }

    public static String strArg(Object[] args, int i, String def) {
        if (args == null || args.length <= i || args[i].toString().trim().isEmpty()) return def;
        return args[i].toString().trim();
    }

    public static double arrondi(double v) {
        return Math.round(v * 100) / 100.0;
    }

    // ---------------- logs horodatés ----------------

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    public static volatile boolean silencieux = Boolean.getBoolean("city.silencieux");

    public static synchronized void log(String who, String text) {
        String ligne = LocalTime.now().format(TIME) + " [" + who + "] " + text;
        if (!silencieux) System.out.println(ligne);
        CityView.journal(ligne);
    }
}
