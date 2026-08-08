# ludus — Documento di Design

Motore di scacchi UCI in Java, in due atti: valutazione scritta a mano, poi rete neurale NNUE.

> **Nota sulla lingua.** Questo documento è in italiano perché è il nostro documento di lavoro.
> Quando il repo diventa pubblico, `README.md` e `DESIGN.md` vanno in inglese: è un progetto
> vetrina e la platea non è italiana.

---

## 0. Obiettivi

**Cosa deve essere vero alla fine.**

1. Il motore è **corretto**: perft esatto su tutta la suite standard.
2. Il motore è **forte**: Elo misurato, non stimato a occhio.
3. L'**Atto II è un innesto, non una riscrittura**: sostituire la valutazione a mano con la NNUE non deve toccare un solo file di `search`.
4. Ogni miglioramento è **dimostrato da un numero**, mai da un'impressione.

**Non-obiettivi dichiarati** (per difendersi dallo scope creep, che è il modo standard in cui questi progetti muoiono):

| Fuori scope | Perché |
|---|---|
| Interfaccia grafica propria | UCI ti dà Arena, Cute Chess, En Croissant gratis |
| Ricerca parallela (Lazy SMP) | Raddoppia la difficoltà di debug della ricerca. Dopo l'Atto II, se mai |
| Tablebase Syzygy | Elo facile ma zero contenuto interessante |
| Libro di apertura | Idem |
| Pondering | Complica il time management senza insegnare niente |

---

## 1. Metriche e oracoli

Questa sezione viene **prima** dell'architettura, ed è deliberato: gli oracoli sono il motivo per cui abbiamo scelto questo progetto. Vanno costruiti per primi, non aggiunti dopo.

### 1.1 Perft — l'oracolo di correttezza

`perft(n)` conta le foglie dell'albero legale a profondità `n`. I conteggi sono tabulati per posizioni standard: se il tuo numero non torna, hai un bug — e il **divide** (perft per ogni mossa alla radice) ti dice in quale ramo cercarlo. È un debugger di move generation, non solo un test.

| Posizione | d1 | d2 | d3 | d4 | d5 | d6 |
|---|---|---|---|---|---|---|
| Initial | 20 | 400 | 8 902 | 197 281 | 4 865 609 | 119 060 324 |
| Kiwipete | 48 | 2 039 | 97 862 | 4 085 603 | 193 690 690 | — |
| Position 3 | 14 | 191 | 2 812 | 43 238 | 674 624 | 11 030 083 |
| Position 4 | 6 | 264 | 9 467 | 422 333 | 15 833 292 | — |
| Position 5 | 44 | 1 486 | 62 379 | 2 103 487 | 89 941 194 | — |
| Position 6 | 46 | 2 079 | 89 890 | 3 894 594 | — | — |

> **Verifica questi numeri contro il Chess Programming Wiki prima di codificarli nei test.** Sono i valori standard, ma un test che asserisce il numero sbagliato è peggio di nessun test: ti fa cercare un bug che non esiste, o peggio, valida un bug che esiste.

Le FEN delle posizioni 3–6 e di Kiwipete stanno su CPW alla pagina "Perft Results" — mettile in un file di risorse `perft-suite.txt` nel formato `FEN;d1 n1;d2 n2;...`, così la suite è dati e non codice.

### 1.2 Elo — l'oracolo di forza

Nessun cambiamento alla ricerca o alla valutazione entra senza un match. Strumento: **fastchess** (o `cutechess-cli`).

- Time control corto e fisso: `10+0.1` o `8+0.08`.
- Avversario: la versione precedente di `ludus` stessa.
- Aperture da un book `.epd` bilanciato, colori invertiti a coppie.
- **SPRT** con `elo0=0, elo1=5, alpha=beta=0.05`: si ferma da solo quando ha una risposta, invece di farti giocare 20 000 partite inutili.

Regola dura: *se l'SPRT non passa, la patch non entra*, per quanto elegante sia il codice. È la disciplina che separa un motore che migliora da uno che si gonfia.

### 1.3 nps — l'oracolo di performance

Benchmark JMH su un set fisso di ~30 posizioni, a profondità fissa. Serve a distinguere i due modi in cui una patch può fallire: *cerca peggio* (nps uguale, Elo giù) contro *cerca più lentamente* (nps giù). Sono bug diversi e senza questa metrica li confondi.

**Baseline di M0**, misurato dalla suite perft profonda: **36–55 milioni di nodi al secondo**, dove un nodo comprende generazione pseudo-legale, `makeMove`, verifica di legalità e `unmakeMove`. Circa 610 milioni di nodi in 12,6 secondi su tutti i 32 casi. Non è ancora l'nps della ricerca — non c'è ricerca — ma è il tetto contro cui misurarla, e conferma che la disciplina zero-allocazione di §3.3 sta pagando.

