package communication;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.WakerBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

/**
 * Exercice 4 : l'émetteur envoie un INFORM puis un REQUEST au récepteur
 * et affiche les réponses construites avec createReply().
 * Lancement : jade.Boot -gui -agents "receiver:communication.ReceiverAgent;sender:communication.SenderAgent(receiver)"
 */
public class SenderAgent extends Agent {

    @Override
    protected void setup() {
        Object[] args = getArguments();
        String receiverName = (args != null && args.length > 0) ? args[0].toString() : "receiver";
        AID receiver = new AID(receiverName, AID.ISLOCALNAME);
        long delai = (args != null && args.length > 1) ? Long.parseLong(args[1].toString()) : 1000;

        // On attend un peu pour être sûr que le récepteur est démarré.
        addBehaviour(new WakerBehaviour(this, delai) {
            @Override
            protected void onWake() {
                ACLMessage inform = new ACLMessage(ACLMessage.INFORM);
                inform.addReceiver(receiver);
                inform.setContent("Bonjour, je suis " + getLocalName());
                inform.setProtocol("information");
                inform.setConversationId("salutation");
                send(inform);
                System.out.println("[" + getLocalName() + "] INFORM envoyé : " + inform.getContent());

                ACLMessage request = new ACLMessage(ACLMessage.REQUEST);
                request.addReceiver(receiver);
                request.setContent("heure");
                request.setProtocol("information");
                request.setConversationId("demande-heure");
                send(request);
                System.out.println("[" + getLocalName() + "] REQUEST envoyé : " + request.getContent());
            }
        });

        // Lecture des réponses.
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                ACLMessage msg = receive(MessageTemplate.MatchProtocol("information"));
                if (msg != null) {
                    System.out.println("[" + getLocalName() + "] réponse reçue ("
                            + ACLMessage.getPerformative(msg.getPerformative()) + ", conv="
                            + msg.getConversationId() + ") de " + msg.getSender().getLocalName()
                            + " : " + msg.getContent());
                } else {
                    block();
                }
            }
        });
    }
}
