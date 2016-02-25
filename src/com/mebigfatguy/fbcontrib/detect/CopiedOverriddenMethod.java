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

import java.util.HashMap;
import java.util.Map;

import org.apache.bcel.Constants;
import org.apache.bcel.classfile.Code;
import org.apache.bcel.classfile.ConstantClass;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.ConstantPoolGen;
import org.apache.bcel.generic.FieldInstruction;
import org.apache.bcel.generic.Instruction;
import org.apache.bcel.generic.InstructionHandle;
import org.apache.bcel.generic.InstructionList;
import org.apache.bcel.generic.InvokeInstruction;
import org.apache.bcel.generic.LDC;
import org.apache.bcel.generic.ReferenceType;
import org.apache.bcel.generic.Type;

import com.mebigfatguy.fbcontrib.utils.BugType;
import com.mebigfatguy.fbcontrib.utils.SignatureUtils;
import com.mebigfatguy.fbcontrib.utils.ToString;
import com.mebigfatguy.fbcontrib.utils.Values;

import edu.umd.cs.findbugs.BugInstance;
import edu.umd.cs.findbugs.BugReporter;
import edu.umd.cs.findbugs.BytecodeScanningDetector;
import edu.umd.cs.findbugs.ba.ClassContext;

/**
 * Looks for methods that are direct copies of the implementation in the super class. This detector doesn't handle multi-level inheritance, ie child to
 * grandparent. Could be done.
 */
public class CopiedOverriddenMethod extends BytecodeScanningDetector {
    private final BugReporter bugReporter;
    private Map<String, CodeInfo> superclassCode;
    private ClassContext classContext;
    private String curMethodInfo;
    private ConstantPoolGen childPoolGen, parentPoolGen;
    private Type[] parmTypes;
    private int nextParmIndex;
    private int nextParmOffset;
    private boolean sawAload0;
    private boolean sawParentCall;
    private boolean ignore;

    /**
     * constructs a COM detector given the reporter to report bugs on
     *
     * @param bugReporter
     *            the sync of bug reports
     */
    public CopiedOverriddenMethod(BugReporter bugReporter) {
        this.bugReporter = bugReporter;
    }

    /**
     * overrides the visitor to accept classes derived from non java.lang.Object classes.
     *
     * @param clsContext
     *            the context object of the currently parsed class
     */
    @Override
    public void visitClassContext(ClassContext clsContext) {
        try {
            JavaClass cls = clsContext.getJavaClass();
            String superName = cls.getSuperclassName();
            if (!Values.DOTTED_JAVA_LANG_OBJECT.equals(superName)) {
                this.classContext = clsContext;
                superclassCode = new HashMap<String, CodeInfo>();
                JavaClass superCls = cls.getSuperClass();
                childPoolGen = new ConstantPoolGen(cls.getConstantPool());
                parentPoolGen = new ConstantPoolGen(superCls.getConstantPool());
                Method[] methods = superCls.getMethods();
                for (Method m : methods) {
                    String methodName = m.getName();
                    if ((m.isPublic() || m.isProtected()) && !m.isAbstract() && !m.isSynthetic()
                            && !Values.CONSTRUCTOR.equals(methodName) && !Values.STATIC_INITIALIZER.equals(methodName)) {
                        String methodInfo = methodName + ':' + m.getSignature();
                        superclassCode.put(methodInfo, new CodeInfo(m.getCode(), m.getAccessFlags()));
                    }
                }
                cls.accept(this);
            }
        } catch (ClassNotFoundException cnfe) {
            bugReporter.reportMissingClass(cnfe);
        } finally {
            superclassCode = null;
            this.classContext = null;
            childPoolGen = null;
            parentPoolGen = null;
        }
    }

    /**
     * overrides the visitor to get the methodInfo
     *
     * @param obj
     *            the method object for the currently parsed method
     */
    @Override
    public void visitMethod(Method obj) {
        curMethodInfo = obj.getName() + ':' + obj.getSignature();
    }

