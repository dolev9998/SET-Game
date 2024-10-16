package bguspl.set.ex;

import java.util.LinkedList;
import java.util.PriorityQueue;
import java.util.Queue;

/**
 * a blocking queue with limit invariant.
 * @inv 0 <= queue.Size() <= limit
 */
public class LimitedQueue {
    private int limit;
    private Queue<Integer> queue;
    public LimitedQueue(int limit){
        this.limit = limit;
        this.queue = new LinkedList<>();
    }

    public synchronized void add(Integer i) {
        try {
            while (queue.size() == limit) {
                wait();
            }
            queue.add(i);
            notify();
        }
        catch (InterruptedException e) {}
    }
    public synchronized  void addIfNotFull(Integer i)
    {
        if(queue.size()!=limit)
        {
            queue.add(i);
            notify();
        }
    }
    public synchronized Integer remove() throws InterruptedException {
        try{
            while(queue.size()==0)
            {
                wait();
            }
            Integer retValue = queue.remove();
            notify();
            return retValue;
        } catch (InterruptedException e) {
            throw e;
        }
    }
}
