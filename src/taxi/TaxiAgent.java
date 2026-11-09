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
import taxi.onto.ContreOffre;
import taxi.onto.Demande;
import taxi.onto.EtatCourse;
import taxi.onto.Offre;
import taxi.onto.Paiement;
import taxi.onto.Refus;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Agent Taxi (architecture BDI).
 *
 * Arguments : x, y, priseEnCharge, prixParKm, carburant [, capacite=40]
 *   ex. taxi1:taxi.TaxiAgent(0,0,3,1.5,40)
 *
 * Croyances  : position, état, carburant, tarif, gains, demande observée, offres en attente,
 *              passagers à bord, plan en cours.
 * Désirs     : gagner de l'argent (utilité de chaque course) tout en restant opérationnel
 *              (carburant, risque de panne).
 * Intentions : un PLAN, c'est-à-dire une liste d'étapes (PRISE, DEPOSE, STATION, REPOS) exécutée
 *              case par case. Les intentions en découlent : proposer ou refuser, aller chercher,
 *              transporter (y compris en covoiturage), se ravitailler, se repositionner.
 */
public class TaxiAgent extends Agent {

    public enum Etat { LIBRE, REPOSITIONNEMENT, VERS_CLIENT, EN_COURSE, RAVITAILLEMENT, PANNE }

    private enum TypeEtape { PRISE, DEPOSE, STATION, REPOS }

    private static final class Etape {
        final TypeEtape type; final int x, y; final AID client; final String conv; final double prix;
        final long tickEngagement; final boolean covoiturage;
        int duree;   // ticks à passer sur place (ravitaillement)
        Etape(TypeEtape type, int x, int y, AID client, String conv, double prix, long tick, boolean covoit, int duree) {
            this.type = type; this.x = x; this.y = y; this.client = client; this.conv = conv;
            this.prix = prix; this.tickEngagement = tick; this.covoiturage = covoit; this.duree = duree;
        }
    }

    private static final class OffreEnAttente {
        final Demande demande; final AID client; final double prix; final int eta; final boolean covoiturage;
        OffreEnAttente(Demande d, AID client, double prix, int eta, boolean covoit) {
            this.demande = d; this.client = client; this.prix = prix; this.eta = eta; this.covoiturage = covoit;
        }
    }

    /** Résultat de la délibération sur une demande. */
    private static final class Evaluation {
        boolean ok; String raison; double prix, utilite, mult; int eta, besoin; boolean covoiturage;
        static Evaluation refus(String raison) { Evaluation e = new Evaluation(); e.raison = raison; return e; }
    }

    // ---------------- paramètres de délibération ----------------
    private static final double SEUIL_RESERVE = 0.25;   // sous 25 % : aller faire le plein
    private static final double COUT_CASE = 0.3;         // coût d'exploitation d'une case
    private static final double SEUIL_RISQUE = 0.30;     // sous 30 % après la course : pénalité de risque
    private static final int DETOUR_MAX = 3;             // détour max accepté en covoiturage (ticks)
    private static final double REMISE_COVOIT = 0.7;
    private static final double SEUIL_CONTRE_OFFRE = 0.8; // accepte une contre-offre >= 80 % de son prix
    private static final int TICKS_RAVITAILLEMENT = 3;
    private static final int TICKS_DEPANNAGE = 10;
    private static final int INACTIVITE_REPOSITION = 15;
    private static final long DUREE_RESERVATION_MS = 2000;

    // ---------------- Croyances ----------------
    private int x, y;
    private int carburant, capacite;
    private double priseEnCharge, prixParKm;
    private double gains = 0;
    private int courses = 0;
    private boolean panne = false;
    private int ticksPanne = 0;

    private final List<Etape> plan = new ArrayList<>();
    private List<int[]> route = null;
    private int attenteCase = 0;          // ticks restants pour traverser une case embouteillée
    private final Map<String, AID> aBord = new LinkedHashMap<>();

    private long ticks = 0, ticksOccupe = 0, dernierOccupe = 0;
    private final Deque<Object[]> cfpRecents = new ArrayDeque<>();   // {tick, client}
    private final Deque<int[]> demandesVues = new ArrayDeque<>();

    private final Map<String, OffreEnAttente> offres = new HashMap<>();
    private String reservationConv = null;
    private long reservationFin = 0;