### 1.4 L'invariante dell'accumulatore — l'oracolo dell'Atto II

L'aggiornamento incrementale della NNUE deve dare **esattamente** lo stesso risultato del ricalcolo da zero, bit per bit. Questo è un test, non una speranza. Dettagli in §7.4.

---

## 2. Architettura

### 2.1 Moduli

Maven multi-modulo, JDK 24.

> **Scostamenti dalla prima stesura, registrati qui perché un design doc che non segue la realtà smette di essere utile.** La build era prevista in Gradle: è Maven, per scelta esplicita. E il target era JDK 25 LTS: è 24, la versione installata sulla macchina di sviluppo. Nessuna delle due cambia niente di sostanziale — la Vector API dell'Atto II è in incubator in entrambe.

```
ludus/
├── ludus-core/      board, move gen, zobrist, FEN, move encoding
├── ludus-eval/      interfaccia Evaluator + HCE (valutazione a mano)
├── ludus-search/    alpha-beta, TT, ordering, time management
├── ludus-nnue/      implementazione NNUE di Evaluator        [Atto II]
├── ludus-uci/       protocollo UCI, entry point, fat jar
└── ludus-tools/     perft runner, JMH, generatore dataset, self-play
```

**Perché multi-modulo e non un solo progetto con package.** Non è ceremonia: i moduli hanno dipendenze *realmente diverse*. `ludus-nnue` richiede `--add-modules jdk.incubator.vector`; `ludus-tools` tira dentro JMH e roba di I/O che non deve finire nel jar spedito a Cute Chess. E soprattutto il grafo delle dipendenze rende il seam dell'Atto II **impossibile da violare per sbaglio**: `ludus-search` dipende da `ludus-eval` e non conosce l'esistenza di `ludus-nnue`. Se un giorno ti viene la tentazione di chiamare codice NNUE dalla ricerca, il compilatore ti fermerà. Con i package non ti fermerebbe nessuno.

Il grafo è aciclico e a senso unico:

```
uci ──► search ──► eval ──► core
 │                   ▲
 └────► nnue ────────┘   (nnue implementa eval, search non lo sa)
```

`ludus-uci` è l'unico punto che sa quale `Evaluator` istanziare. È il *composition root*.

### 2.2 Package base

`io.github.lorenzovicino.ludus.*` — reverse-domain corretto per un progetto ospitato su GitHub Pages.

---

## 3. Rappresentazione della posizione

### 3.1 Bitboard

```java
long[] byType   = new long[6];  // PAWN, KNIGHT, BISHOP, ROOK, QUEEN, KING
long[] byColor  = new long[2];  // WHITE, BLACK
long   occupied;                // derivato, mantenuto per non ricalcolarlo
```

**Scelta: 6 + 2 invece di 12 bitboard separati.** Occupa meno cache (8 long contro 12) al prezzo di un `AND` in più per ottenere "cavalli bianchi". La ricerca è dominata dai cache miss, non dalle ALU, quindi il compromesso è nella direzione giusta.

Stato aggiuntivo:

```java
int  sideToMove;
int  castlingRights;   // 4 bit: WK, WQ, BK, BQ
int  epSquare;         // -1 se assente
int  halfmoveClock;    // per la regola delle 50 mosse
long zobrist;          // mantenuto incrementalmente
```

### 3.2 Zobrist hashing

Chiavi casuali con seed **fisso e hardcoded**: la riproducibilità è indispensabile per debuggare (un bug che dipende da chiavi casuali diverse a ogni run è un incubo).

Aggiornamento incrementale in `makeMove`/`unmakeMove`. Componenti da ricordare: pezzo×casa, diritti di arrocco, casa di en passant, turno. Le prime due dimenticanze classiche sono i diritti di arrocco che decadono e la casa di en passant che scade — entrambe producono collisioni sottili nella TT che si manifestano come mosse assurde una volta ogni diecimila partite.

### 3.3 make / unmake, non copy-make

`Board` è **mutabile**, con uno stack di `UndoInfo` (pezzo catturato, diritti di arrocco precedenti, casa ep precedente, halfmove clock precedente, zobrist precedente).

**Perché make/unmake e non copiare la posizione.** Copy-make è più semplice e immune a una classe intera di bug, ma alloca a ogni nodo. In Java questo significa pressione sull'allocatore in un ciclo che gira dieci milioni di volte al secondo: anche con TLAB e un GC generazionale, è il tipo di codice che passa il tempo in `System.gc` invece che a cercare. Make/unmake è la scelta giusta, e §8.2 descrive il test che la rende sicura.

