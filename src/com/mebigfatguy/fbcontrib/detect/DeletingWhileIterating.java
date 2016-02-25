/*
 * fb-contrib - Auxiliary detectors for Java programs
 * Copyright (C) 2005-2016 Dave Brosius
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
package com.mebigfatguy.fbcontrib.detect;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.bcel.Constants;
import org.apache.bcel.Repository;
import org.apache.bcel.classfile.Code;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.LocalVariable;
import org.apache.bcel.classfile.LocalVariableTable;
import org.apache.bcel.generic.Type;

import com.mebigfatguy.fbcontrib.utils.BugType;
import com.mebigfatguy.fbcontrib.utils.CodeByteUtils;
import com.mebigfatguy.fbcontrib.utils.OpcodeUtils;
import com.mebigfatguy.fbcontrib.utils.QMethod;
import com.mebigfatguy.fbcontrib.utils.RegisterUtils;
import com.mebigfatguy.fbcontrib.utils.TernaryPatcher;
import com.mebigfatguy.fbcontrib.utils.ToString;
import com.mebigfatguy.fbcontrib.utils.UnmodifiableSet;
import com.mebigfatguy.fbcontrib.utils.Values;

import edu.umd.cs.findbugs.BugInstance;
import edu.umd.cs.findbugs.BugReporter;
import edu.umd.cs.findbugs.BytecodeScanningDetector;
import edu.umd.cs.findbugs.FieldAnnotation;
import edu.umd.cs.findbugs.OpcodeStack;
import edu.umd.cs.findbugs.OpcodeStack.CustomUserValue;
import edu.umd.cs.findbugs.ba.ClassContext;
import edu.umd.cs.findbugs.ba.XField;
import edu.umd.cs.findbugs.classfile.FieldDescriptor;

/**
 * looks for deletion of items from a collection using the remove method of the collection at the same time that the collection is being iterated on. If this
 * occurs the iterator will become invalid and throw a ConcurrentModificationException. Instead, the remove should be called on the iterator itself.
 */
@CustomUserValue
public class DeletingWhileIterating extends BytecodeScanningDetector {
    private static JavaClass collectionClass;
    private static JavaClass iteratorClass;
    private static Set<JavaClass> exceptionClasses;

    static {
        try {
            collectionClass = Repository.lookupClass("java/util/Collection");
            iteratorClass = Repository.lookupClass("java/util/Iterator");
        } catch (ClassNotFoundException cnfe) {
            collectionClass = null;
            iteratorClass = null;
        }

        try {
            exceptionClasses = new HashSet<JavaClass>(2);
            exceptionClasses.add(Repository.lookupClass("java/util/concurrent/CopyOnWriteArrayList"));
            exceptionClasses.add(Repository.lookupClass("java/util/concurrent/CopyOnWriteArraySet"));
        } catch (ClassNotFoundException cnfe) {
            // don't have a bugReporter yet, so do nothing
        }
    }

    private static final Set<QMethod> collectionMethods = UnmodifiableSet.create(new QMethod("entrySet", "()Ljava/lang/Set;"),
            new QMethod("keySet", "()Ljava/lang/Set;"), new QMethod("values", "()Ljava/lang/Collection;"));

    private static final Map<QMethod, Integer> modifyingMethods;

    static {
        Map<QMethod, Integer> mm = new HashMap<QMethod, Integer>();
        mm.put(new QMethod("add", "(Ljava/lang/Object;)Z"), Values.ONE);
        mm.put(new QMethod("addAll", "(Ljava/util/Collection;)Z"), Values.ONE);
        mm.put(new QMethod("addAll", "(ILjava/util/Collection;)Z"), Values.TWO);
        mm.put(new QMethod("clear", "()V"), Values.ZERO);
        mm.put(new QMethod("remove", "(I)Ljava/lang/Object;"), Values.ONE);
        mm.put(new QMethod("removeAll", "(Ljava/util/Collection;)Z"), Values.ONE);
        mm.put(new QMethod("retainAll", "(Ljava/util/Collection;)Z"), Values.ONE);
        modifyingMethods = Collections.<QMethod, Integer> unmodifiableMap(mm);
    }

