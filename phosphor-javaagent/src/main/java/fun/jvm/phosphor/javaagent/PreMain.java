package fun.jvm.phosphor.javaagent;

import edu.columbia.cs.psl.phosphor.Configuration;
import edu.columbia.cs.psl.phosphor.InstrumentationHelper;
import edu.columbia.cs.psl.phosphor.PhosphorBaseTransformer;
import edu.columbia.cs.psl.phosphor.control.ControlFlowStack;
import edu.columbia.cs.psl.phosphor.runtime.NonModifiableClassException;
import edu.columbia.cs.psl.phosphor.runtime.Taint;
import edu.columbia.cs.psl.phosphor.struct.LazyByteArrayObjTags;
import edu.columbia.cs.psl.phosphor.struct.TaintedReferenceWithObjTag;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.security.ProtectionDomain;

import static edu.columbia.cs.psl.phosphor.PhosphorBaseTransformer.INITED;

public class PreMain {
    static class PhosphorTransformerBridge implements ClassFileTransformer {
        private PhosphorBaseTransformer transformer;

        public PhosphorTransformerBridge(PhosphorBaseTransformer transformer) {
            this.transformer = transformer;
        }

        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
            try {
                return transformer.signalAndTransform(loader, className, classBeingRedefined, protectionDomain, classfileBuffer);
            } catch (Throwable t) {
                t.printStackTrace();
                throw t;
            }
        }

        @SuppressWarnings("unused")
        public TaintedReferenceWithObjTag transform$$PHOSPHORTAGGED(Taint refTaint, ClassLoader loader, Taint loaderTaint, String className, Taint classNameTaint, Class<?> classBeingRedefined,
                                                                    Taint classBeingRedefinedTaint, ProtectionDomain protectionDomain,
                                                                    Taint protectionDomainTaint, LazyByteArrayObjTags clazz,
                                                                    Taint clazzTaint,
                                                                    TaintedReferenceWithObjTag ret) {
            if (!INITED) {
                Configuration.IMPLICIT_TRACKING = false;
                Configuration.init();
                INITED = true;
            }
            ret.taint = Taint.emptyTaint();
            ret.val = LazyByteArrayObjTags.factory(null, transformer.signalAndTransform(loader, className, classBeingRedefined, protectionDomain, clazz.val));
            return ret;
        }

        @SuppressWarnings("unused")
        public TaintedReferenceWithObjTag transform$$PHOSPHORTAGGED(Taint refTaint, ClassLoader loader, Taint loaderTaint, String className, Taint classNameTaint, Class<?> classBeingRedefined,
                                                                    Taint classBeingRedefinedTaint, ProtectionDomain protectionDomain,
                                                                    Taint protectionDomainTaint, LazyByteArrayObjTags clazz,
                                                                    Taint clazzTaint,
                                                                    ControlFlowStack ctrl,
                                                                    TaintedReferenceWithObjTag ret) {
            if (!INITED) {
                Configuration.IMPLICIT_TRACKING = true;
                Configuration.init();
                INITED = true;
            }
            ret.taint = Taint.emptyTaint();
            ret.val = LazyByteArrayObjTags.factory(null, transformer.signalAndTransform(loader, className, classBeingRedefined, protectionDomain, clazz.val));
            return ret;
        }
    }

    public static void premain$$PHOSPHORTAGGED(String args, Taint argsTaint, final Instrumentation instr, Taint instrTaint) {
        premain(args, instr);
    }

    public static void premain(String args, final Instrumentation instr) {
        edu.columbia.cs.psl.phosphor.PreMain.premain(args, new InstrumentationHelper() {
            @Override
            public void addTransformer(final PhosphorBaseTransformer transformer) {
                instr.addTransformer(new PhosphorTransformerBridge(transformer));
            }

            @Override
            public Class<?>[] getAllLoadedClasses() {
                return instr.getAllLoadedClasses();
            }

            @Override
            public void addTransformer(PhosphorBaseTransformer transformer, boolean canRedefineClasses) {
                instr.addTransformer(new PhosphorTransformerBridge(transformer), canRedefineClasses);
            }

            @Override
            public void retransformClasses(Class<?> clazz) throws NonModifiableClassException {
                try {
                    instr.retransformClasses(clazz);
                } catch (UnmodifiableClassException e) {
                    throw new NonModifiableClassException(e);
                }
            }
        });
    }
}
