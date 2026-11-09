package taxi;

import jade.content.ContentElement;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.Behaviour;
import jade.core.behaviours.FSMBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import taxi.onto.Affectation;
import taxi.onto.ContreOffre;
import taxi.onto.Demande;
import taxi.onto.EtatCourse;
import taxi.onto.Offre;
import taxi.onto.Paiement;
import taxi.onto.Refus;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Agent Client (architecture BDI), dont les intentions forment un FSMBehaviour JADE.
 *
 * Arguments : x, y, destX, destY, budget, attenteMax [, relancesMax=3 [, delaiDepartMs=1000 [, profil=normal]]]
 *   ex. client1:taxi.ClientAgent(2,3,8,8,30,6,3,1000,presse)
 *
 * Croyances  : position, destination, budget, attente maximale, offres reçues, nombre d'échecs.
 * Désir      : arriver à destination en payant peu et en attendant peu, avec un poids qui dépend
 *              du profil (presse : l'attente compte beaucoup ; econome : le prix compte surtout).
 *              Les désirs sont révisés après plusieurs échecs (attente max x1.5, budget +20 %).
 * Intentions : CHERCHER → CHOISIR → [NEGOCIER] → ATTENDRE → VOYAGER → ARRIVE,
 *              ou RELANCER, puis ABANDONNER.
 */
public class ClientAgent extends Agent {

    // ---------------- noms des états (intentions) ----------------
    private static final String CHERCHER = "CHERCHER", CHOISIR = "CHOISIR", NEGOCIER = "NEGOCIER",
            ATTENDRE = "ATTENDRE", VOYAGER = "VOYAGER", RELANCER = "RELANCER",
            ABANDONNER = "ABANDONNER", ARRIVE = "ARRIVE";

    // ---------------- événements (transitions) ----------------
    private static final int E_TAXIS = 1, E_CHOIX = 2, E_NEGO = 3, E_RELANCE = 4, E_PRIS = 5,
            E_ARRIVE = 6, E_ABANDON = 7, E_CHERCHER = 8, E_ATTENTE = 9;

    private static final long DELAI_REPONSES_MS = 1500;
    private static final long DELAI_RELANCE_MS = 3000;
    private static final long DELAI_DISPATCH_MS = 4000;
    private static final int RELANCES_AVANT_REVISION = 2;
    private static final double MARGE_NEGOCIATION = 1.4;   // négocie si l'offre <= budget x 1.4

    // ---------------- Croyances ----------------
    private int x, y, destX, destY;
    private double budget;
    private double tolerance = 1.0;        // fraction du budget qu'il accepte de payer
    private int attenteMax;
    private int relancesMax;
    private long delaiDepart;
    private String profil;
    private double poidsAttente;
    private final List<Proposition> offres = new ArrayList<>();
    private final Set<String> intentionsVisitees = new LinkedHashSet<>();

    // état du cycle
    private String intention = "";
    private String conv;
    private int tentative = 0, relances = 0, cfpEnvoyes = 0;
    private boolean demarrage = true, revise = false, negocie = false, covoiturage = false, enRoute = false;
    private AID taxiChoisi;
    private double prixConvenu;
    private int attenteMesuree;
    private long echeance;

    private static final class Proposition {
        final AID taxi; final double prix; final int eta; final boolean covoit; final ACLMessage msg;
        Proposition(AID taxi, Offre o, ACLMessage msg) {
            this.taxi = taxi; this.prix = o.getPrix(); this.eta = o.getEta();
            this.covoit = o.isCovoiturage(); this.msg = msg;
        }
    }

    @Override
    protected void setup() {
        Msg.initialiser(this);
        Object[] a = getArguments();
        x = City.clamp(City.intArg(a, 0, 0));
        y = City.clamp(City.intArg(a, 1, 0));
        destX = City.clamp(City.intArg(a, 2, 9));
        destY = City.clamp(City.intArg(a, 3, 9));
        budget = City.doubleArg(a, 4, 20);
        attenteMax = City.intArg(a, 5, 8);
        relancesMax = City.intArg(a, 6, 3);
        delaiDepart = City.intArg(a, 7, 1000);
        profil = City.strArg(a, 8, "normal");
        switch (profil) {
            case "presse":  poidsAttente = 2.0; break;
            case "econome": poidsAttente = 0.1; break;
            default:        poidsAttente = 0.5; profil = "normal";
        }

        log(String.format(Locale.ROOT, "profil %s : en %s, veut aller en %s, budget %.2f, attente max %d ticks (poids attente %.1f)",
                profil, City.pos(x, y), City.pos(destX, destY), budget, attenteMax, poidsAttente));
        Stats.clientCree();
        CityView.client(getLocalName(), x, y, destX, destY, "RELANCER");

        FSMBehaviour fsm = new FSMBehaviour(this);
        fsm.registerFirstState(new Relancer(), RELANCER);
        fsm.registerState(new Chercher(), CHERCHER);
        fsm.registerState(new Choisir(), CHOISIR);
        fsm.registerState(new Negocier(), NEGOCIER);
        fsm.registerState(new Attendre(), ATTENDRE);
        fsm.registerState(new Voyager(), VOYAGER);
        fsm.registerLastState(new Fin("ARRIVE"), ARRIVE);
        fsm.registerLastState(new Fin("ABANDON"), ABANDONNER);

        transition(fsm, RELANCER, CHERCHER, E_CHERCHER);
        transition(fsm, RELANCER, ABANDONNER, E_ABANDON);
        transition(fsm, CHERCHER, CHOISIR, E_TAXIS);
        transition(fsm, CHERCHER, ATTENDRE, E_ATTENTE);
        transition(fsm, CHERCHER, RELANCER, E_RELANCE);
        transition(fsm, CHOISIR, ATTENDRE, E_CHOIX);
        transition(fsm, CHOISIR, NEGOCIER, E_NEGO);
        transition(fsm, CHOISIR, RELANCER, E_RELANCE);
        transition(fsm, NEGOCIER, ATTENDRE, E_CHOIX);
        transition(fsm, NEGOCIER, RELANCER, E_RELANCE);
        transition(fsm, ATTENDRE, VOYAGER, E_PRIS);
        transition(fsm, ATTENDRE, RELANCER, E_RELANCE);
        transition(fsm, VOYAGER, ARRIVE, E_ARRIVE);
        addBehaviour(fsm);
    }

    /** Chaque transition remet l'état d'arrivée à zéro (onStart rappelé). */
    private static void transition(FSMBehaviour fsm, String de, String vers, int evt) {
        fsm.registerTransition(de, vers, evt, new String[]{vers});
    }

    @Override
    protected void takeDown() {
        log("quitte la plateforme (intentions parcourues : " + String.join(" → ", intentionsVisitees) + ")");
    }

    // =====================================================================
    //  Base commune des états de l'automate
    // =====================================================================

    private abstract class EtatBDI extends Behaviour {
        private final String nom;
        private boolean fini;
        private int evt;

        EtatBDI(String nom) { super(ClientAgent.this); this.nom = nom; }

        @Override
        public void onStart() {
            fini = false;
            changerIntention(nom);
            entrer();
        }

        @Override
        public void action() {
            if (!fini) agir();
        }

        protected void entrer() {}
        protected abstract void agir();

        protected void finir(int evenement) { evt = evenement; fini = true; }

        @Override
        public boolean done() { return fini; }

        @Override
        public int onEnd() { return evt; }

        protected void attendreJusqua(long t) {
            long reste = t - System.currentTimeMillis();
            block(Math.max(1, reste));
        }
    }

    private void changerIntention(String nouvelle) {
        if (!nouvelle.equals(intention)) {
            if (!intention.isEmpty()) log("intention : " + intention + " → " + nouvelle);
            intention = nouvelle;
            intentionsVisitees.add(nouvelle);
        }
        CityView.client(getLocalName(), x, y, destX, destY, nouvelle);
    }

    // ---------- RELANCER : attente avant (re)tentative, révision des désirs ----------
    private class Relancer extends EtatBDI {
        Relancer() { super(RELANCER); }

        @Override
        protected void entrer() {
            if (demarrage) {
                demarrage = false;
                echeance = System.currentTimeMillis() + delaiDepart;
                return;
            }
            relances++;
            if (relances > relancesMax) { finir(E_ABANDON); return; }
            if (relances == RELANCES_AVANT_REVISION && !revise) reviserDesirs();
            log("relance " + relances + "/" + relancesMax + " dans " + DELAI_RELANCE_MS + " ms");
            echeance = System.currentTimeMillis() + DELAI_RELANCE_MS;
        }

        @Override
        protected void agir() {
            ACLMessage m;
            while ((m = recevoir()) != null) rejeterSiOffre(m);   // réponses tardives
            if (System.currentTimeMillis() >= echeance) finir(E_CHERCHER);
            else attendreJusqua(echeance);
        }
    }

    /** Révision des désirs : après plusieurs échecs, le client assouplit ses exigences. */
    private void reviserDesirs() {
        revise = true;
        int ancienneAttente = attenteMax;
        attenteMax = (int) Math.ceil(attenteMax * 1.5);
        tolerance = 1.2;
        log(String.format(Locale.ROOT, "RÉVISION DES DÉSIRS : attente max %d → %d, prix accepté jusqu'à %.2f (budget +20%%)",
                ancienneAttente, attenteMax, budget * tolerance));
    }

    // ---------- CHERCHER : DF puis CFP (ou REQUEST au dispatcher) ----------
    private class Chercher extends EtatBDI {
        Chercher() { super(CHERCHER); }

        @Override
        protected void agir() {
            tentative++;
            conv = getLocalName() + "-demande-" + tentative;
            offres.clear();
            taxiChoisi = null;
            enRoute = false;
            covoiturage = false;

            if (City.centralise()) {
                AID[] disp = chercher(City.SERVICE_DISPATCH);
                if (disp.length == 0) { log("aucun dispatcher"); finir(E_RELANCE); return; }
                ACLMessage req = Msg.nouveau(ACLMessage.REQUEST, conv);
                req.addReceiver(disp[0]);
                Msg.envoyer(ClientAgent.this, req, demande());
                log("REQUEST envoyée au dispatcher " + disp[0].getLocalName());
                echeance = System.currentTimeMillis() + DELAI_DISPATCH_MS;
                finir(E_ATTENTE);
                return;
            }

            AID[] taxis = chercher(City.SERVICE_TYPE, City.SERVICE_COVOIT);
            if (taxis.length == 0) {
                log("aucun taxi disponible dans le DF");
                finir(E_RELANCE);
                return;
            }
            ACLMessage cfp = Msg.nouveau(ACLMessage.CFP, conv);
            for (AID t : taxis) cfp.addReceiver(t);
            cfp.setReplyWith(conv + "-" + System.currentTimeMillis());
            Msg.envoyer(ClientAgent.this, cfp, demande());
            cfpEnvoyes = taxis.length;
            log("CFP envoyé à " + taxis.length + " taxi(s) : pos=" + City.pos(x, y) + " dest=" + City.pos(destX, destY));
            finir(E_TAXIS);
        }
    }

    private Demande demande() {
        Demande d = new Demande();
        d.setClient(getAID().getName());
        d.setX(x); d.setY(y); d.setDestX(destX); d.setDestY(destY);
        d.setBudget(City.arrondi(budget * tolerance));
        d.setAttenteMax(attenteMax);
        return d;
    }

    // ---------- CHOISIR : collecter les offres puis délibérer ----------
    private class Choisir extends EtatBDI {
        private int recues;

        Choisir() { super(CHOISIR); }

        @Override
        protected void entrer() {
            recues = 0;
            echeance = System.currentTimeMillis() + DELAI_REPONSES_MS;
        }

        @Override
        protected void agir() {
            ACLMessage msg = recevoir();
            if (msg != null) {
                recues++;
                ContentElement c = Msg.lire(ClientAgent.this, msg);
                if (msg.getPerformative() == ACLMessage.PROPOSE && c instanceof Offre) {
                    Proposition p = new Proposition(msg.getSender(), (Offre) c, msg);
                    offres.add(p);
                    log(String.format(Locale.ROOT, "offre de %s : prix=%.2f, attente=%d%s", p.taxi.getLocalName(),
                            p.prix, p.eta, p.covoit ? " (covoiturage)" : ""));
                } else if (msg.getPerformative() == ACLMessage.REFUSE) {
                    log("refus de " + msg.getSender().getLocalName()
                            + (c instanceof Refus ? " (" + ((Refus) c).getRaison() + ")" : ""));
                }
            }
            boolean toutRecu = recues >= cfpEnvoyes;
            if (!toutRecu && System.currentTimeMillis() < echeance) {
                if (msg == null) attendreJusqua(echeance);
                return;
            }
            finir(deliberer());
        }
    }

    private double cout(Proposition p) {
        return p.prix + poidsAttente * p.eta;
    }

    /** Délibération : filtre (budget, attente) puis maximise l'utilité selon le profil. */
    private int deliberer() {
        double budgetMax = budget * tolerance;
        Proposition meilleure = null;
        for (Proposition p : offres) {
            boolean okAttente = p.eta <= attenteMax, okPrix = p.prix <= budgetMax;
            if (!okAttente || !okPrix) {
                log("écarte " + p.taxi.getLocalName() + " (" + (!okAttente ? "attente trop longue" : "trop cher") + ")");
                continue;
            }
            if (meilleure == null || cout(p) < cout(meilleure)) meilleure = p;
        }
        if (meilleure != null) {
            repondreATous(meilleure, ACLMessage.ACCEPT_PROPOSAL);
            taxiChoisi = meilleure.taxi;
            prixConvenu = meilleure.prix;
            covoiturage = meilleure.covoit;
            log(String.format(Locale.ROOT, "CHOISIT %s (prix=%.2f, attente=%d, coût perçu=%.2f selon profil %s)",
                    taxiChoisi.getLocalName(), meilleure.prix, meilleure.eta, cout(meilleure), profil));
            echeance = System.currentTimeMillis() + DELAI_REPONSES_MS;
            return E_CHOIX;
        }
        // Pas d'offre acceptable : peut-on négocier une offre un peu trop chère ?
        Proposition cible = null;
        for (Proposition p : offres) {
            if (p.eta <= attenteMax && p.prix <= budgetMax * MARGE_NEGOCIATION
                    && (cible == null || p.prix < cible.prix)) cible = p;
        }
        if (cible != null) {
            repondreATous(cible, -1);
            taxiChoisi = cible.taxi;
            prixConvenu = City.arrondi(budgetMax);
            covoiturage = cible.covoit;
            ContreOffre co = new ContreOffre();
            co.setPrix(prixConvenu);
            Msg.envoyer(this, Msg.repondre(cible.msg, ACLMessage.PROPOSE), co);
            log(String.format(Locale.ROOT, "NÉGOCIE avec %s : contre-offre %.2f au lieu de %.2f",
                    cible.taxi.getLocalName(), prixConvenu, cible.prix));
            return E_NEGO;
        }
        repondreATous(null, -1);
        log("aucune offre acceptable");
        return E_RELANCE;
    }

    /** ACCEPT (ou rien si perfChoisie = -1) à l'élu, REJECT aux autres. */
    private void repondreATous(Proposition elue, int perfChoisie) {
        for (Proposition p : offres) {
            if (p == elue) {
                if (perfChoisie >= 0) Msg.envoyer(this, Msg.repondre(p.msg, perfChoisie), null);
            } else {
                Msg.envoyer(this, Msg.repondre(p.msg, ACLMessage.REJECT_PROPOSAL), null);
            }
        }
    }

    // ---------- NEGOCIER : attendre la réponse à la contre-offre ----------
    private class Negocier extends EtatBDI {
        Negocier() { super(NEGOCIER); }

        @Override
        protected void entrer() { echeance = System.currentTimeMillis() + DELAI_REPONSES_MS; }

        @Override
        protected void agir() {
            ACLMessage msg = recevoir();
            if (msg == null) {
                if (System.currentTimeMillis() > echeance) { log("pas de réponse à la contre-offre"); finir(E_RELANCE); }
                else attendreJusqua(echeance);
                return;
            }
            ContentElement c = Msg.lire(ClientAgent.this, msg);
            if (msg.getPerformative() == ACLMessage.INFORM && c instanceof EtatCourse
                    && "en-route".equals(((EtatCourse) c).getEtat())) {
                negocie = true;
                enRoute = true;
                log(String.format(Locale.ROOT, "contre-offre ACCEPTÉE par %s : %.2f, arrivée dans %d ticks",
                        taxiChoisi.getLocalName(), prixConvenu, ((EtatCourse) c).getEta()));
                finir(E_CHOIX);
            } else if (msg.getPerformative() == ACLMessage.REJECT_PROPOSAL || msg.getPerformative() == ACLMessage.FAILURE) {
                log("contre-offre refusée par " + msg.getSender().getLocalName());
                finir(E_RELANCE);
            }
        }
    }

    // ---------- ATTENDRE : le taxi vient nous chercher ----------
    private class Attendre extends EtatBDI {
        Attendre() { super(ATTENDRE); }

        @Override
        protected void agir() {
            ACLMessage msg = recevoir();
            if (msg == null) {
                if (!enRoute && System.currentTimeMillis() > echeance) {
                    log("pas de confirmation" + (taxiChoisi != null ? " de " + taxiChoisi.getLocalName() : ""));
                    finir(E_RELANCE);
                } else if (!enRoute) {
                    attendreJusqua(echeance);
                } else {
                    block();
                }
                return;
            }
            ContentElement c = Msg.lire(ClientAgent.this, msg);
            int perf = msg.getPerformative();
            if (perf == ACLMessage.FAILURE || perf == ACLMessage.REFUSE) {
                log(msg.getSender().getLocalName() + " : échec ("
                        + (c instanceof Refus ? ((Refus) c).getRaison() : "?") + ")");
                finir(E_RELANCE);
                return;
            }
            if (c instanceof Affectation) {
                Affectation af = (Affectation) c;
                taxiChoisi = Msg.aid(af.getTaxi());
                prixConvenu = af.getPrix();
                covoiturage = af.isCovoiturage();
                log(String.format(Locale.ROOT, "le dispatcher m'affecte %s (prix=%.2f, attente=%d)",
                        taxiChoisi.getLocalName(), af.getPrix(), af.getEta()));
                enRoute = true;
            } else if (c instanceof EtatCourse) {
                EtatCourse ec = (EtatCourse) c;
                if ("en-route".equals(ec.getEtat())) {
                    enRoute = true;
                    if (taxiChoisi == null) taxiChoisi = msg.getSender();
                    log(taxiChoisi.getLocalName() + " arrive dans " + ec.getEta() + " tick(s)");
                } else if ("pris-en-charge".equals(ec.getEtat())) {
                    attenteMesuree = ec.getAttente();
                    log("pris en charge par " + msg.getSender().getLocalName() + " après " + attenteMesuree + " tick(s) d'attente");
                    finir(E_PRIS);
                }
            }
        }
    }

    // ---------- VOYAGER : trajet puis paiement ----------
    private class Voyager extends EtatBDI {
        Voyager() { super(VOYAGER); }

        @Override
        protected void agir() {
            ACLMessage msg = recevoir();
            if (msg == null) { block(); return; }
            ContentElement c = Msg.lire(ClientAgent.this, msg);
            if (c instanceof EtatCourse && "arrive".equals(((EtatCourse) c).getEtat())) {
                double prix = ((EtatCourse) c).getPrix();
                Paiement p = new Paiement();
                p.setMontant(prix);
                Msg.envoyer(ClientAgent.this, Msg.repondre(msg, ACLMessage.INFORM), p);
                prixConvenu = prix;
                budget -= prix;
                x = destX;
                y = destY;
                log(String.format(Locale.ROOT, "arrivé en %s, payé %.2f, budget restant %.2f",
                        City.pos(x, y), prix, budget));
                finir(E_ARRIVE);
            }
        }
    }

    // ---------- ARRIVE / ABANDONNER (états finaux) ----------
    private class Fin extends EtatBDI {
        private final String statut;

        Fin(String statut) { super("ARRIVE".equals(statut) ? ARRIVE : ABANDONNER); this.statut = statut; }

        @Override
        protected void agir() {
            if ("ABANDON".equals(statut)) log("ABANDON : aucune solution après " + relancesMax + " relance(s)");
            Stats.ResultatClient r = new Stats.ResultatClient();
            r.nom = getLocalName(); r.profil = profil; r.statut = statut;
            r.attente = attenteMesuree; r.prix = "ARRIVE".equals(statut) ? prixConvenu : 0;
            r.relances = relances; r.negocie = negocie; r.covoiturage = covoiturage; r.revise = revise;
            Stats.client(r);
            finir(0);
            doDelete();
        }
    }

    // =====================================================================

    /**
     * Lit un message du protocole taxi pour la conversation courante. Les messages
     * d'anciennes conversations (offres arrivées trop tard) sont rejetés poliment puis ignorés.
     */
    private ACLMessage recevoir() {
        ACLMessage msg;
        while ((msg = receive(MessageTemplate.MatchProtocol(City.PROTOCOL))) != null) {
            if (conv != null && conv.equals(msg.getConversationId())) return msg;
            rejeterSiOffre(msg);
        }
        return null;
    }

    private void rejeterSiOffre(ACLMessage m) {
        if (m.getPerformative() == ACLMessage.PROPOSE) {
            Msg.envoyer(this, Msg.repondre(m, ACLMessage.REJECT_PROPOSAL), null);
        }
    }

    private AID[] chercher(String... types) {
        Set<AID> res = new LinkedHashSet<>();
        for (String type : types) {
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
