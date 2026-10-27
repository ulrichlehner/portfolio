package name.abuchen.portfolio.snapshot.lookthrough;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryIteratorException;
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
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import name.abuchen.portfolio.snapshot.lookthrough.CompositionPersistencePolicy.StorageMode;

/**
 * Cache of company attributes keyed by ISIN, with one versioned file per
 * source in a dedicated private directory. Register records are openly
 * licensed and always stored. Provider profiles follow the account's
 * persistence policy: they are written only while its effective mode is
 * persistent, and otherwise kept in memory for the session. Withdrawing
 * persistence deletes the account's profile file; a lookup started under an
 * earlier configuration generation can never be written afterwards. A stored
 * answer without data ("not found") is cached like data, so it is not asked
 * again before it is due.
 * <p>
 * Changes are kept in memory and written by {@link #flush()}, so a long
 * enrichment pass does not rewrite the file per company. Files contain only
 * the attributes, retrieval dates, a schema version and an integrity digest.
 * Without a directory, or after a disk failure, the cache is memory-only.
 */
public final class CompanyEnrichmentCache
{
    /** A cached answer; an empty value is a stored "not found". */
    public record Cached<T>(Optional<T> value, Instant retrievedAt)
    {
        public Cached
        {
            Objects.requireNonNull(value);
            Objects.requireNonNull(retrievedAt);
        }
    }

    private static final class Profiles
    {
        private CompositionPersistencePolicy policy;
        private Optional<Boolean> preference;
        private boolean persistent;
        private boolean loaded;
        private boolean dirty;
        private long generation;
        private final Map<String, Cached<CompanyProfile>> entries = new HashMap<>();
        /** ISINs whose entry may be written: loaded or fetched in the current persistent generation. */
        private final Set<String> storable = new HashSet<>();
    }

    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final int MAX_ENTRIES = 200000;
    private static final int MAX_TEXT = 1024;
    private static final String REGISTER_FILE = "register.companies"; //$NON-NLS-1$
    private static final String EXTENSION = ".companies"; //$NON-NLS-1$

    private final Path directory;
    private final Clock clock;
    private final Map<String, Cached<RegisterRecord>> register = new HashMap<>();
    private final Map<ProviderAccount, Profiles> accounts = new HashMap<>();
    private boolean registerLoaded;
    private boolean registerDirty;
    private boolean diskFailure;

    /**
     * @param directory
     *            dedicated private cache directory, or null for memory only
     */
    public CompanyEnrichmentCache(Path directory, Clock clock)
    {
        this.clock = Objects.requireNonNull(clock);
        this.directory = directory == null ? null : directory.toAbsolutePath().normalize();
    }

    private boolean disk()
    {
        return directory != null && !diskFailure;
    }

    private void failed()
    {
        diskFailure = true;
    }

    /**
     * Applies the account's persistence policy and preference to its provider
     * profiles. Each change starts a new generation; repeating the current
     * configuration changes nothing. When the effective mode is not
     * persistent, the account's profile file is deleted while the session
     * keeps its profiles in memory.
     */
    public synchronized void configure(CompositionPersistencePolicy policy, Optional<Boolean> preference)
    {
        Objects.requireNonNull(policy);
        Objects.requireNonNull(preference);
        var profiles = accounts.computeIfAbsent(policy.account(), ignored -> new Profiles());
        if (policy.equals(profiles.policy) && preference.equals(profiles.preference))
            return;
        var wasPersistent = profiles.persistent;
        profiles.generation++;
        profiles.policy = policy;
        profiles.preference = preference;
        profiles.persistent = policy.effectiveMode(preference) == StorageMode.PERSISTENT;
        if (!profiles.persistent)
        {
            // Entries fetched before stay in memory only, also after a later re-enable.
            profiles.storable.clear();
            profiles.loaded = false;
            profiles.dirty = false;
            if (disk())
            {
                try
                {
                    deleteFile(profileFile(policy.account()));
                }
                catch (IOException | SecurityException | DirectoryIteratorException e)
                {
                    failed();
                }
            }
            return;
        }
        if (!wasPersistent)
            profiles.storable.clear();
        if (!profiles.loaded && disk())
        {
            profiles.loaded = true;
            loadProfiles(policy.account(), profiles);
        }
        expireProfiles(profiles);
    }

