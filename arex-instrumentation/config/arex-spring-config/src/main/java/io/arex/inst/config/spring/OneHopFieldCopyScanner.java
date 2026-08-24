package io.arex.inst.config.spring;

import io.arex.inst.runtime.log.LogManager;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Discovers the "straight-line, one-hop" constructor-flattening pattern: a source field is read
 * unchanged ({@code this.field}) inside its own declaring class's bytecode, passed unchanged as
 * one argument to {@code new SomeOtherClass(...)}, and assigned directly (no transformation, no
 * intervening instruction) to that constructor's own field. Deliberately scoped to exactly this
 * shape - the {@code fetchGroup}/{@code GetFabImpl} pattern this was built for - not a general
 * dataflow analysis. Anything wider (a helper-method hop, a branch, a transformation, a
 * multi-level chain, a record-accessor source) fails open: no derived field gets registered, same
 * as today's behavior for that field.
 *
 * <p>Read-side private fields (like a {@code @Value} scalar) can only be {@code GETFIELD}'d from
 * within their own declaring class's bytecode - the JVM itself enforces this for private members
 * - so the factory-method pass only needs to scan each source's own class, never the whole
 * application's classes.
 */
final class OneHopFieldCopyScanner {

    private OneHopFieldCopyScanner() {
    }

    private static final Set<Integer> ATOMIC_ZERO_OPERAND_PUSHES = new HashSet<>(java.util.Arrays.asList(
            Opcodes.ACONST_NULL,
            Opcodes.ICONST_M1, Opcodes.ICONST_0, Opcodes.ICONST_1, Opcodes.ICONST_2, Opcodes.ICONST_3, Opcodes.ICONST_4, Opcodes.ICONST_5,
            Opcodes.LCONST_0, Opcodes.LCONST_1,
            Opcodes.FCONST_0, Opcodes.FCONST_1, Opcodes.FCONST_2,
            Opcodes.DCONST_0, Opcodes.DCONST_1
    ));

    /** One place, found in a factory method, that a source field's value flows unchanged into a constructor call. */
    private static final class ConstructorCallSite {
        final String targetInternalName;
        final String constructorDescriptor;
        final int argPosition;

        ConstructorCallSite(String targetInternalName, String constructorDescriptor, int argPosition) {
            this.targetInternalName = targetInternalName;
            this.constructorDescriptor = constructorDescriptor;
            this.argPosition = argPosition;
        }
    }

    /**
     * Finds every one-hop derived field for the given source fields, grouped by the bean name
     * that owns the derived field - ready to be merged into BEAN_FIELDS exactly like
     * SpringBeanConfigRegistry's other discovery passes. Once registered there, a derived field
     * is recorded/replayed as an ordinary field - nothing downstream needs to remember where it
     * came from.
     */
    static Map<String, List<Field>> findOneHopCopies(Map<String, Object> applicationBeans,
            Map<String, List<Field>> sourceFieldsByBean, Map<Field, Class<?>> recordHolderFields) {
        Map<String, List<Map.Entry<String, Object>>> beansByInternalName = new HashMap<>();
        for (Map.Entry<String, Object> entry : applicationBeans.entrySet()) {
            String internalName = Type.getInternalName(entry.getValue().getClass());
            beansByInternalName.computeIfAbsent(internalName, k -> new ArrayList<>()).add(entry);
        }

        Map<String, List<Field>> derivedByBean = new HashMap<>();
        for (Map.Entry<String, List<Field>> beanEntry : sourceFieldsByBean.entrySet()) {
            Object sourceBean = applicationBeans.get(beanEntry.getKey());
            if (sourceBean == null) {
                continue;
            }
            for (Field sourceField : beanEntry.getValue()) {
                if (recordHolderFields.containsKey(sourceField)) {
                    continue; // a record reference field, not a scalar source - out of scope here
                }
                // sourceField.getDeclaringClass(), not sourceBean.getClass(): a @Configuration
                // bean is CGLIB-proxied by Spring by default, so the runtime class has no .class
                // file resource to read (generated in-memory) - the field's declaring class is
                // always the original user-written class regardless of proxying, and that's
                // where the @Bean factory method (inherited, not overridden, by the proxy) is
                // actually declared.
                List<ConstructorCallSite> callSites = scanForConstructorPassthrough(sourceField.getDeclaringClass(), sourceField.getName());
                for (ConstructorCallSite site : callSites) {
                    List<Map.Entry<String, Object>> targets = beansByInternalName.get(site.targetInternalName);
                    if (targets == null) {
                        continue;
                    }
                    for (Map.Entry<String, Object> targetEntry : targets) {
                        Field derivedField = scanConstructorForDirectAssignment(targetEntry.getValue().getClass(), site);
                        if (derivedField != null) {
                            derivedField.setAccessible(true);
                            derivedByBean.computeIfAbsent(targetEntry.getKey(), k -> new ArrayList<>()).add(derivedField);
                        }
                    }
                }
            }
        }
        return derivedByBean;
    }

