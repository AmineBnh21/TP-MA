package taxi.onto;

import jade.content.Predicate;

/** Contre-proposition du client (PROPOSE client vers taxi). */
public class ContreOffre implements Predicate {
    private double prix;

    public ContreOffre() {}

    public double getPrix() { return prix; }
    public void setPrix(double v) { this.prix = v; }
}