    private String serviceDF = null;

    @Override
    protected void setup() {
        Msg.initialiser(this);
        Object[] a = getArguments();
        x = City.clamp(City.intArg(a, 0, 0));
        y = City.clamp(City.intArg(a, 1, 0));
        priseEnCharge = City.doubleArg(a, 2, 3.0);
        prixParKm = City.doubleArg(a, 3, 1.5);
        carburant = City.intArg(a, 4, 40);
        capacite = Math.max(carburant, City.intArg(a, 5, 40));

        log("démarre en " + City.pos(x, y) + ", tarif " + priseEnCharge + " + " + prixParKm
                + "/km, carburant " + carburant + "/" + capacite);

        addBehaviour(new GestionMessages());
        addBehaviour(new Execution());

        if (carburantBas()) seRavitailler();   // intention prioritaire : rester opérationnel
        majDF();
    }

    @Override
    protected void takeDown() {
        desinscrireDF();
        publierStats();
        log(String.format(Locale.ROOT, "termine : %d course(s), gains = %.2f", courses, gains));
    }

    // =====================================================================
    //  Etat courant (dérivé des croyances et du plan)
    // =====================================================================

    private Etat etat() {
        if (panne) return Etat.PANNE;
        if (plan.isEmpty()) return Etat.LIBRE;
        if (plan.get(0).type == TypeEtape.STATION) return Etat.RAVITAILLEMENT;
        if (!aBord.isEmpty()) return Etat.EN_COURSE;
        for (Etape e : plan) if (e.type == TypeEtape.PRISE) return Etat.VERS_CLIENT;
        if (plan.get(0).type == TypeEtape.REPOS) return Etat.REPOSITIONNEMENT;
        return Etat.LIBRE;
    }

    private boolean libre() {
        Etat e = etat();
        return e == Etat.LIBRE || e == Etat.REPOSITIONNEMENT;
    }

    /** Un seul passager, dont il ne reste que la dépose : on peut en prendre un second sur la route. */
    private boolean covoituragePossible() {
        return City.covoiturage && etat() == Etat.EN_COURSE && aBord.size() == 1
                && plan.size() == 1 && plan.get(0).type == TypeEtape.DEPOSE;
    }

    private boolean carburantBas() {
        return carburant < capacite * SEUIL_RESERVE;
    }

    // =====================================================================
    //  Délibération : évaluer une demande (désirs gain / sécurité)
    // =====================================================================

    private Evaluation evaluer(Demande d) {
        boolean covoit;
        if (libre()) covoit = false;
        else if (covoituragePossible()) covoit = true;
        else return Evaluation.refus(etat().name().toLowerCase());

        int px = d.getX(), py = d.getY(), dx = d.getDestX(), dy = d.getDestY();
        int[] st = City.nearestStation(dx, dy);
        Evaluation ev = new Evaluation();
        ev.covoiturage = covoit;
        if (!covoit) {
            ev.eta = City.duree(x, y, px, py);
            ev.besoin = City.longueur(x, y, px, py) + City.longueur(px, py, dx, dy)
                    + City.longueur(dx, dy, st[0], st[1]);
        } else {
            Etape dep = plan.get(0);
            int direct = City.duree(x, y, dep.x, dep.y);
            int detour = City.duree(x, y, px, py) + City.duree(px, py, dep.x, dep.y) - direct;
            if (detour > DETOUR_MAX) return Evaluation.refus("detour-" + detour);
            ev.eta = City.duree(x, y, px, py);
            ev.besoin = City.longueur(x, y, px, py) + City.longueur(px, py, dep.x, dep.y)
                    + City.longueur(dep.x, dep.y, dx, dy) + City.longueur(dx, dy, st[0], st[1]);
        }
        if (carburant < ev.besoin) {
            Evaluation r = Evaluation.refus("carburant");
            r.besoin = ev.besoin;
            return r;
        }

        int km = City.manhattan(px, py, dx, dy);
        ev.mult = multiplicateur();
        ev.prix = City.arrondi((priseEnCharge + prixParKm * km) * ev.mult * (covoit ? REMISE_COVOIT : 1));

        int apres = carburant - ev.besoin;
        double risque = apres < SEUIL_RISQUE * capacite ? (SEUIL_RISQUE * capacite - apres) * 0.8 : 0;
        ev.utilite = ev.prix - COUT_CASE * ev.besoin - risque;
        if (ev.utilite <= 0) {
            Evaluation r = Evaluation.refus("pas-rentable");
            r.utilite = ev.utilite;
            return r;
        }
        ev.ok = true;
        return ev;
    }