    /** Generation of the account's configuration; capture it before a profile request. */
    public synchronized long generation(ProviderAccount account)
    {
        var profiles = accounts.get(Objects.requireNonNull(account));
        return profiles == null ? 0 : profiles.generation;
    }

    private boolean expired(Instant retrieved, CompositionPersistencePolicy policy)
    {
        var now = clock.instant();
        return retrieved.isAfter(now) || policy != null && policy.maximumAge()
                        .map(age -> Duration.between(retrieved, now).compareTo(age) >= 0).orElse(false);
    }

    private void expireProfiles(Profiles profiles)
    {
        var removed = profiles.entries.entrySet().removeIf(item -> expired(item.getValue().retrievedAt(),
                        profiles.policy));
        profiles.storable.retainAll(profiles.entries.keySet());
        if (removed && profiles.persistent)
            profiles.dirty = true;
    }

    public synchronized Optional<Cached<CompanyProfile>> profile(ProviderAccount account, String isin)
    {
        var profiles = accounts.computeIfAbsent(Objects.requireNonNull(account), ignored -> new Profiles());
        var cached = profiles.entries.get(Objects.requireNonNull(isin));
        if (cached != null && expired(cached.retrievedAt(), profiles.policy))
        {
            profiles.entries.remove(isin);
            if (profiles.storable.remove(isin))
                profiles.dirty = true;
            return Optional.empty();
        }
        return Optional.ofNullable(cached);
    }

    /**
     * Keeps a provider answer in memory and marks it for storage when the
     * account is persistent and its configuration is still the generation
     * captured before the request.
     */
    public synchronized void putProfile(ProviderAccount account, String isin, Optional<CompanyProfile> profile,
                    long generation)
    {
        Objects.requireNonNull(isin);
        if (profile.filter(value -> !value.isin().equals(isin)).isPresent())
            throw new IllegalArgumentException();
        var profiles = accounts.computeIfAbsent(Objects.requireNonNull(account), ignored -> new Profiles());
        var retrieved = profile.map(CompanyProfile::retrievedAt).orElseGet(clock::instant);
        profiles.entries.put(isin, new Cached<>(profile, retrieved));
        if (profiles.persistent && profiles.generation == generation)
        {
            profiles.storable.add(isin);
            profiles.dirty = true;
        }
        else
        {
            profiles.storable.remove(isin);
        }
    }

    public synchronized Optional<Cached<RegisterRecord>> register(String isin)
    {
        loadRegister();
        return Optional.ofNullable(register.get(Objects.requireNonNull(isin)));
    }

    public synchronized void putRegister(String isin, Optional<RegisterRecord> record)
    {
        Objects.requireNonNull(isin);
        if (record.filter(value -> !value.isin().equals(isin)).isPresent())
            throw new IllegalArgumentException();
        loadRegister();
        register.put(isin, new Cached<>(record, record.map(RegisterRecord::retrievedAt).orElseGet(clock::instant)));
        registerDirty = true;
    }

    /** Writes changed files atomically. Disk failures make the cache memory-only. */
    public synchronized void flush()
    {
        if (!disk())
            return;
        try
        {
            if (registerDirty)
            {
                write(directory.resolve(REGISTER_FILE), encodeRegister());
                registerDirty = false;
            }
            for (var item : accounts.entrySet())
            {
                var profiles = item.getValue();
                if (!profiles.persistent || !profiles.dirty)
                    continue;
                write(profileFile(item.getKey()), encodeProfiles(item.getKey(), profiles));
                profiles.dirty = false;
            }
        }
        catch (IOException | SecurityException | UnsupportedOperationException | DirectoryIteratorException e)
        {
            failed();
        }
    }

    private Path profileFile(ProviderAccount account)
    {
        var key = account.providerId().length() + ":" + account.providerId() + account.accountId(); //$NON-NLS-1$
        return directory.resolve("profiles-" //$NON-NLS-1$
                        + HexFormat.of().formatHex(digest(key.getBytes(StandardCharsets.UTF_8))) + EXTENSION);
    }