**Disciplina zero-allocazione nella ricerca.** Regola non negoziabile per il codice sotto `search` e `eval`:

- Nessun `new` nel percorso caldo. Mai.
- Le liste di mosse sono `int[]` preallocati, uno per ply, in un array bidimensionale indicizzato dalla profondità.
- Nessun oggetto `Move`, nessun `Optional`, nessuno stream, nessun boxing. Niente lambda che catturano.
- Verifica, non fiducia: profila con JFR e conferma che l'allocation rate durante una ricerca lunga sia **piatto**. Se non lo è, hai un `new` nascosto e va trovato.

Questa è una delle parti più interessanti da raccontare nel README: *cosa cambia a scrivere codice ad alte prestazioni sulla JVM invece che in C++*. È contenuto originale, non un riassunto di CPW.

### 3.4 Codifica delle mosse

Una mossa è un **`int`**, non un oggetto:

```
bit  0-5   from square
bit  6-11  to square
bit 12-15  flags
```

I 4 bit di flag seguono la codifica standard CPW: quiet, double pawn push, arrocco corto, arrocco lungo, cattura, cattura en passant, e le quattro promozioni × {con cattura, senza}. Stanno in 16 bit; usiamo un `int` perché sulla JVM non guadagni nulla a usare `short` e perdi in conversioni implicite.

Lo `0` non è una mossa valida (from == to == 0), quindi funziona come sentinella "nessuna mossa" senza bisogno di un valore speciale.

---

## 4. Move generation

### 4.1 Attacchi

- **Cavallo, re, pedone**: tabelle precalcolate, `long[64]`. Banale.
- **Alfiere, torre, donna**: **magic bitboard**. Tabelle di attacco indicizzate da `(occupancy & mask) * magic >>> shift`.

Nota per chi arriva dal C++: l'istruzione `PEXT` di BMI2, che è la via moderna e più semplice, **non è raggiungibile da Java** in modo portabile. Le magic sono l'unica strada.

**Scostamento, e il ragionamento che l'ha causato.** Questa sezione diceva di usare magic già pubblicate invece di cercarle a runtime. L'implementazione le cerca, con seed fisso, e il motivo è che una magic sbagliata **non fallisce in modo rumoroso**: restituisce in silenzio l'attacco corretto per l'occupancy sbagliata, e il sintomo emerge come una mossa impossibile mille nodi più tardi. Trascrivere a mano 128 costanti da 64 bit è esattamente il compito in cui una cifra sbagliata sopravvive alla revisione.

Una ricerca non può commettere quell'errore, perché **verifica** ogni candidata contro l'implementazione di riferimento per tutte le occupancy prima di accettarla: le tabelle sono corrette per costruzione invece che per trascrizione. Il costo è qualche decina di millisecondi all'avvio, pagato una volta; se un giorno dovesse pesare, le magic trovate si possono stampare e fissare. I seed fissi rendono la ricerca deterministica, quindi ogni run di ogni build produce tabelle identiche byte per byte.

È il principio di §4.2 applicato un livello più in basso: il codice lento e ovviamente corretto esiste per validare quello veloce.

### 4.2 Strategia in due tempi (deliberata)

**Tempo 1 — pseudo-legale + filtro.** Genera tutte le mosse ignorando gli scacchi, poi per ciascuna fai `makeMove`, controlli se il tuo re è attaccato, e `unmakeMove`. Lento ma **quasi impossibile da sbagliare**.

**Tempo 2 — generazione legale diretta.** Maschere di pin, maschera di check, gestione separata del doppio scacco. Molto più veloce, molto più facile da sbagliare.

**Perché in questo ordine e non direttamente il tempo 2.** Perché il tempo 1 ti dà un perft corretto in mezza giornata, e quel perft corretto diventa **l'oracolo con cui validi il tempo 2**. Se parti dalla generazione legale non hai niente contro cui confrontarti e passerai una settimana a caccia di un bug in `d5`. Questo è il singolo consiglio più importante del documento: *lo scopo del codice lento è validare il codice veloce.*

### 4.3 Checklist dei casi che rompono tutti

Da coprire con test unitari dedicati, oltre al perft:

- Arrocco: casa d'arrivo attaccata, casa di transito attaccata, re già sotto scacco, torre già mossa, torre catturata sulla sua casa iniziale.
- **En passant che scopre uno scacco** sulla quinta traversa — il classico assassino del perft, perché la cattura rimuove *due* pedoni da traverse diverse.
- En passant che sarebbe legale ma il pedone catturante è inchiodato.
- Promozione con cattura, e promozione a pezzo minore (un motore che promuove sempre a donna perde patte per stallo).
- Doppio scacco: solo mosse di re, nessuna interposizione e nessuna cattura del pezzo che dà scacco.

