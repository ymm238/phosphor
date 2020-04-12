package edu.columbia.cs.psl.phosphor;

import org.objectweb.asm.Attribute;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ModuleHashesAttribute;
import org.objectweb.asm.commons.ModuleResolutionAttribute;
import org.objectweb.asm.commons.ModuleTargetAttribute;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.ModuleExportNode;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class JVMInstrumenter {
    // jmod magic number and version number, taken from java.base/jdk/internal/jmod/JmodFile.java
    private static final int JMOD_MAJOR_VERSION = 0x01;
    private static final int JMOD_MINOR_VERSION = 0x00;
    public static final byte[] JMOD_MAGIC_NUMBER = {
            0x4A, 0x4D, /* JM */
            JMOD_MAJOR_VERSION, JMOD_MINOR_VERSION, /* version 1.0 */
    };

    public static void main(String[] args) throws IOException {
//        String dir = "/Users/jon/Documents/GMU/Projects/phosphor/Phosphor/jmods-to-inst/";
        String dir = "/Library/Java/JavaVirtualMachines/jdk-14-fastdebug.jdk/Contents/Home/jmods/";
        String outDir = "/Users/jon/Documents/GMU/Projects/phosphor/jmods-inst/";
        //First: instrument the jmods
        boolean onlyDoJavaBase = true;
        if(args.length == 0) {
            onlyDoJavaBase = false;
            System.out.println("Inst: " + dir);
            Instrumenter.main(new String[]{dir, outDir});
        }
        HashMap<String, byte[]> checkSums = new HashMap<>();
        LinkedList<Module> notReady = new LinkedList<>();
        Files.list(Paths.get(outDir)).forEach((path -> {
            if (path.getFileName().toString().endsWith("jmod")) {
                Module mod = new Module(path);
                if (mod.classNode != null) {
                    notReady.add(mod);
                } else {
                    System.err.println("Error: couldn't find module-info.class in " + path.getFileName());
                }
            }
        }));
        int iters = 0;
        while (!notReady.isEmpty() && iters < 200) {
            Iterator<Module> iter = notReady.iterator();
            while (iter.hasNext()) {
                Module mod = iter.next();
                if(onlyDoJavaBase){
                    if(mod.classNode.module.name.equals("java.base")) {
                        mod.updateHashesOnDisk();
                    }
                    iter.remove();
                }
                else if (mod.updateHashes(checkSums)) {
                    System.out.println("Updated: " + mod.path.getFileName() + " " + bytesToHex(mod.hash) + " remaining: " + notReady.size());
                    iter.remove();
                }
            }
            iters++;
        }
    }

    static class Module {
        byte[] buf;
        byte[] hash;
        ClassNode classNode;
        Path path;
        ModuleHashesAttribute hashes;


        public Module(Path module) {
            this.path = module;
            reloadFromDisk();
        }

        public boolean updateHashes(Map<String, byte[]> calculatedHashes) {
            HashSet<String> missing = new HashSet<>();
            /*
            For each of the hashes we have, if it's also in calcualtedHashes, udpate it
            if this gets us all of them, save to file, set our hash, then return true
             */
            boolean readyToReHash = hashes == null;
            if (hashes != null) {
                readyToReHash = true;
                for (int i = 0; i < hashes.modules.size(); i++) {
                    String module = hashes.modules.get(i);
                    if (calculatedHashes.containsKey(module)) {
                        hashes.hashes.set(i, calculatedHashes.get(module));
                    } else {
                        missing.add(module);
                        readyToReHash = false;
                        //TODO re-enable for multiple module processing
                    }
                }
            }
            if (readyToReHash) {
                missing.remove(classNode.module.name);
                updateHashesOnDisk();
                reHash();
                calculatedHashes.put(classNode.module.name, hash);
                return true;
            }
            return false;
        }

        public void reloadFromDisk() {
            try {
                this.hashes = null;
                ZipFile zip = new ZipFile(path.toFile());
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    final ZipEntry e = entries.nextElement();
                    if (e.getName().endsWith("module-info.class")) {
                        this.buf = new byte[(int) e.getSize()];
                        InputStream is = zip.getInputStream(e);
                        is.read(this.buf);
                        is.close();
                        classNode = new ClassNode();
                        ClassReader cr = new ClassReader(buf);
                        List<Attribute> attrs = new ArrayList<>();
                        attrs.add(new ModuleTargetAttribute());
                        attrs.add(new ModuleResolutionAttribute());
                        attrs.add(new ModuleHashesAttribute());

                        cr.accept(classNode, attrs.toArray(new Attribute[0]), 0);
                        for (Attribute a : classNode.attrs) {
                            if (a instanceof ModuleHashesAttribute) {
                                this.hashes = (ModuleHashesAttribute) a;
                            }
                        }
                        break;
                    }
                }
                zip.close();
            } catch (IOException ex) {
                if (!(ex instanceof ZipException)) {
                    ex.printStackTrace();
                }
            }

        }

        public void updateHashesOnDisk() {
            try {

                boolean addPhosphor = this.classNode.module.name.equals("java.base");

                if (addPhosphor) {
                    Iterator<ModuleExportNode> exportedModulesIter = classNode.module.exports.iterator();
                    while(exportedModulesIter.hasNext()){
                        ModuleExportNode node = exportedModulesIter.next();
                        if(node.packaze.startsWith("edu/columbia")){
                            exportedModulesIter.remove();
                        }
                    }
                    //Add export
                    classNode.module.exports.add(new ModuleExportNode("edu/columbia/cs/psl/phosphor", 0, null));
                    classNode.module.exports.add(new ModuleExportNode("edu/columbia/cs/psl/phosphor/runtime", 0, null));
                    classNode.module.exports.add(new ModuleExportNode("edu/columbia/cs/psl/phosphor/struct", 0, null));

                    Iterator<String> packagesIter = classNode.module.packages.iterator();
                    while(packagesIter.hasNext()){
                        String node = packagesIter.next();
                        if(node.startsWith("edu/columbia")){
                            packagesIter.remove();
                        }
                    }
                    String packages = "edu.columbia.cs.psl.phosphor\n" +
                            "edu.columbia.cs.psl.phosphor.control\n" +
                            "edu.columbia.cs.psl.phosphor.control.graph\n" +
                            "edu.columbia.cs.psl.phosphor.control.standard\n" +
                            "edu.columbia.cs.psl.phosphor.control.type\n" +
                            "edu.columbia.cs.psl.phosphor.instrumenter\n" +
                            "edu.columbia.cs.psl.phosphor.instrumenter.analyzer\n" +
                            "edu.columbia.cs.psl.phosphor.instrumenter.asm\n" +
                            "edu.columbia.cs.psl.phosphor.org.apache.commons.cli\n" +
                            "edu.columbia.cs.psl.phosphor.org.objectweb.asm\n" +
                            "edu.columbia.cs.psl.phosphor.org.objectweb.asm.analysis\n" +
                            "edu.columbia.cs.psl.phosphor.org.objectweb.asm.commons\n" +
                            "edu.columbia.cs.psl.phosphor.org.objectweb.asm.signature\n" +
                            "edu.columbia.cs.psl.phosphor.org.objectweb.asm.tree\n" +
                            "edu.columbia.cs.psl.phosphor.org.objectweb.asm.tree.analysis\n" +
                            "edu.columbia.cs.psl.phosphor.org.objectweb.asm.util\n" +
                            "edu.columbia.cs.psl.phosphor.runtime\n" +
                            "edu.columbia.cs.psl.phosphor.runtime.proxied\n" +
                            "edu.columbia.cs.psl.phosphor.struct\n" +
                            "edu.columbia.cs.psl.phosphor.struct.harmony.util\n" +
                            "edu.columbia.cs.psl.phosphor.struct.multid";
                    for(String s: packages.split("\n")){
                        classNode.module.packages.add(s.replace('.','/'));
                    }
                }
                this.hash = null;
                ZipOutputStream zos;
                Path tempFile = Files.createTempFile("phosphor-" + path.getFileName(), "jmod");
                FileOutputStream os = new FileOutputStream(tempFile.toFile());
                zos = new ZipOutputStream(os);
                os.write(JMOD_MAGIC_NUMBER);

                ZipFile zip = new ZipFile(path.toFile());
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    final ZipEntry e = entries.nextElement();
                    if (e.getName().endsWith("module-info.class")) {
                        ZipEntry outEntry = new ZipEntry(e.getName());
                        zos.putNextEntry(outEntry);

                        ClassWriter cw = new ClassWriter(0);
                        classNode.accept(cw);
                        byte[] updated = cw.toByteArray();
                        zos.write(updated);
                        zos.closeEntry();

                        if (addPhosphor) {
                            //ALSO extract all of the classes from Phosphor, add to this jar.
                            Path phosphorJar = Paths.get("/Users/jon/Documents/GMU/Projects/phosphor/Phosphor/target/Phosphor-0.0.5-SNAPSHOT.jar");
                            ZipFile phosphorZip = new ZipFile(phosphorJar.toFile());
                            Enumeration<? extends ZipEntry> phosphorEntries = phosphorZip.entries();
                            while (phosphorEntries.hasMoreElements()) {
                                ZipEntry pe = phosphorEntries.nextElement();
                                if (pe.getName().endsWith(".class")) {
                                    outEntry = new ZipEntry("classes/" + pe.getName());
                                    zos.putNextEntry(outEntry);
                                    InputStream is = phosphorZip.getInputStream(pe);
                                    byte[] buffer = new byte[1024];
                                    while (true) {
                                        int count = is.read(buffer);
                                        if (count == -1) {
                                            break;
                                        }
                                        zos.write(buffer, 0, count);
                                    }
                                    is.close();
                                    zos.closeEntry();
                                } else if (e.isDirectory()) {
                                    outEntry = new ZipEntry("classes/" + e.getName());
                                    zos.putNextEntry(outEntry);
                                    zos.closeEntry();
                                }
                            }
                            phosphorZip.close();
                        }
                    } else {
                        //Copy as-is
                        ZipEntry outEntry = new ZipEntry(e.getName());
                        if(e.getName().startsWith("classes/edu/")){
                            //Don't copy any existing phosphor files
                            continue;
                        }
                        if (e.isDirectory()) {
                            try {
                                zos.putNextEntry(outEntry);
                                zos.closeEntry();
                            } catch (ZipException exxxx) {
                                System.out.println("Ignoring exception: " + exxxx.getMessage());
                            }
                        } else {
                            try {
                                zos.putNextEntry(outEntry);
                                InputStream is = zip.getInputStream(e);
                                byte[] buffer = new byte[1024];
                                while (true) {
                                    int count = is.read(buffer);
                                    if (count == -1) {
                                        break;
                                    }
                                    zos.write(buffer, 0, count);
                                }
                                is.close();
                                zos.closeEntry();
                            } catch (ZipException ex) {
//                                if(!ex.getMessage().contains("duplicate entry")) {
                                ex.printStackTrace();
                                System.out.println("Ignoring above warning from improper source zip...");
//                                }
                            }
                        }
                    }
                }
                zos.close();
                zip.close();
                Files.move(tempFile, this.path, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ex) {
                if (!(ex instanceof ZipException)) {
                    ex.printStackTrace();
                }
            }


        }

        public void reHash() {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (FileChannel fc = FileChannel.open(path)) {
                    ByteBuffer bb = ByteBuffer.allocate(32 * 1024);
                    while (fc.read(bb) > 0) {
                        bb.flip();
                        digest.update(bb);
                        assert bb.remaining() == 0;
                        bb.clear();
                    }
                }
                this.hash = digest.digest();
            } catch (NoSuchAlgorithmException | IOException e) {
                e.printStackTrace();
            }
        }
    }

    private static String bytesToHex(byte[] hash) {
        StringBuffer hexString = new StringBuffer();
        for (int i = 0; i < hash.length; i++) {
            String hex = Integer.toHexString(0xff & hash[i]);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }

}