    /**
     * overrides the visitor to find code blocks of methods that are the same as its parents
     *
     * @param obj
     *            the code object of the currently parsed method
     */
    @Override
    public void visitCode(Code obj) {
        Method m = getMethod();
        if ((!m.isPublic() && !m.isProtected()) || m.isAbstract() || m.isSynthetic()) {
            return;
        }

        CodeInfo superCode = superclassCode.remove(curMethodInfo);
        if (superCode != null) {
            if (sameAccess(getMethod().getAccessFlags(), superCode.getAccess()) && codeEquals(obj, superCode.getCode())) {
                bugReporter.reportBug(new BugInstance(this, BugType.COM_COPIED_OVERRIDDEN_METHOD.name(), NORMAL_PRIORITY).addClass(this).addMethod(this)
                        .addSourceLine(classContext, this, getPC()));
                return;
            }

            parmTypes = getMethod().getArgumentTypes();
            ignore = false;
            nextParmIndex = 0;
            nextParmOffset = (getMethod().getAccessFlags() & Constants.ACC_STATIC) != 0 ? 0 : 1;
            sawAload0 = nextParmOffset == 0;
            sawParentCall = false;
            super.visitCode(obj);
        }

    }

    /**
     * overrides the visitor to look for an exact call to the parent class's method using this methods parm.
     *
     * @param seen
     *            the currently parsed instruction
     *
     */
    @Override
    public void sawOpcode(int seen) {
        if (ignore) {
            return;
        }

        if (!sawAload0) {
            if (seen == ALOAD_0) {
                sawAload0 = true;
            } else {
                ignore = true;
            }
        } else if (nextParmIndex < parmTypes.length) {
            if (isExpectedParmInstruction(seen, nextParmOffset, parmTypes[nextParmIndex])) {
                nextParmOffset += SignatureUtils.getSignatureSize(parmTypes[nextParmIndex].getSignature());
                nextParmIndex++;
            } else {
                ignore = true;
            }

        } else if (!sawParentCall) {

            if ((seen == INVOKESPECIAL) && getNameConstantOperand().equals(getMethod().getName())
                    && getSigConstantOperand().equals(getMethod().getSignature())) {
                sawParentCall = true;
            } else {
                ignore = true;
            }
        } else {
            int expectedInstruction = getExpectedReturnInstruction(getMethod().getReturnType());
            if ((seen == expectedInstruction) && (getNextPC() == getCode().getCode().length)) {
                bugReporter.reportBug(
                        new BugInstance(this, BugType.COM_PARENT_DELEGATED_CALL.name(), NORMAL_PRIORITY).addClass(this).addMethod(this).addSourceLine(this));
            } else {
                ignore = true;
            }
        }
    }

    private boolean isExpectedParmInstruction(int seen, int parmOffset, Type type) {

        switch (getExpectedReturnInstruction(type)) {
            case Constants.ARETURN:
                return isExpectedParmInstruction(Constants.ALOAD_0, Constants.ALOAD, seen, parmOffset);
            case Constants.DRETURN:
                return isExpectedParmInstruction(Constants.DLOAD_0, Constants.DLOAD, seen, parmOffset);
            case Constants.FRETURN:
                return isExpectedParmInstruction(Constants.FLOAD_0, Constants.FLOAD, seen, parmOffset);
            case Constants.LRETURN:
                return isExpectedParmInstruction(Constants.LLOAD_0, Constants.LLOAD, seen, parmOffset);
            default:
                return isExpectedParmInstruction(Constants.ILOAD_0, Constants.ILOAD, seen, parmOffset);
        }
    }

    private boolean isExpectedParmInstruction(int offsetConstant, int constant, int seen, int parmOffset) {
        if (parmOffset <= 3) {
            return (offsetConstant + parmOffset) == seen;
        }
        return (constant == seen) && (parmOffset == getRegisterOperand());
    }

    private static int getExpectedReturnInstruction(Type type) {

        if ((type == Type.OBJECT) || (type == Type.STRING) || (type == Type.STRINGBUFFER) || (type == Type.THROWABLE)) {
            return Constants.ARETURN;
        } else if (type == Type.DOUBLE) {
            return Constants.DRETURN;
        } else if (type == Type.FLOAT) {
            return Constants.FRETURN;
        } else if (type == Type.LONG) {
            return Constants.LRETURN;
        }

        return Constants.IRETURN;
    }

    /**
     * determines if two access flags contain the same access modifiers
     *
     * @param parentAccess
     * @param childAccess
     * @return
     */
    private static boolean sameAccess(int parentAccess, int childAccess) {
        return ((parentAccess & (Constants.ACC_PUBLIC | Constants.ACC_PROTECTED)) == (childAccess & (Constants.ACC_PUBLIC | Constants.ACC_PROTECTED)));
    }