    private void loadRegister()
    {
        if (registerLoaded)
            return;
        registerLoaded = true;
        if (!disk())
            return;
        var path = directory.resolve(REGISTER_FILE);
        try
        {
            var loaded = decodeRegister(read(path));
            loaded.putAll(register);
            register.clear();
            register.putAll(loaded);
        }
        catch (NoSuchFileException e)
        {
            // Nothing stored yet.
        }
        catch (IOException | RuntimeException e)
        {
            // Corrupt or incompatible files are dropped and fetched again.
            drop(path);
        }
    }

    private void loadProfiles(ProviderAccount account, Profiles profiles)
    {
        var path = profileFile(account);
        try
        {
            var loaded = decodeProfiles(account, read(path));
            for (var item : loaded.entrySet())
            {
                if (profiles.entries.putIfAbsent(item.getKey(), item.getValue()) == null)
                    profiles.storable.add(item.getKey());
            }
        }
        catch (NoSuchFileException e)
        {
            // Nothing stored yet.
        }
        catch (IOException | RuntimeException e)
        {
            drop(path);
        }
    }

    private void drop(Path path)
    {
        try
        {
            deleteFile(path);
        }
        catch (IOException | SecurityException e)
        {
            failed();
        }
    }

    private void checkDirectory() throws IOException
    {
        var attributes = Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || !directory.toRealPath().equals(directory))
            throw new IOException();
    }

    private byte[] read(Path path) throws IOException
    {
        checkDirectory();
        var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.size() > MAX_BYTES)
            throw new IOException();
        try (var channel = Files.newByteChannel(path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)))
        {
            var output = new ByteArrayOutputStream();
            var buffer = ByteBuffer.allocate(8192);
            while (channel.read(buffer) != -1)
            {
                buffer.flip();
                if (output.size() + buffer.remaining() > MAX_BYTES)
                    throw new IOException();
                output.write(buffer.array(), 0, buffer.remaining());
                buffer.clear();
            }
            return output.toByteArray();
        }
    }

    private void deleteFile(Path path) throws IOException
    {
        try
        {
            checkDirectory();
        }
        catch (NoSuchFileException e)
        {
            return;
        }
        try
        {
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

    private void write(Path destination, byte[] bytes) throws IOException
    {
        checkDirectory();
        var staging = directory.resolve(UUID.randomUUID() + ".pending"); //$NON-NLS-1$
        var permissions = Files.getFileAttributeView(directory, PosixFileAttributeView.class,
                        LinkOption.NOFOLLOW_LINKS) != null
                                        ? new FileAttribute<?>[] { PosixFilePermissions.asFileAttribute(
                                                        PosixFilePermissions.fromString("rw-------")) } //$NON-NLS-1$
                                        : new FileAttribute<?>[0];
        boolean created = false;
        try
        {
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
            Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            created = false;
        }
        finally
        {
            if (created)
                Files.deleteIfExists(staging);
        }
    }

    private static byte[] digest(byte[] bytes)
    {
        return CompositionCacheCodec.digest(bytes);
    }

    private static byte[] seal(ByteArrayOutputStream bytes) throws IOException
    {
        var body = bytes.toByteArray();
        bytes.write(digest(body));
        if (bytes.size() > MAX_BYTES)
            throw new IOException();
        return bytes.toByteArray();
    }

    private static DataInputStream open(byte[] bytes) throws IOException
    {
        if (bytes.length < 36 || bytes.length > MAX_BYTES)
            throw new IOException();
        var body = Arrays.copyOf(bytes, bytes.length - 32);
        if (!MessageDigest.isEqual(digest(body), Arrays.copyOfRange(bytes, body.length, bytes.length)))
            throw new IOException();
        var in = new DataInputStream(new ByteArrayInputStream(body));
        if (in.readInt() != SCHEMA_VERSION)
            throw new IOException();
        return in;
    }

    private byte[] encodeRegister() throws IOException
    {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes))
        {
            out.writeInt(SCHEMA_VERSION);
            text(out, "register"); //$NON-NLS-1$
            out.writeInt(count(register.size()));
            for (var item : new TreeMap<>(register).entrySet())
            {
                text(out, item.getKey());
                text(out, item.getValue().retrievedAt().toString());
                var value = item.getValue().value();
                out.writeBoolean(value.isPresent());
                if (value.isPresent())
                {
                    text(out, value.get().lei());
                    optional(out, value.get().headquartersCountry());
                    optional(out, value.get().legalCountry());
                }
            }
        }
        return seal(bytes);
    }

    private static Map<String, Cached<RegisterRecord>> decodeRegister(byte[] bytes) throws IOException
    {
        try (var in = open(bytes))
        {
            if (!"register".equals(text(in))) //$NON-NLS-1$
                throw new IOException();
            var size = count(in.readInt());
            var result = new HashMap<String, Cached<RegisterRecord>>();
            for (int index = 0; index < size; index++)
            {
                var isin = text(in);
                var retrieved = Instant.parse(text(in));
                Optional<RegisterRecord> value = Optional.empty();
                if (in.readBoolean())
                    value = Optional.of(new RegisterRecord(isin, text(in), optional(in), optional(in), retrieved));
                if (result.put(isin, new Cached<>(value, retrieved)) != null)
                    throw new IOException();
            }
            if (in.available() != 0)
                throw new IOException();
            return result;
        }
    }

    private static byte[] encodeProfiles(ProviderAccount account, Profiles profiles) throws IOException
    {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes))
        {
            out.writeInt(SCHEMA_VERSION);
            text(out, "profiles"); //$NON-NLS-1$
            text(out, account.providerId());
            text(out, account.accountId());
            var stored = new TreeMap<String, Cached<CompanyProfile>>();
            for (var isin : profiles.storable)
                stored.put(isin, profiles.entries.get(isin));
            out.writeInt(count(stored.size()));
            for (var item : stored.entrySet())
            {
                text(out, item.getKey());
                text(out, item.getValue().retrievedAt().toString());
                var value = item.getValue().value();
                out.writeBoolean(value.isPresent());
                if (value.isPresent())
                {
                    var profile = value.get();
                    optional(out, profile.country());
                    optional(out, profile.currency());
                    optional(out, profile.sector());
                    optional(out, profile.industry());
                    optional(out, profile.marketCapMillions().map(BigDecimal::toPlainString));
                }
            }
        }
        return seal(bytes);
    }

    private static Map<String, Cached<CompanyProfile>> decodeProfiles(ProviderAccount account, byte[] bytes)
                    throws IOException
    {
        try (var in = open(bytes))
        {
            if (!"profiles".equals(text(in)) || !account.equals(new ProviderAccount(text(in), text(in)))) //$NON-NLS-1$
                throw new IOException();
            var size = count(in.readInt());
            var result = new HashMap<String, Cached<CompanyProfile>>();
            for (int index = 0; index < size; index++)
            {
                var isin = text(in);
                var retrieved = Instant.parse(text(in));
                Optional<CompanyProfile> value = Optional.empty();
                if (in.readBoolean())
                    value = Optional.of(new CompanyProfile(isin, optional(in), optional(in), optional(in),
                                    optional(in), optional(in).map(BigDecimal::new), retrieved));
                if (result.put(isin, new Cached<>(value, retrieved)) != null)
                    throw new IOException();
            }
            if (in.available() != 0)
                throw new IOException();
            return result;
        }
    }

    private static int count(int value) throws IOException
    {
        if (value < 0 || value > MAX_ENTRIES)
            throw new IOException();
        return value;
    }

    private static void text(DataOutputStream out, String value) throws IOException
    {
        if (value.length() > MAX_TEXT)
            throw new IOException();
        out.writeUTF(value);
    }

    private static String text(DataInputStream in) throws IOException
    {
        var value = in.readUTF();
        if (value.length() > MAX_TEXT)
            throw new IOException();
        return value;
    }

    private static void optional(DataOutputStream out, Optional<String> value) throws IOException
    {
        out.writeBoolean(value.isPresent());
        if (value.isPresent())
            text(out, value.get());
    }

    private static Optional<String> optional(DataInputStream in) throws IOException
    {
        return in.readBoolean() ? Optional.of(text(in)) : Optional.empty();
    }
}