    private static final QMethod ITERATOR = new QMethod("iterator", "()Ljava/util/Iterator;");
    private static final QMethod REMOVE = new QMethod("remove", "(Ljava/lang/Object;)Z");
    private static final QMethod HASNEXT = new QMethod("hasNext", "()Z");

    private final BugReporter bugReporter;
    private OpcodeStack stack;
    private List<GroupPair> collectionGroups;
    private Map<Integer, Integer> groupToIterator;
    private Map<Integer, Loop> loops;
    private Map<Integer, BitSet> endOfScopes;

    /**
     * constructs a DWI detector given the reporter to report bugs on
     *
     * @param bugReporter
     *            the sync of bug reports
     */
    public DeletingWhileIterating(BugReporter bugReporter) {
        this.bugReporter = bugReporter;
    }

    /**
     * implements the visitor to setup the opcode stack, collectionGroups, groupToIterator and loops
     *
     * @param classContext
     *            the context object of the currently parsed class
     */
    @Override
    public void visitClassContext(ClassContext classContext) {
        if ((collectionClass == null) || (iteratorClass == null)) {
            return;
        }

        try {
            stack = new OpcodeStack();
            collectionGroups = new ArrayList<GroupPair>();
            groupToIterator = new HashMap<Integer, Integer>();
            loops = new HashMap<Integer, Loop>(10);
            super.visitClassContext(classContext);
        } finally {
            stack = null;
            collectionGroups = null;
            groupToIterator = null;
            loops = null;
            endOfScopes = null;
        }
    }

    /**
     * implements the visitor to reset the stack, collectionGroups, groupToIterator and loops
     *
     * @param obj
     *            the context object of the currently parsed code block
     */
    @Override
    public void visitCode(Code obj) {
        stack.resetForMethodEntry(this);
        collectionGroups.clear();
        groupToIterator.clear();
        loops.clear();
        buildVariableEndScopeMap();

        super.visitCode(obj);
    }

