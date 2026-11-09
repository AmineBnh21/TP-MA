package taxi;

import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Fenêtre d'observation de la ville. Elle affiche l'état que les agents lui envoient et
 * permet d'agir sur l'ENVIRONNEMENT (pause, vitesse, ajout d'un client par clic).
 * Elle ne prend aucune décision à la place des agents : ce n'est pas un dispatcher.
 * Pour la désactiver : -Dcity.view=false
 */
public final class CityView extends JFrame {

    private static final int CELL = 50;
    private static final int O = 10;
    private static CityView instance;
    private static volatile AgentContainer conteneurClients;
    private static int clientsManuels = 0;

    private static final class TaxiInfo {
        int x, y, fuel, cap, aBord; String etat; double gains; List<int[]> route;
    }
    private static final class ClientInfo {
        int x, y, dx, dy; String intention;
    }

    private final Map<String, TaxiInfo> taxis = new LinkedHashMap<>();
    private final Map<String, ClientInfo> clients = new LinkedHashMap<>();
    private final JTextArea journal = new JTextArea(6, 60);
    private final JLabel bandeau = new JLabel(" ");
    private final JPanel grille;

    private CityView() {
        super("Ville 10x10 - Taxis JADE (" + City.mode + ")");
        grille = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                dessiner((Graphics2D) g);
            }
        };
        grille.setPreferredSize(new Dimension(City.SIZE * CELL + 300, City.SIZE * CELL + 45));
        grille.setBackground(Color.WHITE);
        grille.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int cx = (e.getX() - O) / CELL, cy = (e.getY() - O) / CELL;
                if (cx >= 0 && cy >= 0 && cx < City.SIZE && cy < City.SIZE) creerClient(cx, cy);
            }
        });
        journal.setEditable(false);
        journal.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        JToolBar barre = new JToolBar();
        barre.setFloatable(false);
        JButton pause = new JButton("Pause");
        pause.addActionListener(ev -> {
            City.pause = !City.pause;
            pause.setText(City.pause ? "Reprendre" : "Pause");
        });
        barre.add(pause);
        barre.addSeparator();
        barre.add(new JLabel(" Vitesse (ms/tick) "));
        JSlider vitesse = new JSlider(100, 1000, (int) City.tickMs);
        vitesse.setMajorTickSpacing(300);
        vitesse.setPaintLabels(true);
        vitesse.setMaximumSize(new Dimension(260, 50));
        vitesse.addChangeListener(ev -> City.tickMs = vitesse.getValue());
        barre.add(vitesse);
        barre.addSeparator();
        JCheckBox trafic = new JCheckBox("Embouteillages", City.trafic);
        trafic.addActionListener(ev -> City.trafic = trafic.isSelected());
        barre.add(trafic);
        JCheckBox covoit = new JCheckBox("Covoiturage", City.covoiturage);
        covoit.addActionListener(ev -> City.covoiturage = covoit.isSelected());
        barre.add(covoit);
        barre.addSeparator();
        barre.add(new JLabel("  Clic sur une case = nouveau client"));

        bandeau.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        bandeau.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        JPanel haut = new JPanel(new BorderLayout());
        haut.add(barre, BorderLayout.NORTH);
        haut.add(bandeau, BorderLayout.SOUTH);

        setLayout(new BorderLayout());
        add(haut, BorderLayout.NORTH);
        add(grille, BorderLayout.CENTER);
        add(new JScrollPane(journal), BorderLayout.SOUTH);
        setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        pack();
        setLocation(40, 0);
        setVisible(true);

        new Timer(500, ev -> { bandeau.setText(Stats.resumeCourt()); grille.repaint(); }).start();
    }

    private static boolean actif() {
        return !GraphicsEnvironment.isHeadless() && !"false".equals(System.getProperty("city.view"));
    }

    private static synchronized CityView get() {
        if (instance == null) instance = new CityView();
        return instance;
    }

    private static void later(Runnable r) {
        if (!actif()) return;
        SwingUtilities.invokeLater(() -> { r.run(); get().grille.repaint(); });
    }

    /** Conteneur où créer les clients ajoutés à la souris. */
    public static void setConteneurClients(AgentContainer c) {
        conteneurClients = c;
        later(() -> { });
    }

    private void creerClient(int x, int y) {
        if (conteneurClients == null) {
            journal.append("(clic) aucun conteneur pour créer un client\n");
            return;
        }
        Random r = new Random();
        int dx, dy;
        do { dx = r.nextInt(City.SIZE); dy = r.nextInt(City.SIZE); } while (City.manhattan(x, y, dx, dy) < 3);
        String[] profils = {"normal", "presse", "econome"};
        Object[] args = {x, y, dx, dy, 15 + r.nextInt(15), 8 + r.nextInt(6), 3, 200, profils[r.nextInt(3)]};
        String nom = "clic-client" + (++clientsManuels);
        try {
            AgentController ac = conteneurClients.createNewAgent(nom, ClientAgent.class.getName(), args);
            ac.start();
        } catch (Exception e) {
            journal.append("(clic) création impossible : " + e.getMessage() + "\n");
        }
    }

    // ------------------------- API appelée par les agents -------------------------

    public static void taxi(String nom, int x, int y, String etat, int fuel, int cap, double gains, int aBord) {
        later(() -> {
            TaxiInfo t = get().taxis.computeIfAbsent(nom, k -> new TaxiInfo());
            t.x = x; t.y = y; t.etat = etat; t.fuel = fuel; t.cap = cap; t.gains = gains; t.aBord = aBord;
        });
    }

    public static void route(String nom, List<int[]> route) {
        List<int[]> copie = route == null ? null : new ArrayList<>(route);
        later(() -> get().taxis.computeIfAbsent(nom, k -> new TaxiInfo()).route = copie);
    }

    public static void client(String nom, int x, int y, int dx, int dy, String intention) {
        later(() -> {
            ClientInfo c = get().clients.computeIfAbsent(nom, k -> new ClientInfo());
            if (!"VOYAGER".equals(intention) || c.intention == null) { c.x = x; c.y = y; }
            c.dx = dx; c.dy = dy; c.intention = intention;
            if ("ARRIVE".equals(intention)) { c.x = dx; c.y = dy; }
        });
    }

    public static void clientPosition(String nom, int x, int y) {
        later(() -> {
            ClientInfo c = get().clients.get(nom);
            if (c != null) { c.x = x; c.y = y; }
        });
    }

    public static void journal(String ligne) {
        later(() -> {
            JTextArea j = get().journal;
            j.append(ligne + "\n");
            if (j.getLineCount() > 2000) {
                try { j.replaceRange("", 0, j.getLineEndOffset(500)); } catch (Exception ignored) { }
            }
            j.setCaretPosition(j.getDocument().getLength());
        });
    }

    // ------------------------- dessin -------------------------

    private static Color couleurTaxi(String etat) {
        if (etat == null) return Color.GRAY;
        switch (etat) {
            case "LIBRE":            return new Color(46, 160, 67);
            case "REPOSITIONNEMENT": return new Color(20, 160, 160);
            case "VERS_CLIENT":      return new Color(230, 150, 20);
            case "EN_COURSE":        return new Color(210, 50, 50);
            case "RAVITAILLEMENT":   return new Color(60, 110, 220);
            case "PANNE":            return Color.BLACK;
            default:                 return Color.GRAY;
        }
    }

    private static Color couleurClient(String intention) {
        if (intention == null) return Color.GRAY;
        switch (intention) {
            case "ARRIVE":     return new Color(46, 160, 67);
            case "ABANDONNER": return Color.GRAY;
            case "VOYAGER":    return new Color(210, 50, 50);
            case "NEGOCIER":   return new Color(230, 150, 20);
            default:           return new Color(140, 60, 180);
        }
    }

    private static int cx(int x) { return O + x * CELL + CELL / 2; }

    private void dessiner(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // cases : embouteillages et stations
        for (int x = 0; x < City.SIZE; x++)
            for (int y = 0; y < City.SIZE; y++)
                if (City.trafic && City.embouteillee(x, y)) {
                    g.setColor(new Color(255, 225, 225));
                    g.fillRect(O + x * CELL, O + y * CELL, CELL, CELL);
                }
        for (int[] s : City.STATIONS) {
            g.setColor(new Color(255, 240, 180));
            g.fillRect(O + s[0] * CELL + 1, O + s[1] * CELL + 1, CELL - 1, CELL - 1);
            g.setColor(new Color(160, 120, 0));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
            g.drawString("STATION", O + s[0] * CELL + 4, O + s[1] * CELL + CELL - 6);
        }
        g.setColor(new Color(225, 225, 225));
        for (int i = 0; i <= City.SIZE; i++) {
            g.drawLine(O, O + i * CELL, O + City.SIZE * CELL, O + i * CELL);
            g.drawLine(O + i * CELL, O, O + i * CELL, O + City.SIZE * CELL);
        }
        g.setColor(Color.LIGHT_GRAY);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 9));
        for (int x = 0; x < City.SIZE; x++)
            for (int y = 0; y < City.SIZE; y++)
                g.drawString(x + "," + y, O + x * CELL + 3, O + y * CELL + 11);

        // itinéraires des taxis
        g.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                1, new float[]{6, 6}, 0));
        for (TaxiInfo t : taxis.values()) {
            if (t.route == null || t.route.isEmpty()) continue;
            Color c = couleurTaxi(t.etat);
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 110));
            int px = cx(t.x), py = cx(t.y);
            for (int[] p : t.route) {
                g.drawLine(px, py, cx(p[0]), cx(p[1]));
                px = cx(p[0]); py = cx(p[1]);
            }
        }
        g.setStroke(new BasicStroke(2));

        // clients : destination (croix) + position (rond)
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
        for (Map.Entry<String, ClientInfo> e : clients.entrySet()) {
            ClientInfo c = e.getValue();
            if ("ARRIVE".equals(c.intention) || "ABANDONNER".equals(c.intention)) continue;
            Color col = couleurClient(c.intention);
            int dx = cx(c.dx), dy = cx(c.dy);
            g.setColor(col);
            g.drawLine(dx - 7, dy - 7, dx + 7, dy + 7);
            g.drawLine(dx - 7, dy + 7, dx + 7, dy - 7);
            g.drawString(court(e.getKey()), dx - 12, dy + 18);
            if (!"VOYAGER".equals(c.intention)) {
                int px = O + c.x * CELL + 6, py = O + c.y * CELL + CELL - 22;
                g.fillOval(px, py, 14, 14);
                g.drawString(court(e.getKey()), px - 2, py - 2);
            }
        }

        // taxis
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        for (Map.Entry<String, TaxiInfo> e : taxis.entrySet()) {
            TaxiInfo t = e.getValue();
            int tx = O + t.x * CELL + CELL - 32, ty = O + t.y * CELL + 15;
            g.setColor(couleurTaxi(t.etat));
            g.fillRoundRect(tx, ty, 30, 18, 6, 6);
            g.setColor(Color.WHITE);
            String lib = e.getKey().replace("taxi", "T") + (t.aBord > 0 ? "·" + t.aBord : "");
            g.drawString(lib, tx + 4, ty + 13);
        }
        g.setStroke(new BasicStroke(1));

        // panneau d'état
        int lx = O + City.SIZE * CELL + 16, ly = O + 14;
        g.setColor(Color.BLACK);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
        g.drawString("Taxis", lx, ly);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        for (Map.Entry<String, TaxiInfo> e : taxis.entrySet()) {
            TaxiInfo t = e.getValue();
            ly += 17;
            g.setColor(couleurTaxi(t.etat));
            g.fillRect(lx, ly - 10, 10, 10);
            g.setColor(Color.BLACK);
            g.drawString(e.getKey() + "  " + t.etat + (t.aBord > 0 ? " (" + t.aBord + " à bord)" : ""), lx + 16, ly);
            ly += 14;
            g.drawString(String.format("   carb %d/%d   gains %.1f", t.fuel, t.cap, t.gains), lx + 16, ly);
        }
        ly += 24;
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
        g.drawString("Clients", lx, ly);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        int affiches = 0;
        List<Map.Entry<String, ClientInfo>> liste = new ArrayList<>(clients.entrySet());
        for (int i = Math.max(0, liste.size() - 12); i < liste.size(); i++) {
            Map.Entry<String, ClientInfo> e = liste.get(i);
            ly += 16;
            g.setColor(couleurClient(e.getValue().intention));
            g.fillOval(lx, ly - 10, 10, 10);
            g.setColor(Color.BLACK);
            g.drawString(e.getKey() + " : " + e.getValue().intention, lx + 16, ly);
            affiches++;
        }
        if (affiches == 0) { ly += 16; g.drawString("(aucun)", lx + 16, ly); }
        ly += 26;
        g.setColor(Color.DARK_GRAY);
        g.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 11));
        String[] leg = {"Taxi : vert libre, cyan repositionnement,", "orange va chercher, rouge en course,",
                "bleu ravitaillement, noir panne. T1·2 = 2 à bord", "Client : violet cherche, orange négocie",
                "X = destination, rose = embouteillage (x3)", "pointillés = itinéraire (Dijkstra)"};
        for (String s : leg) { g.drawString(s, lx, ly); ly += 14; }
    }

    private static String court(String nom) {
        return nom.replace("gen-client", "g").replace("clic-client", "k").replace("client", "c");
    }
}