---

## 5. Search

### 5.1 Struttura

Negamax alpha-beta con **PVS** (principal variation search), dentro un **iterative deepening** con finestre di aspirazione.

L'iterative deepening non è uno spreco anche se rifà lavoro: la ricerca a profondità `d-1` popola la TT e produce l'ordinamento che rende la profondità `d` drasticamente più veloce. È anche ciò che rende possibile il time management — puoi fermarti a metà e avere comunque una mossa buona.

### 5.2 Quiescence search

Alla profondità 0 non valutare subito: continua a cercare solo catture e promozioni fino a una posizione "quieta". Senza questo il motore ha l'*horizon effect* ed è tatticamente cieco — è il singolo pezzo che vale più Elo di tutti.

Include `stand pat` (se la valutazione statica già batte beta, taglia) e potatura delta.

### 5.3 Transposition table

Il layout è una scelta Java-specifica che vale la pena spiegare nel README:

```java
long[] keys;   // 32 bit alti dello zobrist + metadati
long[] data;   // move | score | depth | bound | age, impacchettati
```

**Due array paralleli di primitivi, non un array di oggetti `TTEntry`.** Un `TTEntry[]` in Java è un array di *puntatori*: ogni lookup è due cache miss invece di uno, più 16 byte di header per entry. Con una tabella da 256 MB la differenza è enorme. Questo è esattamente il tipo di dettaglio che il layout della memoria in Java ti costringe a considerare e che in C++ ottieni gratis con uno `struct`.

Attenzioni:

- Dimensione potenza di 2, indice per mascheratura. Configurabile via UCI `setoption name Hash`.
- Politica di rimpiazzo: preferisci profondità maggiore, ma con **age** in modo che le entry di ricerche precedenti cedano il posto.
- **Punteggi di matto relativi al ply.** Un matto salvato come "matto in 3 dal nodo corrente" letto da un altro ply diventa un punteggio sbagliato. Aggiusta in scrittura e in lettura. È un bug classico e si manifesta come un motore che annuncia matti che non esistono.
- Una entry non basta per la ripetizione: la patta per ripetizione dipende dal *percorso*, non dalla posizione. Serve uno stack separato di chiavi zobrist.

### 5.4 Move ordering

L'ordinamento vale più di quasi ogni altra ottimizzazione: alpha-beta ideale visita `√N` nodi invece di `N`, ma solo se le mosse migliori arrivano per prime.

Ordine: mossa dalla TT → catture buone per SEE → promozioni → killer moves → history heuristic → resto.

**SEE** (static exchange evaluation) merita la sua implementazione pulita e i suoi test unitari: serve sia per l'ordinamento sia per la potatura in quiescence, ed è un pezzo di codice sottile.

### 5.5 Potature e riduzioni — una per volta

Null move pruning, late move reductions, futility pruning, reverse futility. Ognuna vale Elo, ognuna può nascondere un bug che perde partite in casi rari (lo zugzwang per il null move, ad esempio).

**Regola: una patch per volta, ognuna col suo SPRT.** Se ne aggiungi tre insieme e l'Elo scende, non sai quale. Questo è il punto in cui la disciplina di §1.2 ripaga il costo di averla costruita.

### 5.6 Time management

Da `go wtime btime winc binc movestogo`: calcola un budget *soft* (dopo il quale non inizi una nuova iterazione) e uno *hard* (oltre il quale abortisci comunque). Controlla il tempo ogni ~2048 nodi, non a ogni nodo: `System.nanoTime()` in un ciclo caldo costa più di quanto pensi.

### 5.7 Nota sul polimorfismo di `Evaluator`

`Evaluator` è un'interfaccia chiamata milioni di volte al secondo, e in Java questo solleva una domanda legittima: **il call site diventa megamorfico e la JIT smette di inlinare?**

No, e vale la pena capire perché. Un solo run del motore carica *una sola* implementazione — o HCE o NNUE, scelta all'avvio. La JIT osserva un call site monomorfico e inlina normalmente. Il rischio esiste solo se caricassi entrambe nello stesso processo, e succede in un posto preciso: **i test comparativi**. Lì l'inlining non ci interessa.

Prevenzione: `final` sulle classi di implementazione, e un flag `-XX:+PrintInlining` da controllare una volta per confermare che l'inlining avvenga davvero. Verificare invece di assumere è tutto il punto.

---

## 6. Il seam della valutazione

**Questa è la decisione architetturale più importante del documento.** È ciò che rende l'Atto II un innesto invece di una riscrittura, e va presa il primo giorno, quando non serve ancora a niente.

