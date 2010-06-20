/*
 * Copyright (c) 2009 The Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

package org.broadinstitute.sting.gatk.iterators;

import net.sf.samtools.*;
import net.sf.picard.filter.SamRecordFilter;
import org.apache.log4j.Logger;
import org.broadinstitute.sting.gatk.Reads;
import org.broadinstitute.sting.gatk.GenomeAnalysisEngine;
import org.broadinstitute.sting.gatk.contexts.AlignmentContext;
import org.broadinstitute.sting.utils.*;
import org.broadinstitute.sting.utils.sam.ReadUtils;
import org.broadinstitute.sting.utils.pileup.*;

import java.util.*;

/** Iterator that traverses a SAM File, accumulating information on a per-locus basis */
public class LocusIteratorByState extends LocusIterator {
    private static long discarded_bases = 0L;
    private static long observed_bases = 0L;

    //
    // todo -- eric, add your UG filters here
    //
    //public enum Discard { ADAPTOR_BASES }
    //public static final EnumSet<Discard> NO_DISCARDS = EnumSet.noneOf(Discard.class);

    public static final List<LocusIteratorFilter> NO_FILTERS = Arrays.asList();

    /**
     * the overflow tracker, which makes sure we get a limited number of warnings for locus pile-ups that
     * exceed the max depth
     */
    private LocusOverflowTracker overflowTracker;

    /** our log, which we want to capture anything from this class */
    private static Logger logger = Logger.getLogger(LocusIteratorByState.class);

    // -----------------------------------------------------------------------------------------------------------------
    //
    // member fields
    //
    // -----------------------------------------------------------------------------------------------------------------
    private final PushbackIterator<SAMRecord> it;
    private boolean hasExtendedEvents = false; // will be set to true if at least one read had an indel right before the current position

    private class SAMRecordState {
        SAMRecord read;
        int readOffset = -1;     // how far are we offset from the start of the read bases?
        int genomeOffset = -1;   // how far are we offset from the alignment start on the genome?

        Cigar cigar = null;
        int cigarOffset = -1;
        CigarElement curElement = null;
        int nCigarElements = 0;

        // how far are we into a single cigarElement
        int cigarElementCounter = -1;

        // The logical model for generating extended events is as follows: the "record state" implements the traversal
        // along the reference; thus stepForwardOnGenome() returns on every and only on actual reference bases. This
        // can be a (mis)match or a deletion (in the latter case, we still return on every individual reference base the
        // deletion spans). In the extended events mode, the record state also remembers if there was an insertion, or
        // if the deletion just started *right before* the current reference base the record state is pointing to upon the return from
        // stepForwardOnGenome(). The next call to stepForwardOnGenome() will clear that memory (as we remember only extended
        // events immediately preceding the current reference base).

        boolean generateExtendedEvents = true; // should we generate an additional,  special pile for indels between the ref bases?
        // the only purpose of this flag is to shield away a few additional lines of code
        // when extended piles are not needed, it may not be even worth it...
        byte[] insertedBases = null; // remember full inserted sequence if we are generating piles of extended events (indels)
        int eventLength = -1; // will be set to the length of insertion/deletion if we are generating piles of extended events
        byte eventDelayedFlag = 0; // will be set to non-0 if there was an event (indel) right before the
        // current base on the ref. We use a counter-like variable here since clearing the indel event is
        // delayed by one base, so we need to remember how long ago we have seen the actual event
        int eventStart = -1; // where on the read the extended event starts (i.e. the last position on the read prior to the
        // event, or -1 if alignment starts with an insertion); this one is easy to recompute on the fly,
        // we cache it here mainly for convenience


        public SAMRecordState(SAMRecord read, boolean extended) {
            this.read = read;
            cigar = read.getCigar();
            nCigarElements = cigar.numCigarElements();
            generateExtendedEvents = extended;

            //System.out.printf("Creating a SAMRecordState: %s%n", this);
        }

        public SAMRecordState(SAMRecord read) {
            this(read,false);
        }

        public SAMRecord getRead() { return read; }

        /**
         * What is our current offset in the read's bases that aligns us with the reference genome?
         *
         * @return
         */
        public int getReadOffset() { return readOffset; }

        /**
         * What is the current offset w.r.t. the alignment state that aligns us to the readOffset?
         *
         * @return
         */
        public int getGenomeOffset() { return genomeOffset; }