    /**
     * implements the visitor to look for deletes on collections that are being iterated
     *
     * @param seen
     *            the opcode of the currently parsed instruction
     */
    @Override
    public void sawOpcode(int seen) {
        int groupId = -1;

        try {
            stack.precomputation(this);

            if (seen == INVOKEINTERFACE) {
                String className = getClassConstantOperand();
                String methodName = getNameConstantOperand();
                String signature = getSigConstantOperand();
                QMethod methodInfo = new QMethod(methodName, signature);

                if (isCollection(className)) {
                    if (collectionMethods.contains(methodInfo)) {
                        if (stack.getStackDepth() > 0) {
                            OpcodeStack.Item itm = stack.getStackItem(0);
                            groupId = findCollectionGroup(itm, true);

                        }
                    } else if (ITERATOR.equals(methodInfo)) {
                        if (stack.getStackDepth() > 0) {
                            OpcodeStack.Item itm = stack.getStackItem(0);
                            groupId = findCollectionGroup(itm, true);
                        }
                    } else if (REMOVE.equals(methodInfo)) {
                        if (stack.getStackDepth() > 1) {
                            OpcodeStack.Item itm = stack.getStackItem(1);
                            int id = findCollectionGroup(itm, true);
                            if ((id >= 0) && collectionGroups.get(id).isStandardCollection()) {
                                Integer it = groupToIterator.get(Integer.valueOf(id));
                                Loop loop = loops.get(it);
                                if (loop != null) {
                                    int pc = getPC();
                                    if (loop.hasPC(pc)) {
                                        boolean needPop = !"V".equals(Type.getReturnType(signature).getSignature());

                                        if (!breakFollows(loop, needPop) && !returnFollows(needPop)) {
                                            bugReporter.reportBug(new BugInstance(this, BugType.DWI_DELETING_WHILE_ITERATING.name(), NORMAL_PRIORITY)
                                                    .addClass(this).addMethod(this).addSourceLine(this));
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Integer numArgs = modifyingMethods.get(methodInfo);
                        if ((numArgs != null) && (stack.getStackDepth() > numArgs.intValue())) {
                            OpcodeStack.Item itm = stack.getStackItem(numArgs.intValue());
                            int id = findCollectionGroup(itm, true);
                            if (id >= 0) {
                                Integer it = groupToIterator.get(Integer.valueOf(id));
                                if (it != null) {
                                    Loop loop = loops.get(it);
                                    if (loop != null) {
                                        int pc = getPC();
                                        if (loop.hasPC(pc)) {
                                            boolean needPop = !"V".equals(Type.getReturnType(signature).getSignature());
                                            boolean breakFollows = breakFollows(loop, needPop);
                                            boolean returnFollows = breakFollows ? false : returnFollows(needPop);

                                            if (!breakFollows && !returnFollows) {
                                                bugReporter.reportBug(new BugInstance(this, BugType.DWI_MODIFYING_WHILE_ITERATING.name(), NORMAL_PRIORITY)
                                                        .addClass(this).addMethod(this).addSourceLine(this));
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else if ("java/util/Iterator".equals(className) && HASNEXT.equals(methodInfo) && (stack.getStackDepth() > 0)) {
                    OpcodeStack.Item itm = stack.getStackItem(0);
                    Integer id = (Integer) itm.getUserValue();
                    if (id != null) {
                        groupId = id.intValue();
                    }
                }
            } else if ((seen == PUTFIELD) || (seen == PUTSTATIC)) {
                if (stack.getStackDepth() > 1) {
                    OpcodeStack.Item itm = stack.getStackItem(0);

                    Integer id = (Integer) itm.getUserValue();
                    if (id == null) {
                        FieldAnnotation fa = FieldAnnotation
                                .fromFieldDescriptor(new FieldDescriptor(getClassConstantOperand(), getNameConstantOperand(), getSigConstantOperand(), false));
                        itm = new OpcodeStack.Item(itm.getSignature(), fa, stack.getStackItem(1).getRegisterNumber());
                        removeFromCollectionGroup(itm);
                        groupId = findCollectionGroup(itm, true);
                    }
                }
            } else if (OpcodeUtils.isAStore(seen)) {
                if (stack.getStackDepth() > 0) {
                    OpcodeStack.Item itm = stack.getStackItem(0);
                    Integer id = (Integer) itm.getUserValue();
                    if (id != null) {
                        int reg = RegisterUtils.getAStoreReg(this, seen);

                        try {
                            JavaClass cls = itm.getJavaClass();
                            if ((cls != null) && cls.implementationOf(iteratorClass)) {
                                Integer regIt = Integer.valueOf(reg);
                                Iterator<Integer> curIt = groupToIterator.values().iterator();
                                while (curIt.hasNext()) {
                                    if (curIt.next().equals(regIt)) {
                                        curIt.remove();
                                    }
                                }
                                groupToIterator.put(id, regIt);
                            }

                            GroupPair pair = collectionGroups.get(id.intValue());
                            if (pair != null) {
                                pair.addMember(Integer.valueOf(reg));
                            }
                        } catch (ClassNotFoundException cnfe) {
                            bugReporter.reportMissingClass(cnfe);
                        }
                    } else {
                        String cls = itm.getSignature();
                        if ((cls != null) && cls.startsWith("L")) {
                            cls = cls.substring(1, cls.length() - 1);
                            if (isCollection(cls) || "java/util/Iterator".equals(cls)) {
                                int reg = RegisterUtils.getAStoreReg(this, seen);
                                removeFromCollectionGroup(new OpcodeStack.Item(itm, reg));
                                Iterator<Integer> it = groupToIterator.values().iterator();
                                while (it.hasNext()) {
                                    if (it.next().intValue() == reg) {
                                        it.remove();
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (OpcodeUtils.isALoad(seen)) {
                int reg = RegisterUtils.getALoadReg(this, seen);
                OpcodeStack.Item itm = new OpcodeStack.Item(new OpcodeStack.Item(), reg);
                groupId = findCollectionGroup(itm, false);
            } else if ((seen == IFEQ) && (stack.getStackDepth() > 0)) {
                OpcodeStack.Item itm = stack.getStackItem(0);
                Integer id = (Integer) itm.getUserValue();
                if (id != null) {
                    int target = getBranchTarget();
                    int gotoAddr = target - 3;
                    int ins = getCode().getCode()[gotoAddr];
                    if (ins < 0) {
                        ins = 256 + ins;
                    }
                    if ((ins == GOTO) || (ins == GOTO_W)) {
                        Integer reg = groupToIterator.get(id);
                        if (reg != null) {
                            loops.put(reg, new Loop(getPC(), gotoAddr));
                        }
                    }
                }
            }
        } finally {
            TernaryPatcher.pre(stack, seen);
            stack.sawOpcode(this, seen);
            TernaryPatcher.post(stack, seen);
            if ((groupId >= 0) && (stack.getStackDepth() > 0)) {
                OpcodeStack.Item itm = stack.getStackItem(0);
                itm.setUserValue(Integer.valueOf(groupId));
            }

            processEndOfScopes(Integer.valueOf(getPC()));
        }
    }

    /**
     * looks to see if the following instruction is a GOTO, preceded by potentially a pop
     *
     * @param loop
     *            the loop structure we are checking
     * @param needsPop
     *            whether we expect to see a pop next
     *
     * @return whether a GOTO is found
     */
    private boolean breakFollows(Loop loop, boolean needsPop) {

        byte[] code = getCode().getCode();
        int nextPC = getNextPC();

        if (needsPop) {
            int popOp = CodeByteUtils.getbyte(code, nextPC++);
            if (popOp != Constants.POP) {
                return false;
            }
        }

        int gotoOp = CodeByteUtils.getbyte(code, nextPC);
        if ((gotoOp == Constants.GOTO) || (gotoOp == Constants.GOTO_W)) {
            int target = nextPC + CodeByteUtils.getshort(code, nextPC + 1);
            if (target > loop.getLoopFinish()) {
                return true;
            }
        }

        return false;
    }

    /**
     * This attempts to see if there is some form of a return statement following the collection modifying statement in the loop. It is a bad cheat, because, we
     * may allow a POP, or an ALOAD/ILOAD etc before the return. this is sloppy tho as it might be a multibyte instruction. It also might be a complex piece of
     * code to load the return, or the method may not allow returns. But hopefully it's better than it was.
     *
     * @param couldSeePop
     *            if the preceding instruction returns a value, and thus might need to be popped
     */
    private boolean returnFollows(boolean couldSeePop) {

        byte[] code = getCode().getCode();
        int nextPC = getNextPC();

        int nextOp = CodeByteUtils.getbyte(code, nextPC++);

        if ((nextOp >= Constants.IRETURN) && (nextOp <= Constants.RETURN)) {
            return true;
        } else if ((couldSeePop) && (nextOp == Constants.POP)) {
            nextOp = CodeByteUtils.getbyte(code, nextPC++);
            if ((nextOp >= Constants.IRETURN) && (nextOp <= Constants.RETURN)) {
                return true;
            }
        }

        nextOp = CodeByteUtils.getbyte(code, nextPC++);
        if ((nextOp >= Constants.IRETURN) && (nextOp <= Constants.RETURN)) {
            return true;
        }

        return false;
    }

    /**
     * returns whether the class name is derived from java.util.Collection
     *
     * @param className
     *            the class to check
     * @return whether the class is a collection
     */
    private boolean isCollection(String className) {
        try {
            JavaClass cls = Repository.lookupClass(className);
            return cls.implementationOf(collectionClass) && !exceptionClasses.contains(cls);
        } catch (ClassNotFoundException cnfe) {
            bugReporter.reportMissingClass(cnfe);
            return false;
        }
    }

    /**
     * given an register or field, look to see if this thing is associated with an already discovered loop
     *
     * @param itm
     *            the item containing the register or field
     * @return the group element
     */
    private static Comparable<?> getGroupElement(OpcodeStack.Item itm) {
        Comparable<?> groupElement = null;

        int reg = itm.getRegisterNumber();
        if (reg >= 0) {
            groupElement = Integer.valueOf(reg);
        } else {
            XField field = itm.getXField();
            if (field != null) {
                int regLoad = itm.getFieldLoadedFromRegister();
                if (regLoad >= 0) {
                    groupElement = field.getName() + ":{" + regLoad + '}';
                }
            }
        }

        return groupElement;
    }

    private int findCollectionGroup(OpcodeStack.Item itm, boolean addIfNotFound) {

        Integer id = (Integer) itm.getUserValue();
        if (id != null) {
            return id.intValue();
        }

        Comparable<?> groupElement = getGroupElement(itm);
        if (groupElement != null) {
            int numGroups = collectionGroups.size();
            for (int i = 0; i < numGroups; i++) {
                GroupPair groupPair = collectionGroups.get(i);
                if (groupPair.containsMember(groupElement)) {
                    return i;
                }
            }

            if (addIfNotFound) {
                GroupPair groupPair = new GroupPair(groupElement, itm.getSignature());
                collectionGroups.add(groupPair);
                return collectionGroups.size() - 1;
            }
        }

        return -1;
    }

    private void removeFromCollectionGroup(OpcodeStack.Item itm) {
        Comparable<?> groupElement = getGroupElement(itm);
        if (groupElement != null) {
            for (GroupPair groupPair : collectionGroups) {
                if (groupPair.containsMember(groupElement)) {
                    groupPair.removeMember(groupElement);
                    break;
                }
            }
        }
    }

    private void buildVariableEndScopeMap() {
        endOfScopes = new HashMap<Integer, BitSet>();

        LocalVariableTable lvt = getMethod().getLocalVariableTable();
        if (lvt != null) {
            int len = lvt.getLength();
            for (int i = 0; i < len; i++) {
                @SuppressWarnings("deprecation")
                LocalVariable lv = lvt.getLocalVariable(i);
                if (lv != null) {
                    Integer endPC = Integer.valueOf(lv.getStartPC() + lv.getLength());
                    BitSet vars = endOfScopes.get(endPC);
                    if (vars == null) {
                        vars = new BitSet();
                        endOfScopes.put(endPC, vars);
                    }
                    vars.set(lv.getIndex());
                }
            }
        }
    }

    private void processEndOfScopes(Integer pc) {
        BitSet endVars = endOfScopes.get(pc);
        if (endVars != null) {
            for (int i = endVars.nextSetBit(0); i >= 0; i = endVars.nextSetBit(i + 1)) {
                Integer v = Integer.valueOf(i);
                {
                    Iterator<GroupPair> it = collectionGroups.iterator();
                    while (it.hasNext()) {
                        GroupPair groupPair = it.next();
                        if (groupPair.containsMember(v)) {
                            groupPair.removeMember(v);
                        }
                    }
                }
                {
                    Iterator<Integer> it = groupToIterator.values().iterator();
                    while (it.hasNext()) {
                        if (v.equals(it.next())) {
                            it.remove();
                        }
                    }
                }
            }
        }
    }

    static class Loop {
        public int loopStart;
        public int loopFinish;

        public Loop(int start, int finish) {
            loopStart = start;
            loopFinish = finish;
        }

        int getLoopFinish() {
            return loopFinish;
        }

        int getLoopStart() {
            return loopStart;
        }

        boolean hasPC(int pc) {
            return (loopStart <= pc) && (pc <= loopFinish);
        }

        @Override
        public String toString() {
            return ToString.build(this);
        }
    }

    static class GroupPair {
        private final Set<Comparable<?>> groupMembers;
        private final String colClass;

        public GroupPair(Comparable<?> member, String cls) {
            groupMembers = new HashSet<Comparable<?>>();
            groupMembers.add(member);
            colClass = cls;
        }

        void addMember(Comparable<?> member) {
            groupMembers.add(member);
        }

        void removeMember(Comparable<?> member) {
            groupMembers.remove(member);
        }

        boolean containsMember(Comparable<?> member) {
            return groupMembers.contains(member);
        }

        boolean isStandardCollection() {
            return (colClass == null) || !colClass.contains("/concurrent/");
        }

        @Override
        public String toString() {
            return ToString.build(this);
        }
    }
}
