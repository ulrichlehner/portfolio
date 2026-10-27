package name.abuchen.portfolio.snapshot.lookthrough;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Bounded model-only format. No Java object deserialization or raw responses. */
final class CompositionCacheCodec
{
    static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final int SCHEMA_VERSION = 1;
    private static final int VALIDATION_VERSION = 1;
    private static final int EVIDENCE_SCHEMA_VERSION = 1;
    private static final int ABSENCE_SCHEMA_VERSION = 1;
    private static final int MAX_EVIDENCE = 8;
    private static final int MAX_ROWS = 10000;
    private static final int MAX_TEXT_BYTES = 4096;

    /** Classification answer for one identifier key; an empty list is a stored "no answer". */
    record StoredEvidence(ProviderAccount account, Map<String, String> key, Instant storedAt,
                    List<InstrumentEvidence> evidence)
    {
    }

    /** A provider answer without composition (unavailable or unsupported) for one fund identifier map. */
    record StoredAbsence(ProviderAccount account, Map<String, String> key, Instant storedAt,
                    CompositionResult.Status status)
    {
    }

    private CompositionCacheCodec()
    {
    }

    static Optional<CompositionValidationResult> validate(FundComposition source)
    {
        try
        {
            if (source.constituents().isEmpty())
                return Optional.empty();
            // Apply the same serialization/resource bounds in BOTH modes.
            encode(source);
            for (var row : source.constituents())
            {
                var text = row.weight().orElse(""); //$NON-NLS-1$
                if (text.length() > 128)
                    return Optional.empty();
                var decimal = new BigDecimal(text);
                if (decimal.precision() > 128 || Math.abs((long) decimal.scale()) > 10000)
                    return Optional.empty();
            }
            var validator = new CompositionValidator();
            var result = validator.validate(source);
            // Provider weights without declared allocation semantics are kept
            // as well; semanticsVerified() keeps them unverified after a restart.
            if (!result.isUsable())
                result = validator.validateArithmetic(source);
            return result.isUsable() ? Optional.of(result) : Optional.empty();
        }
        catch (IOException | IllegalArgumentException e)
        {
            return Optional.empty();
        }
    }

    static byte[] digest(byte[] bytes)
    {
        try
        {
            return MessageDigest.getInstance("SHA-256").digest(bytes); //$NON-NLS-1$
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException(e);
        }
    }

