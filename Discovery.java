package gamep2p;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Componente dedicato al discovery dei peer tramite multicast UDP.
 *
 * Ogni peer invia periodicamente un messaggio di annuncio ("HELLO <id>")
 * a un gruppo multicast predefinito e in parallelo ascolta quelli inviati
 * dagli altri nodi. Le informazioni raccolte vengono mantenute in un set
 * thread-safe, aggiornato dinamicamente.
 *
 * In modalità avanzata (gossip abilitato) ogni peer diffonde anche una vista
 * dei peer conosciuti, permettendo un protocollo di membership di tipo epidemico.
 *
 * L’oggetto implementa AutoCloseable per permettere una chiusura ordinata
 * delle risorse (socket multicast, thread di ascolto).
 */
public class Discovery implements AutoCloseable {

    private final String myId;                       // Identificatore del peer (host:port)
    private final InetAddress group;                 // Indirizzo multicast
    private final int port = 50000;                  // Porta multicast condivisa
    private final MulticastSocket socket;            // Socket multicast per invio/ricezione

    // Vista dei peer scoperti (solo ID)
    private final Set<String> peers = new CopyOnWriteArraySet<>();

    // In modalità gossip: timestamp dell’ultima volta in cui abbiamo "visto" il peer
    private final ConcurrentHashMap<String, Long> lastSeen = new ConcurrentHashMap<>();

    // true = gossip completo (HELLO + GOSSIP + cleaner), false = discovery semplice (solo HELLO)
    private final boolean ENABLE_GOSSIP;
    private static final boolean DEBUG = false;  // attiva/disattiva debug
    private static final int HELLO_PERIOD_MS = 2000;  // 2 secondi tra un HELLO e l'altro

    // Stato globale del componente
    private volatile boolean running = true;


    /**
     * Crea un'istanza del componente di discovery.
     * L’oggetto si unisce al gruppo multicast e avvia subito i thread di
     * invio e ricezione. In base al flag enableGossip abilita o meno
     * la logica di gossip (scambio di viste + cleaner).
     *
     * @param myId          identificatore univoco del peer (host:port)
     * @param enableGossip  true per attivare la modalità gossip completa,
     *                      false per usare solo HELLO multicast
     * @throws IOException in caso di errore durante il join del gruppo
     */
    public Discovery(String myId, boolean enableGossip) throws IOException {
        this.myId = myId;
        this.ENABLE_GOSSIP = enableGossip;

        // Gruppo multicast utilizzato per la comunicazione fra peer
        this.group = InetAddress.getByName("239.0.0.1");

        // Socket multicast condivisa sulla porta fissa
        this.socket = new MulticastSocket(port);

        // Associazione all'interfaccia di rete del sistema
        NetworkInterface nif =
                NetworkInterface.getByInetAddress(InetAddress.getLocalHost());

        // Join del gruppo multicast
        this.socket.joinGroup(new InetSocketAddress(group, port), nif);

        // Avvio dei thread di discovery
        startReceiver();
        startSender();
        if (ENABLE_GOSSIP) {
            startCleaner(); // il cleaner ha senso solo se usiamo lastSeen/gossip
        }
    }

    /**
     * Restituisce l’insieme degli ID dei peer scoperti.
     * L'insieme è thread-safe e si aggiorna dinamicamente.
     * L’ID locale non viene incluso.
     *
     * @return insieme degli ID dei peer rilevati
     */
    public Set<String> peers() {
        return peers;
    }

    // ============================
    //  SENDER (HELLO + opz. GOSSIP)
    
