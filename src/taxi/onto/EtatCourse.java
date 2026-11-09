package taxi.onto;

import jade.content.Predicate;

/** INFORM du taxi au client : en-route, pris-en-charge ou arrive. */
public class EtatCourse implements Predicate {
    private String etat;
    private int eta;
    private double prix;
    private int attente;

    public EtatCourse() {}

    public String getEtat() { return etat; }
    public void setEtat(String v) { this.etat = v; }
    public int getEta() { return eta; }
    public void setEta(int v) { this.eta = v; }
    public double getPrix() { return prix; }
    public void setPrix(double v) { this.prix = v; }
    public int getAttente() { return attente; }
    public void setAttente(int v) { this.attente = v; }
}