    /**
     * compares two code blocks to see if they are equal with regard to instructions and field accesses
     *
     * @param child
     *            the first code block
     * @param parent
     *            the second code block
     *
     * @return whether the code blocks are the same
     */
    @SuppressWarnings("deprecation")
    private boolean codeEquals(Code child, Code parent) {

        byte[] childBytes = child.getCode();
        byte[] parentBytes = parent.getCode();

        if ((childBytes == null) || (parentBytes == null)) {
            return false;
        }

        if (childBytes.length != parentBytes.length) {
            return false;
        }

        InstructionHandle[] childihs = new InstructionList(childBytes).getInstructionHandles();
        InstructionHandle[] parentihs = new InstructionList(parentBytes).getInstructionHandles();

        if (childihs.length != parentihs.length) {
            return false;
        }

        for (int i = 0; i < childihs.length; i++) {
            InstructionHandle childih = childihs[i];
            InstructionHandle parentih = parentihs[i];
            Instruction childin = childih.getInstruction();
            Instruction parentin = parentih.getInstruction();

            if (!childin.getName().equals(parentin.getName())) {
                return false;
            }

            if (childin instanceof FieldInstruction) {
                String childFName = ((FieldInstruction) childin).getFieldName(childPoolGen);
                String parentFName = ((FieldInstruction) parentin).getFieldName(parentPoolGen);
                if (!childFName.equals(parentFName)) {
                    return false;
                }
                String childFSig = ((FieldInstruction) childin).getSignature(childPoolGen);
                String parentFSig = ((FieldInstruction) parentin).getSignature(parentPoolGen);
                if (!childFSig.equals(parentFSig)) {
                    return false;
                }

                if (childFSig.charAt(0) == 'L') {
                    ReferenceType childRefType = ((FieldInstruction) childin).getReferenceType(childPoolGen);
                    ReferenceType parentRefType = ((FieldInstruction) parentin).getReferenceType(parentPoolGen);
                    if (!childRefType.getSignature().equals(parentRefType.getSignature())) {
                        return false;
                    }
                }
            } else if (childin instanceof InvokeInstruction) {
                String childClassName = ((InvokeInstruction) childin).getClassName(childPoolGen);
                String parentClassName = ((InvokeInstruction) parentin).getClassName(parentPoolGen);
                if (!childClassName.equals(parentClassName)) {
                    return false;
                }
                String childMethodName = ((InvokeInstruction) childin).getMethodName(childPoolGen);
                String parentMethodName = ((InvokeInstruction) parentin).getMethodName(parentPoolGen);
                if (!childMethodName.equals(parentMethodName)) {
                    return false;
                }
                String childSignature = ((InvokeInstruction) childin).getSignature(childPoolGen);
                String parentSignature = ((InvokeInstruction) parentin).getSignature(parentPoolGen);
                if (!childSignature.equals(parentSignature)) {
                    return false;
                }
            } else if (childin instanceof LDC) {
                Type childType = ((LDC) childin).getType(childPoolGen);
                Type parentType = ((LDC) parentin).getType(parentPoolGen);
                if (!childType.equals(parentType)) {
                    return false;
                }

                Object childValue = ((LDC) childin).getValue(childPoolGen);
                Object parentValue = ((LDC) parentin).getValue(parentPoolGen);

                if (childValue instanceof ConstantClass) {
                    ConstantClass childClass = (ConstantClass) childValue;
                    ConstantClass parentClass = (ConstantClass) parentValue;
                    if (!childClass.getBytes(childPoolGen.getConstantPool()).equals(parentClass.getBytes(parentPoolGen.getConstantPool()))) {
                        return false;
                    }
                } else if (!childValue.equals(parentValue)) {
                    return false;
                }
                // TODO: Other Constant types
            } else {
                if (!childin.equals(parentin)) {
                    return false;
                }
            }
        }

        return true;
    }

    static class CodeInfo {
        private Code code;
        private int access;

        public CodeInfo(Code c, int acc) {
            code = c;
            access = acc;
        }

        public Code getCode() {
            return code;
        }

        public int getAccess() {
            return access;
        }

        @Override
        public String toString() {
            return ToString.build(this);
        }
    }
}
