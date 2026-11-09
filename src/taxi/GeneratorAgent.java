package taxi;

import jade.core.Agent;
import jade.core.behaviours.TickerBehaviour;
import jade.wrapper.AgentController;

import java.util.Locale;
import java.util.Random;

/**
 * Crée des clients aléatoires à intervalle régulier (simulation continue).
 * Arguments : periodeMs, nombre, graine [, prefixe=gen]
 * La graine rend la séquence reproductible, ce qui permet de comparer les expériences.
 */
public class GeneratorAgent extends Agent {

    private static final String[] PROFILS = {"normal", "normal", "presse", "econome"};

    @Override
    protected void setup() {
        Object[] a = getArguments();
        long periode = City.intArg(a, 0, 4000);
        int nombre = City.intArg(a, 1, 10);
        Random rnd = new Random(City.intArg(a, 2, 42));
        String prefixe = City.strArg(a, 3, "gen");
        City.log(getLocalName(), "va créer " + nombre + " client(s), un toutes les " + periode + " ms");

        addBehaviour(new TickerBehaviour(this, periode) {
            private int crees = 0;

            @Override
            protected void onTick() {
                if (City.pause) return;
                if (crees >= nombre) { stop(); City.log(getLocalName(), "génération terminée"); return; }
                int x = rnd.nextInt(City.SIZE), y = rnd.nextInt(City.SIZE), dx, dy;
                do { dx = rnd.nextInt(City.SIZE); dy = rnd.nextInt(City.SIZE); }
                while (City.manhattan(x, y, dx, dy) < 3);
                double budget = 12 + rnd.nextInt(14);
                int attente = 6 + rnd.nextInt(9);
                String profil = PROFILS[rnd.nextInt(PROFILS.length)];
                crees++;
                String nom = prefixe + "-client" + crees;
                Object[] args = {x, y, dx, dy, String.format(Locale.ROOT, "%.0f", budget), attente, 3, 200, profil};
                try {
                    AgentController ac = getContainerController().createNewAgent(nom, ClientAgent.class.getName(), args);
                    ac.start();
                } catch (Exception e) {
                    City.log(getLocalName(), "création impossible : " + e.getMessage());
                }
            }
        });
    }
}
