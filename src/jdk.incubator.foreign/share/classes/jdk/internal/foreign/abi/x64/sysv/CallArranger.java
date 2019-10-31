/*
 *  Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
 *  DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 *  This code is free software; you can redistribute it and/or modify it
 *  under the terms of the GNU General Public License version 2 only, as
 *  published by the Free Software Foundation.  Oracle designates this
 *  particular file as subject to the "Classpath" exception as provided
 *  by Oracle in the LICENSE file that accompanied this code.
 *
 *  This code is distributed in the hope that it will be useful, but WITHOUT
 *  ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 *  FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 *  version 2 for more details (a copy is included in the LICENSE file that
 *  accompanied this code).
 *
 *  You should have received a copy of the GNU General Public License version
 *  2 along with this work; if not, write to the Free Software Foundation,
 *  Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 *   Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 *  or visit www.oracle.com if you need additional information or have any
 *  questions.
 *
 */
package jdk.internal.foreign.abi.x64.sysv;

import jdk.incubator.foreign.FunctionDescriptor;
import jdk.incubator.foreign.GroupLayout;
import jdk.incubator.foreign.MemoryAddress;
import jdk.incubator.foreign.MemoryLayout;
import jdk.incubator.foreign.MemoryLayouts;
import jdk.incubator.foreign.MemorySegment;
import jdk.incubator.foreign.SequenceLayout;
import jdk.incubator.foreign.ValueLayout;
import jdk.internal.foreign.Utils;
import jdk.internal.foreign.abi.CallingSequenceBuilder;
import jdk.internal.foreign.abi.UpcallHandler;
import jdk.internal.foreign.abi.ABIDescriptor;
import jdk.internal.foreign.abi.Binding;
import jdk.internal.foreign.abi.CallingSequence;
import jdk.internal.foreign.abi.ProgrammableInvoker;
import jdk.internal.foreign.abi.ProgrammableUpcallHandler;
import jdk.internal.foreign.abi.VMStorage;
import jdk.internal.foreign.abi.x64.X86_64Architecture;
import jdk.internal.foreign.abi.x64.ArgumentClassImpl;
import jdk.internal.foreign.abi.SharedUtils;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.lang.invoke.MethodHandles.collectArguments;
import static java.lang.invoke.MethodHandles.identity;
import static java.lang.invoke.MethodHandles.insertArguments;
import static java.lang.invoke.MethodHandles.permuteArguments;
import static java.lang.invoke.MethodType.methodType;
import static jdk.internal.foreign.abi.x64.X86_64Architecture.*;
import static jdk.internal.foreign.abi.x64.sysv.SysVx64ABI.MAX_INTEGER_ARGUMENT_REGISTERS;
import static jdk.internal.foreign.abi.x64.sysv.SysVx64ABI.MAX_VECTOR_ARGUMENT_REGISTERS;

/**
 * For the SysV x64 C ABI specifically, this class uses the ProgrammableInvoker API, namely CallingSequenceBuilder2
 * to translate a C FunctionDescriptor into a CallingSequence, which can then be turned into a MethodHandle.
 *
 * This includes taking care of synthetic arguments like pointers to return buffers for 'in-memory' returns.
 */
public class CallArranger {
    private static final int SSE_ARGUMENT_SIZE = 8;
    private static final int STACK_SLOT_SIZE = 8;
    private static final MethodHandle MH_ALLOC_BUFFER;
    private static final MethodHandle MH_BASEADDRESS;
    private static final MethodHandle MH_BUFFER_COPY;

    private static final ABIDescriptor CSysV = X86_64Architecture.abiFor(
        new VMStorage[] { rdi, rsi, rdx, rcx, r8, r9, rax },
        new VMStorage[] { xmm0, xmm1, xmm2, xmm3, xmm4, xmm5, xmm6, xmm7 },
        new VMStorage[] { rax, rdx },
        new VMStorage[] { xmm0, xmm1 },
        2,
        new VMStorage[] { r10, r11 },
        new VMStorage[] { xmm8, xmm9, xmm10, xmm11, xmm12, xmm13, xmm14, xmm15 },
        16,
        0 //no shadow space
    );

