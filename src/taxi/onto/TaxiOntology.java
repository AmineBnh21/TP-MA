package taxi.onto;

import jade.content.onto.BeanOntology;
import jade.content.onto.BeanOntologyException;
import jade.content.onto.Ontology;

/**
 * Ontologie partagée par tous les agents du système de taxis.
 * Les messages sont codés en FIPA-SL : au lieu d'une chaîne "cle=valeur",
 * chaque message transporte un prédicat typé (Demande, Offre, EtatCourse...).
 */
public final class TaxiOntology extends BeanOntology {

    public static final String NAME = "taxi-ontology";
    private static TaxiOntology instance;

    private TaxiOntology() {
        super(NAME);
        try {
            add(Demande.class);
            add(Offre.class);
            add(Refus.class);
            add(ContreOffre.class);
            add(EtatCourse.class);
            add(Affectation.class);
            add(Paiement.class);
        } catch (BeanOntologyException e) {
            throw new IllegalStateException("Ontologie invalide", e);
        }
    }

    public static synchronized Ontology getInstance() {
        if (instance == null) instance = new TaxiOntology();
        return instance;
    }
}
