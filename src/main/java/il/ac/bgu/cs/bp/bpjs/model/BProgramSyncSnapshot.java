package il.ac.bgu.cs.bp.bpjs.model;

import il.ac.bgu.cs.bp.bpjs.execution.BProgramRunner;
import il.ac.bgu.cs.bp.bpjs.exceptions.BProgramException;
import il.ac.bgu.cs.bp.bpjs.execution.tasks.ResumeBThread;
import il.ac.bgu.cs.bp.bpjs.execution.tasks.StartBThread;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContinuationPending;
import org.mozilla.javascript.Scriptable;
import il.ac.bgu.cs.bp.bpjs.execution.listeners.BProgramRunnerListener;

/**
 * The state of a {@link BProgram} when all its BThreads are at {@code bsync}.
 * This is more than a set of {@link BThreadSyncSnapshot}s, as it contains
 * the queue of external events as well.
 * 
 * @author michael
 */
public class BProgramSyncSnapshot {
    
    private final Set<BThreadSyncSnapshot> threadSnapshots;
    private final List<BEvent> externalEvents;
    private final BProgram bprog;

	public boolean triggered=false;
    
    public BProgramSyncSnapshot(BProgram aBProgram, Set<BThreadSyncSnapshot> threadSnapshots, List<BEvent> externalEvents) {
        this.threadSnapshots = threadSnapshots;
        this.externalEvents = externalEvents;
        bprog = aBProgram;
    }
    
    public BProgramSyncSnapshot copyWith( List<BEvent> updatedExternalEvents ) {
        return new BProgramSyncSnapshot(bprog, threadSnapshots, updatedExternalEvents);
    }

    /**
     * Starts the BProgram - runs all the registered b-threads to their first 
     * {@code bsync}. 
     * 
     * @return A snapshot of the program at the first {@code bsync}.
     * @throws java.lang.InterruptedException
     */
    public BProgramSyncSnapshot start() throws InterruptedException {
        Set<BThreadSyncSnapshot> nextRound = new HashSet<>(threadSnapshots.size());
        nextRound.addAll(BProgramRunner.getExecutorService().invokeAll(threadSnapshots.stream()
                    .map(bt -> new StartBThread(bt))
                    .collect(toList())
                ).stream().map(f -> safeGet(f) ).collect(toList())
        );
        
        executeAllAddedBThreads(nextRound);
        List<BEvent> nextExternalEvents = new ArrayList<>(getExternalEvents());
        nextExternalEvents.addAll( bprog.drainEnqueuedExternalEvents() );
        return new BProgramSyncSnapshot(bprog, nextRound, nextExternalEvents);
    }

    public BProgramSyncSnapshot triggerEvent(BEvent anEvent) throws InterruptedException {
    	if(triggered) {
    		throw new IllegalStateException("A BProgramSyncSnapshot is not allowed to be triggered twice.");
    	}
    	
    	triggered = true;
    	
        return triggerEvent(anEvent, Collections.emptySet());
    }
    
    /**
     * Runs the program from the snapshot, triggering the passed event.
     * @param anEvent the event selected.
     * @param listeners 
     * @return A set of b-thread snapshots that should participate in the next cycle.
     * @throws InterruptedException 
     */
    public BProgramSyncSnapshot triggerEvent(BEvent anEvent, Iterable<BProgramRunnerListener> listeners) throws InterruptedException {
        if (anEvent == null) throw new IllegalArgumentException("Cannot trigger a null event.");
        
        Set<BThreadSyncSnapshot> resumingThisRound = new HashSet<>(threadSnapshots.size());
        Set<BThreadSyncSnapshot> sleepingThisRound = new HashSet<>(threadSnapshots.size());
        Set<BThreadSyncSnapshot> nextRound = new HashSet<>(threadSnapshots.size());
        List<BEvent> nextExternalEvents = new ArrayList<>(getExternalEvents());
        try {
            Context ctxt = Context.enter();
            handleInterrupts(anEvent, listeners, bprog, ctxt);
            nextExternalEvents.addAll(bprog.drainEnqueuedExternalEvents());
            
            // Split threads to those that advance this round and those that sleep.
            threadSnapshots.forEach( snapshot -> {
                (snapshot.getBSyncStatement().shouldWakeFor(anEvent) ? resumingThisRound : sleepingThisRound).add(snapshot);
            });
        } finally {
            Context.exit();
        }
        
        // add the run results of all those who advance this stage
        nextRound.addAll(BProgramRunner.getExecutorService().invokeAll(
                            resumingThisRound.stream()
                                             .map(bt -> new ResumeBThread(bt, anEvent))
                                             .collect(toList())
                ).stream().map(f -> safeGet(f) ).filter(Objects::nonNull).collect(toList())
        );
        
        // inform listeners which b-threads completed
        Set<String> nextRoundIds = nextRound.stream().map(t->t.getName()).collect(toSet());
        resumingThisRound.stream().filter(t->!nextRoundIds.contains(t.getName()))
                .forEach(t->listeners.forEach(l->l.bthreadDone(bprog, t)));
        
        executeAllAddedBThreads(nextRound);
        nextExternalEvents.addAll( bprog.drainEnqueuedExternalEvents() );
        
        // carry over BThreads that did not advance this round to next round.
        nextRound.addAll(sleepingThisRound);
        
        return new BProgramSyncSnapshot(bprog, nextRound, nextExternalEvents);
    }

