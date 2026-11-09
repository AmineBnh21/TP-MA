package taxi;

import jade.content.ContentElement;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.Behaviour;
import jade.core.behaviours.CyclicBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import taxi.onto.Affectation;
import taxi.onto.Demande;
import taxi.onto.Offre;
import taxi.onto.Refus;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Dispatcher central, utilisé UNIQUEMENT en mode "centralise" pour comparer avec l'approche
 * décentralisée. Le client ne choisit plus : il envoie une REQUEST et le dispatcher affecte
 * le taxi qui arrive le plus vite (objectif global : réduire l'attente de la flotte),
 * dans les limites du budget et de l'attente du client.
 */
public class DispatcherAgent extends Agent {

    private static final long DELAI_REPONSES_MS = 1500;
    private final Set<String> sessionsActives = new HashSet<>();

    @Override
    protected void setup() {
        Msg.initialiser(this);
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType(City.SERVICE_DISPATCH);
        sd.setName("dispatch");
        dfd.addServices(sd);
        try {
            DFService.register(this, dfd);
        } catch (FIPAException e) {
            log("échec inscription DF : " + e.getMessage());
        }
        log("dispatcher central prêt (mode centralisé)");

        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                ACLMessage m = receive(MessageTemplate.and(MessageTemplate.MatchProtocol(City.PROTOCOL),
                        MessageTemplate.MatchPerformative(ACLMessage.REQUEST)));
                if (m != null) {
                    ContentElement c = Msg.lire(DispatcherAgent.this, m);
                    if (c instanceof Demande) {
                        sessionsActives.add(m.getConversationId());
                        addBehaviour(new Session(m, (Demande) c));
                    }
                    return;
                }
                // offres tardives d'une session terminée : on les rejette
                ACLMessage tard = receive(new MessageTemplate(msg -> msg.getPerformative() == ACLMessage.PROPOSE
                        && !sessionsActives.contains(msg.getConversationId())));
                if (tard != null) {
                    Msg.envoyer(DispatcherAgent.this, Msg.repondre(tard, ACLMessage.REJECT_PROPOSAL), null);
                    return;
                }
                block();
            }
        });
    }

    @Override
    protected void takeDown() {
        try { DFService.deregister(this); } catch (FIPAException ignored) { }
    }

    /** Traitement d'une demande : CFP aux taxis, choix global, confirmation au client. */
    private class Session extends Behaviour {
        private final ACLMessage requete;
        private final Demande demande;
        private final String conv;
        private final MessageTemplate mt;
        private final List<ACLMessage> propositions = new ArrayList<>();
        private int attendues, recues;
        private long echeance;
        private int etape = 0;       // 0 = collecte des offres, 1 = attente de la confirmation du taxi
        private ACLMessage elue;
        private boolean fini = false;

        Session(ACLMessage requete, Demande d) {
            super(DispatcherAgent.this);
            this.requete = requete;
            this.demande = d;
            this.conv = requete.getConversationId();
            this.mt = MessageTemplate.and(MessageTemplate.MatchConversationId(conv),
                    MessageTemplate.not(MessageTemplate.MatchPerformative(ACLMessage.REQUEST)));
        }

        @Override
        public void onStart() {
            AID[] taxis = chercherTaxis();
            String client = requete.getSender().getLocalName();
            if (taxis.length == 0) {
                repondreClient(ACLMessage.REFUSE, "aucun-taxi");
                log("aucun taxi libre pour " + client);
                terminer();
                return;
            }
            ACLMessage cfp = Msg.nouveau(ACLMessage.CFP, conv);
            for (AID t : taxis) cfp.addReceiver(t);
            Msg.envoyer(DispatcherAgent.this, cfp, demande);
            attendues = taxis.length;
            echeance = System.currentTimeMillis() + DELAI_REPONSES_MS;
            log("demande de " + client + " → CFP à " + taxis.length + " taxi(s)");
        }

        @Override
        public void action() {
            if (fini) return;
            ACLMessage m = receive(mt);
            if (etape == 0) {
                if (m != null) {
                    recues++;
                    if (m.getPerformative() == ACLMessage.PROPOSE) propositions.add(m);
                }
                if (recues < attendues && System.currentTimeMillis() < echeance) {
                    if (m == null) block(Math.max(1, echeance - System.currentTimeMillis()));
                    return;
                }
                choisir();
            } else {
                if (m == null) {
                    if (System.currentTimeMillis() > echeance) {
                        repondreClient(ACLMessage.FAILURE, "taxi-muet");
                        terminer();
                    } else block(Math.max(1, echeance - System.currentTimeMillis()));
                    return;
                }
                if (m.getPerformative() == ACLMessage.INFORM) {
                    Offre o = (Offre) Msg.lire(DispatcherAgent.this, elue);
                    Affectation af = new Affectation();
                    af.setTaxi(elue.getSender().getName());
                    af.setPrix(o.getPrix());
                    af.setEta(o.getEta());
                    af.setCovoiturage(o.isCovoiturage());
                    ACLMessage r = Msg.repondre(requete, ACLMessage.INFORM);
                    Msg.envoyer(DispatcherAgent.this, r, af);
                    log("affecte " + elue.getSender().getLocalName() + " à " + requete.getSender().getLocalName());
                    terminer();
                } else if (m.getPerformative() == ACLMessage.FAILURE) {
                    repondreClient(ACLMessage.FAILURE, "taxi-indisponible");
                    terminer();
                }
            }
        }

        /** Critère global : le taxi qui arrive le plus vite (puis le moins cher). */
        private void choisir() {
            elue = null;
            Offre meilleure = null;
            for (ACLMessage p : propositions) {
                ContentElement c = Msg.lire(DispatcherAgent.this, p);
                if (!(c instanceof Offre)) continue;
                Offre o = (Offre) c;
                if (o.getPrix() > demande.getBudget() || o.getEta() > demande.getAttenteMax()) continue;
                if (meilleure == null || o.getEta() < meilleure.getEta()
                        || (o.getEta() == meilleure.getEta() && o.getPrix() < meilleure.getPrix())) {
                    meilleure = o;
                    elue = p;
                }
            }
            for (ACLMessage p : propositions) {
                if (p != elue) Msg.envoyer(DispatcherAgent.this, Msg.repondre(p, ACLMessage.REJECT_PROPOSAL), null);
            }
            if (elue == null) {
                repondreClient(ACLMessage.REFUSE, "aucune-offre-compatible");
                log("aucune offre compatible pour " + requete.getSender().getLocalName());
                terminer();
                return;
            }
            Msg.envoyer(DispatcherAgent.this, Msg.repondre(elue, ACLMessage.ACCEPT_PROPOSAL), null);
            log(String.format(Locale.ROOT, "choisit %s pour %s (attente=%d, prix=%.2f)", elue.getSender().getLocalName(),
                    requete.getSender().getLocalName(), meilleure.getEta(), meilleure.getPrix()));
            etape = 1;
            echeance = System.currentTimeMillis() + DELAI_REPONSES_MS;
        }

        private void repondreClient(int perf, String raison) {
            Refus r = new Refus();
            r.setRaison(raison);
            Msg.envoyer(DispatcherAgent.this, Msg.repondre(requete, perf), r);
        }

        private void terminer() {
            fini = true;
            sessionsActives.remove(conv);
        }

        @Override
        public boolean done() { return fini; }
    }

    private AID[] chercherTaxis() {
        Set<AID> res = new LinkedHashSet<>();
        for (String type : new String[]{City.SERVICE_TYPE, City.SERVICE_COVOIT}) {
            DFAgentDescription modele = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            sd.setType(type);
            modele.addServices(sd);
            try {
                for (DFAgentDescription d : DFService.search(this, modele)) res.add(d.getName());
            } catch (FIPAException e) {
                log("erreur DF : " + e.getMessage());
            }
        }
        return res.toArray(new AID[0]);
    }

    private void log(String s) {
        City.log(getLocalName(), s);
    }
}
