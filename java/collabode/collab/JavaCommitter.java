package collabode.collab;

import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.eclipse.jdt.core.*;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IRegion;
import org.eclipse.text.edits.ReplaceEdit;

import collabode.*;

/**
 * Commits error-free changes in Java files.
 */
public class JavaCommitter extends WorkingCopyOwner implements CollabListener, Runnable, IProblemRequestor {
    
    private final BlockingQueue<CollabDocument> queue = new LinkedBlockingQueue<CollabDocument>();
    private final BlockingQueue<IProblem[]> reported = new LinkedBlockingQueue<IProblem[]>(1);
    
    public JavaCommitter(Collab collab) {
        new Thread(this, getClass().getSimpleName() + " " + collab.id).start();
    }
    
    public void updated(PadDocument doc) {
        queue.add(doc.collab);
    }
    
    public void committed(CollabDocument doc) {
        Collection<CollabDocument> others = doc.collaboration.get(doc.file.getProject());
        for (CollabDocument other : others) {
            if ( ! other.equals(doc)) { queue.add(other); }
        }
    }
    
    public void run() {
        while (true) {
            try {
                CollabDocument doc = queue.take();
                queue.removeAll(Collections.singleton(doc));
                handleUpdated(doc);
            } catch (BadLocationException ble) {
                ble.printStackTrace(); // XXX
            } catch (JavaModelException jme) {
                jme.printStackTrace(); // XXX
            } catch (InterruptedException ie) {
                ie.printStackTrace(); // XXX
            } catch (Exception e) {
                e.printStackTrace(); // XXX ... and madly soldier on
            }
        }
    }
    
    private void handleUpdated(CollabDocument doc) throws BadLocationException, JavaModelException, InterruptedException {
        List<ReplaceEdit> edits = new ArrayList<ReplaceEdit>();
        
        List<IRegion> regions = doc.unionOnlyRegionsOfDisk();
        for (IRegion region : regions) {
            int diskOffset = doc.unionToDiskOffset(region.getOffset());
            edits.add(new ReplaceEdit(diskOffset, 0, doc.union.get(region.getOffset(), region.getLength())));
        }
        
        regions = doc.localOnlyRegionsOfDisk();
        for (IRegion region : regions) {
            int diskOffset = doc.unionToDiskOffset(region.getOffset()) - region.getLength();
            edits.add(new ReplaceEdit(diskOffset, region.getLength(), ""));
        }
        
        handleEdits(doc, edits);
    }
    
    private void handleEdits(CollabDocument doc, List<ReplaceEdit> options) throws BadLocationException, JavaModelException, InterruptedException {
        if (options.isEmpty()) { return; }
        
        Collections.sort(options, new Comparator<ReplaceEdit>() {
            public int compare(ReplaceEdit e1, ReplaceEdit e2) {
                int offset = e2.getOffset() - e1.getOffset();
                if (offset != 0) { return offset; }
                return e2.getLength() - e1.getLength(); // deletions first
            }
        });
        
        if ( ! JavaCore.isJavaLikeFileName(doc.file.getName())) { // XXX duplicated from PadDocumentOwner, fishy
            doc.commitDiskCoordinateEdits(options); // XXX is this right?
            return;
        }
        
        ICompilationUnit wc = JavaCore.createCompilationUnitFrom(doc.file).getWorkingCopy(this, null);
        final String disk = wc.getBuffer().getContents();
        Set<Problem> existing = Problem.set(reported.take());
        
        Collection<ReplaceEdit> accepted = bestEdits(wc, disk, options, existing);
        wc.discardWorkingCopy();
        
        if ( ! accepted.isEmpty()) {
            doc.commitDiskCoordinateEdits(accepted);
        }
    }
    
    private Collection<ReplaceEdit> bestEdits(ICompilationUnit wc, String disk, List<ReplaceEdit> options, Set<Problem> existing) throws JavaModelException, InterruptedException {
        if (options.isEmpty()) { return options; }
        
        Collection<ReplaceEdit> best = new ArrayList<ReplaceEdit>();
        
        for (int ii = 0; ii < options.size(); ii += LargeToSmallPowerSet.MAX_SIZE) {
            List<ReplaceEdit> considerable = options.subList(ii, Math.min(options.size(), ii + LargeToSmallPowerSet.MAX_SIZE));
            for (Collection<ReplaceEdit> subset : new LargeToSmallPowerSet<ReplaceEdit>(considerable)) {
                wc.getBuffer().setContents(disk);
                CoordinateMap map = new CoordinateMap();
                for (ReplaceEdit edit : subset) {
                    wc.getBuffer().replace(edit.getOffset(), edit.getLength(), edit.getText());
                    map.unionOnly(edit);
                }
                wc.reconcile(ICompilationUnit.NO_AST, true, this, null);
                IProblem[] problems = reported.take();
                
                if (existing.containsAll(Problem.set(problems, map))) {
                    best.addAll(subset);
                    break;
                }
            }
        }
        
        return best;
    }
    