    /** Tarif dynamique : forte demande récente → majoration, longue inactivité → remise. */
    private double multiplicateur() {
        while (!cfpRecents.isEmpty() && (long) cfpRecents.peekFirst()[0] < ticks - 15) cfpRecents.pollFirst();
        java.util.Set<Object> distincts = new java.util.HashSet<>();
        for (Object[] c : cfpRecents) distincts.add(c[1]);
        int n = distincts.size();   // clients différents : les relances d'un même client ne comptent pas
        if (n >= 2) return Math.min(1.5, 1 + 0.1 * (n - 1));
        if (libre() && ticks - dernierOccupe > 30) return 0.9;
        return 1.0;
    }

    // =====================================================================
    //  Messages
    // =====================================================================

    private class GestionMessages extends CyclicBehaviour {
        private final MessageTemplate mt = MessageTemplate.MatchProtocol(City.PROTOCOL);

        @Override
        public void action() {
            ACLMessage msg = receive(mt);
            if (msg == null) { block(); return; }
            ContentElement c = Msg.lire(TaxiAgent.this, msg);
            switch (msg.getPerformative()) {
                case ACLMessage.CFP:
                    if (c instanceof Demande) traiterCFP(msg, (Demande) c);
                    break;
                case ACLMessage.ACCEPT_PROPOSAL:
                    traiterAcceptation(msg);
                    break;
                case ACLMessage.PROPOSE:
                    if (c instanceof ContreOffre) traiterContreOffre(msg, (ContreOffre) c);
                    break;
                case ACLMessage.REJECT_PROPOSAL:
                    if (offres.remove(msg.getConversationId()) != null)
                        log("offre rejetée par " + msg.getSender().getLocalName());
                    libererReservation(msg.getConversationId());
                    break;
                case ACLMessage.INFORM:
                    if (c instanceof Paiement) traiterPaiement(msg, (Paiement) c);
                    break;
                default:
                    send(Msg.repondre(msg, ACLMessage.NOT_UNDERSTOOD));
            }
        }
    }

    private void traiterCFP(ACLMessage cfp, Demande d) {
        String conv = cfp.getConversationId();
        String demandeur = Msg.aid(d.getClient()).getLocalName();
        cfpRecents.addLast(new Object[]{ticks, d.getClient()});
        demandesVues.addLast(new int[]{d.getX(), d.getY()});
        if (demandesVues.size() > 10) demandesVues.pollFirst();

        if (City.reservation && reservationConv != null && System.currentTimeMillis() < reservationFin
                && !conv.equals(reservationConv)) {
            refuser(cfp, "offre-en-cours", demandeur + " : offre déjà en cours pour un autre client (réservation)");
            return;
        }

        Evaluation ev = evaluer(d);
        if (!ev.ok) {
            String detail = ev.raison;
            if ("carburant".equals(ev.raison)) detail += " (" + carburant + " < " + ev.besoin + ")";
            if ("pas-rentable".equals(ev.raison)) detail += String.format(Locale.ROOT, " (utilité %.2f)", ev.utilite);
            refuser(cfp, ev.raison, demandeur + " : " + detail);
            if ("carburant".equals(ev.raison) && libre()) seRavitailler();
            return;
        }

        offres.put(conv, new OffreEnAttente(d, Msg.aid(d.getClient()), ev.prix, ev.eta, ev.covoiturage));
        if (City.reservation) {
            reservationConv = conv;
            reservationFin = System.currentTimeMillis() + DUREE_RESERVATION_MS;
        }
        Offre o = new Offre();
        o.setPrix(ev.prix); o.setEta(ev.eta); o.setTaxiX(x); o.setTaxiY(y);
        o.setCovoiturage(ev.covoiturage); o.setMultiplicateur(ev.mult);
        Msg.envoyer(this, Msg.repondre(cfp, ACLMessage.PROPOSE), o);
        log(String.format(Locale.ROOT, "PROPOSE à %s : prix=%.2f, attente=%d%s%s, utilité=%.2f",
                demandeur, ev.prix, ev.eta,
                ev.covoiturage ? " [COVOITURAGE -30%]" : "",
                ev.mult > 1 ? String.format(Locale.ROOT, " [forte demande x%.1f]", ev.mult)
                        : ev.mult < 1 ? " [remise inactivité x0.9]" : "",
                ev.utilite));
    }

