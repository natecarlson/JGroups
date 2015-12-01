


package org.jgroups.stack;

import org.jgroups.Address;
import org.jgroups.Message;
import org.jgroups.annotations.GuardedBy;
import org.jgroups.logging.Log;
import org.jgroups.logging.LogFactory;
import org.jgroups.util.RetransmitTable;
import org.jgroups.util.TimeScheduler;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;


/**
 * Keeps track of messages according to their sequence numbers. Allows
 * messages to be added out of order, and with gaps between sequence numbers.
 * Method <code>remove()</code> removes the first message with a sequence
 * number that is 1 higher than <code>next_to_remove</code> (this variable is
 * then incremented), or it returns null if no message is present, or if no
 * message's sequence number is 1 higher.
 * <p>
 * When there is a gap upon adding a message, its seqno will be added to the
 * Retransmitter, which (using a timer) requests retransmissions of missing
 * messages and keeps on trying until the message has been received, or the
 * member who sent the message is suspected.
 *
 * There are 3 variables which keep track of messages:
 * <ul>
 * <li>low: lowest seqno, modified on stable(). On stable(), we purge msgs [low digest.highest_delivered]
 * <li>highest_delivered: the highest delivered seqno, updated on remove(). The next message to be removed is highest_delivered + 1
 * <li>highest_received: the highest received message, updated on add (if a new message is added, not updated e.g.
 * if a missing msg was received)
 * </ul>
 * <p/>
 * Note that the first seqno expected is 1. This design is described in doc/design.NAKACK.txt
 * <p/>
 * Example:
 * 1,2,3,5,6,8: low=1, highest_delivered=2 (or 3, depending on whether remove() was called !), highest_received=8
 * 
 * @author Bela Ban
 */
public class NakReceiverWindow {

    public interface Listener {
        void missingMessageReceived(long seqno, Address original_sender);
        void messageGapDetected(long from, long to, Address src);
    }

    private final ReadWriteLock lock=new ReentrantReadWriteLock();

    private volatile boolean running=true;

    /** Lowest seqno, modified on stable(). On stable(), we purge msgs [low digest.highest_delivered] */
    @GuardedBy("lock")
    private long low=0;

    /** The highest delivered seqno, updated on remove(). The next message to be removed is highest_delivered + 1 */
    @GuardedBy("lock")
    private long highest_delivered=0;

    /** The highest received message, updated on add (if a new message is added, not updated e.g. if a missing msg
     * was received) */
    @GuardedBy("lock")
    private long highest_received=0;


    /**
     * ConcurrentMap<Long,Message>. Maintains messages keyed by (sorted) sequence numbers
     */
    private final RetransmitTable xmit_table;


    private final AtomicBoolean processing=new AtomicBoolean(false);

    /** if not set, no retransmitter thread will be started. Useful if
     * protocols do their own retransmission (e.g PBCAST) */
    private Retransmitter retransmitter=null;

    private Listener listener=null;

    protected static final Log log=LogFactory.getLog(NakReceiverWindow.class);

    /** The highest stable() seqno received */
    long highest_stability_seqno=0;

    /** The loss rate (70% of the new value and 30% of the old value) */
    private double smoothed_loss_rate=0.0;


    /**
     * Creates a new instance with the given retransmit command
     *
     * @param sender The sender associated with this instance
     * @param cmd The command used to retransmit a missing message, will
     * be invoked by the table. If null, the retransmit thread will not be started
     * @param highest_delivered_seqno The next seqno to remove is highest_delivered_seqno +1
     * @param lowest_seqno The low seqno purged
     * @param sched the external scheduler to use for retransmission
     * requests of missing msgs. If it's not provided or is null, an internal
     */
    public NakReceiverWindow(Address sender, Retransmitter.RetransmitCommand cmd, long highest_delivered_seqno,
                             long lowest_seqno, TimeScheduler sched) {
        this(sender, cmd, highest_delivered_seqno, lowest_seqno, sched, true);
    }



    public NakReceiverWindow(Address sender, Retransmitter.RetransmitCommand cmd,
                             long highest_delivered_seqno, long lowest_seqno, TimeScheduler sched,
                             boolean use_range_based_retransmitter) {
        this(sender, cmd, highest_delivered_seqno, lowest_seqno, sched, use_range_based_retransmitter,
             5, 10000, 1.2, 5 * 60 * 1000, false);
    }


