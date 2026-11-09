package taxi;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Mesures de la simulation (observation uniquement, aucune décision n'en dépend).
 * Les agents y déposent leurs résultats ; StatsAgent les résume et les exporte en CSV.
 */
public final class Stats {

    private Stats() {}

    public static final class ResultatClient {
        public String nom, profil, statut;   // statut : ARRIVE ou ABANDON
        public int attente, relances;
        public double prix;
        public boolean negocie, covoiturage, revise;
    }

    public static final class EtatTaxi {
        public int courses;
        public double gains;
        public long ticksOccupe, ticksTotal;
    }

    private static final List<ResultatClient> clients = new ArrayList<>();
    private static final Map<String, EtatTaxi> taxis = new LinkedHashMap<>();
    private static int clientsCrees = 0;

    public static synchronized void clientCree() { clientsCrees++; }

    public static synchronized void client(ResultatClient r) { clients.add(r); }

    public static synchronized void taxi(String nom, int courses, double gains, long occupe, long total) {
        EtatTaxi t = taxis.computeIfAbsent(nom, k -> new EtatTaxi());
        t.courses = courses; t.gains = gains; t.ticksOccupe = occupe; t.ticksTotal = total;
    }

    // ------------------------- agrégats -------------------------

    public static synchronized Map<String, Object> agregats() {
        Map<String, Object> m = new LinkedHashMap<>();
        int arrives = 0, abandons = 0, nego = 0, covoit = 0, revises = 0;
        double attente = 0, prix = 0;
        for (ResultatClient r : clients) {
            if ("ARRIVE".equals(r.statut)) { arrives++; attente += r.attente; prix += r.prix; }
            else abandons++;
            if (r.negocie) nego++;
            if (r.covoiturage) covoit++;
            if (r.revise) revises++;
        }
        double gains = 0, occupation = 0;
        for (EtatTaxi t : taxis.values()) {
            gains += t.gains;
            occupation += t.ticksTotal == 0 ? 0 : (double) t.ticksOccupe / t.ticksTotal;
        }
        int termines = arrives + abandons;
        m.put("clients", Math.max(clientsCrees, termines));
        m.put("arrives", arrives);
        m.put("abandons", abandons);
        m.put("enCours", Math.max(0, clientsCrees - termines));
        m.put("tauxAbandon", termines == 0 ? 0.0 : (double) abandons / termines);
        m.put("attenteMoy", arrives == 0 ? 0.0 : attente / arrives);
        m.put("prixMoy", arrives == 0 ? 0.0 : prix / arrives);
        m.put("gainsTotal", gains);
        m.put("occupationMoy", taxis.isEmpty() ? 0.0 : occupation / taxis.size());
        m.put("negociations", nego);
        m.put("covoiturages", covoit);
        m.put("revisions", revises);
        return m;
    }

    public static String resumeCourt() {
        Map<String, Object> a = agregats();
        return String.format(Locale.ROOT, "arrivés %d | abandons %d | attente moy %.1f | prix moy %.1f | occupation %.0f%%",
                a.get("arrives"), a.get("abandons"), (double) a.get("attenteMoy"),
                (double) a.get("prixMoy"), 100 * (double) a.get("occupationMoy"));
    }

    public static synchronized String resumeComplet() {
        Map<String, Object> a = agregats();
        StringBuilder sb = new StringBuilder();
        sb.append("\n================= BILAN DE LA SIMULATION =================\n");
        sb.append(String.format(Locale.ROOT, "Mode : %s | taxis : %d | clients : %d (en cours : %d)%n",
                City.mode, taxis.size(), a.get("clients"), a.get("enCours")));
        sb.append(String.format(Locale.ROOT, "Arrivés : %d   Abandons : %d   Taux d'abandon : %.0f%%%n",
                a.get("arrives"), a.get("abandons"), 100 * (double) a.get("tauxAbandon")));
        sb.append(String.format(Locale.ROOT, "Attente moyenne : %.1f ticks   Prix moyen : %.2f%n",
                (double) a.get("attenteMoy"), (double) a.get("prixMoy")));
        sb.append(String.format(Locale.ROOT, "Négociations réussies : %d   Covoiturages : %d   Révisions de désirs : %d%n",
                a.get("negociations"), a.get("covoiturages"), a.get("revisions")));
        sb.append("Taxis :\n");
        for (Map.Entry<String, EtatTaxi> e : taxis.entrySet()) {
            EtatTaxi t = e.getValue();
            sb.append(String.format(Locale.ROOT, "  %-8s courses %2d   gains %7.2f   occupation %3.0f%%%n",
                    e.getKey(), t.courses, t.gains,
                    t.ticksTotal == 0 ? 0.0 : 100.0 * t.ticksOccupe / t.ticksTotal));
        }
        sb.append("==========================================================");
        return sb.toString();
    }

    // ------------------------- export CSV -------------------------

    /** Ajoute une ligne de synthèse à results/experience.csv. */
    public static synchronized void exporterSynthese(String label) {
        File f = new File("results/experience.csv");
        boolean entete = !f.exists();
        f.getParentFile().mkdirs();
        try (PrintWriter w = new PrintWriter(new FileWriter(f, StandardCharsets.UTF_8, true))) {
            if (entete) {
                w.println("label;mode;taxis;clients;arrives;abandons;taux_abandon;attente_moy;prix_moy;gains_total;occupation_moy;negociations;covoiturages;revisions");
            }
            Map<String, Object> a = agregats();
            w.println(String.format(Locale.ROOT, "%s;%s;%d;%d;%d;%d;%.3f;%.2f;%.2f;%.2f;%.3f;%d;%d;%d",
                    label, City.mode, taxis.size(), a.get("clients"), a.get("arrives"), a.get("abandons"),
                    (double) a.get("tauxAbandon"), (double) a.get("attenteMoy"), (double) a.get("prixMoy"),
                    (double) a.get("gainsTotal"), (double) a.get("occupationMoy"),
                    a.get("negociations"), a.get("covoiturages"), a.get("revisions")));
        } catch (IOException e) {
            System.err.println("Export CSV impossible : " + e.getMessage());
        }
    }

    /** Écrit le détail par client dans results/clients-&lt;label&gt;.csv. */
    public static synchronized void exporterClients(String label) {
        File f = new File("results/clients-" + label + ".csv");
        f.getParentFile().mkdirs();
        try (PrintWriter w = new PrintWriter(new FileWriter(f, StandardCharsets.UTF_8))) {
            w.println("client;profil;statut;attente_ticks;prix;relances;negocie;covoiturage;revise");
            for (ResultatClient r : clients) {
                w.println(String.format(Locale.ROOT, "%s;%s;%s;%d;%.2f;%d;%b;%b;%b", r.nom, r.profil,
                        r.statut, r.attente, r.prix, r.relances, r.negocie, r.covoiturage, r.revise));
            }
        } catch (IOException e) {
            System.err.println("Export CSV impossible : " + e.getMessage());
        }
    }
}
