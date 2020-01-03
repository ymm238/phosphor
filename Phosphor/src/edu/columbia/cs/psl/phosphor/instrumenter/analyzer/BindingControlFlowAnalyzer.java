package edu.columbia.cs.psl.phosphor.instrumenter.analyzer;

import edu.columbia.cs.psl.phosphor.instrumenter.analyzer.BranchStartInfo.PropagatingBranchEdge;
import edu.columbia.cs.psl.phosphor.instrumenter.analyzer.LoopLevel.VariantLoopLevel;
import edu.columbia.cs.psl.phosphor.instrumenter.analyzer.graph.*;
import edu.columbia.cs.psl.phosphor.instrumenter.analyzer.graph.FlowGraph.NaturalLoop;
import edu.columbia.cs.psl.phosphor.instrumenter.analyzer.trace.LoopAwareConstancyInfo;
import edu.columbia.cs.psl.phosphor.instrumenter.analyzer.trace.TracingInterpreter;
import edu.columbia.cs.psl.phosphor.struct.SinglyLinkedList;
import edu.columbia.cs.psl.phosphor.struct.harmony.util.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.AnalyzerException;

import java.util.Iterator;

import static edu.columbia.cs.psl.phosphor.instrumenter.analyzer.LoopLevel.ConstantLoopLevel.CONSTANT_LOOP_LEVEL;
import static org.objectweb.asm.Opcodes.*;

/**
 * Identifies and marks the scope of "binding" branch edges and indicates whether each marked edge is "revisable". Does
 * not consider edges due to exceptional control flow.
 *
 * <p>For a control flow graph G = (V, E):
 *
 * <p>An edge (u, v) in E is said to be a branch edge if and only if there exits some edge (u, w) in E such that v != w.
 * Branch edges are the result of conditional jump instructions (i.e., IF_ACMP<cond>, IF_ICMP<cond>, IF<cond>,
 * TABLESWITCH, LOOKUPSWITCH, IFNULL, and IFNONNULL).
 *
 * <p>A branch edge (u, v) is said to be binding if and only if one of the following conditions is true:
 * <ul>
 *     <li>The basic block u ends with an IFEQ or IFNE instruction.</li>
 *     <li>The basic block u ends with an IF_ICMPEQ or IF_ACMEQ instruction that has a jump target t and t = v</li>
 *     <li>The basic block u ends with an IF_ICMPNE or IF_ACMPNE instruction that has a jump target t and t != v</li>
 *     <li>The basic block u ends with a TABLESWITCH or LOOKUPSWITCH instruction that has a set of jump targets T and
 *     v is an element of T.</li>
 * </ul>
 *
 * <p>The scope of a binding branch edge is the range of instructions that are considered to have a binding control
 * dependency on the edge. The scope of a binding branch edge (u, v) starts after the end of the basic block u and
 * before the start of the basic block v. The scope of a binding branch edge (u, v) ends before each basic block w
 * in V such that the exists a path from the distinguished start vertex of the control flow graph to w that does not
 * contain the edge (u, v).
 *
 * <p> A branch edge (u, v) is said to be revisable if and only if all of the following conditions are true:
 * <ul>
 *     <li>The predicate of the conditional jump instruction that ends basic block u is not constant</li>
 *     <li>There exits some edge (u, w) in E such that v != w and there exists a path from w to v</li>
 * </ul>
 *
 * <p> An instruction is said to be revision-excluded if and only if one of the following conditions is true:
 * <ul>
 *     <li>It is an ICONST_M1, ICONST_0, ICONST_1, ICONST_2, ICONST_3, ICONST_4, ICONST_5, LCONST_0, LCONST_1, FCONST_0,
 *     FCONST_1, FCONST_2, DCONST_0, DCONST_1, BIPUSH, SIPUSH, or LDC instruction</li>
 *     <li>It is an IINC instruction</li>
 *     <li>It is an ISTORE, LSTORE, FSTORE, DSTORE, or ASTORE instruction that stores a value v into the local variable
 *     x where v can be expressed as an arithmetic expression where each operand is either a constant value or a single
 *     definition of x.
 * </ul>
 * A revision-excluded instruction is considered to be outside of the scope of all revisable branch edges.
 */