    public NakReceiverWindow(Address sender, Retransmitter.RetransmitCommand cmd,
                             long highest_delivered_seqno, long lowest_seqno, TimeScheduler sched,
                             boolean use_range_based_retransmitter,
                             int num_rows, int msgs_per_row, double resize_factor, long max_compaction_time,
                             boolean automatic_purging) {
        highest_delivered=highest_delivered_seqno;
        highest_received=highest_delivered;
        low=Math.min(lowest_seqno, highest_delivered);
        if(sched == null)
            throw new IllegalStateException("timer has to be provided and cannot be null");
        if(cmd != null)
            retransmitter=use_range_based_retransmitter?
                    new RangeBasedRetransmitter(sender, cmd, sched) :
                    new DefaultRetransmitter(sender, cmd, sched);

        xmit_table=new RetransmitTable(num_rows, msgs_per_row, low, resize_factor, max_compaction_time, automatic_purging);
    }


    /**
     * Creates a new instance with the given retransmit command
     *
     * @param sender The sender associated with this instance
     * @param cmd The command used to retransmit a missing message, will
     * be invoked by the table. If null, the retransmit thread will not be started
     * @param highest_delivered_seqno The next seqno to remove is highest_delivered_seqno +1
     * @param sched the external scheduler to use for retransmission
     * requests of missing msgs. If it's not provided or is null, an internal
     */
    public NakReceiverWindow(Address sender, Retransmitter.RetransmitCommand cmd, long highest_delivered_seqno, TimeScheduler sched) {
        this(sender, cmd, highest_delivered_seqno, 0, sched);
    }

   

    public AtomicBoolean getProcessing() {
        return processing;
    }

    public void setRetransmitTimeouts(Interval timeouts) {
        retransmitter.setRetransmitTimeouts(timeouts);
    }

    @Deprecated
    public void setDiscardDeliveredMessages(boolean flag) {
    }

    @Deprecated
    public int getMaxXmitBufSize() {
        return 0;
    }

    @Deprecated
    public void setMaxXmitBufSize(int max_xmit_buf_size) {
    }

    public void setListener(Listener l) {
        this.listener=l;
    }

    public int getPendingXmits() {
        return retransmitter!= null? retransmitter.size() : 0;
    }

    /**
     * Returns the loss rate, which is defined as the number of pending retransmission requests / the total number of
     * messages in xmit_table
     * @return The loss rate
     */
    public double getLossRate() {
        int total_msgs=size();
        int pending_xmits=getPendingXmits();
        if(pending_xmits == 0 || total_msgs == 0)
            return 0.0;

        return pending_xmits / (double)total_msgs;
    }

    public double getSmoothedLossRate() {
        return smoothed_loss_rate;
    }

    /** Set the new smoothed_loss_rate value to 70% of the new value and 30% of the old value */
    private void setSmoothedLossRate() {
        double new_loss_rate=getLossRate();
        if(smoothed_loss_rate == 0) {
            smoothed_loss_rate=new_loss_rate;
        }
        else {
            smoothed_loss_rate=smoothed_loss_rate * .3 + new_loss_rate * .7;
        }
    }


    public int getRetransmiTableSize() {return xmit_table.size();}

    public int getRetransmitTableCapacity() {return xmit_table.capacity();}

    public double getRetransmitTableFillFactor() {return xmit_table.getFillFactor();}

    public void compact() {
        xmit_table.compact();
    }


