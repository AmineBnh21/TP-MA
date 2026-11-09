package taxi.fipa;

import jade.core.Agent;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.FailureException;
import jade.domain.FIPAAgentManagement.RefuseException;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.domain.FIPANames;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.proto.ContractNetResponder;
import taxi.City;

import java.util.Locale;

/**
 * Variante "bibliothèque" du taxi : le protocole FIPA Contract Net est géré par la classe
 * ContractNetResponder de JADE (au lieu d'être codé à la main comme dans TaxiAgent).
 * Arguments : x, y, priseEnCharge, prixParKm [, probabilitéEchec=0]
 */
public class CnpTaxiAgent extends Agent {

    public static final String SERVICE = "taxi-fipa";
    private int x, y;
    private double pec, ppk, echec;
    private boolean occupe = false;

    @Override
    protected void setup() {
        Object[] a = getArguments();
        x = City.intArg(a, 0, 0);
        y = City.intArg(a, 1, 0);
        pec = City.doubleArg(a, 2, 3);
        ppk = City.doubleArg(a, 3, 1.5);
        echec = City.doubleArg(a, 4, 0);

        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType(SERVICE);
        sd.setName(getLocalName());
        dfd.addServices(sd);
        try { DFService.register(this, dfd); } catch (FIPAException e) { e.printStackTrace(); }
        City.log(getLocalName(), "taxi FIPA prêt en " + City.pos(x, y));

        MessageTemplate mt = MessageTemplate.and(
                MessageTemplate.MatchProtocol(FIPANames.InteractionProtocol.FIPA_CONTRACT_NET),
                MessageTemplate.MatchPerformative(ACLMessage.CFP));

        addBehaviour(new ContractNetResponder(this, mt) {
            @Override
            protected ACLMessage handleCfp(ACLMessage cfp) throws RefuseException {
                if (occupe) throw new RefuseException("occupe");
                int[] v = parse(cfp.getContent());
                int eta = City.manhattan(x, y, v[0], v[1]);
                double prix = City.arrondi(pec + ppk * City.manhattan(v[0], v[1], v[2], v[3]));
                ACLMessage p = cfp.createReply();
                p.setPerformative(ACLMessage.PROPOSE);
                p.setContent(String.format(Locale.ROOT, "%.2f;%d", prix, eta));
                City.log(getLocalName(), "PROPOSE (ContractNetResponder) prix=" + prix + " eta=" + eta);
                return p;
            }

            @Override
            protected ACLMessage handleAcceptProposal(ACLMessage cfp, ACLMessage propose, ACLMessage accept)
                    throws FailureException {
                if (Math.random() < echec) throw new FailureException("panne-moteur");
                int[] v = parse(cfp.getContent());
                x = v[2];
                y = v[3];
                ACLMessage inform = accept.createReply();
                inform.setPerformative(ACLMessage.INFORM);
                inform.setContent("course-effectuee;" + x + "," + y);
                City.log(getLocalName(), "course effectuée, maintenant en " + City.pos(x, y));
                return inform;
            }

            @Override
            protected void handleRejectProposal(ACLMessage cfp, ACLMessage propose, ACLMessage reject) {
                City.log(getLocalName(), "offre rejetée");
            }
        });
    }

    static int[] parse(String s) {
        String[] p = s.split(",");
        int[] v = new int[p.length];
        for (int i = 0; i < p.length; i++) v[i] = Integer.parseInt(p[i].trim());
        return v;
    }

    @Override
    protected void takeDown() {
        try { DFService.deregister(this); } catch (FIPAException ignored) { }
    }
}
