package name.abuchen.portfolio.snapshot.lookthrough;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Explicitly injected, dedicated local cache directory, never a portfolio path.
 * The application creates that directory with private permissions in plugin
 * state and owns exactly one store for it. Construction does not access disk.
 * Uses portable Files operations within that application-owned private directory.
 * Explicit component/link checks reject traversal and observed directory aliases;
 * record opens do not follow links. These path-based checks are NOT race-proof
 * against a hostile local process replacing directories between checks and use.
 * The application must control the directory and its ancestors throughout use.
 * POSIX file permissions are applied where supported; other systems inherit the
 * private application's directory permissions/ACLs. Atomic publication is required:
 * unsupported atomic moves select DISK_FAILURE without a non-atomic fallback.
 *
 * Records contain only composition models, instrument classification evidence
 * or answers without composition, schema/validation versions and an integrity digest (not authentication
 * against a malicious local user). Account and fund filenames are
 * length-delimited hashes, not identifiers as paths.
 * Atomic rename publishes complete records. No raw payloads, credentials,
 * portfolio objects or derived exposure results are serialized.
 * Policy changes/deletion and commit share the inherited monitor; tickets from
 * an earlier policy generation cannot repopulate disk even after re-enabling.
 */
public final class VersionedCompositionStore extends SessionCompositionStore
{
    private static final String EXTENSION = ".composition"; //$NON-NLS-1$
    private static final String EVIDENCE_EXTENSION = ".evidence"; //$NON-NLS-1$
    private static final String ABSENCE_EXTENSION = ".absent"; //$NON-NLS-1$
    private final Path directory;
    private final FileOperations files;
    private final Map<String, CacheStatus> discarded = new HashMap<>();

    public VersionedCompositionStore(Path dedicatedCacheDirectory, Clock clock)
    {
        this(dedicatedCacheDirectory, clock, new FileOperations()
        {
        });
    }

    /** Small package-local seam for portable filesystem capability/failure tests. */
    interface FileOperations
    {
        default DirectoryStream<Path> list(Path directory) throws IOException
        {
            return Files.newDirectoryStream(directory);
        }

        default boolean supportsPosix(Path directory)
        {
            return Files.getFileAttributeView(directory, PosixFileAttributeView.class,
                            LinkOption.NOFOLLOW_LINKS) != null;
        }