    static {
        try {
            var lookup = MethodHandles.lookup();
            MH_ALLOC_BUFFER = lookup.findStatic(MemorySegment.class, "ofNative",
                    methodType(MemorySegment.class, MemoryLayout.class));
            MH_BASEADDRESS = lookup.findVirtual(MemorySegment.class, "baseAddress",
                    methodType(MemoryAddress.class));
            MH_BUFFER_COPY = lookup.findStatic(CallArranger.class, "bufferCopy",
                    methodType(void.class, MemoryAddress.class, MemorySegment.class));
        } catch (ReflectiveOperationException e) {
            throw new BootstrapMethodError(e);
        }
    }

    public static MethodHandle arrangeDowncall(long addr, MethodType mt, FunctionDescriptor cDesc) {
        assert mt.parameterCount() == cDesc.argumentLayouts().size() : "arity must match!";
        assert (mt.returnType() != void.class) == cDesc.returnLayout().isPresent() : "return type presence must match!";

        CallingSequenceBuilder csb = new CallingSequenceBuilder();

        UnboxBindingCalculator argCalc = new UnboxBindingCalculator(true);
        BoxBindingCalculator retCalc = new BoxBindingCalculator(false);

        boolean returnInMemory = isInMemoryReturn(cDesc.returnLayout());
        if (returnInMemory) {
            csb.addArgument(MemoryAddress.class, MemoryLayouts.SysV.C_POINTER,
                    argCalc.getBindings(MemoryAddress.class, MemoryLayouts.SysV.C_POINTER));
        } else if (cDesc.returnLayout().isPresent()) {
            csb.setReturnBindings(mt.returnType(), cDesc.returnLayout().get(),
                    retCalc.getBindings(mt.returnType(), cDesc.returnLayout().get()));
        }

        for (int i = 0; i < mt.parameterCount(); i++) {
            MemoryLayout layout = cDesc.argumentLayouts().get(i);
            csb.addArgument(mt.parameterType(i), layout, argCalc.getBindings(mt.parameterType(i), layout));
        }

        //add extra binding for number of used vector registers (used for variadic calls)
        csb.addArgument(long.class, MemoryLayouts.SysV.C_LONG,
                List.of(new Binding.Move(rax, long.class)));

        CallingSequence cs = csb.build();
        MethodHandle rawHandle = new ProgrammableInvoker(CSysV, addr, cs).getBoundMethodHandle();
        rawHandle = MethodHandles.insertArguments(rawHandle, rawHandle.type().parameterCount() - 1, argCalc.storageCalculator.nVectorReg);

        if (returnInMemory) {
            assert rawHandle.type().returnType() == void.class : "return expected to be void for in memory returns";
            assert rawHandle.type().parameterType(0) == MemoryAddress.class : "MemoryAddress expected as first param";

            MethodHandle ret = identity(MemorySegment.class); // (MemorySegment) MemorySegment
            rawHandle = collectArguments(ret, 1, rawHandle); // (MemorySegment, MemoryAddress ...) MemorySegment
            rawHandle = collectArguments(rawHandle, 1, MH_BASEADDRESS); // (MemorySegment, MemorySegment ...) MemorySegment
            MethodType oldType = rawHandle.type(); // (MemorySegment, MemorySegment, ...) MemorySegment
            MethodType newType = oldType.dropParameterTypes(0, 1); // (MemorySegment, ...) MemorySegment
            int[] reorder = IntStream.concat(IntStream.of(0), IntStream.range(0, oldType.parameterCount() - 1)).toArray(); // [0, 0, 1, 2, 3, ...]
            rawHandle = permuteArguments(rawHandle, newType, reorder); // (MemorySegment, ...) MemoryAddress
            rawHandle = collectArguments(rawHandle, 0, insertArguments(MH_ALLOC_BUFFER, 0, cDesc.returnLayout().get())); // (...) MemoryAddress
        }

        return rawHandle;
    }

