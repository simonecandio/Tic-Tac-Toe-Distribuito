package gamep2p;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.rmi.Naming;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Implementazione concreta dell'interfaccia remota {@link PeerService}.
 *
 * Ogni peer svolge contemporaneamente il ruolo di:
 * - server RMI, esponendo metodi remoti per gli altri peer;
 * - client RMI, invocando metodi remoti sugli altri peer.
 *
 * La classe gestisce:
 * - discovery dei peer tramite {@link Discovery},
 * - matchmaking distribuito,
 * - logica di gioco con coordinamento basato su token,
 * - protocollo di rivincita (rematch) con mini-consenso a due.
 *
 * Al termine di una partita il peer può:
 * - chiedere una rivincita con lo stesso avversario;
 * - oppure chiudere la partita e decidere se continuare a cercare altri match.
 */
public class PeerImpl extends UnicastRemoteObject implements PeerService {

    // ===== Identità e stato globale del peer =====

    private final String myHost;
    private final int myPort;
    private final String myId;                 // forma "host:porta"
    private volatile boolean inGame = false;   // true se il peer è attualmente in partita

    // ===== Stato di gioco =====

    private final GameBoard board = new GameBoard();
    private volatile char mySymbol  = ' ';             // 'X' o 'O'
    private volatile boolean hasToken = false;        // true se tocca a questo peer
    private volatile PeerService opponent = null;     // stub remoto dell'avversario
    private volatile String opponentId = null;        // id dell'avversario per log
    private volatile String lastOpponentId = null;    // ultimo avversario con cui ho giocato

    /**
     * Se false, il peer non partecipa più al matchmaking automatico
     * (non propone e non accetta nuovi match).
     */
    private volatile boolean lookingForMatches = true;

    // ===== Discovery e scheduling =====

    private final Discovery discovery;
    private final ScheduledExecutorService exec =
            Executors.newScheduledThreadPool(2);  // Pool di 2 thread usati per: 
                                                                // (1) eseguire periodicamente il matchmaking 
                                                                // (2) gestire in parallelo turni, token e fine partita 
                                                                // senza bloccare le chiamate RMI o il thread principale.
    private static final boolean Discovery=true; // true=gossip, false=simple
    // ===== Coordinamento per la rivincita =====

    /**
     * Decisione locale sulla rivincita.
     * - true  → il giocatore vuole una nuova partita;
     * - false → il giocatore non vuole la rivincita;
     * - null  → il giocatore non ha ancora risposto.
     *
     * Accesso protetto dal lock {@link #rematchLock}.
     */
    private volatile Boolean rematchDecision = null;

    /** Monitor usato per attendere/risvegliare i thread che leggono la decisione di rematch. */
    private final Object rematchLock = new Object();


    /**
     * Costruisce un nuovo peer, inizializzando discovery e thread di matchmaking.
     *
     * @param host hostname locale
     * @param port porta su cui esporre il registry RMI
     * @throws Exception se l'inizializzazione RMI o del discovery fallisce
     */
    protected PeerImpl(String host, int port) throws Exception {
        super(0); //oggetto RMI con porta automatica
        this.myHost = host;
        this.myPort = port;
        this.myId = host + ":" + port;
        this.discovery = new Discovery(myId, Discovery);

        // Avvio periodico del matchmaking (dopo 1s, ogni 1.5s)
        exec.scheduleWithFixedDelay(this::tryMatchMaking,
                1000, 1500, TimeUnit.MILLISECONDS);
        // Controllo periodico della liveness dell'avversario durante una partita
        exec.scheduleWithFixedDelay(this::checkOpponentLiveness, 2000, 2000, TimeUnit.MILLISECONDS);

    }


    // ========================================================================
    // Implementazione PeerService
    // ========================================================================

    @Override
    public boolean ping() {
        return lookingForMatches && !inGame;
    }

    @Override
    public String getId() {
        return myId;
    }

    @Override
    public synchronized boolean isInGame() {
        return inGame;
    }