public class BindingControlFlowAnalyzer {

    private BindingControlFlowAnalyzer() {
        // Prevents this class from being instantiated
    }

    /**
     * @param methodNode the method to be analyzed and possibly modified
     * @return the number of unique branch ids used in added instructions
     */
    public static int analyzeAndModify(String owner, MethodNode methodNode) throws AnalyzerException {
        InsnList instructions = methodNode.instructions;
        if(instructions.size() == 0) {
            return 0;
        }
        BindingControlFlowGraphCreator creator = new BindingControlFlowGraphCreator();
        FlowGraph<BasicBlock> controlFlowGraph = creator.createControlFlowGraph(methodNode);
        Map<AbstractInsnNode, Set<NaturalLoop<BasicBlock>>> containingLoopMap = getContainingLoops(instructions, controlFlowGraph);
        TracingInterpreter interpreter = new TracingInterpreter(owner, methodNode, containingLoopMap, controlFlowGraph);
        Set<AbstractInsnNode> exclusionCandidates = interpreter.identifyRevisionExcludedInstructions();
        Collection<BindingBranchEdge> edges = filterEdges(creator.bindingBranchEdges, controlFlowGraph);
        Map<BasicBlock, Set<BindingBranchEdge>> groupedStartBlocks = groupEdgesBySource(edges);
        int nextBranchIDAssigned = 0;
        for(BasicBlock source : groupedStartBlocks.keySet()) {
            // Assign two unique identifiers to each source
            int branchID = nextBranchIDAssigned++;
            int revisableBranchID = nextBranchIDAssigned++;
            int containingLoops = containingLoopMap.get(source.getFirstInsn()).size();
            BranchStartInfo startInfo = new BranchStartInfo.MultiIDBranch(Arrays.asList(
                    new PropagatingBranchEdge(CONSTANT_LOOP_LEVEL, branchID, null),
                    new PropagatingBranchEdge(new VariantLoopLevel(containingLoops), revisableBranchID, null)
            ));
            instructions.insertBefore(source.getLastInsn(), new LdcInsnNode(startInfo));
            Set<BasicBlock> successors = new HashSet<>(controlFlowGraph.getSuccessors(source));
            // Add LoopAwarePopInfo LDC instructions
            for(BindingBranchEdge edge : groupedStartBlocks.get(source)) {
                successors.remove(edge);
                AbstractInsnNode nextInsn = findNextPrecedableInstruction(edge.target.getFirstInsn());
                if(edge.isRevisable(controlFlowGraph, interpreter)) {
                    markBranchEnds(methodNode.instructions, edge, revisableBranchID, controlFlowGraph);
                    instructions.insertBefore(nextInsn, new LdcInsnNode(new LoopAwarePopInfo(branchID)));
                } else {
                    markBranchEnds(methodNode.instructions, edge, branchID, controlFlowGraph);
                    instructions.insertBefore(nextInsn, new LdcInsnNode(new LoopAwarePopInfo(revisableBranchID)));
                }
            }
            for(BasicBlock successor : successors) {
                while(successor instanceof BindingBranchEdge) {
                    successor = ((BindingBranchEdge) successor).target;
                }
                if(!(successor instanceof DummyBasicBlock)) {
                    AbstractInsnNode nextInsn = findNextPrecedableInstruction(successor.getFirstInsn());
                    instructions.insertBefore(nextInsn, new LdcInsnNode(new LoopAwarePopInfo(branchID)));
                    instructions.insertBefore(nextInsn, new LdcInsnNode(new LoopAwarePopInfo(revisableBranchID)));
                }
            }
        }
        // Mark all revision-excluded instructions
        for(AbstractInsnNode exclusionCandidate : exclusionCandidates) {
            instructions.insertBefore(exclusionCandidate, new LdcInsnNode(new CopyTagInfo(CONSTANT_LOOP_LEVEL)));
        }
        markLoopExits(instructions, controlFlowGraph, containingLoopMap);
        // Add constancy information for method calls
        addConstancyInfoNodes(instructions, interpreter);
        return nextBranchIDAssigned;
    }

