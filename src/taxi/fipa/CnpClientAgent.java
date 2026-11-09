package taxi.fipa;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.WakerBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.domain.FIPANames;
import jade.lang.acl.ACLMessage;
import jade.proto.ContractNetInitiator;
import taxi.City;

import java.util.Date;
import java.util.Enumeration;
import java.util.Vector;

/**
 * Variante "bibliothèque" du client : ContractNetInitiator de JADE gère l'envoi du CFP,
 * le délai de réponse, la collecte des PROPOSE/REFUSE et les résultats INFORM/FAILURE.
 * Arguments : x, y, destX, destY, budget
 */
public class CnpClientAgent extends Agent {

    @Override
    protected void setup() {
        Object[] a = getArguments();
        int x = City.intArg(a, 0, 0), y = City.intArg(a, 1, 0);
        int dx = City.intArg(a, 2, 9), dy = City.intArg(a, 3, 9);
        double budget = City.doubleArg(a, 4, 20);

        addBehaviour(new WakerBehaviour(this, 1000) {
            @Override
            protected void onWake() {
                ACLMessage cfp = new ACLMessage(ACLMessage.CFP);
                AID[] taxis = chercher();
                for (AID t : taxis) cfp.addReceiver(t);
                cfp.setProtocol(FIPANames.InteractionProtocol.FIPA_CONTRACT_NET);
                cfp.setReplyByDate(new Date(System.currentTimeMillis() + 2000));
                cfp.setContent(x + "," + y + "," + dx + "," + dy);
                City.log(getLocalName(), "CFP (ContractNetInitiator) à " + taxis.length + " taxi(s)");

                addBehaviour(new ContractNetInitiator(CnpClientAgent.this, cfp) {
                    @Override
                    protected void handlePropose(ACLMessage propose, Vector acceptances) {
                        City.log(getLocalName(), "PROPOSE de " + propose.getSender().getLocalName() + " : " + propose.getContent());
                    }

                    @Override
                    protected void handleRefuse(ACLMessage refuse) {
                        City.log(getLocalName(), "REFUSE de " + refuse.getSender().getLocalName());
                    }

                    @Override
                    @SuppressWarnings({"rawtypes", "unchecked"})
                    protected void handleAllResponses(Vector responses, Vector acceptances) {
                        ACLMessage meilleure = null;
                        double meilleurCout = Double.MAX_VALUE;
                        for (Enumeration e = responses.elements(); e.hasMoreElements(); ) {
                            ACLMessage m = (ACLMessage) e.nextElement();
                            if (m.getPerformative() != ACLMessage.PROPOSE) continue;
                            String[] p = m.getContent().split(";");
                            double prix = Double.parseDouble(p[0]);
                            int eta = Integer.parseInt(p[1]);
                            double cout = prix + 0.5 * eta;
                            if (prix <= budget && cout < meilleurCout) { meilleurCout = cout; meilleure = m; }
                        }
                        for (Enumeration e = responses.elements(); e.hasMoreElements(); ) {
                            ACLMessage m = (ACLMessage) e.nextElement();
                            if (m.getPerformative() != ACLMessage.PROPOSE) continue;
                            ACLMessage r = m.createReply();
                            r.setPerformative(m == meilleure ? ACLMessage.ACCEPT_PROPOSAL : ACLMessage.REJECT_PROPOSAL);
                            acceptances.add(r);
                        }
                        City.log(getLocalName(), meilleure == null ? "aucune offre dans le budget"
                                : "accepte " + meilleure.getSender().getLocalName() + " (coût " + meilleurCout + ")");
                    }

                    @Override
                    protected void handleInform(ACLMessage inform) {
                        City.log(getLocalName(), "INFORM de " + inform.getSender().getLocalName() + " : " + inform.getContent());
                    }

                    @Override
                    protected void handleFailure(ACLMessage failure) {
                        City.log(getLocalName(), "FAILURE de " + failure.getSender().getLocalName() + " : " + failure.getContent());
                    }
                });
            }
        });
    }

    private AID[] chercher() {
        DFAgentDescription modele = new DFAgentDescription();
        ServiceDescription sd = new ServiceDescription();
        sd.setType(CnpTaxiAgent.SERVICE);
        modele.addServices(sd);
        try {
            DFAgentDescription[] r = DFService.search(this, modele);
            AID[] res = new AID[r.length];
            for (int i = 0; i < r.length; i++) res[i] = r[i].getName();
            return res;
        } catch (FIPAException e) {
            return new AID[0];
        }
    }
}