    /**
     * Richiesta di instaurare un match da parte di un altro peer.
     * Accetta solo se:
     * - questo peer non è già in partita;
     * - questo peer partecipa ancora al matchmaking;
     * - l'id del proponente è lessicograficamente minore (symmetry breaking).
     */
    @Override
    public synchronized boolean proposeMatch(String proposerId) throws RemoteException {
        if (inGame || !lookingForMatches) {
            return false;
        }
        // Rompiamo la simmetria accettando solo se proposerId < myId
        if (!(proposerId.compareTo(myId) < 0)) {
            return false;
        }
        try {
            PeerService prop = (PeerService) Naming.lookup("rmi://" + proposerId + "/peer");
            this.opponent = prop;
            this.opponentId = proposerId;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Conferma di un match precedentemente proposto.
     * Inizializza lo stato di gioco (simbolo, token, avversario) e,
     * se questo peer possiede il token, avvia il ciclo di gioco locale.
     */
    @Override
    public synchronized void confirmMatch(String opponentId,
                                          boolean iStartWithToken,
                                          char mySymbol) throws RemoteException {
        if (inGame || !lookingForMatches) {
            return;
        }
        this.inGame = true;
        this.mySymbol = mySymbol;
        this.hasToken = iStartWithToken;
        this.opponentId = opponentId;

        try {
            this.opponent = (PeerService) Naming.lookup("rmi://" + opponentId + "/peer");
        } catch (Exception e) {
            // Avversario non raggiungibile: annullo il match
            this.inGame = false;
            this.opponent = null;
            this.opponentId = null;
            return;
        }

        System.out.println("Match avviato con " + opponentId
                + " | mio simbolo: " + mySymbol
                + " | token: " + hasToken);

        if (hasToken) {
            exec.execute(this::playTurnLoop);
        }
    }

    /**
     * Chiamato dall'avversario quando questo peer deve ricevere il token
     * e giocare il proprio turno.
     */
    @Override
    public synchronized void receiveToken() throws RemoteException {
        if (!inGame) {
            return;
        }
        hasToken = true;
        exec.execute(this::playTurnLoop);
    }

    /**
     * Aggiorna localmente la board con la mossa ricevuta dall'avversario.
     * Se il risultato indica fine partita, delega la gestione ai thread interni.
     */
    @Override
    public synchronized void updateMove(int row, int col, char symbol, char result) throws RemoteException {
        if (!inGame) {
            return;
        }

        if (board.isValid(row, col)) {
            board.apply(row, col, symbol);
        }
        System.out.println("Mossa avversario: " + (row + 1) + " " + (col + 1));

        if (result != ' ') {
            // La partita è terminata: gestisco fine partita su un thread separato
            final char resCopy = result;
            exec.execute(() -> announceResultAndHandleEnd(resCopy));
        }
        // Se la partita non è finita, il token verrà passato via receiveToken()
    }

    // ========================================================================
    // Matchmaking distribuito
    // ========================================================================

    /**
     * Tenta di trovare e instaurare un match con un altro peer libero.
     * Viene invocato periodicamente dal thread di scheduling.
     *
     * Logica:
     * - se il peer è già in partita o non cerca più match, esce;
     * - prende lo snapshot dei peer scoperti;
     * - filtra i peer vivi e non in partita via RMI;
     * - evita di proporre subito un nuovo match all’ultimo avversario;
     * - sceglie un target usando l'ordine lessicografico sugli ID;
     * - propone il match e, se accettato, inizializza lo stato locale.
     */
    private void tryMatchMaking() {
        if (inGame || !lookingForMatches) {
            return;
        }

        // Snapshot dei peer scoperti
        List<String> candidates = new ArrayList<>(discovery.peers());
        candidates.remove(myId);
        Collections.sort(candidates);
        if (candidates.isEmpty()) {
            return;
        }

        // Filtra peer vivi e liberi
        List<String> free = new ArrayList<>();
        for (String id : candidates) {
            try {
                PeerService p = (PeerService) Naming.lookup("rmi://" + id + "/peer");
                if (p.ping()) {
                    free.add(id);
                }
            } catch (Exception ignored) {
            }
        }
        if (free.isEmpty()) {
            return;
        }

        // Evita di rigiocare immediatamente con l'ultimo avversario
        if (lastOpponentId != null && free.contains(lastOpponentId)) {
            if (free.size() == 1) {
                // L'unico candidato è proprio lui: non si instaura un nuovo match
                return;
            } else {
                free.remove(lastOpponentId);
            }
        }

        // Scelta del target: successore lessicografico, altrimenti il più piccolo
        String target = null;
        for (String id : free) {
            if (id.compareTo(myId) > 0) {
                target = id;
                break;
            }
        }
        if (target == null) {
            target = free.get(0);
        }

        try {
            PeerService rem = (PeerService) Naming.lookup("rmi://" + target + "/peer");
            boolean ok = rem.proposeMatch(myId);
            if (!ok) {
                return;
            }

            synchronized (this) {
                // Ricontrollo che nel frattempo non sia cambiato lo stato
                if (inGame || !lookingForMatches) {
                    return;
                }

                this.opponent = rem;
                this.opponentId = target;
                this.inGame = true;

                boolean iStart = myId.compareTo(target) < 0;
                this.mySymbol = iStart ? 'X' : 'O';
                this.hasToken = iStart;

                rem.confirmMatch(myId, !iStart, iStart ? 'O' : 'X');
            }

            System.out.println("Match avviato con " + target
                    + " | mio simbolo: " + mySymbol
                    + " | token: " + hasToken);

            if (hasToken) {
                exec.execute(this::playTurnLoop);
            }
        } catch (Exception ignored) {
        }
    }

    // ========================================================================
    // Logica di gioco (token-based)
    // ========================================================================

    /**
     * Gestisce un turno di gioco quando questo peer possiede il token.
     * Chiede una mossa all'utente, la applica localmente, la notifica
     * all'avversario e, se necessario, passa il token.
     */
    private void playTurnLoop() {
        try {
            if (!inGame || !hasToken) {
                return;
            }

            BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
            int r;
            int c;

            // Richiesta fino a mossa valida
            while (true) {
                printBoardAndStatus();
                System.out.print("Inserisci mossa (riga colonna) o 'quit': ");

                String line = br.readLine();
                if (line == null) {
                    return;
                }
                line = line.trim();

                if (line.equalsIgnoreCase("quit")) {
                    System.out.println("Abbandono partita.");
                        // Salvo un riferimento locale all'avversario (può essere null)
                    PeerService opp = opponent;

                    // Se ho un avversario, lo avviso che la partita è terminata
                    if (opp != null) {
                        notifySafe(opp, PeerService::noRematch);
                    }

                   // Gestisco la chiusura come se avessi ricevuto io stesso un noRematch
                    try {
                        noRematch();  // stampa messaggio, chiama endGame() e askIfStayInQueue()
                    } catch (RemoteException e) {
                        // Chiamata locale non dovrebbe fallire, ma in caso chiudo comunque
                        endGame();
                        askIfStayInQueue();
                    }
                    return;
                }

                String[] parts = line.split("\\s+");
                if (parts.length != 2) {
                    System.out.println("Formato non valido. Inserisci due numeri separati da spazio.");
                    continue;
                }

                try {
                    r = Integer.parseInt(parts[0]) - 1;
                    c = Integer.parseInt(parts[1]) - 1;
                } catch (NumberFormatException e) {
                    System.out.println("Input non numerico. Riprova.");
                    continue;
                }

                synchronized (this) {
                    if (!board.isValid(r, c)) {
                        System.out.println("Mossa non valida. Casella occupata o fuori range. Riprova.");
                        continue;
                    }
                }
                // Mossa sintatticamente valida
                break;
            }

            // Applicazione mossa e notifica all'avversario
            synchronized (this) {
                final int moveR = r;
                final int moveC = c;

                board.apply(moveR, moveC, mySymbol);
                printBoardAndStatus();
                char res = board.check();
                final char result = res;

                notifySafe(opponent, o -> o.updateMove(moveR, moveC, mySymbol, result));

                if (res != ' ') {
                    // Partita finita: annuncio esito e avvio gestione fine partita
                    announceResultAndHandleEnd(result);
                    return;
                }

                // Partita ancora in corso: passo il token all'avversario
                hasToken = false;
                notifySafe(opponent, PeerService::receiveToken);
            }
        } catch (Exception e) {
            System.out.println("Errore turno: " + e);
            endGame();
        }
    }

    /**
     * Annuncia il risultato della partita su questo peer
     * e poi attiva la logica di fine partita/rematch.
     */
    private synchronized void announceResultAndHandleEnd(char res) {
        printBoardAndStatus();

        if (res == 'D') {
            System.out.println("[Notifica] Pareggio.");
        } else if (res == mySymbol) {
            System.out.println("[Notifica] Hai vinto!");
        } else {
            System.out.println("[Notifica] Hai perso!");
        }

        handleGameEnd();
    }

    // ========================================================================
    // Supporto per chiamate RMI sicure
    // ========================================================================

    /**
     * Interfaccia funzionale per incapsulare una chiamata RMI
     * e gestire in un unico punto le eccezioni.
     */
    private interface RmiCall {
        void run(PeerService o) throws Exception;
    }

    /**
     * Esegue una chiamata remota in modo sicuro: in caso di eccezioni RMI
     * la partita viene terminata localmente.
     */
    private void notifySafe(PeerService o, RmiCall call) {
        try {
            call.run(o);
        } catch (NotBoundException | MalformedURLException | RemoteException e) {
            System.out.println("Avversario irraggiungibile. Termino match.");
            endGame();
        } catch (Exception e) {
            System.out.println("Errore remoto: " + e);
            endGame();
        }
    }

    /**
     * Termina la partita corrente e ripristina lo stato interno del peer.
     * Dopo questa chiamata il peer può eventualmente partecipare a nuovi match.
     */
    private synchronized void endGame() {
        inGame = false;
        hasToken = false;

        // Salva ultimo avversario prima di azzerare riferimenti
        String prevOpponent = this.opponentId;
        opponent = null;
        opponentId = null;
        lastOpponentId = prevOpponent;

        board.reset();

        // Reset decisione di rematch
        synchronized (rematchLock) {
            rematchDecision = null;
        }

        System.out.println("Partita terminata. Torno disponibile per un nuovo match.");
    }

    // ========================================================================
    // Logica di rematch
    // ========================================================================

    /**
     * Gestisce la fase finale di una partita e coordina la possibile rivincita.
     *
     * Viene invocato da entrambi i peer, ma solo uno assume il ruolo di
     * coordinatore (quello con ID lessicograficamente minore).
     *
     * Il coordinatore:
     * - chiede la decisione locale (rematch sì/no),
     * - attende la decisione remota via RMI,
     * - se entrambe sono positive, avvia la nuova partita;
     * - altrimenti notifica la chiusura tramite {@link #noRematch()}.
     *
     * Il non-coordinatore:
     * - si limita a raccogliere la decisione dell'utente e a restare in attesa.
     */
    private synchronized void handleGameEnd() {
        hasToken = false;

        synchronized (rematchLock) {
            rematchDecision = null;
        }

        boolean iAmCoordinator =
                opponentId != null && myId.compareTo(opponentId) < 0;

        if (iAmCoordinator) {
            boolean localWantsRematch = promptLocalRematch();
            System.out.println("Attendo la decisione dell'avversario sulla rivincita...");

            boolean remoteWantsRematch;
            try {
                remoteWantsRematch = opponent.getRematchDecision();
            } catch (Exception e) {
                remoteWantsRematch = false;
            }

            if (localWantsRematch && remoteWantsRematch) {
                // Alternanza: chi era 'O' inizia la nuova partita
                boolean iStartNew = (mySymbol == 'O');
                char newMySymbol = (mySymbol == 'X') ? 'O' : 'X';

                try {
                    char oppSymbol = (newMySymbol == 'X') ? 'O' : 'X';
                    opponent.startRematch(!iStartNew, oppSymbol);
                } catch (Exception e) {
                    System.out.println("Avversario irraggiungibile durante la rivincita. Termino match.");
                    endGame();
                    return;
                }

                try {
                    startRematch(iStartNew, newMySymbol);
                } catch (RemoteException e) {
                    System.out.println("Errore nel riavvio della partita: " + e);
                    endGame();
                }
            } else {
                // Almeno un giocatore ha rifiutato
                try {
                    opponent.noRematch();
                } catch (Exception ignored) {
                }
                try {
                    noRematch();
                } catch (RemoteException e) {
                    endGame();
                }
            }
        } else {
            // Non coordinatore: decide localmente e attende la scelta globale
            promptLocalRematch();
        }
    }

    /**
     * Chiede all'utente locale se desidera una rivincita.
     * Qualsiasi input che inizia per 's'/'S' viene interpretato come "sì".
     *
     * @return true se il giocatore vuole una nuova partita, false altrimenti
     */
    private boolean promptLocalRematch() {
        boolean wants = false;
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
            System.out.print("Vuoi giocare un'altra partita? (s/n): ");
            String resp = br.readLine();
            if (resp != null) {
                resp = resp.trim().toLowerCase();
                wants = resp.startsWith("s");
            }
        } catch (Exception e) {
            wants = false;
        }

        synchronized (rematchLock) {
            rematchDecision = wants;
            rematchLock.notifyAll();
        }
        return wants;
    }

    @Override
    public boolean getRematchDecision() throws RemoteException {
        synchronized (rematchLock) {
            while (rematchDecision == null) {
                try {
                    rematchLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return rematchDecision;
        }
    }

    @Override
    public synchronized void startRematch(boolean iStartWithToken, char newSymbol)
            throws RemoteException {
        this.mySymbol = newSymbol;
        this.hasToken = iStartWithToken;
        this.inGame = true;
        board.reset();

        synchronized (rematchLock) {
            rematchDecision = null;
        }

        System.out.println("Inizia una nuova partita con " + opponentId
                + " | mio simbolo: " + mySymbol
                + " | token: " + hasToken);

        if (hasToken) {
            exec.execute(this::playTurnLoop);
        }
    }

    @Override
    public synchronized void noRematch() throws RemoteException {
        System.out.println("L'avversario ha rifiutato la rivincita o non si e' raggiunto l'accordo.");
        endGame();
        askIfStayInQueue();
    }

    /**
     * Chiede all'utente se desidera continuare a partecipare al matchmaking
     * automatico dopo una partita terminata senza rivincita.
     *
     * Se l'utente risponde "s", il peer resta nel sistema.
     * Se risponde "n", il peer termina la propria esecuzione.
     */
    private void askIfStayInQueue() {
        boolean wants = false;
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
            System.out.print("Vuoi cercare automaticamente un nuovo avversario? (s/n): ");
            String resp = br.readLine();
            if (resp != null) {
                resp = resp.trim().toLowerCase();
                wants = resp.startsWith("s");
            }
        } catch (Exception e) {
            wants = false;
        }

        lookingForMatches = wants;
        // Uscita non pulita

        /*if (!wants) {
            System.out.println("Ok, non cercherò più automaticamente nuovi avversari.");
            try {
                exec.shutdownNow();
            } catch (Exception ignored) {
            }
            System.exit(0);
        } else {
            System.out.println("Rimango in ricerca automatica di nuovi avversari.");
        }*/

        // Uscita pulita: chiudo il socket multicast, lascio il gruppo e fermo i thread di sender/receiver
        if (!wants) {
        System.out.println("Ok, non cerchero' più automaticamente nuovi avversari.");

        // Non cerco più match
        lookingForMatches = false;

        // Fermiamo il thread pool (matchmaking + liveness check)
        try {
            exec.shutdownNow();
        } catch (Exception ignored) {
        }

        // Chiudiamo il componente di discovery (multicast)
        try {
            discovery.close();
        } catch (Exception e) {
            System.out.println("Errore durante la chiusura del discovery: " + e);
        }

        // A questo punto il peer può uscire dal processo in modo pulito
        System.exit(0);
    } else {
        System.out.println("Rimango in ricerca automatica di nuovi avversari.");
    }

    }

    // ========================================================================
    // Utility di stampa e binding RMI
    // ========================================================================

    /**
     * Stampa a schermo lo stato corrente:
     * id del peer, simbolo, possesso del token e griglia di gioco.
     */
    private void printBoardAndStatus() {
        System.out.println("=== " + myId
                + " | simbolo " + mySymbol
                + " | token " + hasToken + " ===");
        if (opponentId != null) {
            System.out.println("Avversario: " + opponentId);
        }
        System.out.println(board.render());
    }

    /**
     * Registra questo peer nel registry RMI locale alla porta configurata.
     * Se il registry non esiste, viene creato. L'oggetto viene esposto
     * sotto il nome logico "peer".
     *
     * @throws Exception se la fase di binding fallisce
     */
    public void bind() throws Exception {
        try {
            LocateRegistry.createRegistry(myPort);
        } catch (Exception ignored) {
        }

        Naming.rebind("rmi://" + myHost + ":" + myPort + "/peer", this);
        System.out.println("Peer registrato su rmi://" + myHost + ":" + myPort + "/peer");
    }

    /**
     * Controlla periodicamente se l'avversario è ancora raggiungibile.
     * Se la chiamata RMI fallisce, notifySafe() termina il match con endGame().
     */
    private void checkOpponentLiveness() {
        // Se non sono in partita o non ho un avversario noto, non faccio nulla
        if (!inGame || opponent == null) {
            return;
        }

        // Uso notifySafe così qualsiasi RemoteException porta a endGame()
        notifySafe(opponent, PeerService::ping);
    }

}