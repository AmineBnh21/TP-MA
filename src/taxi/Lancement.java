package taxi;

import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;
import jade.wrapper.ContainerController;
import jade.wrapper.StaleProxyException;

import java.util.HashMap;
import java.util.Map;

/**
 * Lance la plateforme et un scénario avec l'API Runtime de JADE, sur trois conteneurs :
 *   Main-Container (AMS, DF, RMA, stats, dispatcher), Conteneur-Taxis, Conteneur-Clients.
 *
 * Usage : java taxi.Lancement <scenario> [--nogui] [--taxis=N] [--mode=centralise|decentralise]
 *                             [--duree=sec] [--seed=N] [--clients=N] [--periode=ms] [--label=texte] [--port=1099] [--sniff=agent1,agent2]
 */
public final class Lancement {

    private static ContainerController taxis, clients, main;
    private static String port = "1099";

    private Lancement() {}

    public static void main(String[] args) throws Exception {
        String scenario = "nominal";
        Map<String, String> opt = new HashMap<>();
        for (String a : args) {
            if (a.startsWith("--")) {
                String[] kv = a.substring(2).split("=", 2);
                opt.put(kv[0], kv.length > 1 ? kv[1] : "true");
            } else {
                scenario = a;
            }
        }
        boolean gui = !opt.containsKey("nogui");
        if (opt.containsKey("mode")) City.mode = opt.get("mode");
        if (opt.containsKey("reservation")) City.reservation = true;
        if ("ville".equals(scenario) && !opt.containsKey("mode")) City.mode = "decentralise";
        if ("centralise".equals(scenario)) City.mode = "centralise";
        if ("reservation".equals(scenario)) City.reservation = true;
        if ("experience".equals(scenario)) { City.silencieux = true; System.setProperty("city.view", "false"); gui = false; }

        Runtime rt = Runtime.instance();
        rt.setCloseVM(true);
        Profile pm = new ProfileImpl();
        if (gui) pm.setParameter(Profile.GUI, "true");
        port = opt.getOrDefault("port", "1099");
        pm.setParameter(Profile.LOCAL_PORT, port);
        main = rt.createMainContainer(pm);
        taxis = conteneur(rt, "Conteneur-Taxis");
        clients = conteneur(rt, "Conteneur-Clients");
        CityView.setConteneurClients((AgentContainer) clients);

        int duree = Integer.parseInt(opt.getOrDefault("duree", "0"));
        String label = opt.getOrDefault("label", scenario);
        if (opt.containsKey("sniff")) {
            // Sniffer lancé d'office sur les agents listés (séparés par des virgules)
            agent(main, "sniffer", jade.tools.sniffer.Sniffer.class, opt.get("sniff").replace(',', ';'));
            Thread.sleep(2500);
        }
        agent(main, "stats", StatsAgent.class, duree, label);
        if (City.centralise()) agent(main, "dispatcher", DispatcherAgent.class);

        System.out.println(">>> Scénario " + scenario + " | mode " + City.mode
                + (City.reservation ? " | réservation" : "") + " | tick " + City.tickMs + " ms");

        switch (scenario) {
            case "nominal":
                // 3 offres : compromis prix / attente, l'itinéraire contourne le centre embouteillé
                taxi("taxi1", 0, 0, 3, 1.5, 40);
                taxi("taxi2", 6, 7, 2, 2, 40);
                taxi("taxi3", 9, 0, 4, 1, 40);
                client("client1", 3, 2, 9, 8, 30, 9, 3, 3000, "normal");
                break;
            case "concurrence":
            case "reservation":
                // deux clients veulent le même taxi au même instant
                taxi("taxi1", 0, 0, 3, 1.5, 40);
                taxi("taxi2", 9, 9, 3, 1.5, 40);
                client("client1", 1, 1, 5, 2, 25, 18, 5, 2000, "normal");
                client("client2", 2, 2, 7, 7, 25, 18, 5, 2000, "normal");
                break;
            case "budget":
                // la course coûte bien plus que le budget : révision, puis abandon
                taxi("taxi1", 0, 0, 5, 2, 40);
                client("client1", 0, 1, 9, 9, 10, 8, 2, 2000, "normal");
                break;
            case "negociation":
                // l'offre dépasse un peu le budget : contre-offre acceptée par le taxi
                taxi("taxi1", 0, 0, 3, 1.5, 40);
                client("client1", 1, 0, 7, 6, 17, 8, 3, 2000, "normal");
                client("client2", 0, 9, 9, 2, 20, 30, 1, 9000, "normal");   // contre-offre trop basse : refusée
                break;
            case "carburant":
                taxi("taxi1", 0, 0, 3, 1.5, 12, 40);
                taxi("taxi2", 9, 0, 3, 2, 40);
                client("client1", 5, 2, 9, 7, 30, 12, 3, 2000, "normal");
                client("client2", 1, 1, 3, 3, 20, 8, 3, 7000, "normal");
                break;
            case "profils":
                // mêmes offres, choix différents : le pressé prend le taxi proche, l'économe le moins cher
                taxi("taxiProche", 3, 1, 6, 2.5, 40);
                taxi("taxiEco", 9, 0, 2, 1, 40);
                client("presse", 2, 2, 2, 8, 40, 14, 3, 2000, "presse");
                client("econome", 2, 1, 2, 7, 40, 14, 3, 2000, "econome");
                break;
            case "revision":
                // seul taxi trop loin : après 2 relances, le client assouplit son attente max
                City.repositionnement = false;   // le taxi reste loin : seule la révision peut débloquer
                taxi("taxi1", 0, 0, 3, 1.5, 40);
                client("client1", 3, 3, 8, 1, 30, 5, 4, 2000, "normal");
                break;
            case "covoiturage":
                // taxi1 emmène client1 vers l'est et prend client2 sur sa route
                taxi("taxi1", 0, 1, 3, 1.5, 40);
                taxi("taxi2", 9, 9, 3, 1.5, 40);
                client("client1", 1, 1, 9, 1, 30, 10, 3, 2000, "normal");
                client("client2", 5, 1, 9, 2, 30, 10, 3, 4500, "normal");
                break;
            case "trafic":
                // le chemin le plus court en cases traverse les embouteillages ; Dijkstra les contourne
                taxi("taxi1", 0, 4, 3, 1.5, 40);
                client("client1", 2, 4, 9, 5, 30, 10, 3, 2000, "normal");
                break;
            case "centralise":
                flotte(3);
                agent(clients, "generateur", GeneratorAgent.class, 2500, 8, 7, "gen");
                break;
            case "ville":
                flotte(4);
                agent(clients, "generateur", GeneratorAgent.class, 2500, 20, 7, "gen");
                break;
            case "experience": {
                int n = Integer.parseInt(opt.getOrDefault("taxis", "3"));
                flotte(n);
                agent(clients, "generateur", GeneratorAgent.class,
                        Integer.parseInt(opt.getOrDefault("periode", "1500")),
                        Integer.parseInt(opt.getOrDefault("clients", "20")),
                        Integer.parseInt(opt.getOrDefault("seed", "42")), "gen");
                break;
            }
            case "fipa":
                agent(taxis, "fipaTaxi1", taxi.fipa.CnpTaxiAgent.class, 0, 0, 3, 1.5, 0);
                agent(taxis, "fipaTaxi2", taxi.fipa.CnpTaxiAgent.class, 5, 5, 2, 2, 0);
                agent(taxis, "fipaTaxi3", taxi.fipa.CnpTaxiAgent.class, 9, 9, 4, 1, 1);
                agent(clients, "fipaClient", taxi.fipa.CnpClientAgent.class, 4, 4, 9, 9, 30);
                break;
            case "distant":
                // taxis seulement : les clients viennent d'une autre JVM (voir run.ps1 multijvm)
                flotte(3);
                break;
            default:
                System.out.println("Scénario inconnu : " + scenario);
                System.exit(1);
        }
    }