    /**
     * Adds a message according to its seqno (sequence number).
     * <p>
     * There are 4 cases where messages are added:
     * <ol>
     * <li>seqno is the next to be expected seqno: added to map
     * <li>seqno is <= highest_delivered: discard as we've already delivered it
     * <li>seqno is smaller than the next expected seqno: missing message, add it
     * <li>seqno is greater than the next expected seqno: add it to map and fill the gaps with null messages
     *     for retransmission. Add the seqno to the retransmitter too
     * </ol>
     * @return True if the message was added successfully, false otherwise (e.g. duplicate message)
     */
    public boolean add(final long seqno, final Message msg) {
        long old_next, next_to_add;
        int num_xmits=0;
        boolean missing_msg_received=false;

        lock.writeLock().lock();
        try {
            if(!running)
                return false;

            next_to_add=highest_received +1;
            old_next=next_to_add;

            // Case #1: we received the expected seqno: most common path
            if(seqno == next_to_add) {
                xmit_table.put(seqno, msg);
                return true;
            }

            // Case #2: we received a message that has already been delivered: discard it
            if(seqno <= highest_delivered) {
                if(log.isTraceEnabled())
                    log.trace("seqno " + seqno + " is smaller than " + next_to_add + "); discarding message");
                return false;
            }

            // Case #3: we finally received a missing message. Case #2 handled seqno <= highest_delivered, so this
            // seqno *must* be between highest_delivered and next_to_add 
            if(seqno < next_to_add) {
                Message existing=xmit_table.putIfAbsent(seqno, msg);
                if(existing != null)
                    return false; // key/value was present
                num_xmits=retransmitter.remove(seqno);
                missing_msg_received = true;
                if(log.isTraceEnabled())
                    log.trace(new StringBuilder("added missing msg ").append(msg.getSrc()).append('#').append(seqno));
                return true;
            }

            // Case #4: we received a seqno higher than expected: add to Retransmitter
            if(seqno > next_to_add) {
                xmit_table.put(seqno, msg);
                retransmitter.add(old_next, seqno -1);     // BUT: add only null messages to xmitter
                if(listener != null) {
                    try {listener.messageGapDetected(next_to_add, seqno, msg.getSrc());} catch(Throwable t) {}
                }
                return true;
            }
        }
        finally {
            highest_received=Math.max(highest_received, seqno);
            lock.writeLock().unlock();
        }

        if(listener != null && missing_msg_received) {
            try {listener.missingMessageReceived(seqno, msg.getSrc());} catch(Throwable t) {}
        }

        return true;
    }



    public Message remove() {
        return remove(true, false);
    }


    public Message remove(boolean acquire_lock, boolean remove_msg) {
        Message retval;

        if(acquire_lock)
            lock.writeLock().lock();
        try {
            long next=highest_delivered +1;
            retval=remove_msg? xmit_table.remove(next) : xmit_table.get(next);

            if(retval != null) { // message exists and is ready for delivery
                highest_delivered=next;
                return retval;
            }
            return null;
        }
        finally {
            if(acquire_lock)
                lock.writeLock().unlock();
        }
    }


    /**
     * Removes as many messages as possible
     * @return List<Message> A list of messages, or null if no available messages were found
     */
    public List<Message> removeMany(final AtomicBoolean processing) {
        return removeMany(processing, false, 0);
    }


    /**
     * Removes as many messages as possible
     * @param remove_msgs Removes messages from xmit_table
     * @param max_results Max number of messages to remove in one batch
     * @return List<Message> A list of messages, or null if no available messages were found
     */
    public List<Message> removeMany(final AtomicBoolean processing, boolean remove_msgs, int max_results) {
        List<Message> retval=null;
        int num_results=0;

        lock.writeLock().lock();
        try {
            while(true) {
                long next=highest_delivered +1;
                Message msg=remove_msgs? xmit_table.remove(next) : xmit_table.get(next);
                if(msg != null) { // message exists and is ready for delivery
                    highest_delivered=next;
                    if(retval == null)
                        retval=new LinkedList<Message>();
                    retval.add(msg);
                    if(max_results <= 0 || ++num_results < max_results)
                        continue;
                }

                if((retval == null || retval.isEmpty()) && processing != null)
                    processing.set(false);
                return retval;
            }
        }
        finally {
            lock.writeLock().unlock();
        }
    }



    /**
     * Delete all messages <= seqno (they are stable, that is, have been received at all members).
     * Stop when a number > seqno is encountered (all messages are ordered on seqnos).
     */
    public void stable(long seqno) {
        lock.writeLock().lock();
        try {
            if(seqno > highest_delivered) {
                if(log.isWarnEnabled())
                    log.warn("seqno " + seqno + " is > highest_delivered (" + highest_delivered + ";) ignoring stability message");
                return;
            }

            // we need to remove all seqnos *including* seqno
            xmit_table.purge(seqno);
            
            // remove all seqnos below seqno from retransmission
            for(long i=low; i <= seqno; i++) {
                retransmitter.remove(i);
            }

            highest_stability_seqno=Math.max(highest_stability_seqno, seqno);
            low=Math.max(low, seqno);
        }
        finally {
            lock.writeLock().unlock();
        }
    }


    /**
     * Destroys the NakReceiverWindow. After this method returns, no new messages can be added and a new
     * NakReceiverWindow should be used instead. Note that messages can still be <em>removed</em> though.
     */
    public void destroy() {
        lock.writeLock().lock();
        try {
            running=false;
            retransmitter.reset();
            xmit_table.clear();
            low=0;
            highest_delivered=0; // next (=first) to deliver will be 1
            highest_received=0;
            highest_stability_seqno=0;
        }
        finally {
            lock.writeLock().unlock();
        }
    }