    /**
     * Adds LoopAwareConstancyInfo instruction nodes to the specified list before each MethodInsnNode and
     * each InvokeDynamicInsnNode.
     *
     * @param instructions the instructions whose method constancy information is to be annotated
     * @param interpreter  interpreter used to calculate the constancy information for instructions
     */
    private static void addConstancyInfoNodes(InsnList instructions, TracingInterpreter interpreter) {
        SinglyLinkedList<AbstractInsnNode> methodCalls = new SinglyLinkedList<>();
        Iterator<AbstractInsnNode> itr = instructions.iterator();
        while(itr.hasNext()) {
            AbstractInsnNode insn = itr.next();
            if(insn instanceof MethodInsnNode || insn instanceof InvokeDynamicInsnNode) {
                methodCalls.addLast(insn);
            }
        }
        for(AbstractInsnNode insn : methodCalls) {
            LoopAwareConstancyInfo constancyInfo = interpreter.generateMethodConstancyInfo(insn);
            instructions.insertBefore(insn, new LdcInsnNode(constancyInfo));
        }
    }

    /**
     * Adds ExitLoopLevelInfo instruction nodes to the specified list at the beginning of each basic block v
     * for each edge (u,v) in the control flow graph such that u is containing in some loop l and v is not contained
     * in l.
     *
     * @param instructions      the instructions whose loop exits are to be marked
     * @param controlFlowGraph  a control flow graph representing the instructions in the specified list
     * @param containingLoopMap a mapping between each instruction in the specified list and the natural loops that contain it
     */
    private static void markLoopExits(InsnList instructions, FlowGraph<BasicBlock> controlFlowGraph, Map<AbstractInsnNode, Set<NaturalLoop<BasicBlock>>> containingLoopMap) {
        Set<NaturalLoop<BasicBlock>> loops = controlFlowGraph.getNaturalLoops();
        for(NaturalLoop<BasicBlock> loop : loops) {
            BasicBlock header = loop.getHeader();
            if(header instanceof SimpleBasicBlock) {
                Set<SimpleBasicBlock> exits = new HashSet<>();
                for(BasicBlock vertex : loop.getVertices()) {
                    for(BasicBlock target : controlFlowGraph.getSuccessors(vertex)) {
                        if(target instanceof SimpleBasicBlock && !loop.contains(target)) {
                            exits.add((SimpleBasicBlock) target);
                        }
                    }
                }
                int numContainingLoops = containingLoopMap.get(header.getFirstInsn()).size();
                ExitLoopLevelInfo exitLoopLevelInfo = new ExitLoopLevelInfo(numContainingLoops);
                for(BasicBlock exit : exits) {
                    AbstractInsnNode nextInsn = findNextPrecedableInstruction(exit.getFirstInsn());
                    instructions.insertBefore(nextInsn, new LdcInsnNode(exitLoopLevelInfo));
                }
            }
        }
    }

    /**
     * @param instructions     the instructions whose containing loops are to be calculated
     * @param controlFlowGraph a control flow graph representing the instructions in the specified list
     * @return a mapping between each instruction in the specified list and the natural loops that contain it
     */
    public static Map<AbstractInsnNode, Set<NaturalLoop<BasicBlock>>> getContainingLoops(InsnList instructions, FlowGraph<BasicBlock> controlFlowGraph) {
        Set<NaturalLoop<BasicBlock>> loops = controlFlowGraph.getNaturalLoops();
        Map<AbstractInsnNode, Set<NaturalLoop<BasicBlock>>> loopMap = new HashMap<>();
        Iterator<AbstractInsnNode> itr = instructions.iterator();
        while(itr.hasNext()) {
            loopMap.put(itr.next(), new HashSet<>());
        }
        for(NaturalLoop<BasicBlock> loop : loops) {
            for(BasicBlock basicBlock : loop.getVertices()) {
                if(basicBlock instanceof SimpleBasicBlock) {
                    AbstractInsnNode start = basicBlock.getFirstInsn();
                    while(start != null) {
                        loopMap.get(start).add(loop);
                        if(start == basicBlock.getLastInsn()) {
                            break;
                        }
                        start = start.getNext();
                    }
                }
            }
        }
        return loopMap;
    }