    public static UpcallHandler arrangeUpcall(MethodHandle target, MethodType mt, FunctionDescriptor cDesc) {
        assert mt.parameterCount() == cDesc.argumentLayouts().size() : "arity must match!";
        assert (mt.returnType() != void.class) == cDesc.returnLayout().isPresent() : "return type presence must match!";

        CallingSequenceBuilder csb = new CallingSequenceBuilder();

        BoxBindingCalculator argCalc = new BoxBindingCalculator(true);
        UnboxBindingCalculator retCalc = new UnboxBindingCalculator(false);

        boolean returnInMemory = isInMemoryReturn(cDesc.returnLayout());
        if (returnInMemory) {
            csb.addArgument(MemoryAddress.class, MemoryLayouts.SysV.C_POINTER,
                    argCalc.getBindings(MemoryAddress.class, MemoryLayouts.SysV.C_POINTER));
        } else if (cDesc.returnLayout().isPresent()) {
            csb.setReturnBindings(mt.returnType(), cDesc.returnLayout().get(),
                    retCalc.getBindings(mt.returnType(), cDesc.returnLayout().get()));
        }

        for (int i = 0; i < mt.parameterCount(); i++) {
            MemoryLayout layout = cDesc.argumentLayouts().get(i);
            csb.addArgument(mt.parameterType(i), layout, argCalc.getBindings(mt.parameterType(i), layout));
        }

        if (returnInMemory) {
            assert target.type().returnType() == MemorySegment.class : "Must return MemorySegment for IMR";

            target = collectArguments(MH_BUFFER_COPY, 1, target); // erase return type
            int[] reorder = IntStream.range(-1, target.type().parameterCount()).toArray();
            reorder[0] = 0; // [0, 0, 1, 2, 3 ...]
            target = collectArguments(identity(MemoryAddress.class), 1, target); // (MemoryAddress, MemoryAddress, ...) MemoryAddress
            target = permuteArguments(target, target.type().dropParameterTypes(0, 1), reorder); // (MemoryAddress, ...) MemoryAddress
        }

        CallingSequence cs = csb.build();
        return new ProgrammableUpcallHandler(CSysV, target, cs);
    }

    private static void bufferCopy(MemoryAddress dest, MemorySegment buffer) {
        MemoryAddress.copy(buffer.baseAddress(), dest, buffer.byteSize());
    }

    private static boolean isInMemoryReturn(Optional<MemoryLayout> returnLayout) {
        return returnLayout
                .filter(GroupLayout.class::isInstance)
                .filter(g -> classifyLayout(g).inMemory())
                .isPresent();
    }

    static class TypeClass {
        enum Kind {
            STRUCT,
            POINTER,
            INTEGER,
            FLOAT
        }

        private final Kind kind;
        private final List<ArgumentClassImpl> classes;

        private TypeClass(Kind kind, List<ArgumentClassImpl> classes) {
            this.kind = kind;
            this.classes = classes;
        }

        public static TypeClass ofValue(List<ArgumentClassImpl> classes) {
            if (classes.size() != 1) {
                throw new IllegalStateException();
            }
            final Kind kind;
            switch (classes.get(0)) {
                case POINTER: kind = Kind.POINTER; break;
                case INTEGER: kind = Kind.INTEGER; break;
                case SSE: kind = Kind.FLOAT; break;
                default:
                    throw new IllegalStateException();
            }
            return new TypeClass(kind, classes);
        }

        public static TypeClass ofStruct(List<ArgumentClassImpl> classes) {
            return new TypeClass(Kind.STRUCT, classes);
        }

        boolean inMemory() {
            return classes.stream().anyMatch(c -> c == ArgumentClassImpl.MEMORY);
        }

        long numClasses(ArgumentClassImpl clazz) {
            return classes.stream().filter(c -> c == clazz).count();
        }
    }

    static class StorageCalculator {
        private final boolean forArguments;

        private int nVectorReg = 0;
        private int nIntegerReg = 0;
        private long stackOffset = 0;

        public StorageCalculator(boolean forArguments) {
            this.forArguments = forArguments;
        }

        private int maxRegisterArguments(int type) {
            return type == StorageClasses.INTEGER ?
                    MAX_INTEGER_ARGUMENT_REGISTERS :
                    SysVx64ABI.MAX_VECTOR_ARGUMENT_REGISTERS;
        }

        VMStorage stackAlloc() {
            assert forArguments : "no stack returns";
            VMStorage storage = X86_64Architecture.stackStorage((int)stackOffset);
            stackOffset++;
            return storage;
        }

        VMStorage nextStorage(int type) {
            int registerCount = registerCount(type);
            if (registerCount < maxRegisterArguments(type)) {
                VMStorage[] source =
                    (forArguments ? CSysV.inputStorage : CSysV.outputStorage)[type];
                incrementRegisterCount(type);
                return source[registerCount];
            } else {
                return stackAlloc();
            }
        }