        public int getGenomePosition() { return read.getAlignmentStart() + getGenomeOffset(); }

        public GenomeLoc getLocation() {
            return GenomeLocParser.createGenomeLoc(read.getReferenceName(), getGenomePosition());
        }

        public CigarOperator getCurrentCigarOperator() {
            return curElement.getOperator();
        }

        /** Returns true if we just stepped over insertion/into a deletion prior to the last return from stepForwardOnGenome.
         *
         * @return
         */
        public boolean hadIndel() {
            return ( eventLength > 0 );
        }

        public int getEventLength() { return eventLength; }

        public byte[] getEventBases() { return insertedBases; }

        public int getReadEventStartOffset() { return eventStart; }

        public String toString() {
            return String.format("%s ro=%d go=%d co=%d cec=%d %s", read.getReadName(), readOffset, genomeOffset, cigarOffset, cigarElementCounter, curElement);
        }

        public CigarOperator stepForwardOnGenome() {
            // we enter this method with readOffset = index of the last processed base on the read
            // (-1 if we did not process a single base yet); this can be last matching base, or last base of an insertion


            if ( curElement == null || ++cigarElementCounter > curElement.getLength() ) {
                cigarOffset++;
                if ( cigarOffset < nCigarElements ) {
                    curElement = cigar.getCigarElement(cigarOffset);
                    cigarElementCounter = 0;
                    // next line: guards against cigar elements of length 0; when new cigar element is retrieved,
                    // we reenter in order to re-check cigarElementCounter against curElement's length
                    return stepForwardOnGenome();
                } else {
                    if ( generateExtendedEvents && eventDelayedFlag > 0 ) {
                        genomeOffset++; // extended events need that. Logically, it's legal to advance the genomic offset here:
                                        // we do step forward on the ref, and by returning null we also indicate that we are past the read end.

                        // if we had an indel right before the read ended (i.e. insertion was the last cigar element),
                        // we keep it until next reference base; then we discard it and this will allow the LocusIterator to
                        // finally discard this read
                        eventDelayedFlag--;
                        if ( eventDelayedFlag == 0 )  {
                            eventLength = -1; // reset event when we are past it
                            insertedBases = null;
                            eventStart = -1;
                        }
                    }
                    return null;
                }
            }


            boolean done = false;
            switch (curElement.getOperator()) {
                case H : // ignore hard clips
                case P : // ignore pads
                    cigarElementCounter = curElement.getLength();
                    break;
                case I : // insertion w.r.t. the reference
                    if ( generateExtendedEvents ) {
                        // we see insertions only once, when we step right onto them; the position on the read is scrolled
                        // past the insertion right after that
                        if ( eventDelayedFlag > 1 ) throw new StingException("Adjacent I/D events in read "+read.getReadName());
                        insertedBases = Arrays.copyOfRange(read.getReadBases(),readOffset+1,readOffset+1+curElement.getLength());
                        eventLength = curElement.getLength() ;
                        eventStart = readOffset;
                        eventDelayedFlag = 2; // insertion causes re-entry into stepForwardOnGenome, so we set the delay to 2
//                        System.out.println("Inserted "+(new String (insertedBases)) +" after "+readOffset);
                    } // continue onto the 'S' case !
                case S : // soft clip
                    cigarElementCounter = curElement.getLength();
                    readOffset += curElement.getLength();
                    break;
                case D : // deletion w.r.t. the reference
                    if ( generateExtendedEvents ) {
                        if ( cigarElementCounter == 1) {
                            // generate an extended event only if we just stepped into the deletion (i.e. don't
                            // generate the event at every deleted position on the ref, that's what cigarElementCounter==1 is for!)
                            if ( eventDelayedFlag > 1 ) throw new StingException("Adjacent I/D events in read "+read.getReadName());
                            eventLength = curElement.getLength();
                            eventDelayedFlag = 2; // deletion on the ref causes an immediate return, so we have to delay by 1 only
                            eventStart = readOffset;
                            insertedBases = null;
//                            System.out.println("Deleted "+eventLength +" bases after "+readOffset);
                        }
                    } // continue onto the 'N' case !
                case N : // reference skip (looks and gets processed just like a "deletion", just different logical meaning)
                    genomeOffset++;
                    done = true;
                    break;
                case M :
                    readOffset++;
                    genomeOffset++;
                    done = true;
                    break;
                default : throw new IllegalStateException("Case statement didn't deal with cigar op: " + curElement.getOperator());
            }

            if ( generateExtendedEvents ) {
                if ( eventDelayedFlag > 0 && done ) {
                    // if we did make a successful step on the ref, decrement delayed flag. If, upon the decrementthe,
                    // the flag is 1, we are standing on the reference base right after the indel (so we have to keep it).
                    // Otherwise, we are away from the previous indel and have to clear our memories...
                    eventDelayedFlag--; // when we notice an indel, we set delayed flag to 2, so now
                                    // if eventDelayedFlag == 1, an indel occured right before the current base
                    if ( eventDelayedFlag == 0 ) {
                        eventLength = -1; // reset event when we are past it
                        insertedBases = null;
                        eventStart = -1;
                    }
                }
            }

            return done ? curElement.getOperator() : stepForwardOnGenome();
        }
    }

