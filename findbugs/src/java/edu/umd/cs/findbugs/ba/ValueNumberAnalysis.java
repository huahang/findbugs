/*
 * Bytecode Analysis Framework
 * Copyright (C) 2003, University of Maryland
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package edu.umd.cs.daveho.ba;

import java.util.*;

// We require BCEL 5.0 or later.
import org.apache.bcel.*;
import org.apache.bcel.classfile.*;
import org.apache.bcel.generic.*;

/**
 * A dataflow analysis to track the production and flow of values in the Java
 * stack frame.  See the {@link ValueNumber ValueNumber} class for an explanation
 * of what the value numbers mean, and when they can be compared.
 *
 * <p> This class is still experimental.
 *
 * <p> TODO: we will need redundant load elimination in order to
 * successfully capture the programmer's intent in a lot of cases.
 * For example:
 * <pre>
 *    synchronized (foo) {
 *       foo.blat();
 *    }
 * </pre>
 * Assuming foo is an instance field, there will be two GETFIELD instructions;
 * one for the MONITORENTER, and one for the call to blat().  At runtime,
 * it is more or less certain that the JIT will reuse the value of the
 * first GETFIELD for the call to blat(), and this is almost certainly what
 * the programmer intended to happen.
 *
 * @see ValueNumber
 * @see DominatorsAnalysis
 * @author David Hovemeyer
 */
public class ValueNumberAnalysis extends FrameDataflowAnalysis<ValueNumber, ValueNumberFrame> {

	private static final boolean DEBUG = Boolean.getBoolean("vna.debug");

	private MethodGen methodGen;
	private ValueNumberFactory factory;
	private ValueNumberCache cache;
	private ValueNumberFrameModelingVisitor visitor;
	private ValueNumber[] entryLocalValueList;
	private IdentityHashMap<BasicBlock, ValueNumber> exceptionHandlerValueNumberMap;
	private ValueNumber thisValue;
	private HashMap<Location, ValueNumberFrame> factAtLocationMap;
	private HashMap<Location, ValueNumberFrame> factAfterLocationMap;

	public ValueNumberAnalysis(MethodGen methodGen, DepthFirstSearch dfs,
		RepositoryLookupFailureCallback lookupFailureCallback) {

		super(dfs);
		this.methodGen = methodGen;
		this.factory = new ValueNumberFactory();
		this.cache = new ValueNumberCache();
		this.visitor = new ValueNumberFrameModelingVisitor(methodGen.getConstantPool(), factory, cache,
			lookupFailureCallback);

		int numLocals = methodGen.getMaxLocals();
		this.entryLocalValueList = new ValueNumber[numLocals];
		for (int i = 0; i < numLocals; ++i)
			this.entryLocalValueList[i] = factory.createFreshValue();

		this.exceptionHandlerValueNumberMap = new IdentityHashMap<BasicBlock, ValueNumber>();

		// For non-static methods, keep track of which value represents the
		// "this" reference
		if (!methodGen.isStatic())
			this.thisValue = entryLocalValueList[0];

		this.factAtLocationMap = new HashMap<Location, ValueNumberFrame>();
		this.factAfterLocationMap = new HashMap<Location, ValueNumberFrame>();
	}

	public int getNumValuesAllocated() {
		return factory.getNumValuesAllocated();
	}

	public boolean isThisValue(ValueNumber value) {
		return thisValue != null && thisValue.getNumber() == value.getNumber();
	}

	public ValueNumber getThisValue() {
		return thisValue;
	}

	public ValueNumber getEntryValue(int local) {
		return entryLocalValueList[local];
	}

	public ValueNumberFrame createFact() {
		return new ValueNumberFrame(methodGen.getMaxLocals(), factory);
	}

	public void initEntryFact(ValueNumberFrame result) {
		// Change the frame from TOP to something valid.
		result.setValid();

		// At entry to the method, each local has (as far as we know) a unique value.
		int numSlots = result.getNumSlots();
		for (int i = 0; i < numSlots; ++i)
			result.setValue(i, entryLocalValueList[i]);
	}

	public void transferInstruction(InstructionHandle handle, BasicBlock basicBlock, ValueNumberFrame fact)
		throws DataflowAnalysisException {

		Location location = new Location(handle, basicBlock);

		ValueNumberFrame atLocation = getFactAtLocation(location);
		copy(fact, atLocation);

		visitor.setFrame(fact);
		visitor.setHandle(handle);
		Instruction ins = handle.getInstruction();
		ins.accept(visitor);

		ValueNumberFrame afterLocation = getFactAfterLocation(location);
		copy(fact, afterLocation);
	}

	public void meetInto(ValueNumberFrame fact, Edge edge, ValueNumberFrame result) throws DataflowAnalysisException {
		if (edge.getTarget().isExceptionHandler() && fact.isValid()) {
			// Special case: when merging predecessor facts for entry to
			// an exception handler, we clear the stack and push a
			// single entry for the exception object.  That way, the locals
			// can still be merged.

			// Get the value number for the exception
			BasicBlock handlerBlock = edge.getTarget();
			ValueNumber exceptionValueNumber = getExceptionValueNumber(handlerBlock);

			// Set up the stack frame
			ValueNumberFrame tmpFact = createFact();
			tmpFact.copyFrom(fact);
			tmpFact.clearStack();
			tmpFact.pushValue(exceptionValueNumber);
			fact = tmpFact;
		}

		result.mergeWith(fact);
	}