    /**
     * Marks the ends of the scope for the specified edge by inserting a LoopWarePopInfo instruction node.
     *
     * @param instructions     the instructions of a method
     * @param edge             a vertex that represents a binding branch edge in the specified method
     * @param id               the unique identifier assigned to the specified branch edge
     * @param controlFlowGraph the control flow graph of the method
     */
    private static void markBranchEnds(InsnList instructions, BindingBranchEdge edge, int id, FlowGraph<BasicBlock> controlFlowGraph) {
        Set<BasicBlock> scopeEnds = edge.getScopeEnds(controlFlowGraph);
        for(BasicBlock scopeEnd : scopeEnds) {
            AbstractInsnNode insn = findNextPrecedableInstruction(scopeEnd.getFirstInsn());
            instructions.insertBefore(insn, new LdcInsnNode(new LoopAwarePopInfo(id)));
        }
    }

    private static AbstractInsnNode findNextPrecedableInstruction(AbstractInsnNode insn) {
        while(insn.getType() == AbstractInsnNode.FRAME || insn.getType() == AbstractInsnNode.LINE
                || insn.getType() == AbstractInsnNode.LABEL || insn.getOpcode() > 200) {
            insn = insn.getNext();
        }
        return insn;
    }

    /**
     * @param edges a set containing the edges to be grouped
     * @return a mapping between basic blocks and non-empty sets containing all of the edges for which the basic
     * block is a source
     */
    private static Map<BasicBlock, Set<BindingBranchEdge>> groupEdgesBySource(Collection<BindingBranchEdge> edges) {
        Map<BasicBlock, Set<BindingBranchEdge>> groupedEdges = new HashMap<>();
        for(BindingBranchEdge edge : edges) {
            if(!groupedEdges.containsKey(edge.source)) {
                groupedEdges.put(edge.source, new HashSet<>());
            }
            groupedEdges.get(edge.source).add(edge);
        }
        return groupedEdges;
    }

    /**
     * @param edges            a set containing the edges to be filtered
     * @param controlFlowGraph the control flow graph containing the specified edges
     * @return a subset of the specified of edges that contains only edges that have a non-empty scope
     * in the specified graph
     */
    private static Set<BindingBranchEdge> filterEdges(Set<BindingBranchEdge> edges, FlowGraph<BasicBlock> controlFlowGraph) {
        Set<BindingBranchEdge> result = new HashSet<>();
        for(BindingBranchEdge edge : edges) {
            if(edge.hasNonEmptyScope(controlFlowGraph)) {
                result.add(edge);
            }
        }
        return result;
    }

    /**
     * Builds a control flow graph where each binding branch edge (u, v) is replaced with a vertex w, an edge (u, w)
     * and an edge (w, v).
     */
    private static class BindingControlFlowGraphCreator extends BaseControlFlowGraphCreator {

        /**
         * A set containing the vertices used to represent binding branch edges in the control flow graph
         */
        Set<BindingBranchEdge> bindingBranchEdges = new HashSet<>();

        @Override
        protected void addBranchTakenEdge(BasicBlock source, BasicBlock target) {
            switch(source.getLastInsn().getOpcode()) {
                case IFEQ:
                case IFNE:
                case IF_ICMPEQ:
                case IF_ACMPEQ:
                    addBindingBranchEdge(source, target);
                    break;
                default:
                    super.addBranchTakenEdge(source, target);
            }
        }

        @Override
        protected void addBranchNotTakenEdge(BasicBlock source, BasicBlock target) {
            switch(source.getLastInsn().getOpcode()) {
                case IFEQ:
                case IFNE:
                case IF_ICMPNE:
                case IF_ACMPNE:
                    addBindingBranchEdge(source, target);
                    break;
                default:
                    super.addBranchNotTakenEdge(source, target);
            }
        }

        @Override
        protected void addNonDefaultCaseSwitchEdge(BasicBlock source, BasicBlock target) {
            addBindingBranchEdge(source, target);
        }

        @Override
        protected void addDefaultCaseSwitchEdge(BasicBlock source, BasicBlock target) {
            addBindingBranchEdge(source, target);
        }

