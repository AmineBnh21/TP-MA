# TP JADE : système multi-agents BDI de réservation de taxis

JADE 4.6.0 (`lib/jade.jar`), Java 8 ou plus récent (testé avec le JDK 24).

## Contenu

| Package | Classes | Rôle |
|---|---|---|
| `examples.bookTrading` | `BookSellerAgent`, `BookBuyerAgent`, `BookSellerGui` | Ex. 2 (exemple officiel JADE) |
| `firstAgent` | `HelloAgent` | Ex. 3 |
| `communication` | `SenderAgent`, `ReceiverAgent` | Ex. 4 (`ACLMessage`, `createReply()`) |
| `taxi` | `TaxiAgent`, `ClientAgent` | Les deux agents BDI du projet |
| `taxi` | `City` | Environnement : grille, embouteillages, Dijkstra, réglages |
| `taxi` | `DispatcherAgent` | Dispatcher central, **uniquement** pour la comparaison (mode `centralise`) |
| `taxi` | `GeneratorAgent`, `StatsAgent`, `Stats` | Clients aléatoires, mesures, export CSV |
| `taxi` | `CityView`, `Lancement`, `Msg` | Fenêtre interactive, lancement multi-conteneurs, messages SL |
| `taxi.onto` | `TaxiOntology` + 7 prédicats | Ontologie (contenu des messages en FIPA-SL) |
| `taxi.fipa` | `CnpTaxiAgent`, `CnpClientAgent` | Même protocole avec `ContractNetResponder` / `ContractNetInitiator` |

## Lancement

```powershell
.\run.ps1 gui                 # plateforme seule : RMA + AMS + DF
.\run.ps1 hello | comm | booktrading
.\run.ps1 <scenario>          # scénarios taxis (tableau ci-dessous), RMA + fenêtre Ville
.\run.ps1 ville -Mode centralise
.\run.ps1 multijvm            # deux JVM : taxis dans l'une, clients dans l'autre
.\experience.ps1              # comparaison chiffrée (environ 6 min, sans interface)
```

Si PowerShell bloque les scripts : `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned`.

**IntelliJ** : ouvrir le dossier (le module `TP-MA.iml` référence `lib/jade.jar`), puis créer une configuration *Application* :
- Main class : `taxi.Lancement`
- Program arguments : `ville` (ou `nominal`, `covoiturage`…)

Pour les exercices, utiliser `jade.Boot` avec les arguments `-gui -agents "..."`.

La plateforme démarre **trois conteneurs** (visibles dans le RMA) : `Main-Container` (AMS, DF, RMA, stats, dispatcher), `Conteneur-Taxis` et `Conteneur-Clients`.

### Fenêtre « Ville »
- **Pause / Reprendre**, et un curseur de **vitesse** (ms par tick).
- Cases à cocher **Embouteillages** et **Covoiturage**, actives en direct.
- **Un clic sur une case** crée un client à cet endroit, avec une destination, un budget et un profil aléatoires.
- Les taxis sont colorés selon leur état, leurs itinéraires s'affichent en pointillés, les embouteillages en rose. Le bandeau affiche les statistiques en direct.

Le Sniffer (*Tools > Start Sniffer* dans le RMA) montre les messages CFP, PROPOSE, ACCEPT et INFORM. Le contenu des messages est en SL, par exemple `((Offre :prix 16.0 :eta 8 ...))`.

## Scénarios

| Scénario | Ce qu'on observe |
|---|---|
| `nominal` | 3 offres. taxi2 est écarté (attente trop longue). Le client choisit taxi3 (16 + 0,5×8 = 20) plutôt que taxi1 (21 + 0,5×5 = 23,5). |
| `concurrence` | Deux clients acceptent taxi1 en même temps. L'un reçoit FAILURE, relance et finit par être servi. |
| `reservation` | Même situation avec l'option réservation : taxi1 refuse le second CFP (`offre-en-cours`), donc il n'y a plus de FAILURE. |
| `budget` | Course à 39 pour un budget de 10 : relance, **révision des désirs** (budget +20 %), puis abandon. |
| `negociation` | client1 : **contre-offre** à 17 au lieu de 21, acceptée (≥ 80 %). client2 : 20 au lieu de 27, refusée. Entre les deux, taxi1 refuse faute de carburant et part faire le plein. |
| `carburant` | taxi1 refuse (12 < 18), se ravitaille, se réinscrit au DF et sert client2. |
| `profils` | Mêmes offres, choix opposés : le client **pressé** prend le taxi proche (21), l'**économe** prend le taxi lointain (8,80). |
| `revision` | L'attente de 6 dépasse le maximum de 5. Après 2 relances, le client révise son désir (5 → 8) et accepte. |
| `covoiturage` | taxi1 transporte client1 vers l'est et prend client2 sur sa route (-30 %, 2 passagers à bord). |
| `trafic` | L'itinéraire calculé par Dijkstra contourne le centre embouteillé (détour de 2 cases). |
| `centralise` | Le dispatcher reçoit les REQUEST et affecte le taxi le plus rapide. |
| `ville` | Simulation continue : 4 taxis, 20 clients générés, repositionnement, tarif dynamique, bilan final. |
| `fipa` | Même logique avec les classes FIPA de JADE, dont un FAILURE « panne-moteur ». |
| `multijvm` | Les clients d'une 2e JVM rejoignent la plateforme sur `localhost:1099`. |