        VMStorage[] structStorages(TypeClass typeClass) {
            if (typeClass.inMemory()) {
                return typeClass.classes.stream().map(c -> stackAlloc()).toArray(VMStorage[]::new);
            }
            long nIntegerReg = typeClass.numClasses(ArgumentClassImpl.INTEGER) +
                          typeClass.numClasses(ArgumentClassImpl.POINTER);

            if (this.nIntegerReg + nIntegerReg > MAX_INTEGER_ARGUMENT_REGISTERS) {
                //not enough registers - pass on stack
                return typeClass.classes.stream().map(c -> stackAlloc()).toArray(VMStorage[]::new);
            }

            long nVectorReg = typeClass.numClasses(ArgumentClassImpl.SSE);

            if (this.nVectorReg + nVectorReg > MAX_VECTOR_ARGUMENT_REGISTERS) {
                //not enough registers - pass on stack
                return typeClass.classes.stream().map(c -> stackAlloc()).toArray(VMStorage[]::new);
            }

            //ok, let's pass pass on registers
            VMStorage[] storage = new VMStorage[(int)(nIntegerReg + nVectorReg)];
            for (int i = 0 ; i < typeClass.classes.size() ; i++) {
                boolean sse = typeClass.classes.get(i) == ArgumentClassImpl.SSE;
                storage[i] = nextStorage(sse ? StorageClasses.VECTOR : StorageClasses.INTEGER);
            }
            return storage;
        }

        int registerCount(int type) {
            switch (type) {
                case StorageClasses.INTEGER:
                    return nIntegerReg;
                case StorageClasses.VECTOR:
                    return nVectorReg;
                default:
                    throw new IllegalStateException();
            }
        }

        void incrementRegisterCount(int type) {
            switch (type) {
                case StorageClasses.INTEGER:
                    nIntegerReg++;
                    break;
                case StorageClasses.VECTOR:
                    nVectorReg++;
                    break;
                default:
                    throw new IllegalStateException();
            }
        }
    }

    static class BindingCalculator {
        protected final StorageCalculator storageCalculator;

        protected BindingCalculator(boolean forArguments) {
            this.storageCalculator = new StorageCalculator(forArguments);
        }
    }

    static class UnboxBindingCalculator extends BindingCalculator {

        UnboxBindingCalculator(boolean forArguments) {
            super(forArguments);
        }

        List<Binding> getBindings(Class<?> carrier, MemoryLayout layout) {
            TypeClass argumentClass = classifyLayout(layout);
            List<Binding> bindings = new ArrayList<>();
            switch (argumentClass.kind) {
                case STRUCT: {
                    assert carrier == MemorySegment.class;
                    VMStorage[] regs = storageCalculator.structStorages(argumentClass);
                    int regIndex = 0;
                    long offset = 0;
                    while (offset < layout.byteSize()) {
                        final long copy = Math.min(layout.byteSize() - offset, 8);
                        VMStorage storage = regs[regIndex++];
                        bindings.add(new Binding.Dereference(storage, offset, copy));
                        offset += 8;
                    }
                    break;
                }
                case POINTER: {
                    bindings.add(new Binding.BoxAddress());
                    VMStorage storage = storageCalculator.nextStorage(StorageClasses.INTEGER);
                    bindings.add(new Binding.Move(storage, long.class));
                    break;
                }
                case INTEGER: {
                    VMStorage storage = storageCalculator.nextStorage(StorageClasses.INTEGER);
                    bindings.add(new Binding.Move(storage, carrier));
                    break;
                }
                case FLOAT: {
                    VMStorage storage = storageCalculator.nextStorage(StorageClasses.VECTOR);
                    bindings.add(new Binding.Move(storage, carrier));
                    break;
                }
                default:
                    throw new UnsupportedOperationException("Unhandled class " + argumentClass);
            }
            return bindings;
        }
    }

    static class BoxBindingCalculator extends BindingCalculator {

        BoxBindingCalculator(boolean forArguments) {
            super(forArguments);
        }

