package taxi;

import jade.core.Agent;
import jade.core.behaviours.TickerBehaviour;
import jade.core.behaviours.WakerBehaviour;

/**
 * Observe la simulation : résumé périodique, bilan final et export CSV dans results/.
 * Arguments : dureeSec (0 = jusqu'à l'arrêt de la plateforme), label
 * Avec une durée > 0, l'agent écrit le bilan puis arrête la JVM (utile pour les expériences en série).
 */
public class StatsAgent extends Agent {

    private String label;
    private boolean exporte = false;

    @Override
    protected void setup() {
        Object[] a = getArguments();
        int duree = City.intArg(a, 0, 0);
        label = City.strArg(a, 1, "simulation");

        addBehaviour(new TickerBehaviour(this, 10000) {
            @Override
            protected void onTick() {
                City.log("stats", Stats.resumeCourt());
            }
        });

        if (duree > 0) {
            addBehaviour(new WakerBehaviour(this, duree * 1000L) {
                @Override
                protected void onWake() {
                    exporter();
                    System.out.flush();
                    new Thread(() -> {
                        try { Thread.sleep(300); } catch (InterruptedException ignored) { }
                        System.exit(0);
                    }).start();
                }
            });
        }
    }

    @Override
    protected void takeDown() {
        exporter();
    }

    private synchronized void exporter() {
        if (exporte) return;
        exporte = true;
        System.out.println(Stats.resumeComplet());
        CityView.journal(Stats.resumeComplet());
        Stats.exporterSynthese(label);
        Stats.exporterClients(label);
        System.out.println("CSV écrits : results/experience.csv et results/clients-" + label + ".csv");
    }
}