    /** Returns the lowest, highest delivered and highest received seqnos */
    public long[] getDigest() {
        lock.readLock().lock();
        try {
            long[] retval=new long[3];
            retval[0]=low;
            retval[1]=highest_delivered;
            retval[2]=highest_received;
            return retval;
        }
        finally {
            lock.readLock().unlock();
        }
    }


    /**
     * @return the lowest sequence number of a message that has been
     * delivered or is a candidate for delivery (by the next call to
     * <code>remove()</code>)
     */
    public long getLowestSeen() {
        lock.readLock().lock();
        try {
            return low;
        }
        finally {
            lock.readLock().unlock();
        }
    }


    /** Returns the highest sequence number of a message <em>consumed</em> by the application (by <code>remove()</code>).
     * Note that this is different from the highest <em>deliverable</em> seqno. E.g. in 23,24,26,27,29, the highest
     * <em>delivered</em> message may be 22, whereas the highest <em>deliverable</em> message may be 24 !
     * @return the highest sequence number of a message consumed by the
     * application (by <code>remove()</code>)
     */
    public long getHighestDelivered() {
        lock.readLock().lock();
        try {
            return highest_delivered;
        }
        finally {
            lock.readLock().unlock();
        }
    }


    public long setHighestDelivered(long new_val) {
        lock.writeLock().lock();
        try {
            long retval=highest_delivered;
            highest_delivered=new_val;
            return retval;
        }
        finally {
            lock.writeLock().unlock();
        }
    }


    /**
     * Returns the highest sequence number received so far (which may be
     * higher than the highest seqno <em>delivered</em> so far; e.g., for
     * 1,2,3,5,6 it would be 6.
     *
     * @see NakReceiverWindow#getHighestDelivered
     */
    public long getHighestReceived() {
        lock.readLock().lock();
        try {
            return highest_received;
        }
        finally {
            lock.readLock().unlock();
        }
    }


    /**
     * Returns the message from xmit_table
     * @param seqno
     * @return Message from xmit_table
     */
    public Message get(long seqno) {
        lock.readLock().lock();
        try {
            return xmit_table.get(seqno);
        }
        finally {
            lock.readLock().unlock();
        }
    }


    /**
     * Returns a list of messages in the range [from .. to], including from and to
     * @param from
     * @param to
     * @return A list of messages, or null if none in range [from .. to] was found
     */
    public List<Message> get(long from, long to) {
        lock.readLock().lock();
        try {
            return xmit_table.get(from, to);
        }
        finally {
            lock.readLock().unlock();
        }
    }


    public int size() {
        lock.readLock().lock();
        try {
            return xmit_table.size();
        }
        finally {
            lock.readLock().unlock();
        }
    }


    public String toString() {
        lock.readLock().lock();
        try {
            return printMessages();
        }
        finally {
            lock.readLock().unlock();
        }
    }



    /**
     * Prints xmit_table. Requires read lock to be present
     * @return String
     */
    protected String printMessages() {
        StringBuilder sb=new StringBuilder();
        lock.readLock().lock();
        try {
            sb.append('[').append(low).append(" : ").append(highest_delivered).append(" (").append(highest_received).append(")");
            if(xmit_table != null && !xmit_table.isEmpty()) {
                int non_received=xmit_table.getNullMessages(highest_received);
                sb.append(" (size=").append(xmit_table.size()).append(", missing=").append(non_received).
                  append(", highest stability=").append(highest_stability_seqno).append(')');
            }
            sb.append(']');
            return sb.toString();
        }
        finally {
            lock.readLock().unlock();
        }
    }

    public String printLossRate() {
        StringBuilder sb=new StringBuilder();
        int num_missing=getPendingXmits();
        int num_received=size();
        int total=num_missing + num_received;
        sb.append("total=").append(total).append(" (received=").append(num_received).append(", missing=")
                .append(num_missing).append("), loss rate=").append(getLossRate())
                .append(", smoothed loss rate=").append(smoothed_loss_rate);
        return sb.toString();
    }

    public String printRetransmitStats() {
        return retransmitter instanceof RangeBasedRetransmitter? ((RangeBasedRetransmitter)retransmitter).printStats() : "n/a";
    }

    /* ------------------------------- Private Methods -------------------------------------- */



    /* --------------------------- End of Private Methods ----------------------------------- */


}