	public ValueNumberFrame getFactAtLocation(Location location) {
		ValueNumberFrame fact = factAtLocationMap.get(location);
		if (fact == null) {
			fact = createFact();
			factAtLocationMap.put(location, fact);
		}
		return fact;
	}

	public ValueNumberFrame getFactAfterLocation(Location location) {
		ValueNumberFrame fact = factAfterLocationMap.get(location);
		if (fact == null) {
			fact = createFact();
			factAfterLocationMap.put(location, fact);
		}
		return fact;
	}

	/**
	 * Get an Iterator over all dataflow facts that we've recorded for
	 * the Locations in the CFG.  Note that this does not include
	 * result facts (since there are no Locations corresponding to
	 * the end of basic blocks).
	 */
	public Iterator<ValueNumberFrame> factIterator() {
		return factAtLocationMap.values().iterator();
	}

	// These fields are used by the compactValueNumbers() method.
	private static class ValueCompacter {
		public final BitSet valuesUsed;
		public int numValuesUsed;
		public final int[] discovered;

		public ValueCompacter(int origNumValuesAllocated) {
			valuesUsed = new BitSet();
			numValuesUsed = 0;

			// The "discovered" array tells us the mapping of old value numbers
			// to new (which are based on order of discovery).  Negative values
			// specify value numbers which are not actually used (and thus can
			// be purged.)
			discovered = new int[origNumValuesAllocated];
			for (int i = 0; i < discovered.length; ++i)
				discovered[i] = -1;
		}

		public boolean isUsed(int number) { return valuesUsed.get(number); }
		public void setUsed(int number) { valuesUsed.set(number, true); }
		public int allocateValue() { return numValuesUsed++; }
	}

	/**
	 * Compact the value numbers assigned.
	 * This should be done only after the dataflow algorithm has executed.
	 * This works by modifying the actual ValueNumber objects assigned.
	 * After this method is called, the getNumValuesAllocated() method
	 * of this object will return a value less than or equal to the value
	 * it would have returned before the call to this method.
	 *
	 * <p> <em>This method should be called at most once</em>.
	 *
	 * @param dataflow the Dataflow object which executed this analysis
	 *  (and has all of the block result values)
	 */
	public void compactValueNumbers(Dataflow<ValueNumberFrame, ValueNumberAnalysis> dataflow) {
		ValueCompacter compacter = new ValueCompacter(factory.getNumValuesAllocated());

		// We can get all extant Frames by looking at the values in
		// the location to value map, and also the block result values.
		for (Iterator<ValueNumberFrame> i = factIterator(); i.hasNext(); ) {
			ValueNumberFrame frame = i.next();
			markFrameValues(frame, compacter);
		}
		for (Iterator<ValueNumberFrame> i = resultFactIterator(); i.hasNext(); ) {
			ValueNumberFrame frame = i.next();
			markFrameValues(frame, compacter);
		}

		int before = factory.getNumValuesAllocated();

		// Now the factory can modify the ValueNumbers.
		factory.compact(compacter.discovered, compacter.numValuesUsed);

		int after = factory.getNumValuesAllocated();

		if (DEBUG && after < before && before > 0) System.out.println("Value compaction: " + after + "/" + before + " (" +
			((after * 100) / before) + "%)");

	}

	/**
	 * Mark value numbers in a value number frame for compaction.
	 */
	private static void markFrameValues(ValueNumberFrame frame, ValueCompacter compacter) {
		// We don't need to do anything for top and bottom frames.
		if (!frame.isValid())
			return;

		for (int j = 0; j < frame.getNumSlots(); ++j) {
			ValueNumber value = frame.getValue(j);
			int number = value.getNumber();

			if (!compacter.isUsed(number)) {
				compacter.discovered[number] = compacter.allocateValue();
				compacter.setUsed(number);
			}
		}
	}

	/**
	 * Test driver.
	 */
	public static void main(String[] argv) {
		try {
			if (argv.length != 1) {
				System.out.println("Usage: edu.umd.cs.daveho.ba.ValueNumberAnalysis <filename>");
				System.exit(1);
			}

			DataflowTestDriver<ValueNumberFrame, ValueNumberAnalysis> driver =
				new DataflowTestDriver<ValueNumberFrame, ValueNumberAnalysis>() {
				public Dataflow<ValueNumberFrame, ValueNumberAnalysis> createDataflow(ClassContext classContext, Method method)
					throws CFGBuilderException, DataflowAnalysisException {
					return classContext.getValueNumberDataflow(method);
				}
			};

			driver.execute(argv[0]);

		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private ValueNumber getExceptionValueNumber(BasicBlock handlerBlock) {
		ValueNumber valueNumber = exceptionHandlerValueNumberMap.get(handlerBlock);
		if (valueNumber == null) {
			valueNumber = factory.createFreshValue();
			exceptionHandlerValueNumberMap.put(handlerBlock, valueNumber);
		}
		return valueNumber;
	}
}

// vim:ts=4