    private void handleInterrupts(BEvent anEvent, Iterable<BProgramRunnerListener> listeners, BProgram bprog, Context ctxt) {
        Set<BThreadSyncSnapshot> interrupted = threadSnapshots.stream()
                .filter(bt -> bt.getBSyncStatement().getInterrupt().contains(anEvent))
                .collect(toSet());
        if (!interrupted.isEmpty()) {
            threadSnapshots.removeAll(interrupted);
            interrupted.forEach(bt -> {
                listeners.forEach(l -> l.bthreadRemoved(bprog, bt));
                bt.getInterrupt()
                        .ifPresent( func -> {
                            final Scriptable scope = bt.getScope();
                            scope.delete("bsync"); // can't call bsync from a break handler.
                            try {
                                ctxt.callFunctionWithContinuations(func, scope, new Object[]{anEvent});
                            } catch ( ContinuationPending ise ) {
                                throw new BProgramException("Cannot call bsync from a break-upon handler. Consider pushing an external event.");
                            }
                        });
            });
        }
    }

    public List<BEvent> getExternalEvents() {
        return externalEvents;
    }

    public Set<BThreadSyncSnapshot> getBThreadSnapshots() {
        return threadSnapshots;
    }
    
    public Set<BSyncStatement> getStatements() {
        return getBThreadSnapshots().stream().map(BThreadSyncSnapshot::getBSyncStatement)
                .collect(toSet());
    }
    
    /**
     * Does this snapshot have any b-threads to run? If not, this means that
     * the b-program has terminated.
     * 
     * @return {@code true} iff the snapshot contains b-threads.
     */
    public boolean noBThreadsLeft() {
        return threadSnapshots.isEmpty();
    }
    
    /**
     * 
     * @return The BProgram this object is a snapshot of.
     */
    public BProgram getBProgram() {
        return bprog;
    }
    
    private BThreadSyncSnapshot safeGet(Future<BThreadSyncSnapshot> fbss) {
        try {
            return fbss.get();
        } catch (InterruptedException | ExecutionException ex) {
            Logger.getLogger(BProgramSyncSnapshot.class.getName()).log(Level.SEVERE, null, ex);
            throw new RuntimeException("Error running a bthread: " + ex.getMessage(), ex);
        }
    }
    
    /**
     * Executes and adds all newly registered b-threads, until no more new b-threads 
     * are registered.
     * @param listeners
     * @param nextRound the set of b-threads that will participate in the next round
     * @throws InterruptedException 
     */
    private void executeAllAddedBThreads(Set<BThreadSyncSnapshot> nextRound) throws InterruptedException {
        // if any new bthreads are added, run and add their result
        Set<BThreadSyncSnapshot> added = bprog.drainRecentlyRegisteredBthreads();
        while ( ! added.isEmpty() ) {
            nextRound.addAll(BProgramRunner.getExecutorService().invokeAll(
                    added.stream()
                            .map(bt -> new StartBThread(bt))
                            .collect(toList())
            ).stream().map(f -> safeGet(f) ).filter(Objects::nonNull).collect(toList()));
            added = bprog.drainRecentlyRegisteredBthreads();
        }
    }

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((threadSnapshots == null) ? 0 : threadSnapshots.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		BProgramSyncSnapshot other = (BProgramSyncSnapshot) obj;
		return Objects.equals(threadSnapshots, other.threadSnapshots);
	}


}
