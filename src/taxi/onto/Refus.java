package taxi.onto;

import jade.content.Predicate;

/** Contenu d'un REFUSE ou d'un FAILURE. */
public class Refus implements Predicate {
    private String raison;

    public Refus() {}

    public String getRaison() { return raison; }
    public void setRaison(String v) { this.raison = v; }
}