    private static final int[][] POSITIONS = {{0, 0}, {9, 9}, {9, 0}, {0, 9}, {5, 2}, {2, 6}, {7, 6}, {4, 8}};

    private static void flotte(int n) throws StaleProxyException {
        for (int i = 0; i < n; i++) {
            int[] p = POSITIONS[i % POSITIONS.length];
            taxi("taxi" + (i + 1), p[0], p[1], 2 + (i % 3), 1 + 0.25 * (i % 4), 40);
        }
    }

    private static ContainerController conteneur(Runtime rt, String nom) {
        Profile p = new ProfileImpl();
        p.setParameter(Profile.MAIN_HOST, "localhost");
        p.setParameter(Profile.MAIN_PORT, port);
        p.setParameter(Profile.CONTAINER_NAME, nom);
        return rt.createAgentContainer(p);
    }

    private static void taxi(String nom, Object... args) throws StaleProxyException {
        agent(taxis, nom, TaxiAgent.class, args);
    }

    private static void client(String nom, Object... args) throws StaleProxyException {
        agent(clients, nom, ClientAgent.class, args);
    }

    private static void agent(ContainerController c, String nom, Class<?> cls, Object... args) throws StaleProxyException {
        Object[] s = new Object[args.length];
        for (int i = 0; i < args.length; i++) s[i] = String.valueOf(args[i]);
        c.createNewAgent(nom, cls.getName(), s).start();
    }
}