        default void atomicReplace(Path staging, Path destination) throws IOException
        {
            Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    VersionedCompositionStore(Path dedicatedCacheDirectory, Clock clock, FileOperations files)
    {
        super(clock);
        this.files = Objects.requireNonNull(files);
        directory = Objects.requireNonNull(dedicatedCacheDirectory).toAbsolutePath();
        if (!directory.equals(directory.normalize()) || directory.getNameCount() == 0)
            throw new IllegalArgumentException();
    }

    @Override
    protected boolean supportsPersistence()
    {
        return true;
    }

    private void checkDirectory() throws IOException
    {
        var component = directory.getRoot();
        for (var name : directory)
        {
            component = component.resolve(name);
            var attributes = Files.readAttributes(component, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()
                            || !component.toRealPath().equals(component))
                throw new IOException();
        }
    }

    private ArrayList<Path> ownedFiles(ProviderAccount account) throws IOException
    {
        checkDirectory();
        var paths = new ArrayList<Path>();
        try (var stream = files.list(directory))
        {
            for (var item : stream)
            {
                if (!directory.equals(item.toAbsolutePath().normalize().getParent()))
                    throw new IOException();
                if (owned(item.getFileName().toString(), account))
                    paths.add(item);
            }
        }
        return paths;
    }

    private static String hash(Map<String, String> values)
    {
        var text = new StringBuilder();
        for (var entry : new TreeMap<>(values).entrySet())
            text.append(entry.getKey().length()).append(':').append(entry.getKey())
                            .append(entry.getValue().length()).append(':').append(entry.getValue());
        return HexFormat.of().formatHex(CompositionCacheCodec.digest(
                        text.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static String prefix(ProviderAccount account)
    {
        return hash(Map.of("provider", account.providerId(), "account", account.accountId())) + "-"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static String filename(ProviderAccount account, CompositionInstrument fund)
    {
        return prefix(account) + hash(fund.identifiers()) + EXTENSION;
    }

    private static String evidenceFilename(ProviderAccount account, Map<String, String> identifiers)
    {
        return prefix(account) + hash(identifiers) + EVIDENCE_EXTENSION;
    }

    private static String absenceFilename(ProviderAccount account, Map<String, String> identifiers)
    {
        return prefix(account) + hash(identifiers) + ABSENCE_EXTENSION;
    }

    private static boolean owned(String name, ProviderAccount account)
    {
        if (!name.startsWith(prefix(account)))
            return false;
        var suffix = name.substring(65);
        return suffix.matches("[a-f0-9]{64}\\.composition") //$NON-NLS-1$
                        || suffix.matches("[a-f0-9]{64}\\.evidence") //$NON-NLS-1$
                        || suffix.matches("[a-f0-9]{64}\\.absent") //$NON-NLS-1$
                        || suffix.matches("[a-f0-9-]{36}\\.pending"); //$NON-NLS-1$
    }

    private byte[] readBytes(Path path) throws IOException
    {
        checkDirectory();
        var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.size() > CompositionCacheCodec.MAX_BYTES)
            throw new IOException();
        try (var channel = Files.newByteChannel(path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)))
        {
            var output = new ByteArrayOutputStream();
            var buffer = ByteBuffer.allocate(8192);
            while (channel.read(buffer) != -1)
            {
                buffer.flip();
                if (output.size() + buffer.remaining() > CompositionCacheCodec.MAX_BYTES)
                    throw new IOException();
                output.write(buffer.array(), 0, buffer.remaining());
                buffer.clear();
            }
            return output.toByteArray();
        }
    }

    private void deleteFile(Path path) throws IOException
    {
        checkDirectory();
        try
        {
            // Unlinks a symlink itself, never its target. Directories are refused.
            var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() && !attributes.isSymbolicLink())
                throw new IOException();
            Files.delete(path);
        }
        catch (NoSuchFileException e)
        {
            // Already absent is safe.
        }
    }

    @Override
    protected void deleteDisk(ProviderAccount account) throws IOException
    {
        try
        {
            for (var path : ownedFiles(account))
                deleteFile(path);
        }
        catch (NoSuchFileException e)
        {
            // A missing dedicated directory contains no retained data.
        }
        discarded.keySet().removeIf(name -> owned(name, account));
    }

    @Override
    protected void pruneDisk(CompositionPersistencePolicy policy) throws IOException
    {
        for (var path : ownedFiles(policy.account()))
        {
            var name = path.getFileName().toString();
            var status = CacheStatus.DISCARDED;
            try
            {
                if (name.endsWith(EVIDENCE_EXTENSION))
                {
                    var stored = CompositionCacheCodec.decodeEvidence(readBytes(path));
                    if (!stored.account().equals(policy.account())
                                    || !evidenceFilename(policy.account(), stored.key()).equals(name))
                        throw new IOException();
                    if (!expired(stored.storedAt(), policy))
                        continue;
                    deleteFile(path);
                    continue;
                }
                if (name.endsWith(ABSENCE_EXTENSION))
                {
                    var stored = CompositionCacheCodec.decodeAbsence(readBytes(path));
                    if (!stored.account().equals(policy.account())
                                    || !absenceFilename(policy.account(), stored.key()).equals(name))
                        throw new IOException();
                    if (!expired(stored.storedAt(), policy))
                        continue;
                    deleteFile(path);
                    continue;
                }
                var source = CompositionCacheCodec.decode(readBytes(path));
                if (!source.provenance().source().equals(policy.account())
                                || !filename(policy.account(), source.provenance().fund()).equals(name)
                                || CompositionCacheCodec.validate(source).isEmpty())
                    throw new IOException();
                if (!expired(source, policy))
                    continue;
                status = CacheStatus.EXPIRED;
            }
            catch (IOException e)
            {
                // Includes interrupted staging, corrupt/incompatible records and links.
            }
            deleteFile(path);
            discarded.put(name, status);
        }
    }

    @Override
    protected Snapshot readDisk(ProviderAccount account, CompositionInstrument fund) throws IOException
    {
        var name = filename(account, fund);
        var path = directory.resolve(name);
        try
        {
            var source = CompositionCacheCodec.decode(readBytes(path));
            var validation = CompositionCacheCodec.validate(source);
            if (!source.provenance().source().equals(account)
                            || !source.provenance().fund().identifiers().equals(fund.identifiers())
                            || validation.isEmpty())
                throw new IOException();
            return new Snapshot(validation, Optional.empty(), State.PERSISTENT, CacheStatus.AVAILABLE);
        }
        catch (NoSuchFileException e)
        {
            return new Snapshot(Optional.empty(), Optional.empty(), State.PERSISTENT,
                            discarded.getOrDefault(name, CacheStatus.MISS));
        }
        catch (IOException e)
        {
            deleteFile(path);
            return new Snapshot(Optional.empty(), Optional.empty(), State.PERSISTENT, CacheStatus.DISCARDED);
        }
    }

    @Override
    protected void writeDisk(FundComposition composition, CompositionPersistencePolicy policy) throws IOException
    {
        var bytes = CompositionCacheCodec.encode(composition);
        var account = composition.provenance().source();
        var destination = directory.resolve(filename(account, composition.provenance().fund()));
        if (publish(bytes, account, destination, !expired(composition, policy)))
            discarded.remove(destination.getFileName().toString());
    }

    @Override
    protected Optional<List<InstrumentEvidence>> readEvidenceDisk(ProviderAccount account,
                    Map<String, String> identifiers, CompositionPersistencePolicy policy) throws IOException
    {
        var path = directory.resolve(evidenceFilename(account, identifiers));
        try
        {
            var stored = CompositionCacheCodec.decodeEvidence(readBytes(path));
            if (!stored.account().equals(account) || !stored.key().equals(identifiers))
                throw new IOException();
            if (expired(stored.storedAt(), policy))
            {
                deleteFile(path);
                return Optional.empty();
            }
            return Optional.of(stored.evidence());
        }
        catch (NoSuchFileException e)
        {
            return Optional.empty();
        }
        catch (IOException e)
        {
            // Corrupt or incompatible records are dropped and fetched again.
            deleteFile(path);
            return Optional.empty();
        }
    }

    @Override
    protected void writeEvidenceDisk(ProviderAccount account, Map<String, String> identifiers,
                    List<InstrumentEvidence> evidence, CompositionPersistencePolicy policy) throws IOException
    {
        var bytes = CompositionCacheCodec
                        .encodeEvidence(new CompositionCacheCodec.StoredEvidence(account, identifiers, clock.instant(),
                                        evidence));
        publish(bytes, account, directory.resolve(evidenceFilename(account, identifiers)), true);
    }

    @Override
    protected Optional<NoComposition> readNoCompositionDisk(ProviderAccount account, Map<String, String> identifiers,
                    CompositionPersistencePolicy policy) throws IOException
    {
        var path = directory.resolve(absenceFilename(account, identifiers));
        try
        {
            var stored = CompositionCacheCodec.decodeAbsence(readBytes(path));
            if (!stored.account().equals(account) || !stored.key().equals(identifiers))
                throw new IOException();
            if (expired(stored.storedAt(), policy))
            {
                deleteFile(path);
                return Optional.empty();
            }
            return Optional.of(new NoComposition(stored.storedAt(), stored.status()));
        }
        catch (NoSuchFileException e)
        {
            return Optional.empty();
        }
        catch (IOException e)
        {
            // Corrupt or incompatible records are dropped and asked again.
            deleteFile(path);
            return Optional.empty();
        }
    }

    @Override
    protected void writeNoCompositionDisk(ProviderAccount account, Map<String, String> identifiers,
                    CompositionResult.Status status, CompositionPersistencePolicy policy) throws IOException
    {
        var bytes = CompositionCacheCodec.encodeAbsence(
                        new CompositionCacheCodec.StoredAbsence(account, identifiers, clock.instant(), status));
        publish(bytes, account, directory.resolve(absenceFilename(account, identifiers)), true);
    }

    /** Stages the record privately and publishes it atomically; returns whether it was published. */
    private boolean publish(byte[] bytes, ProviderAccount account, Path destination, boolean retained)
                    throws IOException
    {
        var staging = directory.resolve(prefix(account) + UUID.randomUUID() + ".pending"); //$NON-NLS-1$
        boolean created = false;
        checkDirectory();
        try
        {
            var permissions = files.supportsPosix(directory)
                            ? new FileAttribute<?>[] { PosixFilePermissions
                                            .asFileAttribute(PosixFilePermissions.fromString("rw-------")) } //$NON-NLS-1$
                            : new FileAttribute<?>[0];
            try (var channel = FileChannel.open(staging,
                            Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                            permissions))
            {
                created = true;
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining())
                    channel.write(buffer);
                channel.force(true);
            }
            checkDirectory();
            // Retention is rechecked immediately before atomic publication as well.
            if (!retained)
                return false;
            files.atomicReplace(staging, destination);
            return true;
        }
        catch (UnsupportedOperationException e)
        {
            throw new IOException();
        }
        finally
        {
            if (created)
                deleteFile(staging);
        }
    }
}