    private LinkedList<SAMRecordState> readStates = new LinkedList<SAMRecordState>();
    //final boolean DEBUG = false;
    //final boolean DEBUG2 = false && DEBUG;
    private Reads readInfo;
    private AlignmentContext nextAlignmentContext;
    private List<LocusIteratorFilter> filters = new ArrayList<LocusIteratorFilter>();

    // -----------------------------------------------------------------------------------------------------------------
    //
    // constructors and other basic operations
    //
    // -----------------------------------------------------------------------------------------------------------------
    public LocusIteratorByState(final Iterator<SAMRecord> samIterator, Reads readInformation ) {
        this(samIterator, readInformation, NO_FILTERS);
    }

    public LocusIteratorByState(final Iterator<SAMRecord> samIterator, Reads readInformation, List<LocusIteratorFilter> filters ) {
        this.it = new PushbackIterator<SAMRecord>(samIterator);
        this.readInfo = readInformation;
        this.filters = filters;
        overflowTracker = new LocusOverflowTracker(readInformation.getMaxReadsAtLocus());
    }

    public Iterator<AlignmentContext> iterator() {
        return this;
    }

    public void close() {
        //this.it.close();
    }

    public boolean hasNext() {
        lazyLoadNextAlignmentContext();
        boolean r = (nextAlignmentContext != null);
        //if ( DEBUG ) System.out.printf("hasNext() = %b%n", r);

        // if we don't have a next record, make sure we clean the warning queue
        if (!r) overflowTracker.cleanWarningQueue();
        return r;
    }

    public void printState() {
        for ( SAMRecordState state : readStates ) {
            logger.debug(String.format("printState():"));
            SAMRecord read = state.getRead();
            int offset = state.getReadOffset();
            logger.debug(String.format("  read: %s(%d)=%s, cigar=%s", read.getReadName(), offset, (char)read.getReadBases()[offset], read.getCigarString()));
        }
    }

    public void clear() {
        logger.debug(String.format(("clear() called")));
        readStates.clear();
    }

    private GenomeLoc getLocation() {
        return readStates.isEmpty() ? null : readStates.getFirst().getLocation();
    }

    // -----------------------------------------------------------------------------------------------------------------
    //
    // next() routine and associated collection operations
    //
    // -----------------------------------------------------------------------------------------------------------------
    public AlignmentContext next() {
        lazyLoadNextAlignmentContext();
        if(!hasNext())
            throw new NoSuchElementException("LocusIteratorByState: out of elements.");
        AlignmentContext currentAlignmentContext = nextAlignmentContext;
        nextAlignmentContext = null;
        return currentAlignmentContext;
    }

