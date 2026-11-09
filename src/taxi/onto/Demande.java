package taxi.onto;

import jade.content.Predicate;

/** Contenu d'un CFP ou d'une REQUEST : où est le client, où il va, ses contraintes. */
public class Demande implements Predicate {
    private String client;
    private int x;
    private int y;
    private int destX;
    private int destY;
    private double budget;
    private int attenteMax;

    public Demande() {}

    public String getClient() { return client; }
    public void setClient(String v) { this.client = v; }
    public int getX() { return x; }
    public void setX(int v) { this.x = v; }
    public int getY() { return y; }
    public void setY(int v) { this.y = v; }
    public int getDestX() { return destX; }
    public void setDestX(int v) { this.destX = v; }
    public int getDestY() { return destY; }
    public void setDestY(int v) { this.destY = v; }
    public double getBudget() { return budget; }
    public void setBudget(double v) { this.budget = v; }
    public int getAttenteMax() { return attenteMax; }
    public void setAttenteMax(int v) { this.attenteMax = v; }
}