    private void refuser(ACLMessage cfp, String raison, String texte) {
        Refus r = new Refus();
        r.setRaison(raison);
        Msg.envoyer(this, Msg.repondre(cfp, ACLMessage.REFUSE), r);
        log("REFUSE " + texte);
    }

    private void traiterAcceptation(ACLMessage accept) {
        String conv = accept.getConversationId();
        OffreEnAttente o = offres.remove(conv);
        libererReservation(conv);
        if (o == null) {
            echec(accept, "offre-inconnue");
            return;
        }
        Evaluation ev = evaluer(o.demande);
        if (!ev.ok || ev.covoiturage != o.covoiturage) {
            // Un autre client a été plus rapide : engagement impossible.
            echec(accept, ev.ok ? "deja-reserve" : ev.raison);
            return;
        }
        engager(o, o.prix, ev.eta, accept);
    }

    private void traiterContreOffre(ACLMessage msg, ContreOffre c) {
        String conv = msg.getConversationId();
        OffreEnAttente o = offres.get(conv);
        String client = msg.getSender().getLocalName();
        if (o != null && c.getPrix() >= o.prix * SEUIL_CONTRE_OFFRE) {
            Evaluation ev = evaluer(o.demande);
            if (ev.ok && ev.covoiturage == o.covoiturage) {
                offres.remove(conv);
                libererReservation(conv);
                log(String.format(Locale.ROOT, "ACCEPTE la contre-offre de %s : %.2f au lieu de %.2f",
                        client, c.getPrix(), o.prix));
                engager(o, City.arrondi(c.getPrix()), ev.eta, msg);
                return;
            }
        }
        offres.remove(conv);
        libererReservation(conv);
        Msg.envoyer(this, Msg.repondre(msg, ACLMessage.REJECT_PROPOSAL), null);
        log(String.format(Locale.ROOT, "REJETTE la contre-offre de %s (%.2f < %.0f%% de %.2f)",
                client, c.getPrix(), SEUIL_CONTRE_OFFRE * 100, o == null ? 0 : o.prix));
    }

    private void echec(ACLMessage msg, String raison) {
        Refus r = new Refus();
        r.setRaison(raison);
        Msg.envoyer(this, Msg.repondre(msg, ACLMessage.FAILURE), r);
        log("FAILURE pour " + msg.getSender().getLocalName() + " : " + raison);
    }

    /** Engagement : le plan reçoit l'étape PRISE et l'étape DEPOSE. */
    private void engager(OffreEnAttente o, double prix, int eta, ACLMessage declencheur) {
        Demande d = o.demande;
        String conv = declencheur.getConversationId();
        Etape prise = new Etape(TypeEtape.PRISE, d.getX(), d.getY(), o.client, conv, prix, ticks, o.covoiturage, 0);
        Etape depose = new Etape(TypeEtape.DEPOSE, d.getDestX(), d.getDestY(), o.client, conv, prix, ticks, o.covoiturage, 0);
        if (o.covoiturage) {
            plan.add(0, prise);     // on passe le prendre avant de déposer le premier passager
            plan.add(depose);
        } else {
            plan.removeIf(e -> e.type == TypeEtape.REPOS);
            plan.add(prise);
            plan.add(depose);
        }
        route = null;

        EtatCourse ec = new EtatCourse();
        ec.setEtat("en-route"); ec.setEta(eta); ec.setPrix(prix);
        ACLMessage m = Msg.nouveau(ACLMessage.INFORM, conv);
        m.addReceiver(o.client);
        Msg.envoyer(this, m, ec);
        if (!declencheur.getSender().equals(o.client)) {
            // mode centralisé : on confirme aussi au dispatcher
            Msg.envoyer(this, Msg.repondre(declencheur, ACLMessage.INFORM), ec);
        }
        int cases = City.longueur(d.getX(), d.getY(), d.getDestX(), d.getDestY());
        int duree = City.duree(d.getX(), d.getY(), d.getDestX(), d.getDestY());
        int km = City.manhattan(d.getX(), d.getY(), d.getDestX(), d.getDestY());
        log("itinéraire de la course (Dijkstra) : " + cases + " cases, " + duree + " ticks"
                + (cases > km ? " (détour de " + (cases - km) + " case(s) pour éviter les embouteillages)"
                : duree > cases ? " (traverse des embouteillages)" : ""));
        log("ENGAGÉ pour " + o.client.getLocalName() + (o.covoiturage ? " (covoiturage)" : "")
                + " → va le chercher en " + City.pos(d.getX(), d.getY()) + ", arrivée prévue dans " + eta + " ticks");
        majDF();
    }

