package taxi;

import jade.content.ContentElement;
import jade.content.lang.Codec;
import jade.content.lang.sl.SLCodec;
import jade.core.AID;
import jade.core.Agent;
import jade.lang.acl.ACLMessage;
import taxi.onto.TaxiOntology;

/** Petits utilitaires pour envoyer et lire des messages au format SL + ontologie taxi. */
public final class Msg {

    private static final Codec CODEC = new SLCodec();

    private Msg() {}

    /** À appeler dans setup() : enregistre le langage SL et l'ontologie taxi. */
    public static void initialiser(Agent a) {
        a.getContentManager().registerLanguage(CODEC);
        a.getContentManager().registerOntology(TaxiOntology.getInstance());
    }

    public static ACLMessage nouveau(int performatif, String conv) {
        ACLMessage m = new ACLMessage(performatif);
        m.setProtocol(City.PROTOCOL);
        m.setConversationId(conv);
        preparer(m);
        return m;
    }

    private static void preparer(ACLMessage m) {
        m.setLanguage(CODEC.getName());
        m.setOntology(TaxiOntology.NAME);
    }

    /** Remplit le contenu, puis envoie le message. */
    public static void envoyer(Agent a, ACLMessage m, ContentElement contenu) {
        preparer(m);
        if (contenu != null) {
            try {
                a.getContentManager().fillContent(m, contenu);
            } catch (Exception e) {
                throw new IllegalStateException("Encodage SL impossible : " + e.getMessage(), e);
            }
        }
        a.send(m);
    }

    /** Lit le prédicat contenu dans le message, ou null s'il n'y en a pas. */
    public static ContentElement lire(Agent a, ACLMessage m) {
        if (m.getContent() == null || m.getContent().isEmpty()) return null;
        try {
            return a.getContentManager().extractContent(m);
        } catch (Exception e) {
            City.log(a.getLocalName(), "contenu illisible (" + e.getMessage() + ") : " + m.getContent());
            return null;
        }
    }

    public static ACLMessage repondre(ACLMessage recu, int performatif) {
        ACLMessage r = recu.createReply();
        r.setPerformative(performatif);
        r.setProtocol(City.PROTOCOL);
        return r;
    }

    public static AID aid(String nomComplet) {
        return new AID(nomComplet, AID.ISGUID);
    }
}