    private static List<ConstructorCallSite> scanForConstructorPassthrough(Class<?> ownerClass, String sourceFieldName) {
        List<ConstructorCallSite> found = new ArrayList<>();
        byte[] bytecode = readBytecode(ownerClass);
        if (bytecode == null) {
            return found;
        }
        String ownerInternalName = Type.getInternalName(ownerClass);
        try {
            new ClassReader(bytecode).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    return new PassthroughDetector(ownerInternalName, sourceFieldName, found);
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (Throwable t) {
            LogManager.warn("spring.bean.config.dataflow.scan.error", t);
        }
        return found;
    }

    private static Field scanConstructorForDirectAssignment(Class<?> targetClass, ConstructorCallSite site) {
        byte[] bytecode = readBytecode(targetClass);
        if (bytecode == null) {
            return null;
        }
        Type[] argTypes = Type.getArgumentTypes(site.constructorDescriptor);
        int slotAccumulator = 1; // slot 0 is `this`
        for (int i = 0; i < site.argPosition; i++) {
            slotAccumulator += argTypes[i].getSize();
        }
        final int targetSlot = slotAccumulator;
        String targetInternalName = Type.getInternalName(targetClass);
        String[] resultFieldName = new String[1];
        try {
            new ClassReader(bytecode).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    if (!"<init>".equals(name) || !descriptor.equals(site.constructorDescriptor)) {
                        return null;
                    }
                    return new DirectAssignmentDetector(targetInternalName, targetSlot, resultFieldName);
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (Throwable t) {
            LogManager.warn("spring.bean.config.dataflow.scan.error", t);
            return null;
        }
        if (resultFieldName[0] == null) {
            return null;
        }
        try {
            return targetClass.getDeclaredField(resultFieldName[0]);
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    private static byte[] readBytecode(Class<?> clazz) {
        String resourceName = clazz.getName().replace('.', '/') + ".class";
        ClassLoader loader = clazz.getClassLoader();
        if (loader == null) {
            return null;
        }
        try (InputStream in = loader.getResourceAsStream(resourceName)) {
            if (in == null) {
                return null;
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Pass 1 (within one method): tracks straight-line {@code new X(...)} argument lists,
     * watching for the source field being read as one of the (atomic, untransformed) arguments.
     * A {@code this.field} read is two bytecode instructions (ALOAD 0, then GETFIELD) that must
     * be treated as a single atomic push, not two - {@code pendingObjectRefSlot} defers exactly
     * that decision by one instruction. Invalidated - abandoning the current {@code new}
     * expression entirely, never partially - by any non-atomic argument (a method call other
     * than the matching constructor invocation itself, an array/arithmetic op, etc).
     *
     * <p>A branch anywhere in the method disqualifies every match found in it, not just ones
     * textually inside the branch: an if/else could construct the same target type differently
     * in each arm, so "this call site looks like a clean passthrough" isn't trustworthy unless
     * the whole method is straight-line. Matches are buffered in {@code localFound} and only
     * copied into the shared result list from {@link #visitEnd()}, once the whole method has
     * been seen with no branch anywhere.
     */
    private static final class PassthroughDetector extends MethodVisitor {
        private final String sourceOwnerInternalName;
        private final String sourceFieldName;
        private final List<ConstructorCallSite> found;
        private final List<ConstructorCallSite> localFound = new ArrayList<>();
        private boolean methodHasBranch;

        private String pendingNewType;
        private boolean collectingArgs;
        private int argCount;
        private boolean sawSource;
        private int sourceArgPosition = -1;
        private boolean aborted;
        private int pendingObjectRefSlot = -1;

        PassthroughDetector(String sourceOwnerInternalName, String sourceFieldName, List<ConstructorCallSite> found) {
            super(Opcodes.ASM9);
            this.sourceOwnerInternalName = sourceOwnerInternalName;
            this.sourceFieldName = sourceFieldName;
            this.found = found;
        }

        @Override
        public void visitEnd() {
            if (!methodHasBranch) {
                found.addAll(localFound);
            }
        }

        private void flushPendingLoad() {
            if (pendingObjectRefSlot != -1) {
                argCount++; // a bare object reference load, never followed by GETFIELD - its own atomic argument
                pendingObjectRefSlot = -1;
            }
        }

        private void reset() {
            pendingNewType = null;
            collectingArgs = false;
            argCount = 0;
            sawSource = false;
            sourceArgPosition = -1;
            aborted = false;
            pendingObjectRefSlot = -1;
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            if (opcode != Opcodes.NEW) {
                if (collectingArgs) {
                    flushPendingLoad();
                    aborted = true; // e.g. CHECKCAST/ANEWARRAY/INSTANCEOF mid-argument-list
                }
                return;
            }
            if (collectingArgs) {
                flushPendingLoad();
                aborted = true; // a nested "new" while already collecting an outer call's args
                return;
            }
            pendingNewType = type;
        }

        @Override
        public void visitInsn(int opcode) {
            if (opcode == Opcodes.DUP && pendingNewType != null && !collectingArgs) {
                collectingArgs = true;
                argCount = 0;
                sawSource = false;
                sourceArgPosition = -1;
                aborted = false;
                pendingObjectRefSlot = -1;
                return;
            }
            if (!collectingArgs) {
                return;
            }
            flushPendingLoad();
            if (aborted) {
                return;
            }
            if (ATOMIC_ZERO_OPERAND_PUSHES.contains(opcode)) {
                argCount++;
            } else {
                aborted = true;
            }
        }

        @Override
        public void visitVarInsn(int opcode, int var) {
            if (!collectingArgs) {
                return;
            }
            flushPendingLoad();
            if (aborted) {
                return;
            }
            if (opcode == Opcodes.ALOAD) {
                pendingObjectRefSlot = var; // might be immediately consumed by a following GETFIELD
            } else if (opcode >= Opcodes.ILOAD && opcode < Opcodes.ALOAD) {
                argCount++; // ILOAD/LLOAD/FLOAD/DLOAD of a primitive local
            } else {
                aborted = true; // a STORE mid-argument-list - not straight-line enough
            }
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            if (!collectingArgs) {
                return;
            }
            if (opcode == Opcodes.GETFIELD && pendingObjectRefSlot != -1) {
                pendingObjectRefSlot = -1;
                if (!aborted) {
                    if (owner.equals(sourceOwnerInternalName) && name.equals(sourceFieldName)) {
                        sawSource = true;
                        sourceArgPosition = argCount;
                    }
                    argCount++;
                }
                return;
            }
            flushPendingLoad();
            if (aborted) {
                return;
            }
            if (opcode == Opcodes.GETSTATIC) {
                argCount++;
            } else {
                aborted = true; // PUTFIELD/PUTSTATIC/GETFIELD-without-a-pending-ref mid-argument-list
            }
        }

        @Override
        public void visitLdcInsn(Object value) {
            if (!collectingArgs) {
                return;
            }
            flushPendingLoad();
            if (!aborted) {
                argCount++;
            }
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            if (!collectingArgs) {
                return;
            }
            flushPendingLoad();
            if (aborted) {
                return;
            }
            if (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH) {
                argCount++;
            } else {
                aborted = true; // NEWARRAY
            }
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            if (!collectingArgs) {
                return;
            }
            if (opcode == Opcodes.INVOKESPECIAL && "<init>".equals(name) && owner.equals(pendingNewType)) {
                flushPendingLoad();
                if (!aborted && sawSource) {
                    localFound.add(new ConstructorCallSite(owner, descriptor, sourceArgPosition));
                }
                reset();
                return;
            }
            flushPendingLoad();
            aborted = true; // any other method call used to compute an argument
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            methodHasBranch = true;
            if (collectingArgs) {
                flushPendingLoad();
                aborted = true;
            }
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
            methodHasBranch = true;
        }

        @Override
        public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
            methodHasBranch = true;
        }
    }

    /**
     * Pass 2 (within the target constructor): watches for the tracked parameter slot being
     * loaded and immediately - no other instruction between - assigned to one of the
     * constructor's own fields via PUTFIELD. Any other instruction between the load and a
     * PUTFIELD (a transformation, anything) invalidates that particular load. Same as pass 1: a
     * branch anywhere in the constructor disqualifies a match found in it (the field could be
     * conditionally assigned something else in another arm), so the match is only committed to
     * the shared result from {@link #visitEnd()} once the whole constructor is seen branch-free.
     */
    private static final class DirectAssignmentDetector extends MethodVisitor {
        private final String targetInternalName;
        private final int trackedSlot;
        private final String[] result;
        private boolean pendingLoad;
        private String localMatch;
        private boolean methodHasBranch;

        DirectAssignmentDetector(String targetInternalName, int trackedSlot, String[] result) {
            super(Opcodes.ASM9);
            this.targetInternalName = targetInternalName;
            this.trackedSlot = trackedSlot;
            this.result = result;
        }

        @Override
        public void visitEnd() {
            if (!methodHasBranch && localMatch != null && result[0] == null) {
                result[0] = localMatch;
            }
        }

        @Override
        public void visitVarInsn(int opcode, int var) {
            pendingLoad = opcode == Opcodes.ALOAD && var == trackedSlot;
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            if (pendingLoad && opcode == Opcodes.PUTFIELD && owner.equals(targetInternalName) && localMatch == null) {
                localMatch = name;
            }
            pendingLoad = false;
        }

        @Override
        public void visitInsn(int opcode) {
            pendingLoad = false;
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            pendingLoad = false;
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            pendingLoad = false;
            methodHasBranch = true;
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
            methodHasBranch = true;
        }

        @Override
        public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
            methodHasBranch = true;
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            pendingLoad = false;
        }

        @Override
        public void visitLdcInsn(Object value) {
            pendingLoad = false;
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            pendingLoad = false;
        }
    }
}