## Modèle

### Environnement (`City`)
- Grille 10×10. Les stations-service sont en (0,0) et (9,9).
- Le centre-ville (x 3..6, y 4..5) est **embouteillé** : y entrer coûte 3 ticks au lieu de 1.
- Les itinéraires sont calculés par **Dijkstra** sur ce temps de parcours. L'attente (`eta`) est en ticks, le carburant consommé est d'une unité par case.
- Le prix est calculé sur la distance de Manhattan : prise en charge + prix/km × km.

### BDI

| | Client | Taxi |
|---|---|---|
| Croyances | position, destination, budget, attente max, offres reçues, nombre d'échecs | position, carburant, tarif, gains, passagers à bord, demandes observées, offres en attente |
| Désirs | payer peu **et** attendre peu, pondérés par le **profil** (`presse` 2,0 / `normal` 0,5 / `econome` 0,1) ; **révisés** après 2 échecs (attente ×1,5, budget +20 %) | gagner de l'argent (**utilité** = prix − coût d'exploitation − risque) tout en restant opérationnel (carburant) |
| Intentions | `FSMBehaviour` : RELANCER → CHERCHER → CHOISIR → [NEGOCIER] → ATTENDRE → VOYAGER → ARRIVE, ou ABANDONNER | un **plan** d'étapes PRISE / DEPOSE / STATION / REPOS. L'état (LIBRE, REPOSITIONNEMENT, VERS_CLIENT, EN_COURSE, RAVITAILLEMENT, PANNE) se déduit du plan |

**Délibération du taxi** pour une demande :
1. S'il est occupé, il refuse. S'il transporte un seul passager, il évalue un covoiturage (détour ≤ 3 ticks).
2. Si le carburant ne couvre pas approche + course + retour à la station, il refuse et part se ravitailler.
3. Prix = tarif × multiplicateur (de ×1,1 à ×1,5 quand plusieurs clients **distincts** demandent en peu de temps, ×0,9 après une longue inactivité) × 0,7 en covoiturage.
4. Utilité = prix − 0,3 × cases − pénalité de risque si le carburant restant passe sous 30 %. Si l'utilité est ≤ 0, il refuse (`pas-rentable`).
5. Un taxi libre et inactif se **repositionne** vers la zone où il a observé le plus de demandes.

### Protocole (décentralisé, Contract Net, protocole `taxi-cnp`, langage SL + `taxi-ontology`)
1. Un taxi est inscrit au DF sous `taxi-service` quand il est libre, et sous `taxi-covoiturage` quand il a un seul passager. Il se retire du DF dans tous les autres cas.
2. Le client envoie un **CFP**(`Demande`) à tous les taxis trouvés dans le DF.
3. Chaque taxi répond **PROPOSE**(`Offre`) ou **REFUSE**(`Refus`).
4. Le client filtre les offres sur le budget et l'attente, puis minimise `prix + poids × attente`. Il envoie **ACCEPT_PROPOSAL** au taxi choisi et **REJECT_PROPOSAL** aux autres.
   - Si aucune offre n'est acceptable mais que l'une d'elles est ≤ 1,4 × budget, il envoie une **contre-offre** : PROPOSE(`ContreOffre`).
5. Le taxi répond **INFORM**(`EtatCourse` en-route), ou **FAILURE** s'il a été pris entre-temps, ce qui fait relancer le client.
6. Pendant la course, le taxi envoie **INFORM** pris-en-charge puis arrive (avec le prix). Le client paie avec **INFORM**(`Paiement`).

**Mode centralisé** (comparaison) : le client envoie une REQUEST au `DispatcherAgent`. Celui-ci lance le CFP, choisit le taxi le plus rapide et envoie au client un INFORM(`Affectation`).

## Expérience comparative

`.\experience.ps1` lance 8 simulations sans interface : 2 modes × {1, 2, 3, 5} taxis, avec les mêmes 20 clients générés à partir de la graine 42. Pour chaque simulation, le script ajoute une ligne à `results/experience.csv` (taux d'abandon, attente moyenne, prix moyen, occupation, négociations, covoiturages). Il écrit aussi le détail par client dans `results/clients-<mode>-<n>.csv`.

Chaque simulation lancée depuis `run.ps1` écrit aussi son bilan quand on ferme la plateforme.