```java
public interface Evaluator {

    /** Valutazione in centipawn, dal punto di vista del giocatore di turno. */
    int evaluate(Board board);

    /** Chiamato con la board nella posizione PRE-mossa, prima che la mossa venga applicata. */
    default void beforeMakeMove(Board board, int move) {}

    /** Chiamato con la board tornata alla posizione PRE-mossa, dopo l'annullamento. */
    default void afterUnmakeMove(Board board, int move) {}

    /** Scarta lo stato incrementale e lo ricostruisce dalla board. */
    default void reset(Board board) {}
}
```

**Scostamento, scoperto implementando.** La prima stesura aveva `onMakeMove` chiamato **dopo** l'applicazione della mossa. Non funziona: a mossa applicata il pezzo catturato **non è più sulla board**, e la sua identità e la sua casa sono esattamente ciò che serve al delta delle feature. Leggere la posizione *prima* rende visibili tutti i pezzi coinvolti — quello che muove, la vittima, la torre dell'arrocco — senza chiedere niente in più alla `Board`.

I nomi sono cambiati di conseguenza: `beforeMakeMove` e `afterUnmakeMove` **portano il contratto addosso**, invece di affidarlo a un commento che qualcuno dovrà ricordare. Entrambi vedono la posizione pre-mossa, quindi la coppia è simmetrica.

`reset` si è aggiunto per una ragione pratica emersa con l'UCI: un host può consegnare al motore una posizione che non ha niente a che vedere con la precedente, e a quel punto un accumulatore incrementale va ricostruito da zero.

Un ultimo dettaglio che l'implementazione ha fissato: la ricerca chiama gli hook attorno a **ogni** mossa che prova, comprese quelle che si rivelano illegali e vengono subito annullate. L'accoppiamento è quindi sempre bilanciato, ed è su questo che si appoggia un'implementazione che fa push e pop di uno stack.

Gli hook `default` sono l'intera partita. `HandCraftedEvaluator` li ignora — non ha stato, calcola tutto da zero a ogni chiamata. `NnueEvaluator` li userà per mantenere il proprio accumulatore incrementale.

Senza questi hook, l'Atto II ti costringerebbe a mettere le mani in `makeMove` e in ogni ramo della ricerca. Con loro, `ludus-uci` cambia una riga:

```java
Evaluator eval = nnuePath != null
    ? new NnueEvaluator(NnueNetwork.load(nnuePath))
    : new HandCraftedEvaluator();
```

Sui pattern, con onestà: è **Strategy**, e gli hook hanno un sapore di **Observer**. Non è una scoperta né va venduta come tale nel README — quello che vale la pena scrivere è *perché il confine sta esattamente lì* e non due strati più su o più giù. Un lettore competente apprezza il ragionamento sul confine; l'etichetta del pattern la sa già.

### 6.1 HCE — la valutazione a mano

Volutamente modesta, perché è destinata a essere buttata: materiale, tabelle pezzo-casa interpolate tra mediogioco e finale, struttura dei pedoni (doppiati, isolati, passati), mobilità, sicurezza del re, coppia di alfieri.

**Cosa è effettivamente atterrato in M1**, e cosa no. Ci sono materiale, PSQT tapered, struttura dei pedoni e coppia di alfieri. **Mobilità e sicurezza del re non ci sono**, ed è coerente con il paragrafo qui sotto: sono i due termini più costosi da scrivere e da tarare, e su materiale destinato al cestino non valgono il prezzo.

Una semplificazione ulteriore, dichiarata: solo **pedone e re** hanno due tabelle distinte per mediogioco e finale. Cavalli, alfieri, torri e donne ne condividono una sola tra le due fasi, perché le loro case migliori cambiano poco — mentre per il pedone e per il re la fase cambia davvero la risposta, ed è lì che l'interpolazione serve. Inventare un secondo set di numeri non tarati avrebbe aggiunto codice senza aggiungere informazione.

Il test che tiene tutto questo insieme è la **simmetria di colore**: specchiare una posizione — ribaltare le traverse, scambiare i colori, passare la mossa — deve dare lo stesso punteggio. È una sola proprietà e cattura i due errori più facili da fare e più difficili da notare: un segno invertito in un termine, e una tabella pezzo-casa indicizzata senza mirroring per il nero. Entrambi lasciano il motore silenziosamente convinto che un colore stia meglio.

**Non spendere settimane a fare tuning di HCE.** Serve a due cose: dare all'Atto I un avversario onesto e stabilire il *baseline Elo* contro cui misurerai la NNUE. Se la ottimizzi troppo, l'unico effetto è rendere meno impressionante il numero dell'Atto II.