    private void startSender() {
        Thread t = new Thread(() -> {
            while (running) {
                try {
                    // 1) HELLO (sempre, in entrambe le modalità)
                    sendHello();
                    Thread.sleep(HELLO_PERIOD_MS);

                } catch (Exception ignored) {}
            }
        }, "discovery-sender");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Invia un messaggio HELLO <myId> in multicast.
     */
    private void sendHello() {
        try {
            String hello = "HELLO " + myId;
            DatagramPacket p = new DatagramPacket(
                    hello.getBytes(StandardCharsets.UTF_8),
                    hello.length(),
                    group,
                    port
            );
            socket.send(p);
        if (DEBUG) System.out.println("[HELLO][TX][" + myId + "] Inviato HELLO");
        } catch (Exception ignored) {}
    }

     // =========================================================
    //          INVIO GOSSIP SOLO QUANDO LA VISTA CAMBIA

    private void triggerGossip() {
        if (!ENABLE_GOSSIP || peers.isEmpty()) return;

        try {
            // seleziono un peer casuale
            String[] list = peers.toArray(new String[0]);
            String targetId = list[(int)(Math.random() * list.length)];
            String host = targetId.split(":")[0];

            long now = System.currentTimeMillis();
            StringBuilder sb = new StringBuilder();

            sb.append(myId).append(";").append(now);

            for (String id : peers) {
                long ts = lastSeen.getOrDefault(id, now);
                sb.append(",").append(id).append(";").append(ts);
            }


            String payload = "GOSSIP " + sb;

            DatagramPacket pkt = new DatagramPacket(
                    payload.getBytes(StandardCharsets.UTF_8),
                    payload.length(),
                    InetAddress.getByName(host),
                    port
            );

            socket.send(pkt);

            if (DEBUG) {
                System.out.println("[GOSSIP][TX] → " + targetId);
                System.out.println("   payload=" + payload);
            }

        } catch (Exception ignored) {}
    }



    // =======================
    //  RECEIVER (HELLO/GOSSIP)

    private void startReceiver() {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[4096];
            while (running) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    socket.receive(p);
                    String msg = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);

                    if (DEBUG) {
                    System.out.println("\n[RX] Pacchetto ricevuto:");
                    System.out.println("   → Contenuto: " + msg);
                    }

                    if (msg.startsWith("HELLO ")) {
                        String id = msg.substring(6).trim();
                        handleHello(id);
                    } else if (ENABLE_GOSSIP && msg.startsWith("GOSSIP ")) {
                        String payload = msg.substring(7).trim();
                        mergeGossip(payload);
                    }

                } catch (IOException ignored) {}
            }
        }, "discovery-receiver");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Gestisce un messaggio HELLO <id>: aggiunge il peer alla vista.
     * In modalità gossip aggiorna anche lastSeen.
     */
    private void handleHello(String id) {
    if (id.equals(myId)) {
        return; // ignoro me stesso
    }

    long now = System.currentTimeMillis();

    // 1) aggiungo/aggiorno il peer nella vista
    boolean isNewPeer = peers.add(id);

    if (ENABLE_GOSSIP) {
        // 2) aggiorno SEMPRE lastSeen quando ricevo un HELLO
        lastSeen.put(id, now);
    }

    if (DEBUG) {
        System.out.println("[HELLO][RX] Ricevuto HELLO da " + id);
        System.out.println("   → peers attuali: " + peers);
    }

    // 3) faccio partire il gossip SOLO quando scopro veramente un nuovo peer
    if (ENABLE_GOSSIP && isNewPeer) {
        triggerGossip();
    }
}


    // =====================
    //  LOGICA DI GOSSIP

    /**
     * Effettua il merge di una vista ricevuta via GOSSIP.
     * Il payload è del tipo: "id1:ts1,id2:ts2,..."
     */
   private void mergeGossip(String payload) {
    String[] entries = payload.split(",");
    long now = System.currentTimeMillis();
    long maxStaleness = 15000;

    for (String e : entries) {
        if (e == null || e.trim().isEmpty()) continue;

        String[] parts = e.split(";");
        if (parts.length != 2) continue;

        String id = parts[0].trim();
        if (id.equals(myId)) continue;

        try {
            long ts = Long.parseLong(parts[1].trim());

            if (now - ts > maxStaleness) continue;

            long oldTs = lastSeen.getOrDefault(id, -1L);

            if (ts > oldTs) {
                // la vista è cambiata
                lastSeen.put(id, ts);
                peers.add(id);

                if (DEBUG) {
                    System.out.println("[GOSSIP][MERGE] Aggiornato peer:");
                    System.out.println("   → id = " + id);
                    System.out.println("   → oldTS = " + oldTs);
                    System.out.println("   → newTS = " + ts);
                }
            }

        } catch (NumberFormatException ignored) {}
    }

    if (DEBUG) {
        System.out.println("[GOSSIP][MERGE] Vista attuale:");
        System.out.println("   → peers = " + peers);
        System.out.println("   → lastSeen = " + lastSeen);
    }
}

    /**
     * Thread di pulizia periodica: rimuove i peer che non vengono più
     * visti da oltre una certa soglia di tempo (timeout).
     * Attivo solo in modalità gossip.
     */
    private void startCleaner() {
        Thread t = new Thread(() -> {
            while (running) {
                try {
                    long now = System.currentTimeMillis();
                    long timeout = 60_000; // 10 secondi

                    for (String id : peers) {
                        long ts = lastSeen.getOrDefault(id, 0L);

                        if (now - ts > timeout) {
                            peers.remove(id);
                            lastSeen.remove(id);

                        if (DEBUG) {
                        System.out.println("[CLEANER] Peer rimosso per inattività:");
                        System.out.println("   → " + id);
                        }

                        }
                    }

                    Thread.sleep(5000);

                    if (DEBUG) {
                    System.out.println("[CLEANER] Vista dopo pulizia:");
                    System.out.println("   → peers = " + peers);
                    }


                } catch (Exception ignored) {}
            }
        }, "cleaner-thread");
        t.setDaemon(true);
        t.start();
    }

    // =====================
    //  CHIUSURA RISORSE

    /**
     * Chiude il componente di discovery.
     * Ferma i thread di invio e ricezione, lascia il gruppo multicast e
     * rilascia la socket associata.
     */
    @Override
    public void close() {
        running = false;

        try {
            NetworkInterface nif =
                    NetworkInterface.getByInetAddress(InetAddress.getLocalHost());
            socket.leaveGroup(new InetSocketAddress(group, port), nif);
        } catch (Exception ignored) {
        }

        socket.close();
    }
}
