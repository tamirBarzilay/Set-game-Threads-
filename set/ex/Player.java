package bguspl.set.ex;

import bguspl.set.Env;

import java.util.LinkedList;
import java.util.Queue;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated due to an external event.
     */
    public volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;

    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided manually, via the keyboard).
     */

    //add
    public int usedTokens;
    public int[][] setArray;
    public Queue<Integer> incomingActions;
    private Dealer dealer;
    public volatile long penalizedTime;

    public volatile boolean checkingSet;

    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.dealer = dealer;
        this.table = table;
        this.id = id;
        this.human = human;
        usedTokens = 0;
        setArray = new int[3][3];//row 0 represents card id //row 1 represents card slot
        // cell [2][0] represents player number
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 3; j++) {
                setArray[i][j] = -1;
            }
        }
        setArray[2][0] = id;
        incomingActions = new LinkedList<>();
        penalizedTime = 0;
        checkingSet = false;
    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */

    @Override
    public void run() {
        playerThread = Thread.currentThread();
        System.out.printf("Info: Thread %s starting.%n", Thread.currentThread().getName());
        if (!human) createArtificialIntelligence();

        while (!terminate) {
            // TODO implement main player loop
            if (penalizedTime > 0)
                penalize();
            synchronized (this) {
                while (!terminate && penalizedTime == 0 && (incomingActions.isEmpty() || !dealer.cardsOnTable || checkingSet)) {
                    try {
                        this.wait();
                    } catch (InterruptedException ignored) {
                    }
                }
                //the thread execute the next action from the queue
                if (!incomingActions.isEmpty() && !terminate && incomingActions.peek() != null && penalizedTime == 0 && dealer.cardsOnTable && !checkingSet) {
                    actionFromQueue(incomingActions.remove());
                    notifyAll();
                }
            }
        }
        if (!human) try {
            aiThread.join();
        } catch (InterruptedException ignored) {
        }
        System.out.printf("Info: Thread %s terminated.%n", Thread.currentThread().getName());
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very very smart AI (!)
        aiThread = new Thread(() -> {
            System.out.printf("Info: Thread %s starting.%n", Thread.currentThread().getName());
            while (!terminate) {
                // TODO implement player key press simulator
                synchronized (this) {
                    while (incomingActions.size() == 3) {
                        try {
                            this.wait();
                        } catch (InterruptedException ignored) {
                        }

                    }
                }
                //System.out.println("out of wait");
                keyPressed((int) (Math.random() * env.config.tableSize));
            }
            System.out.printf("Info: Thread %s terminated.%n", Thread.currentThread().getName());
        }, "computer-" + id);
        aiThread.start();
    }

    /**
     * Called when the game should be terminated due to an external event.
     */
    public void terminate() {
        // TODO implement
        terminate = true;
        synchronized (this) {
            notifyAll();
        }
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        // TODO implement
        synchronized (this) {
            if (incomingActions.size() < 3 && penalizedTime == 0 && table.slotToCard[slot] != null && table.slotToCard[slot] != -1 && dealer.cardsOnTable && !checkingSet) {
                incomingActions.add(slot); //adding action to the action queue.
                notifyAll();
            }
        }
    }

    public synchronized void actionFromQueue(int slot) {
        if (table.slotToCard[slot] != null && table.slotToCard[slot] != -1) {
            boolean removed = false;
            for (int i = 0; !removed && i < 3; i++) {
                if (setArray[1][i] == slot) {//if there is a token on the slot-remove action
                    synchronized (dealer) {
                        removed = table.removeToken(this.id, slot);
                    }
                    //update setArray
                    setArray[0][i] = -1;
                    setArray[1][i] = -1;
                    //update tokens
                    usedTokens--;
                }
            }
            if (!removed && usedTokens < 3) {
                boolean placed = false;
                synchronized (dealer) {
                    table.placeToken(this.id, slot);
                }
                int cardId = table.slotToCard[slot];
                for (int i = 0; !placed && i < 3; i++) {
                    if (setArray[0][i] == -1) {
                        //update set array
                        setArray[0][i] = cardId;
                        setArray[1][i] = slot;
                        //update tokens
                        usedTokens++;
                        placed = true;
                    }
                }
                if (usedTokens == 3) {//if third token was placed.
                    checkingSet = true;
                    dealer.addSetToQueue(setArray);

                }

            }
        }
    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        // TODO implement
        //int ignored = table.countCards(); // this part is just for demonstration in the unit tests
        env.ui.setScore(id, ++score);
        //penalizedTime = env.config.pointFreezeMillis;
        synchronized (this) {
            penalizedTime = env.config.pointFreezeMillis;
            notifyAll();
        }
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        // TODO implement
        synchronized (this) {
            penalizedTime = env.config.penaltyFreezeMillis;
            notifyAll();
        }
    }

    public int score() {
        return score;
    }

    public void join() {
        try {
            playerThread.join();
        } catch (InterruptedException ignored) {
        }
    }

    public void penalize() {
        if (penalizedTime > 0) { //player penalized
            env.ui.setFreeze(id, penalizedTime);
            long time = penalizedTime;
            while (time > 0) {
                if (time >= 1000) {
                    try {
                        playerThread.sleep(1000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    time = time - 1000;
                    env.ui.setFreeze(id, time);
                } else {
                    try {
                        playerThread.sleep(time);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    time = 0;
                    env.ui.setFreeze(id, time);
                }
            }
        }
        penalizedTime = 0;
    }

        public synchronized void wakeUpPlayer () {
            notifyAll();
        }
    }


