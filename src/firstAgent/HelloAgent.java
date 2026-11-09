package firstAgent;

import jade.core.Agent;

/**
 * Exercice 3 : premier agent "cordial".
 * Lancement : jade.Boot -gui -agents "hello:firstAgent.HelloAgent(Amine)"
 */
public class HelloAgent extends Agent {

    @Override
    protected void setup() {
        System.out.println("Bonjour ! Je suis l'agent " + getLocalName()
                + " (AID complet : " + getAID().getName() + ")");

        Object[] args = getArguments();
        if (args != null && args.length > 0) {
            System.out.println(getLocalName() + " : mes arguments sont :");
            for (Object a : args) {
                System.out.println("  - " + a);
            }
        }
        // L'agent a fini son travail : il se supprime.
        doDelete();
    }

    @Override
    protected void takeDown() {
        System.out.println("Au revoir, l'agent " + getLocalName() + " se termine.");
    }
}
