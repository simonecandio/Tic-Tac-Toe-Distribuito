package gamep2p;

import java.net.InetAddress;
import java.net.ServerSocket;

/**
 * Punto di ingresso di un peer nell'applicazione distribuita di
 * Tic-Tac-Toe (Tris).
 *
 * Ogni istanza di questo programma:
 * - si registra tramite Java RMI su una porta locale,
 * - partecipa al protocollo di discovery per individuare altri peer,
 * - si abbina automaticamente ad un altro peer libero,
 * - prende parte al protocollo di gioco basato su token implementato in {@link PeerImpl}.
 *
 * Questa classe si limita a creare e registrare il peer, mantenendo poi
 * il processo in vita. Tutta la logica applicativa è demandata a {@link PeerImpl}.
 */
public class AutoPeerMain {

    /**
     * Avvia un nuovo peer nel sistema.
     *
     * Utilizzo tipico:
     * {@code java gamep2p.AutoPeerMain} o {@code java gamep2p.AutoPeerMain localhost 9800}
     *
     * Gestione degli argomenti:
     * - nessun argomento: host = "localhost", porta scelta automaticamente;
     * - un argomento: host = args[0], porta scelta automaticamente;
     * - due argomenti: host = args[0], porta = Integer.parseInt(args[1]).
     *
     * @param args host e/o porta opzionali
     * @throws Exception se l'inizializzazione del peer fallisce
     */
    public static void main(String[] args) throws Exception {
        final String host;
        final int port;

        // Determinazione di host e porta in base agli argomenti
        if (args.length == 0) {
            host = InetAddress.getLocalHost().getHostAddress();
            port = findFreePort();
        } else if (args.length == 1) {
            host = args[0];
            port = findFreePort();
        } else {
            host = args[0];
            port = Integer.parseInt(args[1]);
        }

        // Creazione e registrazione dell'oggetto remoto
        PeerImpl peer = new PeerImpl(host, port);
        peer.bind();

        System.out.println("Peer avviato su rmi://" + host + ":" + port + "/peer");
        System.out.println("Auto-discovery attivo: il peer si accoppiera' automaticamente quando trova un avversario libero.");
        System.out.println("Per avviare un peer: java gamep2p.AutoPeerMain [host] [porta opzionale].");

        // Mantiene la JVM attiva: la logica effettiva gira su thread interni
        synchronized (AutoPeerMain.class) {
            AutoPeerMain.class.wait();
        }
    }

    /**
     * Restituisce una porta libera sul sistema locale.
     *
     * La strategia consiste nell'aprire un {@link ServerSocket} sulla porta 0,
     * delegando al sistema operativo la scelta di una porta disponibile.
     * Una volta ottenuto il numero, il socket viene immediatamente chiuso.
     *
     * @return un numero di porta attualmente libero
     * @throws Exception se non è possibile allocare una porta
     */
    private static int findFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
