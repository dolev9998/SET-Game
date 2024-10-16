package bguspl.set.ex;

import bguspl.set.Env;
import bguspl.set.ThreadLogger;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     *  true iff the dealer can process claim set.
     */
    private volatile boolean canProcessClaimSet;
    /**
     * system time since last timer reset.
     */
    private long startTime;
    /**
     * current time in the timer (used in NORMAL/LAST_ACTION).
     */
    private long timerTime;
    /**
     * time when should stop sleeping in NORMAL mode.
     */
    private final long stopSleepTimeDecreasing;
    /**
     * stores how long should the thread in between timer update.
     */
    private final long sleepTime;
    /**
     * different timer states based on turnTimeoutWarningMillis in config.
     */
    private enum TimerType{NORMAL,LAST_ACTION,NONE}

    /**
     * current timer state.
     */
    private final TimerType timerType;
    /**
     * The game environment object.
     */
    private final Env env;
    /**
     * array the holds all the slots that we can remove cards from
      */
    private final List<Integer> slotsToRemove;
    /**
     * array the holds all the slots that we can add cards to
     */
    private List<Integer> slotsToInsert;
    /**
     * list that holds all slots in table (used when inserting to all slots in table).
     */
    private final List<Integer> allSlots;

    /**
     * Game entities.
     */
    private final Table table;
    /**
     * array that holds the players
     */
    private final Player[] players;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * dealer thread
     */
    private Thread dealerThread;
    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;
    /**
     * true iff there is a set on the table (only used in LAST_ACTION/NONE mode).
     */
    private boolean setOnTable;



    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        slotsToInsert = new LinkedList<>();
        slotsToRemove = new LinkedList<>();
        allSlots = new LinkedList<>();
        int slotsAmount = env.config.tableSize;
        for(int i =0;i<slotsAmount;i++)
        {
            allSlots.add(i);
        }

        //timer related stuff:
        if (env.config.turnTimeoutMillis > 0) {
            timerType = TimerType.NORMAL;
        } else if (env.config.turnTimeoutMillis == 0) {
            timerType = TimerType.LAST_ACTION;
        } else {
            timerType = TimerType.NONE;
        }

        stopSleepTimeDecreasing = env.config.turnTimeoutWarningMillis+1000;
        sleepTime = 999;
        canProcessClaimSet = true;

    }

    /**
     * this function checks if a player has a legal set on table
     * @param playerId player who try to claim set.
     */
    public void claimSet(int playerId)
    {
        boolean penalize = false;
        boolean awardPoint = false;

        synchronized (this) {
            while (!canProcessClaimSet) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    return;
                }
            }
            canProcessClaimSet = false;
            if (table.getPlayerTokensAmount(playerId) == env.config.featureSize) {
                Pair<Integer, Integer>[] playerTokens = table.getPlayerTokens(playerId);
                int[] cards = new int[env.config.featureSize];
                for(int i =0;i<env.config.featureSize;i++)
                {
                    cards[i] = playerTokens[i].getSecond();
                }
                if (env.util.testSet(cards)) {
                    for(Pair<Integer,Integer> pair : playerTokens)
                    {
                        slotsToRemove.add(pair.getFirst());
                    }
                    dealerThread.interrupt();
                    awardPoint = true;
                } else {
                    penalize = true;
                    canProcessClaimSet = true;
                    notifyAll();
                }
            } else {
                canProcessClaimSet = true;
                notifyAll();
            }
        }
        if(awardPoint)
        {
            players[playerId].point();
        }
        if(penalize)
        {
            players[playerId].penalty();
        }
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {

        dealerThread = Thread.currentThread();
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        /**
         * array to hold all player threads
         */
        ThreadLogger[] playersThreads = new ThreadLogger[players.length];
        for(int i =0;i< players.length;i++)
        {
            playersThreads[i] = new ThreadLogger(players[i],"Player" + (i+1),env.logger);
            playersThreads[i].startWithLog();
        }

        slotsToInsert = allSlots;
        updateTimerDisplay(true);
        while (!shouldFinish()) {
            Collections.shuffle(deck);
            placeCardsOnTable();
            table.hints();
            switch(timerType)
            {
                case NORMAL:
                    timerLoop();
                    break;
                case LAST_ACTION:
                    setOnTable = table.hasLegalSet();
                    lastActionTimerLoop();
                case NONE:
                    setOnTable = table.hasLegalSet();
                    noTimerLoop();
                    break;
            }
            updateTimerDisplay(true);
            removeAllCardsFromTable();
            slotsToInsert = allSlots;
        }
        announceWinners();
        terminate();
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }
    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
            sleepUntilWokenOrTimeout();
            updateTimerDisplay(false);
            removeCardsFromTable();
            placeCardsOnTable();
        }
    }

    /**
     * inner loop for last action mode
     */
    private void lastActionTimerLoop()
    {
        while (!terminate && setOnTable) {
            sleepUntilWokenOrTimeout();
            updateTimerDisplay(false);
            removeCardsFromTable();
            placeCardsOnTable();
        }
    }

    /**
     * inner loop for no timer mode
     */
    private void noTimerLoop()
    {
        while (!terminate && setOnTable) {
            sleepUntilWokenOrTimeout();
            removeCardsFromTable();
            placeCardsOnTable();
        }
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        for(int i =players.length-1;i>=0;i--)
        {
            players[i].terminate();
        }
        terminate = true;
        dealerThread.interrupt();
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).isEmpty();
    }

    /**
     * Checks if any cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable() {
        synchronized (this){
            if (slotsToRemove.size() > 0) {
                for (Integer slot : slotsToRemove) {
                    table.removeCard(slot);
                    slotsToInsert.add(slot);
                }
                slotsToRemove.clear();
                canProcessClaimSet = true;
                notifyAll();
            }
        }
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {

        if(slotsToInsert.size()>0)
        {
            for(Integer slot: slotsToInsert)
            {
                if(deck.size()>0)
                {
                    int card = deck.remove(0);
                    table.placeCard(card,slot);
                }
            }
            slotsToInsert = new LinkedList<>();
            reshuffleTime = System.currentTimeMillis()+ env.config.turnTimeoutMillis;
            updateTimerDisplay(true);
            if(timerType != TimerType.NORMAL) {
                setOnTable = table.hasLegalSet();
            }
            table.hints();
        }
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        switch(timerType)
        {
            case NORMAL:
                if(stopSleepTimeDecreasing<timerTime) //stop sleeping when getting close to the warning time.
                {
                    try {
                        Thread.sleep(sleepTime);
                    } catch (InterruptedException ignored){}
                }
                break;
            case LAST_ACTION:
                try {
                    Thread.sleep(sleepTime);
                } catch (InterruptedException ignored){}
                break;
            case NONE:
                if(slotsToRemove.isEmpty()){
                    try {
                        Thread.sleep(Long.MAX_VALUE); // should sleep until interrupted
                    } catch (InterruptedException ignored){System.out.println("Woken");}
                }
                break;
        }
    }


    /**
     * Returns all the cards from the table to the deck.
     */
    private synchronized void removeAllCardsFromTable() {
        canProcessClaimSet = false;
        List<Integer> cardsInTable = table.removeAllCards();
        deck.addAll(cardsInTable);
        canProcessClaimSet = true;
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        int[] scores = new int[players.length];
        for(int i =0;i<players.length;i++)
        {
            scores[i] = players[i].score();
        }
        env.ui.announceWinner(getWinnersId(scores));
    }

    /**
     * returns winners id base on highest score indexes.
     */
    private int[] getWinnersId(int[] scores)
    {
        int winners = 1;
        int maxScore = scores[0];
        for(int i=1;i<scores.length;i++)
        {
            if(scores[i]>maxScore)
            {
                winners=1;
                maxScore=scores[i];
            }
            else if(scores[i]==maxScore)
            {
                winners++;
            }
        }
        int[] winnersId = new int[winners];
        int index = 0;
        for(int i=0;i<scores.length;i++)
        {
            if(scores[i]==maxScore)
            {
                winnersId[index] = i;
                index++;
            }
        }
        return winnersId;
    }
    private void updateTimerDisplay(boolean reset)
    {
        if(reset)
        {
            switch(timerType)
            {
                case NORMAL:
                    startTime = System.currentTimeMillis();
                    env.ui.setCountdown(env.config.turnTimeoutMillis,false);
                    break;
                case LAST_ACTION:
                    updateTimeIncreasing();
                    startTime = System.currentTimeMillis();
                    timerTime=0;
                    env.ui.setElapsed(0);
                    break;
                case NONE:
                   // env.ui.setElapsed(0);
                    break;
            }
        }
        else
        {
            switch(timerType)
            {
                case NORMAL:
                    updateTimeDecreasing();
                    break;
                case LAST_ACTION:
                    updateTimeIncreasing();
                    break;
                case NONE:
                    break;
            }

        }

    }
    private void updateTimeIncreasing() {
        // System.out.println(timerTime + " " + (System.currentTimeMillis() - startTime));
        timerTime += 1000;
        env.ui.setElapsed(timerTime);
    }
    private void updateTimeDecreasing()
    {
        timerTime = env.config.turnTimeoutMillis - (System.currentTimeMillis() - startTime);
        //System.out.println(timerTime);
        if (timerTime < env.config.turnTimeoutWarningMillis) {
            if (timerTime > 0)
                env.ui.setCountdown(timerTime, true);
            else {
                env.ui.setCountdown(0, true);
            }
        } else {
            env.ui.setCountdown(timerTime, false);

        }
    }
}