---

## 7. Atto II — NNUE

### 7.1 Architettura della rete

```
input: 768 feature (64 case × 6 tipi × 2 colori), sparse
   │
   ├── prospettiva bianca ──► feature transformer  768 → 256
   └── prospettiva nera   ──► (stessi pesi)        768 → 256
                                    │
                          concat ordinato dal turno → 512
                                    │
                              clipped ReLU
                                    │
                               512 → 32 → 32 → 1
```

**Perché 768 feature e non `halfKP`.** L'`halfKP` di Stockfish ha ~41 000 feature (posizione del re × pezzo × casa) e va molto meglio, ma richiede molti più dati per allenarsi e molta più cura. Un input `768` denso-di-informazione è la scelta giusta per la prima rete: si allena con dati modesti, funziona, e ti dà il numero. `halfKP` è l'ovvia iterazione successiva, ed è meglio averla come *miglioramento misurato* che come rischio iniziale.

Il **concat ordinato dal turno** (prima la prospettiva di chi muove) è ciò che rende la rete simmetrica: impara "chi muove sta meglio", non "il bianco sta meglio".

### 7.2 Accumulatore incrementale

È il cuore della sigla: la **E** di NNUE sta per *efficiently updatable*, e l'efficienza è tutta qui.

Il feature transformer è la parte costosa (768 → 256, due volte). Ma tra un nodo e il figlio cambiano **pochissime feature**: una mossa normale rimuove il pezzo dalla casa di partenza e lo aggiunge a quella d'arrivo. Due colonne di pesi su 768. Quindi non ricalcoli: sommi e sottrai.

```
mossa normale       →  −(pezzo, from)  +(pezzo, to)
cattura             →  ... e −(pezzo catturato, to)
promozione          →  −(pedone, from) +(pezzo promosso, to)
arrocco             →  quattro aggiornamenti (re + torre)
en passant          →  −(pedone catturato, casa NON di arrivo)   ← attenzione
mossa di re         →  ricalcolo completo di quella prospettiva, se usi halfKP
```

Implementazione: uno stack di accumulatori indicizzato dal ply. `onMakeMove` copia dal livello precedente e applica il delta; `onUnmakeMove` fa semplicemente `ply--` — l'annullamento è gratis, che è metà del motivo per cui questo schema funziona.

L'en passant è di nuovo il caso che rompe tutto: il pedone catturato **non è sulla casa di arrivo**, ed è l'errore che farai.

### 7.3 Quantizzazione

L'inferenza gira a interi, non a float. È ciò che la rende abbastanza veloce da stare in una ricerca.

- Pesi e accumulatore del feature transformer: **int16**.
- Clipped ReLU: satura a `[0, 127]`, output **int8**.
- Pesi degli strati densi: **int8**, accumulo in **int32**.
- Fattori di scala scelti in modo che l'output finale sia in centipawn.

Il training è in float; la quantizzazione è un passo di export. Questo introduce una discrepanza controllata tra rete allenata e rete eseguita — che è precisamente ciò che il test di §7.4b misura.

**Vector API** (`jdk.incubator.vector`) per i prodotti dell'accumulatore e degli strati densi, con un percorso scalare di fallback. Due ragioni: è dove sta il guadagno di performance, ed è un pezzo di Java moderno che quasi nessun portfolio mostra. Tieni il fallback scalare *funzionante e testato*, non solo presente: è il tuo riferimento di correttezza per la versione vettoriale.

### 7.4 Verifica — tre livelli

**(a) L'invariante dell'accumulatore.** Il più importante.

> Per ogni posizione raggiunta durante una partita casuale, l'accumulatore mantenuto incrementalmente deve essere **identico bit per bit** a un ricalcolo da zero.

Property test con jqwik: genera migliaia di partite casuali, a ogni nodo confronta. Un bug qui non fa crashare niente — fa solo giocare peggio il motore, in modo silenzioso e inspiegabile. Senza questo test lo cercheresti per settimane.

**(b) Java contro PyTorch.** Su un set di posizioni fisso, l'inferenza Java quantizzata e l'inferenza PyTorch in float devono coincidere entro la tolleranza di quantizzazione. Cattura gli errori di export: pesi trasposti, scale sbagliate, ordine dei layer invertito.

**(c) SPRT, HCE contro NNUE.** Il numero finale, quello che va nel README.

### 7.5 Training

**In PyTorch, non in Java.** È la scelta pragmatica e non è una resa: il training è un processo iterativo che vive di ecosistema (dataloader, ottimizzatori, tensorboard), e riscriverlo sarebbe un secondo progetto travestito da primo. L'inferenza in Java è la parte che conta e la parte difficile.

