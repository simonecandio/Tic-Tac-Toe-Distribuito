package gamep2p;

import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * Interfaccia remota che definisce il contratto RMI tra peer
 * nell’applicazione distribuita di Tic-Tac-Toe.
 *
 * Tutti i metodi dichiarati possono essere invocati da altri peer
 * tramite RMI e devono pertanto dichiarare {@link RemoteException}.
 *
 * L'interfaccia copre:
 * - operazioni di liveness (ping);
 * - matchmaking distribuito;
 * - coordinamento del token per la turnazione;
 * - scambio di mosse di gioco;
 * - protocollo di rivincita basato su consenso a due.
 */
public interface PeerService extends Remote {

    /**
     * Verifica che il peer sia raggiungibile.
     *
     * @return true se il peer è attivo.
     * @throws RemoteException se la comunicazione remota fallisce.
     */
    boolean ping() throws RemoteException;

    /**
     * Restituisce l'identificatore testuale del peer
     * nella forma "host:porta".
     *
     * @return id del peer.
     * @throws RemoteException se la comunicazione remota fallisce.
     */
    String getId() throws RemoteException;

    /**
     * Indica se il peer è attualmente coinvolto in una partita.
     *
     * @return true se il peer è in partita.
     * @throws RemoteException se la comunicazione remota fallisce.
     */
    boolean isInGame() throws RemoteException;

    /**
     * Richiede l’instaurazione di un match.
     * Usato dal proponente nel meccanismo di matchmaking distribuito.
     *
     * @param proposerId id del peer che propone il match.
     * @return true se il match viene accettato, false in caso contrario.
     * @throws RemoteException se la comunicazione remota fallisce.
     */
    boolean proposeMatch(String proposerId) throws RemoteException;

    /**
     * Conferma l’avvio di un match precedentemente proposto.
     *
     * @param opponentId        id dell’avversario.
     * @param iStartWithToken   true se questo peer inizia la partita.
     * @param mySymbol          simbolo assegnato ('X' o 'O').
     * @throws RemoteException se la comunicazione remota fallisce.
     */
    void confirmMatch(String opponentId,
                      boolean iStartWithToken,
                      char mySymbol) throws RemoteException;

    /**
     * Notifica al peer che è il suo turno: riceve il token.
     *
     * @throws RemoteException se la comunicazione remota fallisce.
     */
    void receiveToken() throws RemoteException;

    /**
     * Notifica una mossa di gioco effettuata dall’avversario.
     *
     * @param row     riga (0-2)
     * @param col     colonna (0-2)
     * @param symbol  simbolo giocato ('X' o 'O')
     * @param result  risultato della partita dopo la mossa:
     *                 - 'X' o 'O' se c’è un vincitore
     *                 - 'D' per pareggio
     *                 - ' ' se la partita continua
     *
     * @throws RemoteException se la comunicazione remota fallisce.
     */
    void updateMove(int row,
                    int col,
                    char symbol,
                    char result) throws RemoteException;

    /**
     * Restituisce la decisione locale sulla rivincita.
     * Il metodo è bloccante finché l’utente non risponde.
     *
     * @return true se il peer desidera la rivincita.
     * @throws RemoteException se la comunicazione remota fallisce.
     */
    boolean getRematchDecision() throws RemoteException;

    /**
     * Avvia una nuova partita (rematch) con l’avversario corrente,
     * reimpostando simboli, token e board.
     *
     * @param iStartWithToken true se questo peer deve iniziare.
     * @param newSymbol       nuovo simbolo assegnato ('X' o 'O').
     * @throws RemoteException se la comunicazione remota fallisce.
     */
    void startRematch(boolean iStartWithToken,
                      char newSymbol) throws RemoteException;

    /**
     * Notifica la chiusura definitiva della partita
     * (almeno un peer ha rifiutato la rivincita).
     *
     * @throws RemoteException se la comunicazione remota fallisce.
     */
    void noRematch() throws RemoteException;
}
