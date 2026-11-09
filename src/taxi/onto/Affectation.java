package taxi.onto;

import jade.content.Predicate;

/** INFORM du dispatcher au client (mode centralisé) : taxi affecté. */
public class Affectation implements Predicate {
    private String taxi;
    private double prix;
    private int eta;
    private boolean covoiturage;

    public Affectation() {}

    public String getTaxi() { return taxi; }
    public void setTaxi(String v) { this.taxi = v; }
    public double getPrix() { return prix; }
    public void setPrix(double v) { this.prix = v; }
    public int getEta() { return eta; }
    public void setEta(int v) { this.eta = v; }
    public boolean isCovoiturage() { return covoiturage; }
    public void setCovoiturage(boolean v) { this.covoiturage = v; }
}
