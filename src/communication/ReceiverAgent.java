package communication;

import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Exercice 4 : le récepteur répond à chaque message avec createReply().
 * Le destinataire, la conversation et le protocole sont remplis automatiquement.
 */
public class ReceiverAgent extends Agent {

    @Override
    protected void setup() {
        System.out.println("[" + getLocalName() + "] prêt à recevoir des messages.");

        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                ACLMessage msg = receive();
                if (msg == null) {
                    block();
                    return;
                }
                System.out.println("[" + getLocalName() + "] reçu "
                        + ACLMessage.getPerformative(msg.getPerformative())
                        + " de " + msg.getSender().getLocalName() + " : " + msg.getContent());

                ACLMessage reply = msg.createReply();
                switch (msg.getPerformative()) {
                    case ACLMessage.INFORM:
                        reply.setPerformative(ACLMessage.CONFIRM);
                        reply.setContent("Message bien reçu, bonjour " + msg.getSender().getLocalName());
                        break;
                    case ACLMessage.REQUEST:
                        if ("heure".equals(msg.getContent())) {
                            reply.setPerformative(ACLMessage.INFORM);
                            reply.setContent("Il est " + LocalTime.now()
                                    .format(DateTimeFormatter.ofPattern("HH:mm:ss")));
                        } else {
                            reply.setPerformative(ACLMessage.REFUSE);
                            reply.setContent("Requête inconnue");
                        }
                        break;
                    default:
                        reply.setPerformative(ACLMessage.NOT_UNDERSTOOD);
                        reply.setContent("Performatif non géré");
                }
                send(reply);
            }
        });
    }
}
