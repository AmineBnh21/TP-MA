package taxi.onto;

import jade.content.Predicate;

/** INFORM du client au taxi à l'arrivée. */
public class Paiement implements Predicate {
    private double montant;

    public Paiement() {}

    public double getMontant() { return montant; }
    public void setMontant(double v) { this.montant = v; }
}
