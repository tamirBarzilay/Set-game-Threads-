package bguspl.set.ex;

import bguspl.set.Env;

import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    /**
     * True iff game should be terminated due to an external event.
     */
    public volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;

    public volatile Queue<int[][]> requests;

    private Semaphore sem;

    public int[] setToRemove;

    public volatile boolean cardsOnTable;

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        requests = new LinkedList<>();
        sem = new Semaphore(1, true);//alkfjalkcjblerdfvsd
        setToRemove = new int[3];//represents slots number of the cards that need to be removed,
        // after a set was claimed.
        for (int i = 0; i < 3; i++)
            setToRemove[i] = -1;
        cardsOnTable = false;
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        System.out.printf("Info: Thread %s starting.%n", Thread.currentThread().getName());
        Thread[] threadsArray = new Thread[env.config.players];
        //initializing and starting player threads
        //placeCardsOnTable();
        for (int i = 0; i < env.config.players; i++) {
            threadsArray[i] = new Thread(players[i], env.config.playerNames[i]);
            threadsArray[i].start();
            try {
                Thread.currentThread().sleep(10);
            } catch (InterruptedException ignored) {
            }
        }
        while (!shouldFinish()) {
            placeCardsOnTable();
            reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
            timerLoop();
            updateTimerDisplay(false);
            removeAllCardsFromTable();
        }
        //terminate the player threads.
        for (int i = env.config.players - 1; i >= 0; i--) {
            players[i].terminate();
            players[i].join();
        }
        //did we stop the pressing simulation thread
        //is it actually neccesary to stop them

        announceWinners();

        System.out.printf("Info: Thread %s terminated.%n", Thread.currentThread().getName());
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
            sleepUntilWokenOrTimeout();
            boolean isSet = false;
            boolean cardIsOnTable = true;
            int playerId=-1;
            if (!terminate && System.currentTimeMillis() < reshuffleTime /*&& !requests.isEmpty() && requests.peek() != null*/) {
                int[][] arr = new int[3][3];
                for (int i = 0; i < 3; i++)
                    for (int j = 0; j < 3; j++)
                        arr[i][j] = requests.peek()[i][j];
                synchronized (this) {
                    requests.remove();
                }
                playerId=arr[2][0];
                int[] checkSet = new int[3];
                for (int i = 0; i < 3; i++) {
                    checkSet[i] = arr[0][i];
                    if (arr[1][i] == -1) {//if the card was removed from the table
                        cardIsOnTable = false;
                    }
                }
                isSet = env.util.testSet(checkSet);
                if (isSet && cardIsOnTable) {//set claimed
                    players[arr[2][0]].point();
                    for (int g = 0; g < 3; g++) {
                        setToRemove[g] = arr[1][g];
                    }
                } else if (cardIsOnTable) {//wrong set
                    players[arr[2][0]].penalty();
                }
                synchronized (players[arr[2][0]]) {
                    players[arr[2][0]].incomingActions.clear();
                }

            }
            updateTimerDisplay(isSet && cardIsOnTable);
            if (isSet && cardIsOnTable) {
                removeCardsFromTable();
                placeCardsOnTable();
            }
            if (playerId!=-1) {
                synchronized (players[playerId]) {
                    players[playerId].checkingSet = false;
                    players[playerId].wakeUpPlayer();
                }
            }
        }
    }

    /**
     * Called when the game should be terminated due to an external event.
     */
    public void terminate() {
        // TODO implement
        terminate = true;
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }

    /**
     * Checks if any cards should be removed from the table and returns them to the deck.
     */
    private void removeCardsFromTable() {
        // TODO implement
        cardsOnTable = false;
        for (int i = 0; i < 3; i++) {//setToRemove.length
            for (int j = 0; j < players.length; j++) {
                for (int k = 0; k < 3; k++) {//players[j].setArray[1].length
                    synchronized (players[j]) {
                        if (players[j].setArray[1][k] == setToRemove[i]) {
                            //update players' fields.
                            players[j].setArray[1][k] = -1;
                            players[j].setArray[0][k] = -1;
                            players[j].usedTokens--;
                        }
                    }

                }
            }
            //remove cards and update display
            if (setToRemove[i] != -1) {
                synchronized (this) {
                    env.ui.removeTokens(setToRemove[i]);
                    table.removeCard(setToRemove[i]);
                }
            }
        }
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        // TODO implement
        for (int i = 0; i < env.config.tableSize; i++) {
            if (!deck.isEmpty() && (table.slotToCard[i] == null || table.slotToCard[i] == -1)) {
                int random = (int) (Math.random() * deck.size());
                table.placeCard(deck.remove(random), i);//taking a random card out of the deck and placing it on the table.
            }
        }
        cardsOnTable = true;
        for (int i = 0; i < players.length; i++) {
            players[i].wakeUpPlayer();
        }
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private synchronized void sleepUntilWokenOrTimeout() {
        // TODO implement
            while (!terminate && System.currentTimeMillis() < reshuffleTime && requests.isEmpty()) {
                try {
                    wait(10);
                } catch (InterruptedException ignored) {
                }
                updateTimerDisplay(false);
            }
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        // TODO implement
        if (reset)
            reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
        long timer = Math.abs(reshuffleTime - System.currentTimeMillis());
        env.ui.setCountdown(timer, reset);
        if (timer <= env.config.turnTimeoutWarningMillis)
            env.ui.setCountdown(timer, true);
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        // TODO implement
        cardsOnTable = false;
        //clear all requests
        synchronized (this) {
            requests.clear();
        }
        //update relevant player's fields
        for (int i = 0; i < players.length; i++) {
            synchronized (players[i]) {
                players[i].usedTokens = 0;
                players[i].incomingActions.clear();
                for (int k = 0; k < 2; k++) {
                    for (int j = 0; j < 3; j++) {
                        players[i].setArray[k][j] = -1;
                        players[i].setArray[k][j] = -1;
                        players[i].checkingSet=false;
                    }
                }
            }
        }
        env.ui.removeTokens();  //remove all tokens from the display
        for (int i = 0; i < env.config.tableSize; i++) { //remove all cards from table and display
            if (table.slotToCard[i] != -1) {//gray card
                deck.add(table.slotToCard[i]);
                table.removeCard(i);
            }
        }
        env.ui.removeTokens();  //remove all tokens from the display

    }


    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        // TODO implement
        int maxPoints = -1;
        for (int i = 0; i < players.length; i++) {
            if (players[i].score() > maxPoints) {
                maxPoints = players[i].score();
            }
        }
        List winnersList = new LinkedList<>();
        for (int i = 0; i < players.length; i++) {
            if (players[i].score() == maxPoints)
                winnersList.add(players[i].id);
        }
        int[] winners = new int[winnersList.size()];
        for (int i = 0; i < winners.length; i++)
            winners[i] = (int) (winnersList.get(i));
        env.ui.announceWinner(winners);
    }

    public void addSetToQueue(int[][] array) {//implement
        try {
            sem.acquire();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        synchronized (this) {
            if (array != null) {
                requests.add(array);
            }
            notifyAll();
        }
        sem.release();
    }
}
