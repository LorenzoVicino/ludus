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

### 1.4 L'invariante dell'accumulatore — l'oracolo dell'Atto II

L'aggiornamento incrementale della NNUE deve dare **esattamente** lo stesso risultato del ricalcolo da zero, bit per bit. Questo è un test, non una speranza. Dettagli in §7.4.

---

## 2. Architettura

### 2.1 Moduli

Gradle multi-modulo (Kotlin DSL), JDK 25 LTS.

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

Nota per chi arriva dal C++: l'istruzione `PEXT` di BMI2, che è la via moderna e più semplice, **non è raggiungibile da Java** in modo portabile. Le magic sono l'unica strada. Usa magic già pubblicate invece di cercarle a runtime — cercarle è un esercizio carino ma rallenta l'avvio e non c'entra col progetto.

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

    /** Chiamato DOPO che la mossa è stata applicata alla board. */
    default void onMakeMove(Board board, int move) {}

    /** Chiamato PRIMA che la mossa venga annullata. */
    default void onUnmakeMove(Board board, int move) {}
}
```

I due hook `default` sono l'intera partita. `HandCraftedEvaluator` li ignora — non ha stato, calcola tutto da zero a ogni chiamata. `NnueEvaluator` li usa per mantenere il proprio accumulatore incrementale.

Senza questi hook, l'Atto II ti costringerebbe a mettere le mani in `makeMove` e in ogni ramo della ricerca. Con loro, `ludus-uci` cambia una riga:

```java
Evaluator eval = nnuePath != null
    ? new NnueEvaluator(NnueNetwork.load(nnuePath))
    : new HandCraftedEvaluator();
```

Sui pattern, con onestà: è **Strategy**, e gli hook hanno un sapore di **Observer**. Non è una scoperta né va venduta come tale nel README — quello che vale la pena scrivere è *perché il confine sta esattamente lì* e non due strati più su o più giù. Un lettore competente apprezza il ragionamento sul confine; l'etichetta del pattern la sa già.

### 6.1 HCE — la valutazione a mano

Volutamente modesta, perché è destinata a essere buttata: materiale, tabelle pezzo-casa interpolate tra mediogioco e finale, struttura dei pedoni (doppiati, isolati, passati), mobilità, sicurezza del re, coppia di alfieri.

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
| **M0** | Board, bitboard, magic, movegen pseudo-legale, FEN, perft | **Tutta la suite perft passa.** Nient'altro conta finché questo non è vero |
| **M1** | Negamax, HCE minima, UCI | Gioca una partita legale intera contro una GUI senza mai proporre una mossa illegale |
| **M2** | Quiescence, TT, move ordering, iterative deepening | Batte M1 con SPRT. Questo è il **baseline Elo** |
| **M3** | Movegen legale diretta, potature, time management | Perft ancora corretto (fondamentale) e SPRT positivo su ogni patch |
| **M4** | Inferenza NNUE, prima rete allenata | Invariante accumulatore verde, Java≈PyTorch, **SPRT vs M3 positivo** |
| **M5** | Vector API, tuning, `halfKP` | nps migliorato a parità di Elo, poi Elo migliorato |

**M0 e M1 sono un weekend a testa.** M2 è dove il motore inizia a essere forte. M4 è il momento in cui hai il numero da mettere nel README, e da lì M5 può durare quanto ti diverte.

La cosa importante di questa scaletta: **M1 è già un repo pubblicabile**. Un motore che gioca partite legali con UCI e CI verde è un progetto finito, non un cantiere. Tutto il resto è miglioramento incrementale su una base che si difende da sola. È l'assicurazione contro il repo abbandonato a metà.

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