**Dati.** Posizioni etichettate con il punteggio di una ricerca a profondità bassa-media del tuo stesso motore in self-play, più il risultato finale della partita. La loss interpola tra i due (`lambda` tra valutazione e WDL): la valutazione insegna la tattica, il risultato insegna cosa conta davvero. Formato: `.binpack` o un formato tuo semplice, purché lo streaming sia veloce.

Parti da un dataset pubblico per la prima rete. Genera i tuoi dati solo dopo, quando hai la pipeline che funziona end-to-end — altrimenti debugghi training e generazione dati insieme, e non saprai cosa è rotto.

`ludus-tools` ospita il generatore di self-play; lo script di training sta in `training/` come progetto Python separato, con il suo `requirements.txt`. Non tentare di farli condividere il build.

---

## 8. Strategia di test

| Test | Cosa protegge | Dove |
|---|---|---|
| Suite perft | Move generation | `core`, veloce e lento (tag) |
| **Invariante make/unmake** | Corruzione dello stato | `core`, property-based |
| Zobrist incrementale == ricalcolato | Collisioni TT | `core`, property-based |
| Casi speciali (§4.3) | Arrocco, ep, promozioni | `core`, unit |
| SEE | Ordinamento e potatura | `search`, unit |
| Matto in N | Sanità della ricerca | `search`, suite EPD |
| Protocollo UCI | Compatibilità con le GUI | `uci`, golden I/O |
| **Invariante accumulatore** | Correttezza NNUE | `nnue`, property-based |
| Java vs PyTorch | Correttezza dell'export | `nnue`, dati fissi |

### 8.2 L'invariante make/unmake

Merita una menzione a parte perché è ciò che rende sicura la scelta di §3.3:

> Dopo `makeMove(m)` seguito da `unmakeMove(m)`, **ogni** campo di `Board` deve essere identico a prima: tutti i bitboard, lo zobrist, i diritti di arrocco, la casa ep, l'halfmove clock.

Property test su partite casuali. Un `unmakeMove` che dimentica di ripristinare i diritti di arrocco produce bug che si manifestano venti nodi dopo, in un ramo diverso, come una mossa illegale inspiegabile. Questo test lo becca al primo nodo.

### 8.3 CI

GitHub Actions, tre workflow:

- **PR**: build, test veloci, perft fino a d4 su tutta la suite. Deve stare sotto i due minuti.
- **Nightly**: perft profondo (initial d6, Kiwipete d5), benchmark JMH con confronto sul commit precedente.
- **Release**: fat jar + script di lancio, pubblicati come GitHub Release così chiunque può farlo giocare.

Il badge verde sul README fa una differenza sproporzionata rispetto al costo di metterlo.

---

## 9. Milestone

Ognuna ha un criterio di uscita verificabile, non "quando mi sembra pronto".

| # | Contenuto | Fatto quando |
|---|---|---|
| **M0** ✅ | Board, bitboard, magic, movegen pseudo-legale, FEN, perft | **Tutta la suite perft passa.** Nient'altro conta finché questo non è vero |
| **M1** ✅ | Negamax, HCE minima, UCI, iterative deepening, time management, ordinamento catture | Gioca una partita legale intera contro una GUI senza mai proporre una mossa illegale |
| **M2** | Quiescence, TT, killer e history, potature | Batte M1 con SPRT. Questo è il **baseline Elo** |
| **M3** | Movegen legale diretta, riduzioni, tuning del tempo | Perft ancora corretto (fondamentale) e SPRT positivo su ogni patch |
| **M4** | Inferenza NNUE, prima rete allenata | Invariante accumulatore verde, Java≈PyTorch, **SPRT vs M3 positivo** |
| **M5** | Vector API, tuning, `halfKP` | nps migliorato a parità di Elo, poi Elo migliorato |
| **M6** | Pagina di stato su GitHub Pages + card SVG nel profilo | La pagina si aggiorna da sola a ogni push e nightly, senza intervento manuale |

**M0 e M1 sono un weekend a testa.** M2 è dove il motore inizia a essere forte. M4 è il momento in cui hai il numero da mettere nel README, e da lì M5 può durare quanto ti diverte.

**M0 è chiuso.** Tutti i 32 casi della suite perft passano, i due invarianti della board (make/unmake reversibile, zobrist incrementale contro ricalcolato) sono verdi su partite casuali con seed fisso, e le tabelle magic sono validate contro il ray walking. 60 test in 3 secondi per il gate veloce, 32 casi perft profondi in 14 secondi per il nightly.

**M1 è chiuso.** Il motore parla UCI, viene lanciato come un jar singolo da una GUI, e gioca partite legali. 95 test verdi.