        @SuppressWarnings("fallthrough")
        List<Binding> getBindings(Class<?> carrier, MemoryLayout layout) {
            TypeClass argumentClass = classifyLayout(layout);
            List<Binding> bindings = new ArrayList<>();
            switch (argumentClass.kind) {
                case STRUCT: {
                    assert carrier == MemorySegment.class;
                    bindings.add(new Binding.AllocateBuffer(layout));
                    VMStorage[] regs = storageCalculator.structStorages(argumentClass);
                    int regIndex = 0;
                    long offset = 0;
                    while (offset < layout.byteSize()) {
                        final long copy = Math.min(layout.byteSize() - offset, 8);
                        VMStorage storage = regs[regIndex++];
                        bindings.add(new Binding.Dereference(storage, offset, copy));
                        offset += 8;
                    }
                    break;
                }
                case POINTER: {
                    VMStorage storage = storageCalculator.nextStorage(StorageClasses.INTEGER);
                    bindings.add(new Binding.Move(storage, long.class));
                    bindings.add(new Binding.BoxAddress());
                    break;
                }
                case INTEGER: {
                    VMStorage storage = storageCalculator.nextStorage(StorageClasses.INTEGER);
                    bindings.add(new Binding.Move(storage, carrier));
                    break;
                }
                case FLOAT: {
                    VMStorage storage = storageCalculator.nextStorage(StorageClasses.VECTOR);
                    bindings.add(new Binding.Move(storage, carrier));
                    break;
                }
                default:
                    throw new UnsupportedOperationException("Unhandled class " + argumentClass);
            }
            return bindings;
        }
    }

    // layout classification

    // The AVX 512 enlightened ABI says "eight eightbytes"
    // Although AMD64 0.99.6 states 4 eightbytes
    private static final int MAX_AGGREGATE_REGS_SIZE = 8;

    private static final ArrayList<ArgumentClassImpl> COMPLEX_X87_CLASSES;

    static {
        COMPLEX_X87_CLASSES = new ArrayList<>();
        COMPLEX_X87_CLASSES.add(ArgumentClassImpl.X87);
        COMPLEX_X87_CLASSES.add(ArgumentClassImpl.X87UP);
        COMPLEX_X87_CLASSES.add(ArgumentClassImpl.X87);
        COMPLEX_X87_CLASSES.add(ArgumentClassImpl.X87UP);
    }


    private static List<ArgumentClassImpl> createMemoryClassArray(long size) {
        return IntStream.range(0, (int)size)
                .mapToObj(i -> ArgumentClassImpl.MEMORY)
                .collect(Collectors.toCollection(ArrayList::new));
    }


    private static List<ArgumentClassImpl> classifyValueType(ValueLayout type) {
        ArrayList<ArgumentClassImpl> classes = new ArrayList<>();

        ArgumentClassImpl clazz = (ArgumentClassImpl)Utils.getAnnotation(type, ArgumentClassImpl.ABI_CLASS);
        if (clazz == null) {
            //padding not allowed here
            throw new IllegalStateException("Unexpected value layout: could not determine ABI class");
        }
        if (clazz == ArgumentClassImpl.POINTER) {
            clazz = ArgumentClassImpl.POINTER;
        }
        classes.add(clazz);
        if (clazz == ArgumentClassImpl.INTEGER) {
            // int128
            long left = (type.byteSize()) - 8;
            while (left > 0) {
                classes.add(ArgumentClassImpl.INTEGER);
                left -= 8;
            }
            return classes;
        } else if (clazz == ArgumentClassImpl.X87) {
            classes.add(ArgumentClassImpl.X87UP);
        }

        return classes;
    }

    private static List<ArgumentClassImpl> classifyArrayType(SequenceLayout type) {
        long nWords = Utils.alignUp((type.byteSize()), 8) / 8;
        if (nWords > MAX_AGGREGATE_REGS_SIZE) {
            return createMemoryClassArray(nWords);
        }

        ArrayList<ArgumentClassImpl> classes = new ArrayList<>();

        for (long i = 0; i < nWords; i++) {
            classes.add(ArgumentClassImpl.NO_CLASS);
        }

        long offset = 0;
        final long count = type.elementsCount().getAsLong();
        for (long idx = 0; idx < count; idx++) {
            MemoryLayout t = type.elementLayout();
            offset = SharedUtils.align(t, false, offset);
            List<ArgumentClassImpl> subclasses = classifyType(t);
            if (subclasses.isEmpty()) {
                return classes;
            }

            for (int i = 0; i < subclasses.size(); i++) {
                int pos = (int)(offset / 8);
                ArgumentClassImpl newClass = classes.get(i + pos).merge(subclasses.get(i));
                classes.set(i + pos, newClass);
            }

            offset += t.byteSize();
        }

        for (int i = 0; i < classes.size(); i++) {
            ArgumentClassImpl c = classes.get(i);

            if (c == ArgumentClassImpl.MEMORY) {
                return createMemoryClassArray(classes.size());
            }

            if (c == ArgumentClassImpl.X87UP) {
                if (i == 0) {
                    throw new IllegalArgumentException("Unexpected leading X87UP class");
                }

                if (classes.get(i - 1) != ArgumentClassImpl.X87) {
                    return createMemoryClassArray(classes.size());
                }
            }
        }

        if (classes.size() > 2) {
            if (classes.get(0) != ArgumentClassImpl.SSE) {
                return createMemoryClassArray(classes.size());
            }

            for (int i = 1; i < classes.size(); i++) {
                if (classes.get(i) != ArgumentClassImpl.SSEUP) {
                    return createMemoryClassArray(classes.size());
                }
            }
        }

        return classes;
    }