    private void traiterPaiement(ACLMessage msg, Paiement p) {
        gains += p.getMontant();
        publierStats();
        log(String.format(Locale.ROOT, "paiement reçu de %s : %.2f (gains totaux %.2f)",
                msg.getSender().getLocalName(), p.getMontant(), gains));
    }

    private void libererReservation(String conv) {
        if (conv != null && conv.equals(reservationConv)) reservationConv = null;
    }

    // =====================================================================
    //  Exécution du plan : un tick = une case (x3 dans les embouteillages)
    // =====================================================================

    private class Execution extends Behaviour {
        private long prochain = 0;

        @Override
        public void action() {
            long now = System.currentTimeMillis();
            if (now < prochain) { block(prochain - now); return; }
            if (City.pause) { prochain = now + 100; block(100); return; }
            unTick();
            prochain = now + City.tick();
            block(City.tick());
        }

        @Override
        public boolean done() { return false; }
    }

    private void unTick() {
        ticks++;
        Etat e = etat();
        if (e == Etat.VERS_CLIENT || e == Etat.EN_COURSE) { ticksOccupe++; dernierOccupe = ticks; }
        if (ticks % 10 == 0) publierStats();

        if (panne) {
            if (--ticksPanne <= 0) {
                panne = false;
                carburant = capacite;
                log("dépanné et ravitaillé, reprend son plan");
                majDF();
            }
            return;
        }
        if (attenteCase > 0) {
            if (--attenteCase == 0) verifierArrivee();
            return;
        }
        if (plan.isEmpty()) {
            envisagerRepositionnement();
            return;
        }
        Etape cible = plan.get(0);
        if (x == cible.x && y == cible.y) {
            if (cible.duree > 0) cible.duree--;
            verifierArrivee();
            return;
        }
        if (route == null || route.isEmpty()) {
            route = City.route(x, y, cible.x, cible.y);
            CityView.route(getLocalName(), route);
        }
        if (carburant <= 0) {
            tomberEnPanne();
            return;
        }
        int[] c = route.remove(0);
        x = c[0];
        y = c[1];
        carburant--;
        attenteCase = City.cout(x, y) - 1;
        for (AID p : aBord.values()) CityView.clientPosition(p.getLocalName(), x, y);
        CityView.route(getLocalName(), route);   // partie restante de l'itinéraire
        afficher();
        if (attenteCase == 0) verifierArrivee();
    }

    private void verifierArrivee() {
        if (plan.isEmpty()) return;
        Etape cible = plan.get(0);
        if (x != cible.x || y != cible.y) return;
        if (cible.type == TypeEtape.STATION && cible.duree == TICKS_RAVITAILLEMENT) {
            log("arrivé à la station, fait le plein...");
            cible.duree--;
            return;
        }
        if (cible.duree > 0) return;
        plan.remove(0);
        route = null;
        CityView.route(getLocalName(), null);
        executer(cible);
        if (plan.isEmpty()) redevenirDisponible();
        majDF();
        afficher();
    }

