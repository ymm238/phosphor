package edu.columbia.cs.psl.phosphor;

import edu.columbia.cs.psl.phosphor.runtime.StringUtils;

import java.security.ProtectionDomain;

/* Provides appropriate phosphor tagged versions of transform. */
public abstract class PhosphorBaseTransformer  {

    public static boolean INITED = false;
    protected static int isBusyTransforming = 0;


    public byte[] signalAndTransform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain,
                                      byte[] classFileBuffer) {
        if(className != null && StringUtils.startsWith(className, "sun") && !StringUtils.startsWith(className, "sun/nio")) {
            // Avoid instrumenting dynamically generated accessors for reflection
            return classFileBuffer;
        }
        try {
            synchronized(PhosphorBaseTransformer.class) {
                isBusyTransforming++;
            }
            return transform(loader, className, classBeingRedefined, protectionDomain, classFileBuffer);
        } finally {
            synchronized(PhosphorBaseTransformer.class) {
                isBusyTransforming--;
            }
        }
    }

    protected abstract byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classFileBuffer);
}