    // TODO: handle zero length arrays
    // TODO: Handle nested structs (and primitives)
    private static List<ArgumentClassImpl> classifyStructType(GroupLayout type) {
        ArgumentClassImpl clazz = (ArgumentClassImpl)Utils.getAnnotation(type, ArgumentClassImpl.ABI_CLASS);
        if (clazz == ArgumentClassImpl.COMPLEX_X87) {
            return COMPLEX_X87_CLASSES;
        }

        long nWords = Utils.alignUp((type.byteSize()), 8) / 8;
        if (nWords > MAX_AGGREGATE_REGS_SIZE) {
            return createMemoryClassArray(nWords);
        }

        ArrayList<ArgumentClassImpl> classes = new ArrayList<>();

        for (long i = 0; i < nWords; i++) {
            classes.add(ArgumentClassImpl.NO_CLASS);
        }

        long offset = 0;
        final int count = type.memberLayouts().size();
        for (int idx = 0; idx < count; idx++) {
            MemoryLayout t = type.memberLayouts().get(idx);
            if (Utils.isPadding(t)) {
                continue;
            }
            // ignore zero-length array for now
            // TODO: handle zero length arrays here
            if (t instanceof SequenceLayout) {
                if (((SequenceLayout) t).elementsCount().getAsLong() == 0) {
                    continue;
                }
            }
            offset = SharedUtils.align(t, false, offset);
            List<ArgumentClassImpl> subclasses = classifyType(t);
            if (subclasses.isEmpty()) {
                return classes;
            }

            for (int i = 0; i < subclasses.size(); i++) {
                int pos = (int)(offset / 8);
                ArgumentClassImpl newClass = classes.get(i + pos).merge(subclasses.get(i));
                classes.set(i + pos, newClass);
            }

            // TODO: validate union strategy is sound
            if (type.isStruct()) {
                offset += t.byteSize();
            }
        }

        for (int i = 0; i < classes.size(); i++) {
            ArgumentClassImpl c = classes.get(i);

            if (c == ArgumentClassImpl.MEMORY) {
                return createMemoryClassArray(classes.size());
            }

            if (c == ArgumentClassImpl.X87UP) {
                if (i == 0) {
                    throw new IllegalArgumentException("Unexpected leading X87UP class");
                }

                if (classes.get(i - 1) != ArgumentClassImpl.X87) {
                    return createMemoryClassArray(classes.size());
                }
            }
        }

        if (classes.size() > 2) {
            if (classes.get(0) != ArgumentClassImpl.SSE) {
                return createMemoryClassArray(classes.size());
            }

            for (int i = 1; i < classes.size(); i++) {
                if (classes.get(i) != ArgumentClassImpl.SSEUP) {
                    return createMemoryClassArray(classes.size());
                }
            }
        }

        return classes;
    }

    private static List<ArgumentClassImpl> classifyType(MemoryLayout type) {
        try {
            if (type instanceof ValueLayout) {
                return classifyValueType((ValueLayout) type);
            } else if (type instanceof SequenceLayout) {
                return classifyArrayType((SequenceLayout) type);
            } else if (type instanceof GroupLayout) {
                return classifyStructType((GroupLayout) type);
            } else {
                throw new IllegalArgumentException("Unhandled type " + type);
            }
        } catch (UnsupportedOperationException e) {
            System.err.println("Failed to classify layout: " + type);
            throw e;
        }
    }

    private static TypeClass classifyLayout(MemoryLayout type) {
        List<ArgumentClassImpl> classes = classifyType(type);
        try {
            if (type instanceof ValueLayout) {
                return TypeClass.ofValue(classes);
            } else if (type instanceof GroupLayout) {
                return TypeClass.ofStruct(classes);
            } else {
                throw new IllegalArgumentException("Unhandled type " + type);
            }
        } catch (UnsupportedOperationException e) {
            System.err.println("Failed to classify layout: " + type);
            throw e;
        }
    }
}
