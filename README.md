# Progetto Esame – Gioco del Tris P2P con Java RMI

Questo ramo contiene la versione aggiornata del progetto d’esame di **Algoritmi Distribuiti**:  
un’applicazione **peer-to-peer** per giocare a **tris (tic-tac-toe)** tramite:

- **Java RMI** per la comunicazione 
- un sistema di **discovery automatico** dei peer tramite Multicast UDP
- un meccanismo opzionale di **gossip epidemico** per propagare rapidamente la membership
- un **matchmaking distribuito** completamente privo di server centrale
- una gestione del gioco basata su **token logico**
- un protocollo di **rematch** con coordinatore eletto tramite symmetry breaking

L’applicazione implementa concetti fondamentali degli algoritmi distribuiti:  
*break symmetry, coordination, distributed objects, failure handling, mini-consensus,* e gestione della membership distribuita.

---

## Struttura del progetto

I file principali sono:

- `AutoPeerMain.java` – entry point dell’applicazione
- `PeerService.java` – interfaccia RMI con i metodi remoti esposti dai peer
- `PeerImpl.java` – implementazione concreta del peer (matchmaking, gioco, rematch)
- `Discovery.java` – discovery dei peer tramite UDP multicast + (opzionale) gossip triggered
- `GameBoard.java` – gestione della board 3×3 del tris

Tutti i file si trovano nel package:

```java
package gamep2p;
````

---

## Modello di sistema

Ogni processo avviato tramite `AutoPeerMain` è un **peer distribuito**:

* espone un oggetto remoto RMI (`PeerService`)
* chiama metodi remoti sugli altri peer
* partecipa al **discovery distribuito**
* entra automaticamente nel **matchmaking**
* gioca tramite un **token**, che garantisce la mutua esclusione sul turno
* gestisce la chiusura e il rematch tramite un piccolo protocollo di consenso

Non esiste un server centrale né un’autorità:
tutti i peer sono identici (*fully peer-to-peer*).

Ogni peer possiede un identificatore globale:

```
myId = host:port
```

utilizzato per:

* romper la simmetria nel matchmaking,
* decidere chi inizia la partita,
* decidere chi coordina la fase di rematch,
* selezionare in modo deterministico l’avversario.

---

## File e responsabilità

### `AutoPeerMain.java` – Avvio del peer

Responsabilità principali:

* parsing degli argomenti:

  ```bash
  java gamep2p.AutoPeerMain [host] [porta]
  ```

* scelta di una porta libera se non specificata

* creazione del peer:

  ```java
  PeerImpl peer = new PeerImpl(host, port);
  ```

* avvio e binding nel registry RMI:

  * creazione del registry sulla porta
  * registrazione:

    ```
    rmi://host:port/peer
    ```

* il main thread poi rimane inattivo (il peer usa thread autonomi)

---

### `PeerService.java` – Interfaccia RMI

Definisce le operazioni remote che un peer può invocare sull’altro.

### Metodi di stato

* `boolean ping()`
* `String getId()`
* `boolean isInGame()`

### Matchmaking

* `boolean proposeMatch(String proposerId)`
* `void confirmMatch(String opponentId, boolean iStart, char symbol)`

### Gioco

* `void receiveToken()`
* `void updateMove(int row, int col, char symbol, char result)`

### Rematch

* `boolean getRematchDecision()`
* `void startRematch(boolean iStart, char mySymbol)`
* `void noRematch()`

Questi sono gli unici metodi realmente invocati tramite rete; per questo compaiono nel Class Diagram.

---

### `GameBoard.java` – Logica del tris

Gestisce:

* validazione mosse
* applicazione simboli
* verifica stato (`X`, `O`, `D`, ` `)
* rendering user-friendly della board

È completamente locale e indipendente dalla rete.

---

### `Discovery.java` – Scoperta dei peer (HELLO + Gossip opzionale)

Il discovery mantiene la lista degli ID noti tramite:

#### ✔ HELLO periodico (sempre attivo)

Ogni peer invia:

```
HELLO <myId>
```

dove `<myId>` è la stringa `host:port`.

Il receiver aggiorna la lista dei peer attivi.

#### ✔ Gossip opzionale (abilitato tramite booleano nel costruttore)

Le caratteristiche della versione aggiornata sono:

* **non** è periodico
* viene inviato **solo quando cambia la vista** (nuovo peer scoperto)
* è un **gossip unicast**, non multicast:

  * minor consumo di rete
  * minor congestione
* il payload contiene timestamp (`id;ts`) e viene propagato finché necessario

È presente anche un **cleaner periodico** che rimuove peer non più attivi.

Il discovery viene usato come “black box” da `PeerImpl` per ottenere la lista aggiornata degli avversari possibili.

---

### `PeerImpl.java` – Protocollo distribuito completo

Questa classe contiene tutta la logica del peer:

### Stato

* identificatore
* game board
* discovery
* avversario remoto + stub RMI
* simboli assegnati
* token (bool)
* rematch e matchmaking automatico
* thread scheduler

### Matchmaking distribuito

Esegue periodicamente:

1. recupero vista dal discovery
2. filtraggio peer vivi e liberi (`ping`, `isInGame`)
3. symmetry breaking: si seleziona il **successore lessicografico**
4. invio proposta di match
5. se accettata → setup partita

Inoltre:

* impedisce match immediati con l’ultimo avversario (`lastOpponentId`);
* gestisce failure durante lookup e chiamate remote.

### Token-based Game

Chi ha il token:

* può fare la mossa
* la comunica via RMI
* alla fine passa il token all’avversario

È una mutua esclusione distribuita semplice ed efficace.

### Rematch (mini-consenso a due)

Il coordinatore (ID più piccolo):

1. raccoglie sia la decisione locale sia quella remota
2. se entrambe positive → `startRematch`
3. altrimenti → `noRematch`

Chi non è coordinatore si limita a dare la propria decisione ed attendere.

---

## Compilazione e esecuzione

### Requisiti

* Java 8+
* Rete locale che supporti Multicast UDP

### Compilazione

```bash
javac gamep2p/*.java
```

### Esecuzione

```bash
java gamep2p.AutoPeerMain <host> [porta]
```

Esempio:

```bash
java gamep2p.AutoPeerMain 192.168.1.20 5001
java gamep2p.AutoPeerMain 192.168.1.20 5002
```

Appena i peer si scoprono:

* il matchmaking automatico li abbina
* parte la partita
* chi ha ID minore inizia con il simbolo `X`


## Autore

**Simone Candiani**
Corso di Laurea Magistrale in Informatica – UNIMORE
