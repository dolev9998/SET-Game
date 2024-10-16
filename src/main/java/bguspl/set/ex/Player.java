package bguspl.set.ex;

import bguspl.set.Env;

import java.util.Random;

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
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * queue that holds actions(slots) which the player thread consumes from
     */
    private LimitedQueue actionsQueue;

    /**
     * The current score of the player.
     */

    private int score;
    private Dealer dealer;
    private volatile boolean isFrozen;
    private final long sleepTime;

    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.table = table;
        this.dealer = dealer;
        this.id = id;
        this.human = human;
        actionsQueue = new LimitedQueue(env.config.featureSize);
        isFrozen=false;
        sleepTime = 1000;
    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        if (!human) createArtificialIntelligence();

        while (!terminate) {
            try
            {
                int slot = actionsQueue.remove();
                doAction(slot);
            } catch (InterruptedException e) {}

        }
        if (!human) try { aiThread.join(); } catch (InterruptedException ignored) {}
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        aiThread = new Thread(() -> {
            env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
            Random random = new Random();
            while (!terminate) {
                int i = random.nextInt(env.config.tableSize);
                actionsQueue.add(i);
            }
            env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        terminate=true;
        if(!human) {
            aiThread.interrupt();
        }
        playerThread.interrupt();
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        if (!isFrozen) {
            actionsQueue.addIfNotFull(slot);
        }
    }

    /**
     * tries to remove/place a token on the table
     * iff places a token, trying to claim a set
     * @param slot - slot to place/remove token to/from.
     */
    private void doAction(int slot)
    {
        if(!table.removeToken(id,slot)) {
            if (table.placeToken2(id, slot)) {
                dealer.claimSet(id);
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
        isFrozen=true;
        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
        env.ui.setScore(id, ++score);
        freezeAndDisplayFreeze(env.config.pointFreezeMillis);
        isFrozen=false;
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        isFrozen=true;
        freezeAndDisplayFreeze(env.config.penaltyFreezeMillis);
        isFrozen=false;
    }
    private void freezeAndDisplayFreeze(long freezeTime)
    {
        if(freezeTime%sleepTime!=0)
        {
            env.ui.setFreeze(id,freezeTime%sleepTime);
            try {
                Thread.sleep(freezeTime%sleepTime);
            } catch (InterruptedException e) {
                if(terminate) return;
            }
            freezeTime -= freezeTime%sleepTime;
        }
        while(freezeTime>0)
        {
            env.ui.setFreeze(id,freezeTime);
            try {
                Thread.sleep(sleepTime);
            } catch (InterruptedException e) {
                if(terminate) return;
            }
            freezeTime-=sleepTime;
        }
        env.ui.setFreeze(id,0);
    }
    public int score() {
        return score;
    }
    public boolean isHuman()
    {
        return human;
    }
}