    /**
     * Creates the next alignment context from the given state.  Note that this is implemented as a lazy load method.
     * nextAlignmentContext MUST BE null in order for this method to advance to the next entry.
     */
    private void lazyLoadNextAlignmentContext() {
        while(nextAlignmentContext == null && (!readStates.isEmpty() || it.hasNext())) {
            // this call will set hasExtendedEvents to true if it picks up a read with indel right before the current position on the ref:
            collectPendingReads(readInfo.getMaxReadsAtLocus());

            int size = 0;
            int nDeletions = 0;
            int nInsertions = 0;
            int nMQ0Reads = 0;


            // if extended events are requested, and if previous traversal step brought us over an indel in
            // at least one read, we emit extended pileup (making sure that it is associated with the previous base,
            // i.e. the one right *before* the indel) and do NOT shift the current position on the ref.
            // In this case, the subsequent call to next() will emit the normal pileup at the current base
            // and shift the position.
            if (readInfo.generateExtendedEvents() && hasExtendedEvents) {
                ArrayList<ExtendedEventPileupElement> indelPile = new ArrayList<ExtendedEventPileupElement>(readStates.size());

                int maxDeletionLength = 0;

                for ( SAMRecordState state : readStates ) {
                    if ( state.hadIndel() ) {
                        size++;
                        if ( state.getEventBases() == null ) {
                            nDeletions++;
                            maxDeletionLength = Math.max(maxDeletionLength,state.getEventLength());
                        }
                        else nInsertions++;
                        indelPile.add ( new ExtendedEventPileupElement(state.getRead(),
                                                                       state.getReadEventStartOffset(),
                                                                       state.getEventLength(),
                                                                       state.getEventBases()) );

                    }   else {
                        if ( state.getCurrentCigarOperator() != CigarOperator.N ) {
                            // this read has no indel associated with the previous position on the ref;
                            // we count this read in only if it has actual bases, not N span...
                            if ( state.getCurrentCigarOperator() != CigarOperator.D || readInfo.includeReadsWithDeletionAtLoci() ) {

                                // if cigar operator is D but the read has no extended event reported (that's why we ended
                                // up in this branch), it means that we are currently inside a deletion that started earlier;
                                // we count such reads (with a longer deletion spanning over a deletion at the previous base we are
                                // about to report) only if includeReadsWithDeletionAtLoci is true.
                                size++;
                                indelPile.add ( new ExtendedEventPileupElement(state.getRead(),
                                                                       state.getReadOffset()-1,
                                                                       -1) // length=-1 --> noevent
                                        );
                            }
                        }
                    }
                    if ( state.getRead().getMappingQuality() == 0 ) {
                        nMQ0Reads++;
                    }
                }
                hasExtendedEvents = false; // we are done with extended events prior to current ref base
                SAMRecordState our1stState = readStates.getFirst();
                // get current location on the reference and decrement it by 1: the indels we just stepped over
                // are associated with the *previous* reference base
                GenomeLoc loc = GenomeLocParser.incPos(our1stState.getLocation(),-1);
//                System.out.println("Indel(s) at "+loc);
//               for ( ExtendedEventPileupElement pe : indelPile ) { if ( pe.isIndel() ) System.out.println("  "+pe.toString()); }
                nextAlignmentContext = new AlignmentContext(loc, new ReadBackedExtendedEventPileupImpl(loc, indelPile, size, maxDeletionLength, nInsertions, nDeletions, nMQ0Reads));
            }  else {
                ArrayList<PileupElement> pile = new ArrayList<PileupElement>(readStates.size());

                // todo -- performance problem -- should be lazy, really
                for ( SAMRecordState state : readStates ) {
                    if ( state.getCurrentCigarOperator() != CigarOperator.D && state.getCurrentCigarOperator() != CigarOperator.N ) {
                        if ( filterRead(state.getRead(), getLocation().getStart(), filters ) ) {
                            discarded_bases++;
                            //printStatus("Adaptor bases", discarded_adaptor_bases);
                            continue;
                        } else {
                            observed_bases++;
                            pile.add(new PileupElement(state.getRead(), state.getReadOffset()));
                            size++;
                        }
                    } else if ( readInfo.includeReadsWithDeletionAtLoci() && state.getCurrentCigarOperator() != CigarOperator.N ) {
                        size++;
                        pile.add(new PileupElement(state.getRead(), -1));
                        nDeletions++;
                    }

                    // todo -- this looks like a bug w.r.t. including reads with deletion at loci -- MAD 05/27/10
                    if ( state.getRead().getMappingQuality() == 0 ) {
                        nMQ0Reads++;
                    }
                }

                GenomeLoc loc = getLocation();
                updateReadStates(); // critical - must be called after we get the current state offsets and location
                // if we got reads with non-D/N over the current position, we are done
                if ( pile.size() != 0 ) nextAlignmentContext = new AlignmentContext(loc, new ReadBackedPileupImpl(loc, pile, size, nDeletions, nMQ0Reads));
            }
        }
    }

