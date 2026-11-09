package taxi.onto;

import jade.content.Predicate;

/** Contenu d'un PROPOSE du taxi. */
public class Offre implements Predicate {
    private double prix;
    private int eta;
    private int taxiX;
    private int taxiY;
    private boolean covoiturage;
    private double multiplicateur;

    public Offre() {}

    public double getPrix() { return prix; }
    public void setPrix(double v) { this.prix = v; }
    public int getEta() { return eta; }
    public void setEta(int v) { this.eta = v; }
    public int getTaxiX() { return taxiX; }
    public void setTaxiX(int v) { this.taxiX = v; }
    public int getTaxiY() { return taxiY; }
    public void setTaxiY(int v) { this.taxiY = v; }
    public boolean isCovoiturage() { return covoiturage; }
    public void setCovoiturage(boolean v) { this.covoiturage = v; }
    public double getMultiplicateur() { return multiplicateur; }
    public void setMultiplicateur(double v) { this.multiplicateur = v; }
}