    private void executer(Etape e) {
        switch (e.type) {
            case PRISE: {
                aBord.put(e.client.getName(), e.client);
                EtatCourse ec = new EtatCourse();
                ec.setEtat("pris-en-charge");
                ec.setAttente((int) (ticks - e.tickEngagement));
                informer(e.client, e.conv, ec);
                log("client " + e.client.getLocalName() + " pris en charge en " + City.pos(x, y)
                        + (aBord.size() > 1 ? " (" + aBord.size() + " passagers à bord)" : ""));
                break;
            }
            case DEPOSE: {
                aBord.remove(e.client.getName());
                courses++;
                EtatCourse ec = new EtatCourse();
                ec.setEtat("arrive");
                ec.setPrix(e.prix);
                informer(e.client, e.conv, ec);
                log(String.format(Locale.ROOT, "client %s déposé en %s, à payer : %.2f",
                        e.client.getLocalName(), City.pos(x, y), e.prix));
                break;
            }
            case STATION:
                carburant = capacite;
                log("plein fait (" + carburant + "/" + capacite + ")");
                break;
            case REPOS:
                log("repositionné en " + City.pos(x, y) + ", attend des clients");
                dernierOccupe = ticks;
                break;
        }
        publierStats();
    }

    private void informer(AID client, String conv, EtatCourse ec) {
        ACLMessage m = Msg.nouveau(ACLMessage.INFORM, conv);
        m.addReceiver(client);
        Msg.envoyer(this, m, ec);
    }

    private void redevenirDisponible() {
        if (carburantBas()) seRavitailler();
    }

    private void seRavitailler() {
        for (Etape e : plan) if (e.type == TypeEtape.STATION) return;
        plan.removeIf(e -> e.type == TypeEtape.REPOS);
        int[] st = City.nearestStation(x, y);
        plan.add(new Etape(TypeEtape.STATION, st[0], st[1], null, null, 0, ticks, false, TICKS_RAVITAILLEMENT));
        route = null;
        log("carburant " + carburant + "/" + capacite + " → part se ravitailler en " + City.pos(st[0], st[1]));
        majDF();
        afficher();
    }

    /** Taxi libre et inactif : il se rapproche de la zone où il a observé le plus de demandes. */
    private void envisagerRepositionnement() {
        if (!City.repositionnement || demandesVues.isEmpty() || ticks - dernierOccupe < INACTIVITE_REPOSITION) return;
        int sx = 0, sy = 0;
        for (int[] p : demandesVues) { sx += p[0]; sy += p[1]; }
        int hx = Math.round((float) sx / demandesVues.size());
        int hy = Math.round((float) sy / demandesVues.size());
        dernierOccupe = ticks;
        if (City.manhattan(x, y, hx, hy) <= 2) return;
        plan.add(new Etape(TypeEtape.REPOS, hx, hy, null, null, 0, ticks, false, 0));
        route = null;
        log("inactif → se repositionne vers la zone de demande " + City.pos(hx, hy));
        majDF();
        afficher();
    }

    private void tomberEnPanne() {
        panne = true;
        ticksPanne = TICKS_DEPANNAGE;
        log("PANNE D'ESSENCE en " + City.pos(x, y) + ", attend la dépanneuse");
        majDF();
        afficher();
    }

    // =====================================================================
    //  Annuaire (DF) : inscrit quand il peut accepter une course
    // =====================================================================

    private void majDF() {
        String voulu = libre() ? City.SERVICE_TYPE : covoituragePossible() ? City.SERVICE_COVOIT : null;
        if (voulu == null ? serviceDF == null : voulu.equals(serviceDF)) return;
        desinscrireDF();
        if (voulu == null) return;
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType(voulu);
        sd.setName(getLocalName() + "-" + voulu);
        dfd.addServices(sd);
        try {
            DFService.register(this, dfd);
            serviceDF = voulu;
            log("inscrit au DF (" + voulu + ")");
        } catch (FIPAException e) {
            log("échec inscription DF : " + e.getMessage());
        }
    }

    private void desinscrireDF() {
        if (serviceDF == null) return;
        try {
            DFService.deregister(this);
            log("retiré du DF (" + serviceDF + ")");
        } catch (FIPAException e) {
            log("échec désinscription DF : " + e.getMessage());
        }
        serviceDF = null;
    }

    // =====================================================================

    private void publierStats() {
        Stats.taxi(getLocalName(), courses, gains, ticksOccupe, ticks);
    }

    private void afficher() {
        CityView.taxi(getLocalName(), x, y, etat().name(), carburant, capacite, gains, aBord.size());
    }

    private void log(String s) {
        City.log(getLocalName(), s);
        afficher();
    }
}