    // Extending WorkingCopyOwner
    
    @Override public IProblemRequestor getProblemRequestor(ICompilationUnit workingCopy) {
        return this;
    }
    
    // Implementing IProblemRequestor
    
    private final List<IProblem> problems = new LinkedList<IProblem>();
    
    public void beginReporting() {
        problems.clear();
    }
    
    public void acceptProblem(IProblem problem) {
        if (problem.isError()) { problems.add(problem); }
    }
    
    public void endReporting() {
        reported.add(problems.toArray(new IProblem[0]));
    }
    
    public boolean isActive() {
        return true;
    }
}

/**
 * {@link IProblem} wrapper with equality relation.
 */
class Problem {
    
    /**
     * Wrap a set of problems.
     */
    static Set<Problem> set(IProblem[] problems) {
        return set(problems, IdentityMap.IDENT);
    }
    
    /**
     * Translate and wrap a set of problems.
     */
    static Set<Problem> set(IProblem[] problems, CoordinateTranslation map) {
        Set<Problem> set = new HashSet<Problem>();
        for (IProblem prob : problems) { set.add(new Problem(prob, map)); }
        return set;
    }
    
    final int id;
    final String[] args;
    final int start;
    final int end;
    
    private Problem(IProblem problem, CoordinateTranslation map) {
        id = problem.getID();
        args = problem.getArguments();
        start = map.unionToLocal(problem.getSourceStart());
        end = map.unionToLocal(problem.getSourceEnd());
    }
    
    @Override public boolean equals(Object obj) {
        if ( ! (obj instanceof Problem)) { return false; }
        Problem other = (Problem)obj;
        return id == other.id && Arrays.equals(args, other.args) && start == other.start && end == other.end;
    }
    
    @Override public int hashCode() {
        return id + Arrays.hashCode(args) + start + end;
    }
}

/**
 * Power set enumerating subsets from largest to smallest.
 * Only enumerates subsets of size <code>Integer.SIZE - 1</code> and smaller.
 *
 * @see http://graphics.stanford.edu/~seander/bithacks.html#NextBitPermutation
 * @see http://code.google.com/p/guava-libraries/source/browse/trunk/guava/src/com/google/common/collect/Sets.java
 * @see http://www.pingel.org/xref/org/pingel/util/PowerSet.html
 */
class LargeToSmallPowerSet<E> extends AbstractCollection<Collection<E>> {
    
    public static final int MAX_SIZE = Integer.SIZE - 1;
    
    private final List<E> elements;
    
    LargeToSmallPowerSet(Collection<E> elements) {
        this.elements = new ArrayList<E>(elements);
    }
    
    public int size() {
        return 1 << elements.size();
    }
    
    public Iterator<Collection<E>> iterator() {
        return new Iterator<Collection<E>>() { // iterator over power set sets
            final int size = Math.min(MAX_SIZE, elements.size());
            int selected = size + 1;
            int itMask = 0;
            
            public void remove() {
                throw new UnsupportedOperationException();
            }
            
            public boolean hasNext() {
                return selected > 0;
            }
            
            public Collection<E> next() {
                if (Integer.numberOfTrailingZeros(itMask) >= size - selected) {
                    selected--;
                    itMask = (1 << selected) - 1; // lexically first
                } else {
                    int t = itMask | (itMask - 1);
                    itMask = (t + 1) | (((~t & -~t) - 1) >> (Integer.numberOfTrailingZeros(itMask) + 1)); // lexically next
                }
                
                return new AbstractCollection<E>() {
                    final int setMask = itMask;
                    
                    public int size() {
                        return Integer.bitCount(setMask);
                    }
                    
                    public Iterator<E> iterator() { // iterator over power set set elements
                        return new Iterator<E>() {
                            int mask = setMask; // bitmask of elements to include
                            
                            public void remove() {
                                throw new UnsupportedOperationException();
                            }
                            
                            public boolean hasNext() {
                                return mask > 0;
                            }
                            
                            public E next() {
                                int idx = Integer.numberOfTrailingZeros(mask); // position of first 1 bit
                                mask &= ~(1 << idx); // knock out that bit
                                return elements.get(idx);
                            }
                        };
                    }
                };
            }
        };
    }
}
