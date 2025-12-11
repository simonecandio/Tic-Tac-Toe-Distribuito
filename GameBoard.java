package gamep2p;

/**
 * Rappresentazione mutabile della griglia 3x3 del gioco del Tris (Tic-Tac-Toe).
 *
 * La classe fornisce operazioni sincronizzate per:
 * - azzerare la griglia,
 * - verificare la validità di una mossa,
 * - applicare una mossa,
 * - determinare l'esito della partita.
 *
 * Tutti i metodi pubblici sono synchronized per garantire coerenza dello stato
 * anche in presenza di callback remoti asincroni che accedono alla board da
 * thread diversi.
 */
public class GameBoard {

    /** Griglia 3x3: ogni cella contiene 'X', 'O' oppure spazio per casella vuota. */
    private final char[][] board = new char[3][3];

    /**
     * Costruisce una board vuota, inizializzando tutte le caselle a spazio.
     */
    public GameBoard() {
        reset();
    }

    /**
     * Reimposta la board in stato iniziale, con tutte le caselle vuote.
     * Il metodo è sincronizzato per evitare condizioni di race con letture
     * e scritture concorrenti.
     */
    public synchronized void reset() {
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                board[r][c] = ' ';
            }
        }
    }

    /**
     * Verifica se una mossa alle coordinate specificate è valida.
     * Una mossa è considerata valida se:
     * - la riga è compresa tra 0 e 2;
     * - la colonna è compresa tra 0 e 2;
     * - la casella corrispondente è attualmente vuota.
     *
     * @param row indice di riga (0–2)
     * @param col indice di colonna (0–2)
     * @return true se la mossa è valida, false altrimenti
     */
    public synchronized boolean isValid(int row, int col) {
        return row >= 0 && row < 3
                && col >= 0 && col < 3
                && board[row][col] == ' ';
    }

    /**
     * Applica una mossa alla board. Non viene eseguito alcun controllo di
     * validità: si assume che il chiamante abbia già verificato la mossa
     * tramite {@link #isValid(int, int)}.
     *
     * @param row    indice di riga (0–2)
     * @param col    indice di colonna (0–2)
     * @param symbol simbolo da inserire ('X' o 'O')
     */
    public synchronized void apply(int row, int col, char symbol) {
        board[row][col] = symbol;
    }

    /**
     * Determina lo stato attuale della partita.
     *
     * Possibili valori di ritorno:
     * - 'X' se il giocatore X ha una linea vincente;
     * - 'O' se il giocatore O ha una linea vincente;
     * - 'D' se la board è piena e non ci sono linee vincenti (pareggio);
     * - spazio (' ') se la partita è ancora in corso.
     *
     * @return 'X', 'O', 'D' oppure spazio
     */
    public synchronized char check() {
        // Controllo righe e colonne
        for (int i = 0; i < 3; i++) {
            // Riga i
            if (board[i][0] != ' '
                    && board[i][0] == board[i][1]
                    && board[i][1] == board[i][2]) {
                return board[i][0];
            }
            // Colonna i
            if (board[0][i] != ' '
                    && board[0][i] == board[1][i]
                    && board[1][i] == board[2][i]) {
                return board[0][i];
            }
        }

        // Controllo diagonali
        if (board[1][1] != ' ') {
            boolean mainDiagonal =
                    board[0][0] == board[1][1] && board[1][1] == board[2][2];
            boolean antiDiagonal =
                    board[0][2] == board[1][1] && board[1][1] == board[2][0];

            if (mainDiagonal || antiDiagonal) {
                return board[1][1];
            }
        }

        // Se esiste almeno una casella vuota, la partita è ancora in corso
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                if (board[r][c] == ' ') {
                    return ' ';
                }
            }
        }

        // Nessun vincitore e nessuna casella vuota: pareggio
        return 'D';
    }

    /**
     * Restituisce una rappresentazione testuale della board,
     * con le tre righe separate da newline e righe di separazione intermedie.
     *
     * Esempio:
     * X|O| 
     * -----
     *  |X| 
     * -----
     *  | |O
     *
     * @return stringa che rappresenta lo stato corrente della griglia
     */
    public synchronized String render() {
        StringBuilder sb = new StringBuilder();

        for (int r = 0; r < 3; r++) {
            sb.append(board[r][0])
              .append('|')
              .append(board[r][1])
              .append('|')
              .append(board[r][2]);

            if (r < 2) {
                sb.append('\n');
                sb.append("-----").append('\n');
            }
        }

        return sb.toString();
    }
}