        /**
         * If the specified (source, target) edge is not already represented in the graph adds a new vertex w to the graph
         * and the edges (source, w) and (w, target).
         *
         * @param source the source vertex of the binding branch edge to be represented in the graph
         * @param target the target vertex of the binding branch edge to be represented in the graph
         */
        private void addBindingBranchEdge(BasicBlock source, BasicBlock target) {
            BindingBranchEdge bindingBranchEdge = new BindingBranchEdge(source, target);
            if(bindingBranchEdges.add(bindingBranchEdge)) {
                builder.addVertex(bindingBranchEdge);
                builder.addEdge(source, bindingBranchEdge);
                builder.addEdge(bindingBranchEdge, target);
            }
        }
    }

    /**
     * A BindingBranchEdge w is used in conjunction with the edges (u, w) and (w, v) to represent some edge
     * (u, v).
     */
    private static class BindingBranchEdge extends DummyBasicBlock {

        /**
         * The source vertex of this edge, the last instruction in the source block should be a conditional jump
         * instruction
         */
        private final BasicBlock source;

        /**
         * The target vertex of this edge
         */
        private final BasicBlock target;

        BindingBranchEdge(BasicBlock source, BasicBlock target) {
            this.source = source;
            this.target = target;
        }

        /**
         * @param controlFlowGraph the control flow graph containing this edge
         * @return true if there is at least one basic block in the scope of this edge
         * @throws IllegalArgumentException if the specified control flow graph does not contain this edge
         */
        boolean hasNonEmptyScope(FlowGraph<BasicBlock> controlFlowGraph) {
            if(!controlFlowGraph.getVertices().contains(this)) {
                throw new IllegalArgumentException("Supplied control flow graph does contain this edge");
            }
            if(!controlFlowGraph.getDominanceFrontiers().containsKey(this)) {
                // This edge is unreachable in the specified graph
                return false;
            }
            return !controlFlowGraph.getDominanceFrontiers().get(this).contains(this.target);
        }

        /**
         * @param controlFlowGraph a control flow graph
         * @return the set of non-dummy basic blocks before which the scope of this edge ends or the empty set
         * if this edge is unreachable in the specified graph
         * @throws IllegalArgumentException if the specified control flow graph does not contain this edge or this edge
         *                                  is unreachable in the specified control flow graph
         */
        Set<BasicBlock> getScopeEnds(FlowGraph<BasicBlock> controlFlowGraph) {
            if(!controlFlowGraph.getVertices().contains(this)) {
                throw new IllegalArgumentException("Supplied control flow graph does contain this edge");
            }
            if(!controlFlowGraph.getDominanceFrontiers().containsKey(this)) {
                throw new IllegalArgumentException("Edge is unreachable in supplied control flow graph");
            }
            Set<BasicBlock> scopeEnds = new HashSet<>();
            for(BasicBlock block : controlFlowGraph.getDominanceFrontiers().get(this)) {
                if(!(block instanceof DummyBasicBlock)) {
                    scopeEnds.add(block);
                }
            }
            return scopeEnds;
        }

        /**
         * @param controlFlowGraph a control flow graph
         * @param interpreter      interpreter used to identify constant predicate or null if constant predicates need not
         *                         be identified
         * @return true is this edge is revisable
         * @throws IllegalArgumentException if the specified control flow graph does not contain this edge
         */
        boolean isRevisable(FlowGraph<BasicBlock> controlFlowGraph, TracingInterpreter interpreter) {
            if(!controlFlowGraph.getVertices().contains(this)) {
                throw new IllegalArgumentException("Supplied control flow graph does contain this edge");
            }
            if(interpreter != null && interpreter.hasConstantSources(source.getLastInsn())) {
                return false;
            }
            for(BasicBlock successor : controlFlowGraph.getSuccessors(source)) {
                if(!successor.equals(this) && controlFlowGraph.containsPath(successor, this)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean equals(Object o) {
            if(this == o) {
                return true;
            } else if(!(o instanceof BindingBranchEdge)) {
                return false;
            }
            BindingBranchEdge edge = (BindingBranchEdge) o;
            return source.equals(edge.source) && target.equals(edge.target);
        }

        @Override
        public int hashCode() {
            int result = source.hashCode();
            result = 31 * result + target.hashCode();
            return result;
        }

        @Override
        public String toString() {
            return String.format("<DirectedEdge: %s -> %s>", source, target);
        }
    }
}