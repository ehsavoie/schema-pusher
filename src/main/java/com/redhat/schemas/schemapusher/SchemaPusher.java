package com.redhat.schemas.schemapusher;

//DEPS info.picocli:picocli:4.7.0
//DEPS com.hierynomus:sshj:0.34.0
//DEPS org.bouncycastle:bcpkix-jdk18on:1.72
//DEPS org.bouncycastle:bcprov-jdk18on:1.72
//DEPS org.bouncycastle:bcutil-jdk18on:1.72
//DEPS org.slf4j:slf4j-api:2.0.5
//DEPS org.slf4j:slf4j-reload4j:2.0.5
//DEPS ch.qos.reload4j:reload4j:1.2.24
//JAVA 11

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.RemoteResourceInfo;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.transport.verification.OpenSSHKnownHosts;
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;
import net.schmizz.sshj.xfer.FileSystemFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Option;

/**
 * Simple class to deploy the schemas
 * @author ehsavoie
 */
public class SchemaPusher implements Callable<Integer> {

    static final Logger logger = LoggerFactory.getLogger(SchemaPusher.class);

    @Option(names = {"-u", "--user"}, description = "User name", defaultValue = "schema")
    String user;

    @Option(names = {"-i", "--identity"}, description = "Private key", defaultValue = "~/.ssh/id_rsa")
    String privateKey;

    @Option(names = {"-h", "--host"}, description = "Host", defaultValue = "filemgmt-prod.jboss.org")
    String host;

    @Option(names = {"-n", "--port"}, description = "Port", defaultValue = "22")
    int port;

    @Option(names = {"-rd", "--remote-directory"}, description = "Remote Directory", defaultValue = "schema_htdocs/jbossas/")
    String remoteDirectory;

    @Option(names = {"-ld", "--local-directory"}, description = "Local Directory")
    File localDirectory;

    @ArgGroup(exclusive = true, multiplicity = "1")
    Exclusive exclusive;

    static class Exclusive {

        @Option(names = {"-p", "--password"}, description = "Passpword", interactive = true)
        char[] password;
        @Option(names = {"-ip", "--passphrase"}, description = "Passphrase", interactive = true)
        char[] passphrase;
    }

    public static void main(String[] args) throws Exception {
        logger.atInfo().log("Let's push some files !!!!");
        new CommandLine(new SchemaPusher()).execute(args);
//        new CommandLine(new SchemaPusher()).execute("-ip", "--local-directory=/home/ehsavoie/dev/metadata");
    }

    private SSHClient setupSshj() throws IOException {
        SSHClient client = new SSHClient();
        client.addHostKeyVerifier(new OpenSSHKnownHosts(new File(replaceHome("~/.ssh/known_hosts"))));
        if (exclusive.passphrase != null) {
            KeyProvider keys = client.loadKeys(replaceHome(privateKey), exclusive.passphrase);
            client.connect(host, port);
            client.authPublickey(user, keys);
        } else {
            client.connect(host, port);
            client.authPassword(user, exclusive.password);
        }
        return client;
    }

    /**
     * List XML schemas and dtd files in a path.
     *
     * @throws IOException
     */
    private Collection<Path> listLocalFiles() throws IOException {
        final Path rootPath = localDirectory.toPath();
        List<Path> result = new ArrayList<>();
        if (Files.exists(rootPath)) {
            Files.walkFileTree(rootPath, new FileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if(dir.endsWith("test") || dir.endsWith("test-classes") || dir.endsWith("generated-test-resources")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String fileName = file.getFileName().toString();
                    if (Files.isRegularFile(file) && (fileName.endsWith(".xsd") || fileName.endsWith(".dtd"))) {
                        result.add(file);
                        logger.atInfo().log("Local file: " + fileName);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return result;
    }

    private Set<String> listRemoteFiles(SFTPClient sftpClient) throws IOException {
        Set<String> remoteFiles = new HashSet<>();
        List<RemoteResourceInfo> files = sftpClient.ls(remoteDirectory);
        for (RemoteResourceInfo file : files) {
            logger.atInfo().log("Remote file: " + file.getName());
            remoteFiles.add(file.getName());
        }
        return remoteFiles;
    }

    private String replaceHome(String path) {
        if (path != null && path.startsWith("~")) {
            return path.replace("~", System.getProperty("user.home"));
        }
        return path;
    }

    @Override
    public Integer call() throws Exception {
        SSHClient sshClient = null;
        try {
            Collection<Path> localFiles = listLocalFiles();
            sshClient = setupSshj();
            try (SFTPClient sftpClient = sshClient.newSFTPClient()) {
                Set<String> remoteFiles = listRemoteFiles(sftpClient);
                uploadFiles(sftpClient, localFiles, remoteFiles);
            }
            return 0;
        } finally {
            if (sshClient != null) {
                sshClient.disconnect();
            }
        }
    }

    private void uploadFiles(SFTPClient sftpClient, Collection<Path> localFiles, Set<String> remoteFiles) throws IOException {
        Set<String> uploadedFiles = new HashSet<>();
        for (Path localFile : localFiles) {
            String localFileName = localFile.getFileName().toString();
            if (!remoteFiles.contains(localFileName) && !uploadedFiles.contains(localFileName)) {
                logger.atInfo().log("Uploading file: " + localFile);
                sftpClient.put(new FileSystemFile(localFile.toFile()), remoteDirectory + localFileName);
                uploadedFiles.add(localFileName);
                logger.atInfo().log("File " + localFileName + " uploaded");
            } else {
                logger.atInfo().log("File already present " + localFileName);
            }
        }
        uploadedFiles.addAll(remoteFiles);
        uploadedFiles.remove("index.html");
        File index = createIndex(new ArrayList<>(uploadedFiles));
        sftpClient.put(new FileSystemFile(index), remoteDirectory + "index.html");
    }

    private File createIndex(List<String> files) throws IOException {
        Collections.sort(files);
        Path index = localDirectory.toPath().resolve("index.html");
        Files.deleteIfExists(index);
        try (BufferedWriter writer
                = Files.newBufferedWriter(index, StandardCharsets.UTF_8)) {
            writer.append("<!DOCTYPE html>");
            writer.newLine();
            writer.append("<html>");
            writer.newLine();
            writer.append("<head>");
            writer.newLine();
            writer.append("<meta charset=\"utf-8\"/>");
            writer.newLine();
            writer.append("</head>");
            writer.newLine();
            writer.append("<body>");
            writer.newLine();
            writer.append("<ul>");
            writer.newLine();
            for (String file : files) {
                writer.append("<li><a href=\"");
                writer.append(file);
                writer.append("\">");
                writer.append(file);
                writer.append("</a></li>");
                writer.newLine();
            }
            writer.append("</ul>");
            writer.newLine();
            writer.append("</body>");
            writer.newLine();
            writer.append("</html>");
            writer.newLine();
        }
        return index.toFile();
    }
}