    static byte[] encode(FundComposition source) throws IOException
    {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes))
        {
            out.writeInt(SCHEMA_VERSION);
            out.writeInt(VALIDATION_VERSION);
            text(out, source.provenance().source().providerId());
            text(out, source.provenance().source().accountId());
            instrument(out, source.provenance().fund());
            text(out, source.provenance().retrievedAt().toString());
            text(out, source.provenance().providerRefreshedAt().map(Instant::toString).orElse("")); //$NON-NLS-1$
            text(out, source.provenance().holdingsEffectiveDate().map(LocalDate::toString).orElse("")); //$NON-NLS-1$
            text(out, source.denominator().name());
            text(out, source.exposureBasis().name());
            text(out, source.completeness().name());
            count(source.constituents().size(), MAX_ROWS);
            out.writeInt(source.constituents().size());
            for (var row : source.constituents())
            {
                instrument(out, row.instrument());
                out.writeBoolean(row.weight().isPresent());
                if (row.weight().isPresent())
                    text(out, row.weight().orElseThrow());
                if (bytes.size() > MAX_BYTES - 32)
                    throw new IOException();
            }
        }
        var body = bytes.toByteArray();
        bytes.write(digest(body));
        if (bytes.size() > MAX_BYTES)
            throw new IOException();
        return bytes.toByteArray();
    }

    static FundComposition decode(byte[] bytes) throws IOException
    {
        if (bytes.length < 40 || bytes.length > MAX_BYTES)
            throw new IOException();
        var body = Arrays.copyOf(bytes, bytes.length - 32);
        if (!MessageDigest.isEqual(digest(body), Arrays.copyOfRange(bytes, body.length, bytes.length)))
            throw new IOException();
        try (var in = new DataInputStream(new ByteArrayInputStream(body)))
        {
            if (in.readInt() != SCHEMA_VERSION || in.readInt() != VALIDATION_VERSION)
                throw new IOException();
            var account = new ProviderAccount(text(in), text(in));
            var fund = instrument(in);
            var retrieved = Instant.parse(text(in));
            var refreshed = text(in);
            var effective = text(in);
            var provenance = new CompositionProvenance(account, fund, retrieved,
                            refreshed.isEmpty() ? Optional.empty() : Optional.of(Instant.parse(refreshed)),
                            effective.isEmpty() ? Optional.empty() : Optional.of(LocalDate.parse(effective)));
            var denominator = FundComposition.Denominator.valueOf(text(in));
            var basis = FundComposition.ExposureBasis.valueOf(text(in));
            var completeness = FundComposition.Completeness.valueOf(text(in));
            int size = count(in.readInt(), MAX_ROWS);
            var rows = new ArrayList<FundComposition.Constituent>();
            for (int index = 0; index < size; index++)
                rows.add(new FundComposition.Constituent(instrument(in),
                                in.readBoolean() ? Optional.of(text(in)) : Optional.empty()));
            if (in.available() != 0)
                throw new IOException();
            return new FundComposition(provenance, denominator, basis, completeness, rows);
        }
        catch (RuntimeException e)
        {
            throw new IOException();
        }
    }

    private static void instrument(DataOutputStream out, CompositionInstrument instrument) throws IOException
    {
        text(out, instrument.label());
        identifiers(out, instrument.identifiers());
    }

    private static void identifiers(DataOutputStream out, Map<String, String> values) throws IOException
    {
        count(values.size(), 32);
        out.writeInt(values.size());
        for (var identifier : new TreeMap<>(values).entrySet())
        {
            text(out, identifier.getKey());
            text(out, identifier.getValue());
        }
    }

    private static CompositionInstrument instrument(DataInputStream in) throws IOException
    {
        var label = text(in);
        return new CompositionInstrument(label, identifiers(in));
    }

    private static Map<String, String> identifiers(DataInputStream in) throws IOException
    {
        int size = count(in.readInt(), 32);
        var identifiers = new HashMap<String, String>();
        for (int index = 0; index < size; index++)
        {
            if (identifiers.put(text(in), text(in)) != null)
                throw new IOException();
        }
        return identifiers;
    }

    static byte[] encodeEvidence(StoredEvidence stored) throws IOException
    {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes))
        {
            out.writeInt(EVIDENCE_SCHEMA_VERSION);
            text(out, stored.account().providerId());
            text(out, stored.account().accountId());
            identifiers(out, stored.key());
            text(out, stored.storedAt().toString());
            count(stored.evidence().size(), MAX_EVIDENCE);
            out.writeInt(stored.evidence().size());
            for (var item : stored.evidence())
            {
                identifiers(out, item.identifiers());
                identifier(out, item.identity().value());
                provenance(out, item.identity().provenance());
                out.writeBoolean(item.classification().isPresent());
                if (item.classification().isPresent())
                {
                    text(out, item.classification().orElseThrow().value().name());
                    provenance(out, item.classification().orElseThrow().provenance());
                }
                out.writeBoolean(item.company().isPresent());
                if (item.company().isPresent())
                {
                    identifier(out, item.company().orElseThrow().value());
                    provenance(out, item.company().orElseThrow().provenance());
                }
            }
        }
        var body = bytes.toByteArray();
        bytes.write(digest(body));
        if (bytes.size() > MAX_BYTES)
            throw new IOException();
        return bytes.toByteArray();
    }

    static StoredEvidence decodeEvidence(byte[] bytes) throws IOException
    {
        if (bytes.length < 40 || bytes.length > MAX_BYTES)
            throw new IOException();
        var body = Arrays.copyOf(bytes, bytes.length - 32);
        if (!MessageDigest.isEqual(digest(body), Arrays.copyOfRange(bytes, body.length, bytes.length)))
            throw new IOException();
        try (var in = new DataInputStream(new ByteArrayInputStream(body)))
        {
            if (in.readInt() != EVIDENCE_SCHEMA_VERSION)
                throw new IOException();
            var account = new ProviderAccount(text(in), text(in));
            var key = identifiers(in);
            var storedAt = Instant.parse(text(in));
            int size = count(in.readInt(), MAX_EVIDENCE);
            var list = new ArrayList<InstrumentEvidence>();
            for (int index = 0; index < size; index++)
            {
                var identifiers = identifiers(in);
                var identity = new InstrumentEvidence.Assertion<>(identifier(in), provenance(in));
                Optional<InstrumentEvidence.Assertion<InstrumentEvidence.AssetType>> classification = Optional.empty();
                if (in.readBoolean())
                    classification = Optional.of(new InstrumentEvidence.Assertion<>(
                                    InstrumentEvidence.AssetType.valueOf(text(in)), provenance(in)));
                Optional<InstrumentEvidence.Assertion<InstrumentEvidence.Identifier>> company = Optional.empty();
                if (in.readBoolean())
                    company = Optional.of(new InstrumentEvidence.Assertion<>(identifier(in), provenance(in)));
                list.add(new InstrumentEvidence(identifiers, identity, classification, company));
            }
            if (in.available() != 0)
                throw new IOException();
            return new StoredEvidence(account, key, storedAt, List.copyOf(list));
        }
        catch (RuntimeException e)
        {
            throw new IOException();
        }
    }

    static byte[] encodeAbsence(StoredAbsence stored) throws IOException
    {
        if (stored.status() != CompositionResult.Status.UNAVAILABLE
                        && stored.status() != CompositionResult.Status.UNSUPPORTED)
            throw new IOException();
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes))
        {
            out.writeInt(ABSENCE_SCHEMA_VERSION);
            text(out, stored.account().providerId());
            text(out, stored.account().accountId());
            identifiers(out, stored.key());
            text(out, stored.storedAt().toString());
            text(out, stored.status().name());
        }
        var body = bytes.toByteArray();
        bytes.write(digest(body));
        return bytes.toByteArray();
    }

    static StoredAbsence decodeAbsence(byte[] bytes) throws IOException
    {
        if (bytes.length < 40 || bytes.length > MAX_BYTES)
            throw new IOException();
        var body = Arrays.copyOf(bytes, bytes.length - 32);
        if (!MessageDigest.isEqual(digest(body), Arrays.copyOfRange(bytes, body.length, bytes.length)))
            throw new IOException();
        try (var in = new DataInputStream(new ByteArrayInputStream(body)))
        {
            if (in.readInt() != ABSENCE_SCHEMA_VERSION)
                throw new IOException();
            var account = new ProviderAccount(text(in), text(in));
            var key = identifiers(in);
            var storedAt = Instant.parse(text(in));
            var status = CompositionResult.Status.valueOf(text(in));
            if (in.available() != 0 || (status != CompositionResult.Status.UNAVAILABLE
                            && status != CompositionResult.Status.UNSUPPORTED))
                throw new IOException();
            return new StoredAbsence(account, key, storedAt, status);
        }
        catch (RuntimeException e)
        {
            throw new IOException();
        }
    }

    private static void identifier(DataOutputStream out, InstrumentEvidence.Identifier identifier) throws IOException
    {
        text(out, identifier.namespace());
        text(out, identifier.value());
    }

    private static InstrumentEvidence.Identifier identifier(DataInputStream in) throws IOException
    {
        return new InstrumentEvidence.Identifier(text(in), text(in));
    }

    private static void provenance(DataOutputStream out, InstrumentEvidence.Provenance provenance) throws IOException
    {
        text(out, provenance.source().providerId());
        text(out, provenance.source().accountId());
        text(out, provenance.reference());
        text(out, provenance.retrievedAt().toString());
    }

    private static InstrumentEvidence.Provenance provenance(DataInputStream in) throws IOException
    {
        return new InstrumentEvidence.Provenance(new ProviderAccount(text(in), text(in)), text(in),
                        Instant.parse(text(in)));
    }

    private static int count(int value, int maximum) throws IOException
    {
        if (value < 0 || value > maximum)
            throw new IOException();
        return value;
    }

    private static void text(DataOutputStream out, String value) throws IOException
    {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        count(bytes.length, MAX_TEXT_BYTES);
        if (!value.equals(new String(bytes, StandardCharsets.UTF_8)))
            throw new IOException();
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String text(DataInputStream in) throws IOException
    {
        int size = count(in.readInt(), MAX_TEXT_BYTES);
        var bytes = in.readNBytes(size);
        if (bytes.length != size)
            throw new IOException();
        var text = new String(bytes, StandardCharsets.UTF_8);
        if (!Arrays.equals(bytes, text.getBytes(StandardCharsets.UTF_8)))
            throw new IOException();
        return text;
    }
}