    private static boolean filterRead(SAMRecord rec, long pos, List<LocusIteratorFilter> filters) {
        for ( LocusIteratorFilter filter : filters ) {
            if ( filter.filterOut(rec, pos) ) {
                return true;
            }
        }

        return false;
    }

    private void printStatus(final String title, long n) {
        if ( n % 10000 == 0 )
            System.out.printf("%s %d / %d = %.2f%n", title, n, observed_bases, 100.0 * n / (observed_bases + 1));
    }



    private void collectPendingReads(int maxReads) {
        //if (DEBUG) {
        //    logger.debug(String.format("entering collectPendingReads..., hasNext=%b", it.hasNext()));
        //    printState();
        //}
        GenomeLoc location = null;

        // the farthest right a read extends
        Integer rightMostEnd = -1;

        int curSize = readStates.size();    // simple performance improvement -- avoids unnecessary size() operation
        while (it.hasNext()) {
            SAMRecord read = it.next();
            if (readIsPastCurrentPosition(read)) {
                // We've collected up all the reads that span this region
                it.pushback(read);
                break;
            } else {
                if (curSize < maxReads) {
                    SAMRecordState state = new SAMRecordState(read, readInfo.generateExtendedEvents());
                    state.stepForwardOnGenome();
                    readStates.add(state);
                    curSize++;
                    if (state.hadIndel()) hasExtendedEvents = true;
                    //if (DEBUG) logger.debug(String.format("  ... added read %s", read.getReadName()));
                } else {
                    if (location == null)
                        location = GenomeLocParser.createGenomeLoc(read);                    
                    rightMostEnd = (read.getAlignmentEnd() > rightMostEnd) ? read.getAlignmentEnd() : rightMostEnd;
                }
            }

        }

        if (location != null)
            overflowTracker.exceeded(GenomeLocParser.createGenomeLoc(location.getContigIndex(),location.getStart(),rightMostEnd),
                                     curSize);
    }

    // fast testing of position
    private boolean readIsPastCurrentPosition(SAMRecord read) {
        if ( readStates.isEmpty() )
            return false;
        else {
            SAMRecordState state = readStates.getFirst();
            SAMRecord ourRead = state.getRead();
//            int offset = 0;
//            final CigarElement ce = read.getCigar().getCigarElement(0);
            // if read starts with an insertion, we want to get it in at the moment we are standing on the
            // reference base the insertion is associated with, not when we reach "alignment start", which is
            // first base *after* the insertion
//            if ( ce.getOperator() == CigarOperator.I ) offset = ce.getLength();
//            return read.getReferenceIndex() > ourRead.getReferenceIndex() || read.getAlignmentStart() - offset > state.getGenomePosition();
            return read.getReferenceIndex() > ourRead.getReferenceIndex() || read.getAlignmentStart() > state.getGenomePosition();
        }
    }


    private void updateReadStates() {
        Iterator<SAMRecordState> it = readStates.iterator();
        while ( it.hasNext() ) {
            SAMRecordState state = it.next();
            CigarOperator op = state.stepForwardOnGenome();
            if ( state.hadIndel() && readInfo.generateExtendedEvents() ) hasExtendedEvents = true;
            else {
                // we discard the read only when we are past its end AND indel at the end of the read (if any) was
                // already processed. Keeping the read state that retunred null upon stepForwardOnGenome() is safe
                // as the next call to stepForwardOnGenome() will return null again AND will clear hadIndel() flag.
                if ( op == null ) { // we've stepped off the end of the object
                    //if (DEBUG) logger.debug(String.format("   removing read %s at %d", state.getRead().getReadName(), state.getRead().getAlignmentStart()));
                    it.remove();
                }
            }
        }
    }

    public void remove() {
        throw new UnsupportedOperationException("Can not remove records from a SAM file via an iterator!");
    }

    /**
     * a method for setting the overflow tracker, for dependency injection
     * @param tracker
     */
    protected void setLocusOverflowTracker(LocusOverflowTracker tracker) {
        this.overflowTracker = tracker;
    }

    /**
     * a method for getting the overflow tracker
     * @return the overflow tracker, null if none exists
     */
    public LocusOverflowTracker getLocusOverflowTracker() {
        return this.overflowTracker;
    }
}