Due cose sono migrate da M2 a M1, e vale la pena dire perché. **L'iterative deepening** perché il time management dell'UCI lo richiede: senza, non c'è modo di rispettare `go wtime` — ti fermi a profondità fissa e sfori il tempo o lo butti. E **l'ordinamento delle catture**, perché alpha-beta senza ordinamento pota così poco che confrontare M2 contro un M1 non ordinato misurerebbe l'ordinamento invece di tutto il resto.

**La quiescence invece è rimasta fuori, deliberatamente**, e non è pigrizia: il criterio di uscita di M2 è battere M1 con SPRT, e quel numero è una misura vera solo se M1 esiste prima senza. Il costo è visibile a occhio nudo nell'output UCI — il punteggio oscilla di circa ±100 centipawn tra profondità pari e dispari, perché a profondità dispari l'ultima cattura la fa il motore e a profondità pari l'avversario. È l'*horizon effect* in forma pura, ed è la prima cosa che M2 sistema.

La cosa importante di questa scaletta: **M1 è già un repo pubblicabile**. Un motore che gioca partite legali con UCI e CI verde è un progetto finito, non un cantiere. Tutto il resto è miglioramento incrementale su una base che si difende da sola. È l'assicurazione contro il repo abbandonato a metà.

### 9.1 M6 — la pagina di stato

Ultimo step, e per struttura: raccoglie i numeri che i milestone precedenti producono, quindi prima devono esistere.

L'idea è una pagina che si legge da sola per capire come sta andando il motore — Elo per milestone, nps, esito della suite perft, storico delle patch e degli SPRT — generata dai workflow e aggiornata senza intervento manuale.

**Il vincolo che ne determina la forma:** i README di GitHub **sanificano l'HTML**. Niente `<iframe>`, niente `<script>`, niente CSS. Quindi "attaccare una pagina HTML al profilo" non è letteralmente possibile, e il modo che funziona è a due pezzi:

1. **La pagina piena su GitHub Pages**, servita dal repo `ludus`. Qui l'HTML è libero: grafici dell'Elo nel tempo, tabelle perft, cronologia degli SPRT.
2. **Una card SVG nel profilo**, generata dallo stesso workflow e committata su `LorenzoVicino/LorenzoVicino`, con un link alla pagina. L'SVG passa la sanificazione perché è un'immagine — è esattamente il meccanismo che il profilo già usa per `languages-light.svg`.

Sul tema: scacchiera. La griglia 8×8 è già un sistema di layout, i colori delle case danno la palette, e la notazione algebrica dà le etichette degli assi — quindi il tema non è decorazione applicata sopra, è la struttura stessa dei dati.

Un'accortezza che vale la pena fissare adesso: la card SVG va generata in **due varianti**, chiara e scura, e referenziata nel README con `<picture>` e `prefers-color-scheme`. Il profilo lo fa già per le lingue; una card che si legge solo in tema chiaro è mezza rotta.

---

## 10. Rischi

| Rischio | Mitigazione |
|---|---|
| Bug silenziosi nella movegen | Perft prima di tutto, sempre. Non scrivere ricerca su una movegen non validata |
| Il tempo 2 della movegen rompe la correttezza | Il tempo 1 resta nel repo come oracolo, dietro un flag |
| Perdersi nel tuning di HCE | Timebox esplicito. HCE è materiale di scarto (§6.1) |
| Dati di training scadenti | Prima rete da dataset pubblico, self-play solo dopo |
| Pause GC nella ricerca | Disciplina zero-alloc, verificata con JFR (§3.3) |
| L'Atto II slitta indefinitamente | M1 è già spedibile. Il repo non muore comunque |
| Elo che non sale e non si sa perché | nps e Elo misurati separatamente (§1.3), una patch per volta |

---

## 11. Cosa raccontare nel README

Il codice non parla da solo. Le tre cose che rendono questo progetto interessante da leggere, e su cui vale la pena scrivere davvero:

1. **Il numero.** "La NNUE ha aggiunto N Elo, misurati con SPRT su M partite, ecco la configurazione del match." Con il grafico. È la cosa più rara in un portfolio di machine learning.
2. **Java come vincolo interessante.** Zero-allocazione in un ciclo caldo, layout della TT ad array paralleli, assenza di `PEXT`, Vector API, verifica dell'inlining. Nessuno scrive di questo: tutti i motori seri sono in C++ o Rust. È contenuto originale, non un riassunto di CPW.
3. **Il seam.** Perché il confine della valutazione sta esattamente dove sta, e cosa sarebbe costato metterlo altrove. Questa è la parte di design che un lettore competente riconosce, e vale più di dieci pattern elencati.
